package com.kinetic.app.engine

import android.content.Context
import android.net.Uri
import android.view.Surface

/**
 * Hardware video decoder backed by NDK MediaExtractor/MediaCodec.
 * Decoded frames are rendered directly into the GL SurfaceTexture supplied
 * by the composition renderer; no decoded Bitmap frames are copied to Kotlin.
 */
class NativeVideoPlayer(
    context: Context,
    private val sourceUri: Uri
) {
    private val appContext = context.applicationContext
    private var handle = 0L
    private var surface: Surface? = null
    private var positionMs = 0L
    private var speed = 1f
    private var wantsPlayback = false
    private var released = false
    private val lock = Any()

    fun attachSurface(next: Surface) = synchronized(lock) {
        if (released || surface === next) return@synchronized
        destroyDecoder()
        surface = next
        createDecoder()
    }

    fun detachSurface() = synchronized(lock) {
        if (released) return@synchronized
        destroyDecoder()
        surface = null
    }

    fun updatePositionHint(targetMs: Long) = synchronized(lock) {
        if (!released) positionMs = targetMs.coerceAtLeast(0L)
    }

    fun seekTo(targetMs: Long) = synchronized(lock) {
        if (released) return@synchronized
        positionMs = targetMs.coerceAtLeast(0L)
        if (handle != 0L) nativeSeek(handle, positionMs)
    }

    fun setSpeed(value: Float) = synchronized(lock) {
        if (released) return@synchronized
        speed = value.coerceIn(0.1f, 4f)
        if (handle != 0L) nativeSetSpeed(handle, speed)
    }

    fun play() = synchronized(lock) {
        if (released) return@synchronized
        wantsPlayback = true
        if (handle != 0L) nativePlay(handle)
    }

    fun pause() = synchronized(lock) {
        if (released) return@synchronized
        wantsPlayback = false
        if (handle != 0L) nativePause(handle)
    }

    fun release() = synchronized(lock) {
        if (released) return@synchronized
        released = true
        destroyDecoder()
        surface = null
    }

    private fun createDecoder() {
        val output = surface ?: return
        val descriptor = try {
            appContext.contentResolver.openFileDescriptor(sourceUri, "r")
        } catch (_: Throwable) {
            null
        } ?: return
        val fileLength = runCatching { descriptor.statSize }.getOrDefault(-1L)
        val fd = try {
            descriptor.detachFd()
        } finally {
            descriptor.close()
        }
        handle = try {
            nativeCreate(fd, 0L, fileLength, output, positionMs, speed)
        } catch (_: Throwable) {
            0L
        }
        if (handle != 0L && wantsPlayback) nativePlay(handle)
    }

    private fun destroyDecoder() {
        val old = handle
        handle = 0L
        if (old != 0L) runCatching { nativeRelease(old) }
    }

    private external fun nativeCreate(
        fd: Int, offset: Long, length: Long, surface: Surface,
        startPositionMs: Long, speed: Float
    ): Long
    private external fun nativeSeek(handle: Long, positionMs: Long)
    private external fun nativePlay(handle: Long)
    private external fun nativePause(handle: Long)
    private external fun nativeSetSpeed(handle: Long, speed: Float)
    private external fun nativeRelease(handle: Long)

    companion object {
        init { System.loadLibrary("kinetic_engine") }
    }
}
