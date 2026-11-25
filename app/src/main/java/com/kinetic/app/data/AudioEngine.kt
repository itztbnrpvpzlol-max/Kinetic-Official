package com.kinetic.app.data

import com.kinetic.app.engine.EffectStackRunner
import com.kinetic.app.model.EffectInstance
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
import android.util.Log
import com.kinetic.app.model.Layer
import com.kinetic.app.model.LayerType
import com.kinetic.app.model.speedAt
import com.kinetic.app.model.sourceElapsedAt
import com.kinetic.app.model.volumeAt
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.LinkedHashMap
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
            val clipVolume = layer.volumeAt(time)
            val shouldPlay = playing && layer.visible && clipVolume > 0f && layer.activeAt(time)
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
            player.sync(targetMs, layer.speedAt(time), clipVolume, layer.effectStack, time)
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
        @Volatile private var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
        @Volatile private var appliedSpeed = Float.NaN
        @Volatile private var startHeadFrames = 0L
        @Volatile private var trackStarted = false
        @Volatile private var dropBeforeUs = -1L
        @Volatile private var track: AudioTrack? = null
        @Volatile private var prepareSucceeded = false
        // EffectCraft audio effects for this clip, and the recent raw input that is fed back in
        // as pre-roll so filters, delays and reverbs stay continuous across decoder blocks.
        @Volatile private var audioStack: List<EffectInstance> = emptyList()
        @Volatile private var audioTime = 0f
        private var effectHistory = FloatArray(0)
        private data class AudioEffectCacheKey(
            val presentationUs: Long,
            val startSecondsBits: Long,
            val rate: Int,
            val channels: Int,
            val frames: Int,
            val stackJson: String,
            val inputHash: Long
        )
        private val audioEffectCache = object : LinkedHashMap<AudioEffectCacheKey, FloatArray>(24, 0.75f, true) {}
        private var audioEffectCacheBytes = 0L
        private val audioEffectCacheLimit = 2L * 1024L * 1024L

        private fun hashSamples(samples: FloatArray): Long {
            var hash = -0x340d631b7bdddcdbL
            for (sample in samples) {
                hash = (hash xor java.lang.Float.floatToIntBits(sample).toLong()) * 0x100000001b3L
            }
            return hash
        }

        private fun rememberAudioEffect(key: AudioEffectCacheKey, samples: FloatArray) {
            val copy = samples.copyOf()
            val size = copy.size * 4L
            synchronized(audioEffectCache) {
                audioEffectCache.put(key, copy)?.let { audioEffectCacheBytes -= it.size * 4L }
                audioEffectCacheBytes += size
                val iterator = audioEffectCache.entries.iterator()
                while (audioEffectCacheBytes > audioEffectCacheLimit && iterator.hasNext()) {
                    val old = iterator.next().value
                    iterator.remove()
                    audioEffectCacheBytes -= old.size * 4L
                }
            }
        }

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

        fun sync(positionMs: Long, speed: Float, volume: Float, effects: List<EffectInstance>, time: Float) {
            if (closed) return
            audioStack = EffectStackRunner.audioEffects(effects)
            audioTime = time
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
                // playbackHeadPosition already counts frames consumed by AudioTrack.
                // PlaybackParams changes how quickly those frames are consumed, so
                // multiplying by speed here would apply the clip rate twice.
                val actualMs = lastSeekMs +
                    (elapsedFrames * 1000.0 / sampleRate).toLong()
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
                        effectHistory = FloatArray(0); dropBeforeUs = seek * 1_000L
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
                            // Preserve the source-time position using the speed that
                            // actually played before changing AudioTrack's playback rate.
                            reanchorPlaybackClock(audioTrack)
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
                                // A pause stops the output head. Carry its elapsed
                                // source time forward before establishing the resume anchor.
                                reanchorPlaybackClock(audioTrack)
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

        /** Advance the source-time anchor using the rate that was actually applied. */
        private fun reanchorPlaybackClock(output: AudioTrack) {
            val head = output.playbackHeadPosition.toLong() and 0xffffffffL
            val elapsedFrames = (head - startHeadFrames).coerceAtLeast(0L)
            // The head is already measured in consumed source frames; do not
            // multiply by PlaybackParams speed a second time.
            lastSeekMs += (elapsedFrames * 1000.0 / sampleRate).toLong()
            startHeadFrames = head
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
            pcmEncoding = encoding
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

        /** Runs the clip's audio effects over 16-bit PCM in place (mono is processed as stereo). */
        private fun applyAudioEffects(buffer: ByteBuffer, start: Int, bytes: Int, presentationUs: Long) {
            val stack = audioStack
            val channels = pcmChannelCount
            if (stack.isEmpty() || bytesPerSample != 2 || channels !in 1..2) return
            val frames = bytes / (2 * channels)
            if (frames <= 0) return
            val rate = sampleRate
            // Real-time budget: at most a quarter second of pre-roll per block.
            val wanted = (EffectStackRunner.audioPreroll(stack, audioTime).coerceIn(0.0, 0.25) * rate).toInt()
            val keep = minOf(wanted, effectHistory.size / 2)
            val block = FloatArray((keep + frames) * 2)
            if (keep > 0) System.arraycopy(effectHistory, effectHistory.size - keep * 2, block, 0, keep * 2)
            val input = buffer.duplicate().order(buffer.order())
            input.position(start)
            val shorts = input.asShortBuffer()
            for (f in 0 until frames) {
                val l = shorts.get() / 32768f
                val r = if (channels == 2) shorts.get() / 32768f else l
                block[(keep + f) * 2] = l
                block[(keep + f) * 2 + 1] = r
            }
            // Remember the raw input (before effects) as the next block's pre-roll.
            val total = keep + frames
            val histFrames = minOf(wanted, total)
            effectHistory = if (histFrames > 0) block.copyOfRange((total - histFrames) * 2, total * 2) else FloatArray(0)
            val startSeconds = presentationUs / 1_000_000.0 - keep.toDouble() / rate
            val stackJson = EffectStackRunner.stackJson(stack, audioTime)
            val cacheKey = AudioEffectCacheKey(
                presentationUs, startSeconds.toBits(), rate, channels, frames, stackJson, hashSamples(block)
            )
            val cached = synchronized(audioEffectCache) { audioEffectCache[cacheKey] }
            if (cached != null && cached.size == frames * 2) {
                cached.copyInto(block, keep * 2)
            } else {
                if (!EffectStackRunner.processAudio(block, rate, startSeconds, stack, audioTime)) return
                rememberAudioEffect(cacheKey, block.copyOfRange(keep * 2, (keep + frames) * 2))
            }
            val output = buffer.duplicate().order(buffer.order())
            output.position(start)
            val out = output.asShortBuffer()
            for (f in 0 until frames) {
                val l = block[(keep + f) * 2].coerceIn(-1f, 1f)
                val r = block[(keep + f) * 2 + 1].coerceIn(-1f, 1f)
                out.put((l * 32767f).toInt().toShort())
                if (channels == 2) out.put((r * 32767f).toInt().toShort())
            }
        }

        private fun writePcm(buffer: ByteBuffer, size: Int, presentationTimeUs: Long) {
            var output = track ?: return
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
                    // This decoded block is entirely before the seek target. Keep
                    // the trim marker for the next block; clearing it here leaks
                    // pre-seek audio whenever the target spans multiple PCM blocks.
                    if (bytesToSkip >= size) return
                    val skip = bytesToSkip.toInt().coerceAtLeast(0)
                    buffer.position((buffer.position() + skip).coerceAtMost(buffer.limit()))
                    remaining -= skip
                }
                // Clear only after this block reaches (or crosses) the target.
                dropBeforeUs = -1L
            }

            if (remaining > 0 && audioStack.isNotEmpty()) {
                runCatching { applyAudioEffects(buffer, buffer.position(), remaining, presentationTimeUs) }
            } else if (effectHistory.isNotEmpty()) effectHistory = FloatArray(0)

            while (remaining > 0 && !closed && wantsPlayback) {
                runCatching { output.setVolume(requestedVolume.coerceIn(0f, 1f)) }
                val written = runCatching {
                    output.write(buffer, remaining, AudioTrack.WRITE_NON_BLOCKING)
                }.getOrElse {
                    Log.w("KineticAudio", "AudioTrack write failed", it)
                    AudioTrack.ERROR_DEAD_OBJECT
                }
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
                } else if (written == AudioTrack.ERROR_DEAD_OBJECT) {
                    // A dead sink otherwise leaves this non-blocking loop waiting
                    // forever, silently starving the clip. Rebuild only the output
                    // track and retry the still-unwritten PCM block.
                    Log.w("KineticAudio", "AudioTrack died; rebuilding output")
                    runCatching { output.release() }
                    track = null
                    trackStarted = false
                    if (closed || !wantsPlayback) return
                    runCatching {
                        createAudioTrack(sampleRate, pcmChannelCount, pcmEncoding)
                    }.onFailure {
                        Log.e("KineticAudio", "AudioTrack recovery failed", it)
                        return
                    }
                    output = track ?: return
                } else if (written < 0) {
                    Log.e("KineticAudio", "AudioTrack rejected PCM write: $written")
                    return
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
