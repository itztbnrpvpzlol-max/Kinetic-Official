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
        val totalFrames: Int
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
        readyFrames: Int,
        totalFrames: Int
    ) {
        if (signatures[layerId] != signature) {
            windows.remove(layerId)
            signatures[layerId] = signature
        }
        val start = startSec.coerceAtLeast(0f)
        val end = targetEndSec.coerceAtLeast(start)
        val total = totalFrames.coerceAtLeast(0)
        val ready = readyFrames.coerceIn(0, total)
        val preparedUntil = if (total == 0) start else {
            start + (end - start) * (ready.toFloat() / total.toFloat())
        }
        windows[layerId] = Window(start, preparedUntil, end, ready, total)
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
