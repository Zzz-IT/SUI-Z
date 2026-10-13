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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link AppQueryFlags}.
 *
 * <p>The point is the conditional: {@code GET_PERMISSIONS} must be requested
 * exactly when the caller will read {@code requestedPermissions}. Getting this
 * backwards in either direction is a real bug --
 *
 * <ul>
 *   <li>flag set but unread: every app's permission list crosses the binder and
 *       is discarded, on every list refresh;
 *   <li>flag unset but read: {@code requestedPermissions} is null everywhere, so
 *       the "only Shizuku apps" filter silently matches nothing instead of
 *       failing loudly.
 * </ul>
 */
public class AppQueryFlagsTest {

    // ------------------------------------------------------------------
    // forAppList
    // ------------------------------------------------------------------

    @Test
    public void forAppList_includesGetPermissionsOnlyWhenFiltering() {
        assertTrue(AppQueryFlags.hasGetPermissions(AppQueryFlags.forAppList(true)));
        assertFalse(AppQueryFlags.hasGetPermissions(AppQueryFlags.forAppList(false)));
    }

    @Test
    public void forAppList_alwaysIncludesMatchUninstalled() {
        // The unfiltered list uses this to distinguish an installed app from one
        // that was uninstalled but kept data -- both need to appear.
        assertTrue(AppQueryFlags.hasMatchUninstalled(AppQueryFlags.forAppList(true)));
        assertTrue(AppQueryFlags.hasMatchUninstalled(AppQueryFlags.forAppList(false)));
    }

    @Test
    public void forAppList_unfilteredIsStrictlyNarrower() {
        int unfiltered = AppQueryFlags.forAppList(false);
        int filtered = AppQueryFlags.forAppList(true);
        // The filtered query is a superset: it must contain every bit the
        // unfiltered one has, plus GET_PERMISSIONS.
        assertEquals(0, (~filtered & unfiltered));
        assertEquals(AppQueryFlags.GET_PERMISSIONS, filtered & ~unfiltered);
    }

    @Test
    public void forAppList_exactBitPatterns() {
        // Pin the actual values. A silent change here alters what PackageManager
        // serialises, which shows up only as a performance or correctness
        // regression on device -- not as a compile error.
        assertEquals(0x00002000, AppQueryFlags.MATCH_UNINSTALLED_PACKAGES);
        assertEquals(0x00001000, AppQueryFlags.GET_PERMISSIONS);
        assertEquals(0x00002000, AppQueryFlags.forAppList(false));
        assertEquals(0x00003000, AppQueryFlags.forAppList(true));
    }

    // ------------------------------------------------------------------
    // hasGetPermissions / hasMatchUninstalled
    // ------------------------------------------------------------------

    @Test
    public void hasGetPermissions_detectsTheBit() {
        assertTrue(AppQueryFlags.hasGetPermissions(AppQueryFlags.GET_PERMISSIONS));
        assertTrue(AppQueryFlags.hasGetPermissions(AppQueryFlags.GET_PERMISSIONS | 0x1));
        assertFalse(AppQueryFlags.hasGetPermissions(~AppQueryFlags.GET_PERMISSIONS));
        assertFalse(AppQueryFlags.hasGetPermissions(0));
    }

    @Test
    public void hasMatchUninstalled_detectsTheBit() {
        assertTrue(AppQueryFlags.hasMatchUninstalled(AppQueryFlags.MATCH_UNINSTALLED_PACKAGES));
        assertTrue(AppQueryFlags.hasMatchUninstalled(AppQueryFlags.forAppList(true)));
        assertFalse(AppQueryFlags.hasMatchUninstalled(~AppQueryFlags.MATCH_UNINSTALLED_PACKAGES));
        assertFalse(AppQueryFlags.hasMatchUninstalled(0));
    }

    @Test
    public void predicatesAgreeWithTheFlagsTheyCameFrom() {
        // Round-trip: whatever forAppList produces, the predicates must read it
        // back consistently. If they disagree, a caller guarding a
        // requestedPermissions read on hasGetPermissions() would take the wrong
        // branch.
        for (boolean onlyShizuku : new boolean[] {false, true}) {
            int flags = AppQueryFlags.forAppList(onlyShizuku);
            assertEquals(
                    "onlyShizuku=" + onlyShizuku,
                    onlyShizuku,
                    AppQueryFlags.hasGetPermissions(flags));
            assertTrue(AppQueryFlags.hasMatchUninstalled(flags));
        }
    }

    // ------------------------------------------------------------------
    // Constants
    // ------------------------------------------------------------------

    @Test
    public void constantsMatchFrameworkValues() {
        // Verified against android-36 android.jar via javap on
        // android.content.pm.PackageManager. If a future SDK renumbers these,
        // this test is where it should surface.
        assertEquals(8192, AppQueryFlags.MATCH_UNINSTALLED_PACKAGES);
        assertEquals(4096, AppQueryFlags.GET_PERMISSIONS);
    }

    @Test
    public void flagsAreDistinctNonOverlappingBits() {
        // They must not share a bit, or clearing one would clear the other's
        // meaning.
        assertEquals(0, AppQueryFlags.GET_PERMISSIONS & AppQueryFlags.MATCH_UNINSTALLED_PACKAGES);
    }
}
