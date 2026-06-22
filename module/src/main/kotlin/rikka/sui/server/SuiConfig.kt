package rikka.sui.server

import rikka.shizuku.server.ConfigPackageEntry

class SuiConfig {

    companion object {
        const val LATEST_VERSION = 1

        const val FLAG_ALLOWED = 1 shl 1
        const val FLAG_DENIED = 1 shl 2
        const val FLAG_HIDDEN = 1 shl 3
        const val FLAG_ALLOWED_SHELL = 1 shl 4
        const val MASK_PERMISSION = FLAG_ALLOWED or FLAG_DENIED or FLAG_HIDDEN or FLAG_ALLOWED_SHELL
    }

    @JvmField
    var version: Int = LATEST_VERSION

    @JvmField
    var packages: MutableList<PackageEntry> = ArrayList()

    class PackageEntry(
        @JvmField val uid: Int,
        @JvmField var flags: Int
    ) : ConfigPackageEntry() {

        fun isAllowed(): Boolean {
            return (flags and FLAG_ALLOWED) != 0
        }

        fun isAllowedShell(): Boolean {
            return (flags and FLAG_ALLOWED_SHELL) != 0
        }

        fun isDenied(): Boolean {
            return (flags and FLAG_DENIED) != 0
        }

        fun isHidden(): Boolean {
            return (flags and FLAG_HIDDEN) != 0
        }
    }

    constructor()

    constructor(packages: MutableList<PackageEntry>) {
        this.version = LATEST_VERSION
        this.packages = packages
    }
}
