package com.kinetic.app.data

import androidx.compose.runtime.mutableStateMapOf

/**
 * Tracks a persistent sequential RAM-cache pass for each video clip.
 * The scan cursor is independent of the playhead and playback state.
 */
object TimelineRamCache {
    data class Window(
        val startSec: Float,
        val preparedUntilSec: Float,
        val targetEndSec: Float
    )

    data class Scan(
        val signature: String,
        val startSec: Float,
        val endSec: Float,
        val totalFrames: Int,
        val nextFrameIndex: Int
    )

    val windows = mutableStateMapOf<String, Window>()
    private val scans = mutableMapOf<String, Scan>()

    fun ensure(
        layerId: String,
        signature: String,
        startSec: Float,
        durationSec: Float,
        sampleFps: Int
    ): Scan {
        val existing = scans[layerId]
        if (existing != null && existing.signature == signature) return existing

        val start = startSec.coerceAtLeast(0f)
        val end = (start + durationSec.coerceAtLeast(0f)).coerceAtLeast(start)
        val fps = sampleFps.coerceAtLeast(1)
        val total = (durationSec.coerceAtLeast(0f) * fps).toInt() + 1
        val next = Scan(signature, start, end, total.coerceAtLeast(1), 0)
        scans[layerId] = next
        windows[layerId] = Window(start, start, end)
        return next
    }

    fun scan(layerId: String): Scan? = scans[layerId]

    fun markPrepared(layerId: String, frameIndex: Int, timelineTimeSec: Float) {
        val current = scans[layerId] ?: return
        if (current.nextFrameIndex != frameIndex) return

        val nextIndex = (frameIndex + 1).coerceAtMost(current.totalFrames)
        scans[layerId] = current.copy(nextFrameIndex = nextIndex)
        val previous = windows[layerId]
        val preparedUntil = if (nextIndex >= current.totalFrames) {
            current.endSec
        } else {
            timelineTimeSec
        }
        windows[layerId] = Window(
            current.startSec,
            maxOf(previous?.preparedUntilSec ?: current.startSec, preparedUntil),
            current.endSec
        )
    }

    fun retain(layerIds: Set<String>) {
        val stale = scans.keys.filterNot { it in layerIds }
        for (key in stale) {
            scans.remove(key)
            windows.remove(key)
        }
    }

    fun clear() {
        scans.clear()
        windows.clear()
    }
}
