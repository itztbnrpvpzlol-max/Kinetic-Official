package com.kinetic.app.data

import android.os.Process
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * Bounded CPU dispatcher for media decoding and image processing.
 *
 * Keep at least one logical processor available where possible for the UI,
 * audio scheduling, rendering and Android itself. Background priority prevents
 * thumbnail/waveform work from starving interactive playback.
 */
object MediaWorkers {
    val workerCount: Int = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)

    val cpuDispatcher = Executors.newFixedThreadPool(
        workerCount,
        ThreadFactory { runnable ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                runnable.run()
            }, "KineticMediaWorker").apply { isDaemon = true }
        }
    ).asCoroutineDispatcher()

    // Composition pre-rendering must never occupy the interactive media pool.
    // A single background-priority worker serializes cache writes and keeps
    // decoder/thumbnail work available to the live preview.
    val cacheDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_LOWEST)
            runnable.run()
        }, "KineticFrameCache").apply { isDaemon = true }
    }.asCoroutineDispatcher()
}
