package com.kinetic.app.engine

import org.json.JSONArray
import org.json.JSONObject

/** Control kinds, one per EffectCraft parameter UI type. */
enum class EcKind(val tag: String) {
    NUMBER("number"), SLIDER("slider"), PERCENT("percent"), ANGLE("angle"), PIXELS("pixels"),
    POINT("point"), POINT3("point3"), COLOR("color"), CHECKBOX("checkbox"), POPUP("popup"),
    LAYER("layer"), MASK("mask"), PATH("path"), TEXT("text"), FILE("file"), GRADIENT("gradient"),
    HIDDEN("hidden");

    companion object {
        fun from(tag: String): EcKind = entries.firstOrNull { it.tag == tag } ?: HIDDEN
    }
}

/** One animatable component of a parameter: its storage key and label suffix. */
data class EcComponent(val key: String, val suffix: String, val default: Float)

data class EcParam(
    val id: String,
    val name: String,
    val kind: EcKind,
    val defaults: List<Float>,
    val defaultText: String?,
    val min: Float,
    val max: Float,
    val decimals: Int,
    val options: List<String>
) {
    /**
     * Numeric components, stored in [com.kinetic.app.model.EffectInstance.parameters]. A point
     * is `id.x`/`id.y` (fractions of the layer), a colour `id.r/g/b/a`, everything else `id`.
     * Parameters that carry no number (layer references, paths, gradients) have none and keep
     * the engine default.
     */
    val components: List<EcComponent> by lazy {
        fun d(i: Int) = defaults.getOrElse(i) { 0f }
        when (kind) {
            EcKind.POINT -> listOf(EcComponent("$id.x", " X", d(0)), EcComponent("$id.y", " Y", d(1)))
            EcKind.POINT3 -> listOf(EcComponent("$id.x", " X", d(0)), EcComponent("$id.y", " Y", d(1)), EcComponent("$id.z", " Z", d(2)))
            EcKind.COLOR -> listOf(
                EcComponent("$id.r", " R", d(0)), EcComponent("$id.g", " G", d(1)),
                EcComponent("$id.b", " B", d(2)), EcComponent("$id.a", " A", if (defaults.size > 3) d(3) else 1f)
            )
            EcKind.NUMBER, EcKind.SLIDER, EcKind.PERCENT, EcKind.ANGLE, EcKind.PIXELS,
            EcKind.CHECKBOX, EcKind.POPUP, EcKind.MASK -> listOf(EcComponent(id, "", d(0)))
            else -> emptyList()
        }
    }
}

data class EcEffect(
    val id: String,
    val name: String,
    /** EffectCraft's own category (Effects & Presets tree). */
    val sourceCategory: String,
    /** One of Kinetic's browser categories ([EffectCategories]). */
    val category: String,
    val gpu: Boolean,
    val float: Boolean,
    val audio: Boolean,
    val params: List<EcParam>
) {
    val components: List<EcComponent> get() = params.flatMap { it.components }
    fun param(id: String): EcParam? = params.firstOrNull { it.id == id }
}

/** The effect library, loaded once from the native engine. */
object EffectCatalog {
    @Volatile private var loaded: List<EcEffect>? = null
    @Volatile private var failure: String? = null

    val error: String? get() = failure ?: if (!EffectCraftNative.available) EffectCraftNative.loadError else null

    val effects: List<EcEffect> get() = loaded ?: load()
    private val byId: Map<String, EcEffect> by lazy { effects.associateBy { it.id } }

    fun find(id: String): EcEffect? = byId[id]

    fun inCategory(category: String): List<EcEffect> =
        effects.filter { it.category == category }.sortedBy { it.name.lowercase() }

    fun search(query: String): List<EcEffect> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return effects.filter { it.name.lowercase().contains(q) || it.sourceCategory.lowercase().contains(q) }
            .sortedWith(compareBy({ !it.name.lowercase().startsWith(q) }, { it.name.lowercase() }))
    }

    @Synchronized
    private fun load(): List<EcEffect> {
        loaded?.let { return it }
        if (!EffectCraftNative.available) return emptyList()
        val parsed = runCatching {
            val json = EffectCraftNative.nativeCatalogJson() ?: error("native catalog returned nothing")
            parse(JSONObject(json).getJSONArray("effects"))
        }.onFailure { failure = it.message ?: it.javaClass.simpleName }.getOrDefault(emptyList())
        if (parsed.isNotEmpty()) loaded = parsed
        return parsed
    }

    private fun parse(array: JSONArray): List<EcEffect> = List(array.length()) { i ->
        val e = array.getJSONObject(i)
        val source = e.getString("category")
        val name = e.getString("name")
        EcEffect(
            id = e.getString("id"), name = name, sourceCategory = source,
            category = EffectCategories.of(source, name),
            gpu = e.optBoolean("gpu"), float = e.optBoolean("float"), audio = e.optBoolean("audio"),
            params = e.getJSONArray("params").let { ps -> List(ps.length()) { parseParam(ps.getJSONObject(it)) } }
        )
    }

    private fun parseParam(p: JSONObject): EcParam {
        val kind = EcKind.from(p.getString("type"))
        val d = p.opt("default")
        val defaults = when (d) {
            is JSONArray -> List(d.length()) { d.optDouble(it).toFloat() }
            is Number -> listOf(d.toFloat())
            is Boolean -> listOf(if (d) 1f else 0f)
            else -> emptyList()
        }
        val options = p.optJSONArray("options")?.let { o -> List(o.length()) { o.getString(it) } } ?: emptyList()
        val (min, max) = when {
            p.has("sliderMin") -> p.getDouble("sliderMin").toFloat() to p.getDouble("sliderMax").toFloat()
            kind == EcKind.PERCENT -> 0f to 100f
            kind == EcKind.ANGLE -> -360f to 360f
            kind == EcKind.PIXELS -> 0f to 1000f
            kind == EcKind.POINT || kind == EcKind.POINT3 -> -1f to 2f
            kind == EcKind.COLOR -> 0f to 1f
            kind == EcKind.POPUP -> 0f to (options.size - 1).coerceAtLeast(0).toFloat()
            else -> -1000f to 1000f
        }
        return EcParam(
            id = p.getString("id"), name = p.getString("name"), kind = kind, defaults = defaults,
            defaultText = d as? String, min = min, max = if (max > min) max else min + 1f,
            decimals = p.optInt("decimals", if (kind == EcKind.ANGLE || kind == EcKind.PIXELS) 1 else 2),
            options = options
        )
    }
}

/**
 * Maps EffectCraft's 21 categories onto Kinetic's 11 browser categories. Whole categories map by
 * default; individual effects are moved where their function belongs elsewhere (a glow is light,
 * a strobe is motion, a median filter is a blur).
 */
object EffectCategories {
    const val COLOR_LIGHT = "COLOR & LIGHT"
    const val STYLIZE = "STYLIZE"
    const val DRAWING_EDGE = "DRAWING & EDGE"
    const val MOTION = "MOTION"
    const val DISTORTION = "DISTORTION & WARP"
    const val PROCEDURAL = "PROCEDURAL"
    const val BLUR = "BLUR"
    const val THREE_D = "3D"
    const val TEXT = "TEXT"
    const val AUDIO = "AUDIO"
    const val OTHER = "OTHER"

    val all = listOf(COLOR_LIGHT, STYLIZE, DRAWING_EDGE, MOTION, DISTORTION, PROCEDURAL, BLUR, THREE_D, TEXT, AUDIO, OTHER)

    private val bySource = mapOf(
        "3D Channel" to THREE_D, "Audio" to AUDIO, "Blur & Sharpen" to BLUR, "Channel" to COLOR_LIGHT,
        "Color Correction" to COLOR_LIGHT, "Distort" to DISTORTION, "Expression Controls" to OTHER,
        "Generate" to PROCEDURAL, "Immersive Video" to THREE_D, "Keying" to DRAWING_EDGE,
        "Matte" to DRAWING_EDGE, "Noise & Grain" to STYLIZE, "Obsolete" to OTHER, "Paint" to DRAWING_EDGE,
        "Perspective" to THREE_D, "Simulation" to PROCEDURAL, "Stylize" to STYLIZE, "Text" to TEXT,
        "Time" to MOTION, "Transition" to MOTION, "Utility" to OTHER
    )

    private val byName: Map<String, String> = buildMap {
        fun move(category: String, vararg names: String) = names.forEach { put(it, category) }
        // Stylize
        move(COLOR_LIGHT, "Glow", "Posterize", "Threshold", "RGB Threshold", "Channel Threshold", "Palette Map", "Vignette", "Film Burn")
        move(MOTION, "Strobe Light")
        move(DISTORTION, "Motion Tile", "Edge Tile Repeat", "Kaleidoscope", "Hexagonal Tiles", "Scatter")
        move(DRAWING_EDGE, "Find Edges", "Roughen Edges", "Brush Strokes", "Cartoon")
        // Noise & Grain: generators are procedural, denoisers are blurs
        move(PROCEDURAL, "Fractal Noise", "Curl Noise", "Turbulent Noise")
        move(BLUR, "Median", "Median (Legacy)", "Remove Grain", "Dust & Scratches")
        // Perspective
        move(COLOR_LIGHT, "Drop Shadow", "Radial Shadow", "Surface Spotlight")
        move(DRAWING_EDGE, "Bevel Alpha", "Bevel Edges")
        // Simulation
        move(MOTION, "Card Dance", "Shatter", "Pixel Shatter", "Pixel Scatter")
        // Generate
        move(AUDIO, "Audio Spectrum", "Audio Waveform")
        move(COLOR_LIGHT, "Lens Flare", "Light Rays", "Light Sweep", "Radial Light Burst", "Beam", "Advanced Lightning", "Fill", "Eyedropper Fill")
        move(DRAWING_EDGE, "Stroke", "Scribble", "Paint Beads", "Paint Bucket", "Write-on", "Vegas")
        // Channel
        move(DRAWING_EDGE, "Set Matte", "Remove Color Matting")
        // Utility
        move(COLOR_LIGHT, "Apply Color LUT", "Cineon Converter", "Color Profile Converter", "HDR Compander", "HDR Highlight Compression", "Out-of-Range Display")
        // Obsolete
        move(TEXT, "Basic Text", "Path Text")
        move(THREE_D, "Basic 3D")
        move(COLOR_LIGHT, "Lightning")
        move(DRAWING_EDGE, "Color Key", "Luma Key", "Spill Suppressor")
        move(BLUR, "Gaussian Blur (Legacy)")
    }

    fun of(sourceCategory: String, name: String): String =
        byName[name] ?: bySource[sourceCategory] ?: OTHER
}
