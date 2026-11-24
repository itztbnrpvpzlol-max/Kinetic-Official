package com.kinetic.app.model

/**
 * Particle presets, tuned for the world-space 3D engine (pixels, seconds, y down; a direction spin
 * of 0° shoots up, 90° right, 180° down, 270° left). Every preset starts from the same neutral
 * baseline so switching presets never inherits leftovers from the previous one.
 *
 * Particle types follow Trapcode Particular's families: Sphere (dust, snow, rain), Glow Sphere
 * (sparks, bokeh-like lights), Star (four-pointed glinting stars), Cloudlet (smoke, fire, clouds,
 * dust), Streaklet (long-exposure light painting).
 */
object ParticlePresets {
    val names = listOf("Rain", "Snow", "Fire", "Sparks", "Fireworks", "Dust", "Bokeh", "Trail", "Comet")

    private fun baseline(): MutableMap<PropId, Float> = mutableMapOf(
        PropId.PARTICLE_RATE to 60f, PropId.PARTICLE_PREROLL to 1000f,
        PropId.PARTICLE_POS_X to 0f, PropId.PARTICLE_POS_Y to 0f, PropId.PARTICLE_POS_Z to 0f,
        PropId.PARTICLE_EMITTER_SIZE_X to 0f, PropId.PARTICLE_EMITTER_SIZE_Y to 0f, PropId.PARTICLE_EMITTER_SIZE_Z to 0f,
        PropId.PARTICLE_LIFETIME to 2f, PropId.PARTICLE_LIFE_RANDOM to 20f,
        PropId.PARTICLE_VELOCITY to 80f, PropId.PARTICLE_VELOCITY_RANDOM to 20f,
        PropId.PARTICLE_SPREAD to 28f, PropId.PARTICLE_DIRECTION_TILT to 0f, PropId.PARTICLE_DIRECTION_SPIN to 0f,
        PropId.PARTICLE_GRAVITY to 0f, PropId.PARTICLE_WIND_X to 0f, PropId.PARTICLE_WIND_Y to 0f, PropId.PARTICLE_WIND_Z to 0f,
        PropId.PARTICLE_DRAG to 0f, PropId.PARTICLE_TURBULENCE to 0f, PropId.PARTICLE_TURBULENCE_SPEED to 1f,
        PropId.PARTICLE_SIZE to 6f, PropId.PARTICLE_SIZE_RANDOM to 30f, PropId.PARTICLE_END_SIZE to 0f,
        PropId.PARTICLE_OPACITY to 100f, PropId.PARTICLE_END_OPACITY to 0f,
        PropId.PARTICLE_FADE_IN to 0f, PropId.PARTICLE_FADE_OUT to 0f, PropId.PARTICLE_FEATHER to 0f,
        PropId.PARTICLE_ROTATION to 0f, PropId.PARTICLE_ROTATION_RANDOM to 0f,
        PropId.PARTICLE_SPIN to 0f, PropId.PARTICLE_SPIN_RANDOM to 0f, PropId.PARTICLE_ROTATION_SPEED to 0f,
        PropId.PARTICLE_MOTION_STRETCH to 0f,
        PropId.PARTICLE_TAIL_LENGTH to 0f, PropId.PARTICLE_TAIL_SEGMENTS to 8f, PropId.PARTICLE_TAIL_WIDTH to 100f,
        PropId.PARTICLE_TAIL_TAPER to 0f, PropId.PARTICLE_TAIL_OPACITY to 100f
    )

    private class Preset(
        val values: Map<PropId, Float>,
        val settings: (ParticleSettings) -> ParticleSettings
    )

    private fun build(name: String): Preset? = when (name) {
        // Wide emitter above the frame, fast near-vertical fall, short fading streak tails.
        "Rain" -> Preset(mapOf(
            PropId.PARTICLE_RATE to 420f, PropId.PARTICLE_PREROLL to 2000f,
            PropId.PARTICLE_POS_Y to -720f,
            PropId.PARTICLE_EMITTER_SIZE_X to 2600f, PropId.PARTICLE_EMITTER_SIZE_Y to 40f, PropId.PARTICLE_EMITTER_SIZE_Z to 1400f,
            PropId.PARTICLE_VELOCITY to 1300f, PropId.PARTICLE_VELOCITY_RANDOM to 15f,
            PropId.PARTICLE_SPREAD to 2f, PropId.PARTICLE_DIRECTION_SPIN to 188f,
            PropId.PARTICLE_GRAVITY to 500f, PropId.PARTICLE_WIND_X to -40f,
            PropId.PARTICLE_LIFETIME to 1.25f, PropId.PARTICLE_LIFE_RANDOM to 10f,
            PropId.PARTICLE_SIZE to 3f, PropId.PARTICLE_SIZE_RANDOM to 45f, PropId.PARTICLE_END_SIZE to 3f,
            PropId.PARTICLE_OPACITY to 60f, PropId.PARTICLE_END_OPACITY to 45f, PropId.PARTICLE_FADE_OUT to 8f,
            PropId.PARTICLE_TAIL_LENGTH to 70f, PropId.PARTICLE_TAIL_SEGMENTS to 4f,
            PropId.PARTICLE_TAIL_WIDTH to 70f, PropId.PARTICLE_TAIL_TAPER to 100f, PropId.PARTICLE_TAIL_OPACITY to 70f
        )) { it.copy(preset = "Rain", color = 0xFFBFD8FF.toInt(), endColor = 0xFF8FB4E8.toInt(), particleType = "Sphere",
            shape = "Circle", additive = false, tailEnabled = true, orientToMotion = false, sphereEmitter = false,
            emitterMotion = "Leave Trail", velocityFromMotion = false, showLayer = false) }

        // Slow wide drift with real depth: big and small flakes at different distances, gentle sway.
        "Snow" -> Preset(mapOf(
            PropId.PARTICLE_RATE to 70f, PropId.PARTICLE_PREROLL to 14000f,
            PropId.PARTICLE_POS_Y to -700f,
            PropId.PARTICLE_EMITTER_SIZE_X to 2800f, PropId.PARTICLE_EMITTER_SIZE_Y to 20f, PropId.PARTICLE_EMITTER_SIZE_Z to 1600f,
            PropId.PARTICLE_VELOCITY to 110f, PropId.PARTICLE_VELOCITY_RANDOM to 40f,
            PropId.PARTICLE_SPREAD to 50f, PropId.PARTICLE_DIRECTION_SPIN to 180f,
            PropId.PARTICLE_GRAVITY to 60f, PropId.PARTICLE_WIND_X to 22f, PropId.PARTICLE_DRAG to 10f,
            PropId.PARTICLE_TURBULENCE to 55f, PropId.PARTICLE_TURBULENCE_SPEED to 0.6f,
            PropId.PARTICLE_LIFETIME to 14f, PropId.PARTICLE_LIFE_RANDOM to 15f,
            PropId.PARTICLE_SIZE to 8f, PropId.PARTICLE_SIZE_RANDOM to 70f, PropId.PARTICLE_END_SIZE to 8f,
            PropId.PARTICLE_OPACITY to 90f, PropId.PARTICLE_END_OPACITY to 90f,
            PropId.PARTICLE_FADE_IN to 0f, PropId.PARTICLE_FADE_OUT to 4f, PropId.PARTICLE_FEATHER to 55f
        )) { it.copy(preset = "Snow", color = 0xFFFFFFFF.toInt(), endColor = 0xFFDDEBFF.toInt(), particleType = "Sphere",
            shape = "Circle", additive = false, tailEnabled = false, orientToMotion = false, sphereEmitter = false,
            emitterMotion = "Leave Trail", velocityFromMotion = false, showLayer = false) }

        // Soft additive cloudlets rising from a base, turbulent, white-hot to deep red.
        "Fire" -> Preset(mapOf(
            PropId.PARTICLE_RATE to 110f, PropId.PARTICLE_PREROLL to 1500f,
            PropId.PARTICLE_POS_Y to 330f,
            PropId.PARTICLE_EMITTER_SIZE_X to 170f, PropId.PARTICLE_EMITTER_SIZE_Y to 20f, PropId.PARTICLE_EMITTER_SIZE_Z to 90f,
            PropId.PARTICLE_VELOCITY to 250f, PropId.PARTICLE_VELOCITY_RANDOM to 40f,
            PropId.PARTICLE_SPREAD to 14f, PropId.PARTICLE_DIRECTION_SPIN to 0f,
            PropId.PARTICLE_GRAVITY to -140f, PropId.PARTICLE_DRAG to 10f,
            PropId.PARTICLE_TURBULENCE to 70f, PropId.PARTICLE_TURBULENCE_SPEED to 1.6f,
            PropId.PARTICLE_LIFETIME to 1.2f, PropId.PARTICLE_LIFE_RANDOM to 40f,
            PropId.PARTICLE_SIZE to 120f, PropId.PARTICLE_SIZE_RANDOM to 40f, PropId.PARTICLE_END_SIZE to 25f,
            PropId.PARTICLE_OPACITY to 65f, PropId.PARTICLE_END_OPACITY to 0f,
            PropId.PARTICLE_FADE_IN to 0f, PropId.PARTICLE_FADE_OUT to 55f, PropId.PARTICLE_FEATHER to 85f,
            PropId.PARTICLE_SPIN to 25f, PropId.PARTICLE_SPIN_RANDOM to 40f
        )) { it.copy(preset = "Fire", color = 0xFFFFB347.toInt(), endColor = 0xFFB01800.toInt(), particleType = "Cloudlet",
            shape = "Circle", additive = true, tailEnabled = false, orientToMotion = false, sphereEmitter = false,
            emitterMotion = "Leave Trail", velocityFromMotion = false, showLayer = false) }

        // A fountain of hot glowing sparks that arc under gravity and leave tails.
        "Sparks" -> Preset(mapOf(
            PropId.PARTICLE_RATE to 90f, PropId.PARTICLE_PREROLL to 1500f,
            PropId.PARTICLE_POS_Y to 220f,
            PropId.PARTICLE_VELOCITY to 560f, PropId.PARTICLE_VELOCITY_RANDOM to 55f,
            PropId.PARTICLE_SPREAD to 32f, PropId.PARTICLE_DIRECTION_SPIN to 0f,
            PropId.PARTICLE_GRAVITY to 900f, PropId.PARTICLE_DRAG to 3f,
            PropId.PARTICLE_TURBULENCE to 12f, PropId.PARTICLE_TURBULENCE_SPEED to 2f,
            PropId.PARTICLE_LIFETIME to 1.5f, PropId.PARTICLE_LIFE_RANDOM to 50f,
            PropId.PARTICLE_SIZE to 9f, PropId.PARTICLE_SIZE_RANDOM to 60f, PropId.PARTICLE_END_SIZE to 2f,
            PropId.PARTICLE_OPACITY to 100f, PropId.PARTICLE_END_OPACITY to 0f, PropId.PARTICLE_FADE_OUT to 35f,
            PropId.PARTICLE_TAIL_LENGTH to 130f, PropId.PARTICLE_TAIL_SEGMENTS to 6f,
            PropId.PARTICLE_TAIL_WIDTH to 45f, PropId.PARTICLE_TAIL_TAPER to 100f, PropId.PARTICLE_TAIL_OPACITY to 80f
        )) { it.copy(preset = "Sparks", color = 0xFFFFD27A.toInt(), endColor = 0xFFFF5A14.toInt(), particleType = "Glow Sphere",
            shape = "Circle", additive = true, tailEnabled = true, orientToMotion = false, sphereEmitter = false,
            emitterMotion = "Leave Trail", velocityFromMotion = false, showLayer = false) }

        // A full spherical burst (spread 180°) of glinting stars that slow down and fall.
        "Fireworks" -> Preset(mapOf(
            PropId.PARTICLE_RATE to 240f, PropId.PARTICLE_PREROLL to 2000f,
            PropId.PARTICLE_POS_Y to -160f,
            PropId.PARTICLE_VELOCITY to 380f, PropId.PARTICLE_VELOCITY_RANDOM to 30f,
            PropId.PARTICLE_SPREAD to 180f, PropId.PARTICLE_GRAVITY to 240f, PropId.PARTICLE_DRAG to 18f,
            PropId.PARTICLE_TURBULENCE to 8f, PropId.PARTICLE_TURBULENCE_SPEED to 1.5f,
            PropId.PARTICLE_LIFETIME to 1.9f, PropId.PARTICLE_LIFE_RANDOM to 25f,
            PropId.PARTICLE_SIZE to 16f, PropId.PARTICLE_SIZE_RANDOM to 40f, PropId.PARTICLE_END_SIZE to 3f,
            PropId.PARTICLE_OPACITY to 100f, PropId.PARTICLE_END_OPACITY to 0f, PropId.PARTICLE_FADE_OUT to 40f,
            PropId.PARTICLE_SPIN to 40f, PropId.PARTICLE_SPIN_RANDOM to 60f,
            PropId.PARTICLE_TAIL_LENGTH to 220f, PropId.PARTICLE_TAIL_SEGMENTS to 10f,
            PropId.PARTICLE_TAIL_WIDTH to 30f, PropId.PARTICLE_TAIL_TAPER to 100f, PropId.PARTICLE_TAIL_OPACITY to 70f
        )) { it.copy(preset = "Fireworks", color = 0xFFFFE28A.toInt(), endColor = 0xFFFF4A2E.toInt(), particleType = "Star",
            shape = "Circle", additive = true, tailEnabled = true, orientToMotion = false, sphereEmitter = false,
            emitterMotion = "Leave Trail", velocityFromMotion = false, showLayer = false) }

        // Faint, slow, soft smoke-like motes filling a whole 3D volume.
        "Dust" -> Preset(mapOf(
            PropId.PARTICLE_RATE to 28f, PropId.PARTICLE_PREROLL to 8000f,
            PropId.PARTICLE_EMITTER_SIZE_X to 2200f, PropId.PARTICLE_EMITTER_SIZE_Y to 1300f, PropId.PARTICLE_EMITTER_SIZE_Z to 1400f,
            PropId.PARTICLE_VELOCITY to 14f, PropId.PARTICLE_VELOCITY_RANDOM to 60f,
            PropId.PARTICLE_SPREAD to 180f, PropId.PARTICLE_DRAG to 2f,
            PropId.PARTICLE_TURBULENCE to 40f, PropId.PARTICLE_TURBULENCE_SPEED to 0.35f,
            PropId.PARTICLE_LIFETIME to 8f, PropId.PARTICLE_LIFE_RANDOM to 30f,
            PropId.PARTICLE_SIZE to 40f, PropId.PARTICLE_SIZE_RANDOM to 60f, PropId.PARTICLE_END_SIZE to 40f,
            PropId.PARTICLE_OPACITY to 22f, PropId.PARTICLE_END_OPACITY to 22f,
            PropId.PARTICLE_FADE_IN to 12f, PropId.PARTICLE_FADE_OUT to 25f, PropId.PARTICLE_FEATHER to 90f,
            PropId.PARTICLE_SPIN to 6f, PropId.PARTICLE_SPIN_RANDOM to 12f
        )) { it.copy(preset = "Dust", color = 0xFFD2C4AE.toInt(), endColor = 0xFFB9AC96.toInt(), particleType = "Cloudlet",
            shape = "Circle", additive = false, tailEnabled = false, orientToMotion = false, sphereEmitter = false,
            emitterMotion = "Leave Trail", velocityFromMotion = false, showLayer = false) }

        // Big soft discs at many depths, slowly drifting: out-of-focus lights.
        "Bokeh" -> Preset(mapOf(
            PropId.PARTICLE_RATE to 16f, PropId.PARTICLE_PREROLL to 6000f,
            PropId.PARTICLE_EMITTER_SIZE_X to 2200f, PropId.PARTICLE_EMITTER_SIZE_Y to 1200f, PropId.PARTICLE_EMITTER_SIZE_Z to 2400f,
            PropId.PARTICLE_VELOCITY to 22f, PropId.PARTICLE_VELOCITY_RANDOM to 50f,
            PropId.PARTICLE_SPREAD to 180f, PropId.PARTICLE_TURBULENCE to 25f, PropId.PARTICLE_TURBULENCE_SPEED to 0.3f,
            PropId.PARTICLE_LIFETIME to 6f, PropId.PARTICLE_LIFE_RANDOM to 30f,
            PropId.PARTICLE_SIZE to 95f, PropId.PARTICLE_SIZE_RANDOM to 80f, PropId.PARTICLE_END_SIZE to 95f,
            PropId.PARTICLE_OPACITY to 38f, PropId.PARTICLE_END_OPACITY to 38f,
            PropId.PARTICLE_FADE_IN to 12f, PropId.PARTICLE_FADE_OUT to 30f, PropId.PARTICLE_FEATHER to 18f
        )) { it.copy(preset = "Bokeh", color = 0xFFFFE8B0.toInt(), endColor = 0xFFFFB0E0.toInt(), particleType = "Sphere",
            shape = "Circle", additive = true, tailEnabled = false, orientToMotion = false, sphereEmitter = false,
            emitterMotion = "Leave Trail", velocityFromMotion = false, showLayer = false) }

        // Particles stay where the emitter left them: animate the layer's Position to draw a glowing ribbon.
        "Trail" -> Preset(mapOf(
            PropId.PARTICLE_RATE to 150f, PropId.PARTICLE_PREROLL to 0f,
            PropId.PARTICLE_VELOCITY to 0f, PropId.PARTICLE_VELOCITY_RANDOM to 0f, PropId.PARTICLE_SPREAD to 180f,
            PropId.PARTICLE_TURBULENCE to 14f, PropId.PARTICLE_TURBULENCE_SPEED to 1f,
            PropId.PARTICLE_LIFETIME to 1.6f, PropId.PARTICLE_LIFE_RANDOM to 20f,
            PropId.PARTICLE_SIZE to 26f, PropId.PARTICLE_SIZE_RANDOM to 30f, PropId.PARTICLE_END_SIZE to 0f,
            PropId.PARTICLE_OPACITY to 100f, PropId.PARTICLE_END_OPACITY to 0f, PropId.PARTICLE_FADE_OUT to 50f
        )) { it.copy(preset = "Trail", color = 0xFF79D8FF.toInt(), endColor = 0xFF1D6DFF.toInt(), particleType = "Glow Sphere",
            shape = "Circle", additive = true, tailEnabled = false, orientToMotion = false, sphereEmitter = false,
            emitterMotion = "Leave Trail", velocityFromMotion = false, showLayer = false) }

        // A fast streaking head with a long, thin tapering tail (long-exposure light painting).
        "Comet" -> Preset(mapOf(
            PropId.PARTICLE_RATE to 36f, PropId.PARTICLE_PREROLL to 1200f,
            PropId.PARTICLE_POS_X to -700f, PropId.PARTICLE_POS_Y to -420f,
            PropId.PARTICLE_VELOCITY to 760f, PropId.PARTICLE_VELOCITY_RANDOM to 12f,
            PropId.PARTICLE_SPREAD to 3f, PropId.PARTICLE_DIRECTION_SPIN to 120f,
            PropId.PARTICLE_TURBULENCE to 6f, PropId.PARTICLE_TURBULENCE_SPEED to 1.2f,
            PropId.PARTICLE_LIFETIME to 1.8f, PropId.PARTICLE_LIFE_RANDOM to 15f,
            PropId.PARTICLE_SIZE to 26f, PropId.PARTICLE_SIZE_RANDOM to 25f, PropId.PARTICLE_END_SIZE to 6f,
            PropId.PARTICLE_OPACITY to 100f, PropId.PARTICLE_END_OPACITY to 0f, PropId.PARTICLE_FADE_OUT to 30f,
            PropId.PARTICLE_TAIL_LENGTH to 700f, PropId.PARTICLE_TAIL_SEGMENTS to 24f,
            PropId.PARTICLE_TAIL_WIDTH to 70f, PropId.PARTICLE_TAIL_TAPER to 100f, PropId.PARTICLE_TAIL_OPACITY to 90f
        )) { it.copy(preset = "Comet", color = 0xFFFFF0C8.toInt(), endColor = 0xFFFF7A2A.toInt(), particleType = "Glow Sphere",
            shape = "Circle", additive = true, tailEnabled = true, orientToMotion = false, sphereEmitter = false,
            emitterMotion = "Leave Trail", velocityFromMotion = false, showLayer = false) }

        else -> null
    }

    /** Applies [name] to [layer]: resets every particle property to the baseline, then the preset's overrides. */
    fun apply(layer: Layer, name: String): Layer {
        val preset = build(name) ?: return layer
        val values = baseline().also { it.putAll(preset.values) }
        // Pre-roll must cover the longest possible particle life, otherwise the scene visibly builds up
        // from empty at the start. Trail is the exception: it draws a ribbon from the emitter's motion.
        if (name != "Trail") {
            val life = (values[PropId.PARTICLE_LIFETIME] ?: 2f) * (1f + (values[PropId.PARTICLE_LIFE_RANDOM] ?: 0f) / 100f)
            values[PropId.PARTICLE_PREROLL] = maxOf(values[PropId.PARTICLE_PREROLL] ?: 0f, life * 1000f + 100f)
        }
        val props = values.entries.fold(layer.props) { acc, e -> acc + (e.key to Prop(e.value)) }
        return layer.copy(props = props, particleSettings = preset.settings(layer.particles))
    }
}
