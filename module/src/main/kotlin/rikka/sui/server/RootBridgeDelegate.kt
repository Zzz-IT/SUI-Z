package rikka.sui.server

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ServiceManager
import rikka.shizuku.ShizukuApiConstants
import rikka.sui.util.BridgeConstants

object RootBridgeDelegate {

    @JvmStatic
    fun delegatePermissionConfirmationToRoot(
        clientManager: SuiClientManager,
        requestCode: Int,
        packageName: String?,
        callingUid: Int,
        callingPid: Int
    ) {
        val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == 1) {
                    val allowed = data.readInt() != 0
                    ServerConstants.LOGGER.i(
                        "Received delegated permission result for uid=%d: allowed=%s",
                        callingUid,
                        allowed
                    )

                    val record = clientManager.findClient(callingUid, callingPid)
                    if (record != null) {
                        record.allowed = allowed
                        record.dispatchRequestPermissionResult(requestCode, allowed)
                    }
                    return true
                }
                return false
            }
        }

        var data: Parcel? = null
        var reply: Parcel? = null

        try {
            val bridgeService = ServiceManager.getService(BridgeConstants.SERVICE_NAME) ?: return

            data = Parcel.obtain()
            reply = Parcel.obtain()

            data.writeInterfaceToken(BridgeConstants.SERVICE_DESCRIPTOR)
            data.writeInt(BridgeConstants.ACTION_GET_BINDER)
            data.writeInt(BridgeConstants.SERVER_UID_ROOT)

            val manager = SuiConfigManager.instance
            val shellToken = manager?.readBridgeTokenFromShellFile()
            if (shellToken != null) {
                data.writeString(shellToken)
            }

            bridgeService.transact(BridgeConstants.TRANSACTION_CODE, data, reply, 0)
            reply.readException()
            val rootBinder = reply.readStrongBinder()

            data.recycle()
            data = null
            reply.recycle()
            reply = null

            if (rootBinder != null) {
                data = Parcel.obtain()
                data.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR)
                data.writeInt(requestCode)
                data.writeString(packageName)
                data.writeInt(callingUid)
                data.writeInt(callingPid)
                data.writeStrongBinder(callback)

                if (shellToken != null) {
                    data.writeString(shellToken)
                }

                rootBinder.transact(
                    ServerConstants.BINDER_TRANSACTION_requestPermissionFromShell,
                    data,
                    null,
                    IBinder.FLAG_ONEWAY
                )

                data.recycle()
                data = null
            } else {
                ServerConstants.LOGGER.e("root binder is null, cannot delegate.")
            }
        } catch (e: Throwable) {
            ServerConstants.LOGGER.e(e, "delegatePermissionConfirmationToRoot")
        } finally {
            data?.recycle()
            reply?.recycle()
        }
    }
}
