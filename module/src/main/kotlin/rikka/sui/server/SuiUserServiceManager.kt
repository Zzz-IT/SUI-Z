package rikka.sui.server

import android.os.Build
import rikka.shizuku.server.UserServiceManager
import rikka.shizuku.server.UserServiceRecord
import java.io.File
import java.util.Locale

class SuiUserServiceManager : UserServiceManager() {

    companion object {
        @JvmField
        val USER_SERVICE_CMD_DEBUG: String

        private const val USER_SERVICE_CMD_FORMAT =
            "(CLASSPATH='%s' %s%s /system/bin --nice-name='%s' %s " +
                    "--token='%s' --package='%s' --class='%s' --uid=%d --server-uid=%d%s)&"

        private var dexPath: String? = null

        init {
            val sdk = Build.VERSION.SDK_INT
            USER_SERVICE_CMD_DEBUG = if (sdk >= 30) {
                "-Xcompiler-option --debuggable -XjdwpProvider:adbconnection " +
                        "-XjdwpOptions:suspend=n,server=y"
            } else if (sdk >= 28) {
                "-Xcompiler-option --debuggable -XjdwpProvider:internal " +
                        "-XjdwpOptions:transport=dt_android_adb,suspend=n,server=y"
            } else {
                "-Xcompiler-option --debuggable " +
                        "-agentlib:jdwp=transport=dt_android_adb,suspend=n,server=y"
            }
        }

        @JvmStatic
        fun setStartDex(path: String?) {
            dexPath = path
        }
    }

    override fun getUserServiceStartCmd(
        record: UserServiceRecord,
        key: String,
        token: String,
        packageName: String,
        classname: String,
        processNameSuffix: String,
        callingUid: Int,
        use32Bits: Boolean,
        debug: Boolean
    ): String {
        var appProcess = "/system/bin/app_process"
        if (use32Bits && File("/system/bin/app_process32").exists()) {
            appProcess = "/system/bin/app_process32"
        }

        val processName = String.format("%s:%s", packageName, processNameSuffix)

        return String.format(
            Locale.ENGLISH,
            USER_SERVICE_CMD_FORMAT,
            dexPath,
            appProcess,
            if (debug) " $USER_SERVICE_CMD_DEBUG" else "",
            processName,
            "rikka.sui.server.userservice.Starter",
            token,
            packageName,
            classname,
            callingUid,
            if (SuiService.isShellMode()) 2000 else 0,
            if (debug) " --debug-name=$processName" else ""
        )
    }
}
