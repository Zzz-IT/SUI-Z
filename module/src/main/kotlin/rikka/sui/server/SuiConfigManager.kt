package rikka.sui.server

import android.os.FileObserver
import android.os.Handler
import android.os.HandlerThread
import android.system.Os
import rikka.shizuku.server.ConfigManager
import rikka.sui.server.ServerConstants.LOGGER
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.FileReader
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class SuiConfigManager : ConfigManager() {

    companion object {
        const val DEFAULT_UID = -1
        private const val FLAG_GLOBAL_SETTINGS_INITIALIZED = 1 shl 30
        private const val FLAG_MONET_DISABLED = 1 shl 1
        private const val LEGACY_SHELL_DIR = "/data/local/tmp/sui_shell"
        private const val SHELL_BASE_DIR = "/data/local/tmp"
        private const val SHELL_DIR_MARKER = "/data/adb/sui/shell_dir_name"
        private const val SHELL_CONFIG_FILENAME = "sui_uids.txt"
        private const val BRIDGE_TOKEN_FILENAME = "bridge_token"
        private const val BRIDGE_TOKEN_TMP_FILENAME = "bridge_token.tmp"
        private const val SHELL_DIR_PREFIX = "sui_shell_"
        private const val SHELL_SYNC_DEBOUNCE_MS = 200L

        private val SHELL_SYNC_THREAD = HandlerThread("sui-shell-sync")
        private val SHELL_SYNC_HANDLER: Handler

        private var shellConfigObserver: FileObserver? = null

        @JvmStatic
        var instance: SuiConfigManager? = null
            private set

        const val UID_GLOBAL_SETTINGS = -2

        init {
            SHELL_SYNC_THREAD.start()
            SHELL_SYNC_HANDLER = Handler(SHELL_SYNC_THREAD.looper)
        }

        private fun isValidShellDirName(name: String): Boolean {
            if ("sui_shell" == name) {
                return true
            }
            if (!name.startsWith(SHELL_DIR_PREFIX)) {
                return false
            }
            for (i in name.indices) {
                val c = name[i]
                val ok = (c in '0'..'9') || (c in 'a'..'z') || c == '_' || c == '-'
                if (!ok) {
                    return false
                }
            }
            return true
        }

        private fun resolveShellDirFromMarkerValue(value: String): File? {
            if (value.startsWith("$SHELL_BASE_DIR/")) {
                val name = value.substring(SHELL_BASE_DIR.length + 1)
                if (isValidShellDirName(name)) {
                    return File(value)
                }
                return null
            }
            if (isValidShellDirName(value)) {
                return File(SHELL_BASE_DIR, value)
            }
            return null
        }

        @JvmStatic
        fun load(): SuiConfig {
            if (SuiService.isShellMode()) {
                LOGGER.i("SuiConfigManager: shell mode, starting with empty config and setting up FileObserver")
                return SuiConfig()
            }
            val config = SuiDatabase.readConfig()
            if (config == null) {
                LOGGER.e("SuiConfigManager: failed to read database, starting empty")
                return SuiConfig()
            }
            LOGGER.i("SuiConfigManager: Loaded ${config.packages.size} packages from database.")
            return config
        }
    }

    private val shellSyncVersion = AtomicLong()
    private val config: SuiConfig
    private val packageIndex = HashMap<Int, SuiConfig.PackageEntry>()
    private val syncUidsToShellFileRunnable = Runnable { syncUidsToShellFile() }
    private var hiddenUidsCache: IntArray? = null
    private var rootUidsCache: IntArray? = null
    private var deniedUidsCache: IntArray? = null
    private var shellUidsCache: IntArray? = null
    private var shortcutToken: String? = null

    init {
        synchronized(SuiConfigManager::class.java) {
            instance = this
        }
        this.config = load()
        synchronized(this) {
            rebuildPackageIndexLocked()
        }
        if (SuiService.isShellMode()) {
            reloadShellConfigFromFile()
            if (shellConfigObserver == null) {
                shellConfigObserver = createShellConfigObserver()
                shellConfigObserver?.startWatching()
            }
        } else {
            syncUidsToShellFile()
        }
    }

    private fun getShellDir(): File {
        val runtimePath = if (SuiService.isShellMode()) SuiService.getFilesPath() else null
        if (!runtimePath.isNullOrEmpty()) {
            return File(runtimePath)
        }

        val marker = File(SHELL_DIR_MARKER)
        if (marker.exists() && marker.length() > 0) {
            try {
                BufferedReader(FileReader(marker)).use { br ->
                    val line = br.readLine()
                    if (line != null) {
                        val value = line.trim()
                        if (value.isNotEmpty()) {
                            val resolved = resolveShellDirFromMarkerValue(value)
                            if (resolved != null) {
                                return resolved
                            }
                            LOGGER.w("Invalid shell dir marker content: %s", value)
                        }
                    }
                }
            } catch (e: Throwable) {
                LOGGER.w(e, "Failed to read shell dir marker, fallback to legacy path")
            }
        }
        return File(LEGACY_SHELL_DIR)
    }

    private fun getShellConfigFile(): File {
        return File(getShellDir(), SHELL_CONFIG_FILENAME)
    }

    private fun rebuildPackageIndexLocked() {
        packageIndex.clear()
        for (entry in config.packages) {
            packageIndex[entry.uid] = entry
        }
    }

    private fun invalidateUidCacheLocked() {
        hiddenUidsCache = null
        rootUidsCache = null
        deniedUidsCache = null
        shellUidsCache = null
    }

    private fun reloadShellConfigFromFile() {
        val file = getShellConfigFile()
        if (!file.exists()) {
            return
        }

        val parsed = ArrayList<SuiConfig.PackageEntry>()

        try {
            BufferedReader(FileReader(file)).use { br ->
                var line: String?
                var lineNo = 0

                while (br.readLine().also { line = it } != null) {
                    lineNo++
                    line = line!!.trim()

                    if (line!!.isEmpty()) {
                        continue
                    }

                    val parts = line!!.split(":")
                    if (parts.size != 2) {
                        throw IOException("bad shell config line \$lineNo: \$line")
                    }

                    val uid = parts[0].toInt()
                    val flags = parts[1].toInt() and SuiConfig.MASK_PERMISSION

                    if (uid < 10000 && uid != 1000 && uid != 2000) {
                        LOGGER.w("ignore invalid uid in shell config: %d", uid)
                        continue
                    }

                    parsed.add(SuiConfig.PackageEntry(uid, flags))
                }

                synchronized(this) {
                    config.packages.clear()
                    config.packages.addAll(parsed)
                    rebuildPackageIndexLocked()
                    invalidateUidCacheLocked()
                }

                refreshClientAllowedStateAfterShellReload()
                LOGGER.i("Shell server reloaded config, apps: %d", parsed.size)
            }
        } catch (e: Throwable) {
            LOGGER.e(e, "reload shell config failed, keep previous config")
        }
    }

    private fun refreshClientAllowedStateAfterShellReload() {
        val service = SuiService.getInstance()
        if (service == null || service.clientManager == null) {
            return
        }

        for (record in service.clientManager.clients) {
            val entry = find(record.uid)
            val allowed = entry != null && ((entry.flags and (SuiConfig.FLAG_ALLOWED or SuiConfig.FLAG_ALLOWED_SHELL)) != 0)
            record.allowed = allowed
        }
    }

    fun reloadShellConfig() {
        if (SuiService.isShellMode()) {
            reloadShellConfigFromFile()
        }
    }

    private fun syncUidsToShellFile() {
        if (SuiService.isShellMode()) return
        try {
            val sb = java.lang.StringBuilder()
            synchronized(this) {
                for (entry in config.packages) {
                    sb.append(entry.uid).append(":").append(entry.flags).append('\n')
                }
            }
            val dir = getShellDir()
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, SHELL_CONFIG_FILENAME)
            val tempFile = File(dir, SHELL_CONFIG_FILENAME + ".tmp")
            FileOutputStream(tempFile).use { fos ->
                fos.write(sb.toString().toByteArray(StandardCharsets.UTF_8))
                fos.fd.sync()
            }

            if (tempFile.renameTo(file)) {
                Os.chmod(file.absolutePath, 0x1a4) // 0644
            } else {
                LOGGER.w("rename shell config temp file failed: %s -> %s", tempFile, file)
            }
        } catch (e: Throwable) {
            LOGGER.e(e, "sync uids to shell")
        }
    }

    fun syncUidsToShellFileAsync(afterSync: Runnable?) {
        if (SuiService.isShellMode()) {
            return
        }

        val version = shellSyncVersion.incrementAndGet()

        SHELL_SYNC_HANDLER.removeCallbacks(syncUidsToShellFileRunnable)
        SHELL_SYNC_HANDLER.post {
            if (version != shellSyncVersion.get()) {
                return@post
            }
            try {
                syncUidsToShellFile()
            } catch (e: Throwable) {
                LOGGER.w(e, "syncUidsToShellFileAsync error")
            } finally {
                if (afterSync != null && version == shellSyncVersion.get()) {
                    try {
                        afterSync.run()
                    } catch (e: Throwable) {
                        LOGGER.w(e, "after shell sync callback failed")
                    }
                }
            }
        }
    }

    fun syncUidsToShellFileNow() {
        if (SuiService.isShellMode()) return
        shellSyncVersion.incrementAndGet()
        SHELL_SYNC_HANDLER.removeCallbacks(syncUidsToShellFileRunnable)
        SHELL_SYNC_HANDLER.post {
            try {
                syncUidsToShellFile()
            } catch (e: Throwable) {
                LOGGER.w(e, "syncUidsToShellFileNow error")
            }
        }
    }

    private fun scheduleSyncUidsToShellFile() {
        if (SuiService.isShellMode()) return
        SHELL_SYNC_HANDLER.removeCallbacks(syncUidsToShellFileRunnable)
        SHELL_SYNC_HANDLER.postDelayed(syncUidsToShellFileRunnable, SHELL_SYNC_DEBOUNCE_MS)
    }

    private fun createShellConfigObserver(): FileObserver {
        val shellDir = getShellDir()
        @Suppress("DEPRECATION")
        return object : FileObserver(shellDir.absolutePath, CLOSE_WRITE or MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                if (SHELL_CONFIG_FILENAME == path) {
                    reloadShellConfigFromFile()
                }
            }
        }
    }

    var globalSettings: Int
        get() {
            synchronized(this) {
                val entry = findLocked(UID_GLOBAL_SETTINGS)
                if (entry == null) {
                    return FLAG_MONET_DISABLED
                }
                var flags = entry.flags and FLAG_GLOBAL_SETTINGS_INITIALIZED.inv()
                if ((entry.flags and FLAG_GLOBAL_SETTINGS_INITIALIZED) == 0) {
                    flags = flags or FLAG_MONET_DISABLED
                }
                return flags
            }
        }
        set(flags) {
            update(UID_GLOBAL_SETTINGS, 0xFFFFFFFF.toInt(), flags or FLAG_GLOBAL_SETTINGS_INITIALIZED)
        }

    private fun findLocked(uid: Int): SuiConfig.PackageEntry? {
        return packageIndex[uid]
    }

    fun findExplicit(uid: Int): SuiConfig.PackageEntry? {
        synchronized(this) {
            return findLocked(uid)
        }
    }

    fun find(uid: Int): SuiConfig.PackageEntry? {
        synchronized(this) {
            if (uid == 0 || uid == 1000) {
                return SuiConfig.PackageEntry(uid, SuiConfig.FLAG_ALLOWED)
            }
            val entry = findLocked(uid)
            if (uid == DEFAULT_UID) {
                return entry
            }
            if (entry != null && entry.flags != 0) {
                LOGGER.d("SuiConfigManager: Found explicit flags for uid %d: %d", uid, entry.flags)
                return entry
            }
            val defaultEntry = findLocked(DEFAULT_UID)
            if (defaultEntry == null || defaultEntry.flags == 0) {
                return null
            }
            LOGGER.d("SuiConfigManager: Using DEFAULT flags for uid %d. Flags: %d", uid, defaultEntry.flags)
            return SuiConfig.PackageEntry(uid, defaultEntry.flags)
        }
    }

    override fun update(uid: Int, packages: List<String>, mask: Int, values: Int) {
        update(uid, mask, values)
    }

    fun update(uid: Int, mask: Int, values: Int) {
        LOGGER.i("SuiConfigManager: update uid=$uid mask=$mask val=$values")
        var needRemove = false
        var needUpdate = false
        var finalFlags = 0

        synchronized(this) {
            var entry = findLocked(uid)
            if (entry == null) {
                val newValue = mask and values
                if (newValue == 0) {
                    return
                }
                entry = SuiConfig.PackageEntry(uid, newValue)
                config.packages.add(entry)
                packageIndex[uid] = entry
                invalidateUidCacheLocked()
                needUpdate = true
                finalFlags = newValue
                LOGGER.i("SuiConfigManager: Added new entry for uid $uid")
            } else {
                val newValue = (entry!!.flags and mask.inv()) or (mask and values)
                if (newValue == entry!!.flags) {
                    return
                }
                if (newValue == 0) {
                    config.packages.remove(entry)
                    packageIndex.remove(uid)
                    invalidateUidCacheLocked()
                    needRemove = true
                    LOGGER.i("SuiConfigManager: Removed entry for uid $uid")
                } else {
                    entry!!.flags = newValue
                    invalidateUidCacheLocked()
                    needUpdate = true
                    finalFlags = newValue
                    LOGGER.i("SuiConfigManager: Updated entry for uid $uid")
                }
            }
        }
        if (needRemove) {
            if (!SuiService.isShellMode()) SuiDatabase.removeUid(uid)
        } else if (needUpdate) {
            if (!SuiService.isShellMode()) SuiDatabase.updateUid(uid, finalFlags)
        }
        scheduleSyncUidsToShellFile()
    }

    override fun remove(uid: Int) {
        var needRemove = false
        synchronized(this) {
            val entry = findLocked(uid)
            if (entry != null) {
                config.packages.remove(entry)
                packageIndex.remove(uid)
                invalidateUidCacheLocked()
                needRemove = true
            }
        }
        if (needRemove) {
            if (!SuiService.isShellMode()) SuiDatabase.removeUid(uid)
        }
        scheduleSyncUidsToShellFile()
    }

    fun isHidden(uid: Int): Boolean {
        val entry = find(uid)
        if (entry == null) {
            return false
        }
        return (entry.flags and SuiConfig.FLAG_HIDDEN) != 0
    }

    var defaultPermissionFlags: Int
        get() {
            synchronized(this) {
                val entry = findLocked(DEFAULT_UID)
                if (entry == null) {
                    return 0
                }
                return entry.flags and SuiConfig.MASK_PERMISSION
            }
        }
        set(flags) {
            LOGGER.i("SuiConfigManager: Setting default permission flags: $flags")
            val value = flags and SuiConfig.MASK_PERMISSION
            if (value == 0) {
                remove(DEFAULT_UID)
            } else {
                update(DEFAULT_UID, SuiConfig.MASK_PERMISSION, value)
            }
        }

    private fun buildUidsByFlagLocked(flag: Int): IntArray {
        val uids = ArrayList<Int>()
        for (entry in config.packages) {
            if (entry.uid >= 10000 && (entry.flags and flag) != 0) {
                uids.add(entry.uid)
            }
        }
        val res = IntArray(uids.size)
        for (i in uids.indices) {
            res[i] = uids[i]
        }
        return res
    }

    private fun getUidsByFlagLocked(flag: Int): IntArray {
        if (flag == SuiConfig.FLAG_HIDDEN) {
            if (hiddenUidsCache == null) hiddenUidsCache = buildUidsByFlagLocked(flag)
            return hiddenUidsCache!!.clone()
        }
        if (flag == SuiConfig.FLAG_ALLOWED) {
            if (rootUidsCache == null) rootUidsCache = buildUidsByFlagLocked(flag)
            return rootUidsCache!!.clone()
        }
        if (flag == SuiConfig.FLAG_DENIED) {
            if (deniedUidsCache == null) deniedUidsCache = buildUidsByFlagLocked(flag)
            return deniedUidsCache!!.clone()
        }
        if (flag == SuiConfig.FLAG_ALLOWED_SHELL) {
            if (shellUidsCache == null) shellUidsCache = buildUidsByFlagLocked(flag)
            return shellUidsCache!!.clone()
        }
        return IntArray(0)
    }

    val hiddenUids: IntArray
        get() {
            synchronized(this) {
                return getUidsByFlagLocked(SuiConfig.FLAG_HIDDEN)
            }
        }

    val rootUids: IntArray
        get() {
            synchronized(this) {
                return getUidsByFlagLocked(SuiConfig.FLAG_ALLOWED)
            }
        }

    val deniedUids: IntArray
        get() {
            synchronized(this) {
                return getUidsByFlagLocked(SuiConfig.FLAG_DENIED)
            }
        }

    val shellUids: IntArray
        get() {
            synchronized(this) {
                return getUidsByFlagLocked(SuiConfig.FLAG_ALLOWED_SHELL)
            }
        }

    fun syncBridgeTokenToShellFile(token: String) {
        if (SuiService.isShellMode()) return

        val dir = getShellDir()
        if (!dir.exists() && !dir.mkdirs()) {
            LOGGER.w("failed to create shell dir: %s", dir)
            return
        }

        val tokenFile = File(dir, BRIDGE_TOKEN_FILENAME)
        val tempFile = File(dir, BRIDGE_TOKEN_TMP_FILENAME)

        try {
            FileOutputStream(tempFile).use { fos ->
                fos.write(token.toByteArray(StandardCharsets.UTF_8))
                fos.fd.sync()
            }
        } catch (e: Throwable) {
            LOGGER.w(e, "write bridge token")
            return
        }

        if (tempFile.renameTo(tokenFile)) {
            try {
                Os.chown(tokenFile.absolutePath, 2000, 2000)
                Os.chmod(tokenFile.absolutePath, 0x180) // 0600
            } catch (e: Throwable) {
                LOGGER.w(e, "chown/chmod bridge token")
            }
        } else {
            LOGGER.w("rename bridge token failed")
        }
    }

    fun readBridgeTokenFromShellFile(): String? {
        val tokenFile = File(getShellDir(), BRIDGE_TOKEN_FILENAME)
        if (!tokenFile.exists()) return null

        try {
            BufferedReader(java.io.InputStreamReader(java.io.FileInputStream(tokenFile), StandardCharsets.UTF_8)).use { reader ->
                var token = reader.readLine()
                if (token == null) {
                    return null
                }
                token = token.trim()
                return if (token.isEmpty()) null else token
            }
        } catch (e: Throwable) {
            LOGGER.w(e, "read bridge token")
            return null
        }
    }

    @Synchronized
    fun getShortcutToken(): String {
        if (shortcutToken != null) {
            return shortcutToken!!
        }

        val tokenFile = File("/data/adb/sui/shortcut_token")
        if (tokenFile.exists() && tokenFile.length() > 0) {
            try {
                BufferedReader(FileReader(tokenFile)).use { reader ->
                    val token = reader.readLine()
                    if (!token.isNullOrEmpty()) {
                        shortcutToken = token
                        return token
                    }
                }
            } catch (e: IOException) {
                LOGGER.e(e, "Failed to read shortcut token")
            }
        }

        shortcutToken = UUID.randomUUID().toString()
        val tempFile = File(tokenFile.path + ".tmp")
        try {
            FileOutputStream(tempFile).use { fos ->
                fos.write(shortcutToken!!.toByteArray(StandardCharsets.UTF_8))
                fos.fd.sync()
            }
            if (!tempFile.renameTo(tokenFile)) {
                throw IOException("Rename failed")
            }
        } catch (e: IOException) {
            LOGGER.e(e, "Failed to write shortcut token atomically")
        }
        return shortcutToken!!
    }
}
