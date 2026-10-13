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
 * Unit tests for {@link CacheSizing}.
 *
 * <p>The management UI is injected into the Settings and SystemUI processes, so
 * an unbounded cache here is paid for by a process the user never chose to open.
 * These tests pin the bounds that keep that cost bounded.
 */
public class CacheSizingTest {

    private static final long KB = 1024L;
    private static final long MB = 1024L * 1024L;

    // ------------------------------------------------------------------
    // iconCacheSizeKb
    // ------------------------------------------------------------------

    @Test
    public void iconCacheSizeKb_isOneSixteenthOfHeapWithinBounds() {
        // 256MB heap -> 16MB. The old policy took a quarter (64MB) for the same
        // device, which is four times the new ceiling on a small heap.
        assertEquals(16 * 1024, CacheSizing.iconCacheSizeKb(256 * MB));
    }

    @Test
    public void iconCacheSizeKb_clampsToCeilingOnLargeHeaps() {
        // This is the point of the max bound: a 1GB heap must not produce a
        // proportionally larger cache for a list of the same few hundred icons.
        assertEquals(CacheSizing.ICON_CACHE_MAX_KB, CacheSizing.iconCacheSizeKb(1024 * MB));
        assertEquals(CacheSizing.ICON_CACHE_MAX_KB, CacheSizing.iconCacheSizeKb(4096 * MB));
    }

    @Test
    public void iconCacheSizeKb_clampsToFloorOnSmallHeaps() {
        // Below the floor we must still hand back something usable rather than
        // a cache so small that every icon is evicted on the next one.
        assertEquals(CacheSizing.ICON_CACHE_MIN_KB, CacheSizing.iconCacheSizeKb(64 * MB));
        assertEquals(CacheSizing.ICON_CACHE_MIN_KB, CacheSizing.iconCacheSizeKb(16 * MB));
        assertEquals(CacheSizing.ICON_CACHE_MIN_KB, CacheSizing.iconCacheSizeKb(1 * MB));
    }

    @Test
    public void iconCacheSizeKb_handlesDegenerateHeapValues() {
        // maxMemory() is not guaranteed to be sane on every runtime.
        assertEquals(CacheSizing.ICON_CACHE_MIN_KB, CacheSizing.iconCacheSizeKb(0));
        assertEquals(CacheSizing.ICON_CACHE_MIN_KB, CacheSizing.iconCacheSizeKb(-1));
    }

    @Test
    public void iconCacheSizeKb_neverExceedsCeilingForAnyHeap() {
        // Sweep rather than sample: a bad branch at some untested magnitude is
        // exactly the kind of thing that survives spot checks.
        for (long heapMb = 1; heapMb <= 2048; heapMb *= 2) {
            int size = CacheSizing.iconCacheSizeKb(heapMb * MB);
            assertTrue(
                    "heap " + heapMb + "MB produced " + size + "KB",
                    size >= CacheSizing.ICON_CACHE_MIN_KB && size <= CacheSizing.ICON_CACHE_MAX_KB);
        }
    }

    @Test
    public void iconCacheSizeKb_isMonotonicNonDecreasing() {
        // A larger heap must never yield a smaller cache; that would mean the
        // clamp has an inverted branch.
        int previous = CacheSizing.iconCacheSizeKb(0);
        for (long heapMb = 1; heapMb <= 2048; heapMb *= 2) {
            int current = CacheSizing.iconCacheSizeKb(heapMb * MB);
            assertTrue(
                    "heap " + heapMb + "MB went backwards: " + current + " < " + previous,
                    current >= previous);
            previous = current;
        }
    }

    // ------------------------------------------------------------------
    // workerThreadCount
    // ------------------------------------------------------------------

    @Test
    public void workerThreadCount_staysSmallOnManyCoreDevices() {
        // The old code gave the icon and label caches each cores/2 threads. On
        // an 8-core device that was eight background threads for one list
        // screen. They now share a single pool.
        assertEquals(2, CacheSizing.workerThreadCount(8));
        assertEquals(2, CacheSizing.workerThreadCount(16));
        assertEquals(2, CacheSizing.workerThreadCount(64));
    }

    @Test
    public void workerThreadCount_scalesDownOnFewCoreDevices() {
        assertEquals(1, CacheSizing.workerThreadCount(1));
        assertEquals(1, CacheSizing.workerThreadCount(2));
        assertEquals(1, CacheSizing.workerThreadCount(3));
        assertEquals(2, CacheSizing.workerThreadCount(4));
    }

    @Test
    public void workerThreadCount_handlesDegenerateProcessorCounts() {
        // availableProcessors() can return 0 on some containers.
        assertEquals(CacheSizing.MIN_WORKER_THREADS, CacheSizing.workerThreadCount(0));
        assertEquals(CacheSizing.MIN_WORKER_THREADS, CacheSizing.workerThreadCount(-1));
    }

    @Test
    public void workerThreadCount_neverExceedsBoundForAnyCoreCount() {
        for (int cores = 0; cores <= 128; cores++) {
            int threads = CacheSizing.workerThreadCount(cores);
            assertTrue(
                    cores + " cores produced " + threads + " threads",
                    threads >= CacheSizing.MIN_WORKER_THREADS
                            && threads <= CacheSizing.MAX_WORKER_THREADS);
        }
    }

    // ------------------------------------------------------------------
    // Constants: callers and these tests both read them.
    // ------------------------------------------------------------------

    @Test
    public void boundsAreOrdered() {
        // If MIN ever exceeds MAX, iconCacheSizeKb's two clamps contradict each
        // other and the result depends on which is checked first.
        assertTrue(CacheSizing.ICON_CACHE_MIN_KB < CacheSizing.ICON_CACHE_MAX_KB);
        assertTrue(CacheSizing.MIN_WORKER_THREADS <= CacheSizing.MAX_WORKER_THREADS);
    }

    @Test
    public void iconCacheIsCappedWellBelowTheOldQuarterHeapPolicy() {
        // Guard against someone "restoring" the old generous sizing. A 512MB
        // heap used to mean 128MB of bitmaps; it should now be 32MB.
        int newSize = CacheSizing.iconCacheSizeKb(512 * MB);
        int oldSize = (int) ((512 * MB / 1024) / 4);
        assertTrue(
                "new size " + newSize + "KB is not materially smaller than old " + oldSize + "KB",
                newSize * 4 <= oldSize);
        assertEquals(32 * 1024, newSize);
    }
}
