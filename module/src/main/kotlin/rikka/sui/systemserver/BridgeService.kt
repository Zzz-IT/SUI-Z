package rikka.sui.systemserver

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import moe.shizuku.server.IShizukuService
import rikka.sui.server.SuiConfig
import rikka.sui.util.BridgeConstants

object BridgeService {

    private val DEATH_RECIPIENT_ROOT = IBinder.DeathRecipient {
        rootServiceBinder = null
        rootServerPid = -1
        rootRegisterToken = null
        serviceStarted = false
        SystemServerConstants.LOGGER.i("root service is dead")
    }

    private val DEATH_RECIPIENT_SHELL = IBinder.DeathRecipient {
        shellServiceBinder = null
        shellServerPid = -1
        shellRegisterToken = null
        SystemServerConstants.LOGGER.i("shell service is dead")
    }

    private const val RETRY_MAX = 3
    private const val RETRY_DELAY_MS = 1000L

    @Volatile
    private var rootServiceBinder: IBinder? = null
    @Volatile
    private var shellServiceBinder: IBinder? = null
    @Volatile
    private var serviceStarted = false
    @Volatile
    private var rootServerPid = -1
    @Volatile
    private var shellServerPid = -1
    @Volatile
    private var rootRegisterToken: String? = null
    @Volatile
    private var shellRegisterToken: String? = null

    @Volatile
    private var lastGetBinderUid = -1
    @Volatile
    private var lastGetBinderResult = "none"
    @Volatile
    private var lastGetBinderAt = 0L

    @JvmStatic
    fun get(): IShizukuService? {
        val binder = rootServiceBinder
        return if (binder == null) null else IShizukuService.Stub.asInterface(binder)
    }

    @JvmStatic
    fun getShell(): IShizukuService? {
        val binder = shellServiceBinder
        return if (binder == null) null else IShizukuService.Stub.asInterface(binder)
    }

    @JvmStatic
    fun isServiceStarted(): Boolean {
        return serviceStarted
    }

    @JvmStatic
    private fun sendBinder(binder: IBinder?, isRoot: Boolean): Boolean {
        if (binder == null) {
            SystemServerConstants.LOGGER.w("received empty binder")
            return false
        }

        val recipient = if (isRoot) DEATH_RECIPIENT_ROOT else DEATH_RECIPIENT_SHELL

        try {
            binder.linkToDeath(recipient, 0)
        } catch (e: RemoteException) {
            SystemServerConstants.LOGGER.w(e, "received dead binder")
            return false
        }

        try {
            if (isRoot) {
                val old = rootServiceBinder
                if (old == null) {
                    PackageReceiver.register()
                } else {
                    try {
                        old.unlinkToDeath(DEATH_RECIPIENT_ROOT, 0)
                    } catch (e: Throwable) {
                        SystemServerConstants.LOGGER.w(e, "unlink old root binder")
                    }
                }

                rootServiceBinder = binder
                SystemServerConstants.LOGGER.i("root binder received")
            } else {
                val old = shellServiceBinder
                if (old != null) {
                    try {
                        old.unlinkToDeath(DEATH_RECIPIENT_SHELL, 0)
                    } catch (e: Throwable) {
                        SystemServerConstants.LOGGER.w(e, "unlink old shell binder")
                    }
                }

                shellServiceBinder = binder
                SystemServerConstants.LOGGER.i("shell binder received")
            }
            return true
        } catch (e: Throwable) {
            try {
                binder.unlinkToDeath(recipient, 0)
            } catch (ignored: Throwable) {
            }
            SystemServerConstants.LOGGER.w(e, "sendBinder failed")
            return false
        }
    }

    @JvmStatic
    fun isServiceTransaction(code: Int): Boolean {
        return code == BridgeConstants.TRANSACTION_CODE
    }

    @JvmStatic
    fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        data.enforceInterface(BridgeConstants.SERVICE_DESCRIPTOR)

        val action = data.readInt()
        SystemServerConstants.LOGGER.d(
            "onTransact: action=%d, callingUid=%d, callingPid=%d",
            action, Binder.getCallingUid(), Binder.getCallingPid()
        )

        when (action) {
            BridgeConstants.ACTION_SEND_BINDER -> {
                val callingUid = Binder.getCallingUid()
                val callingPid = Binder.getCallingPid()

                if (callingUid == 0 || callingUid == 2000) {
                    val binder = data.readStrongBinder()

                    var token: String? = null
                    if (data.dataAvail() > 0) {
                        token = data.readString()
                    }

                    if (!isRegisterTokenAllowed(callingUid, token)) {
                        SystemServerConstants.LOGGER.w("reject binder registration: invalid token uid=%d pid=%d", callingUid, callingPid)
                        if (reply != null) {
                            reply.writeNoException()
                            reply.writeInt(0)
                        }
                        return true
                    }

                    var ok: Boolean
                    val identity = Binder.clearCallingIdentity()
                    try {
                        ok = sendBinder(binder, callingUid == 0)
                    } finally {
                        Binder.restoreCallingIdentity(identity)
                    }

                    if (ok) {
                        if (callingUid == 0) {
                            rootServerPid = callingPid
                        } else {
                            shellServerPid = callingPid
                        }
                    } else {
                        SystemServerConstants.LOGGER.w(
                            "reject binder registration: uid=%d pid=%d isRoot=%s",
                            callingUid,
                            callingPid,
                            (callingUid == 0).toString()
                        )
                    }

                    if (reply != null) {
                        reply.writeNoException()
                        reply.writeInt(if (ok) 1 else 0)
                    }
                    return true
                }
            }

            BridgeConstants.ACTION_GET_BINDER -> {
                val callingUid = Binder.getCallingUid()
                val callingPid = Binder.getCallingPid()

                var requestedServerUid: Int? = null

                if ((callingUid == 0 || callingUid == 2000) && data.dataAvail() >= Integer.BYTES) {
                    val value = data.readInt()
                    if (value == BridgeConstants.SERVER_UID_ROOT || value == BridgeConstants.SERVER_UID_SHELL) {
                        requestedServerUid = value
                    }
                }

                var delegateToken: String? = null
                if (requestedServerUid != null && data.dataAvail() > 0) {
                    delegateToken = data.readString()
                }

                if (requestedServerUid != null && !isTrustedServerDelegate(callingUid, callingPid, requestedServerUid, delegateToken)) {
                    SystemServerConstants.LOGGER.w(
                        "reject server binder request: uid=%d pid=%d target=%d",
                        callingUid,
                        callingPid,
                        requestedServerUid
                    )

                    if (reply != null) {
                        reply.writeNoException()
                        reply.writeStrongBinder(null)
                    }
                    return true
                }

                var permissionFlags = 0
                if (requestedServerUid == null) {
                    permissionFlags = Bridge.getPermissionFlags(callingUid)

                    if ((permissionFlags & SuiConfig.FLAG_HIDDEN) != 0) {
                        return false
                    }
                }

                var requestedBinder: IBinder? = null

                for (i in 0 until RETRY_MAX) {
                    if (requestedServerUid != null) {
                        requestedBinder = if (requestedServerUid == BridgeConstants.SERVER_UID_ROOT) {
                            rootServiceBinder
                        } else {
                            shellServiceBinder
                        }
                    } else if ((permissionFlags & SuiConfig.FLAG_ALLOWED) != 0) {
                        requestedBinder = rootServiceBinder
                    } else if ((permissionFlags & SuiConfig.FLAG_ALLOWED_SHELL) != 0) {
                        requestedBinder = shellServiceBinder
                    } else {
                        requestedBinder = rootServiceBinder
                    }

                    if (requestedBinder != null) {
                        break
                    }

                    if (i + 1 < RETRY_MAX) {
                        try {
                            SystemServerConstants.LOGGER.w(
                                "binder missing, wait %d ms: uid=%d pid=%d requested=%s flags=0x%x",
                                RETRY_DELAY_MS,
                                callingUid,
                                callingPid,
                                if (requestedServerUid == null) "auto" else if (requestedServerUid == BridgeConstants.SERVER_UID_ROOT) "root" else "shell",
                                permissionFlags
                            )
                            Thread.sleep(RETRY_DELAY_MS)
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                    }
                }

                SystemServerConstants.LOGGER.d(
                    "get binder: uid=%d pid=%d requested=%s flags=0x%x result=%s",
                    callingUid,
                    callingPid,
                    if (requestedServerUid == null) "auto" else if (requestedServerUid == BridgeConstants.SERVER_UID_ROOT) "root" else "shell",
                    permissionFlags,
                    if (requestedBinder === rootServiceBinder) "root" else if (requestedBinder === shellServiceBinder) "shell" else "null"
                )

                lastGetBinderUid = callingUid
                lastGetBinderResult = if (requestedBinder === rootServiceBinder) "root" else if (requestedBinder === shellServiceBinder) "shell" else "null"
                lastGetBinderAt = System.currentTimeMillis()

                if (reply != null) {
                    reply.writeNoException()
                    reply.writeStrongBinder(requestedBinder)
                }
                return true
            }

            BridgeConstants.ACTION_NOTIFY_FINISHED -> {
                if (Binder.getCallingUid() == 0) {
                    serviceStarted = true

                    reply?.writeNoException()
                    return true
                }
            }

            BridgeConstants.ACTION_SYNC_UIDS -> {
                if (Binder.getCallingUid() == 0) {
                    val hiddenUids = data.createIntArray() ?: IntArray(0)
                    val rootUids = data.createIntArray() ?: IntArray(0)
                    val shellUids = data.createIntArray() ?: IntArray(0)
                    var defaultFlags = 0
                    var deniedUids = IntArray(0)

                    if (data.dataAvail() >= Integer.BYTES) {
                        defaultFlags = data.readInt()
                    }

                    if (data.dataAvail() >= Integer.BYTES) {
                        val arr = data.createIntArray()
                        if (arr != null) {
                            deniedUids = arr
                        }
                    }
                    SystemProcess.updateUids(hiddenUids, rootUids, deniedUids, shellUids, defaultFlags)
                    reply?.writeNoException()
                    return true
                }
            }

            BridgeConstants.ACTION_REGISTER_TOKEN -> {
                val callingUid = Binder.getCallingUid()
                val callingPid = Binder.getCallingPid()

                var accepted = false

                if (callingUid != 0) {
                    SystemServerConstants.LOGGER.w("reject token registration from uid=%d pid=%d", callingUid, callingPid)
                } else {
                    val rToken = data.readString()
                    val sToken = data.readString()

                    if (!isValidToken(rToken) || !isValidToken(sToken)) {
                        SystemServerConstants.LOGGER.w("reject invalid bridge tokens")
                    } else {
                        rootRegisterToken = rToken
                        shellRegisterToken = sToken
                        accepted = true
                        SystemServerConstants.LOGGER.i("bridge tokens registered by root pid=%d", callingPid)
                    }
                }

                if (reply != null) {
                    reply.writeNoException()
                    reply.writeInt(if (accepted) 1 else 0)
                }
                return true
            }
        }
        return false
    }

    @JvmStatic
    private fun isTrustedServerDelegate(uid: Int, pid: Int, requestedServerUid: Int, token: String?): Boolean {
        if (uid == 0) {
            return pid > 0 &&
                    pid == rootServerPid &&
                    rootRegisterToken != null &&
                    rootRegisterToken == token
        }

        if (uid == 2000) {
            return pid > 0 &&
                    pid == shellServerPid &&
                    shellRegisterToken != null &&
                    shellRegisterToken == token &&
                    (requestedServerUid == BridgeConstants.SERVER_UID_ROOT || requestedServerUid == BridgeConstants.SERVER_UID_SHELL)
        }

        return false
    }

    @JvmStatic
    private fun isRegisterTokenAllowed(uid: Int, token: String?): Boolean {
        if (uid == 0) {
            return rootRegisterToken != null && rootRegisterToken == token
        }

        if (uid == 2000) {
            return shellRegisterToken != null && shellRegisterToken == token
        }

        return false
    }

    @JvmStatic
    private fun isValidToken(token: String?): Boolean {
        return token != null && token.length in 32..128
    }

    @JvmStatic
    fun getDebugStateString(): String {
        return "rootReady=${rootServiceBinder != null}" +
                ", shellReady=${shellServiceBinder != null}" +
                ", started=$serviceStarted" +
                ", rootPid=$rootServerPid" +
                ", shellPid=$shellServerPid" +
                ", lastUid=$lastGetBinderUid" +
                ", lastResult=$lastGetBinderResult" +
                ", lastAt=$lastGetBinderAt"
    }
}
