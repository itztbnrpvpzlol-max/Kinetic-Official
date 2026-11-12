package com.kinetic.app.engine

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import dev.jdtech.mpv.MPVLib
import kotlin.math.abs
import kotlin.math.max

/**
 * FFmpeg/libavcodec-backed editor video decoder.
 *
 * libmpv owns demuxing, decoding, timestamp handling and video output. Its
 * Android GPU output is connected to Kinetic's SurfaceTexture, so decoded
 * frames enter the existing OpenGL composition pass without Bitmap copies.
 *
 * Android MediaCodec remains a compatibility fallback if libmpv cannot start.
 */
class FfmpegVideoPlayer(
    context: Context,
    private val sourceUri: Uri
) {
    private val appContext = context.applicationContext
    // All libmpv calls are serialized off the UI and GL threads.
    private val decoderExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "KineticMpvDecoder").apply { isDaemon = true }
    }
    private val pendingClockSync = AtomicReference<ClockSync?>(null)
    private val clockSyncQueued = AtomicBoolean(false)
    private var mpv: MPVLib? = null
    private var sourceDescriptor: ParcelFileDescriptor? = null
    @Volatile private var surface: Surface? = null
    @Volatile private var fallback: AndroidVideoPlayer? = null
    @Volatile private var released = false
    @Volatile private var state = 0
    @Volatile private var pendingSeekMs = 0L
    @Volatile private var speed = 1f
    @Volatile private var lastPlaying = false
    private var attachedSurface: Surface? = null
    private var initialized = false
    private var loaded = false
    private var masterGeneration = Long.MIN_VALUE
    private var lastSyncNs = 0L
    private var lastCorrectionNs = 0L
    private var lastDiagnosticsNs = 0L

    private data class ClockSync(
        val positionUs: Long,
        val monotonicNs: Long,
        val masterSpeed: Float,
        val playing: Boolean,
        val clipStartUs: Long,
        val sourceOffsetUs: Long,
        val clipSpeed: Float,
        val generation: Long
    )

    fun attachSurface(next: Surface) {
        if (released) return
        surface = next
        postToDecoder {
            if (fallback != null) {
                fallback?.attachSurface(next)
                return@postToDecoder
            }
            if (attachedSurface === next && initialized) return@postToDecoder
            try {
                ensureInitialized()
                val instance = checkNotNull(mpv)
                instance.attachSurface(next)
                attachedSurface = next
                loadIfNeeded()
                state = 1
            } catch (error: Throwable) {
                Log.e(TAG, "libmpv attach failed; switching to MediaCodec fallback", error)
                activateFallback(next)
            }
        }
    }

    fun detachSurface() {
        surface = null
        postToDecoder {
            fallback?.detachSurface()
            if (fallback == null) runCatching { mpv?.detachSurface() }
            attachedSurface = null
        }
    }

    fun decoderState(): Int {
        if (released) return 2
        return fallback?.decoderState() ?: state
    }

    fun updatePositionHint(targetMs: Long) {
        if (released) return
        pendingSeekMs = targetMs.coerceAtLeast(0L)
        postToDecoder { fallback?.updatePositionHint(pendingSeekMs) }
    }

    fun seekTo(targetMs: Long) {
        if (released) return
        pendingSeekMs = targetMs.coerceAtLeast(0L)
        postToDecoder {
            val backup = fallback
            if (backup != null) backup.seekTo(pendingSeekMs)
            else if (loaded) seekMpv(pendingSeekMs)
        }
    }

    fun setSpeed(value: Float) {
        if (released) return
        speed = value.coerceIn(0.1f, 4f)
        postToDecoder {
            val backup = fallback
            if (backup != null) backup.setSpeed(speed)
            else runCatching { mpv?.setPropertyDouble("speed", speed.toDouble()) }
        }
    }

    fun play() {
        if (released) return
        lastPlaying = true
        postToDecoder {
            val backup = fallback
            if (backup != null) backup.play()
            else runCatching { mpv?.setPropertyBoolean("pause", false) }
        }
    }

    fun pause() {
        if (released) return
        lastPlaying = false
        postToDecoder {
            val backup = fallback
            if (backup != null) backup.pause()
            else runCatching { mpv?.setPropertyBoolean("pause", true) }
        }
    }

    /**
     * The native timeline clock remains authoritative. Only the newest clock
     * snapshot is retained, so slow native property calls cannot build an
     * unbounded queue of stale synchronization tasks.
     */
    fun syncMasterClock(
        positionUs: Long,
        monotonicNs: Long,
        masterSpeed: Float,
        playing: Boolean,
        clipStartUs: Long,
        sourceOffsetUs: Long,
        clipSpeed: Float,
        generation: Long
    ) {
        if (released) return
        pendingClockSync.set(
            ClockSync(positionUs, monotonicNs, masterSpeed, playing,
                clipStartUs, sourceOffsetUs, clipSpeed, generation)
        )
        scheduleClockSync()
    }

    fun release() {
        if (released) return
        released = true
        state = 2
        runCatching {
            decoderExecutor.execute {
                fallback?.release()
                fallback = null
                val instance = mpv
                mpv = null
                initialized = false
                loaded = false
                if (instance != null) {
                    runCatching { instance.detachSurface() }
                    runCatching { instance.destroy() }
                }
                sourceDescriptor?.close()
                sourceDescriptor = null
                surface = null
                attachedSurface = null
                decoderExecutor.shutdown()
            }
        }.onFailure {
            decoderExecutor.shutdownNow()
        }
    }

    private fun postToDecoder(block: () -> Unit) {
        if (released) return
        runCatching {
            decoderExecutor.execute {
                if (!released) block()
            }
        }
    }

    private fun scheduleClockSync() {
        if (released || !clockSyncQueued.compareAndSet(false, true)) return
        runCatching {
            decoderExecutor.execute {
                try {
                    val latest = pendingClockSync.getAndSet(null)
                    if (latest != null && !released) applyClockSync(latest)
                } finally {
                    clockSyncQueued.set(false)
                    if (pendingClockSync.get() != null) scheduleClockSync()
                }
            }
        }.onFailure {
            clockSyncQueued.set(false)
        }
    }

    private fun applyClockSync(sync: ClockSync) {
        val backup = fallback
        if (backup != null) {
            backup.syncMasterClock(
                sync.positionUs, sync.monotonicNs, sync.masterSpeed, sync.playing,
                sync.clipStartUs, sync.sourceOffsetUs, sync.clipSpeed, sync.generation
            )
            return
        }
        val instance = mpv ?: return
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val safeMasterSpeed = sync.masterSpeed.coerceIn(0.1f, 4f)
        val safeClipSpeed = sync.clipSpeed.coerceIn(0.1f, 4f)
        val timelineNowUs = sync.positionUs.coerceAtLeast(0L) +
            if (sync.playing) (((nowNs - sync.monotonicNs).coerceAtLeast(0L) / 1_000.0) *
                safeMasterSpeed).toLong() else 0L
        val expectedSourceUs = sync.sourceOffsetUs.coerceAtLeast(0L) +
            (((timelineNowUs - sync.clipStartUs).coerceAtLeast(0L)) * safeClipSpeed).toLong()

        if (nowNs - lastSyncNs >= 100_000_000L) {
            lastSyncNs = nowNs
            runCatching {
                instance.setPropertyDouble("speed", (safeMasterSpeed * safeClipSpeed).coerceIn(0.1f, 4f).toDouble())
                instance.setPropertyBoolean("pause", !sync.playing)
            }
            val actualSeconds = instance.getPropertyDouble("time-pos")
            val expectedSeconds = expectedSourceUs / 1_000_000.0
            val generationChanged = masterGeneration != sync.generation
            val driftSeconds = actualSeconds?.let { it - expectedSeconds }
            val drifted = driftSeconds != null && abs(driftSeconds) > 0.18
            val correctionReady = nowNs - lastCorrectionNs >= 300_000_000L

            if (nowNs - lastDiagnosticsNs >= 1_000_000_000L) {
                lastDiagnosticsNs = nowNs
                val activeHwdec = runCatching { instance.getPropertyString("hwdec-current") }
                    .getOrNull() ?: "pending"
                val codecName = runCatching { instance.getPropertyString("video-codec-name") }
                    .getOrNull() ?: "unknown"
                val decodedFps = runCatching { instance.getPropertyDouble("estimated-vf-fps") }
                    .getOrNull()
                val driftMs = driftSeconds?.let { (it * 1_000.0).toLong() } ?: 0L
                Log.i(TAG, "decoder=$activeHwdec codec=$codecName fps=$decodedFps driftMs=$driftMs")
            }

            if ((generationChanged || (sync.playing && drifted && correctionReady)) && loaded) {
                Log.w(TAG, "Correcting preview clock drift: \${driftSeconds?.times(1_000.0)?.toLong() ?: 0L}ms")
                seekMpv((expectedSourceUs / 1_000L).coerceAtLeast(0L))
                lastCorrectionNs = nowNs
            }
            masterGeneration = sync.generation
        }
        lastPlaying = sync.playing
    }

    private fun ensureInitialized() {
        if (initialized) return
        val instance = MPVLib.create(appContext) ?: error("libmpv could not be created")
        try {
            val options = listOf(
                "config" to "no",
                "vo" to "gpu",
                "gpu-context" to "android",
                "hwdec" to "auto-safe",
                "audio" to "no",
                "sub" to "no",
                "osd-level" to "0",
                "terminal" to "no",
                "keep-open" to "yes",
                "pause" to "yes",
                "interpolation" to "no",
                "framedrop" to "vo",
                "video-sync" to "display-resample"
            )
            options.forEach { (key, value) ->
                check(instance.setOptionString(key, value) >= 0) {
                    "libmpv rejected option $key=$value"
                }
            }
            instance.init()
            mpv = instance
            initialized = true
        } catch (error: Throwable) {
            runCatching { instance.destroy() }
            throw error
        }
    }

    private fun loadIfNeeded() {
        if (loaded) return
        val instance = checkNotNull(mpv)
        val path = resolveSourcePath()
        instance.command(arrayOf("loadfile", path, "replace"))
        instance.setPropertyBoolean("pause", true)
        if (pendingSeekMs > 0L) seekMpv(pendingSeekMs)
        loaded = true
        Log.i(TAG, "FFmpeg/libavcodec playback loaded: $sourceUri")
    }

    private fun resolveSourcePath(): String {
        when (sourceUri.scheme?.lowercase()) {
            "file" -> return sourceUri.path ?: error("Video file path is empty")
            "content" -> {
                val descriptor = appContext.contentResolver.openFileDescriptor(sourceUri, "r")
                    ?: error("Android could not open the selected video")
                sourceDescriptor = descriptor
                // Keep the descriptor alive for the complete libmpv session.
                // /proc/self/fd works in-process and avoids copying imported media.
                return "/proc/self/fd/${descriptor.fd}"
            }
            else -> return sourceUri.toString()
        }
    }

    private fun seekMpv(targetMs: Long) {
        val seconds = targetMs.coerceAtLeast(0L) / 1000.0
        runCatching {
            mpv?.command(arrayOf("seek", seconds.toString(), "absolute+exact"))
        }.onFailure { Log.w(TAG, "FFmpeg seek failed", it) }
    }

    private fun activateFallback(outputSurface: Surface) {
        val old = mpv
        mpv = null
        initialized = false
        loaded = false
        runCatching { old?.detachSurface() }
        runCatching { old?.destroy() }
        sourceDescriptor?.close()
        sourceDescriptor = null
        val player = AndroidVideoPlayer(appContext, sourceUri)
        fallback = player
        player.attachSurface(outputSurface)
    }

    companion object {
        private const val TAG = "KineticFFmpeg"
    }
}
