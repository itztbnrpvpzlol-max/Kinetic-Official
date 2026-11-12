package com.kinetic.app.engine

import android.os.SystemClock
import kotlin.math.min

/**
 * Owns the authoritative editor playback clock.
 *
 * Native builds use std::chrono::steady_clock behind JNI so playback time is
 * independent of Compose recomposition and display-frame callback timing.
 * A monotonic Kotlin fallback keeps the editor usable if the native library
 * cannot be loaded for an ABI.
 */
class NativePlaybackEngine {
    private val handle: Long = if (NativeRenderEngine.available) {
        runCatching { nativeCreate() }.getOrDefault(0L)
    } else {
        0L
    }

    private var fallbackPosition = 0f
    private var fallbackDuration = 0f
    private var fallbackSpeed = 1f
    private var fallbackLooping = false
    private var fallbackPlaying = false
    private var fallbackAnchorNs = SystemClock.elapsedRealtimeNanos()
    private var released = false

    fun configure(durationSeconds: Float, looping: Boolean, speed: Float = 1f) {
        if (released) return
        fallbackPosition = positionSeconds()
        fallbackDuration = durationSeconds.coerceAtLeast(0f)
        fallbackLooping = looping
        fallbackSpeed = speed.coerceIn(0.1f, 4f)
        if (handle != 0L) {
            nativeConfigure(handle, fallbackDuration.toDouble(), looping, fallbackSpeed.toDouble())
        } else if (fallbackDuration > 0f) {
            fallbackPosition = min(fallbackPosition, fallbackDuration)
        }
        fallbackAnchorNs = SystemClock.elapsedRealtimeNanos()
    }

    fun seek(positionSeconds: Float) {
        if (released) return
        val upper = if (fallbackDuration > 0f) fallbackDuration else Float.MAX_VALUE
        fallbackPosition = positionSeconds.coerceIn(0f, upper)
        fallbackAnchorNs = SystemClock.elapsedRealtimeNanos()
        if (handle != 0L) nativeSeek(handle, fallbackPosition.toDouble())
    }

    fun play() {
        if (released) return
        if (handle != 0L) {
            nativePlay(handle)
        } else {
            if (fallbackDuration > 0f && fallbackPosition >= fallbackDuration) {
                if (fallbackLooping) fallbackPosition = 0f else return
            }
            fallbackAnchorNs = SystemClock.elapsedRealtimeNanos()
            fallbackPlaying = true
        }
    }

    fun pause() {
        if (released) return
        if (handle != 0L) {
            nativePause(handle)
        } else {
            fallbackPosition = fallbackPositionNow()
            fallbackPlaying = false
            fallbackAnchorNs = SystemClock.elapsedRealtimeNanos()
        }
    }

    fun positionSeconds(): Float {
        if (released) return fallbackPosition
        if (handle != 0L) return nativePosition(handle).toFloat()
        return fallbackPositionNow()
    }

    /** Frame index sampled directly from the authoritative native monotonic clock. */
    fun positionFrame(fps: Float): Long {
        if (released) return 0L
        val safeFps = fps.coerceIn(1f, 240f)
        if (handle != 0L) return nativePositionFrame(handle, safeFps.toDouble()).coerceAtLeast(0L)
        return kotlin.math.floor(positionSeconds().toDouble() * safeFps).toLong().coerceAtLeast(0L)
    }

    fun isPlaying(): Boolean {
        if (released) return false
        if (handle != 0L) return nativeIsPlaying(handle)
        if (fallbackPlaying) {
            fallbackPositionNow()
        }
        return fallbackPlaying
    }

    fun release() {
        if (released) return
        released = true
        fallbackPlaying = false
        if (handle != 0L) runCatching { nativeRelease(handle) }
    }

    private fun fallbackPositionNow(): Float {
        if (!fallbackPlaying) return fallbackPosition
        val now = SystemClock.elapsedRealtimeNanos()
        val elapsed = ((now - fallbackAnchorNs).coerceAtLeast(0L) / 1_000_000_000.0).toFloat()
        var next = fallbackPosition + elapsed * fallbackSpeed
        if (fallbackDuration > 0f && next >= fallbackDuration) {
            if (fallbackLooping) {
                next %= fallbackDuration
                fallbackPosition = next
                fallbackAnchorNs = now
            } else {
                fallbackPosition = fallbackDuration
                fallbackPlaying = false
                fallbackAnchorNs = now
                return fallbackPosition
            }
        }
        return next
    }

    private external fun nativeCreate(): Long
    private external fun nativeConfigure(handle: Long, duration: Double, looping: Boolean, speed: Double)
    private external fun nativeSeek(handle: Long, position: Double)
    private external fun nativePlay(handle: Long)
    private external fun nativePause(handle: Long)
    private external fun nativePosition(handle: Long): Double
    private external fun nativePositionFrame(handle: Long, fps: Double): Long
    private external fun nativeIsPlaying(handle: Long): Boolean
    private external fun nativeRelease(handle: Long)
}
