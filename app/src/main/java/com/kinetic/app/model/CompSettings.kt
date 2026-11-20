package com.kinetic.app.model

data class CompSettings(
    val name: String = "Comp 1",
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 30,
    val duration: Float = 5f,
    val background: Long = 0xFF000000L,
    val shutterAngle: Float = 180f
) {
    fun sanitized(): CompSettings = copy(
        name = name.trim().ifEmpty { "Comp 1" },
        width = width.coerceIn(MIN_SIZE, MAX_SIZE),
        height = height.coerceIn(MIN_SIZE, MAX_SIZE),
        fps = fps.coerceIn(1, 120),
        duration = if (duration.isFinite()) duration.coerceIn(0.1f, MAX_DURATION) else 5f,
        background = background or 0xFF000000L,
        shutterAngle = if (shutterAngle.isFinite()) shutterAngle.coerceIn(0f, 360f) else 180f
    )

    data class Preset(val label: String, val width: Int, val height: Int, val fps: Int)

    companion object {
        const val MIN_SIZE = 16
        const val MAX_SIZE = 4096
        const val MAX_DURATION = 3f * 60f * 60f
        val FPS = listOf(12, 15, 24, 25, 30, 50, 60)
        val PRESETS = listOf(
            Preset("HD 1080 16:9", 1920, 1080, 30),
            Preset("HD 1080 9:16", 1080, 1920, 30),
            Preset("Square 1:1", 1080, 1080, 30),
            Preset("Portrait 4:5", 1080, 1350, 30),
            Preset("HD 720 16:9", 1280, 720, 30),
            Preset("4K UHD 16:9", 3840, 2160, 30),
            Preset("Film 24p 16:9", 1920, 1080, 24)
        )
        val BACKGROUNDS = listOf(
            0xFF000000L, 0xFFFFFFFFL, 0xFF808080L, 0xFF1A1A1AL, 0xFF00B140L, 0xFF0047BBL
        )
    }
}
