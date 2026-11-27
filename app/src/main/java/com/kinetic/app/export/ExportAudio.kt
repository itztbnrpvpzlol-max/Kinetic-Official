package com.kinetic.app.export

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.kinetic.app.engine.EffectStackRunner
import com.kinetic.app.model.Layer
import com.kinetic.app.model.LayerType
import com.kinetic.app.model.sourceElapsedAt
import com.kinetic.app.model.speedAt
import com.kinetic.app.model.volumeAt
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.floor

/** One encoded AAC access unit, kept until the muxer is ready. */
internal class EncodedSample(val data: ByteArray, val presentationUs: Long, val flags: Int)

internal class EncodedAudio(val format: MediaFormat, val samples: List<EncodedSample>)

/** Sequential decoder that serves stereo float audio from an arbitrary source position. */
internal class PcmStream(context: Context, uri: Uri) : AutoCloseable {
    private val extractor = MediaExtractor()
    private var codec: MediaCodec? = null
    var hasAudio = false
        private set
    private var sampleRate = 44_100
    private var channels = 2
    private var floatOutput = false

    // Decoded stereo window: frame `windowStart + i` is at window[2 * i].
    private var window = FloatArray(0)
    private var windowStart = 0L
    private var windowFrames = 0
    private var inputDone = false
    private var outputDone = false

    init {
        runCatching {
            extractor.setDataSource(context, uri, null)
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            if (index != null) {
                extractor.selectTrack(index)
                val format = extractor.getTrackFormat(index)
                sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).also {
                    it.configure(format, null, null, 0)
                    it.start()
                }
                hasAudio = true
            }
        }
    }

    val rate: Int get() = sampleRate

    private fun seek(frame: Long) {
        extractor.seekTo(frame * 1_000_000L / sampleRate, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        codec?.flush()
        windowFrames = 0
        windowStart = frame
        inputDone = false
        outputDone = false
    }

    private fun append(frames: Int, ptsFrame: Long, fill: (FloatArray, Int) -> Unit) {
        if (windowFrames == 0) windowStart = ptsFrame
        val needed = (windowFrames + frames) * 2
        if (window.size < needed) window = window.copyOf(maxOf(needed, window.size * 2, 8192))
        fill(window, windowFrames * 2)
        windowFrames += frames
    }

    private fun decodeSome() {
        val codec = codec ?: run { outputDone = true; return }
        val info = MediaCodec.BufferInfo()
        if (!inputDone) {
            val inIndex = codec.dequeueInputBuffer(2_000)
            if (inIndex >= 0) {
                val buffer = codec.getInputBuffer(inIndex) ?: throw java.io.IOException("Audio decoder returned no input buffer.")
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }
        }
        when (val out = codec.dequeueOutputBuffer(info, 5_000)) {
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                val f = codec.outputFormat
                sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                floatOutput = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                    f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
            }
            in 0..Int.MAX_VALUE -> {
                if (info.size > 0) {
                    val buffer = codec.getOutputBuffer(out) ?: run { codec.releaseOutputBuffer(out, false); throw java.io.IOException("Audio decoder returned no output buffer.") }
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val ch = channels.coerceAtLeast(1)
                    val ptsFrame = info.presentationTimeUs * sampleRate / 1_000_000L
                    if (floatOutput) {
                        val floats = buffer.order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
                        val frames = floats.remaining() / ch
                        append(frames, ptsFrame) { dst, base ->
                            for (i in 0 until frames) {
                                val l = floats.get()
                                var r = l
                                if (ch >= 2) r = floats.get()
                                for (k in 2 until ch) floats.get()
                                dst[base + 2 * i] = l
                                dst[base + 2 * i + 1] = r
                            }
                        }
                    } else {
                        val shorts = buffer.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                        val frames = shorts.remaining() / ch
                        append(frames, ptsFrame) { dst, base ->
                            for (i in 0 until frames) {
                                val l = shorts.get() / 32768f
                                var r = l
                                if (ch >= 2) r = shorts.get() / 32768f
                                for (k in 2 until ch) shorts.get()
                                dst[base + 2 * i] = l
                                dst[base + 2 * i + 1] = r
                            }
                        }
                    }
                }
                codec.releaseOutputBuffer(out, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            }
        }
    }

    /** Frames of the source from [fromFrame] up to (not including) [toFrame] are available in the window. */
    private fun ensure(fromFrame: Long, toFrame: Long) {
        val end = windowStart + windowFrames
        if (windowFrames == 0 || fromFrame < windowStart || fromFrame > end + sampleRate) seek(maxOf(0L, fromFrame - 2))
        var guard = 0
        while (!outputDone && windowStart + windowFrames < toFrame && guard++ < 100_000) decodeSome()
        // Drop what is safely behind us, keeping a little for interpolation.
        val drop = (fromFrame - 4 - windowStart).coerceAtLeast(0L).toInt().coerceAtMost(windowFrames)
        if (drop > 65_536) {
            System.arraycopy(window, drop * 2, window, 0, (windowFrames - drop) * 2)
            windowStart += drop
            windowFrames -= drop
        }
    }

    /**
     * Fills [out] (stereo, [frames] frames at [outRate]) from the source starting at [startSec],
     * advancing [step] source seconds per output second (clip speed). Silence past the end.
     */
    fun read(startSec: Double, step: Double, frames: Int, outRate: Int, out: FloatArray) {
        java.util.Arrays.fill(out, 0, frames * 2, 0f)
        if (!hasAudio) return
        val ratio = step * sampleRate / outRate
        val first = startSec * sampleRate
        val last = first + ratio * frames
        ensure(floor(first).toLong(), ceil(last).toLong() + 2)
        for (i in 0 until frames) {
            val pos = first + ratio * i - windowStart
            val i0 = floor(pos).toInt()
            if (i0 < 0 || i0 + 1 >= windowFrames) continue
            val f = (pos - i0).toFloat()
            out[2 * i] = window[2 * i0] * (1f - f) + window[2 * (i0 + 1)] * f
            out[2 * i + 1] = window[2 * i0 + 1] * (1f - f) + window[2 * (i0 + 1) + 1] * f
        }
    }

    override fun close() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { extractor.release() }
        codec = null
    }
}

/** Mixes every audible layer, applies EffectCraft audio effects and encodes AAC. */
internal object ExportAudio {
    const val SAMPLE_RATE = 48_000
    private const val BLOCK = 4_800 // 0.1 s; clip edges and volume are resolved per block
    private const val PREROLL_CAP_SECONDS = 1.0

    fun audible(layers: List<Layer>, start: Float, end: Float): List<Layer> =
        layers.filter {
            it.visible && it.uri != null && (it.type == LayerType.AUDIO || it.type == LayerType.VIDEO) &&
                it.start < end && it.end > start
        }

    suspend fun mixAndEncode(
        context: Context, layers: List<Layer>, start: Float, end: Float, bitrate: Int,
        onProgress: (Float) -> Unit
    ): EncodedAudio? {
        val sources = audible(layers, start, end).mapNotNull { layer ->
            val stream = PcmStream(context, Uri.parse(layer.uri))
            if (stream.hasAudio) layer to stream else { stream.close(); null }
        }
        if (sources.isEmpty()) return null

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }
        // If the platform cannot create an AAC encoder, release every decoder
        // opened above before propagating the export failure.
        val encoder = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        } catch (error: Throwable) {
            sources.forEach { runCatching { it.second.close() } }
            throw error
        }
        val samples = ArrayList<EncodedSample>()
        var outputFormat: MediaFormat? = null
        var ended = false
        val info = MediaCodec.BufferInfo()

        fun drain(wait: Long) {
            while (true) {
                val index = encoder.dequeueOutputBuffer(info, wait)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = encoder.outputFormat
                    index >= 0 -> {
                        val buffer = encoder.getOutputBuffer(index) ?: run { encoder.releaseOutputBuffer(index, false); throw java.io.IOException("Audio encoder returned no output buffer.") }
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val bytes = ByteArray(info.size)
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            buffer.get(bytes)
                            samples += EncodedSample(bytes, info.presentationTimeUs, info.flags)
                        }
                        encoder.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { ended = true; return }
                    }
                    else -> return
                }
            }
        }

        fun feed(pcm: ByteArray, size: Int, ptsUs: Long, last: Boolean) {
            var offset = 0
            var pts = ptsUs
            var inputWaits = 0
            while (true) {
                val index = encoder.dequeueInputBuffer(10_000)
                if (index < 0) {
                    drain(0)
                    if (++inputWaits >= 1_000) {
                        throw java.io.IOException("Audio encoder stopped accepting input.")
                    }
                    continue
                }
                inputWaits = 0
                val buffer = encoder.getInputBuffer(index) ?: throw java.io.IOException("Audio encoder returned no input buffer.")
                buffer.clear()
                val remaining = size - offset
                val chunk = minOf(buffer.remaining(), remaining)
                if (chunk <= 0) {
                    throw java.io.IOException("Audio encoder input buffer has no available capacity.")
                }
                buffer.put(pcm, offset, chunk)
                offset += chunk
                val done = offset >= size
                val flags = if (done && last) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                encoder.queueInputBuffer(index, 0, chunk, pts, flags)
                pts += chunk / 4L * 1_000_000L / SAMPLE_RATE
                drain(0)
                if (done) break
            }
        }

        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            val totalFrames = ceil((end - start) * SAMPLE_RATE.toDouble()).toLong().coerceAtLeast(1)
            val mix = FloatArray(BLOCK * 2)
            val scratch = FloatArray(BLOCK * 2)
            val history = HashMap<String, FloatArray>()
            var position = 0L
            while (position < totalFrames) {
                coroutineContext.ensureActive()
                val frames = minOf(BLOCK.toLong(), totalFrames - position).toInt()
                val t = start + position.toFloat() / SAMPLE_RATE
                val middle = t + frames / (2f * SAMPLE_RATE)
                java.util.Arrays.fill(mix, 0, frames * 2, 0f)
                for ((layer, stream) in sources) {
                    if (!layer.activeAt(middle)) continue
                    val volume = layer.volume * layer.volumeAt(middle)
                    if (volume <= 0.0001f) continue
                    val speed = layer.speedAt(middle).toDouble()
                    val sourceStart = (layer.sourceElapsedAt(t) + layer.srcOffset).coerceAtLeast(0f).toDouble()
                    stream.read(sourceStart, speed, frames, SAMPLE_RATE, scratch)
                    applyEffects(layer, scratch, frames, t, history)
                    for (i in 0 until frames * 2) mix[i] += scratch[i] * volume
                }
                val pcm = ByteArray(frames * 4)
                for (i in 0 until frames * 2) {
                    val s = (mix[i].coerceIn(-1f, 1f) * 32767f).toInt()
                    pcm[2 * i] = (s and 0xFF).toByte()
                    pcm[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
                }
                position += frames
                feed(pcm, pcm.size, (position - frames) * 1_000_000L / SAMPLE_RATE, last = position >= totalFrames)
                onProgress(position.toFloat() / totalFrames)
            }
            // The last input carried the end-of-stream flag; collect whatever the encoder still holds.
            var guard = 0
            while (!ended && guard++ < 1_000) drain(10_000)
            if (!ended) throw java.io.IOException("Audio encoder did not finish the output stream.")
        } finally {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            sources.forEach { it.second.close() }
        }
        val finalFormat = outputFormat ?: return null
        return EncodedAudio(finalFormat, samples)
    }

    /** Runs the layer's audio effects over [block], feeding the preceding input back in as pre-roll. */
    private fun applyEffects(layer: Layer, block: FloatArray, frames: Int, time: Float, history: MutableMap<String, FloatArray>) {
        val stack = EffectStackRunner.audioEffects(layer.effectStack)
        if (stack.isEmpty()) return
        val wanted = (EffectStackRunner.audioPreroll(stack, time).coerceIn(0.0, PREROLL_CAP_SECONDS) * SAMPLE_RATE).toInt()
        val previous = history[layer.id] ?: FloatArray(0)
        val keep = minOf(wanted, previous.size / 2)
        val work = FloatArray((keep + frames) * 2)
        if (keep > 0) System.arraycopy(previous, previous.size - keep * 2, work, 0, keep * 2)
        System.arraycopy(block, 0, work, keep * 2, frames * 2)
        val total = keep + frames
        val histFrames = minOf(wanted, total)
        history[layer.id] = if (histFrames > 0) work.copyOfRange((total - histFrames) * 2, total * 2) else FloatArray(0)
        val layerSeconds = (time - layer.start).toDouble() - keep.toDouble() / SAMPLE_RATE
        if (EffectStackRunner.processAudio(work, SAMPLE_RATE, layerSeconds, stack, time)) {
            System.arraycopy(work, keep * 2, block, 0, frames * 2)
        }
    }
}

/** A cheap check, with no decoding, for whether any layer could contribute audio. */
object ExportAudioProbe {
    fun hasAudibleLayers(layers: List<Layer>, start: Float, end: Float): Boolean =
        ExportAudio.audible(layers, start, end).isNotEmpty()
}
