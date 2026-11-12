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

    // Two bounded workers let independent composition frames render in parallel.
    // Background priority keeps interactive preview and the dedicated audio thread
    // responsive; we deliberately leave CPU headroom for Android and the UI.
    val cacheDispatcher = Executors.newFixedThreadPool(2) { runnable ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "KineticFrameCache").apply { isDaemon = true }
    }.asCoroutineDispatcher()
}
