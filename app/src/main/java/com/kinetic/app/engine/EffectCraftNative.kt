package com.kinetic.app.engine

import java.nio.ByteBuffer

/**
 * JNI surface of the EffectCraft effects library (`libkinetic_effectcraft.so`, built from
 * `native/effectcraft-android`). The library is stateless: Kotlin evaluates keyframes and passes
 * the current parameter values on every call.
 */
object EffectCraftNative {
    private val loadResult = runCatching { System.loadLibrary("kinetic_effectcraft") }
    val available: Boolean = loadResult.isSuccess
    val loadError: String? = loadResult.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }

    @JvmStatic external fun nativeVersion(): String?

    /** Every effect with its category and typed parameters, as JSON (see `catalog.rs`). */
    @JvmStatic external fun nativeCatalogJson(): String?

    /**
     * Applies a picture-effect stack to premultiplied RGBA8 pixels in a direct buffer, in place.
     * [layerWidth]/[layerHeight] are the logical size of the layer the bitmap shows: effects work
     * in that space, so any bitmap resolution of the layer gives the same look.
     * Returns the number of effects applied, or a negative error code.
     */
    @JvmStatic external fun nativeProcessImage(
        buffer: ByteBuffer, width: Int, height: Int, layerWidth: Double, layerHeight: Double, stackJson: String,
        time: Double, frameRate: Double, adjustment: Boolean
    ): Int

    /**
     * Like [nativeProcessImage] but keeps what effects painted outside the layer rectangle and
     * leaves [buffer] untouched. [out] receives `[width, height, left, top, right, bottom]` (margins
     * in source pixels); fetch the pixels with [nativeTakeGrown] on the SAME thread.
     */
    @JvmStatic external fun nativeProcessImageGrow(
        buffer: ByteBuffer, width: Int, height: Int, layerWidth: Double, layerHeight: Double, stackJson: String,
        time: Double, frameRate: Double, adjustment: Boolean, maxPixels: Int, out: IntArray
    ): Int

    /** Copies the pending [nativeProcessImageGrow] result into [buffer]; returns bytes written or -1. */
    @JvmStatic external fun nativeTakeGrown(buffer: ByteBuffer): Int

    /** Seconds of audio the stack wants fed in before a block so effects start settled. */
    @JvmStatic external fun nativeAudioPreroll(stackJson: String): Double

    /** Applies an audio-effect stack to interleaved stereo float samples, in place. */
    @JvmStatic external fun nativeProcessAudio(samples: FloatArray, rate: Int, startSeconds: Double, stackJson: String): Int
}
