package com.kinetic.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.kinetic.app.engine.EffectCraftAiBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Authors the AI edit directly in an isolated EffectCraft Session.
 *
 * This path deliberately does not create Kinetic Layer/Prop objects or open the
 * Kinetic timeline. Source files are staged as ordinary files because EffectCraft's
 * importer consumes paths, then native commands author the complete composition.
 */
internal object EffectCraftAiComposer {
    private data class SourceShot(val sourceIndex: Int, val sourceStart: Float, val duration: Float, val energy: Float, val saturation: Float)

    data class Composition(
        val compId: Long,
        val durationSeconds: Float,
        val audioUri: Uri?,
        val sourceFiles: List<File>,
        val beatCount: Int,
        val learnedSamples: Int
    )

    fun build(
        context: Context,
        footage: List<Uri>,
        audio: List<Uri>,
        prompt: String,
        style: String,
        styleReferences: List<Uri>,
        priorities: Set<String>,
        durationSeconds: Float,
        fps: Int,
        onProgress: (String) -> Unit = {}
    ): Composition {
        require(footage.isNotEmpty()) { "Add at least one video before generating." }
        EffectCraftAiBridge.reset().getOrThrow()
        command("file.newProject", JSONObject())

        val duration = durationSeconds.coerceIn(10f, 120f)
        val frameRate = fps.coerceIn(12, 60)
        val width = 1280
        val height = 720
        onProgress("Creating a native EffectCraft composition…")
        val comp = command(
            "comp.new",
            JSONObject()
                .put("name", "Kinetic AI • $style")
                .put("width", width)
                .put("height", height)
                .put("frameRate", frameRate)
                .put("duration", duration.toDouble())
                .put("background", "#080810")
                .put("open", true)
        ).optLong("comp", -1L)
        check(comp > 0L) { "EffectCraft did not return a composition ID." }

        val staged = footage.mapIndexed { index, uri -> stage(context, uri, index) }
        onProgress("Importing source footage into EffectCraft…")
        val paths = JSONArray().apply { staged.forEach { put(it.absolutePath) } }
        val imported = command("file.import", JSONObject().put("paths", paths))
        val errors = imported.optJSONArray("errors")
        if (errors != null && errors.length() > 0) {
            val details = (0 until errors.length()).joinToString("; ") { errors.optString(it) }
            throw IllegalStateException("EffectCraft couldn't import the selected footage: $details")
        }
        val items = imported.optJSONArray("items")
            ?: throw IllegalStateException("EffectCraft returned no imported footage items.")
        check(items.length() > 0) { "EffectCraft could not import any selected video files." }

        val sourceDurations = footage.take(usableCount(items, staged)).map { probeDuration(context, it) }
        val usable = min(items.length(), staged.size)
        val targetShotLength = when (style.lowercase(Locale.ROOT)) {
            "velocity" -> 1.55f
            "mograph" -> 2.1f
            else -> 2.8f
        }
        onProgress("Detecting scene changes and selecting distinct source moments…")
        val uniqueShots = (0 until usable).flatMap { sourceIndex ->
            detectShots(context, footage[sourceIndex], sourceDurations[sourceIndex], targetShotLength)
                .map { it.copy(sourceIndex = sourceIndex) }
        }
        check(uniqueShots.isNotEmpty()) { "No usable scene segments were found in the selected footage." }
        val remainingShots = uniqueShots.toMutableList()
        var previousSource = -1
        var previousStart = -1f
        var cursor = 0f
        var shotIndex = 0
        val videoLayers = ArrayList<Long>()
        onProgress("Building native EffectCraft footage layers and varied transitions…")
        while (cursor < duration - 0.2f) {
            if (remainingShots.isEmpty()) remainingShots.addAll(uniqueShots)
            val eligible = remainingShots.filter {
                it.sourceIndex != previousSource || abs(it.sourceStart - previousStart) > 0.25f
            }
            val pool = eligible.ifEmpty { remainingShots }
            val chosen = when (style.lowercase(Locale.ROOT)) {
                "velocity" -> pool.maxByOrNull { it.energy } ?: pool.first()
                "cinematic" -> pool.maxByOrNull { it.duration } ?: pool.first()
                else -> pool.maxByOrNull { it.energy * 0.7f + (1f / it.duration.coerceAtLeast(0.4f)) * 0.3f } ?: pool.first()
            }
            remainingShots.remove(chosen)
            val itemId = items.optLong(chosen.sourceIndex, -1L)
            check(itemId > 0L) { "EffectCraft returned an invalid source footage ID." }
            val clipDuration = min(chosen.duration, duration - cursor).coerceAtLeast(0.2f)
            val transition = transitionFor(shotIndex, chosen.energy, style)
            val overlap = if (shotIndex > 0) transition.second else 0f
            val start = (cursor - overlap).coerceAtLeast(0f)
            val out = min(duration, cursor + clipDuration + if (shotIndex == 0) 0f else overlap)
            if (shotIndex > 0 && videoLayers.isNotEmpty()) {
                // Keep the previous footage underneath the incoming native transition until
                // its reveal has completed; the source can continue naturally through this tail.
                command(
                    "layer.timing",
                    JSONObject()
                        .put("layers", JSONArray().put(videoLayers.last()))
                        .put("out", min(duration, cursor + overlap).toDouble())
                )
            }
            val layerId = command(
                "layer.addItem",
                JSONObject()
                    .put("comp", comp)
                    .put("item", itemId)
                    .put("time", start.toDouble())
                    .put("in", start.toDouble())
                    .put("out", out.toDouble())
                    .put("index", 1)
            ).optLong("layer", -1L)
            check(layerId > 0L) { "EffectCraft couldn't place source shot ${shotIndex + 1}." }
            if (chosen.sourceStart > 0.02f) {
                command(
                    "layer.slip",
                    JSONObject()
                        .put("layers", JSONArray().put(layerId))
                        .put("delta", chosen.sourceStart.toDouble())
                )
            }
            videoLayers.add(layerId)
            animateShot(layerId, shotIndex, clipDuration)
            if (shotIndex > 0) applyNativeTransition(layerId, start, transition.first)
            previousSource = chosen.sourceIndex
            previousStart = chosen.sourceStart
            cursor += clipDuration
            shotIndex++
        }
        check(videoLayers.isNotEmpty()) { "EffectCraft could not create any video layers." }
        val finalDuration = duration
        addNativeMotionGraphics(comp, width, height, duration, style, prompt, frameRate)
        addOptionalNativeAdjustment(comp, videoLayers.firstOrNull())
        onProgress("EffectCraft composition authored.")
        return Composition(comp, finalDuration, audio.firstOrNull(), staged)
    }

    private fun usableCount(items: JSONArray, staged: List<File>): Int = min(items.length(), staged.size)

    /**
     * Lightweight local cut detector. It samples tiny luminance frames, finds strong
     * visual discontinuities, then subdivides long scenes into non-identical edit windows.
     */
    private fun detectShots(
        context: Context,
        uri: Uri,
        sourceDuration: Float,
        targetLength: Float
    ): List<SourceShot> {
        val retriever = MediaMetadataRetriever()
        val motion = ArrayList<Pair<Float, Float>>()
        val saturationSamples = ArrayList<Pair<Float, Float>>()
        val boundaries = arrayListOf(0f)
        var previous: FloatArray? = null
        var lastCut = 0f
        val step = max(0.35f, sourceDuration / 160f)
        try {
            retriever.setDataSource(context, uri)
            var time = 0f
            while (time < sourceDuration) {
                val frame = runCatching {
                    retriever.getFrameAtTime((time * 1_000_000L).toLong(), MediaMetadataRetriever.OPTION_CLOSEST)
                }.getOrNull()
                if (frame != null) {
                    val tiny = Bitmap.createScaledBitmap(frame, 16, 9, true)
                    val pixels = IntArray(16 * 9)
                    tiny.getPixels(pixels, 0, 16, 0, 0, 16, 9)
                    if (tiny !== frame) tiny.recycle()
                    frame.recycle()
                    val luma = FloatArray(pixels.size) { index ->
                        val color = pixels[index]
                        (((color shr 16) and 255) * 0.2126f +
                            ((color shr 8) and 255) * 0.7152f +
                            (color and 255) * 0.0722f) / 255f
                    }
                    val difference = previous?.let { prior ->
                        luma.indices.map { abs(luma[it] - prior[it]) }.average().toFloat()
                    } ?: 0f
                    val saturation = pixels.map { color ->
                        val red = (color shr 16) and 255
                        val green = (color shr 8) and 255
                        val blue = color and 255
                        val hi = max(red, max(green, blue))
                        val lo = min(red, min(green, blue))
                        if (hi == 0) 0f else (hi - lo).toFloat() / hi
                    }.average().toFloat()
                    motion.add(time to difference)
                    saturationSamples.add(time to saturation)
                    if (difference > 0.22f && time - lastCut >= 0.75f) {
                        boundaries.add(time)
                        lastCut = time
                    }
                    previous = luma
                }
                time += step
            }
        } finally {
            runCatching { retriever.release() }
        }
        boundaries.add(sourceDuration)
        val result = ArrayList<SourceShot>()
        boundaries.distinct().sorted().zipWithNext().forEach { (sceneStart, sceneEnd) ->
            if (sceneEnd - sceneStart < 0.35f) return@forEach
            val sceneEnergy = motion.filter { it.first >= sceneStart && it.first < sceneEnd }
                .map { it.second }.average().takeIf { it.isFinite() }?.toFloat()?.coerceIn(0f, 1f) ?: 0f
            val sceneSaturation = saturationSamples.filter { it.first >= sceneStart && it.first < sceneEnd }
                .map { it.second }.average().takeIf { it.isFinite() }?.toFloat()?.coerceIn(0f, 1f) ?: 0.5f
            var segmentStart = sceneStart
            while (segmentStart < sceneEnd - 0.25f) {
                val segmentDuration = min(targetLength, sceneEnd - segmentStart)
                if (segmentDuration >= 0.35f) {
                    result.add(SourceShot(0, segmentStart, segmentDuration, sceneEnergy, sceneSaturation))
                }
                segmentStart += targetLength
            }
        }
        return result.ifEmpty { listOf(SourceShot(0, 0f, sourceDuration.coerceAtLeast(0.35f), 0f, 0.5f)) }
    }

    private fun transitionFor(index: Int, energy: Float, style: String): Pair<String, Float> {
        // IDs and durations come from EffectCraft's pinned built-in transitions.json.
        // Hard cuts are intentional for the first shot and selected high-energy accents.
        if (index == 0 || (style.equals("Velocity", true) && energy > 0.32f && index % 4 == 0)) {
            return "fadeIn" to 0.5f
        }
        val options = listOf(
            "wipeInFromLeft" to 1.0f,
            "diagonalSweepIn" to 1.2f,
            "clockReveal" to 1.2f,
            "blindsOpen" to 0.9f,
            "mosaicFadeIn" to 1.2f,
            "lineSweepIn" to 1.0f,
            "wipeInFromRight" to 1.0f
        )
        val offset = ((index - 1) + (energy * 10f).toInt().coerceIn(0, 6)) % options.size
        return options[offset]
    }

    private fun applyNativeTransition(layer: Long, startTime: Float, preset: String) {
        command("layer.select", JSONObject().put("layers", JSONArray().put(layer)))
        command("time.set", JSONObject().put("time", startTime.toDouble()))
        val result = command(
            "anim.applyBuiltinPreset",
            JSONObject().put("preset", preset).put("layers", JSONArray().put(layer))
        )
        val skipped = result.optJSONArray("skipped")
        check(skipped == null || skipped.length() == 0) {
            "EffectCraft couldn't apply transition $preset: ${skipped?.toString()}"
        }
    }

    private fun animateShot(layer: Long, index: Int, duration: Float) {
        when (index % 4) {
            0 -> Unit // deliberate hard cut
            1 -> {
                key(layer, "transform/opacity", 0.0, 0.0)
                key(layer, "transform/opacity", 0.14, 100.0)
            }
            2 -> {
                key(layer, "transform/scale", 0.0, JSONArray().put(100).put(100).put(100))
                key(layer, "transform/scale", 0.16, JSONArray().put(106).put(106).put(100))
                key(layer, "transform/scale", 0.42, JSONArray().put(100).put(100).put(100))
            }
            else -> {
                key(layer, "transform/position", 0.0, JSONArray().put(640 - 46).put(360).put(0))
                key(layer, "transform/position", 0.22, JSONArray().put(640).put(360).put(0))
            }
        }
        if (duration > 1.2f && index % 3 == 2) {
            key(layer, "transform/scale", (duration - 0.25).toDouble(), JSONArray().put(100).put(100).put(100))
            key(layer, "transform/scale", duration.toDouble(), JSONArray().put(104).put(104).put(100))
        }
    }

    private fun addNativeMotionGraphics(
        comp: Long,
        width: Int,
        height: Int,
        duration: Float,
        style: String,
        prompt: String,
        fps: Int
    ) {
        val wantsMograph = style.equals("Mograph", true) ||
            listOf("typography", "motion graphics", "mograph", "title", "text").any { prompt.contains(it, true) }
        if (!wantsMograph) return

        val title = prompt.trim()
            .replace(Regex("\\s+"), " ")
            .take(34)
            .ifBlank { "KINETIC / AMV" }
            .uppercase(Locale.ROOT)
        val nullId = command(
            "layer.newNull",
            JSONObject().put("comp", comp).put("name", "AI • Title Motion Controller")
        ).optLong("layer", -1L)
        check(nullId > 0L) { "EffectCraft couldn't create the Mograph null controller." }

        val accentId = command(
            "layer.newSolid",
            JSONObject()
                .put("comp", comp)
                .put("name", "AI • Animated Accent Solid")
                .put("color", "#9C72FF")
                .put("width", 520)
                .put("height", 10)
        ).optLong("layer", -1L)
        if (accentId > 0L) {
            key(accentId, "transform/position", 0.0, JSONArray().put(width / 2).put(height * 0.73).put(0))
            key(accentId, "transform/scale", 0.0, JSONArray().put(0).put(100).put(100))
            key(accentId, "transform/scale", 0.42, JSONArray().put(100).put(100).put(100))
            key(accentId, "transform/opacity", 0.0, 0.0)
            key(accentId, "transform/opacity", 0.28, 100.0)
            key(accentId, "transform/opacity", maxOf(0.0, duration - 0.3).toDouble(), 100.0)
            key(accentId, "transform/opacity", duration.toDouble(), 0.0)
        }

        val textId = command(
            "layer.newText",
            JSONObject()
                .put("comp", comp)
                .put("name", "AI • Kinetic Typography")
                .put("text", title)
                .put("position", JSONArray().put(width / 2).put(height * 0.66))
                .put("size", 66)
                .put("fill", "#FFFFFF")
                .put("stroke", "#171321")
                .put("applyFill", true)
                .put("applyStroke", true)
                .put("strokeWidth", 2)
                .put("fauxBold", true)
                .put("justify", "center")
        ).optLong("layer", -1L)
        check(textId > 0L) { "EffectCraft couldn't create the native typography layer." }
        command(
            "layer.setParent",
            JSONObject()
                .put("layers", JSONArray().put(textId))
                .put("parent", nullId)
                .put("compensate", true)
        )
        key(nullId, "transform/position", 0.0, JSONArray().put(width / 2 - 70).put(height * 0.66).put(0))
        key(nullId, "transform/position", 0.38, JSONArray().put(width / 2).put(height * 0.66).put(0))
        command(
            "layer.applyTextPreset",
            JSONObject().put("layer", textId).put("preset", when (style.lowercase(Locale.ROOT)) {
                "velocity" -> "bounceInWords"
                "cinematic" -> "fadeUpCharacters"
                else -> "trackingIn"
            })
        )
        key(textId, "transform/opacity", 0.0, 0.0)
        key(textId, "transform/opacity", 0.22, 100.0)
        key(textId, "transform/opacity", minOf(2.8f, duration - 0.3f).coerceAtLeast(0.5f).toDouble(), 100.0)
        key(textId, "transform/opacity", minOf(3.1f, duration).toDouble(), 0.0)
    }

    private fun addOptionalNativeAdjustment(comp: Long, targetLayer: Long?) {
        if (targetLayer == null) return
        // effect.list is a query, so its result is an array rather than an object.
        val catalog = JSONObject(EffectCraftAiBridge.execute("effect.list", "{}").getOrThrow())
        check(catalog.optBoolean("ok")) { "EffectCraft could not inspect its native effects." }
        val effects = catalog.optJSONArray("result") ?: return
        val glow = (0 until effects.length()).mapNotNull { effects.optJSONObject(it) }
            .firstOrNull { it.optString("name").contains("glow", true) } ?: return
        val adjustment = command(
            "layer.newAdjustment",
            JSONObject().put("comp", comp).put("name", "AI • Native Glow Adjustment")
        ).optLong("layer", -1L)
        if (adjustment <= 0L) return
        command("layer.select", JSONObject().put("layers", JSONArray().put(adjustment)))
        command("effect.apply", JSONObject().put("effect", glow.optString("id")))
    }

    private fun key(layer: Long, path: String, time: Double, value: Any) {
        command(
            "prop.addKey",
            JSONObject()
                .put("layer", layer)
                .put("path", path)
                .put("time", time)
                .put("value", value)
        )
    }

    private fun command(id: String, params: JSONObject): JSONObject {
        val response = JSONObject(EffectCraftAiBridge.execute(id, params.toString()).getOrThrow())
        check(response.optBoolean("ok")) {
            "EffectCraft command $id failed: ${response.optString("error", "unknown error")}"
        }
        return response.optJSONObject("result") ?: JSONObject()
    }

    private fun stage(context: Context, uri: Uri, index: Int): File {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "clip_${index + 1}.mp4"
        val extension = name.substringAfterLast('.', "mp4").lowercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() }.take(8).ifBlank { "mp4" }
        val destination = File(context.cacheDir, "effectcraft-ai-${index + 1}.$extension")
        context.contentResolver.openInputStream(uri)?.use { input ->
            destination.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
        } ?: throw IllegalStateException("Couldn't read selected footage: $name")
        check(destination.length() > 0L) { "Selected footage is empty: $name" }
        return destination
    }

    private fun probeDuration(context: Context, uri: Uri): Float {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val millis = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            (millis / 1000f).coerceIn(0.35f, 120f)
        } finally {
            runCatching { retriever.release() }
        }
    }
}
