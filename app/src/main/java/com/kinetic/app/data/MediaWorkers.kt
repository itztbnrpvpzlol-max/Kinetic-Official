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
    val bitmapCacheBudgetMb: Int = if (isConstrainedDevice) {
        (heapMb / 16).coerceIn(12, 24)
    } else {
        (heapMb / 8).coerceIn(32, 128)
    }

    val workerCount: Int = if (isConstrainedDevice) 1 else (cpuCores - 2).coerceIn(2, 4)
    private val cacheWorkerCount: Int = if (isConstrainedDevice) 1
        else if (cpuCores >= 8 && heapMb >= 512) 2 else 1

    val cpuDispatcher = Executors.newFixedThreadPool(
        workerCount,
        backgroundThreadFactory("KineticMediaWorker")
    ).asCoroutineDispatcher()

    // Cache generation stays separately bounded and never takes all CPU cores.
    val cacheDispatcher = Executors.newFixedThreadPool(
        cacheWorkerCount,
        backgroundThreadFactory("KineticFrameCache")
    ).asCoroutineDispatcher()

    private fun backgroundThreadFactory(name: String): ThreadFactory = ThreadFactory { runnable ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, name).apply { isDaemon = true }
    }
}
