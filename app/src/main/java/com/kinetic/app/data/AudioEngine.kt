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
import android.os.Process
import com.kinetic.app.model.Layer
import com.kinetic.app.model.LayerType
import com.kinetic.app.model.speedAt
import com.kinetic.app.model.sourceElapsedAt
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max

/**
 * Timeline audio engine using Android MediaExtractor/MediaCodec decoding and
 * streaming AudioTrack output. Active clips decode on dedicated workers.
 */
class AudioEngine(context: Context) {
    private val appContext = context.applicationContext
    private val players = ConcurrentHashMap<String, AudioClipPlayer>()
    private val preparedPlayers = ConcurrentHashMap<String, AudioClipPlayer>()
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
        val activeUris = sources.values.toSet()
        preparedPlayers.keys.toList().forEach { uri ->
            if (uri !in activeUris) preparedPlayers.remove(uri)?.release()
        }
    }

    /**
     * Open the source and initialize its audio decoder before the clip is
     * committed to the timeline. The prepared player is adopted by sync()
     * when playback begins, avoiding decoder startup on the first play press.
     */
    suspend fun prepareSource(uri: String): Boolean = withContext(Dispatchers.IO) {
        if (released.get()) return@withContext false
        val player = synchronized(preparedPlayers) {
            preparedPlayers[uri] ?: AudioClipPlayer(appContext, Uri.parse(uri)).also {
                preparedPlayers[uri] = it
            }
        }
        val ready = player.awaitPrepared()
        if (!ready) {
            synchronized(preparedPlayers) {
                if (preparedPlayers[uri] === player) preparedPlayers.remove(uri)
            }
            player.release()
        }
        ready
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
                player = preparedPlayers.remove(uri) ?: AudioClipPlayer(appContext, Uri.parse(uri))
                players[layer.id] = player
                playerUris[layer.id] = uri
            }
            // Keep audio on the exact same timeline time as the video transport clock.
            val targetMs = sourcePositionMs(layer, time)
            player.sync(targetMs, layer.speedAt(time), layer.volume)
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
        preparedPlayers.values.forEach { it.release() }
        players.clear()
        preparedPlayers.clear()
        playerUris.clear()
        inactiveSinceMs.clear()
    }

    private fun sourcePositionMs(layer: Layer, time: Float): Long =
        ((layer.sourceElapsedAt(time) + layer.srcOffset).coerceAtLeast(0f) * 1000f).toLong()

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
        @Volatile private var pcmChannelCount = 2
        @Volatile private var bytesPerSample = 2
        @Volatile private var appliedSpeed = Float.NaN
        @Volatile private var startHeadFrames = 0L
        @Volatile private var trackStarted = false
        @Volatile private var dropBeforeUs = -1L
        @Volatile private var track: AudioTrack? = null
        @Volatile private var prepareSucceeded = false
        private val prepareLatch = CountDownLatch(1)
        private val worker = Thread(::decodeLoop, "KineticAudioClip").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }

        fun awaitPrepared(): Boolean {
            return try {
                prepareLatch.await(PREPARE_TIMEOUT_MS, TimeUnit.MILLISECONDS) && prepareSucceeded
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        }

        fun sync(positionMs: Long, speed: Float, volume: Float) {
            if (closed) return
            requestedSpeed = speed.coerceIn(0.1f, 4f)
            requestedVolume = volume.coerceIn(0f, 2f)
            val now = SystemClock.elapsedRealtime()
            val currentTrack = track
            if (!wantsPlayback) {
                // Seek only on the transition from paused to playing. Replacing
                // this request every UI sync tick while the decoder starts can
                // repeatedly flush MediaCodec before it emits its first PCM block.
                pendingSeekMs = positionMs
            } else if (currentTrack != null && trackStarted) {
                val head = currentTrack.playbackHeadPosition.toLong() and 0xffffffffL
                val elapsedFrames = (head - startHeadFrames).coerceAtLeast(0L)
                // playbackHeadPosition counts rendered output frames. Convert that
                // duration back to source-timeline time using the active playback speed.
                val actualMs = lastSeekMs +
                    (elapsedFrames * requestedSpeed * 1000.0 / sampleRate).toLong()
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
            // The decoder worker owns MediaCodec/AudioTrack and releases them in
            // its finally block. Never join it from the editor/UI thread.
            synchronized(lock) { lock.notifyAll() }
        }

        private fun decodeLoop() {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
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
                prepareSucceeded = true
                prepareLatch.countDown()

                while (!closed) {
                    val seek = pendingSeekMs
                    if (seek >= 0L) {
                        pendingSeekMs = -1L
                        runCatching { track?.pause(); track?.flush() }
                        trackStarted = false
                        dropBeforeUs = seek * 1_000L
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
                    if (audioTrack != null && trackStarted && !outputEos) {
                        if (!appliedSpeed.isFinite() || abs(appliedSpeed - requestedSpeed) > 0.001f) {
                            runCatching {
                                audioTrack.playbackParams = audioTrack.playbackParams.setSpeed(requestedSpeed)
                                appliedSpeed = requestedSpeed
                            }
                        }
                        // Resume an already-started track after pause. A newly
                        // created or flushed track waits until PCM is queued.
                        if (audioTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
                            runCatching {
                                audioTrack.setVolume(requestedVolume.coerceIn(0f, 1f))
                                startHeadFrames = audioTrack.playbackHeadPosition.toLong() and 0xffffffffL
                                audioTrack.play()
                            }
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
                                    writePcm(output, info.size, info.presentationTimeUs)
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
                prepareLatch.countDown()
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
            pcmChannelCount = channelCount
            bytesPerSample = when (encoding) {
                AudioFormat.ENCODING_PCM_FLOAT -> 4
                AudioFormat.ENCODING_PCM_8BIT -> 1
                else -> 2
            }
            // Respect the platform minimum without forcing a 200 ms queue.
            // Keep the software queue near 50 ms; the platform minimum still wins on devices
            // that need a larger buffer to avoid underruns.
            val targetBytes = rate / 20 * channelCount * bytesPerSample
            val bufferBytes = max(minBuffer, targetBytes)
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
            created.setVolume(requestedVolume.coerceIn(0f, 1f))
            track = created
            appliedSpeed = Float.NaN
            startHeadFrames = 0L
            trackStarted = false
            // Do not start an empty AudioTrack. Queue the first decoded PCM
            // block first, otherwise startup underrun adds audible delay.
        }

        private fun writePcm(buffer: ByteBuffer, size: Int, presentationTimeUs: Long) {
            val output = track ?: return
            var remaining = size

            // MediaExtractor seeks to the previous sync sample. Trim decoded PCM
            // up to the requested source timestamp so seeks/corrections never
            // replay audio from before the clip's actual in-point.
            val targetUs = dropBeforeUs
            if (targetUs >= 0L) {
                if (presentationTimeUs < targetUs) {
                    val deltaUs = targetUs - presentationTimeUs
                    val framesToSkip = (deltaUs * sampleRate + 999_999L) / 1_000_000L
                    val bytesToSkip = framesToSkip * pcmChannelCount * bytesPerSample
                    if (bytesToSkip >= size) return
                    val skip = bytesToSkip.toInt().coerceAtLeast(0)
                    buffer.position((buffer.position() + skip).coerceAtMost(buffer.limit()))
                    remaining -= skip
                }
                dropBeforeUs = -1L
            }

            while (remaining > 0 && !closed && wantsPlayback) {
                output.setVolume(requestedVolume.coerceIn(0f, 1f))
                val written = output.write(buffer, remaining, AudioTrack.WRITE_NON_BLOCKING)
                if (written > 0) {
                    remaining -= written
                    if (!trackStarted && wantsPlayback) {
                        runCatching {
                            output.playbackParams = output.playbackParams.setSpeed(requestedSpeed)
                            appliedSpeed = requestedSpeed
                        }
                        startHeadFrames = output.playbackHeadPosition.toLong() and 0xffffffffL
                        output.play()
                        trackStarted = true
                    }
                } else {
                    synchronized(lock) {
                        if (!closed && wantsPlayback && pendingSeekMs < 0L) lock.wait(2L)
                    }
                }
            }
        }
    }

    companion object {
        // Allow normal AudioTrack scheduling jitter before flushing the decoder.
        // Frequent 24 ms corrections repeatedly discarded queued PCM and sounded laggy.
        private const val DRIFT_CORRECTION_MS = 80L
        private const val CORRECTION_COOLDOWN_MS = 240L
        private const val PREPARE_TIMEOUT_MS = 8_000L
        private const val IDLE_PLAYER_RELEASE_MS = 2_500L
    }
}
