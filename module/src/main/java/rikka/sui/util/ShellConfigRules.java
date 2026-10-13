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

package rikka.sui.util;

/**
 * Rules for locating and validating the shell server's working directory.
 *
 * <p>The root server (uid 0) and the shell server (uid 2000) cannot share a
 * directory they can both write, so the root server publishes the shell workdir
 * name through a marker file and both sides resolve it independently.
 *
 * <p><b>These rules are duplicated in native code</b> in
 * {@code module/src/main/cpp/main/sui_main.hpp} ({@code is_valid_shell_dir_name}
 * and {@code resolve_shell_dir_path}). The two implementations must agree
 * exactly: a name accepted by one and rejected by the other leaves the shell
 * server unable to read its own {@code sui_uids.txt}, which silently drops every
 * app's shell permission. Any change here must be mirrored there, and the shared
 * cases belong in {@code ShellConfigRulesTest}.
 *
 * <p>This class deliberately has no Android dependencies so that it can be
 * exercised by a plain JVM unit test. Do not introduce {@code android.*} imports,
 * {@code java.io.File}, or any framework type here.
 */
public final class ShellConfigRules {

    /** Legacy fixed directory name. Still accepted so existing installs keep working. */
    public static final String LEGACY_SHELL_DIR_NAME = "sui_shell";

    /** Prefix for the randomized directory names used since the legacy path was deprecated. */
    public static final String SHELL_DIR_PREFIX = "sui_shell_";

    /** Parent directory that holds the shell workdir. */
    public static final String SHELL_BASE_DIR = "/data/local/tmp";

    /** File the root server writes the shell workdir name into. */
    public static final String SHELL_DIR_MARKER = "/data/adb/sui/shell_dir_name";

    private ShellConfigRules() {}

    /**
     * Whether {@code name} is a usable shell workdir name.
     *
     * <p>Accepts the legacy name verbatim, or a {@link #SHELL_DIR_PREFIX} plus a
     * suffix restricted to {@code [0-9a-z_-]}. The narrowed alphabet is not
     * cosmetic: the resolved path is handed to shell scripts and used as a
     * filename, so {@code /}, {@code .}, and shell metacharacters must never
     * survive validation.
     *
     * @param name candidate name; may be null
     * @return true if the name may be joined onto {@link #SHELL_BASE_DIR}
     */
    public static boolean isValidShellDirName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }

        if (LEGACY_SHELL_DIR_NAME.equals(name)) {
            return true;
        }

        if (!name.startsWith(SHELL_DIR_PREFIX)) {
            return false;
        }

        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || c == '_' || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /**
     * Resolves the marker file's content into an absolute shell workdir path.
     *
     * <p>Accepts either an absolute path under {@link #SHELL_BASE_DIR} or a bare
     * name to be joined onto it. Returns null for anything else, including a
     * directory that lives outside the base dir, so a hand-edited or
     * attacker-written marker cannot redirect the shell server to an arbitrary
     * location.
     *
     * @param markerValue raw content of the marker file; may be null or blank
     * @return absolute path, or null if the marker is unusable
     */
    public static String resolveShellDir(String markerValue) {
        if (markerValue == null) {
            return null;
        }

        String value = markerValue.trim();
        if (value.isEmpty()) {
            return null;
        }

        String basePrefix = SHELL_BASE_DIR + "/";
        if (value.startsWith(basePrefix)) {
            if (value.length() <= basePrefix.length()) {
                return null;
            }
            String name = value.substring(basePrefix.length());
            return isValidShellDirName(name) ? value : null;
        }

        return isValidShellDirName(value) ? SHELL_BASE_DIR + "/" + value : null;
    }

    /**
     * Whether {@code name} refers to the deprecated fixed directory.
     *
     * <p>The root server migrates away from this name on sight, because a
     * predictable path lets any app find and tamper with the exported
     * {@code sui_uids.txt}.
     */
    public static boolean isLegacyShellDirName(String name) {
        return LEGACY_SHELL_DIR_NAME.equals(name);
    }
}
