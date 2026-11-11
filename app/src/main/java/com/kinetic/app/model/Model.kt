package com.kinetic.app.model

import android.graphics.Matrix
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.pow

const val COMP_W = 1920f
const val COMP_H = 1080f

fun fmt(f: String, vararg a: Any): String = String.format(Locale.US, f, *a)

enum class LayerType(val label: String) {
    VIDEO("Video"), IMAGE("Image"), AUDIO("Audio"), TEXT("Text"),
    SHAPE("Shape"), ADJUSTMENT("Adjustment"), NULL("Null")
}

/** Transform properties, in After Effects order. scrubStep = value change per dp dragged. */
enum class PropId(val label: String, val scrubStep: Float) {
    ANCHOR_X("Anchor X", 3f),
    ANCHOR_Y("Anchor Y", 3f),
    POS_X("Position X", 3f),
    POS_Y("Position Y", 3f),
    SCALE_X("Scale X", 0.5f),
    SCALE_Y("Scale Y", 0.5f),
    ROTATION("Rotation", 1f),
    OPACITY("Opacity", 0.5f);

    fun format(v: Float): String = when (this) {
        ANCHOR_X, ANCHOR_Y, POS_X, POS_Y -> fmt("%.1f px", v)
        SCALE_X, SCALE_Y -> fmt("%.1f%%", v)
        ROTATION -> fmt("%.1f\u00B0", v)
        OPACITY -> fmt("%.0f%%", v)
    }
}

enum class KeyType { LINEAR, HOLD, BEZIER, ELASTIC, BOUNCE }

/**
 * A keyframe. The segment from this key to the next one is shaped by [type];
 * for BEZIER it uses the cubic-bezier (x1,y1,x2,y2) easing, same as CSS/AE-style ease curves.
 */
data class Keyframe(
    val t: Float,
    val v: Float,
    val type: KeyType = KeyType.LINEAR,
    val x1: Float = 0.42f, val y1: Float = 0f,
    val x2: Float = 0.58f, val y2: Float = 1f
)

private fun bez(s: Float, a: Float, b: Float): Float {
    val i = 1f - s
    return 3f * i * i * s * a + 3f * i * s * s * b + s * s * s
}

fun cubicProgress(u: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
    var lo = 0f
    var hi = 1f
    repeat(24) {
        val mid = (lo + hi) / 2f
        if (bez(mid, x1, x2) < u) lo = mid else hi = mid
    }
    return bez((lo + hi) / 2f, y1, y2)
}

data class Prop(val base: Float, val keys: List<Keyframe> = emptyList(), val expression: String? = null) {
    val animated: Boolean get() = keys.isNotEmpty()

    fun valueAt(t: Float): Float {
        val expr = expression?.trim()
        if (expr != null) {
            val match = Regex("wiggle\\s*\\(\\s*([0-9.]+)\\s*,\\s*([0-9.]+)\\s*\\)", RegexOption.IGNORE_CASE).find(expr)
            if (match != null) {
                val frequency = match.groupValues[1].toFloatOrNull() ?: 2f
                val amplitude = match.groupValues[2].toFloatOrNull() ?: 20f
                val noise = kotlin.math.sin(t * frequency * 6.2831855f) * 0.55f + kotlin.math.sin(t * frequency * 13.7f) * 0.3f + kotlin.math.sin(t * frequency * 31.1f) * 0.15f
                return (if (keys.isEmpty()) base else valueAtWithoutExpression(t)) + amplitude * noise
            }
        }
        return valueAtWithoutExpression(t)
    }

    private fun valueAtWithoutExpression(t: Float): Float {
        if (keys.isEmpty()) return base
        if (t <= keys.first().t) return keys.first().v
        if (t >= keys.last().t) return keys.last().v
        var i = 0
        while (i < keys.size - 2 && keys[i + 1].t <= t) i++
        val a = keys[i]
        val b = keys[i + 1]
        val u = ((t - a.t) / (b.t - a.t)).coerceIn(0f, 1f)
        val p = when (a.type) {
            KeyType.HOLD -> 0f
            KeyType.LINEAR -> u
            KeyType.BEZIER -> cubicProgress(u, a.x1, a.y1, a.x2, a.y2)
            KeyType.ELASTIC -> {
                if (u <= 0f || u >= 1f) u else (2.0.pow(-10.0 * u) * kotlin.math.sin((u * 10.0 - 0.75) * (2.0 * Math.PI / 3.0)) + 1.0).toFloat()
            }
            KeyType.BOUNCE -> {
                val x = u
                when {
                    x < 1f / 2.75f -> 7.5625f * x * x
                    x < 2f / 2.75f -> { val q = x - 1.5f / 2.75f; 7.5625f * q * q + 0.75f }
                    x < 2.5f / 2.75f -> { val q = x - 2.25f / 2.75f; 7.5625f * q * q + 0.9375f }
                    else -> { val q = x - 2.625f / 2.75f; 7.5625f * q * q + 0.984375f }
                }
            }
        }
        return a.v + (b.v - a.v) * p
    }

    /** Set the value at time t. Not animated -> changes base. Animated -> edits/creates a key (like AE). */
    fun withValueAt(t: Float, v: Float, eps: Float): Prop {
        if (keys.isEmpty()) return copy(base = v)
        val idx = keys.indexOfFirst { abs(it.t - t) <= eps }
        return if (idx >= 0) {
            copy(keys = keys.toMutableList().also { it[idx] = it[idx].copy(v = v) })
        } else {
            copy(keys = (keys + Keyframe(t, v)).sortedBy { it.t })
        }
    }
}

data class Layer(
    val id: String = UUID.randomUUID().toString(),
    val type: LayerType,
    val name: String,
    val uri: String? = null,
    val start: Float,
    val duration: Float,
    val srcOffset: Float = 0f,
    val srcLength: Float = Float.POSITIVE_INFINITY,
    val width: Float,
    val height: Float,
    val visible: Boolean = true,
    val parentId: String? = null,
    val scaleLinked: Boolean = false,
    val text: String = "Text",
    val color: Int = 0xFF8D8BA3.toInt(),
    val props: Map<PropId, Prop>,
    /** Reserved for later: adjustment layers will hold effects here. Intentionally empty for now. */
    val effects: List<String> = emptyList()
) {
    val end: Float get() = start + duration
    fun p(id: PropId): Prop = props.getValue(id)
    fun activeAt(t: Float): Boolean = t >= start && t < end
}

fun newLayer(
    type: LayerType, name: String, start: Float, duration: Float, w: Float, h: Float,
    uri: String? = null, srcLength: Float = Float.POSITIVE_INFINITY,
    text: String = "Text", color: Int = 0xFF8D8BA3.toInt()
): Layer {
    val props = mapOf(
        PropId.ANCHOR_X to Prop(w / 2f),
        PropId.ANCHOR_Y to Prop(h / 2f),
        PropId.POS_X to Prop(COMP_W / 2f),
        PropId.POS_Y to Prop(COMP_H / 2f),
        PropId.SCALE_X to Prop(100f),
        PropId.SCALE_Y to Prop(100f),
        PropId.ROTATION to Prop(0f),
        PropId.OPACITY to Prop(100f)
    )
    return Layer(
        type = type, name = name, uri = uri, start = start, duration = duration,
        srcLength = srcLength, width = w, height = h, text = text, color = color, props = props
    )
}

/** Layer-space -> composition-space matrix at time t, including the parent chain. */
fun layerMatrix(layer: Layer, t: Float, byId: Map<String, Layer>, depth: Int = 0): Matrix {
    val m = Matrix()
    m.postTranslate(-layer.p(PropId.ANCHOR_X).valueAt(t), -layer.p(PropId.ANCHOR_Y).valueAt(t))
    m.postScale(layer.p(PropId.SCALE_X).valueAt(t) / 100f, layer.p(PropId.SCALE_Y).valueAt(t) / 100f)
    m.postRotate(layer.p(PropId.ROTATION).valueAt(t))
    m.postTranslate(layer.p(PropId.POS_X).valueAt(t), layer.p(PropId.POS_Y).valueAt(t))
    val parent = layer.parentId?.let { byId[it] }
    if (parent != null && depth < 16) {
        val pm = layerMatrix(parent, t, byId, depth + 1)
        // child space has its origin at the parent's anchor point (same as After Effects)
        m.postTranslate(parent.p(PropId.ANCHOR_X).valueAt(t), parent.p(PropId.ANCHOR_Y).valueAt(t))
        m.postConcat(pm)
    }
    return m
}

fun isDescendantOf(candidate: Layer, ancestorId: String, byId: Map<String, Layer>): Boolean {
    var cur = candidate.parentId
    var n = 0
    while (cur != null && n < 32) {
        if (cur == ancestorId) return true
        cur = byId[cur]?.parentId
        n++
    }
    return false
}
