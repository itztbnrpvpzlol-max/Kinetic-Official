package com.kinetic.app.ui

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinetic.app.model.COMP_H
import com.kinetic.app.model.COMP_W
import com.kinetic.app.model.EditorState
import com.kinetic.app.model.Layer
import com.kinetic.app.model.LayerMask
import com.kinetic.app.model.PropId
import com.kinetic.app.model.MaskStroke
import com.kinetic.app.model.VectorPoint
import com.kinetic.app.model.layerMatrix
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val maskTools = listOf("Pen", "Draw", "Ellipse", "Roto Brush")

@Composable
fun MaskingPanel(
    editor: EditorState, layer: Layer, activeTool: String, onToolChange: (String) -> Unit,
    rotoForeground: Boolean, onRotoForegroundChange: (Boolean) -> Unit, modifier: Modifier = Modifier
) {
    val mask = layer.maskAt(editor.time) ?: LayerMask(kind = activeTool)
    val activeProp = editor.selectedProp
    val keyExists = activeProp?.let { id -> layer.p(id).keys.any { kotlin.math.abs(it.t - editor.time) <= editor.frame / 2f } } == true
    Column(modifier.fillMaxSize().background(KColors.Surface).padding(horizontal = 9.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().height(27.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("MASK PATH", color = KColors.Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(if (layer.maskEnabled) "ON" else "OFF", color = if (layer.maskEnabled) KColors.Accent else KColors.Muted,
                fontSize = 9.sp, modifier = Modifier.clickable { editor.updateLayer(layer.id) { it.copy(maskEnabled = !it.maskEnabled) } }.padding(horizontal = 7.dp))
            Box(Modifier.size(29.dp).clip(RoundedCornerShape(6.dp))
                .background(if (keyExists) KColors.Accent.copy(alpha = .16f) else KColors.Bg)
                .clickable(enabled = activeProp != null) { activeProp?.let { editor.toggleKeyAtTime(layer.id, it) } },
                contentAlignment = Alignment.Center) {
                KIcon(if (keyExists) IconKind.DIAMOND_FILLED else IconKind.DIAMOND,
                    if (activeProp != null) KColors.Accent else KColors.Dim, Modifier.size(17.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            maskTools.forEach { tool ->
                val selected = activeTool == tool
                Box(Modifier.weight(1f).height(28.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (selected) KColors.Accent.copy(alpha = .16f) else KColors.Bg)
                    .border(1.dp, if (selected) KColors.Accent else KColors.Line, RoundedCornerShape(6.dp))
                    .clickable { onToolChange(tool); editor.updateLayer(layer.id) { c ->
                        c.copy(maskEnabled = true, maskPath = (c.maskPath ?: LayerMask(kind = tool)).copy(kind = tool))
                    } }, contentAlignment = Alignment.Center) {
                    Text(tool, color = if (selected) KColors.Accent else KColors.Text, fontSize = 8.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
        MaskPointWorkspace(editor, layer, activeTool)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("Add", "Subtract").forEach { mode ->
                val selected = mask.mode == mode
                Box(Modifier.weight(1f).height(24.dp).clip(RoundedCornerShape(5.dp))
                    .background(if (selected) KColors.Accent.copy(alpha = .13f) else KColors.Bg)
                    .clickable { editor.updateMask(layer.id, mask.copy(mode = mode), push = true) }, contentAlignment = Alignment.Center) {
                    Text(mode, color = if (selected) KColors.Accent else KColors.Muted, fontSize = 8.sp)
                }
            }
            MaskChip(if (mask.closed) "Open" else "Close", mask.closed, Modifier.weight(1f)) {
                editor.updateMask(layer.id, mask.copy(closed = !mask.closed), push = true)
            }
            MaskChip(if (mask.inverted) "Invert ✓" else "Invert", mask.inverted, Modifier.weight(1f)) {
                editor.updateMask(layer.id, mask.copy(inverted = !mask.inverted), push = true)
            }
        }
        if (activeTool == "Roto Brush") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                MaskChip("Foreground", rotoForeground, Modifier.weight(1f)) { onRotoForegroundChange(true) }
                MaskChip("Background", !rotoForeground, Modifier.weight(1f)) { onRotoForegroundChange(false) }
                MaskChip(if (mask.frozen) "Unfreeze" else "Freeze", mask.frozen, Modifier.weight(1f)) {
                    editor.updateMask(layer.id, mask.copy(frozen = !mask.frozen), push = true)
                }
            }
        }
        Text("MASK PROPERTIES", color = KColors.Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
        MaskValueRow(editor, layer, "Opacity", PropId.MASK_OPACITY, mask.opacity, 0f, 100f, 0.5f, "%.0f%%")
        MaskValueRow(editor, layer, "Feather", PropId.MASK_FEATHER, mask.feather, 0f, 100f, 0.5f, "%.0f px")
        MaskValueRow(editor, layer, "Expansion", PropId.MASK_EXPANSION, mask.expansion, -50f, 50f, 0.5f, "%+.0f px")
        if (activeTool == "Draw" || activeTool == "Roto Brush")
            MaskValueRow(editor, layer, "Brush", PropId.MASK_BRUSH_SIZE, mask.brushSize * 100f, 1f, 18f, 0.08f, "%.1f%%")
    }
}

@Composable
private fun MaskPointWorkspace(editor: EditorState, layer: Layer, tool: String) {
    var pending by remember(layer.id, tool) { mutableStateOf<VectorPoint?>(null) }
    Row(Modifier.fillMaxWidth().height(86.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Column(Modifier.width(34.dp).fillMaxHeight().background(KColors.Bg, RoundedCornerShape(6.dp)),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
            Text("↖", color = KColors.Muted, fontSize = 17.sp)
            Text("◎", color = if (tool == "Ellipse") KColors.Accent else KColors.Muted, fontSize = 17.sp)
            Text("⌁", color = if (tool == "Pen") KColors.Accent else KColors.Muted, fontSize = 17.sp)
            Text("+", color = KColors.Accent, fontSize = 19.sp)
        }
        Canvas(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(6.dp))
            .background(KColors.Bg)
            .pointerInput(layer.id, tool, pending) {
                detectTapGestures { p ->
                    val candidate = pending ?: VectorPoint((p.x / size.width).coerceIn(0f, 1f), (p.y / size.height).coerceIn(0f, 1f))
                    editor.addMaskPoint(layer.id, candidate)
                    pending = null
                }
            }
            .pointerInput(layer.id, tool) {
                detectDragGestures(onDragStart = { p ->
                    pending = VectorPoint((p.x / size.width).coerceIn(0f, 1f), (p.y / size.height).coerceIn(0f, 1f))
                }) { change, _ ->
                    change.consume()
                    pending = VectorPoint((change.position.x / size.width).coerceIn(0f, 1f), (change.position.y / size.height).coerceIn(0f, 1f))
                }
            }) {
            val points = layer.maskPath?.points.orEmpty()
            for (i in 1 until points.size) {
                drawLine(KColors.Accent, Offset(points[i-1].x * size.width, points[i-1].y * size.height),
                    Offset(points[i].x * size.width, points[i].y * size.height), strokeWidth = 2.dp.toPx())
            }
            points.forEach { drawCircle(Color.White, 4.dp.toPx(), Offset(it.x * size.width, it.y * size.height)) }
            pending?.let { drawCircle(KColors.Accent, 5.dp.toPx(), Offset(it.x * size.width, it.y * size.height)) }
            if (points.isEmpty()) drawContext.canvas.nativeCanvas.let { canvas ->
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.LTGRAY; textSize = 11.sp.toPx() }
                canvas.drawText("Swipe to position next point, then tap to place it", 8.dp.toPx(), size.height / 2f, paint)
            }
        }
    }
}

@Composable
private fun MaskValueRow(editor: EditorState, layer: Layer, label: String, id: PropId, fallback: Float,
    min: Float, max: Float, step: Float, format: String) {
    val value = layer.props[id]?.valueAt(editor.time) ?: fallback
    Row(Modifier.fillMaxWidth().height(29.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = if (editor.selectedProp == id) KColors.Accent else KColors.Text, fontSize = 10.sp,
            modifier = Modifier.width(65.dp).clickable { editor.selectProp(id) })
        ScrubValueBar(label = "", value = value, modifier = Modifier.weight(1f), step = step,
            selected = editor.selectedProp == id, minValue = min, maxValue = max, valueFormat = format,
            onSelect = { editor.selectProp(id) }) { next ->
            editor.selectProp(id)
            editor.updateLayer(layer.id, push = false) { current ->
                val old = current.maskPath ?: LayerMask()
                val updated = current.p(id).withValueAt(editor.time, next, editor.frame / 2f)
                val changed = when (id) {
                    PropId.MASK_OPACITY -> old.copy(opacity = next)
                    PropId.MASK_FEATHER -> old.copy(feather = next)
                    PropId.MASK_EXPANSION -> old.copy(expansion = next)
                    else -> old.copy(brushSize = next / 100f)
                }
                current.copy(maskEnabled = true, maskPath = changed, props = current.props + (id to updated))
            }
        }
    }
}

@Composable
private fun MaskChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.height(24.dp).clip(RoundedCornerShape(5.dp))
        .background(if (selected) KColors.Accent.copy(alpha = .14f) else KColors.Bg)
        .border(1.dp, if (selected) KColors.Accent.copy(alpha = .55f) else KColors.Line, RoundedCornerShape(5.dp))
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = if (selected) KColors.Accent else KColors.Text, fontSize = 8.sp, fontWeight = FontWeight.Medium)
    }
}

/** Preview overlay: the same composition/layer coordinates drive drawing and touch editing. */
@Composable
fun MaskEditingOverlay(
    editor: EditorState,
    tool: String,
    rotoForeground: Boolean,
    modifier: Modifier = Modifier
) {
    val layer = editor.selected ?: return
    if (tool.isBlank() || layer.type == com.kinetic.app.model.LayerType.AUDIO) return
    Canvas(modifier
        .pointerInput(layer.id, tool) {
            detectTapGestures { offset ->
                val coords = toMaskPoint(offset, size.width.toFloat(), size.height.toFloat(), layer, editor)
                if (tool == "Pen") editor.addMaskPoint(layer.id, coords)
                else if (tool == "Ellipse") {
                    val points = (0 until 40).map { i ->
                        val a = i * 2f * Math.PI.toFloat() / 40f
                        VectorPoint((coords.x + .18f * kotlin.math.cos(a)).coerceIn(0f, 1f),
                            (coords.y + .18f * kotlin.math.sin(a)).coerceIn(0f, 1f))
                    }
                    val current = layer.maskPath ?: LayerMask(kind = "Ellipse")
                    editor.updateMask(layer.id, current.copy(kind = "Ellipse", points = points, closed = true), push = true)
                }
            }
        }
        .pointerInput(layer.id, tool) {
            var activePoint = -1
            var strokePoints = mutableListOf<VectorPoint>()
            var dragStart: VectorPoint? = null
            detectDragGestures(
                onDragStart = { offset ->
                    val point = toMaskPoint(offset, size.width.toFloat(), size.height.toFloat(), layer, editor)
                    if (tool == "Pen") {
                        val points = editor.selected?.maskPath?.points.orEmpty()
                        activePoint = points.indices.minByOrNull { i ->
                            val dx = points[i].x - point.x; val dy = points[i].y - point.y
                            dx * dx + dy * dy
                        }?.takeIf { i ->
                            val p = points[i]
                            val dx = p.x - point.x; val dy = p.y - point.y
                            dx * dx + dy * dy < .012f
                        } ?: -1
                        if (activePoint >= 0) editor.beginEdit()
                    } else {
                        activePoint = -1
                        strokePoints = mutableListOf(point)
                        dragStart = point
                    }
                },
                onDrag = { change, _ ->
                    change.consume()
                    val point = toMaskPoint(change.position, size.width.toFloat(), size.height.toFloat(), layer, editor)
                    if (tool == "Pen" && activePoint >= 0) {
                        val current = editor.selected?.maskPath ?: return@detectDragGestures
                        val next = current.points.toMutableList()
                        if (activePoint in next.indices) next[activePoint] = point
                        editor.updateMask(layer.id, current.copy(points = next), push = false)
                    } else if (tool == "Draw" || tool == "Roto Brush") {
                        if (strokePoints.isEmpty() || abs(strokePoints.last().x - point.x) + abs(strokePoints.last().y - point.y) > .0015f) {
                            strokePoints.add(point)
                        }
                    } else if (tool == "Ellipse") {
                        val start = dragStart ?: point
                        val center = VectorPoint((start.x + point.x) / 2f, (start.y + point.y) / 2f)
                        val rx = abs(point.x - start.x) / 2f
                        val ry = abs(point.y - start.y) / 2f
                        val points = (0 until 40).map { i ->
                            val a = i * 2f * Math.PI.toFloat() / 40f
                            VectorPoint((center.x + rx * kotlin.math.cos(a)).coerceIn(0f, 1f),
                                (center.y + ry * kotlin.math.sin(a)).coerceIn(0f, 1f))
                        }
                        val current = editor.selected?.maskPath ?: LayerMask(kind = "Ellipse")
                        editor.updateMask(layer.id, current.copy(kind = "Ellipse", points = points, closed = true), push = false)
                    }
                },
                onDragEnd = {
                    if ((tool == "Draw" || tool == "Roto Brush") && strokePoints.size > 1) {
                        val current = editor.selected?.maskPath ?: LayerMask(kind = tool)
                        editor.addMaskStroke(layer.id, MaskStroke(strokePoints.toList(), current.brushSize, rotoForeground))
                    }
                    activePoint = -1
                    strokePoints.clear()
                    dragStart = null
                }
            )
        }
    ) {
        val byId = editor.layers.associateBy { it.id }
        val scale = minOf(size.width / COMP_W, size.height / COMP_H)
        val ox = (size.width - COMP_W * scale) / 2f
        val oy = (size.height - COMP_H * scale) / 2f
        val matrix = layerMatrix(layer, editor.time, byId)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(75, 160, 255)
            style = Paint.Style.STROKE
            strokeWidth = 2.2f / scale
        }
        val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.FILL
        }
        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            nc.save()
            nc.translate(ox, oy)
            nc.scale(scale, scale)
            nc.concat(matrix)
            val points = layer.maskPath?.points.orEmpty()
            val path = buildMaskContour(points, layer.width, layer.height, layer.maskPath?.closed == true)
            if (points.size > 1) nc.drawPath(path, paint)
            points.forEach { p -> nc.drawCircle(p.x * layer.width, p.y * layer.height, 7f / scale, handlePaint) }
            layer.maskPath?.strokes.orEmpty().forEach { stroke ->
                if (stroke.points.size > 1) {
                    val strokePath = AndroidPath()
                    stroke.points.forEachIndexed { i, p ->
                        val x = p.x * layer.width; val y = p.y * layer.height
                        if (i == 0) strokePath.moveTo(x, y) else strokePath.lineTo(x, y)
                    }
                    paint.style = Paint.Style.STROKE
                    paint.strokeCap = Paint.Cap.ROUND
                    paint.strokeJoin = Paint.Join.ROUND
                    paint.strokeWidth = stroke.width * min(layer.width, layer.height)
                    paint.color = if (stroke.foreground) android.graphics.Color.rgb(75,160,255)
                        else android.graphics.Color.rgb(255,130,90)
                    nc.drawPath(strokePath, paint)
                }
            }
            nc.restore()
        }
    }
}

private fun toMaskPoint(offset: Offset, width: Float, height: Float, layer: Layer, editor: EditorState): VectorPoint {
    val scale = min(width / COMP_W, height / COMP_H).coerceAtLeast(.0001f)
    val ox = (width - COMP_W * scale) / 2f
    val oy = (height - COMP_H * scale) / 2f
    val points = floatArrayOf((offset.x - ox) / scale, (offset.y - oy) / scale)
    val inverse = Matrix()
    if (layerMatrix(layer, editor.time, editor.layers.associateBy { it.id }).invert(inverse)) inverse.mapPoints(points)
    return VectorPoint((points[0] / layer.width.coerceAtLeast(1f)).coerceIn(0f, 1f),
        (points[1] / layer.height.coerceAtLeast(1f)).coerceIn(0f, 1f))
}
