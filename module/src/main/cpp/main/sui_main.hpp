/*
 * This file is part of Sui.
 *
 * Sui is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Sui is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Sui.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (c) 2021-2026 Sui Contributors
 */

#include <cstdlib>
#include <cstring>
#include <logging.h>
#include <unistd.h>
#include <sched.h>
#include <app_process.h>
#include <misc.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <selinux.h>
#include <string>
#include <dirent.h>

// The string constants below, and the rules in is_valid_shell_dir_name() /
// resolve_shell_dir_path(), are duplicated from
// module/src/main/java/rikka/sui/util/ShellConfigRules.java.
//
// The duplication is unavoidable -- this is native code and that is the ART-side
// definition -- but it is also dangerous: if the two ever disagree, the root
// server publishes a directory name the shell server refuses to resolve, and
// every app silently loses its shell permission. ShellConfigRulesTest reads this
// header and compares the literals, so edit both sides together.
static constexpr const char* SUI_DATA_DIR = "/data/adb/sui";
static constexpr const char* LEGACY_SHELL_DIR_NAME = "sui_shell";
static constexpr const char* LEGACY_SHELL_DIR = "/data/local/tmp/sui_shell";
static constexpr const char* SHELL_BASE_DIR = "/data/local/tmp";
static constexpr const char* SHELL_DIR_PREFIX = "sui_shell_";
static constexpr const char* SHELL_DIR_MARKER = "/data/adb/sui/shell_dir_name";

static std::string trim_copy(const std::string& input) {
    size_t begin = 0;
    size_t end = input.size();
    while (begin < end && (input[begin] == ' ' || input[begin] == '\n' || input[begin] == '\r' ||
                           input[begin] == '\t')) {
        ++begin;
    }
    while (end > begin && (input[end - 1] == ' ' || input[end - 1] == '\n' ||
                           input[end - 1] == '\r' || input[end - 1] == '\t')) {
        --end;
    }
    return input.substr(begin, end - begin);
}

static bool is_valid_shell_dir_name(const std::string& name) {
    if (name == LEGACY_SHELL_DIR_NAME) {
        return true;
    }
    if (name.rfind(SHELL_DIR_PREFIX, 0) != 0) {
        return false;
    }
    for (char c : name) {
        bool ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || c == '_' || c == '-';
        if (!ok)
            return false;
    }
    return true;
}

static bool read_file_line(const char* path, std::string& out) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0)
        return false;
    // Read to EOF instead of one fixed size chunk: a truncated read would return half of the
    // directory name, and the shell server would then resolve a directory that does not exist.
    std::string content;
    char buf[512];
    ssize_t r;
    while ((r = read(fd, buf, sizeof(buf))) > 0) {
        content.append(buf, static_cast<size_t>(r));
        if (content.size() > 4096) {
            break;
        }
    }
    close(fd);
    if (content.empty())
        return false;
    out = trim_copy(content);
    return !out.empty();
}

static int write_text_atomic(const char* path, const std::string& content) {
    std::string tmp_path = std::string(path) + ".tmp";
    int fd = open(tmp_path.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0)
        return -1;
    if (write_full(fd, content.data(), content.size()) != 0 || fsync(fd) != 0) {
        close(fd);
        unlink(tmp_path.c_str());
        return -1;
    }
    close(fd);
    if (rename(tmp_path.c_str(), path) != 0) {
        unlink(tmp_path.c_str());
        return -1;
    }
    return 0;
}

static std::string generate_shell_dir_name() {
    unsigned char random_bytes[8]{0};
    int fd = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
    if (fd >= 0) {
        if (read_full(fd, random_bytes, sizeof(random_bytes)) != 0) {
            memset(random_bytes, 0, sizeof(random_bytes));
        }
        close(fd);
    }
    char suffix[17]{0};
    static constexpr const char* hex = "0123456789abcdef";
    for (size_t i = 0; i < sizeof(random_bytes); ++i) {
        suffix[i * 2] = hex[(random_bytes[i] >> 4) & 0x0f];
        suffix[i * 2 + 1] = hex[random_bytes[i] & 0x0f];
    }
    return std::string(SHELL_DIR_PREFIX) + suffix;
}

static std::string generate_unique_shell_dir_name() {
    for (int i = 0; i < 8; ++i) {
        std::string name = generate_shell_dir_name();
        std::string path = std::string(SHELL_BASE_DIR) + "/" + name;
        if (access(path.c_str(), F_OK) != 0) {
            return name;
        }
    }
    char fallback[64]{0};
    snprintf(fallback, sizeof(fallback), "%s%ld", SHELL_DIR_PREFIX, static_cast<long>(getpid()));
    return std::string(fallback);
}

// Best effort recursive removal, bounded in depth and never following symlinks: the legacy
// directory can contain sub directories, and leaving them behind means rmdir() always fails and
// the legacy directory (with its world readable uid list) stays around forever.
static void remove_path_recursive_best_effort(const std::string& path, int depth) {
    struct stat st{};
    if (lstat(path.c_str(), &st) != 0) {
        return;
    }
    if (!S_ISDIR(st.st_mode)) {
        if (unlink(path.c_str()) != 0) {
            LOGW("remove legacy file failed: %s (%d: %s)", path.c_str(), errno, strerror(errno));
        }
        return;
    }
    if (depth > 4) {
        LOGW("legacy directory nesting too deep, giving up: %s", path.c_str());
        return;
    }
    if (DIR* dir = opendir(path.c_str())) {
        struct dirent* entry = nullptr;
        while ((entry = readdir(dir)) != nullptr) {
            if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0) {
                continue;
            }
            remove_path_recursive_best_effort(path + "/" + entry->d_name, depth + 1);
        }
        closedir(dir);
    }
    if (rmdir(path.c_str()) != 0) {
        LOGW("remove legacy directory failed: %s (%d: %s)", path.c_str(), errno, strerror(errno));
    }
}

static void cleanup_legacy_shell_dir_best_effort() {
    struct stat st{};
    if (lstat(LEGACY_SHELL_DIR, &st) != 0 || !S_ISDIR(st.st_mode)) {
        // Missing, or a symlink planted by someone else: never follow it.
        return;
    }

    DIR* dir = opendir(LEGACY_SHELL_DIR);
    if (dir == nullptr) {
        return;
    }

    struct dirent* entry = nullptr;
    while ((entry = readdir(dir)) != nullptr) {
        if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0) {
            continue;
        }
        std::string path = std::string(LEGACY_SHELL_DIR) + "/" + entry->d_name;
        remove_path_recursive_best_effort(path, 0);
    }
    closedir(dir);

    if (rmdir(LEGACY_SHELL_DIR) != 0) {
        LOGW("remove legacy shell dir failed: %s (%d: %s)", LEGACY_SHELL_DIR, errno,
             strerror(errno));
    } else {
        LOGI("legacy shell dir removed: %s", LEGACY_SHELL_DIR);
    }
}

static std::string migrate_legacy_shell_dir() {
    std::string new_name = generate_unique_shell_dir_name();
    std::string new_path = std::string(SHELL_BASE_DIR) + "/" + new_name;
    if (ensure_dir(new_path.c_str(), 0755) != 0) {
        LOGW("create migrated shell dir failed with %d: %s", errno, strerror(errno));
        return LEGACY_SHELL_DIR;
    }

    std::string old_uids = std::string(LEGACY_SHELL_DIR) + "/sui_uids.txt";
    std::string new_uids = new_path + "/sui_uids.txt";
    if (access(old_uids.c_str(), F_OK) == 0) {
        if (copyfile(old_uids.c_str(), new_uids.c_str()) == 0) {
            // The uid list is the full list of granted apps; only the shell server needs it.
            chmod(new_uids.c_str(), 0600);
            chown(new_uids.c_str(), 2000, 2000);
        } else {
            LOGW("copy legacy sui_uids.txt failed with %d: %s", errno, strerror(errno));
        }
    }

    if (write_text_atomic(SHELL_DIR_MARKER, new_name + "\n") != 0) {
        LOGW("write migrated shell dir marker failed with %d: %s", errno, strerror(errno));
        return LEGACY_SHELL_DIR;
    }

    cleanup_legacy_shell_dir_best_effort();
    LOGI("migrated legacy shell dir to %s", new_path.c_str());
    return new_path;
}

static std::string resolve_shell_dir_path() {
    std::string marker;
    if (read_file_line(SHELL_DIR_MARKER, marker)) {
        std::string marker_trimmed = trim_copy(marker);
        size_t base_len = strlen(SHELL_BASE_DIR);
        if (marker_trimmed.rfind(SHELL_BASE_DIR, 0) == 0 && marker_trimmed.size() > base_len + 1 &&
            marker_trimmed[base_len] == '/') {
            std::string name = marker_trimmed.substr(base_len + 1);
            if (is_valid_shell_dir_name(name)) {
                if (name == LEGACY_SHELL_DIR_NAME) {
                    return migrate_legacy_shell_dir();
                }
                return marker_trimmed;
            }
        } else if (is_valid_shell_dir_name(marker_trimmed)) {
            if (marker_trimmed == LEGACY_SHELL_DIR_NAME) {
                return migrate_legacy_shell_dir();
            }
            return std::string(SHELL_BASE_DIR) + "/" + marker_trimmed;
        }
        LOGW("Invalid shell dir marker content: %s", marker_trimmed.c_str());
    }

    if (access(LEGACY_SHELL_DIR, F_OK) == 0) {
        return migrate_legacy_shell_dir();
    }

    std::string new_name = generate_unique_shell_dir_name();
    if (write_text_atomic(SHELL_DIR_MARKER, new_name + "\n") != 0) {
        LOGW("write randomized shell dir marker failed with %d: %s", errno, strerror(errno));
        return LEGACY_SHELL_DIR;
    }
    return std::string(SHELL_BASE_DIR) + "/" + new_name;
}

static bool build_module_path(char* buffer, size_t buffer_size, const char* root_path,
                              const char* suffix) {
    int written = snprintf(buffer, buffer_size, "%s%s", root_path, suffix);
    if (written < 0 || static_cast<size_t>(written) >= buffer_size) {
        errno = ENAMETOOLONG;
        return false;
    }
    return true;
}

/*
 * argv[1]: path of the module, such as /data/adb/modules/zygisk-sui
 */
static int sui_main(int argc, char** argv) {
    LOGI("Sui starter begin: %s", argv[1]);

    if (daemon(false, false) != 0) {
        PLOGE("daemon");
        return EXIT_FAILURE;
    }

    {
        int fd = open("/proc/self/oom_score_adj", O_WRONLY | O_CLOEXEC);
        if (fd >= 0) {
            const char value[] = "-1000";
            if (write_full(fd, value, sizeof(value) - 1) != 0) {
                LOGW("write /proc/self/oom_score_adj failed with %d: %s", errno, strerror(errno));
            }
            close(fd);
        } else {
            LOGW("open /proc/self/oom_score_adj failed with %d: %s", errno, strerror(errno));
        }
    }

    wait_for_zygote();

    if (access(SUI_DATA_DIR, F_OK) != 0) {
        mkdir(SUI_DATA_DIR, 0700);
    }
    chmod(SUI_DATA_DIR, 0700);
    chown(SUI_DATA_DIR, 0, 0);

    auto root_path = argv[1];

    char dex_path[PATH_MAX]{0};
    if (!build_module_path(dex_path, sizeof(dex_path), root_path, "/sui.dex")) {
        PLOGE("build_module_path %s/sui.dex", root_path);
        return EXIT_FAILURE;
    }

    // Resolve and persist shell workdir before forking so root/shell servers share
    // the same directory from the first startup tick.
    std::string shell_dir_path = resolve_shell_dir_path();

    pid_t pid = fork();
    if (pid < 0) {
        PLOGE("fork");
        return EXIT_FAILURE;
    }

    if (pid == 0) {
        // Child process -> Shell Server
        // uid 2000 cannot read /data/adb/modules/zygisk-sui/sui.dex or .so libraries
        const char* shell_dir = shell_dir_path.c_str();
        ensure_dir(shell_dir, 0755);
        chmod(shell_dir, 0755);
        chown(shell_dir, 2000, 2000);

        char shell_dex_path[PATH_MAX];
        snprintf(shell_dex_path, PATH_MAX, "%s/sui.dex", shell_dir);
        if (copyfile(dex_path, shell_dex_path) == 0) {
            // Only the shell server (uid 2000) reads this, so it does not need to be readable
            // by every app on the device. The .so files stay 0644 on purpose: apps load
            // librish.so themselves.
            chmod(shell_dex_path, 0600);
            chown(shell_dex_path, 2000, 2000);
        }

        char lib_path[PATH_MAX];
        snprintf(lib_path, PATH_MAX, "%s/librish.so", root_path);
        char shell_lib_path[PATH_MAX];
        snprintf(shell_lib_path, PATH_MAX, "%s/librish.so", shell_dir);
        if (copyfile(lib_path, shell_lib_path) == 0) {
            chmod(shell_lib_path, 0644);
            chown(shell_lib_path, 2000, 2000);
        }

        char libsui_path[PATH_MAX];
        snprintf(libsui_path, PATH_MAX, "%s/libsui.so", root_path);
        char shell_libsui_path[PATH_MAX];
        snprintf(shell_libsui_path, PATH_MAX, "%s/libsui.so", shell_dir);
        if (copyfile(libsui_path, shell_libsui_path) == 0) {
            chmod(shell_libsui_path, 0644);
            chown(shell_libsui_path, 2000, 2000);
        }

        // Set SELinux context to shell BEFORE dropping UID/GID (requires root privileges)
        if (setcon("u:r:shell:s0") != 0) {
            PLOGE("setcon u:r:shell:s0");
            exit(EXIT_FAILURE);
        }

        // Set GID to shell (2000)
        if (setresgid(2000, 2000, 2000) != 0) {
            PLOGE("setresgid 2000");
            exit(EXIT_FAILURE);
        }

        // Set UID to shell (2000)
        if (setresuid(2000, 2000, 2000) != 0) {
            PLOGE("setresuid 2000");
            exit(EXIT_FAILURE);
        }

        app_process(shell_dex_path, shell_dir, "rikka.sui.server.Starter", "sui_shell", "--shell");
        exit(EXIT_FAILURE);
    } else {
        // Parent process -> Root Server
        app_process(dex_path, root_path, "rikka.sui.server.Starter", "sui");
        exit(EXIT_FAILURE);
    }

    return EXIT_SUCCESS;
}
