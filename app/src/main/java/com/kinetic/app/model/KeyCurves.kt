package com.kinetic.app.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Default type-specific parameters (p1, p2) used when a key is switched to [type]. */
fun defaultCurveParams(type: KeyType): Pair<Float, Float> = when (type) {
    KeyType.ELASTIC -> 1f to 3f      // amplitude, oscillation speed (3 = classic elastic-out)
    KeyType.BOUNCE -> 3f to 0.35f    // bounce count, bounciness (height ratio per bounce)
    KeyType.WAVE -> 2f to 0.18f      // cycles, amplitude
    KeyType.STEPS -> 5f to 0f        // step count, 0 = jump at end / 1 = jump at start
    else -> 0f to 0f
}

private fun bezAxis(s: Float, a: Float, b: Float): Float {
    val i = 1f - s
    return 3f * i * i * s * a + 3f * i * s * s * b + s * s * s
}

private fun bezAxisDeriv(s: Float, a: Float, b: Float): Float {
    val i = 1f - s
    return 3f * i * i * a + 6f * i * s * (b - a) + 3f * s * s * (1f - b)
}

/** Fast cubic-bezier easing (Newton with bisection fallback). Y may overshoot [0, 1]. */
fun bezierProgress(u: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
    if (u <= 0f) return 0f
    if (u >= 1f) return 1f
    var s = u
    for (i in 0 until 5) {
        val err = bezAxis(s, x1, x2) - u
        if (abs(err) < 1e-5f) return bezAxis(s, y1, y2)
        val d = bezAxisDeriv(s, x1, x2)
        if (abs(d) < 1e-4f) break
        s = (s - err / d).coerceIn(0f, 1f)
    }
    var lo = 0f
    var hi = 1f
    s = u
    for (i in 0 until 22) {
        if (bezAxis(s, x1, x2) < u) lo = s else hi = s
        s = (lo + hi) / 2f
    }
    return bezAxis(s, y1, y2)
}

private fun bounceProgress(u: Float, count: Int, e: Float): Float {
    val n = count.coerceIn(1, 10)
    val r = e.coerceIn(0.05f, 0.9f)
    var total = 1f
    var h = 1f
    for (k in 1..n) { h *= r; total += 2f * sqrt(h) }
    val t = u * total
    if (t <= 1f) return t * t
    var start = 1f
    h = 1f
    for (k in 1..n) {
        h *= r
        val w = 2f * sqrt(h)
        if (t <= start + w) {
            val tau = (t - start) / w
            val x = 2f * tau - 1f
            return 1f - h * (1f - x * x)
        }
        start += w
    }
    return 1f
}

/**
 * Progress (0 at the key, 1 at the next key) of the segment starting at [a] for normalized time [u].
 * Bezier may overshoot; Elastic, Bounce and Wave return values outside 0..1 by design.
 */
fun keyProgress(a: Keyframe, u: Float): Float = when (a.type) {
    KeyType.HOLD -> if (u >= 1f) 1f else 0f
    KeyType.LINEAR -> u
    KeyType.BEZIER -> bezierProgress(u, a.x1, a.y1, a.x2, a.y2)
    KeyType.ELASTIC -> {
        if (u <= 0f) 0f else if (u >= 1f) 1f else {
            val amp = if (a.p1 > 0f) a.p1 else 1f
            val osc = if (a.p2 > 0f) a.p2 else 3f
            1f + amp * (2.0.pow(-10.0 * u) * sin((u * 10.0 * (osc / 3.0) - 0.75) * (2.0 * PI / 3.0))).toFloat()
        }
    }
    KeyType.BOUNCE -> {
        if (a.p1 <= 0f) {
            // Classic ease-out-bounce for keys created before bounce had parameters.
            when {
                u < 1f / 2.75f -> 7.5625f * u * u
                u < 2f / 2.75f -> { val q = u - 1.5f / 2.75f; 7.5625f * q * q + 0.75f }
                u < 2.5f / 2.75f -> { val q = u - 2.25f / 2.75f; 7.5625f * q * q + 0.9375f }
                else -> { val q = u - 2.625f / 2.75f; 7.5625f * q * q + 0.984375f }
            }
        } else bounceProgress(u, a.p1.roundToInt(), a.p2)
    }
    KeyType.WAVE -> {
        val cycles = (((if (a.p1 > 0f) a.p1 else 2f) * 2f).roundToInt() / 2f).coerceAtLeast(0.5f)
        u + a.p2 * sin(2.0 * PI * cycles * u).toFloat()
    }
    KeyType.STEPS -> {
        val n = (if (a.p1 > 0f) a.p1 else 5f).roundToInt().coerceIn(1, 64)
        if (a.p2 >= 0.5f) ceil(u * n) / n else floor(u * n + 1e-4f) / n
    }
}
