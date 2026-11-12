package com.kinetic.app.data

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight preview telemetry for both render paths.
 * GL submit time and CPU draw time measure app-side work, not physical panel FPS.
 */
@OptIn(UnstableApi::class)
object PreviewDiagnostics {
    data class Snapshot(
        val glFps: Float,
        val glSubmitMs: Float,
        val droppedVideoFrames: Long,
        val cpuDrawFps: Float,
        val cpuDrawMs: Float,
        val cpuDecodeMs: Float,
        val cpuQueueMs: Float,
        val cpuRamHitPercent: Int,
        val cpuRequests: Long,
        val cpuRamHits: Long,
        val cpuDiskHits: Long
    )

    private val droppedFrames = AtomicLong(0L)
    private val cpuRequests = AtomicLong(0L)
    private val cpuRamHits = AtomicLong(0L)
    private val cpuDiskHits = AtomicLong(0L)
    private val cpuDecodeCount = AtomicLong(0L)
    private val cpuDecodeTotalNs = AtomicLong(0L)
    private val cpuQueueTotalNs = AtomicLong(0L)

    @Volatile private var glFps = 0f
    @Volatile private var glSubmitMs = 0f
    @Volatile private var cpuDrawFps = 0f
    @Volatile private var cpuDrawMs = 0f
    private var glWindowStartNs = 0L
    private var glWindowFrames = 0
    private var glWindowSubmitNs = 0L
    private var cpuWindowStartNs = 0L
    private var cpuWindowFrames = 0
    private var cpuWindowDrawNs = 0L

    fun attach(player: ExoPlayer) {
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long
            ) {
                if (droppedFrames > 0) this@PreviewDiagnostics.droppedFrames.addAndGet(droppedFrames.toLong())
            }
        })
    }

    @Synchronized
    fun recordGlFrame(submitDurationNs: Long) {
        val now = System.nanoTime()
        if (glWindowStartNs == 0L) glWindowStartNs = now
        glWindowFrames++
        glWindowSubmitNs += submitDurationNs.coerceAtLeast(0L)
        val elapsed = now - glWindowStartNs
        if (elapsed >= 1_000_000_000L) {
            glFps = glWindowFrames * 1_000_000_000f / elapsed.toFloat()
            glSubmitMs = glWindowSubmitNs / glWindowFrames.toFloat() / 1_000_000f
            glWindowStartNs = now
            glWindowFrames = 0
            glWindowSubmitNs = 0L
        }
    }

    fun recordCpuFrameRequest(ramHit: Boolean, diskHit: Boolean, decodeNs: Long, queueNs: Long) {
        cpuRequests.incrementAndGet()
        if (ramHit) cpuRamHits.incrementAndGet()
        if (diskHit) cpuDiskHits.incrementAndGet()
        if (decodeNs > 0L) {
            cpuDecodeCount.incrementAndGet()
            cpuDecodeTotalNs.addAndGet(decodeNs)
        }
        cpuQueueTotalNs.addAndGet(queueNs.coerceAtLeast(0L))
    }

    @Synchronized
    fun recordCpuDrawFrame(drawDurationNs: Long) {
        val now = System.nanoTime()
        if (cpuWindowStartNs == 0L) cpuWindowStartNs = now
        cpuWindowFrames++
        cpuWindowDrawNs += drawDurationNs.coerceAtLeast(0L)
        val elapsed = now - cpuWindowStartNs
        if (elapsed >= 1_000_000_000L) {
            cpuDrawFps = cpuWindowFrames * 1_000_000_000f / elapsed.toFloat()
            cpuDrawMs = cpuWindowDrawNs / cpuWindowFrames.toFloat() / 1_000_000f
            cpuWindowStartNs = now
            cpuWindowFrames = 0
            cpuWindowDrawNs = 0L
        }
    }

    fun snapshot(): Snapshot {
        val requests = cpuRequests.get()
        val decodes = cpuDecodeCount.get()
        return Snapshot(
            glFps = glFps,
            glSubmitMs = glSubmitMs,
            droppedVideoFrames = droppedFrames.get(),
            cpuDrawFps = cpuDrawFps,
            cpuDrawMs = cpuDrawMs,
            cpuDecodeMs = if (decodes == 0L) 0f else cpuDecodeTotalNs.get() / decodes.toFloat() / 1_000_000f,
            cpuQueueMs = if (requests == 0L) 0f else cpuQueueTotalNs.get() / requests.toFloat() / 1_000_000f,
            cpuRamHitPercent = if (requests == 0L) 0 else (cpuRamHits.get() * 100L / requests).toInt(),
            cpuRequests = requests,
            cpuRamHits = cpuRamHits.get(),
            cpuDiskHits = cpuDiskHits.get()
        )
    }

    @Synchronized
    fun resetSession() {
        droppedFrames.set(0L)
        cpuRequests.set(0L)
        cpuRamHits.set(0L)
        cpuDiskHits.set(0L)
        cpuDecodeCount.set(0L)
        cpuDecodeTotalNs.set(0L)
        cpuQueueTotalNs.set(0L)
        glFps = 0f
        glSubmitMs = 0f
        cpuDrawFps = 0f
        cpuDrawMs = 0f
        glWindowStartNs = 0L
        glWindowFrames = 0
        glWindowSubmitNs = 0L
        cpuWindowStartNs = 0L
        cpuWindowFrames = 0
        cpuWindowDrawNs = 0L
    }
}
