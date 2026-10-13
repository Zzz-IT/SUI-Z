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
 * Timing rules for debouncing per-uid work.
 *
 * <p>When an app's permission flags change, SuiService has to invalidate that
 * app's packages so they pick up the new identity. Doing it more than once in
 * quick succession is wasted work, so calls are coalesced into a window.
 *
 * <p>Three properties matter and are pinned by {@code DebouncePolicyTest}:
 *
 * <ol>
 *   <li>The window must be evaluated against a <b>monotonic</b> clock. The
 *       previous implementation read {@code System.currentTimeMillis()}, which
 *       jumps backwards on NTP correction or a user changing the clock. A
 *       backwards jump makes {@code now - last} negative, which reads as
 *       "not enough time has passed" and suppresses every subsequent
 *       invalidation until wall time catches up -- silently, with no error.
 *   <li>Tracked state must be bounded. The previous map only ever grew.
 *   <li>A backwards clock must fail open (allow the work) rather than fail
 *       closed (suppress it), because a suppressed invalidation leaves an app
 *       running with stale permissions.
 * </ol>
 *
 * <p>This class is the pure decision rule; keeping the timestamps themselves is
 * the caller's job. It deliberately has no Android dependencies so it can be
 * exercised by a plain JVM unit test. Do not add {@code android.*} imports.
 */
public final class DebouncePolicy {

    /** Minimum gap between two accepted calls for the same key. */
    public static final long WINDOW_MS = 2000L;

    /** Hard ceiling on tracked keys, as a backstop against unbounded growth. */
    public static final int MAX_TRACKED_KEYS = 4096;

    private DebouncePolicy() {}

    /**
     * Whether a call for {@code key} should proceed at {@code nowMs}.
     *
     * @param nowMs  monotonic timestamp, e.g. {@code SystemClock.elapsedRealtime()};
     *               must not come from a wall clock
     * @param lastMs timestamp of the last accepted call for this key, or
     *               {@code null} if there has not been one
     * @return true if the work should run now
     */
    public static boolean shouldRun(long nowMs, Long lastMs) {
        if (lastMs == null) {
            return true;
        }

        // A backwards clock is a bug in the caller, but suppressing work because
        // of it is worse than doing the work: fail open.
        if (nowMs < lastMs) {
            return true;
        }

        return nowMs - lastMs >= WINDOW_MS;
    }

    /**
     * Whether a tracked entry has outlived the window and can be dropped.
     *
     * <p>An entry older than the window no longer constrains anything -- the
     * next call for that key would be accepted regardless -- so it is safe to
     * evict. Callers use this to keep their map small without changing
     * behaviour.
     *
     * @param nowMs  monotonic timestamp
     * @param lastMs the entry's timestamp
     * @return true if the entry may be discarded
     */
    public static boolean isExpired(long nowMs, long lastMs) {
        if (nowMs < lastMs) {
            // Clock went backwards; keep the entry rather than churn on it.
            return false;
        }
        return nowMs - lastMs >= WINDOW_MS;
    }

    /**
     * Whether a map holding {@code size} entries should be swept for expired
     * entries before accepting another key.
     *
     * <p>Sweeping is O(n), so it is only worth doing once the map is large
     * enough that the scan is cheaper than the memory it reclaims.
     */
    public static boolean shouldSweep(int size) {
        return size >= MAX_TRACKED_KEYS / 2;
    }
}
