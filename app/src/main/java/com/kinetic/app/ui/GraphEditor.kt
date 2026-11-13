package com.kinetic.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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

private enum class GraphMode { EASE, VALUE, SPEED }
private data class GraphPreset(
    val label: String, val type: KeyType,
    val x1: Float, val y1: Float, val x2: Float, val y2: Float
)

private val graphPresets = listOf(
    GraphPreset("Linear", KeyType.LINEAR, 1f / 3f, 1f / 3f, 2f / 3f, 2f / 3f),
    GraphPreset("Ease In", KeyType.BEZIER, 0.42f, 0f, 1f, 1f),
    GraphPreset("Easy Ease", KeyType.BEZIER, 0.3333f, 0f, 0.6667f, 1f),
    GraphPreset("Ease Out", KeyType.BEZIER, 0f, 0f, 0.58f, 1f),
    GraphPreset("In / Out", KeyType.BEZIER, 0.42f, 0f, 0.58f, 1f)
)

private fun graphSegment(keys: List<Keyframe>, selected: Int): Int =
    if (keys.size < 2) -1 else when {
        selected in 0 until keys.lastIndex -> selected
        selected > 0 && selected in keys.indices -> selected - 1
        else -> 0
    }

@Composable
fun GraphPanel(editor: EditorState, layer: Layer, modifier: Modifier = Modifier) {
    val currentLayer = editor.layers.firstOrNull { it.id == layer.id } ?: layer
    val animated = PropId.values().filter { currentLayer.p(it).keys.isNotEmpty() }
    val selected = editor.selectedProp?.takeIf { it in animated }
        ?: animated.firstOrNull()
        ?: PropId.POS_X
    var mode by remember(layer.id) { mutableStateOf(GraphMode.EASE) }
    val prop = currentLayer.p(selected)
    val keys = prop.keys
    val segment = graphSegment(keys, editor.selectedKey)
    val segmentKey = keys.getOrNull(segment)
    val hasBezier = segmentKey?.type == KeyType.BEZIER

    Column(modifier.fillMaxSize().background(KColors.Surface)) {
        Row(
            Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("GRAPH EDITOR", color = KColors.Text, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(selected.label, color = KColors.Muted, fontSize = 9.sp)
            }
            GraphModeButton("Ease", mode == GraphMode.EASE) { mode = GraphMode.EASE }
            Spacer(Modifier.width(5.dp))
            GraphModeButton("Value", mode == GraphMode.VALUE) { mode = GraphMode.VALUE }
            Spacer(Modifier.width(5.dp))
            GraphModeButton("Speed", mode == GraphMode.SPEED) { mode = GraphMode.SPEED }
        }

        if (animated.size > 1) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                animated.forEach { id ->
                    val active = id == selected
                    Box(
                        Modifier.clip(RoundedCornerShape(7.dp))
                            .background(if (active) KColors.Accent.copy(alpha = 0.16f) else KColors.Bg)
                            .border(1.dp, if (active) KColors.Accent.copy(alpha = 0.6f) else KColors.Line, RoundedCornerShape(7.dp))
                            .clickable { editor.selectProp(id) }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(id.label, color = if (active) KColors.Accent else KColors.Muted, fontSize = 9.sp)
                    }
                }
            }
        }

        if (keys.size < 2) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(22.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (keys.isEmpty()) "No keyframes on ${selected.label}" else "Add a second keyframe to shape a transition",
                        color = KColors.Text, fontSize = 14.sp, textAlign = TextAlign.Center
                    )
                    Text("Add another keyframe from the timeline or the diamond control.",
                        color = KColors.Muted, fontSize = 10.sp, textAlign = TextAlign.Center)
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                keys.zipWithNext().forEachIndexed { index, pair ->
                    val active = index == segment
                    Box(
                        Modifier.clip(RoundedCornerShape(6.dp))
                            .background(if (active) KColors.Accent.copy(alpha = 0.16f) else KColors.Bg)
                            .border(1.dp, if (active) KColors.Accent else KColors.Line, RoundedCornerShape(6.dp))
                            .clickable { editor.selectKey(selected, index, pair.first.t) }
                            .padding(horizontal = 9.dp, vertical = 5.dp)
                    ) {
                        Text("K${index + 1} → K${index + 2}", color = if (active) KColors.Accent else KColors.Muted, fontSize = 9.sp)
                    }
                }
            }

            GraphCanvas(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(KColors.Bg)
                    .border(1.dp, KColors.Line, RoundedCornerShape(12.dp)),
                mode = mode,
                keys = keys,
                segment = segment,
                property = selected,
                layer = currentLayer,
                onBeginEdit = { editor.beginEdit() },
                onHandleChange = { handle, x, y ->
                    if (segment in 0 until keys.lastIndex) {
                        val key = keys[segment]
                        val gap = 0.015f
                        val updated = when (handle) {
                            1 -> key.copy(
                                type = KeyType.BEZIER,
                                x1 = x.coerceIn(0f, (key.x2 - gap).coerceAtLeast(0f)),
                                y1 = y.coerceIn(-0.25f, 1.25f)
                            )
                            else -> key.copy(
                                type = KeyType.BEZIER,
                                x2 = x.coerceIn((key.x1 + gap).coerceAtMost(1f), 1f),
                                y2 = y.coerceIn(-0.25f, 1.25f)
                            )
                        }
                        editor.replaceKey(layer.id, selected, segment, updated, push = false)
                    }
                },
                onKeyValueChange = { index, value ->
                    if (index in keys.indices) {
                        editor.replaceKey(layer.id, selected, index, keys[index].copy(v = value), push = false)
                    }
                }
            )

            if (mode == GraphMode.EASE) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    graphPresets.forEach { preset ->
                        GraphPresetChip(preset, segmentKey?.let {
                            it.type == preset.type &&
                                (preset.type == KeyType.LINEAR ||
                                    (abs(it.x1 - preset.x1) < 0.025f && abs(it.y1 - preset.y1) < 0.025f &&
                                     abs(it.x2 - preset.x2) < 0.025f && abs(it.y2 - preset.y2) < 0.025f))
                        } == true) {
                            if (segment >= 0) {
                                editor.beginEdit()
                                editor.replaceKey(
                                    layer.id, selected, segment,
                                    keys[segment].copy(
                                        type = preset.type, x1 = preset.x1, y1 = preset.y1,
                                        x2 = preset.x2, y2 = preset.y2
                                    ), push = false
                                )
                            }
                        }
                    }
                }
                Text(
                    if (hasBezier) "Drag either colored control point. Each handle moves independently."
                    else "Linear segment · choose an easing preset to reveal its two Bézier handles.",
                    color = KColors.Muted, fontSize = 9.sp,
                    modifier = Modifier.fillMaxWidth().padding(start = 13.dp, end = 13.dp, bottom = 8.dp)
                )
            } else {
                Text(
                    if (mode == GraphMode.VALUE) "Drag a keyframe point vertically to change its value."
                    else "Speed is derived from the selected segment's value curve.",
                    color = KColors.Muted, fontSize = 9.sp,
                    modifier = Modifier.fillMaxWidth().padding(start = 13.dp, end = 13.dp, bottom = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun GraphModeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(7.dp))
            .background(if (selected) KColors.Accent.copy(alpha = 0.17f) else KColors.Bg)
            .border(1.dp, if (selected) KColors.Accent.copy(alpha = 0.65f) else KColors.Line, RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(label, color = if (selected) KColors.Accent else KColors.Muted, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun GraphPresetChip(preset: GraphPreset, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.height(34.dp).clip(RoundedCornerShape(8.dp))
            .background(if (selected) KColors.Accent.copy(alpha = 0.16f) else KColors.Bg)
            .border(1.dp, if (selected) KColors.Accent else KColors.Line, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick).padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Canvas(Modifier.size(27.dp, 20.dp)) {
            val path = Path()
            val left = 1.dp.toPx()
            val right = size.width - left
            val top = 2.dp.toPx()
            val bottom = size.height - top
            for (i in 0..24) {
                val u = i / 24f
                val v = if (preset.type == KeyType.LINEAR) u else cubicProgress(u, preset.x1, preset.y1, preset.x2, preset.y2)
                val p = Offset(left + (right - left) * u, bottom - (bottom - top) * v)
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            drawPath(path, if (selected) KColors.Accent else KColors.Text, style = Stroke(1.7.dp.toPx(), cap = StrokeCap.Round))
        }
        Text(preset.label, color = if (selected) KColors.Accent else KColors.Text, fontSize = 9.sp, maxLines = 1)
    }
}

@Composable
private fun GraphCanvas(
    modifier: Modifier,
    mode: GraphMode,
    keys: List<Keyframe>,
    segment: Int,
    property: PropId,
    layer: Layer,
    onBeginEdit: () -> Unit,
    onHandleChange: (Int, Float, Float) -> Unit,
    onKeyValueChange: (Int, Float) -> Unit
) {
    val latestHandleChange by rememberUpdatedState(onHandleChange)
    val latestValueChange by rememberUpdatedState(onKeyValueChange)
    val latestBeginEdit by rememberUpdatedState(onBeginEdit)
    val latestKeys by rememberUpdatedState(keys)
    val minValue = keys.minOfOrNull { it.v } ?: 0f
    val maxValue = keys.maxOfOrNull { it.v } ?: 1f
    val valueSpan = (maxValue - minValue).let { if (it < 0.0001f) max(abs(maxValue) * 0.2f, 1f) else it }
    val valueLow = minValue - valueSpan * 0.18f
    val valueHigh = maxValue + valueSpan * 0.18f
    val latestValueLow by rememberUpdatedState(valueLow)
    val latestValueHigh by rememberUpdatedState(valueHigh)

    Canvas(
        modifier.pointerInput(mode, segment) {
            var activeHandle = 0
            var activeKey = -1
            val pad = 30.dp.toPx()
            val left = pad
            val right = size.width - pad
            val top = pad
            val bottom = size.height - pad
            val width = (right - left).coerceAtLeast(1f)
            val height = (bottom - top).coerceAtLeast(1f)

            detectDragGestures(
                onDragStart = { point ->
                    activeHandle = 0
                    activeKey = -1
                    val currentKeys = latestKeys
                    if (segment !in 0 until currentKeys.lastIndex) return@detectDragGestures

                    if (mode == GraphMode.EASE && currentKeys[segment].type == KeyType.BEZIER) {
                        val key = currentKeys[segment]
                        val p1 = Offset(left + width * key.x1, bottom - height * ((key.y1 + 0.25f) / 1.5f))
                        val p2 = Offset(left + width * key.x2, bottom - height * ((key.y2 + 0.25f) / 1.5f))
                        val d1 = (point - p1).getDistance()
                        val d2 = (point - p2).getDistance()
                        val hit = 38.dp.toPx()
                        if (min(d1, d2) <= hit) activeHandle = if (d1 <= d2) 1 else 2
                    } else if (mode == GraphMode.VALUE) {
                        val low = latestValueLow
                        val high = latestValueHigh
                        val p0 = Offset(left, bottom - height * ((currentKeys[segment].v - low) / (high - low)))
                        val p1 = Offset(right, bottom - height * ((currentKeys[segment + 1].v - low) / (high - low)))
                        val d0 = (point - p0).getDistance()
                        val d1 = (point - p1).getDistance()
                        if (min(d0, d1) <= 38.dp.toPx()) activeKey = if (d0 <= d1) segment else segment + 1
                    }
                    if (activeHandle != 0 || activeKey >= 0) latestBeginEdit()
                },
                onDragEnd = { activeHandle = 0; activeKey = -1 },
                onDragCancel = { activeHandle = 0; activeKey = -1 }
            ) { change, _ ->
                if (activeHandle == 0 && activeKey < 0) return@detectDragGestures
                change.consume()
                val nx = ((change.position.x - left) / width).coerceIn(0f, 1f)
                if (activeHandle != 0) {
                    val ny = 1.5f * (1f - (change.position.y - top) / height) - 0.25f
                    latestHandleChange(activeHandle, nx, ny.coerceIn(-0.25f, 1.25f))
                } else {
                    val normalized = (1f - (change.position.y - top) / height).coerceIn(0f, 1f)
                    latestValueChange(activeKey, latestValueLow + normalized * (latestValueHigh - latestValueLow))
                }
            }
        }
    ) {
        val pad = 30.dp.toPx()
        val left = pad
        val right = size.width - pad
        val top = pad
        val bottom = size.height - pad
        val width = (right - left).coerceAtLeast(1f)
        val height = (bottom - top).coerceAtLeast(1f)
        val grid = KColors.Line.copy(alpha = 0.72f)

        for (i in 0..4) {
            val x = left + width * i / 4f
            val y = top + height * i / 4f
            drawLine(grid, Offset(x, top), Offset(x, bottom), 1.dp.toPx())
            drawLine(grid, Offset(left, y), Offset(right, y), 1.dp.toPx())
        }

        if (segment in 0 until keys.lastIndex) {
            val a = keys[segment]
            val b = keys[segment + 1]
            val path = Path()
            for (i in 0..140) {
                val u = i / 140f
                val y = when (mode) {
                    GraphMode.EASE -> if (a.type == KeyType.LINEAR) u else cubicProgress(u, a.x1, a.y1, a.x2, a.y2)
                    GraphMode.VALUE -> {
                        val t = a.t + (b.t - a.t) * u
                        val value = layer.p(property).valueAt(t)
                        ((value - valueLow) / (valueHigh - valueLow)).coerceIn(-0.25f, 1.25f)
                    }
                    GraphMode.SPEED -> {
                        val t = a.t + (b.t - a.t) * u
                        val dt = max((b.t - a.t) / 500f, 0.0005f)
                        val before = layer.p(property).valueAt((t - dt).coerceAtLeast(a.t))
                        val after = layer.p(property).valueAt((t + dt).coerceAtMost(b.t))
                        val speed = abs(after - before) / max(2f * dt, 0.0001f)
                        val base = abs(b.v - a.v) / max(b.t - a.t, 0.001f)
                        (speed / max(base * 3f, 1f)).coerceIn(0f, 1f)
                    }
                }
                val p = Offset(left + width * u, bottom - height * y)
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            drawPath(path, Color.White, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))

            if (mode == GraphMode.EASE) {
                val start = Offset(left, bottom)
                val end = Offset(right, top)
                if (a.type == KeyType.BEZIER) {
                    val c1 = Offset(left + width * a.x1, bottom - height * ((a.y1 + 0.25f) / 1.5f))
                    val c2 = Offset(left + width * a.x2, bottom - height * ((a.y2 + 0.25f) / 1.5f))
                    drawLine(Color(0xFF42D982), start, c1, 2.dp.toPx(), cap = StrokeCap.Round)
                    drawLine(Color(0xFFFFB45B), end, c2, 2.dp.toPx(), cap = StrokeCap.Round)
                    drawCircle(Color(0xFF42D982).copy(alpha = 0.18f), 16.dp.toPx(), c1)
                    drawCircle(Color(0xFFFFB45B).copy(alpha = 0.18f), 16.dp.toPx(), c2)
                    drawCircle(Color(0xFF42D982), 8.dp.toPx(), c1)
                    drawCircle(Color(0xFFFFB45B), 8.dp.toPx(), c2)
                    drawCircle(Color.White, 3.dp.toPx(), c1)
                    drawCircle(Color.White, 3.dp.toPx(), c2)
                }
                drawCircle(KColors.Accent, 6.dp.toPx(), start)
                drawCircle(Color.White, 6.dp.toPx(), end)
                if (a.type == KeyType.LINEAR) drawLine(Color(0x557F8791), start, end, 1.dp.toPx())
            } else {
                val startY = if (mode == GraphMode.VALUE)
                    bottom - height * ((a.v - valueLow) / (valueHigh - valueLow)) else bottom
                val endY = if (mode == GraphMode.VALUE)
                    bottom - height * ((b.v - valueLow) / (valueHigh - valueLow)) else bottom
                drawCircle(KColors.Accent, 7.dp.toPx(), Offset(left, startY))
                drawCircle(Color.White, 7.dp.toPx(), Offset(right, endY))
                if (mode == GraphMode.VALUE) {
                    drawLine(KColors.Accent.copy(alpha = 0.5f), Offset(left, startY), Offset(left, bottom), 1.dp.toPx())
                    drawLine(Color.White.copy(alpha = 0.35f), Offset(right, endY), Offset(right, bottom), 1.dp.toPx())
                }
            }
        } else {
            drawLine(Color.White.copy(alpha = 0.55f), Offset(left, bottom), Offset(right, top), 2.dp.toPx())
        }

        drawLine(KColors.Muted.copy(alpha = 0.75f), Offset(left, bottom), Offset(right, bottom), 1.dp.toPx())
        drawLine(KColors.Muted.copy(alpha = 0.75f), Offset(left, top), Offset(left, bottom), 1.dp.toPx())
    }
}
