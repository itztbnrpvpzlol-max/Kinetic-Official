package com.kinetic.app.engine

import android.view.Surface

/**
 * Thin JNI boundary to Kinetic's bundled FFmpeg libraries.
 * FFmpeg owns demuxing, timestamps and MediaCodec decoder setup.
 */
internal object NativeFfmpegDecoder {
    private val libraryLoaded: Boolean
        get() = NativeRenderEngine.available

    fun ensureLoaded() {
        check(libraryLoaded) { "Kinetic native engine could not be loaded" }
    }

    external fun nativeCreate(sourcePath: String, surface: Surface, width: Int, height: Int): Long
    external fun nativeSetClock(
        handle: Long, positionUs: Long, monotonicNs: Long, masterSpeed: Double,
        playing: Boolean, clipStartUs: Long, sourceOffsetUs: Long,
        clipSpeed: Double, generation: Long
    )
    external fun nativeSeek(handle: Long, targetUs: Long)
    external fun nativeSetSpeed(handle: Long, speed: Float)
    external fun nativePlay(handle: Long)
    external fun nativePause(handle: Long)
    external fun nativeState(handle: Long): Int
    external fun nativeError(handle: Long): String
    external fun nativeRelease(handle: Long)
}
