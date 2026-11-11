package com.kinetic.app.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kinetic.app.model.fmt
import kotlin.math.roundToInt

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("kinetic_prefs", Context.MODE_PRIVATE)

    companion object {
        val RESOLUTIONS = listOf("720P", "1080P", "1440P", "4K")
        val FPS = listOf(24, 30, 60)
        val COMP_LENGTHS = listOf(5, 10, 30, 60)
        val TIME_FORMATS = listOf("00:00", "Frames", "Timecode")
        val SENSITIVITY = listOf("Slow", "Normal", "Fast")
        private val SENS_VALUES = listOf(0.25f, 1f, 3f)
    }

    // all index-based, saved to SharedPreferences
    var resolution by mutableIntStateOf(prefs.getInt("resolution", 1))
        private set
    var frameRate by mutableIntStateOf(prefs.getInt("frameRate", 1))
        private set
    var compLength by mutableIntStateOf(prefs.getInt("compLength", 0))
        private set
    var timeFormat by mutableIntStateOf(prefs.getInt("timeFormat", 0))
        private set
    var sensitivity by mutableIntStateOf(prefs.getInt("sensitivity", 1))
        private set
    var snap by mutableStateOf(prefs.getBoolean("snap", true))
        private set
    var loop by mutableStateOf(prefs.getBoolean("loop", false))
        private set

    fun updateResolution(i: Int) { resolution = i; prefs.edit().putInt("resolution", i).apply() }
    fun updateFrameRate(i: Int) { frameRate = i; prefs.edit().putInt("frameRate", i).apply() }
    fun updateCompLength(i: Int) { compLength = i; prefs.edit().putInt("compLength", i).apply() }
    fun updateTimeFormat(i: Int) { timeFormat = i; prefs.edit().putInt("timeFormat", i).apply() }
    fun updateSensitivity(i: Int) { sensitivity = i; prefs.edit().putInt("sensitivity", i).apply() }
    fun updateSnap(v: Boolean) { snap = v; prefs.edit().putBoolean("snap", v).apply() }
    fun updateLoop(v: Boolean) { loop = v; prefs.edit().putBoolean("loop", v).apply() }

    val fps: Int get() = FPS[frameRate]
    val compSeconds: Int get() = COMP_LENGTHS[compLength]
    val scrubMultiplier: Float get() = SENS_VALUES[sensitivity]

    fun formatTime(t: Float): String {
        val total = t.coerceAtLeast(0f)
        return when (timeFormat) {
            1 -> "${(total * fps).roundToInt()}f"
            2 -> {
                val s = total.toInt()
                val f = ((total - s) * fps).toInt()
                fmt("%02d:%02d:%02d", s / 60, s % 60, f)
            }
            else -> {
                val s = total.toInt()
                fmt("%02d:%02d", s / 60, s % 60)
            }
        }
    }
}
