package rikka.sui.runtime.diagnostics

data class BridgeRuntimeState(
    val rootBinderReady: Boolean,
    val shellBinderReady: Boolean,
    val serviceStarted: Boolean,
    val rootServerPid: Int,
    val shellServerPid: Int,
    val lastGetBinderUid: Int,
    val lastGetBinderResult: String,
    val lastGetBinderAt: Long,
)
