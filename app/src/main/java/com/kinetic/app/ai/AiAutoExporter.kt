package com.kinetic.app.ai

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.kinetic.app.engine.EffectCraftBridge
import com.kinetic.app.engine.EffectCraftAiBridge
import com.kinetic.app.model.COMP_H
import com.kinetic.app.model.COMP_W
import com.kinetic.app.model.EffectInstance
import com.kinetic.app.model.Layer
import com.kinetic.app.model.LayerType
import com.kinetic.app.model.PropId
import com.kinetic.app.model.sourceElapsedAt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Renders an AI-generated Kinetic timeline to H.264 and publishes it to Movies/Kinetic.
 * Every visible video/image layer is processed through the same EffectCraft native
 * frame compositor used by the editor preview before the layers are composited.
 */
object AiAutoExporter {
    data class Result(
        val uri: Uri,
        val displayName: String,
        val durationSeconds: Float,
        val audioIncluded: Boolean,
        val width: Int,
        val height: Int
    )

    /**
     * Encodes frames rendered from the complete native EffectCraft composition.
     * No Kinetic Layer, property, Canvas compositor or timeline state is consulted.
     */
    suspend fun exportEffectCraft(
        context: Context,
        composition: EffectCraftAiComposer.Composition,
        fps: Int,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Result {
        val duration = composition.durationSeconds.coerceIn(0.1f, 120f)
        val frameRate = fps.coerceIn(12, 60)
        val width = 1280
        val height = 720
        val tempVideo = File(context.cacheDir, "kinetic-effectcraft-ai-${System.currentTimeMillis()}.mp4")
        var published: Uri? = null
        try {
            onProgress(0f, "Rendering the native EffectCraft composition…")
            renderEffectCraftTrack(tempVideo, composition.compId, duration, frameRate, width, height, onProgress)
            currentCoroutineContext().ensureActive()
            val displayName = "Kinetic-AI-${System.currentTimeMillis()}.mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Kinetic")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Android couldn't create the export in Movies/Kinetic.")
            published = uri
            val audioIncluded = composition.audioUri?.let {
                runCatching { muxVideoAndAudio(context, tempVideo, it, uri, duration) }.getOrDefault(false)
            } ?: false
            if (!audioIncluded) {
                resolver.openOutputStream(uri, "w")?.use { output ->
                    tempVideo.inputStream().use { input -> input.copyTo(output, 256 * 1024) }
                } ?: throw IllegalStateException("Android couldn't write the exported MP4.")
            }
            if (Build.VERSION.SDK_INT >= 29) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            }
            onProgress(1f, if (audioIncluded) "Native EffectCraft export saved with soundtrack." else "Native EffectCraft export saved.")
            return Result(uri, displayName, duration, audioIncluded, width, height)
        } catch (error: Throwable) {
            published?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            throw error
        } finally {
            runCatching { tempVideo.delete() }
            composition.sourceFiles.forEach { runCatching { it.delete() } }
        }
    }

    private suspend fun renderEffectCraftTrack(
        output: File,
        compId: Long,
        duration: Float,
        fps: Int,
        width: Int,
        height: Int,
        onProgress: (Float, String) -> Unit
    ) {
        val codecInfo = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
            info.isEncoder && info.supportedTypes.any { it.equals("video/avc", true) } &&
                runCatching {
                    info.getCapabilitiesForType("video/avc").colorFormats.any {
                        it == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar ||
                            it == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                    }
                }.getOrDefault(false)
        } ?: throw IllegalStateException("This device doesn't expose a compatible H.264 encoder.")
        val formats = codecInfo.getCapabilitiesForType("video/avc").colorFormats
        val semiPlanar = formats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
        val colorFormat = if (semiPlanar) MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            else MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
        val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, (width * height * fps * 0.12f).toInt().coerceIn(1_500_000, 8_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        val codec = MediaCodec.createByCodecName(codecInfo.name)
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val pixels = IntArray(width * height)
        val yuv = ByteArray(width * height * 3 / 2)
        val frameCount = ceil(duration * fps).toInt().coerceAtLeast(1)
        val info = MediaCodec.BufferInfo()
        var muxerStarted = false
        var videoTrack = -1
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            fun drain(endOfStream: Boolean) {
                var idle = 0
                while (true) {
                    val index = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000L else 0L)
                    when {
                        index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!endOfStream || idle++ > 100) return
                        }
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted) { "H.264 encoder changed output format more than once." }
                            videoTrack = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        index >= 0 -> {
                            val buffer = codec.getOutputBuffer(index)
                            if (buffer != null && info.size > 0 && muxerStarted) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                muxer.writeSampleData(videoTrack, buffer, info)
                            }
                            val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            codec.releaseOutputBuffer(index, false)
                            if (eos) return
                        }
                        else -> return
                    }
                }
            }
            for (frameIndex in 0 until frameCount) {
                currentCoroutineContext().ensureActive()
                val time = frameIndex.toFloat() / fps
                val encoded = EffectCraftAiBridge.renderFrame(compId, time, width).getOrThrow()
                check(encoded.size >= 8) { "EffectCraft returned an incomplete composition frame." }
                val header = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)
                val frameWidth = header.int
                val frameHeight = header.int
                check(frameWidth == width && frameHeight == height &&
                    encoded.size.toLong() == 8L + width.toLong() * height.toLong() * 4L
                ) { "EffectCraft returned an unexpected frame size: ${frameWidth}x${frameHeight}." }
                var offset = 8
                for (pixel in pixels.indices) {
                    val red = encoded[offset++].toInt() and 0xFF
                    val green = encoded[offset++].toInt() and 0xFF
                    val blue = encoded[offset++].toInt() and 0xFF
                    val alpha = encoded[offset++].toInt() and 0xFF
                    pixels[pixel] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
                }
                rgbToYuv420(pixels, width, height, yuv, semiPlanar)
                var inputIndex = codec.dequeueInputBuffer(10_000L)
                while (inputIndex < 0) {
                    drain(false)
                    currentCoroutineContext().ensureActive()
                    inputIndex = codec.dequeueInputBuffer(10_000L)
                }
                val input = codec.getInputBuffer(inputIndex)
                    ?: throw IllegalStateException("H.264 encoder returned an empty input buffer.")
                input.clear()
                check(input.remaining() >= yuv.size) { "H.264 encoder input buffer is too small." }
                input.put(yuv)
                codec.queueInputBuffer(inputIndex, 0, yuv.size, frameIndex * 1_000_000L / fps, 0)
                drain(false)
                if (frameIndex % max(1, fps / 2) == 0 || frameIndex == frameCount - 1) {
                    onProgress((frameIndex + 1f) / frameCount * 0.9f,
                        "Rendering EffectCraft composition… ${frameIndex + 1}/$frameCount frames")
                }
            }
            var eosInput = codec.dequeueInputBuffer(10_000L)
            while (eosInput < 0) {
                drain(false)
                eosInput = codec.dequeueInputBuffer(10_000L)
            }
            codec.queueInputBuffer(eosInput, 0, 0, frameCount * 1_000_000L / fps,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
            check(muxerStarted && videoTrack >= 0) { "H.264 encoder produced no video track." }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    suspend fun export(
        context: Context,
        layers: List<Layer>,
        durationSeconds: Float,
        fps: Int,
        resolution: Int,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Result {
        require(layers.any { it.type == LayerType.VIDEO }) { "The generated timeline has no video layers." }
        EffectCraftBridge.initializeWithError()?.let {
            throw IllegalStateException("EffectCraft is required for AI export: $it")
        }
        val duration = durationSeconds.coerceIn(0.1f, 120f)
        val frameRate = fps.coerceIn(12, 60)
        val requestedWidth = listOf(1280, 1920, 2560, 3840).getOrElse(resolution.coerceIn(0, 3)) { 1280 }
        // The app targets 32-bit ARM as well; cap exports to a practical mobile frame size.
        val width = min(requestedWidth, 1280).let { it - it % 2 }
        val height = (width * 9 / 16).let { it - it % 2 }
        val tempVideo = File(context.cacheDir, "kinetic-ai-${System.currentTimeMillis()}.mp4")
        var published: Uri? = null
        try {
            onProgress(0f, "Preparing EffectCraft renderer…")
            renderVideoTrack(context, tempVideo, layers, duration, frameRate, width, height, onProgress)
            currentCoroutineContext().ensureActive()
            val audioUri = layers.firstOrNull { it.type == LayerType.AUDIO && !it.uri.isNullOrBlank() }?.uri?.let(Uri::parse)
            val displayName = "Kinetic-AI-${System.currentTimeMillis()}.mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Kinetic")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Android couldn't create the exported video in Movies/Kinetic.")
            published = uri
            val audioIncluded = if (audioUri != null) {
                runCatching { muxVideoAndAudio(context, tempVideo, audioUri, uri, duration) }.getOrDefault(false)
            } else false
            if (!audioIncluded) {
                resolver.openOutputStream(uri, "w")?.use { output ->
                    tempVideo.inputStream().use { input -> input.copyTo(output, 256 * 1024) }
                } ?: throw IllegalStateException("Android couldn't write the exported MP4.")
            }
            if (Build.VERSION.SDK_INT >= 29) {
                val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            }
            onProgress(1f, if (audioIncluded) "Export saved to Movies/Kinetic with soundtrack." else "Export saved to Movies/Kinetic.")
            return Result(uri, displayName, duration, audioIncluded, width, height)
        } catch (error: Throwable) {
            published?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            throw error
        } finally {
            runCatching { tempVideo.delete() }
        }
    }

    private suspend fun renderVideoTrack(
        context: Context,
        output: File,
        layers: List<Layer>,
        duration: Float,
        fps: Int,
        width: Int,
        height: Int,
        onProgress: (Float, String) -> Unit
    ) {
        val codecInfo = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
            info.isEncoder && info.supportedTypes.any { it.equals("video/avc", true) } &&
                runCatching {
                    info.getCapabilitiesForType("video/avc").colorFormats.any {
                        it == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar ||
                            it == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                    }
                }.getOrDefault(false)
        } ?: throw IllegalStateException("This device doesn't expose a compatible H.264 video encoder.")
        val colorFormats = codecInfo.getCapabilitiesForType("video/avc").colorFormats
        val colorFormat = when {
            colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) ->
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            else -> MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
        }
        val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, (width * height * fps * 0.12f).toInt().coerceIn(1_500_000, 8_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        val codec = MediaCodec.createByCodecName(codecInfo.name)
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val retrievers = HashMap<String, MediaMetadataRetriever>()
        val imageCache = HashMap<String, Bitmap>()
        val composition = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(composition)
        val pixels = IntArray(width * height)
        val yuv = ByteArray(width * height * 3 / 2)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val orderedLayers = layers.asReversed()
        val byId = layers.associateBy { it.id }
        val frameCount = ceil(duration * fps).toInt().coerceAtLeast(1)
        var muxerStarted = false
        var videoTrack = -1
        val info = MediaCodec.BufferInfo()
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            fun drain(endOfStream: Boolean) {
                var idlePasses = 0
                while (true) {
                    val index = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000L else 0L)
                    when {
                        index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!endOfStream || idlePasses++ > 100) return
                        }
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted) { "Encoder changed its output format more than once." }
                            videoTrack = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        index >= 0 -> {
                            val buffer = codec.getOutputBuffer(index)
                            if (buffer != null && info.size > 0 && muxerStarted) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                muxer.writeSampleData(videoTrack, buffer, info)
                            }
                            val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            codec.releaseOutputBuffer(index, false)
                            if (eos) return
                        }
                        else -> return
                    }
                }
            }

            for (frameIndex in 0 until frameCount) {
                currentCoroutineContext().ensureActive()
                val time = frameIndex.toFloat() / fps
                canvas.drawColor(Color.BLACK)
                canvas.save()
                canvas.scale(width / COMP_W, height / COMP_H)
                for (layer in orderedLayers) {
                    if (!layer.visible || !layer.activeAt(time) || layer.type == LayerType.AUDIO ||
                        layer.type == LayerType.ADJUSTMENT || layer.type == LayerType.NULL
                    ) continue
                    val opacity = (layer.p(PropId.OPACITY).valueAt(time) / 100f).coerceIn(0f, 1f)
                    if (opacity <= 0.001f) continue
                    when (layer.type) {
                        LayerType.VIDEO, LayerType.IMAGE -> {
                            val sourceUri = layer.uri ?: continue
                            var source = if (layer.type == LayerType.VIDEO) {
                                val retriever = retrievers.getOrPut(sourceUri) {
                                    MediaMetadataRetriever().also { it.setDataSource(context, Uri.parse(sourceUri)) }
                                }
                                val sourceTime = (layer.srcOffset + layer.sourceElapsedAt(time))
                                    .coerceAtLeast(0f)
                                retriever.getFrameAtTime((sourceTime * 1_000_000L).toLong(),
                                    MediaMetadataRetriever.OPTION_CLOSEST)
                                    ?: throw IllegalStateException("Couldn't decode ${layer.name} at ${"%.2f".format(time)}s.")
                            } else {
                                imageCache.getOrPut(sourceUri) {
                                    context.contentResolver.openInputStream(Uri.parse(sourceUri))?.use {
                                        BitmapFactory.decodeStream(it)
                                    } ?: throw IllegalStateException("Couldn't open image layer ${layer.name}.")
                                }
                            }
                            var ownsSource = layer.type == LayerType.VIDEO
                            if (source.width > width || source.height > height) {
                                val scale = min(width.toFloat() / source.width, height.toFloat() / source.height)
                                val scaled = Bitmap.createScaledBitmap(source,
                                    (source.width * scale).toInt().coerceAtLeast(1),
                                    (source.height * scale).toInt().coerceAtLeast(1), true)
                                if (ownsSource) source.recycle()
                                source = scaled
                                ownsSource = true
                            }
                            val activeEffects = layer.effectStack.filter { it.enabled && it.effectCraftId != null }
                            var rendered = source
                            if (activeEffects.isNotEmpty()) {
                                rendered = EffectCraftBridge.renderFrame(
                                    source, activeEffects, time - layer.start, max(width, height),
                                    "ai-export-${layer.id}"
                                ) ?: throw IllegalStateException("EffectCraft couldn't render the effects on ${layer.name}.")
                            }
                            drawLayerBitmap(canvas, rendered, layer, time, opacity, paint)
                            if (rendered !== source) rendered.recycle()
                            if (ownsSource) source.recycle()
                        }
                        LayerType.TEXT -> drawTextLayer(canvas, layer, time, opacity)
                        LayerType.SHAPE -> drawShapeLayer(canvas, layer, time, opacity, paint)
                        else -> Unit
                    }
                }
                canvas.restore()
                composition.getPixels(pixels, 0, width, 0, 0, width, height)
                rgbToYuv420(pixels, width, height, yuv, colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
                var inputIndex = codec.dequeueInputBuffer(10_000L)
                while (inputIndex < 0) {
                    drain(false)
                    currentCoroutineContext().ensureActive()
                    inputIndex = codec.dequeueInputBuffer(10_000L)
                }
                val input = codec.getInputBuffer(inputIndex)
                    ?: throw IllegalStateException("H.264 encoder returned an empty input buffer.")
                input.clear()
                check(input.remaining() >= yuv.size) { "H.264 encoder input buffer is too small for this frame." }
                input.put(yuv)
                val pts = frameIndex * 1_000_000L / fps
                codec.queueInputBuffer(inputIndex, 0, yuv.size, pts, 0)
                drain(false)
                if (frameIndex % max(1, fps / 2) == 0 || frameIndex == frameCount - 1) {
                    onProgress((frameIndex + 1f) / frameCount * 0.88f,
                        "Rendering with EffectCraft… ${frameIndex + 1}/$frameCount frames")
                }
            }
            var eosInput = codec.dequeueInputBuffer(10_000L)
            while (eosInput < 0) {
                drain(false)
                eosInput = codec.dequeueInputBuffer(10_000L)
            }
            codec.queueInputBuffer(eosInput, 0, 0, frameCount * 1_000_000L / fps,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
            check(muxerStarted && videoTrack >= 0) { "H.264 encoder produced no video track." }
        } finally {
            retrievers.values.forEach { runCatching { it.release() } }
            imageCache.values.forEach { runCatching { it.recycle() } }
            runCatching { composition.recycle() }
            runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    private fun drawLayerBitmap(
        canvas: Canvas,
        bitmap: Bitmap,
        layer: Layer,
        time: Float,
        opacity: Float,
        paint: Paint
    ) {
        val save = canvas.save()
        canvas.translate(layer.p(PropId.POS_X).valueAt(time), layer.p(PropId.POS_Y).valueAt(time))
        canvas.rotate(layer.p(PropId.ROTATION).valueAt(time))
        canvas.scale(layer.p(PropId.SCALE_X).valueAt(time) / 100f,
            layer.p(PropId.SCALE_Y).valueAt(time) / 100f)
        val anchorX = layer.p(PropId.ANCHOR_X).valueAt(time)
        val anchorY = layer.p(PropId.ANCHOR_Y).valueAt(time)
        paint.alpha = (opacity * 255f).toInt().coerceIn(0, 255)
        canvas.drawBitmap(bitmap, null,
            RectF(-anchorX, -anchorY, layer.width - anchorX, layer.height - anchorY), paint)
        canvas.restoreToCount(save)
    }

    private fun drawTextLayer(canvas: Canvas, layer: Layer, time: Float, opacity: Float) {
        val save = canvas.save()
        canvas.translate(layer.p(PropId.POS_X).valueAt(time), layer.p(PropId.POS_Y).valueAt(time))
        canvas.rotate(layer.p(PropId.ROTATION).valueAt(time))
        canvas.scale(layer.p(PropId.SCALE_X).valueAt(time) / 100f,
            layer.p(PropId.SCALE_Y).valueAt(time) / 100f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = layer.color
            alpha = (opacity * 255f).toInt().coerceIn(0, 255)
            textSize = layer.fontSize
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(layer.fontFamily,
                if (layer.textBold && layer.textItalic) Typeface.BOLD_ITALIC
                else if (layer.textBold) Typeface.BOLD
                else if (layer.textItalic) Typeface.ITALIC else Typeface.NORMAL)
        }
        val baseline = -(paint.ascent() + paint.descent()) / 2f
        if (layer.strokeEnabled && layer.strokeWidth > 0f) {
            val stroke = Paint(paint).apply {
                style = Paint.Style.STROKE
                color = layer.strokeColor
                strokeWidth = layer.strokeWidth
            }
            canvas.drawText(layer.text, 0f, baseline, stroke)
        }
        if (layer.fillEnabled) canvas.drawText(layer.text, 0f, baseline, paint)
        canvas.restoreToCount(save)
    }

    private fun drawShapeLayer(canvas: Canvas, layer: Layer, time: Float, opacity: Float, paint: Paint) {
        val save = canvas.save()
        canvas.translate(layer.p(PropId.POS_X).valueAt(time), layer.p(PropId.POS_Y).valueAt(time))
        canvas.rotate(layer.p(PropId.ROTATION).valueAt(time))
        canvas.scale(layer.p(PropId.SCALE_X).valueAt(time) / 100f,
            layer.p(PropId.SCALE_Y).valueAt(time) / 100f)
        val rect = RectF(-layer.width / 2f, -layer.height / 2f, layer.width / 2f, layer.height / 2f)
        paint.style = Paint.Style.FILL
        paint.color = layer.color
        paint.alpha = (opacity * 255f).toInt().coerceIn(0, 255)
        canvas.drawRect(rect, paint)
        if (layer.strokeEnabled && layer.strokeWidth > 0f) {
            paint.style = Paint.Style.STROKE
            paint.color = layer.strokeColor
            paint.strokeWidth = layer.strokeWidth
            canvas.drawRect(rect, paint)
        }
        paint.style = Paint.Style.FILL
        canvas.restoreToCount(save)
    }

    private fun rgbToYuv420(
        pixels: IntArray,
        width: Int,
        height: Int,
        output: ByteArray,
        semiPlanar: Boolean
    ) {
        val frameSize = width * height
        var yIndex = 0
        var uvIndex = frameSize
        var uIndex = frameSize
        var vIndex = frameSize + frameSize / 4
        for (row in 0 until height) {
            for (column in 0 until width) {
                val color = pixels[row * width + column]
                val red = (color shr 16) and 255
                val green = (color shr 8) and 255
                val blue = color and 255
                val y = ((66 * red + 129 * green + 25 * blue + 128) shr 8) + 16
                output[yIndex++] = y.coerceIn(0, 255).toByte()
                if (row % 2 == 0 && column % 2 == 0) {
                    val u = ((-38 * red - 74 * green + 112 * blue + 128) shr 8) + 128
                    val v = ((112 * red - 94 * green - 18 * blue + 128) shr 8) + 128
                    if (semiPlanar) {
                        output[uvIndex++] = u.coerceIn(0, 255).toByte()
                        output[uvIndex++] = v.coerceIn(0, 255).toByte()
                    } else {
                        output[uIndex++] = u.coerceIn(0, 255).toByte()
                        output[vIndex++] = v.coerceIn(0, 255).toByte()
                    }
                }
            }
        }
    }

    private fun muxVideoAndAudio(
        context: Context,
        videoFile: File,
        audioUri: Uri,
        destination: Uri,
        duration: Float
    ): Boolean {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        return try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            val videoSourceTrack = (0 until videoExtractor.trackCount).firstOrNull {
                videoExtractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return false
            videoExtractor.selectTrack(videoSourceTrack)
            audioExtractor.setDataSource(context, audioUri, null)
            val audioSourceTrack = (0 until audioExtractor.trackCount).firstOrNull {
                audioExtractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return false
            val audioFormat = audioExtractor.getTrackFormat(audioSourceTrack)
            if (audioFormat.getString(MediaFormat.KEY_MIME) != "audio/mp4a-latm") return false
            audioExtractor.selectTrack(audioSourceTrack)
            val pfd = context.contentResolver.openFileDescriptor(destination, "w") ?: return false
            pfd.use { descriptor ->
                muxer = MediaMuxer(descriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val videoTrack = muxer!!.addTrack(videoExtractor.getTrackFormat(videoSourceTrack))
                val audioTrack = muxer!!.addTrack(audioFormat)
                muxer!!.start()
                started = true
                val videoBuffer = ByteBuffer.allocateDirect(2 * 1024 * 1024)
                val audioBuffer = ByteBuffer.allocateDirect(512 * 1024)
                val videoInfo = MediaCodec.BufferInfo()
                val audioInfo = MediaCodec.BufferInfo()
                var videoTime = videoExtractor.sampleTime
                var audioTime = audioExtractor.sampleTime
                while (videoTime >= 0L || (audioTime >= 0L && audioTime < (duration * 1_000_000L).toLong())) {
                    val takeVideo = videoTime >= 0L && (audioTime < 0L || audioTime >= (duration * 1_000_000L).toLong() || videoTime <= audioTime)
                    if (takeVideo) {
                        videoBuffer.clear()
                        val size = videoExtractor.readSampleData(videoBuffer, 0)
                        if (size <= 0) break
                        videoInfo.set(0, size, videoTime, videoExtractor.sampleFlags)
                        videoBuffer.position(0); videoBuffer.limit(size)
                        muxer!!.writeSampleData(videoTrack, videoBuffer, videoInfo)
                        videoExtractor.advance()
                        videoTime = videoExtractor.sampleTime
                    } else {
                        audioBuffer.clear()
                        val size = audioExtractor.readSampleData(audioBuffer, 0)
                        if (size <= 0) break
                        audioInfo.set(0, size, audioTime, audioExtractor.sampleFlags)
                        audioBuffer.position(0); audioBuffer.limit(size)
                        muxer!!.writeSampleData(audioTrack, audioBuffer, audioInfo)
                        audioExtractor.advance()
                        audioTime = audioExtractor.sampleTime
                    }
                }
                muxer!!.stop()
                started = false
            }
            true
        } catch (_: Throwable) {
            false
        } finally {
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
        }
    }
}
