package com.kinetic.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue

/**
 * Describes the small RAM working set nearest a clip's current playhead.
 * This is deliberately not cumulative whole-clip scan progress.
 */
object TimelineRamCache {
    data class Window(
        val startSec: Float,
        val preparedUntilSec: Float,
        val targetEndSec: Float,
        val readyFrames: Int,
        val totalFrames: Int,
        val readyTimesSec: List<Float>
    )

    val windows = mutableStateMapOf<String, Window>()
    var generation by mutableIntStateOf(0)
        private set
    private val signatures = mutableMapOf<String, String>()

    fun setWindow(
        layerId: String,
        signature: String,
        startSec: Float,
        targetEndSec: Float,
        readyTimesSec: List<Float>,
        totalFrames: Int
    ) {
        if (signatures[layerId] != signature) {
            windows.remove(layerId)
            signatures[layerId] = signature
        }
        val start = startSec.coerceAtLeast(0f)
        val end = targetEndSec.coerceAtLeast(start)
        val total = totalFrames.coerceAtLeast(0)
        val readyTimes = readyTimesSec.distinct().filter { it >= start && it <= end }.sorted()
        val ready = readyTimes.size.coerceIn(0, total)
        // Kept for compatibility with older UI consumers; exact readiness is
        // represented by readyTimesSec because centered prefetch is not a prefix.
        val preparedUntil = readyTimes.lastOrNull() ?: start
        windows[layerId] = Window(start, preparedUntil, end, ready, total, readyTimes)
    }

    fun invalidate() {
        windows.clear()
        generation++
    }

    fun clearWindow(layerId: String) {
        windows.remove(layerId)
    }

    fun retain(layerIds: Set<String>) {
        val stale = signatures.keys.filterNot { it in layerIds }
        for (key in stale) {
            signatures.remove(key)
            windows.remove(key)
        }
    }

    fun clear() {
        signatures.clear()
        windows.clear()
    }
}
