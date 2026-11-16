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
        val glFrameMs: Float,
        val gpuTextureUploadMs: Float,
        val gpuTextureUploads: Long,
        val gpuTextureCacheMb: Float,
        val ramFrameCacheMb: Float,
        val droppedVideoFrames: Long,
        val cpuDrawFps: Float,
        val cpuDrawMs: Float,
        val cpuDecodeMs: Float,
        val cpuQueueMs: Float,
        val cpuRamHitPercent: Int,
        val cpuRequests: Long,
        val cpuRamHits: Long,
        val cpuDiskHits: Long,
        val surfaceFps: Float,
        val surfaceFrames: Long,
        val surfaceActive: Boolean
    )

    private val droppedFrames = AtomicLong(0L)
    private val cpuRequests = AtomicLong(0L)
    private val cpuRamHits = AtomicLong(0L)
    private val cpuDiskHits = AtomicLong(0L)
    private val surfaceFrames = AtomicLong(0L)
    @Volatile private var surfaceActive = false
    @Volatile private var surfaceFps = 0f
    private var surfaceWindowStartNs = 0L
    private var surfaceWindowFrames = 0
    private val cpuDecodeCount = AtomicLong(0L)
    private val cpuDecodeTotalNs = AtomicLong(0L)
    private val cpuQueueTotalNs = AtomicLong(0L)

    @Volatile private var glFps = 0f
    @Volatile private var glSubmitMs = 0f
    @Volatile private var glFrameMs = 0f
    @Volatile private var gpuTextureUploadMs = 0f
    @Volatile private var gpuTextureCacheMb = 0f
    private val gpuTextureUploads = AtomicLong(0L)
    private val gpuTextureUploadTotalNs = AtomicLong(0L)
    @Volatile private var cpuDrawFps = 0f
    @Volatile private var cpuDrawMs = 0f
    private var glWindowStartNs = 0L
    private var glWindowFrames = 0
    private var glWindowSubmitNs = 0L
    private var cpuWindowStartNs = 0L
    private var cpuWindowFrames = 0
    private var cpuWindowDrawNs = 0L

    fun setSurfacePreviewActive(active: Boolean) {
        surfaceActive = active
        if (!active) synchronized(this) {
            surfaceFps = 0f
            surfaceWindowStartNs = 0L
            surfaceWindowFrames = 0
        }
    }

    @Synchronized
    fun recordSurfaceVideoFrame() {
        surfaceFrames.incrementAndGet()
        val now = System.nanoTime()
        if (surfaceWindowStartNs == 0L) surfaceWindowStartNs = now
        surfaceWindowFrames++
        val elapsed = now - surfaceWindowStartNs
        if (elapsed >= 1_000_000_000L) {
            surfaceFps = surfaceWindowFrames * 1_000_000_000f / elapsed.toFloat()
            surfaceWindowStartNs = now
            surfaceWindowFrames = 0
        }
    }

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

    fun recordGpuTextureUpload(durationNs: Long) {
        gpuTextureUploads.incrementAndGet()
        gpuTextureUploadTotalNs.addAndGet(durationNs.coerceAtLeast(0L))
        gpuTextureUploadMs = gpuTextureUploadTotalNs.get() /
            gpuTextureUploads.get().coerceAtLeast(1L) / 1_000_000f
    }

    fun setGpuTextureCacheBytes(bytes: Long) {
        gpuTextureCacheMb = bytes.coerceAtLeast(0L) / (1024f * 1024f)
    }

    @Synchronized
    fun recordGlFrame(submitDurationNs: Long) {
        glFrameMs = submitDurationNs.coerceAtLeast(0L) / 1_000_000f
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
            glFrameMs = glFrameMs,
            gpuTextureUploadMs = gpuTextureUploadMs,
            gpuTextureUploads = gpuTextureUploads.get(),
            gpuTextureCacheMb = gpuTextureCacheMb,
            ramFrameCacheMb = MediaLoader.compressedPreviewFrameCacheBytes() / (1024f * 1024f),
            droppedVideoFrames = droppedFrames.get(),
            cpuDrawFps = cpuDrawFps,
            cpuDrawMs = cpuDrawMs,
            cpuDecodeMs = if (decodes == 0L) 0f else cpuDecodeTotalNs.get() / decodes.toFloat() / 1_000_000f,
            cpuQueueMs = if (requests == 0L) 0f else cpuQueueTotalNs.get() / requests.toFloat() / 1_000_000f,
            cpuRamHitPercent = if (requests == 0L) 0 else (cpuRamHits.get() * 100L / requests).toInt(),
            cpuRequests = requests,
            cpuRamHits = cpuRamHits.get(),
            cpuDiskHits = cpuDiskHits.get(),
            surfaceFps = surfaceFps,
            surfaceFrames = surfaceFrames.get(),
            surfaceActive = surfaceActive
        )
    }

    @Synchronized
    fun resetSession() {
        droppedFrames.set(0L)
        cpuRequests.set(0L)
        cpuRamHits.set(0L)
        cpuDiskHits.set(0L)
        surfaceFrames.set(0L)
        surfaceFps = 0f
        surfaceWindowStartNs = 0L
        surfaceWindowFrames = 0
        cpuDecodeCount.set(0L)
        cpuDecodeTotalNs.set(0L)
        cpuQueueTotalNs.set(0L)
        glFps = 0f
        glSubmitMs = 0f
        glFrameMs = 0f
        gpuTextureUploadMs = 0f
        gpuTextureUploads.set(0L)
        gpuTextureUploadTotalNs.set(0L)
        gpuTextureCacheMb = 0f
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
