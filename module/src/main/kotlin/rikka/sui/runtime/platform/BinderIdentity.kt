package rikka.sui.runtime.platform

import android.os.Binder

inline fun <T> withCleanCallingIdentity(block: () -> T): T {
    val token = Binder.clearCallingIdentity()
    return try {
        block()
    } finally {
        Binder.restoreCallingIdentity(token)
    }
}
