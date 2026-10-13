/*
 * This file is part of SUI Z.
 *
 * SUI Z is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * SUI Z is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with SUI Z.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (c) 2026 SUI Z Contributors
 */

package rikka.sui.util;

/**
 * Query flags for enumerating installed packages in the management UI.
 *
 * <p>{@code getInstalledPackages} is the single most expensive call the
 * management UI makes: PackageManager has to serialise every {@code PackageInfo}
 * across a binder transaction. Each flag we set widens what gets serialised, so
 * a flag that is requested but never read is pure cost -- paid on every list
 * refresh, and paid again on every configuration change that triggers a reload.
 *
 * <p>This class exists to keep the flag set minimal and to make the "which
 * flags for which query" decision testable without a PackageManager.
 *
 * <p>It deliberately has no Android dependencies so it can be exercised by a
 * plain JVM unit test. Do not add {@code android.*} imports here.
 */
public final class AppQueryFlags {

    /** Keeps packages that were uninstalled but kept their data. */
    public static final int MATCH_UNINSTALLED_PACKAGES = 0x00002000;

    /**
     * Populates {@code PackageInfo.requestedPermissions}.
     *
     * <p>Cost note: this makes PackageManager attach a {@code String[]} of every
     * requested permission to every package in the result. It is only needed to
     * answer "does this app request the Shizuku API permission", which is a
     * question the UI asks only when the "only Shizuku apps" filter is on.
     */
    public static final int GET_PERMISSIONS = 0x00001000;

    private AppQueryFlags() {}

    /**
     * Flags for enumerating the app list.
     *
     * <p>{@code GET_PERMISSIONS} is included only when {@code onlyShizuku} is
     * set, because that is the only path that reads
     * {@code PackageInfo.requestedPermissions}. Requesting it for the unfiltered
     * list meant shipping every app's permission list across the binder and then
     * never looking at it.
     *
     * @param onlyShizuku whether the caller will filter on the Shizuku API
     *                    permission, and therefore needs {@code requestedPermissions}
     * @return flags for {@code PackageManager.getInstalledPackages}
     */
    public static int forAppList(boolean onlyShizuku) {
        int flags = MATCH_UNINSTALLED_PACKAGES;
        if (onlyShizuku) {
            flags |= GET_PERMISSIONS;
        }
        return flags;
    }

    /**
     * Whether {@code flags} carries {@link #GET_PERMISSIONS}.
     *
     * <p>Exposed for the unit tests and for callers that need to guard a read of
     * {@code requestedPermissions}: if the flag was not requested, the field will
     * be null on every package, and a filter that scans it would silently drop
     * every app rather than match any.
     */
    public static boolean hasGetPermissions(int flags) {
        return (flags & GET_PERMISSIONS) != 0;
    }

    /**
     * Whether {@code flags} carries {@link #MATCH_UNINSTALLED_PACKAGES}.
     *
     * <p>The unfiltered list relies on this to tell "installed" from
     * "uninstalled but keeping data", so dropping it would hide apps the user
     * has already granted permissions to.
     */
    public static boolean hasMatchUninstalled(int flags) {
        return (flags & MATCH_UNINSTALLED_PACKAGES) != 0;
    }
}
