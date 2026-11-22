package com.kinetic.app.model

import java.util.UUID

/** Character/paragraph styling for a TEXT layer. */
data class TextStyle(
    val font: String = "Default",
    val weight: Int = 700,
    val italic: Boolean = false,
    val size: Float = 120f,
    val strokeEnabled: Boolean = false,
    val strokeColor: Int = 0xFF000000.toInt(),
    val strokeWidth: Float = 6f,
    val gradientEnabled: Boolean = false,
    val gradientColor: Int = 0xFF3F95F7.toInt(),
    val gradientAngle: Float = 90f,
    /** 0 = left, 1 = center, 2 = right. */
    val align: Int = 1,
    /** Extra space after every character, in px. */
    val tracking: Float = 0f,
    /** Line height as a multiple of the font size. */
    val lineHeight: Float = 1.2f,
    // Path options (After Effects "Path Options"): the text follows the layer's mask.
    // First/Last Margin are keyframeable layer properties (PropId.TEXT_FIRST_MARGIN / TEXT_LAST_MARGIN).
    val pathOnMask: Boolean = false,
    val reversePath: Boolean = false,
    val perpendicular: Boolean = true,
    val forceAlign: Boolean = false
)

/** Number of animator slots a text layer can hold (each owns a block of keyframeable properties). */
const val TEXT_ANIMATOR_SLOTS = 6

/** One keyframeable Flow parameter. Values live in [Layer.props] under `TA<slot>_<key>`. */
data class TaField(val key: String, val label: String, val default: Float, val min: Float, val max: Float, val format: String)

val TA_FIELDS: List<TaField> = listOf(
    TaField("STRENGTH", "Strength", 100f, 0f, 300f, "%.0f%%"),
    TaField("DELAY", "Delay", 80f, 0f, 3000f, "%.0f ms"),
    TaField("OPACITY", "Opacity", 100f, 0f, 100f, "%.0f%%"),
    TaField("POS_X", "Pos X", 0f, -4000f, 4000f, "%.1f px"),
    TaField("POS_Y", "Pos Y", 0f, -4000f, 4000f, "%.1f px"),
    TaField("SCALE", "Scale", 100f, 0f, 1000f, "%.1f%%"),
    TaField("SCALE_Y", "Scale Y", 100f, 0f, 1000f, "%.1f%%"),
    TaField("ROTATION", "Rotation", 0f, -3600f, 3600f, "%.1f\u00B0"),
    TaField("TRACKING", "Tracking", 0f, -300f, 600f, "%.1f px"),
    TaField("ROT_X", "Rot X 3D", 0f, -1080f, 1080f, "%.1f\u00B0"),
    TaField("ROT_Y", "Rot Y 3D", 0f, -1080f, 1080f, "%.1f\u00B0"),
    TaField("WIG_X", "Wiggle X", 0f, 0f, 1000f, "%.1f px"),
    TaField("WIG_Y", "Wiggle Y", 0f, 0f, 1000f, "%.1f px"),
    TaField("WIG_SCALE", "Wiggle Scale", 0f, 0f, 500f, "%.1f%%"),
    TaField("WIG_ROT", "Wiggle Rot", 0f, 0f, 720f, "%.1f\u00B0"),
    TaField("WIG_SPEED", "Speed", 2f, 0f, 60f, "%.2f/s"),
    TaField("HOLD", "Hold", 0f, 0f, 100f, "%.0f%%")
)

fun taId(slot: Int, key: String): PropId = PropId.valueOf("TA${slot}_$key")

private val taLookup: Map<PropId, TaField> by lazy {
    buildMap { for (slot in 0 until TEXT_ANIMATOR_SLOTS) for (f in TA_FIELDS) put(taId(slot, f.key), f) }
}

fun taFieldOf(id: PropId): TaField? = taLookup[id]
fun taDefault(id: PropId): Float? = when (id) {
    PropId.TEXT_FIRST_MARGIN, PropId.TEXT_LAST_MARGIN -> 0f
    else -> taLookup[id]?.default
}
fun taFormat(id: PropId, v: Float): String? = when (id) {
    PropId.TEXT_FIRST_MARGIN, PropId.TEXT_LAST_MARGIN -> fmt("%.1f px", v)
    else -> taLookup[id]?.let { fmt(it.format, v) }
}
fun taClamp(id: PropId, v: Float): Float? = taLookup[id]?.let { v.coerceIn(it.min, it.max) }
val PropId.isTextProp: Boolean get() = taLookup.containsKey(this) || this == PropId.TEXT_FIRST_MARGIN || this == PropId.TEXT_LAST_MARGIN

/**
 * A "Flow" text animator. Every text unit (character / word / line) animates from the FROM values
 * to its resting state, staggered by Delay. Wiggle runs on top as continuous noise. All numbers are
 * keyframeable layer properties in the animator's [slot]; this class holds the non-numeric settings.
 */
data class TextAnimator(
    val id: String = UUID.randomUUID().toString(),
    val slot: Int = 0,
    val name: String = "Animation 1",
    val enabled: Boolean = true,
    val collapsed: Boolean = false,
    val separateScale: Boolean = false,
    /** "All", "Per Character", "Per Word" or "Per Line". */
    val unit: String = "Per Character",
    val seed: Int = 0,
    /** Exit animation: plays towards the end of the layer instead of the start. */
    val reverse: Boolean = false
)

/** Whether the mask on this layer clips it. Text that uses its mask as a path is not clipped. */
val Layer.maskClips: Boolean get() = maskEnabled && !(type == LayerType.TEXT && textStyle.pathOnMask)

/** Hash of every keyframeable text value, so cached text rasters refresh when a property changes. */
fun Layer.textPropsHash(): Int {
    var h = 17
    for (a in textAnimators) for (f in TA_FIELDS) h = h * 31 + (props[taId(a.slot, f.key)]?.hashCode() ?: 0)
    h = h * 31 + (props[PropId.TEXT_FIRST_MARGIN]?.hashCode() ?: 0)
    h = h * 31 + (props[PropId.TEXT_LAST_MARGIN]?.hashCode() ?: 0)
    return h
}

val Layer.textIsAnimated: Boolean get() =
    type == LayerType.TEXT && (textAnimators.any { it.enabled } || (textStyle.pathOnMask && maskPath != null))

/** Resizes a layer's box while keeping an un-animated, centered anchor at the box center. */
fun Layer.withBox(w: Float, h: Float): Layer {
    if (kotlin.math.abs(w - width) < 0.5f && kotlin.math.abs(h - height) < 0.5f) return this
    val ax = p(PropId.ANCHOR_X); val ay = p(PropId.ANCHOR_Y)
    val centered = !ax.animated && !ay.animated &&
        kotlin.math.abs(ax.base - width / 2f) < 1f && kotlin.math.abs(ay.base - height / 2f) < 1f
    val newProps = if (centered) props + mapOf(
        PropId.ANCHOR_X to ax.copy(base = w / 2f),
        PropId.ANCHOR_Y to ay.copy(base = h / 2f)
    ) else props
    return copy(width = w, height = h, props = newProps)
}

// ---------- persistence ----------
internal fun TextStyle.toJson(): org.json.JSONObject = org.json.JSONObject()
    .put("font", font).put("weight", weight).put("italic", italic).put("size", size.toDouble())
    .put("strokeEnabled", strokeEnabled).put("strokeColor", strokeColor).put("strokeWidth", strokeWidth.toDouble())
    .put("gradientEnabled", gradientEnabled).put("gradientColor", gradientColor).put("gradientAngle", gradientAngle.toDouble())
    .put("align", align).put("tracking", tracking.toDouble()).put("lineHeight", lineHeight.toDouble())
    .put("pathOnMask", pathOnMask).put("reversePath", reversePath).put("perpendicular", perpendicular)
    .put("forceAlign", forceAlign)

internal fun textStyleFromJson(j: org.json.JSONObject?): TextStyle {
    if (j == null) return TextStyle()
    val d = TextStyle()
    return TextStyle(
        font = j.optString("font", d.font), weight = j.optInt("weight", d.weight), italic = j.optBoolean("italic", d.italic),
        size = j.optDouble("size", d.size.toDouble()).toFloat(),
        strokeEnabled = j.optBoolean("strokeEnabled", d.strokeEnabled), strokeColor = j.optInt("strokeColor", d.strokeColor),
        strokeWidth = j.optDouble("strokeWidth", d.strokeWidth.toDouble()).toFloat(),
        gradientEnabled = j.optBoolean("gradientEnabled", d.gradientEnabled), gradientColor = j.optInt("gradientColor", d.gradientColor),
        gradientAngle = j.optDouble("gradientAngle", d.gradientAngle.toDouble()).toFloat(),
        align = j.optInt("align", d.align), tracking = j.optDouble("tracking", 0.0).toFloat(),
        lineHeight = j.optDouble("lineHeight", d.lineHeight.toDouble()).toFloat(),
        pathOnMask = j.optBoolean("pathOnMask", false), reversePath = j.optBoolean("reversePath", false),
        perpendicular = j.optBoolean("perpendicular", true), forceAlign = j.optBoolean("forceAlign", false)
    )
}

internal fun List<TextAnimator>.toJson(): org.json.JSONArray = org.json.JSONArray().also { arr ->
    forEach { a ->
        arr.put(org.json.JSONObject().put("id", a.id).put("slot", a.slot).put("name", a.name).put("enabled", a.enabled)
            .put("collapsed", a.collapsed).put("separateScale", a.separateScale)
            .put("unit", a.unit).put("seed", a.seed).put("reverse", a.reverse))
    }
}

internal fun textAnimatorsFromJson(arr: org.json.JSONArray?): List<TextAnimator> {
    if (arr == null) return emptyList()
    val d = TextAnimator()
    return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { j ->
        TextAnimator(
            id = j.optString("id", java.util.UUID.randomUUID().toString()),
            slot = j.optInt("slot", 0).coerceIn(0, TEXT_ANIMATOR_SLOTS - 1), name = j.optString("name", d.name),
            enabled = j.optBoolean("enabled", true), collapsed = j.optBoolean("collapsed", false),
            separateScale = j.optBoolean("separateScale", false),
            unit = j.optString("unit", d.unit), seed = j.optInt("seed", 0), reverse = j.optBoolean("reverse", false)
        )
    }
}
