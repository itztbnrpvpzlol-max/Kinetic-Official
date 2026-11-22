package com.kinetic.app.model

import android.graphics.Matrix
import android.graphics.Camera
import java.util.Locale
import java.util.UUID
import com.kinetic.app.engine.NativeRenderEngine
import com.kinetic.app.engine.EffectCatalog
import com.kinetic.app.engine.EffectKeyframes
import kotlin.math.abs
import kotlin.math.pow

private val compWState = androidx.compose.runtime.mutableFloatStateOf(1920f)
private val compHState = androidx.compose.runtime.mutableFloatStateOf(1080f)

var COMP_W: Float
    get() = compWState.floatValue
    set(value) { compWState.floatValue = value }

var COMP_H: Float
    get() = compHState.floatValue
    set(value) { compHState.floatValue = value }

fun fmt(f: String, vararg a: Any): String = String.format(Locale.US, f, *a)

enum class LayerType(val label: String) {
    VIDEO("Video"), IMAGE("Image"), AUDIO("Audio"), TEXT("Text"),
    SHAPE("Shape"), ADJUSTMENT("Adjustment"), NULL("Null"), PRECOMP("Pre-comp"), CAMERA("Camera")
}

/** Transform properties, in After Effects order. scrubStep = value change per dp dragged. */
enum class PropId(
    val label: String,
    val scrubStep: Float,
    val vectorPointIndex: Int = -1,
    val vectorPointAxis: Char = ' '
) {
    ANCHOR_X("Anchor X", 0.15f),
    ANCHOR_Y("Anchor Y", 0.15f),
    POS_X("Position X", 0.15f),
    POS_Y("Position Y", 0.15f),
    POS_Z("Position Z", 0.15f),
    SCALE_X("Scale X", 0.08f),
    SCALE_Y("Scale Y", 0.08f),
    ROTATION("Rotation Z", 0.15f),
    ROTATION_X("Rotation X", 0.15f),
    ROTATION_Y("Rotation Y", 0.15f),
    OPACITY("Opacity", 0.12f),
    SPEED("Speed", 0.003f),
    VOLUME("Volume", 0.003f),
    CAM_ZOOM("Zoom", 1f),
    CAM_POI_X("Point of Interest X", 0.15f),
    CAM_POI_Y("Point of Interest Y", 0.15f),
    CAM_POI_Z("Point of Interest Z", 0.15f),
    CAM_FOCUS("Focus Distance", 1f),
    CAM_APERTURE("Aperture", 0.1f),
    CAM_BLUR("Blur Level", 0.12f),
    TEXT_FIRST_MARGIN("First Margin", 0.6f),
    TEXT_LAST_MARGIN("Last Margin", 0.6f),
    TA0_STRENGTH("Anim 1 · Strength", 0.5f),
    TA0_DELAY("Anim 1 · Delay", 1f),
    TA0_DURATION("Anim 1 · Duration", 3f),
    TA0_OPACITY("Anim 1 · Opacity", 0.5f),
    TA0_POS_X("Anim 1 · Pos X", 0.5f),
    TA0_POS_Y("Anim 1 · Pos Y", 0.5f),
    TA0_SCALE("Anim 1 · Scale", 0.3f),
    TA0_SCALE_Y("Anim 1 · Scale Y", 0.3f),
    TA0_ROTATION("Anim 1 · Rotation", 0.5f),
    TA0_TRACKING("Anim 1 · Tracking", 0.2f),
    TA0_ROT_X("Anim 1 · Rot X 3D", 0.5f),
    TA0_ROT_Y("Anim 1 · Rot Y 3D", 0.5f),
    TA0_WIG_X("Anim 1 · Wiggle X", 0.5f),
    TA0_WIG_Y("Anim 1 · Wiggle Y", 0.5f),
    TA0_WIG_SCALE("Anim 1 · Wiggle Scale", 0.3f),
    TA0_WIG_ROT("Anim 1 · Wiggle Rot", 0.5f),
    TA0_WIG_SPEED("Anim 1 · Speed", 0.05f),
    TA0_HOLD("Anim 1 · Hold", 0.5f),
    TA1_STRENGTH("Anim 2 · Strength", 0.5f),
    TA1_DELAY("Anim 2 · Delay", 1f),
    TA1_DURATION("Anim 2 · Duration", 3f),
    TA1_OPACITY("Anim 2 · Opacity", 0.5f),
    TA1_POS_X("Anim 2 · Pos X", 0.5f),
    TA1_POS_Y("Anim 2 · Pos Y", 0.5f),
    TA1_SCALE("Anim 2 · Scale", 0.3f),
    TA1_SCALE_Y("Anim 2 · Scale Y", 0.3f),
    TA1_ROTATION("Anim 2 · Rotation", 0.5f),
    TA1_TRACKING("Anim 2 · Tracking", 0.2f),
    TA1_ROT_X("Anim 2 · Rot X 3D", 0.5f),
    TA1_ROT_Y("Anim 2 · Rot Y 3D", 0.5f),
    TA1_WIG_X("Anim 2 · Wiggle X", 0.5f),
    TA1_WIG_Y("Anim 2 · Wiggle Y", 0.5f),
    TA1_WIG_SCALE("Anim 2 · Wiggle Scale", 0.3f),
    TA1_WIG_ROT("Anim 2 · Wiggle Rot", 0.5f),
    TA1_WIG_SPEED("Anim 2 · Speed", 0.05f),
    TA1_HOLD("Anim 2 · Hold", 0.5f),
    TA2_STRENGTH("Anim 3 · Strength", 0.5f),
    TA2_DELAY("Anim 3 · Delay", 1f),
    TA2_DURATION("Anim 3 · Duration", 3f),
    TA2_OPACITY("Anim 3 · Opacity", 0.5f),
    TA2_POS_X("Anim 3 · Pos X", 0.5f),
    TA2_POS_Y("Anim 3 · Pos Y", 0.5f),
    TA2_SCALE("Anim 3 · Scale", 0.3f),
    TA2_SCALE_Y("Anim 3 · Scale Y", 0.3f),
    TA2_ROTATION("Anim 3 · Rotation", 0.5f),
    TA2_TRACKING("Anim 3 · Tracking", 0.2f),
    TA2_ROT_X("Anim 3 · Rot X 3D", 0.5f),
    TA2_ROT_Y("Anim 3 · Rot Y 3D", 0.5f),
    TA2_WIG_X("Anim 3 · Wiggle X", 0.5f),
    TA2_WIG_Y("Anim 3 · Wiggle Y", 0.5f),
    TA2_WIG_SCALE("Anim 3 · Wiggle Scale", 0.3f),
    TA2_WIG_ROT("Anim 3 · Wiggle Rot", 0.5f),
    TA2_WIG_SPEED("Anim 3 · Speed", 0.05f),
    TA2_HOLD("Anim 3 · Hold", 0.5f),
    TA3_STRENGTH("Anim 4 · Strength", 0.5f),
    TA3_DELAY("Anim 4 · Delay", 1f),
    TA3_DURATION("Anim 4 · Duration", 3f),
    TA3_OPACITY("Anim 4 · Opacity", 0.5f),
    TA3_POS_X("Anim 4 · Pos X", 0.5f),
    TA3_POS_Y("Anim 4 · Pos Y", 0.5f),
    TA3_SCALE("Anim 4 · Scale", 0.3f),
    TA3_SCALE_Y("Anim 4 · Scale Y", 0.3f),
    TA3_ROTATION("Anim 4 · Rotation", 0.5f),
    TA3_TRACKING("Anim 4 · Tracking", 0.2f),
    TA3_ROT_X("Anim 4 · Rot X 3D", 0.5f),
    TA3_ROT_Y("Anim 4 · Rot Y 3D", 0.5f),
    TA3_WIG_X("Anim 4 · Wiggle X", 0.5f),
    TA3_WIG_Y("Anim 4 · Wiggle Y", 0.5f),
    TA3_WIG_SCALE("Anim 4 · Wiggle Scale", 0.3f),
    TA3_WIG_ROT("Anim 4 · Wiggle Rot", 0.5f),
    TA3_WIG_SPEED("Anim 4 · Speed", 0.05f),
    TA3_HOLD("Anim 4 · Hold", 0.5f),
    TA4_STRENGTH("Anim 5 · Strength", 0.5f),
    TA4_DELAY("Anim 5 · Delay", 1f),
    TA4_DURATION("Anim 5 · Duration", 3f),
    TA4_OPACITY("Anim 5 · Opacity", 0.5f),
    TA4_POS_X("Anim 5 · Pos X", 0.5f),
    TA4_POS_Y("Anim 5 · Pos Y", 0.5f),
    TA4_SCALE("Anim 5 · Scale", 0.3f),
    TA4_SCALE_Y("Anim 5 · Scale Y", 0.3f),
    TA4_ROTATION("Anim 5 · Rotation", 0.5f),
    TA4_TRACKING("Anim 5 · Tracking", 0.2f),
    TA4_ROT_X("Anim 5 · Rot X 3D", 0.5f),
    TA4_ROT_Y("Anim 5 · Rot Y 3D", 0.5f),
    TA4_WIG_X("Anim 5 · Wiggle X", 0.5f),
    TA4_WIG_Y("Anim 5 · Wiggle Y", 0.5f),
    TA4_WIG_SCALE("Anim 5 · Wiggle Scale", 0.3f),
    TA4_WIG_ROT("Anim 5 · Wiggle Rot", 0.5f),
    TA4_WIG_SPEED("Anim 5 · Speed", 0.05f),
    TA4_HOLD("Anim 5 · Hold", 0.5f),
    TA5_STRENGTH("Anim 6 · Strength", 0.5f),
    TA5_DELAY("Anim 6 · Delay", 1f),
    TA5_DURATION("Anim 6 · Duration", 3f),
    TA5_OPACITY("Anim 6 · Opacity", 0.5f),
    TA5_POS_X("Anim 6 · Pos X", 0.5f),
    TA5_POS_Y("Anim 6 · Pos Y", 0.5f),
    TA5_SCALE("Anim 6 · Scale", 0.3f),
    TA5_SCALE_Y("Anim 6 · Scale Y", 0.3f),
    TA5_ROTATION("Anim 6 · Rotation", 0.5f),
    TA5_TRACKING("Anim 6 · Tracking", 0.2f),
    TA5_ROT_X("Anim 6 · Rot X 3D", 0.5f),
    TA5_ROT_Y("Anim 6 · Rot Y 3D", 0.5f),
    TA5_WIG_X("Anim 6 · Wiggle X", 0.5f),
    TA5_WIG_Y("Anim 6 · Wiggle Y", 0.5f),
    TA5_WIG_SCALE("Anim 6 · Wiggle Scale", 0.3f),
    TA5_WIG_ROT("Anim 6 · Wiggle Rot", 0.5f),
    TA5_WIG_SPEED("Anim 6 · Speed", 0.05f),
    TA5_HOLD("Anim 6 · Hold", 0.5f),
    MASK_OPACITY("Mask Opacity", 0.12f),
    MASK_FEATHER("Mask Feather", 0.08f),
    MASK_EXPANSION("Mask Expansion", 0.08f),
    MASK_BRUSH_SIZE("Mask Brush Size", 0.015f),
    VECTOR_P1_X("Point 1 X", 0.0005f, 0, 'X'),
    VECTOR_P1_Y("Point 1 Y", 0.0005f, 0, 'Y'),
    VECTOR_P2_X("Point 2 X", 0.0005f, 1, 'X'),
    VECTOR_P2_Y("Point 2 Y", 0.0005f, 1, 'Y'),
    VECTOR_P3_X("Point 3 X", 0.0005f, 2, 'X'),
    VECTOR_P3_Y("Point 3 Y", 0.0005f, 2, 'Y'),
    VECTOR_P4_X("Point 4 X", 0.0005f, 3, 'X'),
    VECTOR_P4_Y("Point 4 Y", 0.0005f, 3, 'Y'),
    VECTOR_P5_X("Point 5 X", 0.0005f, 4, 'X'),
    VECTOR_P5_Y("Point 5 Y", 0.0005f, 4, 'Y'),
    VECTOR_P6_X("Point 6 X", 0.0005f, 5, 'X'),
    VECTOR_P6_Y("Point 6 Y", 0.0005f, 5, 'Y'),
    VECTOR_P7_X("Point 7 X", 0.0005f, 6, 'X'),
    VECTOR_P7_Y("Point 7 Y", 0.0005f, 6, 'Y'),
    VECTOR_P8_X("Point 8 X", 0.0005f, 7, 'X'),
    VECTOR_P8_Y("Point 8 Y", 0.0005f, 7, 'Y'),
    VECTOR_P9_X("Point 9 X", 0.0005f, 8, 'X'),
    VECTOR_P9_Y("Point 9 Y", 0.0005f, 8, 'Y'),
    VECTOR_P10_X("Point 10 X", 0.0005f, 9, 'X'),
    VECTOR_P10_Y("Point 10 Y", 0.0005f, 9, 'Y'),
    VECTOR_P11_X("Point 11 X", 0.0005f, 10, 'X'),
    VECTOR_P11_Y("Point 11 Y", 0.0005f, 10, 'Y'),
    VECTOR_P12_X("Point 12 X", 0.0005f, 11, 'X'),
    VECTOR_P12_Y("Point 12 Y", 0.0005f, 11, 'Y'),
    VECTOR_P13_X("Point 13 X", 0.0005f, 12, 'X'),
    VECTOR_P13_Y("Point 13 Y", 0.0005f, 12, 'Y'),
    VECTOR_P14_X("Point 14 X", 0.0005f, 13, 'X'),
    VECTOR_P14_Y("Point 14 Y", 0.0005f, 13, 'Y'),
    VECTOR_P15_X("Point 15 X", 0.0005f, 14, 'X'),
    VECTOR_P15_Y("Point 15 Y", 0.0005f, 14, 'Y'),
    VECTOR_P16_X("Point 16 X", 0.0005f, 15, 'X'),
    VECTOR_P16_Y("Point 16 Y", 0.0005f, 15, 'Y'),
    VECTOR_P17_X("Point 17 X", 0.0005f, 16, 'X'),
    VECTOR_P17_Y("Point 17 Y", 0.0005f, 16, 'Y'),
    VECTOR_P18_X("Point 18 X", 0.0005f, 17, 'X'),
    VECTOR_P18_Y("Point 18 Y", 0.0005f, 17, 'Y'),
    VECTOR_P19_X("Point 19 X", 0.0005f, 18, 'X'),
    VECTOR_P19_Y("Point 19 Y", 0.0005f, 18, 'Y'),
    VECTOR_P20_X("Point 20 X", 0.0005f, 19, 'X'),
    VECTOR_P20_Y("Point 20 Y", 0.0005f, 19, 'Y'),
    VECTOR_P21_X("Point 21 X", 0.0005f, 20, 'X'),
    VECTOR_P21_Y("Point 21 Y", 0.0005f, 20, 'Y'),
    VECTOR_P22_X("Point 22 X", 0.0005f, 21, 'X'),
    VECTOR_P22_Y("Point 22 Y", 0.0005f, 21, 'Y'),
    VECTOR_P23_X("Point 23 X", 0.0005f, 22, 'X'),
    VECTOR_P23_Y("Point 23 Y", 0.0005f, 22, 'Y'),
    VECTOR_P24_X("Point 24 X", 0.0005f, 23, 'X'),
    VECTOR_P24_Y("Point 24 Y", 0.0005f, 23, 'Y'),
    VECTOR_P25_X("Point 25 X", 0.0005f, 24, 'X'),
    VECTOR_P25_Y("Point 25 Y", 0.0005f, 24, 'Y'),
    VECTOR_P26_X("Point 26 X", 0.0005f, 25, 'X'),
    VECTOR_P26_Y("Point 26 Y", 0.0005f, 25, 'Y'),
    VECTOR_P27_X("Point 27 X", 0.0005f, 26, 'X'),
    VECTOR_P27_Y("Point 27 Y", 0.0005f, 26, 'Y'),
    VECTOR_P28_X("Point 28 X", 0.0005f, 27, 'X'),
    VECTOR_P28_Y("Point 28 Y", 0.0005f, 27, 'Y'),
    VECTOR_P29_X("Point 29 X", 0.0005f, 28, 'X'),
    VECTOR_P29_Y("Point 29 Y", 0.0005f, 28, 'Y'),
    VECTOR_P30_X("Point 30 X", 0.0005f, 29, 'X'),
    VECTOR_P30_Y("Point 30 Y", 0.0005f, 29, 'Y'),
    VECTOR_P31_X("Point 31 X", 0.0005f, 30, 'X'),
    VECTOR_P31_Y("Point 31 Y", 0.0005f, 30, 'Y'),
    VECTOR_P32_X("Point 32 X", 0.0005f, 31, 'X'),
    VECTOR_P32_Y("Point 32 Y", 0.0005f, 31, 'Y'),
    VECTOR_P33_X("Point 33 X", 0.0005f, 32, 'X'),
    VECTOR_P33_Y("Point 33 Y", 0.0005f, 32, 'Y'),
    VECTOR_P34_X("Point 34 X", 0.0005f, 33, 'X'),
    VECTOR_P34_Y("Point 34 Y", 0.0005f, 33, 'Y'),
    VECTOR_P35_X("Point 35 X", 0.0005f, 34, 'X'),
    VECTOR_P35_Y("Point 35 Y", 0.0005f, 34, 'Y'),
    VECTOR_P36_X("Point 36 X", 0.0005f, 35, 'X'),
    VECTOR_P36_Y("Point 36 Y", 0.0005f, 35, 'Y'),
    VECTOR_P37_X("Point 37 X", 0.0005f, 36, 'X'),
    VECTOR_P37_Y("Point 37 Y", 0.0005f, 36, 'Y'),
    VECTOR_P38_X("Point 38 X", 0.0005f, 37, 'X'),
    VECTOR_P38_Y("Point 38 Y", 0.0005f, 37, 'Y'),
    VECTOR_P39_X("Point 39 X", 0.0005f, 38, 'X'),
    VECTOR_P39_Y("Point 39 Y", 0.0005f, 38, 'Y'),
    VECTOR_P40_X("Point 40 X", 0.0005f, 39, 'X'),
    VECTOR_P40_Y("Point 40 Y", 0.0005f, 39, 'Y'),
    VECTOR_P41_X("Point 41 X", 0.0005f, 40, 'X'),
    VECTOR_P41_Y("Point 41 Y", 0.0005f, 40, 'Y'),
    VECTOR_P42_X("Point 42 X", 0.0005f, 41, 'X'),
    VECTOR_P42_Y("Point 42 Y", 0.0005f, 41, 'Y'),
    VECTOR_P43_X("Point 43 X", 0.0005f, 42, 'X'),
    VECTOR_P43_Y("Point 43 Y", 0.0005f, 42, 'Y'),
    VECTOR_P44_X("Point 44 X", 0.0005f, 43, 'X'),
    VECTOR_P44_Y("Point 44 Y", 0.0005f, 43, 'Y'),
    VECTOR_P45_X("Point 45 X", 0.0005f, 44, 'X'),
    VECTOR_P45_Y("Point 45 Y", 0.0005f, 44, 'Y'),
    VECTOR_P46_X("Point 46 X", 0.0005f, 45, 'X'),
    VECTOR_P46_Y("Point 46 Y", 0.0005f, 45, 'Y'),
    VECTOR_P47_X("Point 47 X", 0.0005f, 46, 'X'),
    VECTOR_P47_Y("Point 47 Y", 0.0005f, 46, 'Y'),
    VECTOR_P48_X("Point 48 X", 0.0005f, 47, 'X'),
    VECTOR_P48_Y("Point 48 Y", 0.0005f, 47, 'Y'),
    VECTOR_P49_X("Point 49 X", 0.0005f, 48, 'X'),
    VECTOR_P49_Y("Point 49 Y", 0.0005f, 48, 'Y'),
    VECTOR_P50_X("Point 50 X", 0.0005f, 49, 'X'),
    VECTOR_P50_Y("Point 50 Y", 0.0005f, 49, 'Y');

    companion object {
        private val vectorPointIds by lazy {
            values().filter { it.isVectorPoint }.associateBy { it.vectorPointIndex to it.vectorPointAxis }
        }
        fun vectorPoint(index: Int, axis: Char): PropId? =
            vectorPointIds[index to axis.uppercaseChar()]
    }

    val isVectorPoint: Boolean get() = vectorPointIndex >= 0

    fun format(v: Float): String = taFormat(this, v) ?: when (this) {
        ANCHOR_X, ANCHOR_Y, POS_X, POS_Y, POS_Z, CAM_ZOOM, CAM_POI_X, CAM_POI_Y, CAM_POI_Z, CAM_FOCUS, CAM_APERTURE -> fmt("%.1f px", v)
        CAM_BLUR -> fmt("%.0f%%", v)
        SCALE_X, SCALE_Y -> fmt("%.1f%%", v)
        ROTATION, ROTATION_X, ROTATION_Y -> fmt("%.1f\u00B0", v)
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
        if (!t.isFinite()) return base
        val expr = expression?.trim()
        if (expr != null) {
            val match = Regex("wiggle\\s*\\(\\s*([0-9.]+)\\s*,\\s*([0-9.]+)\\s*\\)", RegexOption.IGNORE_CASE).find(expr)
            if (match != null) {
                val frequency = match.groupValues[1].toFloatOrNull()?.takeIf { it.isFinite() } ?: 2f
                val amplitude = match.groupValues[2].toFloatOrNull()?.takeIf { it.isFinite() } ?: 20f
                val baseValue = if (keys.isEmpty()) base else valueAtWithoutExpression(t)
                val noise = kotlin.math.sin(t * frequency * 6.2831855f) * 0.55f +
                    kotlin.math.sin(t * frequency * 13.7f) * 0.3f +
                    kotlin.math.sin(t * frequency * 31.1f) * 0.15f
                val result = baseValue + amplitude * noise
                return result.takeIf { it.isFinite() } ?: base
            }
        }
        return valueAtWithoutExpression(t).takeIf { it.isFinite() } ?: base
    }

    private fun valueAtWithoutExpression(t: Float): Float {
        if (keys.isEmpty()) return base
        val first = keys.first()
        val last = keys.last()
        if (!first.t.isFinite() || !first.v.isFinite()) return base
        if (t <= first.t) return first.v
        if (!last.t.isFinite() || !last.v.isFinite()) return first.v
        if (t >= last.t) return last.v
        var i = 0
        while (i < keys.size - 2 && keys[i + 1].t <= t) i++
        val a = keys[i]
        val b = keys[i + 1]
        if (!a.t.isFinite() || !a.v.isFinite()) return base
        if (!b.t.isFinite() || !b.v.isFinite()) return a.v
        val span = b.t - a.t
        if (!span.isFinite() || span <= 0.000001f) return b.v
        val u = ((t - a.t) / span).coerceIn(0f, 1f)
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
        val result = a.v + (b.v - a.v) * p
        return result.takeIf { it.isFinite() } ?: a.v
    }

    /** Set the value at time t. Not animated -> changes base. Animated -> edits/creates a key (like AE). */
    fun withValueAt(t: Float, v: Float, eps: Float): Prop {
        if (!t.isFinite() || !v.isFinite()) return this
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

data class MaskStroke(
    val points: List<VectorPoint>,
    val width: Float = 0.045f,
    val foreground: Boolean = true
)

data class LayerMask(
    val kind: String = "Pen",
    val points: List<VectorPoint> = emptyList(),
    val closed: Boolean = false,
    val inverted: Boolean = false,
    val mode: String = "Add",
    val opacity: Float = 100f,
    val feather: Float = 0f,
    val expansion: Float = 0f,
    val brushSize: Float = 0.045f,
    val strokes: List<MaskStroke> = emptyList(),
    val frozen: Boolean = false
)

data class EffectInstance(
    val id: String = UUID.randomUUID().toString(),
    /** Display name (the EffectCraft effect's name). */
    val name: String,
    val enabled: Boolean = true,
    /**
     * Numeric parameter components keyed by [com.kinetic.app.engine.EcComponent.key]: `id` for
     * plain values, `id.x`/`id.y` for points (fractions of the layer), `id.r/g/b/a` for colours.
     */
    val parameters: Map<String, Float> = emptyMap(),
    val parameterKeys: Map<String, List<Keyframe>> = emptyMap(),
    /** EffectCraft effect id, for example `ec.blur.gaussian`. */
    val effectId: String = "",
    /** Text and file parameters by EffectCraft parameter id. */
    val texts: Map<String, String> = emptyMap()
) {
    /** Stable per instance, so random-based effects look the same on every frame and export. */
    val seed: Int get() = id.hashCode() and 0x7fffffff

    fun parameterAt(key: String, time: Float, fallback: Float = 0f): Float {
        val base = parameters[key] ?: fallback
        return EffectKeyframes.evaluate(base, parameterKeys[key].orEmpty(), time)
            .takeIf { it.isFinite() } ?: fallback
    }
}

/** A new instance of the EffectCraft effect [effectId] with the engine's default parameters. */
fun createEffect(effectId: String): EffectInstance? {
    val spec = EffectCatalog.find(effectId) ?: return null
    val parameters = LinkedHashMap<String, Float>()
    spec.components.forEach { parameters[it.key] = it.default }
    val texts = spec.params.filter { it.components.isEmpty() && it.defaultText != null }
        .associate { it.id to it.defaultText!! }
    return EffectInstance(name = spec.name, effectId = spec.id, parameters = parameters, texts = texts)
}

/** Audio effects belong on audio and video layers, picture effects on everything except audio layers. */
fun effectFitsLayer(effect: com.kinetic.app.engine.EcEffect, type: LayerType?): Boolean = when (type) {
    null, LayerType.VIDEO -> true
    LayerType.AUDIO -> effect.audio
    else -> !effect.audio
}

fun effectFitsLayer(effectId: String, type: LayerType?): Boolean =
    EffectCatalog.find(effectId)?.let { effectFitsLayer(it, type) } ?: false

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
    val is3D: Boolean = false,
    /** CAMERA layers: two-node cameras look at a point of interest, one-node cameras use orientation only. */
    val cameraTwoNode: Boolean = true,
    val motionBlur: Boolean = false,
    val parentId: String? = null,
    val scaleLinked: Boolean = true,
    val positionLinked: Boolean = true,
    val text: String = "Text",
    val textStyle: TextStyle = TextStyle(),
    val textAnimators: List<TextAnimator> = emptyList(),
    val color: Int = 0xFF8D8BA3.toInt(),
    val props: Map<PropId, Prop>,
    /** Legacy simple layer flags (for example Grayscale). */
    val effects: List<String> = emptyList(),
    /** Ordered, parameterized GPU effect stack. */
    val effectStack: List<EffectInstance> = emptyList(),
    val speed: Float = 1f,
    val volume: Float = 1f,
    val label: Int = 0,
    val maskEnabled: Boolean = false,
    val blendMode: String = "Normal",
    val shapeKind: String = "Rectangle",
    val vectorPoints: List<VectorPoint> = emptyList(),
    val vectorSmooth: List<Boolean> = emptyList(),
    val vectorClosed: Boolean = true,
    val maskPath: LayerMask? = null,
    /** Child layers stored inside a pre-composition. */
    val precompLayers: List<Layer> = emptyList()
) {
    val end: Float get() = start + duration
    fun p(id: PropId): Prop = props[id] ?: Prop(
        when (id) {
            PropId.ANCHOR_X -> width / 2f
            PropId.ANCHOR_Y -> height / 2f
            PropId.POS_X -> COMP_W / 2f
            PropId.POS_Y -> COMP_H / 2f
            PropId.POS_Z -> 0f
            PropId.SCALE_X, PropId.SCALE_Y -> 100f
            PropId.OPACITY, PropId.MASK_OPACITY -> 100f
            PropId.SPEED, PropId.VOLUME -> 1f
            PropId.CAM_ZOOM, PropId.CAM_FOCUS -> CAMERA_DEFAULT_ZOOM
            PropId.CAM_POI_X -> COMP_W / 2f
            PropId.CAM_POI_Y -> COMP_H / 2f
            PropId.CAM_BLUR -> 100f
            else -> taDefault(id) ?: if (id.isVectorPoint) 0.5f else 0f
        }
    )
    fun maskAt(t: Float): LayerMask? = maskPath?.let { mask -> mask.copy(opacity = (props[PropId.MASK_OPACITY]?.valueAt(t) ?: mask.opacity).coerceIn(0f, 100f), feather = (props[PropId.MASK_FEATHER]?.valueAt(t) ?: mask.feather).coerceIn(0f, 100f), expansion = (props[PropId.MASK_EXPANSION]?.valueAt(t) ?: mask.expansion).coerceIn(-50f, 50f), brushSize = (props[PropId.MASK_BRUSH_SIZE]?.valueAt(t) ?: mask.brushSize * 100f).coerceIn(1f, 18f) / 100f) }
    fun vectorPointsAt(t: Float): List<VectorPoint> = vectorPoints.mapIndexed { index, point ->
        val x = PropId.vectorPoint(index, 'X')?.let { props[it]?.valueAt(t) } ?: point.x
        val y = PropId.vectorPoint(index, 'Y')?.let { props[it]?.valueAt(t) } ?: point.y
        VectorPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
    }
    fun activeAt(t: Float): Boolean = t >= start && t < end
    val isVector: Boolean get() = type == LayerType.SHAPE && shapeKind == "Vector"
}

const val VECTOR_STROKE = 10f

/** After Effects' default 50mm camera: 39.6 degree angle of view on a 1920px wide comp. */
val CAMERA_DEFAULT_ZOOM: Float get() = 50f * COMP_W / 36f

/** Zoom (px) for a lens focal length in mm, using AE's 36mm film size. */
fun cameraZoomForLens(mm: Float): Float = mm * COMP_W / 36f

fun buildVectorPath(
    points: List<VectorPoint>, smooth: List<Boolean>, closed: Boolean, w: Float, h: Float,
    path: android.graphics.Path = android.graphics.Path()
): android.graphics.Path {
    path.reset()
    val n = points.size
    if (n == 0) return path
    val loop = closed && n > 2
    fun px(i: Int) = points[i].x * w
    fun py(i: Int) = points[i].y * h
    fun sm(i: Int) = smooth.getOrElse(i) { false }
    fun prev(i: Int) = if (i > 0) i - 1 else if (loop) n - 1 else i
    fun next(i: Int) = if (i < n - 1) i + 1 else if (loop) 0 else i
    path.moveTo(px(0), py(0))
    val segments = if (loop) n else n - 1
    for (a in 0 until segments) {
        val b = (a + 1) % n
        val aSmooth = sm(a)
        val bSmooth = sm(b)
        if (!aSmooth && !bSmooth) {
            path.lineTo(px(b), py(b))
        } else {
            val c1x = if (aSmooth) px(a) + (px(next(a)) - px(prev(a))) / 6f else px(a)
            val c1y = if (aSmooth) py(a) + (py(next(a)) - py(prev(a))) / 6f else py(a)
            val c2x = if (bSmooth) px(b) - (px(next(b)) - px(prev(b))) / 6f else px(b)
            val c2y = if (bSmooth) py(b) - (py(next(b)) - py(prev(b))) / 6f else py(b)
            path.cubicTo(c1x, c1y, c2x, c2y, px(b), py(b))
        }
    }
    if (loop) path.close()
    return path
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
        PropId.POS_Z to Prop(0f),
        PropId.SCALE_X to Prop(100f),
        PropId.SCALE_Y to Prop(100f),
        PropId.ROTATION_X to Prop(0f),
        PropId.ROTATION_Y to Prop(0f),
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

fun newCamera(name: String, start: Float, duration: Float, twoNode: Boolean, zoom: Float = CAMERA_DEFAULT_ZOOM): Layer {
    val base = newLayer(LayerType.CAMERA, name, start, duration, COMP_W, COMP_H)
    val props = base.props + mapOf(
        PropId.POS_Z to Prop(-zoom),
        PropId.CAM_ZOOM to Prop(zoom),
        PropId.CAM_POI_X to Prop(COMP_W / 2f),
        PropId.CAM_POI_Y to Prop(COMP_H / 2f),
        PropId.CAM_POI_Z to Prop(0f),
        PropId.CAM_FOCUS to Prop(zoom),
        PropId.CAM_APERTURE to Prop(0f),
        PropId.CAM_BLUR to Prop(100f)
    )
    return base.copy(props = props, cameraTwoNode = twoNode, is3D = true)
}

// ---------- 3D camera (After Effects style) ----------
private fun mulM(a: FloatArray, b: FloatArray): FloatArray {
    val r = FloatArray(16)
    for (i in 0 until 4) for (j in 0 until 4) {
        var sum = 0f
        for (k in 0 until 4) sum += a[i * 4 + k] * b[k * 4 + j]
        r[i * 4 + j] = sum
    }
    return r
}
private fun identM() = floatArrayOf(1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f)
private fun transM(x: Float, y: Float, z: Float) = identM().also { it[3] = x; it[7] = y; it[11] = z }
private fun scaleM(x: Float, y: Float) = identM().also { it[0] = x; it[5] = y }
private fun rotXM(deg: Float): FloatArray {
    val r = Math.toRadians(deg.toDouble()); val c = Math.cos(r).toFloat(); val s = Math.sin(r).toFloat()
    return identM().also { it[5] = c; it[6] = -s; it[9] = s; it[10] = c }
}
private fun rotYM(deg: Float): FloatArray {
    val r = Math.toRadians(deg.toDouble()); val c = Math.cos(r).toFloat(); val s = Math.sin(r).toFloat()
    return identM().also { it[0] = c; it[2] = s; it[8] = -s; it[10] = c }
}
private fun rotZM(deg: Float): FloatArray {
    val r = Math.toRadians(deg.toDouble()); val c = Math.cos(r).toFloat(); val s = Math.sin(r).toFloat()
    return identM().also { it[0] = c; it[1] = -s; it[4] = s; it[5] = c }
}

/** Topmost visible camera layer active at [t], like AE's active camera. */
fun activeCamera(byId: Map<String, Layer>, t: Float): Layer? =
    byId.values.firstOrNull { it.type == LayerType.CAMERA && it.visible && it.activeAt(t) }

/** Camera-to-world rotation (columns are the camera's x/y/z axes in world space). */
private fun cameraRotation(cam: Layer, t: Float): FloatArray {
    val rx = cam.p(PropId.ROTATION_X).valueAt(t)
    val ry = cam.p(PropId.ROTATION_Y).valueAt(t)
    val rz = cam.p(PropId.ROTATION).valueAt(t)
    if (!cam.cameraTwoNode) return mulM(rotZM(rz), mulM(rotYM(ry), rotXM(rx)))
    val cx = cam.p(PropId.POS_X).valueAt(t); val cy = cam.p(PropId.POS_Y).valueAt(t); val cz = cam.p(PropId.POS_Z).valueAt(t)
    var fx = cam.p(PropId.CAM_POI_X).valueAt(t) - cx
    var fy = cam.p(PropId.CAM_POI_Y).valueAt(t) - cy
    var fz = cam.p(PropId.CAM_POI_Z).valueAt(t) - cz
    val fl = Math.sqrt((fx * fx + fy * fy + fz * fz).toDouble()).toFloat()
    if (fl < 1e-4f) { fx = 0f; fy = 0f; fz = 1f } else { fx /= fl; fy /= fl; fz /= fl }
    // x = normalize(down x forward); falls back when looking straight up/down
    var ax = fz; var ay = 0f; var az = -fx
    var al = Math.sqrt((ax * ax + az * az).toDouble()).toFloat()
    if (al < 1e-4f) { ax = 1f; ay = 0f; az = 0f; al = 1f }
    ax /= al; ay /= al; az /= al
    // y = forward x x
    val bx = fy * az - fz * ay; val by = fz * ax - fx * az; val bz = fx * ay - fy * ax
    val base = floatArrayOf(ax, bx, fx, 0f,  ay, by, fy, 0f,  az, bz, fz, 0f,  0f, 0f, 0f, 1f)
    return mulM(base, rotZM(rz))
}

/**
 * Writes into [out] the layer-plane -> screen homography for a 3D layer seen through [cam].
 * Position/rotation/orientation of the layer and its parent chain are composed in world space.
 */
private fun cameraLayerMatrix(layer: Layer, cam: Layer, t: Float, byId: Map<String, Layer>, depth: Int, out: Matrix) {
    var world = transM(-layer.transformValue(PropId.ANCHOR_X, t, layer.width / 2f),
        -layer.transformValue(PropId.ANCHOR_Y, t, layer.height / 2f), 0f)
    world = mulM(scaleM(layer.transformValue(PropId.SCALE_X, t, 100f) / 100f,
        layer.transformValue(PropId.SCALE_Y, t, 100f) / 100f), world)
    world = mulM(rotXM(layer.transformValue(PropId.ROTATION_X, t, 0f)), world)
    world = mulM(rotYM(layer.transformValue(PropId.ROTATION_Y, t, 0f)), world)
    world = mulM(rotZM(layer.transformValue(PropId.ROTATION, t, 0f)), world)
    world = mulM(transM(layer.transformValue(PropId.POS_X, t, COMP_W / 2f),
        layer.transformValue(PropId.POS_Y, t, COMP_H / 2f),
        layer.transformValue(PropId.POS_Z, t, 0f)), world)
    val parent = layer.parentId?.let { byId[it] }
    if (parent != null && parent.id != layer.id && depth < 16) {
        val pm = Matrix()
        layerMatrixInto(parent, t, byId.filterValues { it.type != LayerType.CAMERA }, pm, mutableMapOf(), depth + 1)
        val v = FloatArray(9); pm.getValues(v)
        val pe = floatArrayOf(v[0], v[1], 0f, v[2],  v[3], v[4], 0f, v[5],  0f, 0f, 1f, 0f,  0f, 0f, 0f, 1f)
        world = mulM(pe, mulM(transM(parent.transformValue(PropId.ANCHOR_X, t, parent.width / 2f),
            parent.transformValue(PropId.ANCHOR_Y, t, parent.height / 2f), 0f), world))
    }
    val r = cameraRotation(cam, t)
    val cx = cam.p(PropId.POS_X).valueAt(t); val cy = cam.p(PropId.POS_Y).valueAt(t); val cz = cam.p(PropId.POS_Z).valueAt(t)
    // view = R^T * T(-C)
    val rt = floatArrayOf(r[0], r[4], r[8], 0f,  r[1], r[5], r[9], 0f,  r[2], r[6], r[10], 0f,  0f, 0f, 0f, 1f)
    val view = mulM(rt, transM(-cx, -cy, -cz))
    val zoom = cam.p(PropId.CAM_ZOOM).valueAt(t).coerceAtLeast(1f)
    val proj = floatArrayOf(zoom, 0f, COMP_W / 2f, 0f,  0f, zoom, COMP_H / 2f, 0f,  0f, 0f, 0f, 1f,  0f, 0f, 1f, 0f)
    val m = mulM(proj, mulM(view, world))
    // layer plane has z = 0: use columns x, y and translation; rows x', y', w
    out.setValues(floatArrayOf(
        m[0], m[1], m[3],
        m[4], m[5], m[7],
        m[12], m[13], m[15]
    ))
}

/**
 * Reuses caller-owned Matrix instances for the CPU preview draw loop.
 * This avoids allocating a Matrix for every layer and parent on every frame.
 */
private fun Layer.transformValue(id: PropId, time: Float, fallback: Float): Float =
    p(id).valueAt(time).takeIf { it.isFinite() } ?: fallback

fun layerMatrixInto(
    layer: Layer,
    t: Float,
    byId: Map<String, Layer>,
    out: Matrix,
    scratch: MutableMap<Int, Matrix>,
    depth: Int = 0
) {
    if (layer.is3D && layer.type != LayerType.CAMERA) {
        val cam = activeCamera(byId, t)
        if (cam != null) { cameraLayerMatrix(layer, cam, t, byId, depth, out); return }
    }
    out.reset()
    out.postTranslate(-layer.transformValue(PropId.ANCHOR_X, t, layer.width / 2f),
        -layer.transformValue(PropId.ANCHOR_Y, t, layer.height / 2f))
    if (layer.is3D) {
        val camera = Camera()
        val cameraMatrix = Matrix()
        camera.save()
        camera.translate(0f, 0f, layer.transformValue(PropId.POS_Z, t, 0f))
        camera.rotateX(layer.transformValue(PropId.ROTATION_X, t, 0f))
        camera.rotateY(layer.transformValue(PropId.ROTATION_Y, t, 0f))
        camera.rotateZ(layer.transformValue(PropId.ROTATION, t, 0f))
        camera.getMatrix(cameraMatrix)
        camera.restore()
        out.postConcat(cameraMatrix)
    } else {
        out.postRotate(layer.transformValue(PropId.ROTATION, t, 0f))
    }
    out.postScale(layer.transformValue(PropId.SCALE_X, t, 100f) / 100f,
        layer.transformValue(PropId.SCALE_Y, t, 100f) / 100f)
    out.postTranslate(layer.transformValue(PropId.POS_X, t, COMP_W / 2f),
        layer.transformValue(PropId.POS_Y, t, COMP_H / 2f))
    val parent = layer.parentId?.let { byId[it] }
    if (parent != null && parent.id != layer.id && depth < 16) {
        val parentMatrix = scratch.getOrPut(depth + 1) { Matrix() }
        layerMatrixInto(parent, t, byId, parentMatrix, scratch, depth + 1)
        out.postTranslate(parent.transformValue(PropId.ANCHOR_X, t, parent.width / 2f),
            parent.transformValue(PropId.ANCHOR_Y, t, parent.height / 2f))
        out.postConcat(parentMatrix)
    }
}

/** Layer-space -> composition-space matrix at time t, including the parent chain. */
fun layerMatrix(layer: Layer, t: Float, byId: Map<String, Layer>, depth: Int = 0): Matrix {
    val m = Matrix()
    if (layer.is3D && layer.type != LayerType.CAMERA) {
        val cam = activeCamera(byId, t)
        if (cam != null) { cameraLayerMatrix(layer, cam, t, byId, depth, m); return m }
    }
    m.postTranslate(-layer.transformValue(PropId.ANCHOR_X, t, layer.width / 2f),
        -layer.transformValue(PropId.ANCHOR_Y, t, layer.height / 2f))
    if (layer.is3D) {
        val camera = Camera()
        val cameraMatrix = Matrix()
        camera.save()
        camera.translate(0f, 0f, layer.transformValue(PropId.POS_Z, t, 0f))
        camera.rotateX(layer.transformValue(PropId.ROTATION_X, t, 0f))
        camera.rotateY(layer.transformValue(PropId.ROTATION_Y, t, 0f))
        camera.rotateZ(layer.transformValue(PropId.ROTATION, t, 0f))
        camera.getMatrix(cameraMatrix)
        camera.restore()
        m.postConcat(cameraMatrix)
    } else {
        m.postRotate(layer.transformValue(PropId.ROTATION, t, 0f))
    }
    m.postScale(layer.transformValue(PropId.SCALE_X, t, 100f) / 100f,
        layer.transformValue(PropId.SCALE_Y, t, 100f) / 100f)
    m.postTranslate(layer.transformValue(PropId.POS_X, t, COMP_W / 2f),
        layer.transformValue(PropId.POS_Y, t, COMP_H / 2f))
    val parent = layer.parentId?.let { byId[it] }
    if (parent != null && depth < 16) {
        val pm = layerMatrix(parent, t, byId, depth + 1)
        // child space has its origin at the parent's anchor point (same as After Effects)
        m.postTranslate(parent.transformValue(PropId.ANCHOR_X, t, parent.width / 2f),
            parent.transformValue(PropId.ANCHOR_Y, t, parent.height / 2f))
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
