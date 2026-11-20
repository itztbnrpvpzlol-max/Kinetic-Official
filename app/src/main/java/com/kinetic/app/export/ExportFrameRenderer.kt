package com.kinetic.app.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.kinetic.app.model.CompSettings
import com.kinetic.app.data.SequentialVideoFrameDecoder
import com.kinetic.app.engine.EffectStackRunner
import com.kinetic.app.model.COMP_H
import com.kinetic.app.model.COMP_W
import com.kinetic.app.model.Layer
import com.kinetic.app.model.LayerType
import com.kinetic.app.model.PropId
import com.kinetic.app.model.layerMatrix
import com.kinetic.app.model.sourceElapsedAt
import com.kinetic.app.ui.CompositeSnapshot
import com.kinetic.app.ui.LayerRaster
import com.kinetic.app.ui.buildMaskContour
import kotlin.math.max
import kotlin.math.min

/**
 * Draws one composition frame at export resolution: every layer with its transform, mask, blend
 * mode and EffectCraft effects, then adjustment layers over everything beneath them. Effects run
 * in layer space, so the result matches the preview at any export size.
 */
internal class ExportFrameRenderer(
    private val context: Context,
    layers: List<Layer>,
    private val comp: CompSettings,
    val width: Int,
    val height: Int,
    private val fps: Int
) : AutoCloseable {
    /** Back to front. */
    private val ordered = layers.asReversed()
    private val byId = layers.associateBy { it.id }
    val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val sx = width / COMP_W
    private val sy = height / COMP_H
    private val decoders = HashMap<String, SequentialVideoFrameDecoder>()
    private val retrievers = HashMap<String, MediaMetadataRetriever>()
    private val images = HashMap<String, Bitmap>()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val grayscale = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })

    /** Draws [time] (composition seconds) and returns the shared frame bitmap. */
    fun render(time: Float): Bitmap {
        canvas.drawColor(comp.background.toInt(), PorterDuff.Mode.SRC)
        for (layer in ordered) {
            if (!layer.visible || !layer.activeAt(time)) continue
            when (layer.type) {
                LayerType.AUDIO, LayerType.NULL -> Unit
                LayerType.ADJUSTMENT -> drawAdjustment(layer, time)
                else -> if (layer.motionBlur && comp.shutterAngle > 0f) {
                    drawMotionBlurLayer(layer, time)
                } else {
                    drawLayer(layer, time)
                }
            }
        }
        return bitmap
    }

    private fun opacityOf(layer: Layer, time: Float): Int =
        (layer.p(PropId.OPACITY).valueAt(time) * 2.55f).toInt().coerceIn(0, 255)

    private fun drawAdjustment(layer: Layer, time: Float) {
        val stack = EffectStackRunner.pictureEffects(layer.effectStack).take(12)
        if (stack.isEmpty()) return
        val processed = EffectStackRunner.processBitmap(
            bitmap, stack, time, time - layer.start, fps.toFloat(), COMP_W, COMP_H, adjustment = true
        ) ?: return
        paint.reset()
        paint.isFilterBitmap = true
        paint.alpha = opacityOf(layer, time)
        canvas.drawBitmap(processed, 0f, 0f, paint)
        processed.recycle()
    }

    private class Piece(val bitmap: Bitmap, val dest: RectF, val recycle: Boolean)

    private fun drawMotionBlurLayer(layer: Layer, frameTime: Float) {
        val samples = MOTION_BLUR_SAMPLES
        val exposure = (comp.shutterAngle.coerceIn(0f, 360f) / 360f) / fps.coerceAtLeast(1)
        if (exposure <= 0f) {
            drawLayer(layer, frameTime)
            return
        }
        // Accumulate temporal samples into an isolated layer, then composite it once.
        val compositePaint = Paint().apply { xfermode = CompositeSnapshot.blendMode(layer.blendMode) }
        val checkpoint = canvas.saveLayer(null, compositePaint)
        for (index in 0 until samples) {
            val offset = ((index + 0.5f) / samples - 0.5f) * exposure
            val sampleTime = frameTime + offset
            if (sampleTime >= layer.start && sampleTime < layer.end) {
                drawLayer(layer, sampleTime, 1f / samples, additive = true)
            }
        }
        canvas.restoreToCount(checkpoint)
        compositePaint.xfermode = null
    }

    private fun drawLayer(layer: Layer, time: Float, opacityMultiplier: Float = 1f, additive: Boolean = false) {
        var piece = rasterFor(layer, time) ?: return
        val stack = EffectStackRunner.pictureEffects(layer.effectStack).take(12)
        if (stack.isNotEmpty()) {
            val effected = EffectStackRunner.processBitmapGrown(
                piece.bitmap, stack, time, time - layer.start, fps.toFloat(),
                layer.width, layer.height, MAX_EFFECT_PIXELS
            )
            if (effected != null) {
                val d = piece.dest
                val grown = Piece(
                    effected.bitmap,
                    RectF(d.left - effected.left, d.top - effected.top, d.right + effected.right, d.bottom + effected.bottom),
                    recycle = true
                )
                if (piece.recycle) piece.bitmap.recycle()
                piece = grown
            }
        }

        val mask = layer.maskAt(time).takeIf { layer.maskEnabled }
        canvas.save()
        canvas.scale(sx, sy)
        canvas.concat(layerMatrix(layer, time, byId))
        if (mask != null) canvas.saveLayer(RectF(piece.dest).apply { union(0f, 0f, layer.width, layer.height) }, null)
        paint.reset()
        paint.isFilterBitmap = true
        paint.isAntiAlias = true
        paint.alpha = (opacityOf(layer, time) * opacityMultiplier).toInt().coerceIn(0, 255)
        paint.xfermode = if (additive) PorterDuffXfermode(PorterDuff.Mode.ADD) else CompositeSnapshot.blendMode(layer.blendMode)
        paint.colorFilter = if ("Grayscale" in layer.effects) grayscale else null
        canvas.drawBitmap(piece.bitmap, null, piece.dest, paint)
        if (mask != null) {
            applyMask(layer, mask)
            canvas.restore()
        }
        canvas.restore()
        paint.xfermode = null
        paint.colorFilter = null
        if (piece.recycle) piece.bitmap.recycle()
    }

    private fun applyMask(layer: Layer, mask: com.kinetic.app.model.LayerMask) {
        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            alpha = (mask.opacity * 2.55f).toInt().coerceIn(0, 255)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            if (mask.feather > 0f) maskFilter = BlurMaskFilter(mask.feather.coerceIn(0f, 100f), BlurMaskFilter.Blur.NORMAL)
        }
        val shape = buildMaskContour(mask.points, layer.width, layer.height, mask.closed)
        if (mask.inverted || mask.mode == "Subtract") shape.fillType = Path.FillType.INVERSE_WINDING
        if ((mask.kind == "Pen" || mask.kind == "Ellipse") && mask.points.size >= 3 && mask.closed) {
            if (mask.expansion != 0f) {
                maskPaint.style = Paint.Style.FILL_AND_STROKE
                maskPaint.strokeWidth = mask.expansion.coerceIn(-100f, 100f) * 2f
                maskPaint.strokeJoin = Paint.Join.ROUND
            }
            canvas.drawPath(shape, maskPaint)
        } else if ((mask.kind == "Draw" || mask.kind == "Roto Brush") && mask.strokes.isNotEmpty()) {
            val foreground = Path()
            val background = Path()
            mask.strokes.forEach { stroke ->
                if (stroke.points.size > 1) {
                    val target = if (stroke.foreground) foreground else background
                    stroke.points.forEachIndexed { i, p ->
                        val x = p.x * layer.width
                        val y = p.y * layer.height
                        if (i == 0) target.moveTo(x, y) else target.lineTo(x, y)
                    }
                }
            }
            val brush = Paint(maskPaint).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                strokeWidth = (mask.brushSize * min(layer.width, layer.height)).coerceAtLeast(2f)
            }
            if (!foreground.isEmpty) canvas.drawPath(foreground, brush)
            if (!background.isEmpty) {
                brush.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                canvas.drawPath(background, brush)
            }
        }
    }

    /** The layer's picture at export resolution, with where it sits in layer space. */
    private fun rasterFor(layer: Layer, time: Float): Piece? {
        val target = max(layer.width * sx, layer.height * sy).toInt().coerceIn(64, MAX_EDGE)
        return when (layer.type) {
            LayerType.TEXT, LayerType.SHAPE ->
                LayerRaster.render(layer, time, target, allowUpscale = true)
                    ?.let { Piece(it, RectF(0f, 0f, layer.width, layer.height), recycle = true) }
            LayerType.IMAGE -> {
                val uri = layer.uri ?: return null
                val bmp = images.getOrPut(layer.id) { loadImage(Uri.parse(uri), target) ?: return null }
                Piece(bmp, fit(bmp, layer), recycle = false)
            }
            LayerType.VIDEO -> {
                val uri = layer.uri ?: return null
                val bmp = videoFrame(layer, Uri.parse(uri), time, target) ?: return null
                Piece(bmp, fit(bmp, layer), recycle = false)
            }
            else -> null
        }
    }

    /** Aspect-fit [bmp] inside the layer, like the preview does. */
    private fun fit(bmp: Bitmap, layer: Layer): RectF {
        val scale = min(layer.width / bmp.width, layer.height / bmp.height)
        val w = bmp.width * scale
        val h = bmp.height * scale
        val x = (layer.width - w) / 2f
        val y = (layer.height - h) / 2f
        return RectF(x, y, x + w, y + h)
    }

    private fun loadImage(uri: Uri, targetEdge: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetEdge) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }.getOrNull()

    private fun videoFrame(layer: Layer, uri: Uri, time: Float, targetEdge: Int): Bitmap? {
        val sourceTime = (layer.sourceElapsedAt(time) + layer.srcOffset).coerceAtLeast(0f)
        val targetUs = (sourceTime * 1_000_000f).toLong()
        val key = layer.id
        val decoder = decoders.getOrPut(key) { SequentialVideoFrameDecoder() }
        val identity = "export:$uri"
        if (!decoder.shouldUseRetrieverFallback(identity)) {
            decoder.decodeFrame(context, uri, identity, targetUs, targetEdge)?.let { return it }
        }
        // Some files defeat the sequential decoder; the retriever is slower but accepts them.
        val retriever = retrievers.getOrPut(key) { MediaMetadataRetriever().also { it.setDataSource(context, uri) } }
        val frame = retriever.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST) ?: return null
        val longEdge = max(frame.width, frame.height)
        if (longEdge <= targetEdge) return frame
        val k = targetEdge.toFloat() / longEdge
        return Bitmap.createScaledBitmap(frame, (frame.width * k).toInt().coerceAtLeast(1), (frame.height * k).toInt().coerceAtLeast(1), true)
            .also { if (it !== frame) frame.recycle() }
    }

    override fun close() {
        decoders.values.forEach { runCatching { it.release() } }
        retrievers.values.forEach { runCatching { it.release() } }
        decoders.clear()
        retrievers.clear()
        images.values.forEach { if (!it.isRecycled) it.recycle() }
        images.clear()
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    private companion object {
        const val MOTION_BLUR_SAMPLES = 8
        const val MAX_EDGE = 4096
        /** Memory budget for one layer's effect result (RGBA float working copy is 16 bytes per pixel). */
        const val MAX_EFFECT_PIXELS = 12_000_000
    }
}
