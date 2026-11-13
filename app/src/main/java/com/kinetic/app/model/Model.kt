package com.kinetic.app.model

import android.graphics.Matrix
import java.util.Locale
import java.util.UUID
import com.kinetic.app.engine.NativeRenderEngine
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
enum class PropId(
    val label: String,
    val scrubStep: Float,
    val vectorPointIndex: Int = -1,
    val vectorPointAxis: Char = ' '
) {
    ANCHOR_X("Anchor X", 3f),
    ANCHOR_Y("Anchor Y", 3f),
    POS_X("Position X", 3f),
    POS_Y("Position Y", 3f),
    SCALE_X("Scale X", 0.5f),
    SCALE_Y("Scale Y", 0.5f),
    ROTATION("Rotation", 1f),
    OPACITY("Opacity", 0.5f),
    SPEED("Speed", 0.02f),
    VOLUME("Volume", 0.02f),
    VECTOR_P1_X("Point 1 X", 0.004f, 0, 'X'),
    VECTOR_P1_Y("Point 1 Y", 0.004f, 0, 'Y'),
    VECTOR_P2_X("Point 2 X", 0.004f, 1, 'X'),
    VECTOR_P2_Y("Point 2 Y", 0.004f, 1, 'Y'),
    VECTOR_P3_X("Point 3 X", 0.004f, 2, 'X'),
    VECTOR_P3_Y("Point 3 Y", 0.004f, 2, 'Y'),
    VECTOR_P4_X("Point 4 X", 0.004f, 3, 'X'),
    VECTOR_P4_Y("Point 4 Y", 0.004f, 3, 'Y'),
    VECTOR_P5_X("Point 5 X", 0.004f, 4, 'X'),
    VECTOR_P5_Y("Point 5 Y", 0.004f, 4, 'Y'),
    VECTOR_P6_X("Point 6 X", 0.004f, 5, 'X'),
    VECTOR_P6_Y("Point 6 Y", 0.004f, 5, 'Y'),
    VECTOR_P7_X("Point 7 X", 0.004f, 6, 'X'),
    VECTOR_P7_Y("Point 7 Y", 0.004f, 6, 'Y'),
    VECTOR_P8_X("Point 8 X", 0.004f, 7, 'X'),
    VECTOR_P8_Y("Point 8 Y", 0.004f, 7, 'Y'),
    VECTOR_P9_X("Point 9 X", 0.004f, 8, 'X'),
    VECTOR_P9_Y("Point 9 Y", 0.004f, 8, 'Y'),
    VECTOR_P10_X("Point 10 X", 0.004f, 9, 'X'),
    VECTOR_P10_Y("Point 10 Y", 0.004f, 9, 'Y'),
    VECTOR_P11_X("Point 11 X", 0.004f, 10, 'X'),
    VECTOR_P11_Y("Point 11 Y", 0.004f, 10, 'Y'),
    VECTOR_P12_X("Point 12 X", 0.004f, 11, 'X'),
    VECTOR_P12_Y("Point 12 Y", 0.004f, 11, 'Y'),
    VECTOR_P13_X("Point 13 X", 0.004f, 12, 'X'),
    VECTOR_P13_Y("Point 13 Y", 0.004f, 12, 'Y'),
    VECTOR_P14_X("Point 14 X", 0.004f, 13, 'X'),
    VECTOR_P14_Y("Point 14 Y", 0.004f, 13, 'Y'),
    VECTOR_P15_X("Point 15 X", 0.004f, 14, 'X'),
    VECTOR_P15_Y("Point 15 Y", 0.004f, 14, 'Y'),
    VECTOR_P16_X("Point 16 X", 0.004f, 15, 'X'),
    VECTOR_P16_Y("Point 16 Y", 0.004f, 15, 'Y'),
    VECTOR_P17_X("Point 17 X", 0.004f, 16, 'X'),
    VECTOR_P17_Y("Point 17 Y", 0.004f, 16, 'Y'),
    VECTOR_P18_X("Point 18 X", 0.004f, 17, 'X'),
    VECTOR_P18_Y("Point 18 Y", 0.004f, 17, 'Y'),
    VECTOR_P19_X("Point 19 X", 0.004f, 18, 'X'),
    VECTOR_P19_Y("Point 19 Y", 0.004f, 18, 'Y'),
    VECTOR_P20_X("Point 20 X", 0.004f, 19, 'X'),
    VECTOR_P20_Y("Point 20 Y", 0.004f, 19, 'Y'),
    VECTOR_P21_X("Point 21 X", 0.004f, 20, 'X'),
    VECTOR_P21_Y("Point 21 Y", 0.004f, 20, 'Y'),
    VECTOR_P22_X("Point 22 X", 0.004f, 21, 'X'),
    VECTOR_P22_Y("Point 22 Y", 0.004f, 21, 'Y'),
    VECTOR_P23_X("Point 23 X", 0.004f, 22, 'X'),
    VECTOR_P23_Y("Point 23 Y", 0.004f, 22, 'Y'),
    VECTOR_P24_X("Point 24 X", 0.004f, 23, 'X'),
    VECTOR_P24_Y("Point 24 Y", 0.004f, 23, 'Y'),
    VECTOR_P25_X("Point 25 X", 0.004f, 24, 'X'),
    VECTOR_P25_Y("Point 25 Y", 0.004f, 24, 'Y'),
    VECTOR_P26_X("Point 26 X", 0.004f, 25, 'X'),
    VECTOR_P26_Y("Point 26 Y", 0.004f, 25, 'Y'),
    VECTOR_P27_X("Point 27 X", 0.004f, 26, 'X'),
    VECTOR_P27_Y("Point 27 Y", 0.004f, 26, 'Y'),
    VECTOR_P28_X("Point 28 X", 0.004f, 27, 'X'),
    VECTOR_P28_Y("Point 28 Y", 0.004f, 27, 'Y'),
    VECTOR_P29_X("Point 29 X", 0.004f, 28, 'X'),
    VECTOR_P29_Y("Point 29 Y", 0.004f, 28, 'Y'),
    VECTOR_P30_X("Point 30 X", 0.004f, 29, 'X'),
    VECTOR_P30_Y("Point 30 Y", 0.004f, 29, 'Y'),
    VECTOR_P31_X("Point 31 X", 0.004f, 30, 'X'),
    VECTOR_P31_Y("Point 31 Y", 0.004f, 30, 'Y'),
    VECTOR_P32_X("Point 32 X", 0.004f, 31, 'X'),
    VECTOR_P32_Y("Point 32 Y", 0.004f, 31, 'Y'),
    VECTOR_P33_X("Point 33 X", 0.004f, 32, 'X'),
    VECTOR_P33_Y("Point 33 Y", 0.004f, 32, 'Y'),
    VECTOR_P34_X("Point 34 X", 0.004f, 33, 'X'),
    VECTOR_P34_Y("Point 34 Y", 0.004f, 33, 'Y'),
    VECTOR_P35_X("Point 35 X", 0.004f, 34, 'X'),
    VECTOR_P35_Y("Point 35 Y", 0.004f, 34, 'Y'),
    VECTOR_P36_X("Point 36 X", 0.004f, 35, 'X'),
    VECTOR_P36_Y("Point 36 Y", 0.004f, 35, 'Y'),
    VECTOR_P37_X("Point 37 X", 0.004f, 36, 'X'),
    VECTOR_P37_Y("Point 37 Y", 0.004f, 36, 'Y'),
    VECTOR_P38_X("Point 38 X", 0.004f, 37, 'X'),
    VECTOR_P38_Y("Point 38 Y", 0.004f, 37, 'Y'),
    VECTOR_P39_X("Point 39 X", 0.004f, 38, 'X'),
    VECTOR_P39_Y("Point 39 Y", 0.004f, 38, 'Y'),
    VECTOR_P40_X("Point 40 X", 0.004f, 39, 'X'),
    VECTOR_P40_Y("Point 40 Y", 0.004f, 39, 'Y'),
    VECTOR_P41_X("Point 41 X", 0.004f, 40, 'X'),
    VECTOR_P41_Y("Point 41 Y", 0.004f, 40, 'Y'),
    VECTOR_P42_X("Point 42 X", 0.004f, 41, 'X'),
    VECTOR_P42_Y("Point 42 Y", 0.004f, 41, 'Y'),
    VECTOR_P43_X("Point 43 X", 0.004f, 42, 'X'),
    VECTOR_P43_Y("Point 43 Y", 0.004f, 42, 'Y'),
    VECTOR_P44_X("Point 44 X", 0.004f, 43, 'X'),
    VECTOR_P44_Y("Point 44 Y", 0.004f, 43, 'Y'),
    VECTOR_P45_X("Point 45 X", 0.004f, 44, 'X'),
    VECTOR_P45_Y("Point 45 Y", 0.004f, 44, 'Y'),
    VECTOR_P46_X("Point 46 X", 0.004f, 45, 'X'),
    VECTOR_P46_Y("Point 46 Y", 0.004f, 45, 'Y'),
    VECTOR_P47_X("Point 47 X", 0.004f, 46, 'X'),
    VECTOR_P47_Y("Point 47 Y", 0.004f, 46, 'Y'),
    VECTOR_P48_X("Point 48 X", 0.004f, 47, 'X'),
    VECTOR_P48_Y("Point 48 Y", 0.004f, 47, 'Y'),
    VECTOR_P49_X("Point 49 X", 0.004f, 48, 'X'),
    VECTOR_P49_Y("Point 49 Y", 0.004f, 48, 'Y'),
    VECTOR_P50_X("Point 50 X", 0.004f, 49, 'X'),
    VECTOR_P50_Y("Point 50 Y", 0.004f, 49, 'Y');

    companion object {
        private val vectorPointIds by lazy {
            values().filter { it.isVectorPoint }.associateBy { it.vectorPointIndex to it.vectorPointAxis }
        }
        fun vectorPoint(index: Int, axis: Char): PropId? =
            vectorPointIds[index to axis.uppercaseChar()]
    }

    val isVectorPoint: Boolean get() = vectorPointIndex >= 0

    fun format(v: Float): String = when (this) {
        ANCHOR_X, ANCHOR_Y, POS_X, POS_Y -> fmt("%.1f px", v)
        SCALE_X, SCALE_Y -> fmt("%.1f%%", v)
        ROTATION -> fmt("%.1f\u00B0", v)
        OPACITY -> fmt("%.0f%%", v)
        SPEED -> fmt("%.2f×", v)
        VOLUME -> fmt("%.0f%%", v * 100f)
        else -> fmt("%.1f%%", v * 100f)
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
    val progress = u.coerceIn(0f, 1f)
    // Use the native evaluator for the live preview path when the C++ library
    // is available; retain the exact Kotlin fallback for unsupported ABIs.
    if (NativeRenderEngine.available) {
        return runCatching {
            NativeRenderEngine.nativeCubicProgress(progress, x1, y1, x2, y2)
        }.getOrElse {
            var lo = 0f
            var hi = 1f
            repeat(24) {
                val mid = (lo + hi) / 2f
                if (bez(mid, x1, x2) < progress) lo = mid else hi = mid
            }
            bez((lo + hi) / 2f, y1, y2)
        }
    }
    var lo = 0f
    var hi = 1f
    repeat(24) {
        val mid = (lo + hi) / 2f
        if (bez(mid, x1, x2) < progress) lo = mid else hi = mid
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

data class VectorPoint(val x: Float, val y: Float)

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
    val scaleLinked: Boolean = true,
    val positionLinked: Boolean = true,
    val text: String = "Text",
    val color: Int = 0xFF8D8BA3.toInt(),
    val props: Map<PropId, Prop>,
    /** Reserved for later: adjustment layers will hold effects here. Intentionally empty for now. */
    val effects: List<String> = emptyList(),
    val speed: Float = 1f,
    val volume: Float = 1f,
    val maskEnabled: Boolean = false,
    val blendMode: String = "Normal",
    val shapeKind: String = "Rectangle",
    val vectorPoints: List<VectorPoint> = emptyList()
) {
    val end: Float get() = start + duration
    fun p(id: PropId): Prop = props[id] ?: Prop(if (id.isVectorPoint) 0.5f else 0f)
    fun vectorPointsAt(t: Float): List<VectorPoint> = vectorPoints.mapIndexed { index, point ->
        val x = PropId.vectorPoint(index, 'X')?.let { props[it]?.valueAt(t) } ?: point.x
        val y = PropId.vectorPoint(index, 'Y')?.let { props[it]?.valueAt(t) } ?: point.y
        VectorPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
    }
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
        PropId.OPACITY to Prop(100f),
        PropId.SPEED to Prop(1f),
        PropId.VOLUME to Prop(1f)
    )
    return Layer(
        type = type, name = name, uri = uri, start = start, duration = duration,
        srcLength = srcLength, width = w, height = h, text = text, color = color, props = props
    )
}


/**
 * Reuses caller-owned Matrix instances for the CPU preview draw loop.
 * This avoids allocating a Matrix for every layer and parent on every frame.
 */
fun layerMatrixInto(
    layer: Layer,
    t: Float,
    byId: Map<String, Layer>,
    out: Matrix,
    scratch: MutableMap<Int, Matrix>,
    depth: Int = 0
) {
    out.reset()
    out.postTranslate(-layer.p(PropId.ANCHOR_X).valueAt(t), -layer.p(PropId.ANCHOR_Y).valueAt(t))
    out.postScale(layer.p(PropId.SCALE_X).valueAt(t) / 100f, layer.p(PropId.SCALE_Y).valueAt(t) / 100f)
    out.postRotate(layer.p(PropId.ROTATION).valueAt(t))
    out.postTranslate(layer.p(PropId.POS_X).valueAt(t), layer.p(PropId.POS_Y).valueAt(t))
    val parent = layer.parentId?.let { byId[it] }
    if (parent != null && parent.id != layer.id && depth < 16) {
        val parentMatrix = scratch.getOrPut(depth + 1) { Matrix() }
        layerMatrixInto(parent, t, byId, parentMatrix, scratch, depth + 1)
        out.postTranslate(parent.p(PropId.ANCHOR_X).valueAt(t), parent.p(PropId.ANCHOR_Y).valueAt(t))
        out.postConcat(parentMatrix)
    }
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


fun Layer.speedAt(t: Float): Float = p(PropId.SPEED).valueAt(t).coerceIn(0.1f, 4f)

/** Integrates animated playback rate over clip-local time using bounded segment sampling. */
fun Layer.sourceElapsedAt(t: Float): Float {
    val end = (t - start).coerceIn(0f, duration)
    if (end <= 0f) return 0f
    val speedProp = p(PropId.SPEED)
    if (speedProp.keys.isEmpty()) return end * speedProp.base.coerceIn(0.1f, 4f)
    val boundaries = (listOf(0f, end) + speedProp.keys.map { it.t - start }.filter { it > 0f && it < end }).distinct().sorted()
    var elapsed = 0f
    for (index in 0 until boundaries.lastIndex) {
        val a = boundaries[index]; val b = boundaries[index + 1]
        val steps = 8; val dt = (b - a) / steps
        var previous = speedAt(start + a)
        for (step in 1..steps) {
            val current = speedAt(start + a + dt * step)
            elapsed += (previous + current) * 0.5f * dt
            previous = current
        }
    }
    return elapsed.coerceAtLeast(0f)
}

fun Layer.volumeAt(t: Float): Float = p(PropId.VOLUME).valueAt(t).coerceIn(0f, 2f)
