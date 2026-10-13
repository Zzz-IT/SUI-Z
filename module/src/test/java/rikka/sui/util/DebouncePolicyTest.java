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
 * Unit tests for {@link DebouncePolicy}.
 *
 * <p>The failure mode this guards against is silent: an invalidation that gets
 * suppressed leaves an app running with stale permissions, and nothing logs an
 * error. The previous implementation used a wall clock, so a backwards clock
 * jump suppressed every invalidation until wall time caught up.
 */
public class DebouncePolicyTest {

    // ------------------------------------------------------------------
    // shouldRun
    // ------------------------------------------------------------------

    @Test
    public void shouldRun_acceptsFirstCall() {
        assertTrue(DebouncePolicy.shouldRun(1000L, null));
    }

    @Test
    public void shouldRun_suppressesWithinWindow() {
        // The window is inclusive at the boundary: exactly WINDOW_MS has passed,
        // so this must be accepted, not suppressed.
        assertTrue(DebouncePolicy.shouldRun(2000L, 0L));
        assertFalse(DebouncePolicy.shouldRun(1999L, 0L));
        assertFalse(DebouncePolicy.shouldRun(1001L, 1000L));
    }

    @Test
    public void shouldRun_acceptsAfterWindow() {
        assertTrue(DebouncePolicy.shouldRun(0L + DebouncePolicy.WINDOW_MS, 0L));
        assertTrue(DebouncePolicy.shouldRun(10_000L, 5_000L));
    }

    @Test
    public void shouldRun_failsOpenOnBackwardsClock() {
        // This is the regression. A wall clock that jumps backwards used to make
        // (now - last) negative, which read as "too soon" and suppressed the work
        // indefinitely. With a monotonic clock this should not happen, but if it
        // does the work must still run.
        assertTrue(DebouncePolicy.shouldRun(500L, 5000L));
        assertTrue(DebouncePolicy.shouldRun(0L, Long.MAX_VALUE));
    }

    @Test
    public void shouldRun_boundaryIsInclusiveNotExclusive() {
        long last = 1000L;
        assertFalse(DebouncePolicy.shouldRun(last + DebouncePolicy.WINDOW_MS - 1, last));
        assertTrue(DebouncePolicy.shouldRun(last + DebouncePolicy.WINDOW_MS, last));
        assertTrue(DebouncePolicy.shouldRun(last + DebouncePolicy.WINDOW_MS + 1, last));
    }

    @Test
    public void shouldRun_neverSuppressesForeverAcrossSweep() {
        // Simulate a long session on a monotonic clock: for any fixed last
        // timestamp, there must exist a future time at which the call is
        // accepted. This is what "not suppressed forever" means.
        for (long last : new long[] {0L, 1L, 1000L, 1_000_000L}) {
            long now = last;
            boolean accepted = false;
            for (int i = 0; i < 10 && !accepted; i++) {
                now = last + DebouncePolicy.WINDOW_MS * (i + 1);
                if (DebouncePolicy.shouldRun(now, last)) {
                    accepted = true;
                }
            }
            assertTrue("last=" + last + " never accepted", accepted);
        }
    }

    // ------------------------------------------------------------------
    // isExpired
    // ------------------------------------------------------------------

    @Test
    public void isExpired_matchesWindow() {
        assertTrue(DebouncePolicy.isExpired(2000L, 0L));
        assertFalse(DebouncePolicy.isExpired(1999L, 0L));
        assertTrue(DebouncePolicy.isExpired(3000L, 1000L));
        assertFalse(DebouncePolicy.isExpired(1000L, 1000L));
    }

    @Test
    public void isExpired_keepsEntryOnBackwardsClock() {
        // Dropping the entry would let a burst through; keeping it is safer
        // because shouldRun() already fails open.
        assertFalse(DebouncePolicy.isExpired(500L, 5000L));
    }

    @Test
    public void isExpiredAgreesWithShouldRun() {
        // The two must not disagree, or a caller that evicts on isExpired() would
        // change whether shouldRun() accepts. Evicting an expired entry and then
        // asking shouldRun() must give the same answer as asking first.
        long last = 1000L;
        for (long now = 0; now <= 5000; now += 137) {
            boolean expired = DebouncePolicy.isExpired(now, last);
            boolean wouldRun = DebouncePolicy.shouldRun(now, last);
            if (expired) {
                assertTrue(
                        "now=" + now + " expired but shouldRun said suppress",
                        wouldRun || now < last);
            }
        }
    }

    // ------------------------------------------------------------------
    // shouldSweep
    // ------------------------------------------------------------------

    @Test
    public void shouldSweep_defersUntilHalfCapacity() {
        int half = DebouncePolicy.MAX_TRACKED_KEYS / 2;
        assertFalse(DebouncePolicy.shouldSweep(0));
        assertFalse(DebouncePolicy.shouldSweep(half - 1));
        assertTrue(DebouncePolicy.shouldSweep(half));
        assertTrue(DebouncePolicy.shouldSweep(DebouncePolicy.MAX_TRACKED_KEYS));
    }

    // ------------------------------------------------------------------
    // Constants
    // ------------------------------------------------------------------

    @Test
    public void constantsAreSane() {
        assertTrue(DebouncePolicy.WINDOW_MS > 0);
        assertTrue(DebouncePolicy.MAX_TRACKED_KEYS > 0);
        // The ceiling must be large enough that eviction never actually fires on
        // a realistic device, otherwise it is not a backstop but a behaviour
        // change.
        assertTrue(DebouncePolicy.MAX_TRACKED_KEYS >= 1024);
    }

    @Test
    public void windowMatchesPreviousBehaviour() {
        // The previous code hardcoded 2000 ms. Pin it so the window is not
        // changed as a side effect of some other edit.
        assertEquals(2000L, DebouncePolicy.WINDOW_MS);
    }
}
