package com.kinetic.app.data

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import com.kinetic.app.model.Layer
import com.kinetic.app.model.LayerType
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

/**
 * Timeline audio engine using Android MediaExtractor/MediaCodec decoding and
 * streaming AudioTrack output. Active clips decode on dedicated workers.
 */
class AudioEngine(context: Context) {
    private val appContext = context.applicationContext
    private val players = ConcurrentHashMap<String, AudioClipPlayer>()
    private val playerUris = ConcurrentHashMap<String, String>()
    private val inactiveSinceMs = ConcurrentHashMap<String, Long>()
    private val released = AtomicBoolean(false)

    fun prepare(layers: List<Layer>) {
        if (released.get()) return
        val sources = layers.asSequence()
            .filter { it.type == LayerType.AUDIO || it.type == LayerType.VIDEO }
            .mapNotNull { layer -> layer.uri?.let { layer.id to it } }
            .toMap()
        players.keys.toList().forEach { id ->
            if (sources[id] != playerUris[id]) releasePlayer(id)
        }
    }

    fun sync(layers: List<Layer>, time: Float, playing: Boolean) {
        if (released.get()) return
        val now = SystemClock.elapsedRealtime()
        val activeIds = HashSet<String>()
        for (layer in layers) {
            val uri = layer.uri ?: continue
            if (layer.type != LayerType.AUDIO && layer.type != LayerType.VIDEO) continue
            activeIds += layer.id
            val shouldPlay = playing && layer.visible && layer.volume > 0f && layer.activeAt(time)
            var player = players[layer.id]
            if (player != null && playerUris[layer.id] != uri) {
                releasePlayer(layer.id)
                player = null
            }
            if (!shouldPlay) {
                player?.pause()
                if (player != null) {
                    inactiveSinceMs.putIfAbsent(layer.id, now)
                    if (now - (inactiveSinceMs[layer.id] ?: now) >= IDLE_PLAYER_RELEASE_MS) {
                        releasePlayer(layer.id)
                    }
                }
                continue
            }
            inactiveSinceMs.remove(layer.id)
            if (player == null) {
                player = AudioClipPlayer(appContext, Uri.parse(uri))
                players[layer.id] = player
                playerUris[layer.id] = uri
            }
            val targetMs = sourcePositionMs(layer, time)
            player.sync(targetMs, layer.speed, layer.volume)
        }
        players.keys.filter { it !in activeIds }.toList().forEach(::releasePlayer)
    }

    fun pauseAll() {
        if (released.get()) return
        val now = SystemClock.elapsedRealtime()
        players.forEach { (id, player) ->
            player.pause()
            inactiveSinceMs.putIfAbsent(id, now)
        }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        players.values.forEach { it.release() }
        players.clear()
        playerUris.clear()
        inactiveSinceMs.clear()
    }

    private fun sourcePositionMs(layer: Layer, time: Float): Long =
        (((time - layer.start).coerceAtLeast(0f) * layer.speed + layer.srcOffset)
            .coerceAtLeast(0f) * 1000f).toLong()

    private fun releasePlayer(id: String) {
        players.remove(id)?.release()
        playerUris.remove(id)
        inactiveSinceMs.remove(id)
    }

    private class AudioClipPlayer(
        private val context: Context,
        private val uri: Uri
    ) {
        private val lock = Object()
        @Volatile private var closed = false
        @Volatile private var wantsPlayback = false
        @Volatile private var pendingSeekMs = -1L
        @Volatile private var requestedSpeed = 1f
        @Volatile private var requestedVolume = 1f
        @Volatile private var lastSeekMs = 0L
        @Volatile private var lastSyncMs = 0L
        @Volatile private var sampleRate = 48_000
        @Volatile private var startHeadFrames = 0L
        @Volatile private var track: AudioTrack? = null
        private val worker = Thread(::decodeLoop, "KineticAudioClip").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }

        fun sync(positionMs: Long, speed: Float, volume: Float) {
            if (closed) return
            requestedSpeed = speed.coerceIn(0.1f, 4f)
            requestedVolume = volume.coerceIn(0f, 2f)
            val now = SystemClock.elapsedRealtime()
            val currentTrack = track
            if (!wantsPlayback || currentTrack == null) {
                pendingSeekMs = positionMs
            } else {
                val head = currentTrack.playbackHeadPosition.toLong() and 0xffffffffL
                val elapsedFrames = (head - startHeadFrames).coerceAtLeast(0L)
                val actualMs = lastSeekMs +
                    (elapsedFrames * 1000.0 / sampleRate * requestedSpeed).toLong()
                if (abs(positionMs - actualMs) > DRIFT_CORRECTION_MS &&
                    now - lastSyncMs >= CORRECTION_COOLDOWN_MS) {
                    pendingSeekMs = positionMs
                }
            }
            lastSyncMs = now
            wantsPlayback = true
            synchronized(lock) { lock.notifyAll() }
        }

        fun pause() {
            wantsPlayback = false
            synchronized(lock) { lock.notifyAll() }
        }

        fun release() {
            if (closed) return
            closed = true
            wantsPlayback = false
            synchronized(lock) { lock.notifyAll() }
            worker.join(1_500L)
            track?.runCatching { pause(); flush(); release() }
            track = null
        }

        private fun decodeLoop() {
            var extractor: MediaExtractor? = null
            var codec: MediaCodec? = null
            var inputEos = false
            var outputEos = false
            try {
                extractor = MediaExtractor()
                extractor.setDataSource(context, uri, null)
                val sourceFormat = findAudioTrack(extractor) ?: return
                val mime = sourceFormat.getString(MediaFormat.KEY_MIME) ?: return
                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(sourceFormat, null, null, 0)
                codec.start()

                while (!closed) {
                    val seek = pendingSeekMs
                    if (seek >= 0L) {
                        pendingSeekMs = -1L
                        runCatching { track?.pause(); track?.flush() }
                        extractor.seekTo(seek * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        codec.flush()
                        inputEos = false
                        outputEos = false
                        lastSeekMs = seek
                        startHeadFrames = 0L
                    }

                    if (!wantsPlayback) {
                        runCatching { track?.pause() }
                        synchronized(lock) {
                            if (!closed && !wantsPlayback && pendingSeekMs < 0L) lock.wait(12L)
                        }
                        continue
                    }

                    val audioTrack = track
                    if (audioTrack != null && audioTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        runCatching {
                            audioTrack.setVolume(requestedVolume)
                            audioTrack.playbackParams = audioTrack.playbackParams.setSpeed(requestedSpeed)
                            audioTrack.play()
                            startHeadFrames = audioTrack.playbackHeadPosition.toLong() and 0xffffffffL
                        }
                    }

                    if (!inputEos) {
                        val inputIndex = codec.dequeueInputBuffer(0L)
                        if (inputIndex >= 0) {
                            val input = codec.getInputBuffer(inputIndex)
                            if (input == null) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0L, 0)
                            } else {
                                val size = extractor.readSampleData(input, 0)
                                if (size < 0) {
                                    codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputEos = true
                                } else {
                                    val pts = extractor.sampleTime
                                    codec.queueInputBuffer(inputIndex, 0, size, pts, 0)
                                    extractor.advance()
                                }
                            }
                        }
                    }

                    val info = MediaCodec.BufferInfo()
                    val outputIndex = codec.dequeueOutputBuffer(info, 10_000L)
                    when {
                        outputIndex >= 0 -> {
                            if (info.size > 0) {
                                val output = codec.getOutputBuffer(outputIndex)
                                if (output != null) {
                                    output.position(info.offset)
                                    output.limit(info.offset + info.size)
                                    writePcm(output, info.size)
                                }
                            }
                            codec.releaseOutputBuffer(outputIndex, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputEos = true
                        }
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val decoded = codec.outputFormat
                            sampleRate = decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            val channels = decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 2)
                            val encoding = if (decoded.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                decoded.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            } else AudioFormat.ENCODING_PCM_16BIT
                            createAudioTrack(sampleRate, channels, encoding)
                        }
                    }
                    if (outputEos) {
                        runCatching { track?.pause() }
                        synchronized(lock) {
                            if (!closed && wantsPlayback && pendingSeekMs < 0L) lock.wait(20L)
                        }
                    }
                }
            } catch (_: Throwable) {
                // Invalid, unsupported, or revoked sources fail closed without crashing the editor.
            } finally {
                runCatching { track?.pause(); track?.flush(); track?.release() }
                track = null
                runCatching { codec?.stop(); codec?.release() }
                runCatching { extractor?.release() }
            }
        }

        private fun findAudioTrack(extractor: MediaExtractor): MediaFormat? {
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    extractor.selectTrack(index)
                    return format
                }
            }
            return null
        }

        private fun createAudioTrack(rate: Int, channelCount: Int, encoding: Int) {
            runCatching { track?.pause(); track?.flush(); track?.release() }
            val channelMask = if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val minBuffer = AudioTrack.getMinBufferSize(rate, channelMask, encoding)
                .takeIf { it > 0 } ?: (rate / 5 * channelCount * 2)
            val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
            val bufferBytes = max(minBuffer, rate / 5 * channelCount * bytesPerSample)
            val format = AudioFormat.Builder()
                .setSampleRate(rate)
                .setChannelMask(channelMask)
                .setEncoding(encoding)
                .build()
            val created = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferBytes)
                .build()
            created.setVolume(requestedVolume)
            track = created
            startHeadFrames = 0L
            if (wantsPlayback) runCatching {
                created.playbackParams = created.playbackParams.setSpeed(requestedSpeed)
                created.play()
            }
        }

        private fun writePcm(buffer: ByteBuffer, size: Int) {
            val output = track ?: return
            var remaining = size
            while (remaining > 0 && !closed && wantsPlayback) {
                output.setVolume(requestedVolume)
                val written = output.write(buffer, remaining, AudioTrack.WRITE_NON_BLOCKING)
                if (written > 0) {
                    remaining -= written
                } else {
                    synchronized(lock) {
                        if (!closed && wantsPlayback && pendingSeekMs < 0L) lock.wait(2L)
                    }
                }
            }
        }
    }

    companion object {
        private const val DRIFT_CORRECTION_MS = 500L
        private const val CORRECTION_COOLDOWN_MS = 1_200L
        private const val IDLE_PLAYER_RELEASE_MS = 2_500L
    }
}
