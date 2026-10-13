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
 * Sizing policy for the management UI's in-memory caches.
 *
 * <p>The management UI is injected into the Settings and SystemUI processes, so
 * everything it allocates is charged against a process the user did not choose
 * to open. This class exists to keep that cost bounded.
 *
 * <p>It deliberately has no Android dependencies so it can be exercised by a
 * plain JVM unit test. Do not add {@code android.*} imports here; callers pass
 * in the raw values from {@code Runtime} instead.
 */
public final class CacheSizing {

    /**
     * Divisor applied to the heap to size the icon cache.
     *
     * <p>The previous code used 1/4 of the heap. On a 512MB heap that is 128MB
     * of retained {@code Bitmap} allocations in the Settings process, for a list
     * screen that is opened occasionally and holds at most a few hundred icons.
     */
    public static final int ICON_CACHE_HEAP_DIVISOR = 16;

    /** Lower bound, so a small heap still gets a usable cache. */
    public static final int ICON_CACHE_MIN_KB = 8 * 1024;

    /** Hard ceiling. Never exceed this regardless of how large the heap is. */
    public static final int ICON_CACHE_MAX_KB = 64 * 1024;

    /** Upper bound on worker threads shared by the icon and label caches. */
    public static final int MAX_WORKER_THREADS = 2;

    /** Lower bound; at least one worker is needed to make progress. */
    public static final int MIN_WORKER_THREADS = 1;

    private CacheSizing() {}

    /**
     * Icon cache capacity in kilobytes.
     *
     * <p>One sixteenth of the heap, clamped into [{@link #ICON_CACHE_MIN_KB},
     * {@link #ICON_CACHE_MAX_KB}]. The ceiling is the point of the clamp: a
     * device with a large heap should not get a proportionally large cache for a
     * screen that shows the same few hundred icons.
     *
     * @param maxMemoryBytes {@code Runtime.getRuntime().maxMemory()}; a value of
     *                       zero or less falls back to {@link #ICON_CACHE_MIN_KB}
     * @return capacity in KB, for {@code LruCache} whose {@code sizeOf} returns KB
     */
    public static int iconCacheSizeKb(long maxMemoryBytes) {
        if (maxMemoryBytes <= 0) {
            return ICON_CACHE_MIN_KB;
        }

        long heapKb = maxMemoryBytes / 1024L;
        long target = heapKb / ICON_CACHE_HEAP_DIVISOR;

        if (target < ICON_CACHE_MIN_KB) {
            return ICON_CACHE_MIN_KB;
        }
        if (target > ICON_CACHE_MAX_KB) {
            return ICON_CACHE_MAX_KB;
        }
        return (int) target;
    }

    /**
     * Number of worker threads for the shared icon/label dispatcher.
     *
     * <p>The icon and label caches previously each created their own pool of
     * {@code availableProcessors / 2} threads, so an 8-core device ran eight
     * background threads to fill two caches on one list screen. They now share a
     * single pool, which is why this number is small.
     *
     * @param availableProcessors {@code Runtime.getRuntime().availableProcessors()};
     *                            a value of zero or less falls back to
     *                            {@link #MIN_WORKER_THREADS}
     * @return thread count in [{@link #MIN_WORKER_THREADS}, {@link #MAX_WORKER_THREADS}]
     */
    public static int workerThreadCount(int availableProcessors) {
        if (availableProcessors <= 0) {
            return MIN_WORKER_THREADS;
        }

        // Half the cores, then clamp. The old code used cores/2 with no upper
        // bound, and created one such pool per cache -- two pools, because the
        // icon and label caches each built their own.
        int threads = availableProcessors / 2;
        if (threads < MIN_WORKER_THREADS) {
            return MIN_WORKER_THREADS;
        }
        if (threads > MAX_WORKER_THREADS) {
            return MAX_WORKER_THREADS;
        }
        return threads;
    }
}
