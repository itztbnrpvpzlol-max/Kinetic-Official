package com.kinetic.app.export

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import kotlin.math.ceil
import kotlin.math.roundToInt

enum class ExportCodec(val label: String, val mime: String, val note: String) {
    H264("H.264", "video/avc", "Plays everywhere"),
    HEVC("HEVC", "video/hevc", "Smaller files")
}

/** Bits per pixel per frame; the bitrate follows resolution and frame rate. */
enum class ExportQuality(val label: String, val bitsPerPixel: Float, val note: String) {
    DRAFT("Draft", 0.04f, "Smallest"),
    STANDARD("Standard", 0.07f, "Balanced"),
    HIGH("High", 0.11f, "Sharp"),
    MAX("Maximum", 0.17f, "Largest")
}

enum class ExportRange(val label: String) {
    FULL("Whole composition"),
    FROM_PLAYHEAD("Playhead to end"),
    TO_PLAYHEAD("Start to playhead")
}

data class ExportSettings(
    val width: Int,
    val height: Int,
    val fps: Int,
    val codec: ExportCodec = ExportCodec.H264,
    val quality: ExportQuality = ExportQuality.HIGH,
    val includeAudio: Boolean = true,
    val audioBitrate: Int = 192_000,
    val start: Float = 0f,
    val end: Float,
    val fileName: String
) {
    val duration: Float get() = (end - start).coerceAtLeast(0f)
    val frameCount: Int get() = ceil(duration * fps).toInt().coerceAtLeast(1)
    val videoBitrate: Int
        get() = (width.toLong() * height * fps * quality.bitsPerPixel).toLong().coerceIn(600_000L, 100_000_000L).toInt()
    val estimatedBytes: Long
        get() = ((videoBitrate + if (includeAudio) audioBitrate else 0) / 8.0 * duration).toLong()
    val safeFileName: String
        get() = fileName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "Kinetic export" }

    companion object {
        val heightPresets = listOf(480, 720, 1080, 1440, 2160)
        val frameRates = listOf(24, 25, 30, 50, 60)
        val audioBitrates = listOf(128_000, 192_000, 256_000)

        /** Even pixel size for [height] that keeps the composition's aspect ratio. */
        fun sizeFor(compWidth: Int, compHeight: Int, height: Int): Pair<Int, Int> {
            val h = height and 1.inv()
            val w = ((h.toLong() * compWidth / compHeight.coerceAtLeast(1)).toInt() and 1.inv()).coerceAtLeast(2)
            return w to h.coerceAtLeast(2)
        }
    }
}

/** What this device's video encoders can do, so the screen only offers workable choices. */
object ExportCapabilities {
    private fun capabilities(mime: String): List<MediaCodecInfo.VideoCapabilities> =
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }
                .mapNotNull { runCatching { it.getCapabilitiesForType(mime).videoCapabilities }.getOrNull() }
        }.getOrDefault(emptyList())

    fun supportsCodec(codec: ExportCodec): Boolean = capabilities(codec.mime).isNotEmpty()

    fun supports(codec: ExportCodec, width: Int, height: Int, fps: Int): Boolean =
        capabilities(codec.mime).any {
            runCatching { it.areSizeAndRateSupported(width, height, fps.toDouble()) }.getOrDefault(false)
        }

    fun maxBitrate(codec: ExportCodec): Int =
        capabilities(codec.mime).maxOfOrNull { it.bitrateRange.upper } ?: 20_000_000

    fun clampBitrate(codec: ExportCodec, bitrate: Int): Int = bitrate.coerceAtMost(maxBitrate(codec))
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    else -> "${(bytes / 1024.0).roundToInt()} KB"
}

internal fun formatClock(seconds: Float): String {
    val total = seconds.coerceAtLeast(0f)
    val m = (total / 60).toInt()
    val s = total - m * 60
    return "%d:%04.1f".format(m, s)
}
