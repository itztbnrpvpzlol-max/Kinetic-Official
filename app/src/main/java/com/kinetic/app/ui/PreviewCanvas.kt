package com.kinetic.app.ui

import android.graphics.Bitmap
import android.graphics.DashPathEffect
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import com.kinetic.app.data.MediaLoader
import com.kinetic.app.model.COMP_H
import com.kinetic.app.model.COMP_W
import com.kinetic.app.model.EditorState
import com.kinetic.app.model.LayerType
import com.kinetic.app.model.PropId
import com.kinetic.app.model.layerMatrix
import android.graphics.Path as AndroidPath

@Composable
fun CpuPreviewCanvas(editor: EditorState, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val bitmaps = remember { mutableStateMapOf<String, Bitmap>() }

    // load image / video frames for layers that are visible at the playhead
    val t = editor.time
    for (l in editor.layers) {
        val uri = l.uri
        if (uri != null && (l.type == LayerType.IMAGE || l.type == LayerType.VIDEO)) {
            val active = l.activeAt(t)
            val src = ((t - l.start).coerceAtLeast(0f) * l.speed + l.srcOffset).coerceAtLeast(0f)
            // Decode video preview frames at a bounded rate. Scrubbing and playback
            // share the same bucket size so toggling transport doesn't invalidate cache.
            val bucket = if (!active) -1 else if (l.type == LayerType.VIDEO) (src * 12f).toInt() else 0
            key(l.id) {
                DisposableEffect(l.id) {
                    onDispose { bitmaps.remove(l.id) }
                }
                LaunchedEffect(uri, bucket) {
                    if (bucket >= 0) {
                        MediaLoader.bitmapFor(ctx, Uri.parse(uri), l.type, src, 12, 640)?.let { bitmaps[l.id] = it }
                    } else {
                        bitmaps.remove(l.id)
                    }
                }
            }
        }
    }

    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true } }
    val textPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER; textSize = 120f; typeface = Typeface.DEFAULT_BOLD
        }
    }
    val outline = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF8466FF.toInt() }
    }

    Canvas(modifier.background(Color(0xFF0A0A0F))) {
        val s = minOf(size.width / COMP_W, size.height / COMP_H)
        val ox = (size.width - COMP_W * s) / 2f
        val oy = (size.height - COMP_H * s) / 2f
        val time = editor.time
        val layers = editor.layers
        val byId = layers.associateBy { it.id }
        val selId = editor.selectedId

        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            nc.save()
            nc.translate(ox, oy)
            nc.scale(s, s)
            nc.clipRect(0f, 0f, COMP_W, COMP_H)
            nc.drawColor(android.graphics.Color.BLACK)

            for (l in layers.asReversed()) {
                if (!l.visible || !l.activeAt(time)) continue
                val alpha = (l.p(PropId.OPACITY).valueAt(time) * 2.55f).toInt().coerceIn(0, 255)
                nc.save()
                nc.concat(layerMatrix(l, time, byId))
                if (l.maskEnabled) nc.clipRect(0f, 0f, l.width * 0.8f, l.height * 0.8f)
                paint.xfermode = when (l.blendMode) {
                    "Multiply" -> PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
                    "Screen" -> PorterDuffXfermode(PorterDuff.Mode.SCREEN)
                    "Add" -> PorterDuffXfermode(PorterDuff.Mode.ADD)
                    else -> null
                }
                paint.colorFilter = if ("Grayscale" in l.effects) ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) else null
                when (l.type) {
                    LayerType.IMAGE, LayerType.VIDEO -> {
                        val bmp = bitmaps[l.id]
                        paint.alpha = alpha
                        if (bmp != null) {
                            nc.drawBitmap(bmp, null, RectF(0f, 0f, l.width, l.height), paint)
                        } else {
                            paint.color = 0xFF24242F.toInt(); paint.alpha = alpha
                            nc.drawRect(0f, 0f, l.width, l.height, paint)
                        }
                    }
                    LayerType.SHAPE -> {
                        paint.color = l.color; paint.alpha = alpha
                        nc.drawRoundRect(0f, 0f, l.width, l.height, 24f, 24f, paint)
                    }
                    LayerType.TEXT -> {
                        textPaint.color = l.color; textPaint.alpha = alpha
                        textPaint.xfermode = when (l.blendMode) {
                            "Multiply" -> PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
                            "Screen" -> PorterDuffXfermode(PorterDuff.Mode.SCREEN)
                            "Add" -> PorterDuffXfermode(PorterDuff.Mode.ADD)
                            else -> null
                        }
                        textPaint.colorFilter = if ("Grayscale" in l.effects) ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) else null
                        nc.drawText(l.text, l.width / 2f, l.height / 2f + 40f, textPaint)
                    }
                    else -> Unit
                }
                paint.xfermode = null
                paint.colorFilter = null
                textPaint.xfermode = null
                textPaint.colorFilter = null
                nc.restore()
            }
            nc.restore()

            // selection overlay (not clipped to the composition)
            val sel = layers.firstOrNull { it.id == selId }
            if (sel != null && sel.visible && sel.activeAt(time) && sel.type != LayerType.AUDIO) {
                nc.save()
                nc.translate(ox, oy)
                nc.scale(s, s)
                val m = layerMatrix(sel, time, byId)
                val pts = floatArrayOf(0f, 0f, sel.width, 0f, sel.width, sel.height, 0f, sel.height)
                m.mapPoints(pts)
                outline.strokeWidth = 2f / s
                outline.pathEffect =
                    if (sel.type == LayerType.ADJUSTMENT) DashPathEffect(floatArrayOf(14f / s, 10f / s), 0f) else null
                val path = AndroidPath()
                path.moveTo(pts[0], pts[1]); path.lineTo(pts[2], pts[3])
                path.lineTo(pts[4], pts[5]); path.lineTo(pts[6], pts[7]); path.close()
                nc.drawPath(path, outline)
                val a = floatArrayOf(sel.p(PropId.ANCHOR_X).valueAt(time), sel.p(PropId.ANCHOR_Y).valueAt(time))
                m.mapPoints(a)
                outline.pathEffect = null
                nc.drawCircle(a[0], a[1], 7f / s, outline)
                nc.restore()
            }
        }
    }
}
