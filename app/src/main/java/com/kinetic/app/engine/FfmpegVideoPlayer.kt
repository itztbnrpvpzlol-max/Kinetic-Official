package com.kinetic.app.engine

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Surface
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Primary video preview decoder. Bundled FFmpeg owns demuxing, timestamps,
 * seeking and decoding. It prefers MediaCodec surface output and uses FFmpeg's
 * software decoder plus swscale when a hardware decoder is unavailable.
 * AndroidVideoPlayer is only a last-resort platform compatibility fallback.
 */
class FfmpegVideoPlayer(
    context: Context,
    private val sourceUri: Uri,
    private val surfaceWidth: Int = 1280,
    private val surfaceHeight: Int = 720
) {
    private val appContext = context.applicationContext
    private val decoderExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "KineticFFmpegDecoder").apply { isDaemon = true }
    }
    private val pendingClockSync = AtomicReference<ClockSync?>(null)
    private val clockSyncQueued = AtomicBoolean(false)
    private val pendingSeekRequest = AtomicReference<Long?>(null)
    private val seekTaskQueued = AtomicBoolean(false)

    @Volatile private var nativeHandle = 0L
    @Volatile private var fallback: AndroidVideoPlayer? = null
    @Volatile private var released = false
    @Volatile private var state = 0
    @Volatile private var pendingSeekMs = 0L
    @Volatile private var speed = 1f
    @Volatile private var lastPlaying = false
    @Volatile private var receivedSurfaceFrame = false
    @Volatile private var attachedSurface: Surface? = null
    private var sourceDescriptor: ParcelFileDescriptor? = null
    private var sourcePath: String? = null

    private data class ClockSync(
        val positionUs: Long, val monotonicNs: Long, val masterSpeed: Float,
        val playing: Boolean, val clipStartUs: Long, val sourceOffsetUs: Long,
        val clipSpeed: Float, val generation: Long
    )

    fun attachSurface(next: Surface) {
        if (released) return
        postToDecoder {
            if (attachedSurface === next && (nativeHandle != 0L || fallback != null)) return@postToDecoder
            stopNativeDecoder()
            attachedSurface = next
            receivedSurfaceFrame = false

            val existingFallback = fallback
            if (existingFallback != null) {
                existingFallback.attachSurface(next)
                existingFallback.setSpeed(speed)
                existingFallback.updatePositionHint(pendingSeekMs)
                existingFallback.seekTo(pendingSeekMs)
                if (lastPlaying) existingFallback.play() else existingFallback.pause()
                return@postToDecoder
            }

            try {
                NativeFfmpegDecoder.ensureLoaded()
                val path = sourcePath ?: resolveSourcePath().also { sourcePath = it }
                val handle = NativeFfmpegDecoder.nativeCreate(
                    path, next, surfaceWidth.coerceAtLeast(16), surfaceHeight.coerceAtLeast(16)
                )
                check(handle != 0L) { "FFmpeg native decoder could not be created" }
                nativeHandle = handle
                state = 0
                NativeFfmpegDecoder.nativeSetSpeed(handle, speed)
                if (pendingSeekMs > 0L) NativeFfmpegDecoder.nativeSeek(handle, pendingSeekMs * 1_000L)
                if (lastPlaying) NativeFfmpegDecoder.nativePlay(handle)
                else NativeFfmpegDecoder.nativePause(handle)
                pendingClockSync.getAndSet(null)?.let(::applyClockSync)
            decoderExecutor.schedule({
                if (!released && attachedSurface === next && !receivedSurfaceFrame && fallback == null) {
                    val handleAtTimeout = nativeHandle
                    val reason = if (handleAtTimeout != 0L) {
                        runCatching { NativeFfmpegDecoder.nativeError(handleAtTimeout) }
                            .getOrDefault("FFmpeg did not present a frame")
                    } else {
                        "FFmpeg did not present a frame"
                    }
                    Log.w(TAG, "$reason; switching to Android MediaCodec compatibility fallback")
                    activateFallback(next)
                }
            }, FIRST_FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                Log.i(TAG, "Direct FFmpeg decoder attached to SurfaceTexture: $sourceUri")
            } catch (error: Throwable) {
                Log.e(TAG, "Direct FFmpeg decoder could not start; using Android compatibility fallback", error)
                activateFallback(next)
            }
        }
    }

    fun detachSurface() {
        postToDecoder {
            fallback?.detachSurface()
            stopNativeDecoder()
            attachedSurface = null
            receivedSurfaceFrame = false
        }
    }

    fun markFrameAvailable() {
        receivedSurfaceFrame = true
        state = 1
    }

    fun hasReceivedFrame(): Boolean = receivedSurfaceFrame

    fun decoderState(): Int {
        if (released) return 2
        return fallback?.decoderState() ?: state
    }

    fun updatePositionHint(targetMs: Long) {
        if (released) return
        pendingSeekMs = targetMs.coerceAtLeast(0L)
        val backup = fallback ?: return
        postToDecoder { if (fallback === backup) backup.updatePositionHint(pendingSeekMs) }
    }

    fun seekTo(targetMs: Long) {
        if (released) return
        pendingSeekMs = targetMs.coerceAtLeast(0L)
        pendingSeekRequest.set(pendingSeekMs)
        scheduleSeek()
    }

    fun setSpeed(value: Float) {
        if (released) return
        speed = value.coerceIn(0.1f, 4f)
        postToDecoder {
            val backup = fallback
            if (backup != null) backup.setSpeed(speed)
            else if (nativeHandle != 0L) NativeFfmpegDecoder.nativeSetSpeed(nativeHandle, speed)
        }
    }

    fun play() {
        if (released) return
        lastPlaying = true
        postToDecoder {
            val backup = fallback
            if (backup != null) backup.play()
            else if (nativeHandle != 0L) NativeFfmpegDecoder.nativePlay(nativeHandle)
        }
    }

    fun pause() {
        if (released) return
        lastPlaying = false
        postToDecoder {
            val backup = fallback
            if (backup != null) backup.pause()
            else if (nativeHandle != 0L) NativeFfmpegDecoder.nativePause(nativeHandle)
        }
    }

    fun syncMasterClock(
        positionUs: Long, monotonicNs: Long, masterSpeed: Float, playing: Boolean,
        clipStartUs: Long, sourceOffsetUs: Long, clipSpeed: Float, generation: Long
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
                stopNativeDecoder()
                sourceDescriptor?.close()
                sourceDescriptor = null
                sourcePath = null
                decoderExecutor.shutdown()
            }
        }.onFailure { decoderExecutor.shutdownNow() }
    }

    private fun postToDecoder(block: () -> Unit) {
        if (released) return
        runCatching { decoderExecutor.execute { if (!released) block() } }
    }

    private fun scheduleSeek() {
        if (released || !seekTaskQueued.compareAndSet(false, true)) return
        runCatching {
            decoderExecutor.execute {
                try {
                    while (!released) {
                        val target = pendingSeekRequest.getAndSet(null) ?: break
                        val backup = fallback
                        if (backup != null) backup.seekTo(target)
                        else if (nativeHandle != 0L) {
                            NativeFfmpegDecoder.nativeSeek(nativeHandle, target * 1_000L)
                        }
                    }
                } finally {
                    seekTaskQueued.set(false)
                    if (pendingSeekRequest.get() != null) scheduleSeek()
                }
            }
        }.onFailure { seekTaskQueued.set(false) }
    }

    private fun scheduleClockSync() {
        if (released || !clockSyncQueued.compareAndSet(false, true)) return
        runCatching {
            decoderExecutor.execute {
                try {
                    val sync = pendingClockSync.get()
                    if (sync != null && (fallback != null || nativeHandle != 0L) &&
                        pendingClockSync.compareAndSet(sync, null)
                    ) applyClockSync(sync)
                } finally {
                    clockSyncQueued.set(false)
                    if (pendingClockSync.get() != null && (fallback != null || nativeHandle != 0L)) scheduleClockSync()
                }
            }
        }.onFailure { clockSyncQueued.set(false) }
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
        val handle = nativeHandle
        if (handle == 0L) return
        NativeFfmpegDecoder.nativeSetClock(
            handle, sync.positionUs, sync.monotonicNs, sync.masterSpeed.toDouble(),
            sync.playing, sync.clipStartUs, sync.sourceOffsetUs,
            sync.clipSpeed.toDouble(), sync.generation
        )
    }


    private fun activateFallback(outputSurface: Surface) {
        if (released || fallback != null) return
        stopNativeDecoder()
        val player = AndroidVideoPlayer(appContext, sourceUri)
        fallback = player
        state = 0
        player.attachSurface(outputSurface)
        player.setSpeed(speed)
        player.updatePositionHint(pendingSeekMs)
        player.seekTo(pendingSeekRequest.getAndSet(null) ?: pendingSeekMs)
        if (lastPlaying) player.play() else player.pause()
    }

    private fun stopNativeDecoder() {
        val handle = nativeHandle
        nativeHandle = 0L
        if (handle != 0L) {
            runCatching { NativeFfmpegDecoder.nativeRelease(handle) }
                .onFailure { Log.w(TAG, "FFmpeg native decoder release failed", it) }
        }
    }

    private fun resolveSourcePath(): String = when (sourceUri.scheme?.lowercase()) {
        "file" -> sourceUri.path ?: error("Video file path is empty")
        "content" -> {
            val descriptor = appContext.contentResolver.openFileDescriptor(sourceUri, "r")
                ?: error("Android could not open the selected video")
            sourceDescriptor = descriptor
            "/proc/self/fd/" + descriptor.fd
        }
        else -> sourceUri.toString()
    }

    companion object {
        private const val TAG = "KineticFFmpeg"
        private const val FIRST_FRAME_TIMEOUT_SECONDS = 10L
    }
}
