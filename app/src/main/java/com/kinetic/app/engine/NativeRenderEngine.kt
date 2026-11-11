package com.kinetic.app.engine

/**
 * Native math boundary for the rendering engine.
 *
 * Batch APIs are intentional: avoid one JNI transition per property or layer.
 * The Kotlin renderer remains the fallback when the native library is unavailable.
 */
object NativeRenderEngine {
    val available: Boolean = runCatching {
        System.loadLibrary("kinetic_engine")
        true
    }.getOrDefault(false)

    external fun nativeCubicProgress(
        progress: Float,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float
    ): Float

    /** Each input tuple is [progress, x1, y1, x2, y2]. */
    external fun nativeEvaluateCurves(input: FloatArray): FloatArray
}
