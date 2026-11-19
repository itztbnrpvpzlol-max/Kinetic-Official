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
import android.os.Build
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
object EffectCraftAiComposer {
    private data class SourceShot(val sourceIndex: Int, val sourceStart: Float, val duration: Float, val energy: Float, val saturation: Float, val brightness: Float, val cutStrength: Float)

    data class Composition(
        val compId: Long,
        val durationSeconds: Float,
        val audioUri: Uri?,
        val sourceFiles: List<File>,
        val projectFile: File,
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

        val projectDirectory = File(context.filesDir, "kinetic-ai-projects/${System.currentTimeMillis()}").apply {
            check(mkdirs() || isDirectory) { "Kinetic couldn't create a folder for the native EffectCraft project." }
        }
        val stagedVideo = footage.mapIndexed { index, uri -> stage(context, uri, index, projectDirectory) }
        val stagedAudio = audio.firstOrNull()?.let { stage(context, it, footage.size, projectDirectory) }
        val staged = stagedVideo + listOfNotNull(stagedAudio)
        onProgress("Importing source footage into EffectCraft…")
        val paths = JSONArray().apply { stagedVideo.forEach { put(it.absolutePath) } }
        val imported = command("file.import", JSONObject().put("paths", paths))
        val errors = imported.optJSONArray("errors")
        if (errors != null && errors.length() > 0) {
            val details = (0 until errors.length()).joinToString("; ") { errors.optString(it) }
            throw IllegalStateException("EffectCraft couldn't import the selected footage: $details")
        }
        val items = imported.optJSONArray("items")
            ?: throw IllegalStateException("EffectCraft returned no imported footage items.")
        check(items.length() > 0) { "EffectCraft could not import any selected video files." }

        val sourceDurations = footage.take(usableCount(items, stagedVideo)).map { probeDuration(context, it) }
        onProgress("Learning visual pacing from your reference media and analysing the soundtrack…")
        val referenceSamples = styleReferences.take(12).flatMap { uri ->
            runCatching { analyzeStyleReference(context, uri) }.getOrDefault(emptyList())
        }
        val audioBeats = audio.firstOrNull()?.let {
            runCatching { analyzeAudioBeats(context, it, duration) }.getOrDefault(emptyList())
        }.orEmpty()
        val usable = min(items.length(), stagedVideo.size)
        val learnedShotLength = referenceSamples.map { kotlin.math.exp(it[0].toDouble()).toFloat() }
            .average().takeIf { it.isFinite() }?.toFloat()?.coerceIn(0.45f, 8f)
        val styleDefaultShot = when (style.lowercase(Locale.ROOT)) {
            "velocity" -> 1.55f
            "mograph" -> 2.1f
            else -> 2.8f
        }
        val targetShotLength = learnedShotLength?.let { styleDefaultShot * 0.35f + it * 0.65f }
            ?.coerceIn(0.45f, 8f) ?: styleDefaultShot
        onProgress("Detecting scene changes and selecting distinct source moments…")
        val uniqueShots = (0 until usable).flatMap { sourceIndex ->
            detectShots(context, footage[sourceIndex], sourceDurations[sourceIndex], targetShotLength)
                .map { it.copy(sourceIndex = sourceIndex) }
        }
        check(uniqueShots.isNotEmpty()) { "No usable scene segments were found in the selected footage." }
        val sourceTraining = uniqueShots.map { shot ->
            floatArrayOf(kotlin.math.ln(shot.duration.coerceAtLeast(0.1f)), shot.brightness, shot.cutStrength, shot.energy, shot.saturation)
        }
        val styleModel = AiStyleModel.trainOrLoad(context, referenceSamples + sourceTraining)
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
                "velocity" -> pool.maxByOrNull {
                    it.energy * 0.55f + styleModel.similarity(it.duration, it.brightness, it.cutStrength, it.energy, it.saturation) * 0.45f
                } ?: pool.first()
                "cinematic" -> pool.maxByOrNull {
                    it.duration * 0.2f + styleModel.similarity(it.duration, it.brightness, it.cutStrength, it.energy, it.saturation)
                } ?: pool.first()
                else -> pool.maxByOrNull {
                    it.energy * 0.4f + it.saturation * 0.1f +
                        styleModel.similarity(it.duration, it.energy, it.energy, it.energy, it.saturation) * 0.5f
                } ?: pool.first()
            }
            remainingShots.remove(chosen)
            val itemId = items.optLong(chosen.sourceIndex, -1L)
            check(itemId > 0L) { "EffectCraft returned an invalid source footage ID." }
            var clipDuration = min(chosen.duration, duration - cursor).coerceAtLeast(0.2f)
            if ("Beat sync" in priorities && audioBeats.isNotEmpty()) {
                val beat = audioBeats.firstOrNull { it > cursor + 0.35f && it <= cursor + clipDuration + 0.45f }
                if (beat != null) clipDuration = (beat - cursor).coerceAtLeast(0.2f)
            }
            val transition = transitionFor(shotIndex, chosen.energy, style)
            val overlap = if (shotIndex > 0) transition.second else 0f
            val start = cursor
            val out = min(duration, cursor + clipDuration)
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
            if (shotIndex > 0 && overlap > 0f) applyNativeTransition(layerId, transition.first)
            previousSource = chosen.sourceIndex
            previousStart = chosen.sourceStart
            cursor += clipDuration
            shotIndex++
        }
        check(videoLayers.isNotEmpty()) { "EffectCraft could not create any video layers." }
        if (stagedAudio != null) {
            onProgress("Adding the soundtrack as a native EffectCraft audio layer…")
            val audioImport = command("file.import", JSONObject().put("paths", JSONArray().put(stagedAudio.absolutePath)))
            val audioErrors = audioImport.optJSONArray("errors")
            check(audioErrors == null || audioErrors.length() == 0) {
                "EffectCraft couldn't import the soundtrack: ${audioErrors?.toString()}"
            }
            val audioItems = audioImport.optJSONArray("items")
            val audioItem = audioItems?.optLong(0, -1L) ?: -1L
            check(audioItem > 0L) { "EffectCraft didn't return an imported soundtrack item." }
            command(
                "layer.addItem",
                JSONObject().put("comp", comp).put("item", audioItem).put("time", 0.0).put("index", 1)
            )
        }
        val finalDuration = duration
        addNativeMotionGraphics(comp, width, height, duration, style, prompt, frameRate)
        addOptionalNativeAdjustment(comp, videoLayers.firstOrNull())
        onProgress("Reviewing rendered EffectCraft frames for missing or empty shots…")
        reviewComposition(comp, duration, frameRate, onProgress)
        onProgress("Saving the native EffectCraft project and its media references…")
        val projectFile = File(projectDirectory, "Kinetic AI Project.ecproj")
        command("file.saveAs", JSONObject().put("path", projectFile.absolutePath))
        check(projectFile.isFile && projectFile.length() > 0L) {
            "EffectCraft couldn't persist the native project file."
        }
        onProgress("EffectCraft project authored and saved.")
        return Composition(comp, finalDuration, audio.firstOrNull(), staged, projectFile, audioBeats.size, styleModel.trainingSamples)
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
        val brightnessSamples = ArrayList<Pair<Float, Float>>()
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
                    brightnessSamples.add(time to luma.average().toFloat())
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
            val sceneBrightness = brightnessSamples.filter { it.first >= sceneStart && it.first < sceneEnd }
                .map { it.second }.average().takeIf { it.isFinite() }?.toFloat()?.coerceIn(0f, 1f) ?: 0.5f
            val sceneSaturation = saturationSamples.filter { it.first >= sceneStart && it.first < sceneEnd }
                .map { it.second }.average().takeIf { it.isFinite() }?.toFloat()?.coerceIn(0f, 1f) ?: 0.5f
            val sceneCutStrength = (boundaries.count { it > sceneStart && it < sceneEnd }.toFloat() / (sceneEnd - sceneStart).coerceAtLeast(0.35f)).coerceIn(0f, 1f)
            var segmentStart = sceneStart
            while (segmentStart < sceneEnd - 0.25f) {
                val segmentDuration = min(targetLength, sceneEnd - segmentStart)
                if (segmentDuration >= 0.35f) {
                    result.add(SourceShot(0, segmentStart, segmentDuration, sceneEnergy, sceneSaturation, sceneBrightness, sceneCutStrength))
                }
                segmentStart += targetLength
            }
        }
        return result.ifEmpty { listOf(SourceShot(0, 0f, sourceDuration.coerceAtLeast(0.35f), 0f, 0.5f, 0.5f, 0f)) }
    }


    private fun analyzeStyleReference(context: Context, uri: Uri): List<FloatArray> {
        val retriever = MediaMetadataRetriever()
        val duration = runCatching {
            retriever.setDataSource(context, uri)
            (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000f
        }.getOrDefault(0f)
        if (duration > 0f) {
            return try {
                val brightness = ArrayList<Float>()
                val saturation = ArrayList<Float>()
                val changes = ArrayList<Float>()
                var previous: FloatArray? = null
                for (index in 0 until 8) {
                    val time = duration * index / 8
                    val frame = runCatching {
                        retriever.getFrameAtTime((time * 1_000_000L).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    }.getOrNull() ?: continue
                    val tiny = Bitmap.createScaledBitmap(frame, 16, 9, true)
                    val pixels = IntArray(16 * 9)
                    tiny.getPixels(pixels, 0, 16, 0, 0, 16, 9)
                    if (tiny !== frame) tiny.recycle()
                    frame.recycle()
                    val luma = FloatArray(pixels.size)
                    var sat = 0.0
                    for (p in pixels.indices) {
                        val c = pixels[p]
                        val red = (c shr 16) and 255
                        val green = (c shr 8) and 255
                        val blue = c and 255
                        val hi = max(red, max(green, blue))
                        val lo = min(red, min(green, blue))
                        luma[p] = (red * 0.2126f + green * 0.7152f + blue * 0.0722f) / 255f
                        sat += if (hi == 0) 0.0 else (hi - lo).toDouble() / hi
                    }
                    brightness.add(luma.average().toFloat())
                    saturation.add((sat / pixels.size).toFloat())
                    previous?.let { prior ->
                        changes.add(luma.indices.map { abs(luma[it] - prior[it]) }.average().toFloat())
                    }
                    previous = luma
                }
                val avgBrightness = brightness.average().takeIf { it.isFinite() }?.toFloat() ?: 0.5f
                val avgSaturation = saturation.average().takeIf { it.isFinite() }?.toFloat() ?: 0.5f
                val avgMotion = changes.average().takeIf { it.isFinite() }?.toFloat() ?: 0f
                val cutStrength = changes.count { it > 0.22f }.toFloat() / changes.size.coerceAtLeast(1)
                val avgShot = (duration / (cutStrength * 8f + 1f)).coerceIn(0.35f, 12f)
                listOf(floatArrayOf(kotlin.math.ln(avgShot), avgBrightness, cutStrength, avgMotion, avgSaturation))
            } finally {
                runCatching { retriever.release() }
            }
        }
        runCatching { retriever.release() }
        val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return emptyList()
        return try {
            val tiny = Bitmap.createScaledBitmap(bitmap, 16, 9, true)
            val pixels = IntArray(16 * 9)
            tiny.getPixels(pixels, 0, 16, 0, 0, 16, 9)
            if (tiny !== bitmap) tiny.recycle()
            var brightness = 0.0
            var saturation = 0.0
            pixels.forEach { color ->
                val red = (color shr 16) and 255
                val green = (color shr 8) and 255
                val blue = color and 255
                brightness += (red * 0.2126 + green * 0.7152 + blue * 0.0722) / 255.0
                val hi = max(red, max(green, blue))
                val lo = min(red, min(green, blue))
                saturation += if (hi == 0) 0.0 else (hi - lo).toDouble() / hi
            }
            listOf(floatArrayOf(
                kotlin.math.ln(2f),
                (brightness / pixels.size).toFloat(),
                0.1f,
                0f,
                (saturation / pixels.size).toFloat()
            ))
        } finally {
            bitmap.recycle()
        }
    }


    private fun analyzeAudioBeats(context: Context, uri: Uri, maxDuration: Float): List<Float> {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return emptyList()
            extractor.selectTrack(track)
            val sourceFormat = extractor.getTrackFormat(track)
            val mime = sourceFormat.getString(MediaFormat.KEY_MIME) ?: return emptyList()
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(sourceFormat, null, null, 0)
            decoder.start()
            var sampleRate = sourceFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = sourceFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var inputEnded = false
            var outputEnded = false
            var windowSamples = (sampleRate / 20).coerceAtLeast(128) * channels
            var sumSquares = 0.0
            var sampleCount = 0
            val envelope = ArrayList<Float>()
            val info = MediaCodec.BufferInfo()
            while (!outputEnded) {
                if (!inputEnded) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        val input = decoder.getInputBuffer(inputIndex)
                            ?: throw IllegalStateException("Audio decoder input buffer unavailable.")
                        input.clear()
                        val size = if (extractor.sampleTime >= 0L && extractor.sampleTime < maxDuration * 1_000_000L) {
                            extractor.readSampleData(input, 0)
                        } else -1
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outputIndex = decoder.dequeueOutputBuffer(info, 10_000L)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = decoder.outputFormat
                        sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        encoding = if (Build.VERSION.SDK_INT >= 24 && out.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            out.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else AudioFormat.ENCODING_PCM_16BIT
                        windowSamples = (sampleRate / 20).coerceAtLeast(128) * channels
                    }
                    else -> if (outputIndex >= 0) {
                        val output = decoder.getOutputBuffer(outputIndex)
                        if (output != null && info.size > 0) {
                            val pcm = output.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            pcm.position(info.offset)
                            pcm.limit(info.offset + info.size)
                            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                while (pcm.remaining() >= 4) {
                                    val value = pcm.float.toDouble()
                                    sumSquares += value * value
                                    sampleCount++
                                    if (sampleCount >= windowSamples) {
                                        envelope.add(sqrt(sumSquares / sampleCount).toFloat())
                                        sumSquares = 0.0
                                        sampleCount = 0
                                    }
                                }
                            } else {
                                while (pcm.remaining() >= 2) {
                                    val value = pcm.short.toDouble() / 32768.0
                                    sumSquares += value * value
                                    sampleCount++
                                    if (sampleCount >= windowSamples) {
                                        envelope.add(sqrt(sumSquares / sampleCount).toFloat())
                                        sumSquares = 0.0
                                        sampleCount = 0
                                    }
                                }
                            }
                        }
                        outputEnded = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            if (sampleCount > 0) envelope.add(sqrt(sumSquares / sampleCount).toFloat())
            if (envelope.size < 4) return emptyList()
            val mean = envelope.average().toFloat()
            val variance = envelope.map { (it - mean) * (it - mean) }.average().toFloat()
            val threshold = mean + sqrt(variance) * 0.35f
            val beats = ArrayList<Float>()
            var last = -1f
            for (index in 1 until envelope.lastIndex) {
                val value = envelope[index]
                if (value >= threshold && value >= envelope[index - 1] && value > envelope[index + 1]) {
                    val time = index * 0.05f
                    if (time - last >= 0.24f) {
                        beats.add(time)
                        last = time
                    }
                }
            }
            return beats
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun transitionFor(index: Int, energy: Float, style: String): Pair<String, Float> {
        // IDs and durations come from EffectCraft's pinned built-in transitions.json.
        // Hard cuts are intentional for the first shot and selected high-energy accents.
        if (index == 0 || (style.equals("Velocity", true) && energy > 0.32f && index % 4 == 0)) {
            return "hardCut" to 0.0f
        }
        val options = listOf("fade" to 0.32f, "push" to 0.42f, "zoom" to 0.36f, "wipe" to 0.40f, "hardCut" to 0.0f)
        val offset = ((index - 1) + (energy * 10f).toInt().coerceIn(0, 6)) % options.size
        return options[offset]
    }

    private fun applyNativeTransition(layer: Long, preset: String) {
        when (preset) {
            "fade" -> { key(layer, "transform/opacity", 0.0, 0.0); key(layer, "transform/opacity", 0.32, 100.0) }
            "push" -> { key(layer, "transform/position", 0.0, JSONArray().put(1320).put(360).put(0)); key(layer, "transform/position", 0.42, JSONArray().put(640).put(360).put(0)) }
            "zoom" -> { key(layer, "transform/scale", 0.0, JSONArray().put(128).put(128).put(100)); key(layer, "transform/scale", 0.36, JSONArray().put(100).put(100).put(100)) }
            "wipe" -> { key(layer, "transform/position", 0.0, JSONArray().put(570).put(360).put(0)); key(layer, "transform/position", 0.40, JSONArray().put(640).put(360).put(0)); key(layer, "transform/opacity", 0.0, 15.0); key(layer, "transform/opacity", 0.40, 100.0) }
            else -> Unit
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
        key(textId, "transform/opacity", 0.0, 0.0)
        key(textId, "transform/opacity", 0.22, 100.0)
        key(textId, "transform/opacity", minOf(2.8f, duration - 0.3f).coerceAtLeast(0.5f).toDouble(), 100.0)
        key(textId, "transform/opacity", minOf(3.1f, duration).toDouble(), 0.0)
    }

    private fun addOptionalNativeAdjustment(comp: Long, targetLayer: Long?) {
        if (targetLayer == null) return
        val response = JSONObject(EffectCraftAiBridge.execute("effect.list", "{}").getOrThrow())
        check(response.optBoolean("ok")) { "EffectCraft could not inspect its native effects." }
        val effects = response.optJSONArray("result") ?: return
        val glow = (0 until effects.length()).mapNotNull { effects.optJSONObject(it) }.firstOrNull { it.optString("id") == "ec.stylize.glow" } ?: return
        val adjustment = command("layer.newAdjustment", JSONObject().put("comp", comp).put("name", "AI • Native Glow Adjustment")).optLong("layer", -1L)
        if (adjustment <= 0L) return
        command("effect.apply", JSONObject().put("effect", glow.optString("id")).put("layers", JSONArray().put(adjustment)))
    }

    private fun reviewComposition(comp: Long, duration: Float, fps: Int, onProgress: (String) -> Unit) {
        val sampleCount = minOf(18, maxOf(6, (duration / 2f).toInt()))
        var emptyFrames = 0
        var validFrames = 0
        for (index in 0 until sampleCount) {
            val time = ((index + 0.5f) * duration / sampleCount).coerceIn(0f, duration - 1f / fps)
            val bytes = EffectCraftAiBridge.renderFrame(comp, time, 160).getOrThrow()
            check(bytes.size >= 8) { "EffectCraft review returned an incomplete frame." }
            val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val width = buffer.int
            val height = buffer.int
            check(width > 0 && height > 0 && bytes.size == 8 + width * height * 4) { "EffectCraft review returned invalid frame dimensions." }
            var alpha = 0L
            var offset = 11
            var pixels = 0
            while (offset < bytes.size) { alpha += bytes[offset].toInt() and 255; pixels++; offset += 4 }
            if (alpha / pixels.coerceAtLeast(1) < 8L) emptyFrames++
            validFrames++
        }
        check(emptyFrames < sampleCount / 2) { "AI review found too many empty frames; export stopped to avoid a broken video." }
        onProgress("AI review passed: $validFrames rendered samples checked; $emptyFrames empty frames.")
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

    private fun stage(context: Context, uri: Uri, index: Int, projectDirectory: File): File {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "clip_${index + 1}.mp4"
        val extension = name.substringAfterLast('.', "mp4").lowercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() }.take(8).ifBlank { "mp4" }
        val mediaDirectory = File(projectDirectory, "media").apply {
            check(mkdirs() || isDirectory) { "Kinetic couldn't create the project's media folder." }
        }
        val destination = File(mediaDirectory, "source-${index + 1}.$extension")
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
