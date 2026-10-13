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

package rikka.sui.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The single background dispatcher shared by the management UI's caches.
 *
 * [AppIconCache] and [AppLabelCache] each used to build their own pool of
 * `availableProcessors / 2` threads. On an 8-core device that meant eight
 * background threads resident in the Settings process to service one list
 * screen -- and because the UI is injected rather than launched, those threads
 * are charged to a process the user never chose to open.
 *
 * One shared, bounded pool replaces both. The bound itself lives in
 * [CacheSizing] so it can be unit tested without an Android runtime.
 */
object UiWorkerDispatcher {

    val dispatcher: CoroutineDispatcher by lazy {
        val availableProcessors = try {
            Runtime.getRuntime().availableProcessors()
        } catch (ignored: Exception) {
            1
        }

        val threadCount = CacheSizing.workerThreadCount(availableProcessors)
        val executor: Executor = Executors.newFixedThreadPool(threadCount) { runnable ->
            Thread(runnable).apply {
                // Both caches are pure lookups feeding a RecyclerView. Nothing
                // here competes with the main thread, so stay out of its way.
                priority = Thread.MIN_PRIORITY
                name = "SuiUiWorker"
            }
        }
        executor.asCoroutineDispatcher()
    }
}
