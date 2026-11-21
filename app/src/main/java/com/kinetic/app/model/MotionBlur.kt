package com.kinetic.app.model

import com.kinetic.app.engine.EffectStackRunner
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

object MotionBlur {
    const val PREVIEW_MAX_SAMPLES = 24
    const val EXPORT_MAX_SAMPLES = 48
    private const val STILL_TRAVEL_PX = 0.2f
    private const val EFFECT_MIN_SAMPLES = 8

    fun exposure(shutterAngle: Float, fps: Int): Float =
        shutterAngle.coerceIn(0f, 360f) / 360f / fps.coerceAtLeast(1)

    fun effectsAnimated(layer: Layer): Boolean =
        EffectStackRunner.pictureEffects(layer.effectStack).any { effect ->
            effect.parameterKeys.values.any { it.size > 1 }
        }

    private fun cornersAt(layer: Layer, t: Float, byId: Map<String, Layer>, out: FloatArray) {
        out[0] = 0f; out[1] = 0f
        out[2] = layer.width; out[3] = 0f
        out[4] = layer.width; out[5] = layer.height
        out[6] = 0f; out[7] = layer.height
        layerMatrix(layer, t, byId).mapPoints(out)
    }

    private fun travel(layer: Layer, t0: Float, tm: Float, t1: Float, byId: Map<String, Layer>): Float {
        val a = FloatArray(8)
        val b = FloatArray(8)
        val c = FloatArray(8)
        cornersAt(layer, t0, byId, a)
        cornersAt(layer, tm, byId, b)
        cornersAt(layer, t1, byId, c)
        var best = 0f
        for (i in 0 until 4) {
            val x = i * 2
            val y = x + 1
            val d1 = sqrt((b[x] - a[x]) * (b[x] - a[x]) + (b[y] - a[y]) * (b[y] - a[y]))
            val d2 = sqrt((c[x] - b[x]) * (c[x] - b[x]) + (c[y] - b[y]) * (c[y] - b[y]))
            val total = d1 + d2
            if (total.isFinite()) best = max(best, total)
        }
        return best
    }

    fun sampleTimes(
        layer: Layer,
        time: Float,
        shutterAngle: Float,
        fps: Int,
        byId: Map<String, Layer>,
        maxSamples: Int,
        pxPerStep: Float = 0.75f
    ): FloatArray {
        val exposure = exposure(shutterAngle, fps)
        if (!layer.motionBlur || exposure <= 0f) return floatArrayOf(time)
        val lo = layer.start
        val hi = (layer.end - 0.0001f).coerceAtLeast(lo)
        val half = exposure * 0.5f
        val t0 = (time - half).coerceIn(lo, hi)
        val t1 = (time + half).coerceIn(lo, hi)
        val tm = (t0 + t1) * 0.5f
        val moved = travel(layer, t0, tm, t1, byId)
        var count = if (moved < STILL_TRAVEL_PX) 1 else ceil(moved / pxPerStep).toInt() + 1
        if (effectsAnimated(layer)) count = max(count, EFFECT_MIN_SAMPLES)
        count = count.coerceIn(1, maxSamples.coerceAtLeast(1))
        if (count == 1) return floatArrayOf(time)
        return FloatArray(count) { index ->
            (time + ((index + 0.5f) / count - 0.5f) * exposure).coerceIn(lo, hi)
        }
    }

    fun sampleAlpha(opacity: Float, index: Int, count: Int): Float {
        val target = opacity.coerceIn(0f, 1f)
        if (count <= 1) return target
        val remaining = (count - 1 - index).coerceAtLeast(0)
        val denominator = (count - remaining * target).coerceAtLeast(0.0001f)
        return (target / denominator).coerceIn(0f, 1f)
    }
}
