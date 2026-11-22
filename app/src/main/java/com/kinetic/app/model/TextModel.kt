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
    val pathOnMask: Boolean = false,
    val reversePath: Boolean = false,
    val perpendicular: Boolean = true,
    val forceAlign: Boolean = false,
    val firstMargin: Float = 0f,
    val lastMargin: Float = 0f
)

/**
 * A "Flow" text animator. Every text unit (character / word / line) animates from the FROM values
 * below to its resting state, staggered by [delayMs]. Wiggle runs on top as continuous noise.
 */
data class TextAnimator(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Animation 1",
    val enabled: Boolean = true,
    val collapsed: Boolean = false,
    val strength: Float = 100f,
    val delayMs: Float = 80f,
    val durationMs: Float = 600f,
    val ease: String = "Ease Out",
    val opacity: Float = 100f,
    val posX: Float = 0f,
    val posY: Float = 0f,
    val scale: Float = 100f,
    val scaleY: Float = 100f,
    val separateScale: Boolean = false,
    val rotation: Float = 0f,
    val tracking: Float = 0f,
    val rotX: Float = 0f,
    val rotY: Float = 0f,
    val wiggleX: Float = 0f,
    val wiggleY: Float = 0f,
    val wiggleScale: Float = 0f,
    val wiggleRot: Float = 0f,
    val wiggleSpeed: Float = 2f,
    val hold: Float = 0f,
    /** "All", "Per Character", "Per Word" or "Per Line". */
    val unit: String = "Per Character",
    val seed: Int = 0,
    /** Exit animation: plays towards the end of the layer instead of the start. */
    val reverse: Boolean = false
)

/** Whether the mask on this layer clips it. Text that uses its mask as a path is not clipped. */
val Layer.maskClips: Boolean get() = maskEnabled && !(type == LayerType.TEXT && textStyle.pathOnMask)

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
    .put("forceAlign", forceAlign).put("firstMargin", firstMargin.toDouble()).put("lastMargin", lastMargin.toDouble())

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
        perpendicular = j.optBoolean("perpendicular", true), forceAlign = j.optBoolean("forceAlign", false),
        firstMargin = j.optDouble("firstMargin", 0.0).toFloat(), lastMargin = j.optDouble("lastMargin", 0.0).toFloat()
    )
}

internal fun List<TextAnimator>.toJson(): org.json.JSONArray = org.json.JSONArray().also { arr ->
    forEach { a ->
        arr.put(org.json.JSONObject().put("id", a.id).put("name", a.name).put("enabled", a.enabled).put("collapsed", a.collapsed)
            .put("strength", a.strength.toDouble()).put("delayMs", a.delayMs.toDouble()).put("durationMs", a.durationMs.toDouble())
            .put("ease", a.ease).put("opacity", a.opacity.toDouble()).put("posX", a.posX.toDouble()).put("posY", a.posY.toDouble())
            .put("scale", a.scale.toDouble()).put("scaleY", a.scaleY.toDouble()).put("separateScale", a.separateScale)
            .put("rotation", a.rotation.toDouble()).put("tracking", a.tracking.toDouble())
            .put("rotX", a.rotX.toDouble()).put("rotY", a.rotY.toDouble())
            .put("wiggleX", a.wiggleX.toDouble()).put("wiggleY", a.wiggleY.toDouble())
            .put("wiggleScale", a.wiggleScale.toDouble()).put("wiggleRot", a.wiggleRot.toDouble())
            .put("wiggleSpeed", a.wiggleSpeed.toDouble()).put("hold", a.hold.toDouble())
            .put("unit", a.unit).put("seed", a.seed).put("reverse", a.reverse))
    }
}

internal fun textAnimatorsFromJson(arr: org.json.JSONArray?): List<TextAnimator> {
    if (arr == null) return emptyList()
    val d = TextAnimator()
    fun org.json.JSONObject.f(k: String, def: Float) = optDouble(k, def.toDouble()).toFloat()
    return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { j ->
        TextAnimator(
            id = j.optString("id", java.util.UUID.randomUUID().toString()), name = j.optString("name", d.name),
            enabled = j.optBoolean("enabled", true), collapsed = j.optBoolean("collapsed", false),
            strength = j.f("strength", d.strength), delayMs = j.f("delayMs", d.delayMs), durationMs = j.f("durationMs", d.durationMs),
            ease = j.optString("ease", d.ease), opacity = j.f("opacity", d.opacity), posX = j.f("posX", 0f), posY = j.f("posY", 0f),
            scale = j.f("scale", 100f), scaleY = j.f("scaleY", 100f), separateScale = j.optBoolean("separateScale", false),
            rotation = j.f("rotation", 0f), tracking = j.f("tracking", 0f), rotX = j.f("rotX", 0f), rotY = j.f("rotY", 0f),
            wiggleX = j.f("wiggleX", 0f), wiggleY = j.f("wiggleY", 0f), wiggleScale = j.f("wiggleScale", 0f),
            wiggleRot = j.f("wiggleRot", 0f), wiggleSpeed = j.f("wiggleSpeed", d.wiggleSpeed), hold = j.f("hold", 0f),
            unit = j.optString("unit", d.unit), seed = j.optInt("seed", 0), reverse = j.optBoolean("reverse", false)
        )
    }
}
