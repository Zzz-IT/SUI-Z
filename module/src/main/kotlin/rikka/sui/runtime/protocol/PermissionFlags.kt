package rikka.sui.runtime.protocol

import rikka.sui.server.SuiConfig

@JvmInline
value class PermissionFlags(val raw: Int) {
    val hidden: Boolean get() = raw and SuiConfig.FLAG_HIDDEN != 0
    val denied: Boolean get() = raw and SuiConfig.FLAG_DENIED != 0
    val rootAllowed: Boolean get() = raw and SuiConfig.FLAG_ALLOWED != 0
    val shellAllowed: Boolean get() = raw and SuiConfig.FLAG_ALLOWED_SHELL != 0

    fun mode(): PermissionMode = when {
        hidden -> PermissionMode.Hidden
        rootAllowed -> PermissionMode.Root
        shellAllowed -> PermissionMode.Shell
        denied -> PermissionMode.Denied
        else -> PermissionMode.Ask
    }

    override fun toString(): String = "PermissionFlags(raw=0x${raw.toString(16)}, mode=${mode()})"
}
