package com.kinetic.app.engine

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.Surface
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
    private var mpv: MPVLib? = null
    private var sourceDescriptor: ParcelFileDescriptor? = null
    private var surface: Surface? = null
    private var fallback: AndroidVideoPlayer? = null
    private var released = false
    private var initialized = false
    private var loaded = false
    private var pendingSeekMs = 0L
    private var speed = 1f
    private var masterGeneration = Long.MIN_VALUE
    private var lastSyncNs = 0L
    private var lastCorrectionNs = 0L
    private var lastDiagnosticsNs = 0L
    private var lastPlaying = false

    @Synchronized
    fun attachSurface(next: Surface) {
        if (released) return
        if (fallback != null) {
            fallback?.attachSurface(next)
            surface = next
            return
        }
        if (surface === next && initialized) return
        surface = next
        try {
            ensureInitialized()
            val instance = checkNotNull(mpv)
            instance.attachSurface(next)
            loadIfNeeded()
        } catch (error: Throwable) {
            Log.e(TAG, "libmpv attach failed; switching to MediaCodec fallback", error)
            activateFallback(next)
        }
    }

    @Synchronized
    fun detachSurface() {
        if (fallback != null) {
            fallback?.detachSurface()
            surface = null
            return
        }
        runCatching { mpv?.detachSurface() }
        surface = null
    }

    @Synchronized
    fun decoderState(): Int {
        if (released) return 2
        fallback?.let { return it.decoderState() }
        return if (initialized && loaded) 1 else 0
    }

    @Synchronized
    fun updatePositionHint(targetMs: Long) {
        if (released) return
        pendingSeekMs = targetMs.coerceAtLeast(0L)
        fallback?.updatePositionHint(targetMs)
    }

    @Synchronized
    fun seekTo(targetMs: Long) {
        if (released) return
        pendingSeekMs = targetMs.coerceAtLeast(0L)
        fallback?.let { it.seekTo(targetMs); return }
        if (loaded) seekMpv(pendingSeekMs)
    }

    @Synchronized
    fun setSpeed(value: Float) {
        if (released) return
        speed = value.coerceIn(0.1f, 4f)
        fallback?.let { it.setSpeed(speed); return }
        runCatching { mpv?.setPropertyDouble("speed", speed.toDouble()) }
    }

    @Synchronized
    fun play() {
        if (released) return
        fallback?.let { it.play(); return }
        lastPlaying = true
        runCatching { mpv?.setPropertyBoolean("pause", false) }
    }

    @Synchronized
    fun pause() {
        if (released) return
        fallback?.let { it.pause(); return }
        lastPlaying = false
        runCatching { mpv?.setPropertyBoolean("pause", true) }
    }

    /**
     * Kinetic's native timeline clock remains authoritative. libmpv runs its
     * own frame clock between corrections; large drift is corrected with an
     * exact seek rather than continuously seeking every UI tick.
     */
    @Synchronized
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
        fallback?.let {
            it.syncMasterClock(positionUs, monotonicNs, masterSpeed, playing,
                clipStartUs, sourceOffsetUs, clipSpeed, generation)
            return
        }
        val instance = mpv ?: return
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val safeMasterSpeed = masterSpeed.coerceIn(0.1f, 4f)
        val safeClipSpeed = clipSpeed.coerceIn(0.1f, 4f)
        val timelineNowUs = positionUs.coerceAtLeast(0L) +
            if (playing) (((nowNs - monotonicNs).coerceAtLeast(0L) / 1_000.0) *
                safeMasterSpeed).toLong() else 0L
        val expectedSourceUs = sourceOffsetUs.coerceAtLeast(0L) +
            (((timelineNowUs - clipStartUs).coerceAtLeast(0L)) * safeClipSpeed).toLong()

        if (nowNs - lastSyncNs >= 100_000_000L) {
            lastSyncNs = nowNs
            runCatching {
                instance.setPropertyDouble("speed", (safeMasterSpeed * safeClipSpeed).coerceIn(0.1f, 4f).toDouble())
                instance.setPropertyBoolean("pause", !playing)
            }
            val actualSeconds = instance.getPropertyDouble("time-pos")
            val expectedSeconds = expectedSourceUs / 1_000_000.0
            val generationChanged = masterGeneration != generation
            val driftSeconds = actualSeconds?.let { it - expectedSeconds }
            val drifted = driftSeconds != null && abs(driftSeconds) > 0.18
            val correctionReady = nowNs - lastCorrectionNs >= 300_000_000L

            // Report the decoder actually selected by libmpv, not merely the
            // requested "auto-safe" setting. This makes software fallback and
            // frame-rate regressions visible in device logs.
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

            if ((generationChanged || (playing && drifted && correctionReady)) && loaded) {
                Log.w(TAG, "Correcting preview clock drift: ${driftSeconds?.times(1_000.0)?.toLong() ?: 0L}ms")
                seekMpv((expectedSourceUs / 1_000L).coerceAtLeast(0L))
                lastCorrectionNs = nowNs
            }
            masterGeneration = generation
        }
        lastPlaying = playing
    }

    @Synchronized
    fun release() {
        if (released) return
        released = true
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
