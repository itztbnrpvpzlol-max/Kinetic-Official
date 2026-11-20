package com.kinetic.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import com.kinetic.app.model.COMP_H
import com.kinetic.app.model.COMP_W
import com.kinetic.app.model.Layer
import com.kinetic.app.model.LayerType
import com.kinetic.app.model.PropId
import com.kinetic.app.model.layerMatrix

/** What one layer contributes to a snapshot: its raster and where it sits in layer space. */
internal class SnapshotPiece(val bitmap: Bitmap, val dest: RectF, val temporary: Boolean = false)

/**
 * Flattens the layers beneath an adjustment layer into one bitmap, so the adjustment layer's
 * effects can run on the whole composition rather than on a single clip.
 */
internal object CompositeSnapshot {
    private val modes = mapOf(
        "Multiply" to PorterDuff.Mode.MULTIPLY, "Screen" to PorterDuff.Mode.SCREEN,
        "Add" to PorterDuff.Mode.ADD, "Overlay" to PorterDuff.Mode.OVERLAY,
        "Darken" to PorterDuff.Mode.DARKEN, "Lighten" to PorterDuff.Mode.LIGHTEN
    ).mapValues { PorterDuffXfermode(it.value) }

    /** [below] is ordered bottom to top. The result is composition-shaped, at most [maxEdge] on its long side. */
    fun render(
        below: List<Layer>, time: Float, background: Int, maxEdge: Int,
        piece: (Layer) -> SnapshotPiece?
    ): Bitmap? {
        val scale = minOf(1f, maxEdge.toFloat() / maxOf(COMP_W, COMP_H))
        val w = (COMP_W * scale).toInt().coerceAtLeast(1)
        val h = (COMP_H * scale).toInt().coerceAtLeast(1)
        val bitmap = runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return null
        val canvas = Canvas(bitmap)
        canvas.drawColor(background)
        val byId = below.associateBy { it.id }
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        for (layer in below) {
            if (!layer.visible || !layer.activeAt(time)) continue
            if (layer.type == LayerType.AUDIO || layer.type == LayerType.NULL || layer.type == LayerType.ADJUSTMENT) continue
            val piece = piece(layer) ?: continue
            if (piece.bitmap.isRecycled) continue
            paint.alpha = (layer.p(PropId.OPACITY).valueAt(time) * 2.55f).toInt().coerceIn(0, 255)
            paint.xfermode = modes[layer.blendMode]
            canvas.save()
            canvas.scale(scale, scale)
            canvas.concat(layerMatrix(layer, time, byId))
            canvas.drawBitmap(piece.bitmap, null, piece.dest, paint)
            canvas.restore()
            if (piece.temporary) piece.bitmap.recycle()
        }
        return bitmap
    }
}
