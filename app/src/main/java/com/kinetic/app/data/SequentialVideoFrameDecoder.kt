package com.kinetic.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.max

/**
 * One persistent, sequential video decoder for the interactive preview.
 *
 * Calls are made from KineticPreviewDecoder. Keeping the extractor and codec
 * alive avoids paying codec startup costs for every sampled frame. A backwards
 * seek or a large discontinuity recreates the session at the preceding sync
 * sample, then decodes forward to the requested presentation timestamp.
 */
internal class SequentialVideoFrameDecoder {
    private data class Session(
        val identity: String,
        val extractor: MediaExtractor,
        val codec: MediaCodec,
        val rotation: Int,
        val info: MediaCodec.BufferInfo = MediaCodec.BufferInfo(),
        var inputEnded: Boolean = false,
        var outputEnded: Boolean = false,
        var lastRequestedUs: Long = -1L,
        var lastOutputUs: Long = -1L
    )

    private var session: Session? = null
    @Volatile private var fallbackIdentity: String? = null

    fun shouldUseRetrieverFallback(identity: String): Boolean = fallbackIdentity == identity

    @Synchronized
    fun decodeFrame(context: Context, uri: Uri, identity: String, targetUs: Long, maxEdge: Int): Bitmap? {
        val target = targetUs.coerceAtLeast(0L)
        if (fallbackIdentity == identity) return null
        if (fallbackIdentity != null && fallbackIdentity != identity) fallbackIdentity = null
        try {
            var active = session
            val needsSeek = active == null ||
                active.identity != identity ||
                (active.lastRequestedUs >= 0L && target + 20_000L < active.lastRequestedUs) ||
                (active.lastOutputUs >= 0L && target - active.lastOutputUs > 5_000_000L) ||
                active.outputEnded
            if (needsSeek) {
                closeSession()
                active = openSession(context, uri, identity, target)
                session = active
            }
            val decoder = active ?: return null
            decoder.lastRequestedUs = target
            return decodeUntil(decoder, target, maxEdge)
        } catch (_: Throwable) {
            fallbackIdentity = identity
            closeSession()
            return null
        }
    }

    @Synchronized
    fun release() {
        closeSession()
        fallbackIdentity = null
    }

    private fun openSession(context: Context, uri: Uri, identity: String, targetUs: Long): Session? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: throw IllegalArgumentException("No video track")
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalArgumentException("Video track has no MIME type")
            val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                format.getInteger(MediaFormat.KEY_ROTATION)
            } else 0
            extractor.selectTrack(trackIndex)
            // Always begin at a sync sample. Inter-frame codecs require decoding
            // dependent frames forward from that keyframe to reach targetUs.
            extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            codec = createConfiguredDecoder(mime, format)
            return Session(identity, extractor, codec, rotation)
        } catch (error: Throwable) {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            throw error
        }
    }

    private fun createConfiguredDecoder(mime: String, format: MediaFormat): MediaCodec {
        val candidates = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { info ->
                    !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
                }
        }.getOrDefault(emptyList())

        val hardware = candidates.firstOrNull { info ->
            if (Build.VERSION.SDK_INT >= 29) {
                info.isHardwareAccelerated && !info.isSoftwareOnly
            } else {
                val name = info.name.lowercase()
                !name.contains("google") && !name.contains("android") && !name.contains("software")
            }
        }

        if (hardware != null) {
            var candidate: MediaCodec? = null
            try {
                candidate = MediaCodec.createByCodecName(hardware.name)
                candidate.configure(format, null, null, 0)
                candidate.start()
                return candidate
            } catch (_: Throwable) {
                runCatching { candidate?.stop() }
                runCatching { candidate?.release() }
            }
        }

        return MediaCodec.createDecoderByType(mime).also {
            it.configure(format, null, null, 0)
            it.start()
        }
    }
    private fun decodeUntil(session: Session, targetUs: Long, maxEdge: Int): Bitmap? {
        val codec = session.codec
        val extractor = session.extractor
        val deadline = SystemClock.elapsedRealtime() + 1_800L
        var inputIdle = 0
        var outputIdle = 0

        while (SystemClock.elapsedRealtime() < deadline && !session.outputEnded) {
            if (!session.inputEnded) {
                val inputIndex = codec.dequeueInputBuffer(4_000L)
                if (inputIndex >= 0) {
                    val input = codec.getInputBuffer(inputIndex)
                    if (input == null) {
                        codec.queueInputBuffer(
                            inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        session.inputEnded = true
                    } else {
                        input.clear()
                        val size = extractor.readSampleData(input, 0)
                        val pts = extractor.sampleTime
                        if (size < 0 || pts < 0L) {
                            codec.queueInputBuffer(
                                inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            session.inputEnded = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, pts, 0)
                            extractor.advance()
                        }
                    }
                    inputIdle = 0
                } else {
                    inputIdle++
                }
            }

            when (val outputIndex = codec.dequeueOutputBuffer(session.info, 4_000L)) {
                MediaCodec.INFO_TRY_AGAIN_LATER,
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED,
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputIdle++
                else -> if (outputIndex >= 0) {
                    outputIdle = 0
                    val info = session.info
                    val presentationUs = info.presentationTimeUs
                    val hasFrame = info.size > 0 &&
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    var bitmap: Bitmap? = null
                    if (hasFrame && presentationUs >= targetUs) {
                        val image = runCatching { codec.getOutputImage(outputIndex) }.getOrNull()
                        if (image != null) {
                            bitmap = try {
                                imageToBitmap(image, session.rotation, maxEdge)
                            } finally {
                                image.close()
                            }
                            if (bitmap == null) fallbackIdentity = session.identity
                        } else {
                            fallbackIdentity = session.identity
                        }
                    }
                    if (hasFrame) session.lastOutputUs = presentationUs
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        session.outputEnded = true
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (bitmap != null) return bitmap
                    if (hasFrame && presentationUs >= targetUs) {
                        // Some vendor codecs expose an opaque output instead
                        // of an Image. Let the caller use its compatibility path.
                        return null
                    }
                }
            }

            if (inputIdle > 80 && outputIdle > 80) break
        }
        return null
    }

    private fun imageToBitmap(image: Image, rotation: Int, maxEdge: Int): Bitmap? {
        val crop = image.cropRect
        val sourceWidth = crop.width()
        val sourceHeight = crop.height()
        if (sourceWidth < 2 || sourceHeight < 2) return null
        val scale = ceil(max(sourceWidth, sourceHeight).toFloat() /
            maxEdge.coerceAtLeast(160).toFloat()).toInt().coerceAtLeast(1)
        val width = (sourceWidth / scale).coerceAtLeast(2).let { if (it % 2 == 0) it else it - 1 }
        val height = (sourceHeight / scale).coerceAtLeast(2).let { if (it % 2 == 0) it else it - 1 }
        val planes = image.planes
        if (planes.size < 3) return null
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val nv21 = ByteArray(width * height * 3 / 2)
        var offset = 0

        for (row in 0 until height) {
            val sourceY = crop.top + minOf(row * scale, sourceHeight - 1)
            val rowStart = sourceY * yPlane.rowStride + crop.left * yPlane.pixelStride
            for (column in 0 until width) {
                val sourceX = crop.left + minOf(column * scale, sourceWidth - 1)
                nv21[offset++] = yBuffer.get(rowStart + (sourceX - crop.left) * yPlane.pixelStride)
            }
        }
        for (row in 0 until height / 2) {
            val sourceY = crop.top / 2 + minOf(row * scale, sourceHeight / 2 - 1)
            val uRow = sourceY * uPlane.rowStride + (crop.left / 2) * uPlane.pixelStride
            val vRow = sourceY * vPlane.rowStride + (crop.left / 2) * vPlane.pixelStride
            for (column in 0 until width / 2) {
                val sourceX = minOf(column * scale, sourceWidth / 2 - 1)
                nv21[offset++] = vBuffer.get(vRow + sourceX * vPlane.pixelStride)
                nv21[offset++] = uBuffer.get(uRow + sourceX * uPlane.pixelStride)
            }
        }

        val bytes = ByteArrayOutputStream()
        if (!YuvImage(nv21, ImageFormat.NV21, width, height, null)
                .compressToJpeg(Rect(0, 0, width, height), 82, bytes)) return null
        var bitmap = BitmapFactory.decodeByteArray(bytes.toByteArray(), 0, bytes.size()) ?: return null
        if (rotation % 360 != 0) {
            val matrix = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) bitmap.recycle()
            bitmap = rotated
        }
        return bitmap
    }

    private fun closeSession() {
        val old = session
        session = null
        if (old != null) {
            runCatching { old.codec.stop() }
            runCatching { old.codec.release() }
            runCatching { old.extractor.release() }
        }
    }
}
