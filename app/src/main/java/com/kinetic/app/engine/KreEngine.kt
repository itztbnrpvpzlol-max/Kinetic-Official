package com.kinetic.app.engine

/**
 * Native KRE planning and calculation-cache boundary.
 *
 * Frame planning is deterministic and bounded: callers can request at most
 * twenty timestamps per look-ahead window. The cache stores small reusable
 * calculation outputs, never full-resolution pixel buffers.
 */
object KreEngine {
    val available: Boolean = NativeRenderEngine.available

    init {
        if (available) runCatching { nativeSetCalculationCacheBudget(512 * 1024) }
    }

    /** Returns source timestamps in microseconds, ordered from the playhead forward. */
    fun planFrames(
        playheadUs: Long,
        fps: Double,
        speed: Double,
        durationUs: Long,
        count: Int = 15
    ): LongArray {
        if (!available) return LongArray(0)
        return runCatching {
            nativePlanFrames(playheadUs.coerceAtLeast(0L), fps, speed,
                durationUs.coerceAtLeast(0L), count.coerceIn(1, 20))
        }.getOrDefault(LongArray(0))
    }

    fun getCalculation(fingerprint: Long, output: FloatArray): Boolean {
        if (!available || output.isEmpty()) return false
        return runCatching { nativeGetCalculation(fingerprint, output) }.getOrDefault(false)
    }

    fun putCalculation(fingerprint: Long, values: FloatArray) {
        if (!available || values.isEmpty() || values.size > 4096) return
        runCatching { nativePutCalculation(fingerprint, values) }
    }

    fun setCalculationCacheBudget(bytes: Int) {
        if (available) runCatching { nativeSetCalculationCacheBudget(bytes.coerceIn(64 * 1024, 16 * 1024 * 1024)) }
    }

    fun calculationCacheBytes(): Int =
        if (available) runCatching { nativeCalculationCacheBytes() }.getOrDefault(0) else 0

    fun clearCalculationCache() {
        if (available) runCatching { nativeClearCalculationCache() }
    }

    private external fun nativePlanFrames(
        playheadUs: Long, fps: Double, speed: Double, durationUs: Long, count: Int
    ): LongArray
    private external fun nativeSetCalculationCacheBudget(bytes: Int)
    private external fun nativeGetCalculation(fingerprint: Long, output: FloatArray): Boolean
    private external fun nativePutCalculation(fingerprint: Long, input: FloatArray)
    private external fun nativeCalculationCacheBytes(): Int
    private external fun nativeClearCalculationCache()
}
