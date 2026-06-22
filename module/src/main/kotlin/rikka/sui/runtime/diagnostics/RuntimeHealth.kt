package rikka.sui.runtime.diagnostics

data class RuntimeHealth(
    val rootServerAlive: Boolean,
    val shellServerAlive: Boolean,
    val bridgeAlive: Boolean,
    val systemServerInjected: Boolean,
    val lastBinderResult: String,
    val lastBinderUid: Int,
    val moduleRootImpl: String,
    val shellDirReady: Boolean,
)
