package com.kinetic.app.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kinetic.app.model.CompSettings
import com.kinetic.app.model.fmt
import kotlin.math.roundToInt

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("kinetic_prefs", Context.MODE_PRIVATE)
    companion object {
        val TIME_FORMATS = listOf("00:00", "Frames", "Timecode")
        val PREVIEW_QUALITIES = listOf("Auto", "Full", "Half", "Quarter")
        val INTERFACE_SIZES = listOf(
            "Smaller (70%)" to 0.70f,
            "Small (85%)" to 0.85f,
            "Automatic" to 1.00f,
            "Large (115%)" to 1.15f,
            "Larger (130%)" to 1.30f
        )
    }
    var lastComp by mutableStateOf(loadComp()); private set
    var timeFormat by mutableIntStateOf(prefs.getInt("timeFormat", 0)); private set
    var snap by mutableStateOf(prefs.getBoolean("snap", true)); private set
    var loop by mutableStateOf(prefs.getBoolean("loop", false)); private set
    var previewQualityIndex by mutableIntStateOf(prefs.getInt("previewQualityIndex", 0).coerceIn(0, 3)); private set
    var showPreviewDiagnostics by mutableStateOf(prefs.getBoolean("showPreviewDiagnostics", false)); private set
    var interfaceScale by mutableStateOf(prefs.getFloat("interfaceScale", 1f).coerceIn(0.70f, 1.30f)); private set
    private fun loadComp(): CompSettings {
        val oldFps = listOf(24, 30, 60).getOrElse(prefs.getInt("frameRate", 1)) { 30 }
        val oldLength = listOf(5, 10, 30, 60).getOrElse(prefs.getInt("compLength", 0)) { 5 }
        return CompSettings(
            width = prefs.getInt("compW", 1920), height = prefs.getInt("compH", 1080),
            fps = prefs.getInt("compFps", oldFps), duration = prefs.getFloat("compDur", oldLength.toFloat()),
            background = prefs.getLong("compBg", 0xFF000000L),
            shutterAngle = prefs.getFloat("compShutterAngle", 180f)
        ).sanitized()
    }
    fun rememberComp(c: CompSettings) {
        lastComp = c.sanitized()
        prefs.edit().putInt("compW", lastComp.width).putInt("compH", lastComp.height).putInt("compFps", lastComp.fps)
            .putFloat("compDur", lastComp.duration).putLong("compBg", lastComp.background)
            .putFloat("compShutterAngle", lastComp.shutterAngle).apply()
    }
    fun updateTimeFormat(i: Int) { timeFormat = i; prefs.edit().putInt("timeFormat", i).apply() }
    fun updateSnap(v: Boolean) { snap = v; prefs.edit().putBoolean("snap", v).apply() }
    fun updateLoop(v: Boolean) { loop = v; prefs.edit().putBoolean("loop", v).apply() }
    fun updatePreviewQuality(i: Int) { previewQualityIndex = i.coerceIn(0, 3); prefs.edit().putInt("previewQualityIndex", previewQualityIndex).apply() }
    fun updateShowPreviewDiagnostics(v: Boolean) { showPreviewDiagnostics = v; prefs.edit().putBoolean("showPreviewDiagnostics", v).apply() }
    fun updateInterfaceScale(v: Float) {
        interfaceScale = v.coerceIn(0.70f, 1.30f)
        prefs.edit().putFloat("interfaceScale", interfaceScale).apply()
    }
    val fps: Int get() = lastComp.fps
    fun formatTime(t: Float): String {
        val total = t.coerceAtLeast(0f)
        return when (timeFormat) {
            1 -> "\${(total * fps).roundToInt()}f"
            2 -> { val s = total.toInt(); val f = ((total - s) * fps).toInt(); fmt("%02d:%02d:%02d", s / 60, s % 60, f) }
            else -> { val s = total.toInt(); fmt("%02d:%02d", s / 60, s % 60) }
        }
    }
}
