package com.kinetic.app.ui

import android.content.Context
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.os.Handler
import android.os.HandlerThread
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.kinetic.app.engine.NativePlaybackEngine

/**
 * Transparent, independently rendered timeline needle.
 *
 * The timeline's editable rows remain Compose views, but the time-critical
 * playhead is composited by its own SurfaceView and reads the same monotonic
 * playback clock that drives video output.
 */
internal class TimelinePlayheadSurfaceView(
    context: Context,
    private val playback: NativePlaybackEngine
) : SurfaceView(context), SurfaceHolder.Callback {

    private val density = resources.displayMetrics.density
    private val renderThread = HandlerThread("KineticTimelineNeedle").apply { start() }
    private val renderHandler = Handler(renderThread.looper)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.SQUARE
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.FILL
    }
    private val headPath = Path()

    @Volatile private var surfaceReady = false
    @Volatile private var disposed = false
    @Volatile private var transportPlaying = false
    @Volatile private var fallbackTime = 0f
    @Volatile private var leftColumnPx = 0f
    @Volatile private var leadingPadPx = 0f
    @Volatile private var pixelsPerSecond = 1f
    @Volatile private var scrollOffsetPx = 0f

    private val renderFrame = object : Runnable {
        override fun run() {
            if (disposed || !surfaceReady) return
            drawNeedle()
            if (transportPlaying && surfaceReady && !disposed) {
                renderHandler.postDelayed(this, 16L)
            }
        }
    }

    init {
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        holder.addCallback(this)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun updateGeometry(leftPx: Float, leadingPx: Float, pxPerSecond: Float) {
        val safePps = pxPerSecond.coerceAtLeast(0.001f)
        if (leftColumnPx == leftPx && leadingPadPx == leadingPx && pixelsPerSecond == safePps) return
        leftColumnPx = leftPx
        leadingPadPx = leadingPx
        pixelsPerSecond = safePps
        requestRender()
    }

    fun updateScrollOffset(scrollPx: Float) {
        if (kotlin.math.abs(scrollOffsetPx - scrollPx) < 0.25f) return
        scrollOffsetPx = scrollPx
        requestRender()
    }

    fun updateTransport(playing: Boolean, time: Float) {
        val wasPlaying = transportPlaying
        val nextTime = time.coerceAtLeast(0f)
        transportPlaying = playing
        fallbackTime = nextTime
        if (playing) {
            if (!wasPlaying) requestRender()
        } else if (wasPlaying || kotlin.math.abs(fallbackTime - nextTime) > 0.0001f) {
            requestRender()
        } else if (!wasPlaying) {
            requestRender()
        }
    }

    private fun requestRender() {
        if (!disposed && surfaceReady) {
            renderHandler.removeCallbacks(renderFrame)
            renderHandler.post(renderFrame)
        }
    }

    private fun playbackTime(): Float {
        if (!transportPlaying) return fallbackTime
        val clock = playback.clockSnapshot()
        if (!clock.playing) return fallbackTime
        val elapsed = (System.nanoTime() - clock.monotonicNs).coerceAtLeast(0L) / 1_000_000_000.0
        return (clock.positionUs / 1_000_000.0 + elapsed * clock.speed).toFloat().coerceAtLeast(0f)
    }

    private fun drawNeedle() {
        val canvas = try {
            holder.lockCanvas()
        } catch (_: Throwable) {
            null
        } ?: return

        try {
            canvas.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            val time = playbackTime()
            val x = leftColumnPx + leadingPadPx + time * pixelsPerSecond - scrollOffsetPx
            if (x >= leftColumnPx - density && x <= width + density) {
                canvas.drawLine(x, 14f * density, x, height.toFloat(), linePaint)
                headPath.reset()
                headPath.moveTo(x - 7f * density, 4f * density)
                headPath.lineTo(x + 7f * density, 4f * density)
                headPath.lineTo(x, 17f * density)
                headPath.close()
                canvas.drawPath(headPath, headPaint)
            }
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        requestRender()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        requestRender()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        renderHandler.removeCallbacks(renderFrame)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (disposed) return
        disposed = true
        surfaceReady = false
        holder.removeCallback(this)
        renderHandler.removeCallbacksAndMessages(null)
        renderThread.quitSafely()
    }
}
