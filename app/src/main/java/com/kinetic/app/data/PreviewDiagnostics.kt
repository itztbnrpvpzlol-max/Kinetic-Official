package com.kinetic.app.data

import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight preview telemetry. GL timings measure CPU-side draw submission,
 * while dropped-frame counts come from Media3 decoder analytics.
 */
object PreviewDiagnostics {
    data class Snapshot(
        val glFps: Float,
        val glSubmitMs: Float,
        val droppedVideoFrames: Long
    )

    private val droppedFrames = AtomicLong(0L)
    @Volatile private var glFps = 0f
    @Volatile private var glSubmitMs = 0f
    private var windowStartNs = 0L
    private var windowFrames = 0
    private var windowSubmitNs = 0L

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
        if (windowStartNs == 0L) windowStartNs = now
        windowFrames++
        windowSubmitNs += submitDurationNs.coerceAtLeast(0L)
        val elapsed = now - windowStartNs
        if (elapsed >= 1_000_000_000L) {
            glFps = windowFrames * 1_000_000_000f / elapsed.toFloat()
            glSubmitMs = windowSubmitNs / windowFrames.toFloat() / 1_000_000f
            windowStartNs = now
            windowFrames = 0
            windowSubmitNs = 0L
        }
    }

    fun snapshot() = Snapshot(glFps, glSubmitMs, droppedFrames.get())

    @Synchronized
    fun resetSession() {
        droppedFrames.set(0L)
        glFps = 0f
        glSubmitMs = 0f
        windowStartNs = 0L
        windowFrames = 0
        windowSubmitNs = 0L
    }
}
