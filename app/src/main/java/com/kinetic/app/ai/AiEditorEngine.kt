package com.kinetic.app.ai

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.kinetic.app.model.*
import com.kinetic.app.engine.EffectCraftCatalog
import com.kinetic.app.engine.EffectCraftEffectInfo
import com.kinetic.app.engine.EffectCraftParameterInfo
import org.json.JSONObject
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * On-device AI-assisted editing pipeline.
 *
 * It analyses media locally, trains a compact reference-style clustering model,
 * scores shots using motion and visual descriptors, and emits editable Kinetic
 * layers. Semantic anime recognition and neural optical-flow interpolation are
 * separate capabilities and are not represented as implemented here.
 */
object AiEditorEngine {
    data class Request(
        val footage: List<Uri>,
        val audio: List<Uri>,
        val references: List<Uri>,
        val prompt: String,
        val style: String,
        val durationSeconds: Float,
        val priorities: Set<String>,
        val styleNotes: String
    )

    data class Scene(
        val uri: Uri,
        val sourceName: String,
        val start: Float,
        val end: Float,
        val visualEnergy: Float,
        val cutStrength: Float,
        val motionEnergy: Float = 0f,
        val stylizedVisualScore: Float = 0f
    ) {
        val duration: Float get() = (end - start).coerceAtLeast(0.1f)
    }

    data class StyleProfile(
        val averageShotSeconds: Float,
        val cutRate: Float,
        val averageBrightness: Float,
        val averageSaturation: Float,
        val referenceCount: Int,
        val notes: String
    )

    data class GeneratedProject(
        val name: String,
        val duration: Float,
        val layers: List<Layer>,
        val sceneCount: Int,
        val beatCount: Int,
        val profile: StyleProfile,
        val report: String
    )

    private data class VideoAnalysis(
        val uri: Uri,
        val name: String,
        val duration: Float,
        val width: Int,
        val height: Int,
        val scenes: List<Scene>
    )

    private data class AudioAnalysis(
        val duration: Float,
        val beats: List<Float>,
        val energy: FloatArray
    )

    suspend fun generate(context: Context, request: Request, fps: Int, resolution: Int): GeneratedProject {
        require(request.footage.isNotEmpty()) { "Add at least one video before generating." }
        val videos = request.footage.map { analyzeVideo(context, it) }
        require(videos.any { it.scenes.isNotEmpty() }) { "Kinetic couldn't decode usable frames from the selected videos." }

        val audioUri = request.audio.firstOrNull()
        val audio = audioUri?.let { analyzeAudio(context, it) }
        val audioDuration = audioUri?.let { probeAudioDuration(context, it) } ?: audio?.duration
        val referenceVideos = request.references.filter { isVideo(context, it) }
            .take(6).mapNotNull { runCatching { analyzeVideo(context, it) }.getOrNull() }
        val imageFeatures = request.references.filterNot { isVideo(context, it) }
            .take(12).mapNotNull { analyzeImageFeatures(context, it) }
        val profile = buildStyleProfile(context, referenceVideos, imageFeatures, request.styleNotes)
        persistStyleProfile(context, profile)
        val styleTrainingSamples = referenceVideos.flatMap { it.scenes }.map { scene ->
            floatArrayOf(kotlin.math.ln(scene.duration.coerceAtLeast(0.1f)), scene.visualEnergy, scene.cutStrength, scene.motionEnergy, scene.stylizedVisualScore)
        } + imageFeatures.map { features ->
            floatArrayOf(kotlin.math.ln(profile.averageShotSeconds.coerceAtLeast(0.1f)), features[0], features[1], 0f, features[1])
        }
        val styleModel = AiStyleModel.trainOrLoad(context, styleTrainingSamples)

        val totalDuration = request.durationSeconds.coerceIn(10f, 120f)
        val plan = planEdit(videos.flatMap { it.scenes }, audio?.beats.orEmpty(), profile, request, totalDuration, styleModel)
        val colorGrade = if ("Color grading" in request.priorities) {
            runCatching {
                EffectCraftCatalog.load().getOrNull()?.firstOrNull { effect ->
                    effect.category == "COLOR & LIGHT" && effect.gpu &&
                        effect.params.any { parameter ->
                            val id = parameter.id.lowercase()
                            "saturation" in id || "vibrance" in id || "contrast" in id || "exposure" in id
                        }
                } ?: EffectCraftCatalog.load().getOrNull()?.firstOrNull {
                    it.category == "COLOR & LIGHT" && it.gpu
                }
            }.getOrNull()
        } else null
        val layers = executePlan(plan, audio, audioDuration, request, fps.coerceIn(12, 60), videos, resolution, colorGrade)
        val report = buildString {
            append("Analysed ").append(videos.size).append(" source video(s) and ")
            append(videos.sumOf { it.scenes.size }).append(" candidate scenes")
            if (audio != null) append("; detected ").append(audio.beats.size).append(" music accents")
            if (referenceVideos.isNotEmpty()) append("; extracted pacing from ").append(referenceVideos.size).append(" reference video(s)")
            if (styleModel.trainingSamples > 0) append("; trained the on-device style clustering model from ").append(styleModel.trainingSamples).append(" reference shots")
            append(". Built ").append(plan.size).append(" selected shots with editable motion and title layers.")
            append(" Scene selection is heuristic, not anime-character or story recognition.")
        }
        return GeneratedProject(
            name = "AI Edit • ${request.style}",
            duration = max(totalDuration, layers.maxOfOrNull { it.end } ?: totalDuration),
            layers = layers,
            sceneCount = plan.size,
            beatCount = audio?.beats?.size ?: 0,
            profile = profile,
            report = report
        )
    }

    private fun analyzeVideo(context: Context, uri: Uri): VideoAnalysis {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val duration = (durationMs / 1000f).coerceAtLeast(0.1f)
            var width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) { val swap = width; width = height; height = swap }
            val name = queryName(context, uri)
            val sampleStep = max(0.45f, duration / 150f)
            data class FrameSample(val time: Float, val energy: Float, val motion: Float, val style: Float)
            val frames = ArrayList<FrameSample>()
            var previous: FloatArray? = null
            var t = 0f
            while (t < duration && frames.size < 180) {
                val bitmap = runCatching {
                    retriever.getFrameAtTime((t * 1_000_000L).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }.getOrNull()
                if (bitmap != null) {
                    val features = frameFeatures(bitmap)
                    val difference = previous?.let { featureDistance(it, features) } ?: 0f
                    val energy = (features[1] * 0.34f + features[2] * 0.28f +
                        features[3] * 0.38f).coerceIn(0f, 1f)
                    val stylized = (features[3] * 0.48f + features[2] * 0.32f +
                        features[4] * 0.20f).coerceIn(0f, 1f)
                    frames.add(FrameSample(t, energy, difference, stylized))
                    previous = features
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
                t += sampleStep
            }
            if (frames.isEmpty()) {
                return VideoAnalysis(uri, name, duration, width, height,
                    listOf(Scene(uri, name, 0f, duration, 0.35f, 0f)))
            }
            val cuts = ArrayList<Pair<Float, Float>>()
            var lastCut = 0f
            frames.forEachIndexed { index, item ->
                if (index > 0) {
                    val prior = frames[index - 1]
                    val strength = (item.motion * 0.72f + abs(item.energy - prior.energy) * 0.28f)
                        .coerceIn(0f, 1f)
                    if (strength > 0.20f && item.time - lastCut >= 0.8f) {
                        cuts.add(item.time to strength)
                        lastCut = item.time
                    }
                }
            }
            val boundaries = (listOf(0f) + cuts.map { it.first } + duration).distinct().sorted()
            val scenes = boundaries.zipWithNext().mapNotNull { (start, end) ->
                if (end - start < 0.25f) return@mapNotNull null
                val samples = frames.filter { it.time >= start && it.time < end }
                val energy = samples.map { it.energy }.average().takeIf { it.isFinite() }?.toFloat() ?: 0.35f
                val motion = samples.map { it.motion }.average().takeIf { it.isFinite() }?.toFloat() ?: 0f
                val style = samples.map { it.style }.average().takeIf { it.isFinite() }?.toFloat() ?: 0f
                val cutStrength = cuts.firstOrNull { abs(it.first - start) < sampleStep * 1.5f }?.second ?: 0f
                Scene(uri, name, start, end, energy, cutStrength, motion, style)
            }
            return VideoAnalysis(uri, name, duration, width, height, scenes)
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Returns mean luminance, luminance variance and saturation from a tiny sample. */
    private fun frameFeatures(bitmap: Bitmap): FloatArray {
        val w = 20
        val h = 12
        val pixels = IntArray(w * h)
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled !== bitmap) scaled.recycle()
        var luminance = 0.0
        var luminanceSquared = 0.0
        var saturation = 0.0
        var colorfulness = 0.0
        val gray = FloatArray(pixels.size)
        pixels.forEachIndexed { index, pixel ->
            val r = ((pixel shr 16) and 255) / 255.0
            val g = ((pixel shr 8) and 255) / 255.0
            val b = (pixel and 255) / 255.0
            val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
            val hi = max(r, max(g, b))
            val lo = min(r, min(g, b))
            gray[index] = y.toFloat()
            colorfulness += hi - lo
            luminance += y
            luminanceSquared += y * y
            saturation += if (hi <= 0.0001) 0.0 else (hi - lo) / hi
        }
        val count = pixels.size.toDouble()
        val mean = luminance / count
        val variance = (luminanceSquared / count - mean * mean).coerceAtLeast(0.0)
        var edgeSum = 0.0
        var edgeCount = 0
        for (y in 0 until h) for (x in 0 until w) {
            val index = y * w + x
            if (x + 1 < w) {
                edgeSum += abs(gray[index] - gray[index + 1])
                edgeCount++
            }
            if (y + 1 < h) {
                edgeSum += abs(gray[index] - gray[index + w])
                edgeCount++
            }
        }
        val edgeDensity = if (edgeCount == 0) 0f else (edgeSum / edgeCount * 3.2).toFloat().coerceIn(0f, 1f)
        return floatArrayOf(
            mean.toFloat(),
            sqrt(variance).toFloat().coerceIn(0f, 1f),
            (saturation / count).toFloat(),
            edgeDensity,
            (colorfulness / count).toFloat()
        )
    }

    private fun featureDistance(a: FloatArray, b: FloatArray): Float =
        (abs(a[0] - b[0]) * 0.52f + abs(a[1] - b[1]) * 0.66f +
            abs(a[2] - b[2]) * 0.42f + abs(a[3] - b[3]) * 0.78f +
            abs(a[4] - b[4]) * 0.42f).coerceIn(0f, 1f)

    private fun probeAudioDuration(context: Context, uri: Uri): Float? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            (0 until extractor.trackCount).mapNotNull { index ->
                extractor.getTrackFormat(index).let { format ->
                    if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        format.getLong(MediaFormat.KEY_DURATION) / 1_000_000f
                    } else null
                }
            }.maxOrNull()?.takeIf { it.isFinite() && it > 0f }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun analyzeAudio(context: Context, uri: Uri): AudioAnalysis? {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                if (candidate.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    format = candidate
                    break
                }
            }
            if (track < 0 || format == null) return null
            extractor.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()
            val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 2) else 2
            val windowSamples = (sampleRate * 0.08f).toInt().coerceAtLeast(256)
            val energies = ArrayList<Float>()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var samplesInWindow = 0
            var sumSquares = 0.0
            var processed = 0
            while (!outputDone && processed < sampleRate * 120 * channels) {
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = decoder.getInputBuffer(inputIndex) ?: continue
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)
                if (outputIndex >= 0) {
                    val output = decoder.getOutputBuffer(outputIndex)
                    if (output != null && info.size > 0) {
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        val pcm = output.slice().order(ByteOrder.LITTLE_ENDIAN)
                        while (pcm.remaining() >= 2 && processed < sampleRate * 120 * channels) {
                            val sample = pcm.getShort().toInt() / 32768.0
                            sumSquares += sample * sample
                            samplesInWindow++
                            processed++
                            if (samplesInWindow >= windowSamples * channels) {
                                energies.add(sqrt(sumSquares / samplesInWindow).toFloat())
                                samplesInWindow = 0
                                sumSquares = 0.0
                            }
                        }
                    }
                    decoder.releaseOutputBuffer(outputIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            if (samplesInWindow > 0) energies.add(sqrt(sumSquares / samplesInWindow).toFloat())
            val mean = energies.average().takeIf { it.isFinite() }?.toFloat() ?: 0f
            val beats = ArrayList<Float>()
            for (i in 2 until (energies.size - 2)) {
                val value = energies[i]
                val before = (energies[i - 1] + energies[i - 2]) / 2f
                val after = (energies[i + 1] + energies[i + 2]) / 2f
                val time = i * 0.08f
                if (value > max(mean * 1.28f, 0.018f) && value > before * 1.22f && value >= after &&
                    (beats.isEmpty() || time - beats.last() >= 0.24f)
                ) beats.add(time)
            }
            val tempoGrid = detectBeatGrid(energies)
            val selectedBeats = if (tempoGrid.size >= 3) tempoGrid else beats
            return AudioAnalysis(energies.size * 0.08f, selectedBeats, energies.toFloatArray())
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * Estimates a stable beat grid from the decoded RMS envelope using
     * onset autocorrelation. Times are returned in seconds (80 ms analysis hops).
     */
    private fun detectBeatGrid(energy: List<Float>): List<Float> {
        if (energy.size < 32) return emptyList()
        val onset = FloatArray(energy.size) { index ->
            if (index == 0) 0f else (energy[index] - energy[index - 1]).coerceAtLeast(0f)
        }
        val minLag = 5 // 150 BPM
        val maxLag = minOf(16, energy.size / 3) // about 47 BPM
        if (maxLag <= minLag) return emptyList()
        var bestLag = -1
        var bestScore = 0.0
        for (lag in minLag..maxLag) {
            var correlation = 0.0
            var energyA = 0.0
            var energyB = 0.0
            for (index in lag until onset.size) {
                val a = onset[index].toDouble()
                val b = onset[index - lag].toDouble()
                correlation += a * b
                energyA += a * a
                energyB += b * b
            }
            val score = correlation / kotlin.math.sqrt((energyA * energyB).coerceAtLeast(1e-12))
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag < 0 || bestScore < 0.12) return emptyList()
        val phase = (0 until bestLag).maxByOrNull { offset ->
            var score = 0.0
            var index = offset
            while (index < onset.size) {
                score += onset[index]
                index += bestLag
            }
            score
        } ?: 0
        val result = ArrayList<Float>()
        var grid = phase
        while (grid < onset.size) {
            val from = max(0, grid - 2)
            val to = min(onset.lastIndex, grid + 2)
            val peak = (from..to).maxByOrNull { onset[it] } ?: grid
            val time = peak * 0.08f
            if (result.isEmpty() || time - result.last() >= 0.28f) result.add(time)
            grid += bestLag
        }
        return result
    }

    private fun analyzeImageFeatures(context: Context, uri: Uri): FloatArray? {
        return runCatching {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            if (options.outWidth <= 0 || options.outHeight <= 0) return null
            options.inSampleSize = max(1, max(options.outWidth, options.outHeight) / 512)
            options.inJustDecodeBounds = false
            val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                ?: return null
            val features = frameFeatures(bitmap)
            bitmap.recycle()
            floatArrayOf(features[0], features[2])
        }.getOrNull()
    }

    private fun buildStyleProfile(context: Context, references: List<VideoAnalysis>, images: List<FloatArray>, notes: String): StyleProfile {
        val scenes = references.flatMap { it.scenes }
        val previous = loadStyleProfile(context)
        val avgShot = scenes.map { it.duration }.average().takeIf { it.isFinite() }?.toFloat()
            ?: previous?.averageShotSeconds ?: 1.8f
        val total = references.sumOf { it.duration.toDouble() }.toFloat().coerceAtLeast(0.1f)
        val cutRate = if (scenes.isNotEmpty()) scenes.size / total else previous?.cutRate ?: 0.5f
        val brightnessValues = images.map { it[0] } + scenes.map { it.visualEnergy }
        val brightness = brightnessValues.average().takeIf { it.isFinite() }?.toFloat()
            ?: previous?.averageBrightness ?: 0.5f
        val saturation = images.map { it[1] }.average().takeIf { it.isFinite() }?.toFloat()
            ?: previous?.averageSaturation ?: 0.5f
        return StyleProfile(avgShot.coerceIn(0.35f, 12f), cutRate.coerceIn(0.05f, 4f),
            brightness.coerceIn(0f, 1f), saturation.coerceIn(0f, 1f),
            references.size + images.size, notes.take(500).ifBlank { previous?.notes.orEmpty() })
    }

    private fun planEdit(
        scenes: List<Scene>,
        beats: List<Float>,
        profile: StyleProfile,
        request: Request,
        target: Float,
        styleModel: AiStyleModel
    ): List<Scene> {
        val targetShot = when (request.style) {
            "Velocity" -> min(profile.averageShotSeconds, 1.15f)
            "Mograph" -> max(profile.averageShotSeconds, 1.6f)
            else -> profile.averageShotSeconds.coerceIn(1.0f, 2.6f)
        }
        val sorted = scenes.sortedByDescending { scene ->
            val learnedStyleMatch = styleModel.similarity(scene.duration, scene.visualEnergy, scene.cutStrength, scene.motionEnergy, scene.stylizedVisualScore)
            learnedStyleMatch * 0.46f + scene.visualEnergy * 0.16f + scene.cutStrength * 0.12f +
                scene.motionEnergy * 0.13f + scene.stylizedVisualScore * 0.13f +
                if ("Impact shakes" in request.priorities) scene.cutStrength * 0.08f else 0f
        }
        val selected = ArrayList<Scene>()
        var cursor = 0f
        var index = 0
        while (cursor < target && selected.size < 160) {
            val candidate = sorted.getOrNull(index % sorted.size) ?: break
            val beatBoundary = if ("Beat sync" in request.priorities) {
                beats.firstOrNull { it > cursor + 0.45f && it <= cursor + targetShot * 1.5f }
            } else null
            val beatLength = beatBoundary?.minus(cursor)
            val wanted = min(beatLength ?: targetShot, target - cursor).coerceAtLeast(0.35f)
            val sourceStart = candidate.start + (candidate.duration - min(candidate.duration, wanted)).coerceAtLeast(0f) * 0.5f
            val sourceEnd = min(candidate.end, sourceStart + wanted)
            val snapped = beats.minByOrNull { abs(it - cursor) }?.takeIf { abs(it - cursor) < 0.16f } ?: cursor
            if (selected.isNotEmpty() && snapped > cursor) cursor = snapped
            selected.add(candidate.copy(start = sourceStart, end = sourceEnd))
            cursor += (sourceEnd - sourceStart).coerceAtLeast(0.35f)
            index++
        }
        return selected
    }

    private fun executePlan(
        plan: List<Scene>,
        audio: AudioAnalysis?,
        audioDuration: Float?,
        request: Request,
        fps: Int,
        videos: List<VideoAnalysis>,
        resolution: Int,
        colorGrade: EffectCraftEffectInfo?
    ): List<Layer> {
        val layers = ArrayList<Layer>()
        val compDuration = request.durationSeconds.coerceIn(10f, 120f)
        val baseW = listOf(1280f, 1920f, 2560f, 3840f).getOrElse(resolution.coerceIn(0, 3)) { 1920f }
        val baseH = baseW * 9f / 16f
        var cursor = 0f
        val beatTimes = audio?.beats.orEmpty()
        plan.forEachIndexed { index, scene ->
            if (cursor >= compDuration) return@forEachIndexed
            val duration = min(scene.duration, compDuration - cursor).coerceAtLeast(0.25f)
            val info = videos.firstOrNull { it.uri == scene.uri }
            val sourceW = info?.width?.takeIf { it > 0 }?.toFloat() ?: baseW
            val sourceH = info?.height?.takeIf { it > 0 }?.toFloat() ?: baseH
            val fitScale = min(COMP_W / sourceW, COMP_H / sourceH)
            val w = sourceW * fitScale
            val h = sourceH * fitScale
            val gradeStack = colorGrade?.let { effect ->
                listOf(EffectInstance(
                    name = effect.name,
                    effectCraftId = effect.id,
                    effectCraftParameters = effect.params.associate { parameter ->
                        parameter.id to gradedDefault(parameter)
                    }
                ))
            }.orEmpty()
            var video = newLayer(LayerType.VIDEO, scene.sourceName, cursor, duration, w, h,
                scene.uri.toString(), info?.duration ?: scene.end).copy(
                    srcOffset = scene.start,
                    effectStack = gradeStack
                )
            val fadeDuration = min(0.22f, duration * 0.22f)
            val opacityKeys = if ("Transitions" in request.priorities) {
                listOf(
                    Keyframe(cursor, 0f),
                    Keyframe(min(cursor + duration, cursor + fadeDuration), 100f),
                    Keyframe(max(cursor, cursor + duration - fadeDuration), 100f),
                    Keyframe(cursor + duration, 0f)
                ).distinctBy { (it.t * fps).toInt() }.sortedBy { it.t }
            } else emptyList()
            val speedKeys = if ("Time remapping" in request.priorities &&
                (request.style == "Velocity" || scene.motionEnergy > 0.18f)) {
                val center = cursor + duration * 0.5f
                val slowFactor = (0.92f - scene.motionEnergy * 0.52f - scene.cutStrength * 0.12f).coerceIn(0.38f, 0.82f)
                val fastFactor = (1.12f + scene.motionEnergy * 0.72f).coerceIn(1.12f, 1.85f)
                listOf(
                    Keyframe(cursor, fastFactor),
                    Keyframe(center, slowFactor, KeyType.BEZIER, 0.3f, 0f, 0.7f, 1f),
                    Keyframe(min(cursor + duration, center + duration * 0.22f), fastFactor)
                ).distinctBy { (it.t * fps).toInt() }.sortedBy { it.t }
            } else emptyList()
            val shakeTime = beatTimes.minByOrNull { abs(it - cursor) }?.takeIf { abs(it - cursor) < 0.18f }
            val rotationKeys = if ("Impact shakes" in request.priorities && shakeTime != null) {
                val local = (shakeTime - cursor).coerceIn(0f, duration)
                listOf(
                    Keyframe(cursor + local, -2.2f),
                    Keyframe(min(cursor + duration, cursor + local + 0.045f), 2.5f),
                    Keyframe(min(cursor + duration, cursor + local + 0.09f), -1.2f),
                    Keyframe(min(cursor + duration, cursor + local + 0.15f), 0f)
                ).distinctBy { (it.t * fps).toInt() }.sortedBy { it.t }
            } else emptyList()
            val impactScale = if ("Impact shakes" in request.priorities && shakeTime != null) {
                val local = (shakeTime - cursor).coerceIn(0f, duration)
                val punch = (100f + scene.cutStrength * 7f + scene.motionEnergy * 5f).coerceIn(102f, 112f)
                listOf(
                    Keyframe(max(cursor, cursor + local - 0.035f), 100f),
                    Keyframe(min(cursor + duration, cursor + local + 0.035f), punch, KeyType.BEZIER),
                    Keyframe(min(cursor + duration, cursor + local + 0.13f), 100f)
                ).distinctBy { (it.t * fps).toInt() }.sortedBy { it.t }
            } else emptyList()
            if (speedKeys.isNotEmpty() || rotationKeys.isNotEmpty() || opacityKeys.isNotEmpty() || impactScale.isNotEmpty()) {
                val props = video.props.toMutableMap()
                if (speedKeys.isNotEmpty()) props[PropId.SPEED] = Prop(1f, speedKeys)
                if (rotationKeys.isNotEmpty()) props[PropId.ROTATION] = Prop(0f, rotationKeys)
                if (opacityKeys.isNotEmpty()) props[PropId.OPACITY] = Prop(100f, opacityKeys)
                if (impactScale.isNotEmpty()) {
                    props[PropId.SCALE_X] = Prop(100f, impactScale)
                    props[PropId.SCALE_Y] = Prop(100f, impactScale)
                }
                video = video.copy(props = props)
            }
            layers.add(video)
            cursor += duration
        }
        request.audio.firstOrNull()?.let { uri ->
            val sourceDuration = audioDuration?.takeIf { it > 0f } ?: compDuration
            val layerDuration = min(compDuration, sourceDuration)
            if (layerDuration > 0f) {
                val audioLayer = newLayer(LayerType.AUDIO, "AI Soundtrack", 0f, layerDuration, 0f, 0f,
                    uri.toString(), sourceDuration)
                val fade = min(0.32f, layerDuration * 0.18f)
                layers.add(audioLayer.copy(props = audioLayer.props + (
                    PropId.VOLUME to Prop(1f, listOf(
                        Keyframe(0f, 0f),
                        Keyframe(fade, 1f),
                        Keyframe(max(fade, layerDuration - fade), 1f),
                        Keyframe(layerDuration, 0f)
                    ).distinctBy { (it.t * fps).toInt() }.sortedBy { it.t })
                )))
            }
        }
        if (plan.isNotEmpty()) {
            val title = request.prompt.trim().take(54).ifBlank { "AMV" }
            val titleStart = 0f
            val titleDuration = min(2.4f, compDuration)
            val text = newLayer(LayerType.TEXT, "AI Title", titleStart, titleDuration,
                1500f, 260f, text = title, color = 0xFFFFFFFF.toInt()).copy(
                fontSize = 138f,
                textBold = true,
                props = newLayer(LayerType.TEXT, "temp", 0f, 1f, 1500f, 260f).props + mapOf(
                    PropId.OPACITY to Prop(100f, listOf(Keyframe(0f, 0f), Keyframe(0.28f, 100f), Keyframe(titleDuration, 100f))),
                    PropId.SCALE_X to Prop(88f, listOf(Keyframe(0f, 88f), Keyframe(0.32f, 104f, KeyType.BEZIER), Keyframe(0.65f, 100f))),
                    PropId.SCALE_Y to Prop(88f, listOf(Keyframe(0f, 88f), Keyframe(0.32f, 104f, KeyType.BEZIER), Keyframe(0.65f, 100f)))
                )
            )
            layers.add(text)
        }
        return layers.sortedByDescending { if (it.type == LayerType.TEXT) 2 else if (it.type == LayerType.AUDIO) 0 else 1 }
    }

    private fun gradedDefault(parameter: EffectCraftParameterInfo): String {
        val base = parameter.defaultJson.toDoubleOrNull() ?: return parameter.defaultJson
        val id = parameter.id.lowercase()
        val adjusted = when {
            "saturation" in id || "vibrance" in id -> if (abs(base) <= 1.5) base + 0.12 else base + 12.0
            "contrast" in id -> if (abs(base) <= 2.0) base + 0.12 else base + 10.0
            "exposure" in id -> base + 0.12
            else -> base
        }
        return adjusted.coerceIn(
            parameter.min ?: Double.NEGATIVE_INFINITY,
            parameter.max ?: Double.POSITIVE_INFINITY
        ).toString()
    }

    private fun isVideo(context: Context, uri: Uri): Boolean {
        val type = context.contentResolver.getType(uri).orEmpty()
        return type.startsWith("video/")
    }

    private fun queryName(context: Context, uri: Uri): String {
        val displayName = runCatching {
            context.contentResolver.query(
                uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { cursor ->
                val column = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) {
                    cursor.getString(column)?.substringBeforeLast('.')?.takeIf(String::isNotBlank)
                } else null
            }
        }.getOrNull()
        return displayName ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Footage"
    }

    private fun persistStyleProfile(context: Context, profile: StyleProfile) {
        val json = JSONObject()
            .put("averageShotSeconds", profile.averageShotSeconds.toDouble())
            .put("cutRate", profile.cutRate.toDouble())
            .put("averageBrightness", profile.averageBrightness.toDouble())
            .put("averageSaturation", profile.averageSaturation.toDouble())
            .put("referenceCount", profile.referenceCount)
            .put("notes", profile.notes)
        context.getSharedPreferences("kinetic_ai_style", Context.MODE_PRIVATE)
            .edit().putString("style_profile", json.toString()).apply()
    }

    fun loadStyleProfile(context: Context): StyleProfile? {
        val raw = context.getSharedPreferences("kinetic_ai_style", Context.MODE_PRIVATE)
            .getString("style_profile", null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            StyleProfile(
                json.optDouble("averageShotSeconds", 1.8).toFloat(),
                json.optDouble("cutRate", 0.5).toFloat(),
                json.optDouble("averageBrightness", 0.5).toFloat(),
                json.optDouble("averageSaturation", 0.5).toFloat(),
                json.optInt("referenceCount", 0),
                json.optString("notes")
            )
        }.getOrNull()
    }
}
