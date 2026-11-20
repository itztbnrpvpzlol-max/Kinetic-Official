package com.kinetic.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinetic.app.model.COMP_H
import com.kinetic.app.model.COMP_W
import com.kinetic.app.model.EditorState
import com.kinetic.app.model.Layer
import com.kinetic.app.model.PropId
import com.kinetic.app.model.buildVectorPath
import kotlin.math.abs
import kotlin.math.hypot

private val PointGreen = Color(0xFF1DE9B6)
private val PointGray = Color(0xFFBDBDBD)
private const val MAX_POINTS = 200
private const val ZOOM = 4f

class VectorEditState {
    var sel by mutableIntStateOf(-1)
    var cursor by mutableStateOf(Offset(0.5f, 0.5f))
    var smoothNew by mutableStateOf(false)
    var canvasSize by mutableStateOf(IntSize.Zero)
    var dragStarted = false
}

private fun VectorEditState.compScale(): Float =
    if (canvasSize.width <= 0 || canvasSize.height <= 0) 1f
    else minOf(canvasSize.width / COMP_W, canvasSize.height / COMP_H)

private fun layerOf(editor: EditorState, id: String): Layer? = editor.layers.firstOrNull { it.id == id }

private fun VectorEditState.place(editor: EditorState, id: String) {
    val l = layerOf(editor, id) ?: return
    if (l.vectorPoints.size >= MAX_POINTS) return
    editor.addVectorPoint(id, cursor.x, cursor.y, smoothNew)
}

private fun VectorEditState.moveActive(editor: EditorState, id: String, dx: Float, dy: Float) {
    val l = layerOf(editor, id) ?: return
    val sc = compScale()
    val nx = dx / (COMP_W * sc)
    val ny = dy / (COMP_H * sc)
    if (sel >= 0 && sel < l.vectorPoints.size) {
        val current = l.vectorPointsAt(editor.time)[sel]
        editor.moveVectorPoint(id, sel, current.x + nx, current.y + ny, push = !dragStarted)
        dragStarted = true
    } else {
        cursor = Offset((cursor.x + nx).coerceIn(0f, 1f), (cursor.y + ny).coerceIn(0f, 1f))
    }
}

private fun VectorEditState.deleteSelected(editor: EditorState, id: String) {
    val l = layerOf(editor, id) ?: return
    val s = sel
    if (s < 0 || s >= l.vectorPoints.size) { sel = -1; return }
    val remaining = l.vectorPoints.size - 1
    editor.removeVectorPoint(id, s)
    sel = -1
    if (remaining > 0) {
        val p = l.vectorPoints[(s - 1).coerceAtLeast(0).coerceAtMost(l.vectorPoints.size - 1)]
        cursor = Offset(p.x, p.y)
    }
}

private fun VectorEditState.toggleCurve(editor: EditorState, id: String) {
    val l = layerOf(editor, id) ?: return
    if (sel >= 0 && sel < l.vectorPoints.size) editor.setVectorSmooth(id, sel, !l.vectorSmooth.getOrElse(sel) { false })
    else smoothNew = !smoothNew
}

private fun VectorEditState.cycleSelection(editor: EditorState, id: String) {
    val n = layerOf(editor, id)?.vectorPoints?.size ?: 0
    if (n == 0) { sel = -1; return }
    val next = sel + 1
    if (next >= n) {
        val last = layerOf(editor, id)!!.vectorPointsAt(editor.time)[n - 1]
        cursor = Offset(last.x, last.y)
        sel = -1
    } else sel = next
}

private fun VectorEditState.toggleKeyframe(editor: EditorState, id: String) {
    val l = layerOf(editor, id) ?: return
    if (sel < 0 || sel >= l.vectorPoints.size) return
    PropId.vectorPoint(sel, 'X')?.let { editor.toggleKeyAtTime(id, it) }
    PropId.vectorPoint(sel, 'Y')?.let { editor.toggleKeyAtTime(id, it) }
}

@Composable
fun VectorEditOverlay(editor: EditorState, layerId: String, st: VectorEditState, modifier: Modifier = Modifier) {
    val layer = layerOf(editor, layerId) ?: return
    val density = LocalDensity.current.density
    val pts = layer.vectorPointsAt(editor.time)
    Canvas(
        modifier.onSizeChanged { st.canvasSize = it }
            .pointerInput(layerId) {
                detectTapGestures { tap ->
                    val sc = st.compScale()
                    val ox = (size.width - COMP_W * sc) / 2f
                    val oy = (size.height - COMP_H * sc) / 2f
                    val current = layerOf(editor, layerId)?.vectorPointsAt(editor.time) ?: emptyList()
                    var best = -1
                    var bestDistance = 24f * density
                    current.forEachIndexed { i, p ->
                        val d = hypot(tap.x - (ox + p.x * COMP_W * sc), tap.y - (oy + p.y * COMP_H * sc))
                        if (d < bestDistance) { bestDistance = d; best = i }
                    }
                    if (best < 0 && st.sel >= 0) {
                        val p = current.getOrNull(st.sel)
                        if (p != null) st.cursor = Offset(p.x, p.y)
                    }
                    st.sel = best
                }
            }
            .pointerInput(Unit) { detectDragGestures { change, _ -> change.consume() } }
    ) {
        val sc = minOf(size.width / COMP_W, size.height / COMP_H)
        val ox = (size.width - COMP_W * sc) / 2f
        val oy = (size.height - COMP_H * sc) / 2f
        fun sx(x: Float) = ox + x * COMP_W * sc
        fun sy(y: Float) = oy + y * COMP_H * sc
        if (pts.size >= 2) {
            val path = buildVectorPath(pts, layer.vectorSmooth, layer.vectorClosed, COMP_W, COMP_H)
            withTransform({
                translate(ox, oy)
                scale(sc, sc, Offset.Zero)
            }) {
                drawPath(path.asComposePath(), KColors.Accent.copy(alpha = .75f), style = Stroke(width = 1.dp.toPx() / sc))
            }
        }
        pts.forEachIndexed { i, p ->
            val c = Offset(sx(p.x), sy(p.y))
            if (i == st.sel) drawCircle(PointGreen, 5.5.dp.toPx(), c)
            else {
                drawCircle(Color.Black.copy(alpha = .6f), 4.5.dp.toPx(), c)
                drawCircle(PointGray, 3.2.dp.toPx(), c)
            }
        }
        if (st.sel < 0) {
            val c = Offset(sx(st.cursor.x), sy(st.cursor.y))
            val gap = 2.5.dp.toPx()
            val len = 6.dp.toPx()
            val w = 1.5.dp.toPx()
            drawLine(PointGreen, Offset(c.x - gap - len, c.y), Offset(c.x - gap, c.y), w)
            drawLine(PointGreen, Offset(c.x + gap, c.y), Offset(c.x + gap + len, c.y), w)
            drawLine(PointGreen, Offset(c.x, c.y - gap - len), Offset(c.x, c.y - gap), w)
            drawLine(PointGreen, Offset(c.x, c.y + gap), Offset(c.x, c.y + gap + len), w)
            pts.lastOrNull()?.let { last ->
                drawLine(
                    Color(0x99FFFFFF), Offset(sx(last.x), sy(last.y)), c, 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                )
            }
        }
    }
}

@Composable
fun VectorEditPanel(editor: EditorState, layerId: String, st: VectorEditState, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val layer = layerOf(editor, layerId)
    if (layer == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val density = LocalDensity.current.density
    val pts = layer.vectorPointsAt(editor.time)
    LaunchedEffect(pts.size) { if (st.sel >= pts.size) st.sel = -1 }
    val sel = st.sel
    val keyed = sel in pts.indices && PropId.vectorPoint(sel, 'X')?.let { id ->
        layer.props[id]?.keys?.any { abs(it.t - editor.time) < editor.frame / 2f }
    } == true
    val curveOn = if (sel in pts.indices) layer.vectorSmooth.getOrElse(sel) { false } else st.smoothNew

    Column(modifier.clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)).background(KColors.Surface).pointerInput(Unit) { detectTapGestures { } }) {
        Row(Modifier.fillMaxWidth().height(46.dp).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${layer.name} \u00B7 Edit Points", color = KColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Chip(if (layer.vectorClosed) "Closed" else "Open", layer.vectorClosed) { editor.setVectorClosed(layerId, !layer.vectorClosed) }
            Box(Modifier.size(40.dp).clip(CircleShape).clickable { st.toggleKeyframe(editor, layerId) }, contentAlignment = Alignment.Center) {
                KIcon(if (keyed) IconKind.DIAMOND_FILLED else IconKind.DIAMOND, if (sel >= 0) KColors.Text else KColors.Dim, Modifier.size(20.dp))
            }
            Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                KIcon(IconKind.CLOSE, KColors.Text, Modifier.size(20.dp))
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth().padding(bottom = 6.dp)) {
            Column(Modifier.width(52.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
                PadButton(IconKind.SELECT, if (pts.isEmpty()) KColors.Dim else KColors.Text) { st.cycleSelection(editor, layerId) }
                PadButton(IconKind.GRAPH, if (curveOn) PointGreen else KColors.Muted) { st.toggleCurve(editor, layerId) }
                if (sel >= 0) PadButton(IconKind.DELETE, KColors.Text) { st.deleteSelected(editor, layerId) }
                else PadButton(IconKind.PLUS, KColors.Text) { st.place(editor, layerId) }
            }
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(Color(0xFF2A2A2A))
                    .pointerInput(layerId) {
                        detectTapGestures(onTap = {
                            if (st.sel < 0) st.place(editor, layerId)
                            else {
                                val p = layerOf(editor, layerId)?.vectorPointsAt(editor.time)?.getOrNull(st.sel)
                                if (p != null) st.cursor = Offset(p.x, p.y)
                                st.sel = -1
                            }
                        })
                    }
                    .pointerInput(layerId) {
                        detectDragGestures(
                            onDragStart = { st.dragStarted = false },
                            onDragEnd = { st.dragStarted = false },
                            onDragCancel = { st.dragStarted = false }
                        ) { change, amount ->
                            change.consume()
                            st.moveActive(editor, layerId, amount.x, amount.y)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (sel < 0) "Swipe here to position next point, then tap here to place it" else "Swipe to move point \u00B7 tap to deselect",
                    color = KColors.Dim, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(20.dp)
                )
            }
            Canvas(
                Modifier.padding(start = 8.dp, end = 8.dp).width(84.dp).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(Color(0xFF1E1E1E)).clipToBounds()
                    .pointerInput(layerId) {
                        detectTapGestures { tap ->
                            val l = layerOf(editor, layerId) ?: return@detectTapGestures
                            val current = l.vectorPointsAt(editor.time)
                            val focus = current.getOrNull(st.sel)
                            val fx = (focus?.x ?: st.cursor.x) * COMP_W
                            val fy = (focus?.y ?: st.cursor.y) * COMP_H
                            val z = st.compScale() * ZOOM
                            var best = -1
                            var bestDistance = 24f * density
                            current.forEachIndexed { i, p ->
                                val d = hypot(
                                    tap.x - (size.width / 2f + (p.x * COMP_W - fx) * z),
                                    tap.y - (size.height / 2f + (p.y * COMP_H - fy) * z)
                                )
                                if (d < bestDistance) { bestDistance = d; best = i }
                            }
                            if (best >= 0) st.sel = best
                        }
                    }
                    .pointerInput(layerId) {
                        detectDragGestures(
                            onDragStart = { st.dragStarted = false },
                            onDragEnd = { st.dragStarted = false },
                            onDragCancel = { st.dragStarted = false }
                        ) { change, amount ->
                            change.consume()
                            st.moveActive(editor, layerId, amount.x / ZOOM, amount.y / ZOOM)
                        }
                    }
            ) {
                val focus = pts.getOrNull(sel)
                val fx = (focus?.x ?: st.cursor.x) * COMP_W
                val fy = (focus?.y ?: st.cursor.y) * COMP_H
                val z = st.compScale() * ZOOM
                if (pts.size >= 2) {
                    val path = buildVectorPath(pts, layer.vectorSmooth, layer.vectorClosed, COMP_W, COMP_H)
                    withTransform({
                        translate(size.width / 2f, size.height / 2f)
                        scale(z, z, Offset.Zero)
                        translate(-fx, -fy)
                    }) {
                        drawPath(path.asComposePath(), Color.White.copy(alpha = .85f), style = Stroke(width = 1.5.dp.toPx() / z))
                    }
                }
                pts.forEachIndexed { i, p ->
                    if (i != sel) {
                        val c = Offset(size.width / 2f + (p.x * COMP_W - fx) * z, size.height / 2f + (p.y * COMP_H - fy) * z)
                        drawCircle(PointGray, 3.5.dp.toPx(), c)
                    }
                }
                val center = Offset(size.width / 2f, size.height / 2f)
                if (focus != null) drawCircle(PointGreen, 6.dp.toPx(), center, style = Fill)
                else drawCircle(PointGreen, 6.dp.toPx(), center, style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}

@Composable
private fun PadButton(icon: IconKind, tint: Color, onClick: () -> Unit) {
    Box(Modifier.size(42.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        KIcon(icon, tint, Modifier.size(22.dp))
    }
}
