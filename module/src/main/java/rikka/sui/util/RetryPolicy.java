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
 * Retry timing for reconnecting to the Sui bridge service.
 *
 * <p>The binder to the bridge is established once at boot and torn down only on
 * a crash, so retries are rare events -- and a long delay is fine when the
 * binder is genuinely dead. But the current code wakes up on a fixed interval
 * regardless of whether anything changed. This class exists to keep retry
 * curves consistent, testable, and slow by default.
 *
 * <p>It deliberately has no Android dependencies so it can be exercised by a
 * plain JVM unit test. Do not add {@code android.*} imports here.
 */
public final class RetryPolicy {

    /**
     * When the bridge is simply missing (e.g. during boot, before the bridge
     * has registered itself), an event-driven caller should check again on a
     * fast cadence. But because we know the bridge WILL register, we can wait
     * for the registration instead of polling; this short interval is the
     * fallback for callers that cannot observe the registration directly.
     */
    public static final long FAST_POLL_INTERVAL_MS = 100L;

    /** For the rare transitory failure: how long to sleep between attempts. */
    public static final long INITIAL_BACKOFF_MS = 100L;

    /** Multiplier applied to each consecutive failure. */
    public static final double BACKOFF_MULTIPLIER = 2.0;

    /** Ceiling on any single backoff. */
    public static final long MAX_BACKOFF_MS = 30_000L;

    /** Total time budget for a full reconnection attempt. */
    public static final long TOTAL_TIMEOUT_MS = 10_000L;

    /**
     * Computes the delay before the given retry attempt.
     *
     * <p>The curve is exponential with a multiplier of {@link #BACKOFF_MULTIPLIER},
     * clamped at {@link #MAX_BACKOFF_MS}. Attempt 0 is the first retry, so
     * attempt 0 returns {@link #INITIAL_BACKOFF_MS} rather than zero -- the
     * first attempt already happened and failed, so the delay should never be
     * zero.
     *
     * @param attempt zero-based retry index; values below zero are clamped to 0
     * @return delay in milliseconds before the next attempt
     */
    public static long delayForAttempt(int attempt) {
        if (attempt < 0) {
            attempt = 0;
        }

        // Use shift arithmetic rather than Math.pow so the curve stays exact
        // and cheap to compute on the hot reconnect path.
        int capped = Math.min(attempt, 20);
        long delay = INITIAL_BACKOFF_MS << capped;

        if (delay < 0 || delay > MAX_BACKOFF_MS) {
            return MAX_BACKOFF_MS;
        }
        return delay;
    }

    /**
     * Computes the delay given the elapsed time and attempt count.
     *
     * <p>Once the total timeout has elapsed, this returns 0 to signal that no
     * further retry should be scheduled. That keeps the caller's loop
     * terminating without a separate deadline check.
     *
     * @param elapsedMs time since the first attempt
     * @param attempt   zero-based retry index
     * @return delay before the next attempt, or 0 if the timeout has elapsed
     */
    public static long delayForAttempt(long elapsedMs, int attempt) {
        if (elapsedMs >= TOTAL_TIMEOUT_MS) {
            return 0L;
        }
        return delayForAttempt(attempt);
    }
}
