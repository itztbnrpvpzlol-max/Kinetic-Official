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
import com.kinetic.app.model.maskClips
import com.kinetic.app.model.textIsAnimated
import com.kinetic.app.model.MotionBlur
import com.kinetic.app.model.LayerType
import com.kinetic.app.model.PropId
import com.kinetic.app.model.layerMatrix
import com.kinetic.app.model.sourceElapsedAt
import com.kinetic.app.ui.CompositeSnapshot
import com.kinetic.app.ui.LayerRaster
import com.kinetic.app.ui.buildMaskContour
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
                LayerType.AUDIO, LayerType.NULL, LayerType.CAMERA -> Unit
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
            bitmap, stack, time, time - layer.start, fps.toFloat(), COMP_W, COMP_H, adjustment = true, cacheResult = false
        ) ?: return
        try {
            paint.reset()
            paint.isFilterBitmap = true
            paint.alpha = opacityOf(layer, time)
            canvas.drawBitmap(processed, 0f, 0f, paint)
        } finally {
            if (!processed.isRecycled) processed.recycle()
            paint.reset()
        }
    }

    private class Piece(val bitmap: Bitmap, val dest: RectF, val recycle: Boolean)

    private fun release(piece: Piece) {
        if (piece.recycle && !piece.bitmap.isRecycled) piece.bitmap.recycle()
    }

    private fun applyEffects(layer: Layer, piece: Piece, time: Float): Piece {
        val stack = EffectStackRunner.pictureEffects(layer.effectStack).take(12)
        if (stack.isEmpty()) return piece
        val effected = EffectStackRunner.processBitmapGrown(
            piece.bitmap, stack, time, time - layer.start, fps.toFloat(),
            layer.width, layer.height, MAX_EFFECT_PIXELS, cacheResult = false
        ) ?: return piece
        val d = piece.dest
        return Piece(
            effected.bitmap,
            RectF(d.left - effected.left, d.top - effected.top, d.right + effected.right, d.bottom + effected.bottom),
            recycle = true
        )
    }

    private fun drawMotionBlurLayer(layer: Layer, frameTime: Float) {
        val times = MotionBlur.sampleTimes(layer, frameTime, comp.shutterAngle, fps, byId, MotionBlur.EXPORT_MAX_SAMPLES)
        if (times.size <= 1) {
            drawLayer(layer, frameTime)
            return
        }
        val temporalContent = layer.type == LayerType.VIDEO ||
            layer.type == LayerType.SHAPE || layer.type == LayerType.TEXT
        val fixedRaw = if (temporalContent) null else rasterFor(layer, frameTime)
        if (!temporalContent && fixedRaw == null) return
        val animatedEffects = MotionBlur.effectsAnimated(layer)
        val fixedEffects = if (fixedRaw != null && !animatedEffects) {
            applyEffects(layer, fixedRaw, frameTime)
        } else null
        val opacity = (layer.p(PropId.OPACITY).valueAt(frameTime) / 100f).coerceIn(0f, 1f)
        val compositePaint = Paint().apply { xfermode = CompositeSnapshot.blendMode(layer.blendMode) }
        val checkpoint = canvas.saveLayer(null, compositePaint)
        var reusedFrom: Bitmap? = null
        var reusedPiece: Piece? = null
        var activeTransient: Piece? = null
        try {
            for (index in times.indices) {
                val sampleTime = times[index]
                val raw = fixedRaw ?: rasterFor(layer, sampleTime) ?: continue
                val canReuse = fixedEffects == null && !animatedEffects && !raw.recycle
                val piece: Piece
                if (fixedEffects != null) {
                    piece = fixedEffects
                } else {
                    val cachedPiece = reusedPiece
                    if (canReuse && cachedPiece != null && reusedFrom === raw.bitmap) {
                        piece = cachedPiece
                    } else {
                        val made = applyEffects(layer, raw, if (fixedRaw == null || animatedEffects) sampleTime else frameTime)
                        if (canReuse) {
                            if (cachedPiece != null && cachedPiece !== raw && cachedPiece !== made) release(cachedPiece)
                            reusedFrom = raw.bitmap
                            reusedPiece = made
                        }
                        piece = made
                    }
                }
                val kept = piece === fixedEffects || (canReuse && piece === reusedPiece)
                if (piece !== raw && !kept) activeTransient = piece
                try {
                    drawPiece(layer, piece, sampleTime, MotionBlur.sampleAlpha(opacity, index, times.size))
                } finally {
                    activeTransient?.let { release(it) }
                    activeTransient = null
                    if (raw !== fixedRaw) release(raw)
                }
            }
        } finally {
            activeTransient?.let { release(it) }
            canvas.restoreToCount(checkpoint)
            compositePaint.xfermode = null
            reusedPiece?.let { if (it.bitmap !== reusedFrom) release(it) }
            if (fixedEffects != null && fixedEffects !== fixedRaw) release(fixedEffects)
            if (fixedRaw != null) release(fixedRaw)
        }
    }

    /** Effected stills that cannot change from frame to frame, kept so they are processed once per export, not once per frame. */
    private val staticEffected = HashMap<String, Pair<String, Piece>>()

    private fun drawLayer(layer: Layer, time: Float) {
        if (layer.type == LayerType.IMAGE) {
            val stack = EffectStackRunner.pictureEffects(layer.effectStack).take(12)
            if (stack.isNotEmpty() && !EffectStackRunner.isTimeDependent(stack) && !MotionBlur.effectsAnimated(layer)) {
                val key = buildString {
                    append(layer.uri.orEmpty()).append('|')
                    append(layer.width).append('x').append(layer.height).append('|')
                    append(EffectStackRunner.stackJson(stack, 0f))
                }
                var cached = staticEffected[layer.id]?.takeIf { it.first == key && !it.second.bitmap.isRecycled }
                if (cached == null) {
                    staticEffected.remove(layer.id)?.second?.let { release(it) }
                    val raw0 = rasterFor(layer, time) ?: return
                    val made = applyEffects(layer, raw0, time)
                    if (made === raw0) { drawPiece(layer, raw0, time, null); return }
                    cached = key to made
                    if (staticEffected.size < 6) staticEffected[layer.id] = cached
                    else { drawPiece(layer, made, time, null); release(made); return }
                }
                drawPiece(layer, cached.second, time, null)
                return
            }
        }
        val raw = rasterFor(layer, time) ?: return
        val piece = applyEffects(layer, raw, time)
        drawPiece(layer, piece, time, null)
        if (piece !== raw) release(piece)
        release(raw)
    }

    private fun drawPiece(layer: Layer, piece: Piece, time: Float, sampleAlpha: Float?) {
        val mask = layer.maskAt(time).takeIf { layer.maskClips }
        val checkpoint = canvas.save()
        try {
            canvas.scale(sx, sy)
            canvas.concat(layerMatrix(layer, time, byId))
            if (mask != null) {
                canvas.saveLayer(RectF(piece.dest).apply { union(0f, 0f, layer.width, layer.height) }, null)
            }
            paint.reset()
            paint.isFilterBitmap = true
            paint.isAntiAlias = true
            if (sampleAlpha != null) {
                paint.alpha = (sampleAlpha * 255f).roundToInt().coerceIn(0, 255)
                paint.xfermode = null
            } else {
                paint.alpha = opacityOf(layer, time)
                paint.xfermode = CompositeSnapshot.blendMode(layer.blendMode)
            }
            paint.colorFilter = if ("Grayscale" in layer.effects) grayscale else null
            canvas.drawBitmap(piece.bitmap, null, piece.dest, paint)
            if (mask != null) applyMask(layer, mask)
        } finally {
            canvas.restoreToCount(checkpoint)
            paint.reset()
        }
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
            LayerType.TEXT, LayerType.SHAPE, LayerType.PARTICULAR ->
                LayerRaster.render(layer, time, target, allowUpscale = true, byId = byId)
                    ?.let { Piece(it, RectF(0f, 0f, layer.width, layer.height), recycle = true) }
            LayerType.IMAGE -> {
                val uri = layer.uri ?: return null
                // A layer can be retargeted to another image while retaining its ID.
                // Include the URI so a previous export decode is never reused for new media.
                val imageKey = layer.id + "|" + uri
                val bmp = images.getOrPut(imageKey) { loadImage(Uri.parse(uri), target) ?: return null }
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
            decoder.decodeFrameShared(context, uri, identity, targetUs, targetEdge)?.let { return it }
        }
        // Some files defeat the sequential decoder; the retriever is slower but accepts them.
        val retrieverKey = "$key|$uri"
        val retriever = retrievers.getOrPut(retrieverKey) {
            MediaMetadataRetriever().also { created ->
                try {
                    created.setDataSource(context, uri)
                } catch (failure: Throwable) {
                    runCatching { created.release() }
                    throw failure
                }
            }
        }
        val frame = retriever.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST) ?: return null
        val longEdge = max(frame.width, frame.height)
        if (longEdge <= targetEdge) return frame
        val k = targetEdge.toFloat() / longEdge
        return try {
            Bitmap.createScaledBitmap(frame, (frame.width * k).toInt().coerceAtLeast(1), (frame.height * k).toInt().coerceAtLeast(1), true)
        } finally {
            if (!frame.isRecycled) frame.recycle()
        }
    }

    override fun close() {
        decoders.values.forEach { runCatching { it.release() } }
        retrievers.values.forEach { runCatching { it.release() } }
        decoders.clear()
        retrievers.clear()
        staticEffected.values.forEach { release(it.second) }
        staticEffected.clear()
        images.values.forEach { if (!it.isRecycled) it.recycle() }
        images.clear()
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    private companion object {
        const val MAX_EDGE = 4096
        /** Memory budget for one layer's effect result (RGBA float working copy is 16 bytes per pixel). */
        const val MAX_EFFECT_PIXELS = 12_000_000
    }
}
