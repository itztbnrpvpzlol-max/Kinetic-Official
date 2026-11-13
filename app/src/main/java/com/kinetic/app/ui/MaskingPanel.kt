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
import androidx.compose.material3.Slider
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
import com.kinetic.app.model.MaskStroke
import com.kinetic.app.model.VectorPoint
import com.kinetic.app.model.layerMatrix
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val maskTools = listOf("Pen", "Draw", "Ellipse", "Roto Brush")

@Composable
fun MaskingPanel(
    editor: EditorState,
    layer: Layer,
    activeTool: String,
    onToolChange: (String) -> Unit,
    rotoForeground: Boolean,
    onRotoForegroundChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val mask = layer.maskPath ?: LayerMask(kind = activeTool)
    Column(modifier.fillMaxSize().background(KColors.Surface).verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("MASK TOOLS", color = KColors.Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            Text(if (layer.maskEnabled) "ON" else "OFF", color = if (layer.maskEnabled) KColors.Accent else KColors.Muted,
                fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text(if (layer.maskEnabled) "  Disable" else "  Enable", color = KColors.Text, fontSize = 9.sp,
                modifier = Modifier.clip(RoundedCornerShape(5.dp)).clickable {
                    editor.updateLayer(layer.id) { it.copy(maskEnabled = !it.maskEnabled) }
                }.padding(horizontal = 5.dp, vertical = 3.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            maskTools.forEach { tool ->
                val selected = activeTool == tool
                Box(Modifier.weight(1f).height(30.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (selected) KColors.Accent.copy(alpha = .16f) else KColors.Bg)
                    .border(1.dp, if (selected) KColors.Accent else KColors.Line, RoundedCornerShape(6.dp))
                    .clickable {
                        onToolChange(tool)
                        editor.updateLayer(layer.id) { current ->
                            current.copy(maskEnabled = true,
                                maskPath = (current.maskPath ?: LayerMask(kind = tool)).copy(kind = tool))
                        }
                    }, contentAlignment = Alignment.Center) {
                    Text(tool, color = if (selected) KColors.Accent else KColors.Text,
                        fontSize = 8.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            listOf("Add", "Subtract").forEach { mode ->
                val selected = mask.mode == mode
                Box(Modifier.weight(1f).height(28.dp).clip(RoundedCornerShape(5.dp))
                    .background(if (selected) KColors.Accent.copy(alpha = .13f) else KColors.Bg)
                    .clickable { editor.updateMask(layer.id, mask.copy(mode = mode), push = true) },
                    contentAlignment = Alignment.Center) {
                    Text(mode, color = if (selected) KColors.Accent else KColors.Muted, fontSize = 7.sp,
                        maxLines = 1)
                }
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
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                MaskChip(if (mask.closed) "Open path" else "Close path", mask.closed, Modifier.weight(1f)) {
                    editor.updateMask(layer.id, mask.copy(closed = !mask.closed), push = true)
                }
                MaskChip(if (mask.inverted) "Inverted" else "Invert", mask.inverted, Modifier.weight(1f)) {
                    editor.updateMask(layer.id, mask.copy(inverted = !mask.inverted), push = true)
                }
                MaskChip("Clear", false, Modifier.weight(1f)) { editor.clearMask(layer.id) }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MaskSlider("Feather", mask.feather, 0f, 100f, "%.0f px") {
                editor.updateMask(layer.id, mask.copy(feather = it), push = false)
            }
            Spacer(Modifier.width(8.dp))
            MaskSlider("Expansion", mask.expansion, -50f, 50f, "%+.0f px") {
                editor.updateMask(layer.id, mask.copy(expansion = it), push = false)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MaskSlider("Opacity", mask.opacity, 0f, 100f, "%.0f%%") {
                editor.updateMask(layer.id, mask.copy(opacity = it), push = false)
            }
            Spacer(Modifier.width(8.dp))
            if (activeTool == "Draw" || activeTool == "Roto Brush") {
                MaskSlider("Brush", mask.brushSize * 100f, 1f, 18f, "%.1f%%") {
                    editor.updateMask(layer.id, mask.copy(brushSize = it / 100f), push = false)
                }
            } else {
                Text("${mask.points.size} points", color = KColors.Muted, fontSize = 8.sp)
            }
        }
        Text(
            when (activeTool) {
                "Pen" -> "Tap the preview to add vertices. Drag a point to refine the mask path."
                "Ellipse" -> "Drag across the preview to create an ellipse mask. Drag its points to reshape it."
                "Draw" -> "Paint directly on the preview to build a freehand mask."
                else -> "Paint foreground and background strokes, then freeze the matte when ready."
            },
            color = KColors.Muted, fontSize = 8.sp, maxLines = 2
        )
    }
}

@Composable
private fun MaskChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.height(25.dp).clip(RoundedCornerShape(5.dp))
        .background(if (selected) KColors.Accent.copy(alpha = .14f) else KColors.Bg)
        .border(1.dp, if (selected) KColors.Accent.copy(alpha = .55f) else KColors.Line, RoundedCornerShape(5.dp))
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = if (selected) KColors.Accent else KColors.Text, fontSize = 8.sp,
            fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun RowScope.MaskSlider(
    label: String, value: Float, min: Float, max: Float, format: String, onValue: (Float) -> Unit
) {
    Column(Modifier.weight(1f)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = KColors.Muted, fontSize = 8.sp, modifier = Modifier.weight(1f))
            Text(String.format(java.util.Locale.US, format, value), color = KColors.Text, fontSize = 8.sp)
        }
        Slider(value = value.coerceIn(min, max), onValueChange = onValue, valueRange = min..max)
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
