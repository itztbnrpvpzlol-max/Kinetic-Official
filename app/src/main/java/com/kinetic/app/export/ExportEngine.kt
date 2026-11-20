package com.kinetic.app.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.kinetic.app.model.CompSettings
import com.kinetic.app.model.Layer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext

enum class ExportPhase { PREPARING, AUDIO, VIDEO, FINISHING }

class ExportProgress(
    val phase: ExportPhase,
    /** 0..1 across the whole export. */
    val fraction: Float,
    val frame: Int,
    val totalFrames: Int,
    val elapsedMs: Long,
    val etaMs: Long?,
    /** Frames rendered per second so far. */
    val renderFps: Float
)

class ExportFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Renders the composition to an MP4 with hardware video encoding and mixed AAC audio. */
object ExportEngine {
    private const val AUDIO_SHARE = 0.12f
    private const val FINISH_SHARE = 0.02f

    /**
     * Renders [layers] to [output]. [onProgress] and [onPreview] are called from the export thread
     * (the preview bitmap is a small copy the caller may keep). Cancelling the calling coroutine
     * stops the export and deletes the partial file.
     */
    suspend fun run(
        context: Context, layers: List<Layer>, comp: CompSettings, settings: ExportSettings, output: File,
        onProgress: (ExportProgress) -> Unit, onPreview: (Bitmap) -> Unit
    ) {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "KineticExport").apply { priority = Thread.NORM_PRIORITY } }
        try {
            withContext(executor.asCoroutineDispatcher()) { render(context.applicationContext, layers, comp, settings, output, onProgress, onPreview) }
        } catch (e: CancellationException) {
            output.delete()
            throw e
        } catch (e: ExportFailure) {
            output.delete()
            throw e
        } catch (e: Throwable) {
            output.delete()
            throw ExportFailure(e.message ?: e.javaClass.simpleName, e)
        } finally {
            executor.shutdown()
        }
    }

    private suspend fun render(
        context: Context, layers: List<Layer>, comp: CompSettings, settings: ExportSettings, output: File,
        onProgress: (ExportProgress) -> Unit, onPreview: (Bitmap) -> Unit
    ) {
        val started = SystemClock.elapsedRealtime()
        val codec = settings.codec
        if (!ExportCapabilities.supportsCodec(codec)) throw ExportFailure("This device has no ${codec.label} encoder. Choose another codec.")
        if (!ExportCapabilities.supports(codec, settings.width, settings.height, settings.fps)) {
            throw ExportFailure("This device's ${codec.label} encoder can't make ${settings.width}\u00D7${settings.height} at ${settings.fps} fps. Try a lower resolution or frame rate.")
        }
        if (settings.duration <= 0f) throw ExportFailure("There is nothing to export in that range.")
        output.parentFile?.mkdirs()
        output.delete()

        fun report(phase: ExportPhase, fraction: Float, frame: Int) {
            val elapsed = SystemClock.elapsedRealtime() - started
            val eta = if (fraction > 0.04f) (elapsed / fraction * (1f - fraction)).toLong() else null
            val fps = if (phase == ExportPhase.VIDEO && elapsed > 0) frame * 1000f / elapsed else 0f
            onProgress(ExportProgress(phase, fraction.coerceIn(0f, 1f), frame, settings.frameCount, elapsed, eta, fps))
        }

        report(ExportPhase.PREPARING, 0f, 0)

        // 1. Audio first: it is mixed offline and held until the muxer can start.
        val wantAudio = settings.includeAudio && ExportAudio.audible(layers, settings.start, settings.end).isNotEmpty()
        val audio = if (wantAudio) {
            ExportAudio.mixAndEncode(context, layers, settings.start, settings.end, settings.audioBitrate) {
                report(ExportPhase.AUDIO, it * AUDIO_SHARE, 0)
            }
        } else null
        val videoStart = if (audio != null) AUDIO_SHARE else 0f
        val videoSpan = 1f - videoStart - FINISH_SHARE

        // 2. Video encoder fed through a GL surface.
        val format = MediaFormat.createVideoFormat(codec.mime, settings.width, settings.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, ExportCapabilities.clampBitrate(codec, settings.videoBitrate))
            setInteger(MediaFormat.KEY_FRAME_RATE, settings.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            if (Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
        val encoder = MediaCodec.createEncoderByType(codec.mime)
        var gl: GlEncoderSurface? = null
        var renderer: ExportFrameRenderer? = null
        var muxer: MediaMuxer? = null
        var drain: Drain? = null
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            gl = GlEncoderSurface(encoder.createInputSurface(), settings.width, settings.height)
            encoder.start()
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            drain = Drain(encoder, muxer, audio).also { it.start() }
            renderer = ExportFrameRenderer(context, layers, comp, settings.width, settings.height, settings.fps)

            val total = settings.frameCount
            for (i in 0 until total) {
                coroutineContext.ensureActive()
                drain.failure?.let { throw ExportFailure("The video encoder stopped: ${it.message}", it) }
                val time = (settings.start + i.toFloat() / settings.fps).coerceAtMost(settings.end - 0.0001f)
                val frame = renderer.render(time)
                gl.draw(frame, i * 1_000_000_000L / settings.fps)
                report(ExportPhase.VIDEO, videoStart + videoSpan * (i + 1) / total, i + 1)
                if (i % 3 == 0 || i == total - 1) onPreview(thumbnail(frame))
            }

            report(ExportPhase.FINISHING, 1f - FINISH_SHARE, total)
            encoder.signalEndOfInputStream()
            drain.join(60_000)
            drain.failure?.let { throw ExportFailure("The video encoder stopped: ${it.message}", it) }
            if (!drain.finished) throw ExportFailure("The encoder did not finish. Try a lower resolution.")
            if (drain.videoSamples == 0) throw ExportFailure("The encoder produced no video.")
            muxer.stop()
            report(ExportPhase.FINISHING, 1f, total)
        } finally {
            drain?.abort = true
            runCatching { drain?.join(2_000) }
            runCatching { renderer?.close() }
            runCatching { gl?.release() }
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            runCatching { muxer?.release() }
        }
    }

    private fun thumbnail(frame: Bitmap): Bitmap {
        val edge = maxOf(frame.width, frame.height)
        if (edge <= 540) return frame.copy(Bitmap.Config.ARGB_8888, false)
        val k = 540f / edge
        return Bitmap.createScaledBitmap(frame, (frame.width * k).toInt().coerceAtLeast(1), (frame.height * k).toInt().coerceAtLeast(1), true)
    }

    /** Collects encoder output on its own thread so a full output queue can never stall frame submission. */
    private class Drain(
        private val encoder: MediaCodec, private val muxer: MediaMuxer, private val audio: EncodedAudio?
    ) : Thread("KineticExportDrain") {
        @Volatile var failure: Throwable? = null
        @Volatile var abort = false
        @Volatile var finished = false
        @Volatile var videoSamples = 0
        private var videoTrack = -1
        private var started = false

        override fun run() {
            val info = MediaCodec.BufferInfo()
            try {
                while (!abort) {
                    val index = encoder.dequeueOutputBuffer(info, 10_000)
                    when {
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            videoTrack = muxer.addTrack(encoder.outputFormat)
                            val audioTrack = audio?.let { muxer.addTrack(it.format) } ?: -1
                            muxer.start()
                            started = true
                            audio?.samples?.forEach { s ->
                                val meta = MediaCodec.BufferInfo().apply { set(0, s.data.size, s.presentationUs, MediaCodec.BUFFER_FLAG_KEY_FRAME) }
                                muxer.writeSampleData(audioTrack, ByteBuffer.wrap(s.data), meta)
                            }
                        }
                        index >= 0 -> {
                            val buffer = encoder.getOutputBuffer(index)
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                            if (info.size > 0 && started && buffer != null) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                muxer.writeSampleData(videoTrack, buffer, info)
                                videoSamples++
                            }
                            encoder.releaseOutputBuffer(index, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                finished = true
                                return
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                failure = t
            }
        }
    }
}

/** Where finished exports end up, and how they are shared. */
object ExportStorage {
    /** A scratch file for the render; moved to the gallery when it succeeds. */
    fun workFile(context: Context, name: String): File =
        File(File(context.cacheDir, "exports"), "$name.mp4")

    /** Copies [file] to the device's Movies/Kinetic folder and returns a shareable Uri. */
    fun saveToGallery(context: Context, file: File, name: String): Uri {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "$name.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Kinetic")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw ExportFailure("Could not create the file in your gallery.")
            try {
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                    ?: throw ExportFailure("Could not write to your gallery.")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            } catch (e: Throwable) {
                resolver.delete(uri, null, null)
                throw e
            }
            file.delete()
            return uri
        }
        // Android 8 and 9: the app's own Movies folder needs no permission.
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Kinetic").apply { mkdirs() }
        var target = File(dir, "$name.mp4")
        var n = 1
        while (target.exists()) target = File(dir, "$name (${n++}).mp4")
        file.copyTo(target)
        file.delete()
        return FileProvider.getUriForFile(context, "${context.packageName}.exports", target)
    }

    fun whereSaved(): String =
        if (Build.VERSION.SDK_INT >= 29) "Movies/Kinetic in your gallery" else "this app's Movies folder"
}
