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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link RetryPolicy}.
 *
 * <p>The bridge binder is established once at boot and torn down only on a
 * crash, so reconnecting is a rare event. These tests pin the retry curve so a
 * later "optimization" cannot silently reintroduce a busy-poll loop.
 */
public class RetryPolicyTest {

    // ------------------------------------------------------------------
    // delayForAttempt(int)
    // ------------------------------------------------------------------

    @Test
    public void delayForAttempt_startsAtInitialBackoff() {
        // Attempt 0 is the first retry, and the first delay must not be zero --
        // a zero delay turns "retry" into a busy-poll.
        assertEquals(RetryPolicy.INITIAL_BACKOFF_MS, RetryPolicy.delayForAttempt(0));
    }

    @Test
    public void delayForAttempt_growsExponentially() {
        assertEquals(100L, RetryPolicy.delayForAttempt(0));
        assertEquals(200L, RetryPolicy.delayForAttempt(1));
        assertEquals(400L, RetryPolicy.delayForAttempt(2));
        assertEquals(800L, RetryPolicy.delayForAttempt(3));
    }

    @Test
    public void delayForAttempt_clampsAtMaxBackoff() {
        // 100 << 8 = 25,600 -- under the 30s ceiling. 100 << 9 = 51,200 -- over.
        assertEquals(25_600L, RetryPolicy.delayForAttempt(8));
        assertEquals(RetryPolicy.MAX_BACKOFF_MS, RetryPolicy.delayForAttempt(9));
        assertEquals(RetryPolicy.MAX_BACKOFF_MS, RetryPolicy.delayForAttempt(20));
        assertEquals(RetryPolicy.MAX_BACKOFF_MS, RetryPolicy.delayForAttempt(100));
    }

    @Test
    public void delayForAttempt_isMonotonicNonDecreasing() {
        // A later attempt must never wait less than an earlier one; that would
        // mean the curve has an inverted branch somewhere.
        long previous = RetryPolicy.delayForAttempt(0);
        for (int attempt = 1; attempt <= 50; attempt++) {
            long current = RetryPolicy.delayForAttempt(attempt);
            assertTrue(
                    "attempt " + attempt + " went backwards: " + current + " < " + previous,
                    current >= previous);
            previous = current;
        }
    }

    @Test
    public void delayForAttempt_neverExceedsMaxForAnyAttempt() {
        // Sweep rather than sample: a bad branch at some untested magnitude is
        // exactly what survives spot checks.
        for (int attempt = 0; attempt <= 100; attempt++) {
            long delay = RetryPolicy.delayForAttempt(attempt);
            assertTrue(
                    "attempt " + attempt + " exceeded max: " + delay,
                    delay >= 0 && delay <= RetryPolicy.MAX_BACKOFF_MS);
        }
    }

    @Test
    public void delayForAttempt_handlesNegativeAttempts() {
        // Negative input can come from a caller that miscounts on a clock reset.
        assertEquals(RetryPolicy.INITIAL_BACKOFF_MS, RetryPolicy.delayForAttempt(-1));
        assertEquals(RetryPolicy.INITIAL_BACKOFF_MS, RetryPolicy.delayForAttempt(Integer.MIN_VALUE));
    }

    // ------------------------------------------------------------------
    // delayForAttempt(long elapsedMs, int attempt)
    // ------------------------------------------------------------------

    @Test
    public void delayForAttempt_withElapsed_returnsZeroAfterTimeout() {
        // Once the total timeout has elapsed, the caller must stop retrying.
        // Returning 0 is the termination signal.
        assertEquals(0L, RetryPolicy.delayForAttempt(RetryPolicy.TOTAL_TIMEOUT_MS, 0));
        assertEquals(0L, RetryPolicy.delayForAttempt(RetryPolicy.TOTAL_TIMEOUT_MS + 1, 5));
        assertEquals(0L, RetryPolicy.delayForAttempt(Long.MAX_VALUE, 0));
    }

    @Test
    public void delayForAttempt_withElapsed_behavesLikeCurveBeforeTimeout() {
        // Before the timeout, the elapsed variant must agree with the attempt
        // variant exactly -- otherwise one of them drifts.
        assertEquals(
                RetryPolicy.delayForAttempt(3),
                RetryPolicy.delayForAttempt(RetryPolicy.TOTAL_TIMEOUT_MS - 1, 3));
    }

    @Test
    public void delayForAttempt_withElapsed_isBoundedAcrossSweep() {
        // Sweep elapsed x attempt: the result must either be 0 (timed out) or
        // within the curve bounds. No other value is allowed.
        for (long elapsed = 0; elapsed <= RetryPolicy.TOTAL_TIMEOUT_MS * 2; elapsed += 1000) {
            for (int attempt = 0; attempt <= 50; attempt++) {
                long delay = RetryPolicy.delayForAttempt(elapsed, attempt);
                assertTrue(
                        "elapsed=" + elapsed + " attempt=" + attempt + " gave " + delay,
                        delay == 0L || (delay > 0 && delay <= RetryPolicy.MAX_BACKOFF_MS));
            }
        }
    }

    // ------------------------------------------------------------------
    // Constants: callers and these tests both read them.
    // ------------------------------------------------------------------

    @Test
    public void constantsAreSane() {
        // These relationships are what make the class safe to use. If any
        // constant moves outside these bounds, the retry logic breaks in a way
        // a unit test should catch rather than a device.
        assertTrue(RetryPolicy.INITIAL_BACKOFF_MS > 0);
        assertTrue(RetryPolicy.BACKOFF_MULTIPLIER > 1.0);
        assertTrue(RetryPolicy.MAX_BACKOFF_MS >= RetryPolicy.INITIAL_BACKOFF_MS);
        assertTrue(RetryPolicy.TOTAL_TIMEOUT_MS > RetryPolicy.INITIAL_BACKOFF_MS);
        assertTrue(RetryPolicy.FAST_POLL_INTERVAL_MS > 0);
        assertTrue(RetryPolicy.FAST_POLL_INTERVAL_MS <= RetryPolicy.MAX_BACKOFF_MS);
    }

    @Test
    public void totalTimeoutCoversReasonableRetryCount() {
        // The timeout must be long enough to cover the retries a real reconnect
        // needs. If TOTAL_TIMEOUT_MS is shorter than the time needed for a few
        // backoff steps, the elapsed variant would cut retries off too early.
        long cumulative = 0;
        int attempts = 0;
        while (cumulative < RetryPolicy.TOTAL_TIMEOUT_MS) {
            cumulative += RetryPolicy.delayForAttempt(attempts);
            attempts++;
        }
        // At least three attempts must fit inside the timeout.
        assertTrue("timeout too short for useful retries: " + attempts, attempts >= 3);
    }
}
