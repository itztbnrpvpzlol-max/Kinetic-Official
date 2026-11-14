package com.kinetic.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RectF
import android.os.Handler
import android.os.HandlerThread
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.ui.graphics.toArgb
import com.kinetic.app.model.LayerType

internal data class TimelineClipVisual(
    val id: String,
    val bounds: RectF,
    val type: LayerType,
    val name: String,
    val visible: Boolean,
    val selected: Boolean,
    val thumbnails: List<Bitmap>,
    val waveform: FloatArray?
)

internal data class TimelineKeyframeVisual(
    val id: String,
    val bounds: RectF,
    val keyTimes: List<Float>,
    val currentTime: Float,
    val frameDuration: Float,
    val previewIndex: Int,
    val previewTime: Float
)

/** Thread-safe model shared by the Compose hit targets and the lane surface. */
internal class TimelineLaneSurfaceState {
    private val lock = Any()
    private val clips = LinkedHashMap<String, TimelineClipVisual>()
    private val keyframes = LinkedHashMap<String, TimelineKeyframeVisual>()
    @Volatile private var invalidate: (() -> Unit)? = null

    fun attach(callback: (() -> Unit)?) {
        invalidate = callback
        callback?.invoke()
    }

    fun updateClip(value: TimelineClipVisual) {
        synchronized(lock) {
            clips[value.id] = value.copy(bounds = RectF(value.bounds))
        }
        invalidate?.invoke()
    }

    fun removeClip(id: String) {
        synchronized(lock) { clips.remove(id) }
        invalidate?.invoke()
    }

    fun updateKeyframes(value: TimelineKeyframeVisual) {
        synchronized(lock) {
            keyframes[value.id] = value.copy(bounds = RectF(value.bounds), keyTimes = value.keyTimes.toList())
        }
        invalidate?.invoke()
    }

    fun removeKeyframes(id: String) {
        synchronized(lock) { keyframes.remove(id) }
        invalidate?.invoke()
    }

    fun snapshot(): Pair<List<TimelineClipVisual>, List<TimelineKeyframeVisual>> =
        synchronized(lock) { clips.values.toList() to keyframes.values.toList() }
}

/**
 * One shared transparent Android surface renders all visible timeline lane
 * artwork. Compose still owns the gesture targets and editable controls.
 */
internal class TimelineLaneSurfaceView(
    context: Context,
    private val state: TimelineLaneSurfaceState
) : SurfaceView(context), SurfaceHolder.Callback {
    private val density = resources.displayMetrics.density
    private val thread = HandlerThread("KineticTimelineLanes").apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile private var ready = false
    @Volatile private var disposed = false
    @Volatile private var leadingPadPx = 0f
    @Volatile private var pixelsPerSecond = 1f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val thumbnailPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = 118 }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = KColors.ClipText.toArgb()
        textSize = 12f * density
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = KColors.ClipText.toArgb()
        textSize = 10f * density
        typeface = android.graphics.Typeface.create("sans-serif-bold", android.graphics.Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val diamond = Path()

    private val drawTask = Runnable { drawSurface() }

    init {
        setZOrderMediaOverlay(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        holder.addCallback(this)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        state.attach { requestDraw() }
    }

    fun updateGeometry(leading: Float, pps: Float) {
        leadingPadPx = leading
        pixelsPerSecond = pps.coerceAtLeast(0.001f)
        requestDraw()
    }

    private fun requestDraw() {
        if (!disposed && ready) {
            handler.removeCallbacks(drawTask)
            handler.post(drawTask)
        }
    }

    private fun drawSurface() {
        if (disposed || !ready) return
        val canvas = try { holder.lockCanvas() } catch (_: Throwable) { null } ?: return
        try {
            canvas.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            val location = IntArray(2)
            getLocationInWindow(location)
            val (clipItems, keyItems) = state.snapshot()
            clipItems.sortedBy { it.bounds.top }.forEach { item ->
                drawClip(canvas, item, location[0].toFloat(), location[1].toFloat())
            }
            keyItems.sortedBy { it.bounds.top }.forEach { item ->
                drawKeyframes(canvas, item, location[0].toFloat(), location[1].toFloat())
            }
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun drawClip(canvas: Canvas, item: TimelineClipVisual, viewX: Float, viewY: Float) {
        val rect = RectF(item.bounds).apply { offset(-viewX, -viewY) }
        if (rect.right <= 0f || rect.left >= width || rect.bottom <= 0f || rect.top >= height) return
        val radius = 8f * density
        val save = canvas.save()
        canvas.clipRect(rect)
        val baseColor = clipColor(item.type).toArgb()
        fill.color = if (item.visible) baseColor else baseColor and 0x66FFFFFF
        canvas.drawRoundRect(rect, radius, radius, fill)

        if (item.thumbnails.isNotEmpty()) {
            val segmentWidth = rect.width() / item.thumbnails.size.coerceAtLeast(1)
            item.thumbnails.forEachIndexed { index, bitmap ->
                if (!bitmap.isRecycled) {
                    val left = rect.left + index * segmentWidth
                    val target = RectF(left, rect.top, left + segmentWidth + 1f, rect.bottom)
                    canvas.drawBitmap(bitmap, null, target, thumbnailPaint)
                }
            }
        }

        if (item.type == LayerType.AUDIO && item.waveform != null) {
            val peaks = item.waveform
            val count = (rect.width() / (5f * density)).toInt().coerceIn(1, peaks.size)
            val step = rect.width() / count
            stroke.color = 0xFF8D72FF.toInt()
            stroke.strokeWidth = 2f * density
            for (index in 0 until count) {
                val x = rect.left + (index + 0.5f) * step
                val h = rect.height() * peaks[index.coerceAtMost(peaks.lastIndex)].coerceIn(0f, 1f)
                canvas.drawLine(x, rect.centerY() - h / 2f, x, rect.centerY() + h / 2f, stroke)
            }
        }

        if (item.type == LayerType.ADJUSTMENT || item.type == LayerType.NULL) {
            stroke.color = KColors.Muted.toArgb()
            stroke.strokeWidth = density
            stroke.pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f * density, 10f * density), 0f)
            canvas.drawRoundRect(rect, radius, radius, stroke)
            stroke.pathEffect = null
        }

        if (item.selected) {
            fill.color = KColors.Accent.toArgb()
            canvas.drawRect(rect.left, rect.top, rect.left + 16f * density, rect.bottom, fill)
            canvas.drawRect(rect.right - 16f * density, rect.top, rect.right, rect.bottom, fill)
            stroke.color = KColors.Accent.toArgb()
            stroke.strokeWidth = 1.5f * density
            canvas.drawRoundRect(rect, radius, radius, stroke)
        }

        val letter = when (item.type) {
            LayerType.TEXT -> "T"
            LayerType.IMAGE -> "I"
            LayerType.VIDEO -> "V"
            LayerType.AUDIO -> "A"
            LayerType.SHAPE -> "S"
            LayerType.ADJUSTMENT -> "FX"
            LayerType.NULL -> "N"
        }
        iconPaint.color = KColors.ClipText.toArgb()
        canvas.drawText(letter, rect.left + 19f * density, rect.centerY() + 3.5f * density, iconPaint)
        labelPaint.color = KColors.ClipText.toArgb()
        canvas.drawText(item.name, rect.left + 31f * density, rect.centerY() + 4f * density, labelPaint)
        canvas.restoreToCount(save)
    }

    private fun drawKeyframes(canvas: Canvas, item: TimelineKeyframeVisual, viewX: Float, viewY: Float) {
        val rect = RectF(item.bounds).apply { offset(-viewX, -viewY) }
        if (rect.right <= 0f || rect.left >= width || rect.bottom <= 0f || rect.top >= height) return
        val centerY = rect.centerY()
        val radius = 5f * density
        item.keyTimes.forEachIndexed { index, originalTime ->
            val time = if (index == item.previewIndex) item.previewTime else originalTime
            val x = rect.left + leadingPadPx + time * pixelsPerSecond
            if (x < -radius || x > width + radius) return@forEachIndexed
            diamond.reset()
            diamond.moveTo(x, centerY - radius)
            diamond.lineTo(x + radius, centerY)
            diamond.lineTo(x, centerY + radius)
            diamond.lineTo(x - radius, centerY)
            diamond.close()
            fill.color = if (kotlin.math.abs(item.currentTime - originalTime) <= item.frameDuration / 2f)
                KColors.Accent.toArgb() else KColors.ClipText.toArgb()
            canvas.drawPath(diamond, fill)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        ready = true
        requestDraw()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        requestDraw()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        ready = false
        handler.removeCallbacks(drawTask)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (disposed) return
        disposed = true
        ready = false
        state.attach(null)
        holder.removeCallback(this)
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }
}
