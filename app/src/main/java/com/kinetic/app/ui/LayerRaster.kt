package com.kinetic.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.kinetic.app.model.Layer
import com.kinetic.app.model.LayerType
import com.kinetic.app.model.VECTOR_STROKE
import com.kinetic.app.model.VectorPoint
import com.kinetic.app.model.buildVectorPath
import kotlin.math.roundToInt

/** Rasterizes a text or shape layer so picture effects can run on it like on any clip. */
internal object LayerRaster {
    /** [allowUpscale] renders larger than the layer's logical size, for export at high resolution. */
    fun render(layer: Layer, time: Float, maxEdge: Int, allowUpscale: Boolean = false): Bitmap? {
        if (layer.type != LayerType.TEXT && layer.type != LayerType.SHAPE) return null
        val logicalWidth = layer.width.toInt().coerceIn(1, 4096)
        val logicalHeight = layer.height.toInt().coerceIn(1, 4096)
        val fit = maxEdge.toFloat() / maxOf(logicalWidth, logicalHeight)
        val scale = if (allowUpscale) fit else minOf(1f, fit)
        val w = (logicalWidth * scale).roundToInt().coerceAtLeast(1)
        val h = (logicalHeight * scale).roundToInt().coerceAtLeast(1)
        val bitmap = runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return null
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = layer.color }
        if (layer.type == LayerType.TEXT) {
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 120f
            paint.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText(layer.text, logicalWidth / 2f, logicalHeight / 2f + 40f, paint)
        } else {
            val points = if (layer.vectorPoints.isEmpty() && !layer.isVector) {
                listOf(VectorPoint(0f, 0f), VectorPoint(1f, 0f), VectorPoint(1f, 1f), VectorPoint(0f, 1f))
            } else layer.vectorPointsAt(time)
            if (points.size >= 2) {
                val path = buildVectorPath(points, layer.vectorSmooth, layer.vectorClosed, logicalWidth.toFloat(), logicalHeight.toFloat())
                if (!layer.vectorClosed) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = VECTOR_STROKE
                    paint.strokeCap = Paint.Cap.ROUND
                    paint.strokeJoin = Paint.Join.ROUND
                }
                canvas.drawPath(path, paint)
            }
        }
        return bitmap
    }
}
