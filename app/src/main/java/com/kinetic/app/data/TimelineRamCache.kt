package com.kinetic.app.data

import androidx.compose.runtime.mutableStateMapOf

/**
 * Describes the contiguous decoded-frame window currently held for a video
 * layer. This is a live RAM window, not a disk proxy or a promise that the
 * entire clip is resident.
 */
object TimelineRamCache {
    data class Window(
        val startSec: Float,
        val cachedUntilSec: Float,
        val targetEndSec: Float
    )

    val windows = mutableStateMapOf<String, Window>()

    fun publish(layerId: String, startSec: Float, cachedUntilSec: Float, targetEndSec: Float) {
        windows[layerId] = Window(
            startSec.coerceAtLeast(0f),
            cachedUntilSec.coerceAtLeast(startSec),
            targetEndSec.coerceAtLeast(cachedUntilSec)
        )
    }

    fun retain(layerIds: Set<String>) {
        val staleKeys = windows.keys.toList().filterNot { it in layerIds }
        for (key in staleKeys) windows.remove(key)
    }

    fun clear() {
        windows.clear()
    }
}
