package com.kinetic.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinetic.app.engine.NativePlaybackEngine
import com.kinetic.app.model.COMP_H
import com.kinetic.app.model.COMP_W
import com.kinetic.app.model.EditorState
import com.kinetic.app.model.Layer
import com.kinetic.app.model.PropId
import com.kinetic.app.model.buildVectorPath
import com.kinetic.app.model.fmt
import kotlin.math.abs
import kotlin.math.hypot

private val PointGreen = Color(0xFF1DE9B6)
private val PointGray = Color(0xFFBDBDBD)
private const val MAX_POINTS = 200

@Composable
fun EditPointsScreen(editor: EditorState, layerId: String, playback: NativePlaybackEngine, onClose: () -> Unit) {
    val layer = editor.layers.firstOrNull { it.id == layerId }
    if (layer == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    BackHandler(onBack = onClose)

    var sel by remember { mutableIntStateOf(-1) }
    var cursor by remember { mutableStateOf(Offset(0.5f, 0.5f)) }
    var smoothNew by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var dragStarted by remember { mutableStateOf(false) }
    val density = LocalDensity.current.density
    val layerNow by rememberUpdatedState(layer)

    val pts = layer.vectorPointsAt(editor.time)
    LaunchedEffect(pts.size) { if (sel >= pts.size) sel = -1 }

    fun compScale(): Float {
        val sz = canvasSize
        return if (sz.width <= 0 || sz.height <= 0) 1f else minOf(sz.width / COMP_W, sz.height / COMP_H)
    }

    fun place(at: Offset) {
        val l = layerNow
        val index = l.vectorPoints.size
        if (index >= MAX_POINTS) return
        editor.addVectorPoint(l.id, at.x, at.y, smoothNew)
        sel = index
    }

    fun movePad(dx: Float, dy: Float) {
        val sc = compScale()
        val nx = dx / (COMP_W * sc)
        val ny = dy / (COMP_H * sc)
        val l = layerNow
        val s = sel
        if (s >= 0 && s < l.vectorPoints.size) {
            val current = l.vectorPointsAt(editor.time)[s]
            editor.moveVectorPoint(l.id, s, current.x + nx, current.y + ny, push = !dragStarted)
            dragStarted = true
        } else {
            cursor = Offset((cursor.x + nx).coerceIn(0f, 1f), (cursor.y + ny).coerceIn(0f, 1f))
        }
    }

    fun nextPoint() {
        val s = sel
        if (s >= 0 && s < pts.size) {
            cursor = Offset(pts[s].x, pts[s].y)
            sel = -1
        } else place(cursor)
    }

    fun deleteSelected() {
        val s = sel
        if (s < 0 || s >= layerNow.vectorPoints.size) { sel = -1; return }
        val remaining = layerNow.vectorPoints.size - 1
        editor.removeVectorPoint(layerNow.id, s)
        sel = if (remaining <= 0) -1 else (s - 1).coerceIn(0, remaining - 1)
    }

    fun toggleCurve() {
        val s = sel
        if (s >= 0 && s < pts.size) editor.setVectorSmooth(layer.id, s, !layer.vectorSmooth.getOrElse(s) { false })
        else smoothNew = !smoothNew
    }

    fun cycleSelection() {
        if (pts.isEmpty()) return
        sel = if (sel < 0) pts.size - 1 else (sel + 1) % pts.size
    }

    fun toggleKeyframe() {
        val s = sel
        if (s < 0 || s >= pts.size) return
        PropId.vectorPoint(s, 'X')?.let { editor.toggleKeyAtTime(layer.id, it) }
        PropId.vectorPoint(s, 'Y')?.let { editor.toggleKeyAtTime(layer.id, it) }
    }

    val keyed = sel in pts.indices && PropId.vectorPoint(sel, 'X')?.let { id ->
        layer.props[id]?.keys?.any { abs(it.t - editor.time) < editor.frame / 2f }
    } == true
    val curveOn = if (sel in pts.indices) layer.vectorSmooth.getOrElse(sel) { false } else smoothNew

    Column(
        Modifier.fillMaxSize().background(KColors.Bg).statusBarsPadding().navigationBarsPadding()
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        Row(Modifier.fillMaxWidth().height(52.dp).background(KColors.Surface).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { KIcon(IconKind.BACK, KColors.Text) }
            Text("Edit Points", color = KColors.Text, fontSize = 17.sp, modifier = Modifier.weight(1f).padding(start = 4.dp))
            Chip(if (layer.vectorClosed) "Closed" else "Open", layer.vectorClosed) {
                editor.setVectorClosed(layer.id, !layer.vectorClosed)
            }
            IconButton(onClick = { toggleKeyframe() }) {
                KIcon(if (keyed) IconKind.DIAMOND_FILLED else IconKind.DIAMOND, if (sel >= 0) KColors.Text else KColors.Dim)
            }
            Box(
                Modifier.padding(horizontal = 6.dp).size(34.dp).clip(CircleShape).border(1.5.dp, PointGreen, CircleShape)
                    .clickable { nextPoint() },
                contentAlignment = Alignment.Center
            ) { KIcon(IconKind.PLUS, PointGreen, Modifier.size(18.dp)) }
        }

        Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { canvasSize = it }) {
            PreviewCanvas(editor, Modifier.fillMaxSize(), editor.settings.previewQualityIndex, playback = playback)
            Canvas(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTapGestures { tap ->
                        val sc = compScale()
                        val ox = (size.width - COMP_W * sc) / 2f
                        val oy = (size.height - COMP_H * sc) / 2f
                        val current = layerNow.vectorPointsAt(editor.time)
                        var best = -1
                        var bestDistance = 28f * density
                        current.forEachIndexed { i, p ->
                            val d = hypot(tap.x - (ox + p.x * COMP_W * sc), tap.y - (oy + p.y * COMP_H * sc))
                            if (d < bestDistance) { bestDistance = d; best = i }
                        }
                        sel = best
                    }
                }
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
                        drawPath(path.asComposePath(), Color.White, style = Stroke(width = 2.dp.toPx() / sc))
                    }
                }
                pts.forEachIndexed { i, p ->
                    val c = Offset(sx(p.x), sy(p.y))
                    if (i == sel) drawCircle(PointGreen, 7.dp.toPx(), c)
                    else {
                        drawCircle(Color.Black.copy(alpha = .6f), 6.dp.toPx(), c)
                        drawCircle(PointGray, 4.5.dp.toPx(), c)
                    }
                }
                if (sel < 0) {
                    val c = Offset(sx(cursor.x), sy(cursor.y))
                    val gap = 3.dp.toPx()
                    val len = 7.dp.toPx()
                    val w = 1.5.dp.toPx()
                    drawLine(PointGray, Offset(c.x - gap - len, c.y), Offset(c.x - gap, c.y), w)
                    drawLine(PointGray, Offset(c.x + gap, c.y), Offset(c.x + gap + len, c.y), w)
                    drawLine(PointGray, Offset(c.x, c.y - gap - len), Offset(c.x, c.y - gap), w)
                    drawLine(PointGray, Offset(c.x, c.y + gap), Offset(c.x, c.y + gap + len), w)
                    pts.lastOrNull()?.let { last ->
                        drawLine(
                            Color(0x88FFFFFF), Offset(sx(last.x), sy(last.y)), c, 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
                        )
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().height(44.dp).background(KColors.Bg), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { editor.undo() }) { KIcon(IconKind.UNDO, if (editor.canUndo) KColors.Text else KColors.Dim) }
            IconButton(onClick = { editor.redo() }) { KIcon(IconKind.REDO, if (editor.canRedo) KColors.Text else KColors.Dim) }
            IconButton(onClick = { editor.playing = false; editor.time = (editor.time - editor.frame).coerceAtLeast(0f) }) { KIcon(IconKind.FRAME_BACK, KColors.Text) }
            IconButton(onClick = { editor.playing = !editor.playing }) {
                KIcon(if (editor.playing) IconKind.PAUSE else IconKind.PLAY, KColors.Text, Modifier.size(26.dp))
            }
            IconButton(onClick = { editor.playing = false; editor.time = (editor.time + editor.frame).coerceAtMost(editor.duration) }) { KIcon(IconKind.FRAME_FORWARD, KColors.Text) }
        }

        ScrubBar(editor, layer)

        Row(Modifier.fillMaxWidth().height(172.dp).background(KColors.Bg)) {
            Column(Modifier.width(54.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
                PadButton(IconKind.BACK, KColors.Text, onClose)
                PadButton(IconKind.SELECT, if (pts.isEmpty()) KColors.Dim else KColors.Text) { cycleSelection() }
                PadButton(IconKind.GRAPH, if (curveOn) PointGreen else KColors.Muted) { toggleCurve() }
                if (sel >= 0) PadButton(IconKind.DELETE, KColors.Text) { deleteSelected() }
                else PadButton(IconKind.PLUS, KColors.Text) { place(cursor) }
            }
            Box(
                Modifier.weight(1f).fillMaxHeight().padding(vertical = 4.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF2A2A2A))
                    .pointerInput(Unit) { detectTapGestures(onTap = { if (sel < 0) place(cursor) }) }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { dragStarted = false },
                            onDragEnd = { dragStarted = false },
                            onDragCancel = { dragStarted = false }
                        ) { change, amount ->
                            change.consume()
                            movePad(amount.x, amount.y)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (sel < 0) "Swipe here to position next point, then tap here to place it" else "Swipe to move point",
                    color = KColors.Dim, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp)
                )
            }
            Canvas(Modifier.width(84.dp).fillMaxHeight().padding(vertical = 4.dp).clipToBounds()) {
                val focus = if (sel in pts.indices) pts[sel] else null
                val fx = (focus?.x ?: cursor.x) * COMP_W
                val fy = (focus?.y ?: cursor.y) * COMP_H
                val z = compScale() * 4f
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
                        drawCircle(PointGray, 3.dp.toPx(), c)
                    }
                }
                drawCircle(if (focus != null) PointGreen else PointGray, 6.dp.toPx(), Offset(size.width / 2f, size.height / 2f), style = if (focus != null) androidx.compose.ui.graphics.drawscope.Fill else Stroke(1.5.dp.toPx()))
            }
        }
    }
}

@Composable
private fun PadButton(icon: IconKind, tint: Color, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        KIcon(icon, tint, Modifier.size(22.dp))
    }
}

@Composable
private fun ScrubBar(editor: EditorState, layer: Layer) {
    Box(
        Modifier.fillMaxWidth().height(52.dp).background(KColors.Surface)
            .pointerInput(Unit) {
                detectTapGestures { tap ->
                    editor.playing = false
                    editor.time = (tap.x / size.width * editor.duration).coerceIn(0f, editor.duration)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(onDragStart = { editor.playing = false }) { change, _ ->
                    change.consume()
                    editor.time = (change.position.x / size.width * editor.duration).coerceIn(0f, editor.duration)
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val duration = editor.duration.coerceAtLeast(0.1f)
            val x0 = layer.start / duration * size.width
            val x1 = (layer.end / duration * size.width).coerceAtMost(size.width)
            drawRoundRect(
                KColors.Accent.copy(alpha = .85f), Offset(x0, size.height * 0.45f),
                Size((x1 - x0).coerceAtLeast(2f), 20.dp.toPx()), CornerRadius(4.dp.toPx())
            )
            val px = editor.time / duration * size.width
            drawLine(Color.White, Offset(px, 0f), Offset(px, size.height), 2.dp.toPx())
        }
        Text(
            "${layer.name}  \u00B7  ${fmt("%.2f", editor.time)}s", color = KColors.Muted, fontSize = 11.sp,
            modifier = Modifier.padding(start = 10.dp, top = 5.dp)
        )
    }
}
