package com.kinetic.app.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max

/**
 * Android-framework video decoder.
 *
 * MediaExtractor and MediaCodec run on one dedicated worker thread and send
 * decoded frames directly to the renderer's Surface. The C++ library remains
 * responsible for composition/rendering and the authoritative playback clock.
 */
class AndroidVideoPlayer(
    context: Context,
    private val sourceUri: Uri
) {
    private val appContext = context.applicationContext
    private val lock = java.lang.Object()
    private val state = AtomicInteger(0) // 0=starting/detached, 1=ready, -1=failed, 2=released

    private var worker: Thread? = null
    private var surface: Surface? = null
    private var stopping = false
    private var released = false
    private var playing = false
    private var stepFramePending = false
    private var pendingSeekUs: Long? = 0L
    private var positionMs = 0L
    private var speed = 1f

    private var hasMasterClock = false
    private var masterPositionUs = 0L
    private var masterAnchorNs = 0L
    private var masterSpeed = 1f
    private var clipStartUs = 0L
    private var sourceOffsetUs = 0L
    private var clipSpeed = 1f
    private var masterGeneration = Long.MIN_VALUE

    private var lastOutputPtsUs = -1L
    private var localAnchorPtsUs = -1L
    private var localAnchorNs = 0L

    fun attachSurface(next: Surface) {
        stopWorker()
        synchronized(lock) {
            if (released) return
            if (surface === next && worker?.isAlive == true) return
            surface = next
            stopping = false
            pendingSeekUs = positionMs * 1_000L
            stepFramePending = true
            lastOutputPtsUs = -1L
            state.set(0)
            val thread = Thread({ decodeLoop(next) }, "KineticAndroidVideoDecoder")
            thread.isDaemon = true
            worker = thread
            thread.start()
        }
    }

    fun detachSurface() {
        stopWorker()
        synchronized(lock) {
            surface = null
            state.set(if (released) 2 else 0)
        }
    }

    fun decoderState(): Int = synchronized(lock) {
        if (released) 2 else if (surface == null) 0 else state.get()
    }

    fun updatePositionHint(targetMs: Long) = synchronized(lock) {
        if (!released) positionMs = targetMs.coerceAtLeast(0L)
    }

    fun seekTo(targetMs: Long) = synchronized(lock) {
        if (released) return@synchronized
        positionMs = targetMs.coerceAtLeast(0L)
        pendingSeekUs = positionMs * 1_000L
        stepFramePending = true
        lock.notifyAll()
    }

    fun syncMasterClock(
        positionUs: Long,
        monotonicNs: Long,
        masterSpeed: Float,
        playing: Boolean,
        clipStartUs: Long,
        sourceOffsetUs: Long,
        clipSpeed: Float,
        generation: Long
    ) = synchronized(lock) {
        if (released) return@synchronized
        this.hasMasterClock = true
        this.masterPositionUs = positionUs.coerceAtLeast(0L)
        this.masterAnchorNs = monotonicNs.coerceAtLeast(0L)
        this.masterSpeed = masterSpeed.coerceIn(0.1f, 4f)
        this.playing = playing
        this.clipStartUs = clipStartUs
        this.sourceOffsetUs = sourceOffsetUs.coerceAtLeast(0L)
        this.clipSpeed = clipSpeed.coerceIn(0.1f, 4f)

        if (masterGeneration != generation) {
            val expectedSourceUs = this.sourceOffsetUs +
                (((this.masterPositionUs - this.clipStartUs).coerceAtLeast(0L)) *
                    this.clipSpeed).toLong()
            if (lastOutputPtsUs >= 0L && abs(expectedSourceUs - lastOutputPtsUs) > 150_000L) {
                pendingSeekUs = expectedSourceUs
                stepFramePending = true
            }
            masterGeneration = generation
        }
        lock.notifyAll()
    }

    fun setSpeed(value: Float) = synchronized(lock) {
        if (released) return@synchronized
        speed = value.coerceIn(0.1f, 4f)
        if (!hasMasterClock) {
            localAnchorPtsUs = lastOutputPtsUs
            localAnchorNs = SystemClock.elapsedRealtimeNanos()
        }
    }

    fun play() = synchronized(lock) {
        if (released) return@synchronized
        playing = true
        if (!hasMasterClock) {
            localAnchorPtsUs = lastOutputPtsUs
            localAnchorNs = SystemClock.elapsedRealtimeNanos()
        }
        lock.notifyAll()
    }

    fun pause() = synchronized(lock) {
        if (released) return@synchronized
        playing = false
        stepFramePending = false
        lock.notifyAll()
    }

    fun release() {
        synchronized(lock) {
            if (released) return
            released = true
        }
        stopWorker()
        synchronized(lock) {
            surface = null
            state.set(2)
        }
    }

    private fun stopWorker() {
        val old = synchronized(lock) {
            stopping = true
            lock.notifyAll()
            worker.also { worker = null }
        }
        if (old != null && old !== Thread.currentThread()) {
            runCatching {
                old.join(2_000L)
                if (old.isAlive) {
                    old.interrupt()
                    old.join(1_000L)
                }
            }
        }
    }

    private fun decodeLoop(outputSurface: Surface) {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(appContext, sourceUri, null)

            var videoTrack = -1
            var videoFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) {
                    videoTrack = index
                    videoFormat = format
                    break
                }
            }
            check(videoTrack >= 0 && videoFormat != null) { "No video track found" }
            extractor.selectTrack(videoTrack)

            val format = videoFormat!!
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: error("Video track has no MIME type")
            val frameRate = runCatching {
                format.getInteger(MediaFormat.KEY_FRAME_RATE).coerceAtLeast(1)
            }.getOrDefault(30)
            val frameDurationNs = (1_000_000_000L / frameRate).coerceIn(8_000_000L, 100_000_000L)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, outputSurface, null, 0)
            codec.start()

            synchronized(lock) {
                if (stopping || released) return
                state.set(1)
            }
            Log.i("KineticVideo", "Android decoder ready: " + mime)

            var inputEos = false
            var outputEos = false
            var dropBeforeUs = -1L

            while (true) {
                var seekTargetUs = -1L
                var shouldDecode = false
                synchronized(lock) {
                    if (stopping || released || surface !== outputSurface) break
                    seekTargetUs = pendingSeekUs ?: -1L
                    pendingSeekUs = null
                    shouldDecode = playing || stepFramePending
                    if (!shouldDecode && seekTargetUs < 0L) {
                        lock.wait(20L)
                        if (stopping || released || surface !== outputSurface) break
                        seekTargetUs = pendingSeekUs ?: -1L
                        pendingSeekUs = null
                        shouldDecode = playing || stepFramePending
                    }
                }

                if (seekTargetUs >= 0L) {
                    extractor.seekTo(seekTargetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    codec.flush()
                    inputEos = false
                    outputEos = false
                    dropBeforeUs = seekTargetUs
                }
                if (!shouldDecode) continue
                if (outputEos) {
                    synchronized(lock) {
                        playing = false
                        stepFramePending = false
                    }
                    continue
                }

                if (!inputEos) {
                    val inputIndex = codec.dequeueInputBuffer(0L)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        val sampleSize = if (inputBuffer != null) {
                            inputBuffer.clear()
                            extractor.readSampleData(inputBuffer, 0)
                        } else -1
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inputIndex, 0, 0, 0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEos = true
                        } else {
                            val ptsUs = extractor.sampleTime
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, ptsUs, 0)
                            extractor.advance()
                        }
                    }
                }

                val info = MediaCodec.BufferInfo()
                when (val outputIndex = codec.dequeueOutputBuffer(info, 5_000L)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> if (outputIndex >= 0) {
                        val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        val canRender = synchronized(lock) {
                            !stopping && !released && (playing || stepFramePending) && pendingSeekUs == null
                        }
                        var render = !eos && canRender
                        if (render && dropBeforeUs >= 0L) {
                            if (info.presentationTimeUs < dropBeforeUs) {
                                render = false
                            } else {
                                dropBeforeUs = -1L
                            }
                        }

                        val nowNs = SystemClock.elapsedRealtimeNanos()
                        val targetNs = if (render) targetPresentationNs(info.presentationTimeUs, nowNs) else nowNs
                        if (render && isPlayingNow() && targetNs < nowNs - frameDurationNs) {
                            render = false
                        }

                        if (render) {
                            codec.releaseOutputBuffer(outputIndex, max(targetNs, nowNs))
                            synchronized(lock) {
                                lastOutputPtsUs = info.presentationTimeUs
                                if (!playing) stepFramePending = false
                            }
                        } else {
                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                        if (eos) outputEos = true
                    }
                }
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Throwable) {
            synchronized(lock) {
                if (!stopping && !released) state.set(-1)
            }
            Log.e("KineticVideo", "Android MediaCodec decoder failed", error)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor?.release() }
            synchronized(lock) {
                if (worker === Thread.currentThread()) worker = null
                if (!released && state.get() != -1) state.set(0)
            }
        }
    }

    private fun targetPresentationNs(ptsUs: Long, nowNs: Long): Long = synchronized(lock) {
        if (hasMasterClock && playing) {
            val timelineUs = clipStartUs +
                ((ptsUs - sourceOffsetUs).toDouble() / clipSpeed.toDouble()).toLong()
            masterAnchorNs + ((timelineUs - masterPositionUs) * 1_000.0 / masterSpeed).toLong()
        } else if (playing) {
            if (localAnchorPtsUs < 0L) {
                localAnchorPtsUs = ptsUs
                localAnchorNs = nowNs
            }
            localAnchorNs + ((ptsUs - localAnchorPtsUs) * 1_000.0 / speed).toLong()
        } else {
            nowNs
        }
    }

    private fun isPlayingNow(): Boolean = synchronized(lock) { playing }
}
