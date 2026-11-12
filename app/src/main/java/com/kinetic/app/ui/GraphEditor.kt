package com.kinetic.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinetic.app.model.EditorState
import com.kinetic.app.model.KeyType
import com.kinetic.app.model.Keyframe
import com.kinetic.app.model.Layer
import com.kinetic.app.model.PropId
import com.kinetic.app.model.cubicProgress
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.hypot

private enum class GraphMode { EASE, VALUE, SPEED }
private data class GraphPreset(val label: String, val type: KeyType, val x1: Float, val y1: Float, val x2: Float, val y2: Float)
private val graphPresets = listOf(
    GraphPreset("Linear", KeyType.LINEAR, 1f/3f, 1f/3f, 2f/3f, 2f/3f),
    GraphPreset("Ease out", KeyType.BEZIER, 0.42f, 0f, 1f, 1f),
    GraphPreset("Easy ease", KeyType.BEZIER, 0.3333f, 0f, 0.6667f, 1f),
    GraphPreset("Ease in", KeyType.BEZIER, 0f, 0f, 0.58f, 1f),
    GraphPreset("Ease in/out", KeyType.BEZIER, 0.42f, 0f, 0.58f, 1f)
)
private fun graphSegment(keys: List<Keyframe>, selected: Int): Int =
    if (keys.size < 2) -1 else when {
        selected in 0 until keys.lastIndex -> selected
        selected > 0 && selected in keys.indices -> selected - 1
        else -> 0
    }

@Composable
fun GraphPanel(editor: EditorState, layer: Layer, modifier: Modifier = Modifier) {
    val selected = editor.selectedProp ?: PropId.POS_X
    var mode by remember(layer.id) { mutableStateOf(GraphMode.EASE) }
    var presetSelection by remember(layer.id) { mutableIntStateOf(0) }
    val currentLayer = editor.layers.firstOrNull { it.id == layer.id } ?: layer
    val prop = currentLayer.p(selected)
    val keys = prop.keys
    val segment = graphSegment(keys, editor.selectedKey)

    Column(modifier.fillMaxSize().background(Color(0xFF080809))) {
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            GraphTab("Ease", mode == GraphMode.EASE) { mode = GraphMode.EASE }
            GraphTab("Value", mode == GraphMode.VALUE) { mode = GraphMode.VALUE }
            GraphTab("Speed", mode == GraphMode.SPEED) { mode = GraphMode.SPEED }
            Spacer(Modifier.weight(1f))
            Text(selected.label + if (segment >= 0) "  ◆" + (segment + 1) + " → ◆" + (segment + 2) else "",
                color = KColors.Muted, fontSize = 10.sp, maxLines = 1)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)).background(KColors.Surface2).clickable {
                if (!prop.animated) {
                    editor.toggleStopwatch(layer.id, selected)
                } else {
                    editor.beginEdit()
                    val value = prop.valueAt(editor.time)
                    editor.setPropValue(layer.id, selected, value)
                    val latest = editor.layers.firstOrNull { it.id == layer.id }?.p(selected)?.keys ?: emptyList()
                    val idx = latest.indices.minByOrNull { abs(latest[it].t - editor.time) } ?: -1
                    if (idx >= 0) editor.selectKey(selected, idx, latest[idx].t)
                }
            }, contentAlignment = Alignment.Center) {
                Text("+", color = KColors.Text, fontSize = 25.sp, fontWeight = FontWeight.Light)
            }
        }

        val animated = PropId.values().filter { currentLayer.p(it).animated }
        if (animated.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                animated.forEach { id ->
                    val active = id == selected
                    Box(Modifier.clip(RoundedCornerShape(7.dp))
                        .background(if (active) KColors.Accent.copy(alpha = 0.18f) else KColors.Surface2)
                        .border(1.dp, if (active) KColors.Accent.copy(alpha = 0.55f) else KColors.Line, RoundedCornerShape(7.dp))
                        .clickable { editor.selectProp(id) }.padding(horizontal = 9.dp, vertical = 5.dp)) {
                        Text(id.label, color = if (active) KColors.Accent else KColors.Muted, fontSize = 9.sp)
                    }
                }
            }
        }

        if (keys.size < 2) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (keys.isEmpty()) "No keyframes on " + selected.label else "Add a second keyframe to edit a curve",
                        color = KColors.Text, fontSize = 14.sp, textAlign = TextAlign.Center)
                    Text("Move the playhead, change the property value, then add a keyframe with +.",
                        color = KColors.Muted, fontSize = 11.sp, textAlign = TextAlign.Center)
                    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(KColors.Accent).clickable {
                        if (keys.isEmpty()) editor.toggleStopwatch(layer.id, selected)
                        else {
                            editor.beginEdit()
                            editor.setPropValue(layer.id, selected, prop.valueAt(editor.time))
                            val latest = editor.layers.firstOrNull { it.id == layer.id }?.p(selected)?.keys ?: emptyList()
                            val idx = latest.indices.minByOrNull { abs(latest[it].t - editor.time) } ?: -1
                            if (idx >= 0) editor.selectKey(selected, idx, latest[idx].t)
                        }
                    }.padding(horizontal = 14.dp, vertical = 9.dp)) {
                        Text("Add keyframe", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        } else {
            Row(Modifier.weight(1f).fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
                Column(Modifier.width(34.dp).fillMaxHeight().padding(end = 5.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    listOf(IconKind.CLOSE, IconKind.UNDO, IconKind.COPY, IconKind.ROTATE).forEach { icon ->
                        Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) { KIcon(icon, KColors.Muted, Modifier.size(17.dp)) }
                    }
                    Spacer(Modifier.weight(1f))
                    Text("⋮", color = KColors.Muted, fontSize = 22.sp)
                }
                GraphCanvas(
                    modifier = Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF050506)).border(1.dp, Color(0xFF19191D), RoundedCornerShape(12.dp)),
                    mode = mode, keys = keys, segment = segment, selectedKey = editor.selectedKey,
                    property = selected, layer = currentLayer, duration = editor.duration, playhead = editor.time,
                    onBeginEdit = { editor.beginEdit() },
                    onSelectKey = { index -> editor.selectKey(selected, index, keys[index].t) },
                    onSeek = { editor.seek(it) },
                    onHandleChange = { outgoing, x, y ->
                        if (segment in keys.indices && segment < keys.lastIndex) {
                            val key = keys[segment]
                            val changed = if (outgoing) key.copy(type = KeyType.BEZIER, x1 = x.coerceIn(0f, 1f), y1 = y.coerceIn(-0.2f, 1.2f))
                            else key.copy(type = KeyType.BEZIER, x2 = x.coerceIn(0f, 1f), y2 = y.coerceIn(-0.2f, 1.2f))
                            editor.replaceKey(layer.id, selected, segment, changed, push = false)
                        }
                    }
                )
                Column(Modifier.width(58.dp).fillMaxHeight().padding(start = 6.dp), verticalArrangement = Arrangement.spacedBy(7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    graphPresets.forEachIndexed { index, preset ->
                        GraphPresetButton(preset, index == presetSelection, Modifier.weight(1f)) {
                            presetSelection = index
                            if (segment >= 0) {
                                editor.beginEdit()
                                editor.replaceKey(layer.id, selected, segment,
                                    keys[segment].copy(type = preset.type, x1 = preset.x1, y1 = preset.y1, x2 = preset.x2, y2 = preset.y2),
                                    push = false)
                            }
                        }
                    }
                }
            }
            Text(when (mode) {
                GraphMode.EASE -> "Drag Bézier handles to shape the transition."
                GraphMode.VALUE -> "Value graph · vertical axis is the property value."
                GraphMode.SPEED -> "Speed graph · vertical axis is the rate of change."
            }, color = KColors.Dim, fontSize = 9.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp))
        }
    }
}

@Composable
private fun GraphTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(70.dp).fillMaxHeight().clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(label, color = if (selected) Color(0xFF36C96B) else KColors.Muted, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        Spacer(Modifier.height(7.dp))
        Box(Modifier.fillMaxWidth().height(2.dp).background(if (selected) Color(0xFF36C96B) else Color.Transparent))
    }
}

@Composable
private fun GraphPresetButton(preset: GraphPreset, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(8.dp)).background(if (selected) KColors.Accent.copy(alpha = 0.18f) else KColors.Surface2)
        .border(1.dp, if (selected) KColors.Accent else KColors.Line, RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val left = 4.dp.toPx(); val top = 5.dp.toPx()
            val w = size.width - left * 2; val h = size.height - top * 2
            val path = Path()
            for (i in 0..32) {
                val u = i / 32f
                val v = if (preset.type == KeyType.LINEAR) u else cubicProgress(u, preset.x1, preset.y1, preset.x2, preset.y2)
                val p = Offset(left + w * u, top + h * (1f - v))
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            drawPath(path, if (selected) Color(0xFF36C96B) else Color.White, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round))
        }
        Text(preset.label, color = if (selected) KColors.Accent else KColors.Muted, fontSize = 7.sp, maxLines = 1)
    }
}

@Composable
private fun GraphCanvas(
    modifier: Modifier, mode: GraphMode, keys: List<Keyframe>, segment: Int, selectedKey: Int,
    property: PropId, layer: Layer, duration: Float, playhead: Float,
    onBeginEdit: () -> Unit, onSelectKey: (Int) -> Unit, onSeek: (Float) -> Unit,
    onHandleChange: (Boolean, Float, Float) -> Unit
) {
    var dragHandle by remember { mutableIntStateOf(0) }
    Canvas(modifier.pointerInput(mode, segment, selectedKey) {
        val pad = 18.dp.toPx()
        val left = pad; val right = size.width - pad; val top = pad; val bottom = size.height - pad
        fun px(x: Float) = left + x.coerceIn(0f, 1f) * (right - left)
        fun py(y: Float) = bottom - y.coerceIn(-0.2f, 1.2f) * (bottom - top)
        detectDragGestures(onDragStart = { point ->
            dragHandle = 0
            if (mode == GraphMode.EASE && segment >= 0) {
                val key = keys[segment]
                val p1 = Offset(px(key.x1), py(key.y1)); val p2 = Offset(px(key.x2), py(key.y2))
                val d1 = hypot(point.x - p1.x, point.y - p1.y)
                val d2 = hypot(point.x - p2.x, point.y - p2.y)
                if (min(d1, d2) < 28.dp.toPx()) dragHandle = if (d1 <= d2) 1 else 2
            }
            if (dragHandle != 0) onBeginEdit()
            else if (mode != GraphMode.EASE) {
                val u = ((point.x - left) / (right - left)).coerceIn(0f, 1f)
                val t = if (keys.size >= 2) keys.first().t + (keys.last().t - keys.first().t) * u else u * duration
                val idx = keys.indices.minByOrNull { abs(keys[it].t - t) } ?: -1
                if (idx >= 0) onSelectKey(idx)
            }
        }, onDragEnd = { dragHandle = 0 }, onDragCancel = { dragHandle = 0 }) { change, _ ->
            val delta = change.position - change.previousPosition
            val editingHandle = mode == GraphMode.EASE && dragHandle != 0
            val scrubbing = mode != GraphMode.EASE && abs(delta.x) >= abs(delta.y)
            if (!editingHandle && !scrubbing) return@detectDragGestures
            change.consume()
            if (mode == GraphMode.EASE && dragHandle != 0) {
                val nx = ((change.position.x - left) / (right - left)).coerceIn(0f, 1f)
                val ny = ((bottom - change.position.y) / (bottom - top)).coerceIn(-0.2f, 1.2f)
                onHandleChange(dragHandle == 1, nx, ny)
            } else {
                val u = ((change.position.x - left) / (right - left)).coerceIn(0f, 1f)
                onSeek(if (keys.size >= 2) keys.first().t + (keys.last().t - keys.first().t) * u else u * duration)
            }
        }
    }) {
        val pad = 18.dp.toPx(); val left = pad; val right = size.width - pad
        val top = pad; val bottom = size.height - pad; val w = right - left; val h = bottom - top
        val grid = Color(0xFF28282D)
        for (i in 0..4) {
            val x = left + w * i / 4f; val y = top + h * i / 4f
            drawLine(grid, Offset(x, top), Offset(x, bottom), 1.dp.toPx())
            drawLine(grid, Offset(left, y), Offset(right, y), 1.dp.toPx())
        }
        if (segment >= 0 && segment < keys.lastIndex) {
            val a = keys[segment]; val b = keys[segment + 1]
            val path = Path()
            for (i in 0..120) {
                val u = i / 120f
                val progress = when (mode) {
                    GraphMode.EASE -> if (a.type == KeyType.LINEAR) u else cubicProgress(u, a.x1, a.y1, a.x2, a.y2)
                    GraphMode.VALUE -> {
                        val t = a.t + (b.t - a.t) * u
                        val value = layer.p(property).valueAt(t)
                        val span = max(abs(b.v - a.v), max(abs(a.v) * 0.15f, 1f))
                        0.5f + (value - (a.v + b.v) / 2f) / (span * 1.5f)
                    }
                    GraphMode.SPEED -> {
                        val t = a.t + (b.t - a.t) * u
                        val dt = max((b.t - a.t) / 1000f, 0.0001f)
                        val v1 = layer.p(property).valueAt((t - dt).coerceAtLeast(a.t))
                        val v2 = layer.p(property).valueAt((t + dt).coerceAtMost(b.t))
                        val speed = abs(v2 - v1) / max(2f * dt, 0.0001f)
                        val base = abs(b.v - a.v) / max(b.t - a.t, 0.001f)
                        (speed / max(base * 2.2f, 1f)).coerceIn(0f, 1f)
                    }
                }
                val p = Offset(left + w * u, bottom - progress.coerceIn(-0.2f, 1.2f) * h)
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            drawPath(path, Color.White, style = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round))
            if (mode == GraphMode.EASE && a.type == KeyType.BEZIER) {
                val start = Offset(left, bottom); val end = Offset(right, top)
                val c1 = Offset(left + w * a.x1, bottom - h * a.y1)
                val c2 = Offset(left + w * a.x2, bottom - h * a.y2)
                drawLine(Color(0xFF36C96B), start, c1, 1.5.dp.toPx())
                drawLine(Color(0xFFFFB23F), end, c2, 1.5.dp.toPx())
                drawCircle(Color(0xFF36C96B), 7.dp.toPx(), c1)
                drawCircle(Color(0xFFFFB23F), 7.dp.toPx(), c2)
            }
            drawCircle(Color(0xFF36C96B), 5.dp.toPx(), Offset(left, bottom))
            drawCircle(Color.White, 5.dp.toPx(), Offset(right, top))
        }
        if (segment >= 0 && segment < keys.lastIndex && keys[segment].type == KeyType.LINEAR && mode == GraphMode.EASE) {
            drawLine(Color(0x66888888), Offset(left, bottom), Offset(right, top), 1.dp.toPx())
        }
        val u = if (keys.size >= 2) ((playhead - keys.first().t) / max(keys.last().t - keys.first().t, 0.001f)).coerceIn(0f, 1f) else 0f
        val playX = left + w * u
        drawLine(Color(0xCC36C96B), Offset(playX, top), Offset(playX, bottom), 1.dp.toPx())
    }
}
