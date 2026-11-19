package com.kinetic.app.ai

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.kinetic.app.engine.EffectCraftAiBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.min

/**
 * Authors the AI edit directly in an isolated EffectCraft Session.
 *
 * This path deliberately does not create Kinetic Layer/Prop objects or open the
 * Kinetic timeline. Source files are staged as ordinary files because EffectCraft's
 * importer consumes paths, then native commands author the complete composition.
 */
internal object EffectCraftAiComposer {
    data class Composition(
        val compId: Long,
        val durationSeconds: Float,
        val audioUri: Uri?,
        val sourceFiles: List<File>
    )

    fun build(
        context: Context,
        footage: List<Uri>,
        audio: List<Uri>,
        prompt: String,
        style: String,
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

        val sourceDurations = footage.map { probeDuration(context, it) }
        val usable = min(items.length(), staged.size)
        val lengths = sourceDurations.take(usable)
        val totalSource = lengths.sum().coerceAtLeast(0.1f)
        val scale = min(1f, duration / totalSource)
        val clipLengths = lengths.map { (it * scale).coerceAtLeast(0.35f) }
        var cursor = 0f
        val videoLayers = ArrayList<Long>()
        onProgress("Building native footage layers and varied transitions…")
        for (index in 0 until usable) {
            if (cursor >= duration - 0.2f) break
            val itemId = items.optLong(index, -1L)
            if (itemId <= 0L) continue
            val clipDuration = min(clipLengths[index], duration - cursor).coerceAtLeast(0.2f)
            val overlap = if (index > 0) 0.12f else 0f
            val start = (cursor - overlap).coerceAtLeast(0f)
            val out = min(duration, cursor + clipDuration)
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
            check(layerId > 0L) { "EffectCraft couldn't place source clip ${index + 1}." }
            videoLayers.add(layerId)
            animateShot(layerId, index, clipDuration)
            cursor += clipDuration
        }
        check(videoLayers.isNotEmpty()) { "EffectCraft could not create any video layers." }

        // Fill unused time by extending the final source layer's out point where possible.
        val finalDuration = maxOf(duration, cursor).coerceAtMost(duration)
        addNativeMotionGraphics(comp, width, height, duration, style, prompt, frameRate)
        addOptionalNativeAdjustment(comp, videoLayers.firstOrNull())
        onProgress("EffectCraft composition authored.")
        return Composition(comp, finalDuration, audio.firstOrNull(), staged)
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
