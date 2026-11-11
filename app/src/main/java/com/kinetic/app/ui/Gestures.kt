package com.kinetic.app.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope

/** Two-finger pinch only; single-finger drags pass through to scroll/drag handlers. */
suspend fun PointerInputScope.detectPinch(onZoom: (Float) -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.size >= 2) {
                val z = event.calculateZoom()
                if (z != 1f) {
                    onZoom(z)
                    event.changes.forEach { it.consume() }
                }
            }
        } while (event.changes.any { it.pressed })
    }
}
