package com.kinetic.app.engine

import com.kinetic.app.model.Keyframe
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/** Evaluates a keyframed effect parameter. Keys are expected sorted by time. */
object EffectKeyframes {
    private fun cubic(t: Float, a: Float, b: Float): Float {
        val inv = 1f - t
        return 3f * inv * inv * t * a + 3f * inv * t * t * b + t * t * t
    }

    private fun bezier(u: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        var low = 0f
        var high = 1f
        repeat(20) {
            val mid = (low + high) * 0.5f
            if (cubic(mid, x1, x2) < u) low = mid else high = mid
        }
        return cubic((low + high) * 0.5f, y1, y2)
    }

    fun evaluate(base: Float, keys: List<Keyframe>, time: Float): Float {
        if (keys.isEmpty() || !time.isFinite()) return base
        if (time <= keys.first().t) return keys.first().v
        if (time >= keys.last().t) return keys.last().v
        var i = 0
        while (i + 1 < keys.size - 1 && keys[i + 1].t <= time) i++
        val a = keys[i]
        val b = keys[i + 1]
        val span = b.t - a.t
        if (!span.isFinite() || span <= 0.000001f) return b.v
        val u = ((time - a.t) / span).coerceIn(0f, 1f)
        val p = com.kinetic.app.model.keyProgress(a, u)
        val result = a.v + (b.v - a.v) * p
        return if (result.isFinite()) result else a.v
    }
}
