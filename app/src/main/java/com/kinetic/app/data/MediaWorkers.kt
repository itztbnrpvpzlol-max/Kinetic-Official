package com.kinetic.app.data

import android.os.Process
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * Device-aware limits for background media work.
 *
 * Low-memory / four-core devices keep one worker in each pool so decoding and
 * idle cache generation cannot occupy every CPU core or grow competing queues.
 * Larger devices can use more parallelism while retaining UI/audio headroom.
 */
object MediaWorkers {
    private val cpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    private val heapMb = (Runtime.getRuntime().maxMemory() / (1024L * 1024L))
        .toInt().coerceAtLeast(64)

    val isConstrainedDevice: Boolean = cpuCores <= 4 || heapMb <= 256
    // Hardware decoders are scarce system resources, not a thread pool.
    // Keep one on low-RAM/four-core phones; raise concurrency only with
    // substantially more CPU and heap headroom.
    private val playbackRequestCount = java.util.concurrent.atomic.AtomicInteger(0)
    val isPlaybackRequested: Boolean get() = playbackRequestCount.get() > 0
    fun beginPlaybackRequest() { playbackRequestCount.incrementAndGet() }
    fun endPlaybackRequest() { playbackRequestCount.updateAndGet { (it - 1).coerceAtLeast(0) } }

    val maxConcurrentVideoDecoders: Int = when {
        isConstrainedDevice -> 1
        cpuCores >= 8 && heapMb >= 512 -> 4
        else -> 2
    }

    val workerCount: Int = if (isConstrainedDevice) 1 else (cpuCores - 2).coerceIn(2, 4)
    private val cacheWorkerCount: Int = if (isConstrainedDevice) 1
        else if (cpuCores >= 8 && heapMb >= 512) 2 else 1

    val cpuDispatcher = Executors.newFixedThreadPool(
        workerCount,
        backgroundThreadFactory("KineticMediaWorker")
    ).asCoroutineDispatcher()

    // Interactive preview decoding must not inherit the background priority
    // used by imports, waveforms, and other non-urgent media work.
    val previewDispatcher = Executors.newSingleThreadExecutor(
        foregroundThreadFactory("KineticPreviewDecoder")
    ).asCoroutineDispatcher()

    // Cache generation stays separately bounded and never takes all CPU cores.
    val cacheDispatcher = Executors.newFixedThreadPool(
        cacheWorkerCount,
        cacheThreadFactory("KineticFrameCache")
    ).asCoroutineDispatcher()

    private fun foregroundThreadFactory(name: String): ThreadFactory = ThreadFactory { runnable ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND)
            runnable.run()
        }, name).apply { isDaemon = true }
    }

    private fun cacheThreadFactory(name: String): ThreadFactory = ThreadFactory { runnable ->
        Thread({
            // Keep look-ahead below the interactive decoder, but runnable enough
            // to prepare the next frame while the preview decodes the current one.
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, name).apply { isDaemon = true }
    }

    private fun backgroundThreadFactory(name: String): ThreadFactory = ThreadFactory { runnable ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, name).apply { isDaemon = true }
    }
}
