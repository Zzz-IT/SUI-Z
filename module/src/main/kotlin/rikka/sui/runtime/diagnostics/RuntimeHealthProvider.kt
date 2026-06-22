package rikka.sui.runtime.diagnostics

object RuntimeHealthProvider {
    @JvmStatic
    fun snapshot(): RuntimeHealth {
        return RuntimeHealth(
            rootServerAlive = true,
            shellServerAlive = true,
            bridgeAlive = true,
            systemServerInjected = true,
            lastBinderResult = "root",
            lastBinderUid = -1,
            moduleRootImpl = detectRootImplementation(),
            shellDirReady = true,
        )
    }

    private fun detectRootImplementation(): String {
        return when {
            System.getenv("KSU") == "true" -> "KernelSU"
            System.getenv("APATCH") == "true" -> "APatch"
            System.getenv("MAGISK_VER_CODE") != null -> "Magisk"
            else -> "unknown"
        }
    }
}
