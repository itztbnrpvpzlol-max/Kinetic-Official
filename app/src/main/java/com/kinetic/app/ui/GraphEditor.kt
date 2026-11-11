package com.kinetic.app.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinetic.app.model.EditorState
import com.kinetic.app.model.KeyType
import com.kinetic.app.model.Keyframe
import com.kinetic.app.model.Layer
import com.kinetic.app.model.Prop
import com.kinetic.app.model.PropId
import com.kinetic.app.model.fmt
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private const val PAD_L = 48f
private const val PAD_R = 14f
private const val PAD_V = 18f

private enum class GraphMode { AUTO, VALUE, SPEED }

private data class GraphHandle(
    val segment: Int,
    val outgoing: Boolean,
    val time: Float,
    val value: Float
)

private class GMap(
    val w: Float,
    val h: Float,
    val dur: Float,
    val lo: Float,
    val hi: Float,
    val padL: Float,
    val padR: Float,
    val padV: Float
) {
    fun x(t: Float) = padL + t / dur * (w - padL - padR)
    fun y(v: Float) = padV + (1f - (v - lo) / (hi - lo)) * (h - 2f * padV)
    fun t(x: Float) = (x - padL) / (w - padL - padR) * dur
    fun v(y: Float) = lo + (1f - (y - padV) / (h - 2f * padV)) * (hi - lo)
}

private fun gmap(w: Float, h: Float, dur: Float, range: Pair<Float, Float>, density: Float) =
    GMap(w, h, max(dur, 0.1f), range.first, range.second, PAD_L * density, PAD_R * density, PAD_V * density)

private fun isSpatial(id: PropId): Boolean = when (id) {
    PropId.ANCHOR_X, PropId.ANCHOR_Y, PropId.POS_X, PropId.POS_Y -> true
    else -> false
}

private fun graphColor(id: PropId): Color = when (id) {
    PropId.POS_X, PropId.ANCHOR_X -> Color(0xFF8D78FF)
    PropId.POS_Y, PropId.ANCHOR_Y -> Color(0xFF36C9C1)
    PropId.SCALE_X -> Color(0xFFFFB454)
    PropId.SCALE_Y -> Color(0xFFFF7B91)
    PropId.ROTATION -> Color(0xFF66B7FF)
    PropId.OPACITY -> Color(0xFFB4D66B)
}

private fun speedAt(p: Prop, t: Float, duration: Float): Float {
    val step = min(0.005f, max(0.0005f, duration / 10000f))
    return abs(p.valueAt(t + step) - p.valueAt(t - step)) / (2f * step)
}

private fun keySpeed(keys: List<Keyframe>, index: Int): Float {
    if (index !in keys.indices) return 0f
    val key = keys[index]
    val incoming = if (index > 0) {
        abs(key.v - keys[index - 1].v) / max(key.t - keys[index - 1].t, 0.001f)
    } else 0f
    val outgoing = if (index < keys.lastIndex) {
        abs(keys[index + 1].v - key.v) / max(keys[index + 1].t - key.t, 0.001f)
    } else 0f
    return max(incoming, outgoing)
}

private fun computeRange(p: Prop, dur: Float, speed: Boolean): Pair<Float, Float> {
    var lo = Float.MAX_VALUE
    var hi = -Float.MAX_VALUE
    val samples = 240
    for (i in 0..samples) {
        val t = max(dur, 0.1f) * i / samples
        val v = if (speed) speedAt(p, t, dur) else p.valueAt(t)
        lo = min(lo, v)
        hi = max(hi, v)
    }
    if (!speed) {
        for (key in p.keys) {
            lo = min(lo, key.v)
            hi = max(hi, key.v)
        }
    } else {
        lo = 0f
        hi = max(hi, 1f)
    }
    if (hi - lo < 1e-3f) {
        val pad = max(abs(hi) * 0.15f, 10f)
        lo -= pad
        hi += pad
    } else {
        val pad = (hi - lo) * 0.14f
        lo -= pad
        hi += pad
    }
    if (speed) lo = 0f
    return lo to hi
}

private fun valueHandles(keys: List<Keyframe>, keyIndex: Int): List<GraphHandle> {
    if (keyIndex !in keys.indices) return emptyList()
    val result = ArrayList<GraphHandle>(2)
    if (keyIndex < keys.lastIndex) {
        val a = keys[keyIndex]
        val b = keys[keyIndex + 1]
        if (a.type == KeyType.BEZIER) {
            val dt = b.t - a.t
            val dv = b.v - a.v
            result += GraphHandle(keyIndex, true, a.t + a.x1 * dt, a.v + a.y1 * dv)
        }
    }
    if (keyIndex > 0) {
        val segment = keyIndex - 1
        val a = keys[segment]
        val b = keys[keyIndex]
        if (a.type == KeyType.BEZIER) {
            val dt = b.t - a.t
            val dv = b.v - a.v
            result += GraphHandle(segment, false, a.t + a.x2 * dt, a.v + a.y2 * dv)
        }
    }
    return result
}

private fun speedHandles(keys: List<Keyframe>, keyIndex: Int): List<GraphHandle> {
    if (keyIndex !in keys.indices) return emptyList()
    val result = ArrayList<GraphHandle>(2)
    if (keyIndex < keys.lastIndex) {
        val a = keys[keyIndex]
        val b = keys[keyIndex + 1]
        if (a.type == KeyType.BEZIER) {
            val dt = max(b.t - a.t, 0.001f)
            val base = abs(b.v - a.v) / dt
            val value = if (a.x1 > 1e-4f) base * abs(a.y1 / a.x1) else 0f
            result += GraphHandle(keyIndex, true, a.t + a.x1 * dt, value)
        }
    }
    if (keyIndex > 0) {
        val segment = keyIndex - 1
        val a = keys[segment]
        val b = keys[keyIndex]
        if (a.type == KeyType.BEZIER) {
            val dt = max(b.t - a.t, 0.001f)
            val base = abs(b.v - a.v) / dt
            val value = if (a.x2 < 0.9999f) base * abs((1f - a.y2) / (1f - a.x2)) else 0f
            result += GraphHandle(segment, false, a.t + a.x2 * dt, value)
        }
    }
    return result
}

private fun hitKey(keys: List<Keyframe>, map: GMap, x: Float, y: Float, radius: Float, speed: Boolean): Int {
    var best = -1
    var bestDistance = radius
    keys.forEachIndexed { index, key ->
        val value = if (speed) keySpeed(keys, index) else key.v
        val distance = hypot(map.x(key.t) - x, map.y(value) - y)
        if (distance <= bestDistance) {
            bestDistance = distance
            best = index
        }
    }
    return best
}

@Composable
fun GraphPanel(editor: EditorState, layer: Layer, modifier: Modifier = Modifier) {
    val animated = PropId.values().filter { layer.p(it).animated }
    if (animated.isEmpty()) {
        Box(modifier.fillMaxSize().padding(18.dp), contentAlignment = Alignment.Center) {
            Text(
                "No animated properties yet. Enable a stopwatch in Properties and add at least two keyframes.",
                color = KColors.Muted,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    val selected = editor.selectedProp?.takeIf { it in animated } ?: animated.first()
    var graphMode by remember(layer.id) { mutableStateOf(GraphMode.AUTO) }
    var visibleProps by remember(layer.id) { mutableStateOf(setOf(animated.first())) }
    var frozenRange by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    LaunchedEffect(selected) { visibleProps = visibleProps + selected }

    val prop = layer.p(selected)
    val keys = prop.keys
    val duration = max(editor.duration, 0.1f)
    val speedGraph = graphMode == GraphMode.SPEED || (graphMode == GraphMode.AUTO && isSpatial(selected))
    val range = frozenRange ?: computeRange(prop, duration, speedGraph)
    val shownProps = (visibleProps + selected).filter { it in animated }
    val latestLayer by rememberUpdatedState(layer)
    val latestSelected by rememberUpdatedState(selected)
    val latestSpeed by rememberUpdatedState(speedGraph)
    val latestRange by rememberUpdatedState(range)
    val labelPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9B9AA9.toInt() } }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp).height(38.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            animated.forEach { id ->
                val isSelected = id == selected
                Chip((if (visibleProps.contains(id)) "● " else "○ ") + id.label, isSelected) {
                    editor.selectProp(id)
                    visibleProps = if (visibleProps.contains(id)) visibleProps - id else visibleProps + id
                }
            }
            Chip("All curves", visibleProps.containsAll(animated.toSet())) {
                visibleProps = if (visibleProps.containsAll(animated.toSet())) setOf(selected) else animated.toSet()
            }
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp).height(36.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Chip("Auto", graphMode == GraphMode.AUTO) { graphMode = GraphMode.AUTO; frozenRange = null }
            Chip("Value Graph", graphMode == GraphMode.VALUE) { graphMode = GraphMode.VALUE; frozenRange = null }
            Chip("Speed Graph", graphMode == GraphMode.SPEED) { graphMode = GraphMode.SPEED; frozenRange = null }
            Box(Modifier.weight(1f))
            Text(if (speedGraph) "Velocity / second" else "Property value", color = KColors.Dim, fontSize = 10.sp)
        }

        Canvas(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(selected, speedGraph) {
                    detectTapGestures { point ->
                        val map = gmap(size.width.toFloat(), size.height.toFloat(), duration, latestRange, density)
                        val current = latestLayer.p(latestSelected)
                        val index = hitKey(current.keys, map, point.x, point.y, 25.dp.toPx(), latestSpeed)
                        if (index >= 0) editor.selectKey(latestSelected, index, current.keys[index].t)
                        else editor.seek(map.t(point.x).coerceIn(0f, duration))
                    }
                }
                .pointerInput(selected, speedGraph) {
                    var mode = 0 // 0 scrub, 1 value key, 2 value out, 3 value in, 4 speed out, 5 speed in
                    var segment = -1
                    val hitRadius = 25.dp.toPx()
                    detectDragGestures(
                        onDragStart = { point ->
                            val current = latestLayer.p(latestSelected)
                            val currentKeys = current.keys
                            frozenRange = latestRange
                            val map = gmap(size.width.toFloat(), size.height.toFloat(), duration, latestRange, density)
                            mode = 0
                            segment = -1
                            val keyIndex = editor.selectedKey
                            val handles = if (latestSpeed) speedHandles(currentKeys, keyIndex) else valueHandles(currentKeys, keyIndex)
                            val handle = handles.minByOrNull {
                                hypot(map.x(it.time) - point.x, map.y(it.value) - point.y)
                            }
                            if (handle != null && hypot(map.x(handle.time) - point.x, map.y(handle.value) - point.y) <= hitRadius) {
                                segment = handle.segment
                                mode = if (latestSpeed) {
                                    if (handle.outgoing) 4 else 5
                                } else {
                                    if (handle.outgoing) 2 else 3
                                }
                            } else if (!latestSpeed) {
                                val index = hitKey(currentKeys, map, point.x, point.y, hitRadius, false)
                                if (index >= 0) {
                                    mode = 1
                                    segment = index
                                    editor.selectKey(latestSelected, index, null)
                                }
                            } else {
                                val index = hitKey(currentKeys, map, point.x, point.y, hitRadius, true)
                                if (index >= 0) editor.selectKey(latestSelected, index, null)
                            }
                            if (mode != 0) editor.beginEdit() else editor.seek(map.t(point.x).coerceIn(0f, duration))
                        },
                        onDragEnd = { frozenRange = null; mode = 0 },
                        onDragCancel = { frozenRange = null; mode = 0 }
                    ) { change, _ ->
                        change.consume()
                        val currentLayer = latestLayer
                        val id = latestSelected
                        val currentKeys = currentLayer.p(id).keys
                        val map = gmap(size.width.toFloat(), size.height.toFloat(), duration, latestRange, density)
                        val x = change.position.x
                        val y = change.position.y
                        when (mode) {
                            1 -> if (segment in currentKeys.indices) {
                                var newValue = map.v(y)
                                if (id == PropId.OPACITY) newValue = newValue.coerceIn(0f, 100f)
                                editor.replaceKey(
                                    currentLayer.id, id, segment,
                                    currentKeys[segment].copy(v = newValue), push = false
                                )
                            }
                            2, 3 -> if (segment in currentKeys.indices && segment < currentKeys.lastIndex) {
                                val a = currentKeys[segment]
                                val b = currentKeys[segment + 1]
                                val dt = max(b.t - a.t, 0.01f)
                                val dv = b.v - a.v
                                val influence = ((map.t(x) - a.t) / dt).coerceIn(0.02f, 0.98f)
                                val value = if (abs(dv) > 1e-4f) ((map.v(y) - a.v) / dv).coerceIn(-2f, 3f) else 0f
                                val changed = if (mode == 2) {
                                    a.copy(type = KeyType.BEZIER, x1 = influence, y1 = value)
                                } else {
                                    a.copy(type = KeyType.BEZIER, x2 = influence, y2 = value)
                                }
                                editor.replaceKey(currentLayer.id, id, segment, changed, push = false)
                            }
                            4, 5 -> if (segment in currentKeys.indices && segment < currentKeys.lastIndex) {
                                val a = currentKeys[segment]
                                val b = currentKeys[segment + 1]
                                val dt = max(b.t - a.t, 0.01f)
                                val baseSpeed = abs(b.v - a.v) / dt
                                val influence = ((map.t(x) - a.t) / dt).coerceIn(0.02f, 0.98f)
                                val targetSpeed = map.v(y).coerceAtLeast(0f)
                                val changed = if (mode == 4) {
                                    val controlY = if (baseSpeed > 1e-4f) targetSpeed / baseSpeed * influence else 0f
                                    a.copy(type = KeyType.BEZIER, x1 = influence, y1 = controlY.coerceIn(-2f, 3f))
                                } else {
                                    val controlY = if (baseSpeed > 1e-4f) 1f - targetSpeed / baseSpeed * (1f - influence) else 1f
                                    a.copy(type = KeyType.BEZIER, x2 = influence, y2 = controlY.coerceIn(-2f, 3f))
                                }
                                editor.replaceKey(currentLayer.id, id, segment, changed, push = false)
                            }
                            else -> editor.seek(map.t(x).coerceIn(0f, duration))
                        }
                    }
                }
        ) {
            val activeMap = gmap(size.width, size.height, duration, range, density)
            labelPaint.textSize = 10.sp.toPx()
            val grid = Color(0xFF292936)
            for (i in 0..4) {
                val value = range.first + (range.second - range.first) * i / 4f
                val y = activeMap.y(value)
                drawLine(grid, Offset(activeMap.padL, y), Offset(size.width - activeMap.padR, y), 1f)
                drawIntoCanvas {
                    it.nativeCanvas.drawText(
                        fmt("%.1f", value),
                        2.dp.toPx(),
                        y + 3.dp.toPx(),
                        labelPaint
                    )
                }
            }
            val step = if (duration <= 10f) 1 else if (duration <= 30f) 5 else 10
            var second = 0
            while (second <= duration.toInt()) {
                val x = activeMap.x(second.toFloat())
                drawLine(grid, Offset(x, activeMap.padV), Offset(x, size.height - activeMap.padV), 1f)
                second += step
            }

            shownProps.forEach { id ->
                val current = layer.p(id)
                val currentSpeed = speedGraph
                val ownRange = if (id == selected) range else computeRange(current, duration, currentSpeed)
                val map = gmap(size.width, size.height, duration, ownRange, density)
                val curve = Path()
                val samples = 260
                for (i in 0..samples) {
                    val t = duration * i / samples
                    val value = if (currentSpeed) speedAt(current, t, duration) else current.valueAt(t)
                    if (i == 0) curve.moveTo(map.x(t), map.y(value)) else curve.lineTo(map.x(t), map.y(value))
                }
                drawPath(
                    curve,
                    graphColor(id),
                    style = Stroke(
                        if (id == selected) 2.6.dp.toPx() else 1.6.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }

            if (speedGraph) {
                keys.forEachIndexed { index, key ->
                    val point = Offset(activeMap.x(key.t), activeMap.y(keySpeed(keys, index)))
                    if (index == editor.selectedKey) {
                        drawCircle(Color.White, 8.dp.toPx(), point)
                        drawCircle(graphColor(selected), 5.dp.toPx(), point)
                    } else {
                        drawCircle(graphColor(selected), 5.dp.toPx(), point)
                    }
                }
                speedHandles(keys, editor.selectedKey).forEach { handle ->
                    val key = keys[editor.selectedKey]
                    val anchor = if (handle.outgoing) {
                        Offset(activeMap.x(key.t), activeMap.y(keySpeed(keys, editor.selectedKey)))
                    } else {
                        Offset(activeMap.x(key.t), activeMap.y(keySpeed(keys, editor.selectedKey)))
                    }
                    val control = Offset(activeMap.x(handle.time), activeMap.y(handle.value))
                    drawLine(KColors.Muted, anchor, control, 1.5.dp.toPx())
                    drawCircle(Color.White, 6.dp.toPx(), control)
                    drawCircle(graphColor(selected), 3.5.dp.toPx(), control)
                }
            } else {
                valueHandles(keys, editor.selectedKey).forEach { handle ->
                    val key = keys[editor.selectedKey]
                    val anchor = Offset(activeMap.x(key.t), activeMap.y(key.v))
                    val control = Offset(activeMap.x(handle.time), activeMap.y(handle.value))
                    drawLine(KColors.Muted, anchor, control, 1.5.dp.toPx())
                    drawCircle(Color.White, 6.dp.toPx(), control)
                    drawCircle(graphColor(selected), 3.5.dp.toPx(), control)
                }
                keys.forEachIndexed { index, key ->
                    val point = Offset(activeMap.x(key.t), activeMap.y(key.v))
                    if (index == editor.selectedKey) {
                        drawCircle(Color.White, 8.dp.toPx(), point)
                        drawCircle(graphColor(selected), 5.dp.toPx(), point)
                    } else {
                        drawCircle(graphColor(selected), 5.dp.toPx(), point)
                    }
                }
            }

            val playheadX = activeMap.x(editor.time)
            drawLine(Color(0xDDE8E8EF), Offset(playheadX, 0f), Offset(playheadX, size.height), 1.5.dp.toPx())
        }

        val selectedKey = editor.selectedKey
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp).height(44.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectedKey in keys.indices) {
                val key = keys[selectedKey]
                Text(
                    fmt("%.2fs", key.t) + "  " + selected.format(key.v),
                    color = KColors.Muted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(end = 4.dp)
                )
                fun setSegment(segment: Int, transform: (Keyframe) -> Keyframe) {
                    if (segment in keys.indices && segment < keys.lastIndex) {
                        editor.replaceKey(layer.id, selected, segment, transform(keys[segment]), push = true)
                    }
                }
                val outgoing = if (selectedKey < keys.lastIndex) selectedKey else selectedKey - 1
                val incoming = selectedKey - 1
                Chip("Linear", outgoing in keys.indices && keys[outgoing].type == KeyType.LINEAR) {
                    setSegment(outgoing) { it.copy(type = KeyType.LINEAR) }
                }
                Chip("Hold", outgoing in keys.indices && keys[outgoing].type == KeyType.HOLD) {
                    setSegment(outgoing) { it.copy(type = KeyType.HOLD) }
                }
                Chip("Ease In", incoming in keys.indices && keys[incoming].type == KeyType.BEZIER) {
                    setSegment(incoming) { it.copy(type = KeyType.BEZIER, x2 = 0.58f, y2 = 1f) }
                }
                Chip("Ease Out", selectedKey in keys.indices && keys[selectedKey].type == KeyType.BEZIER) {
                    setSegment(selectedKey) { it.copy(type = KeyType.BEZIER, x1 = 0.42f, y1 = 0f) }
                }
                Chip("Easy Ease", false) {
                    editor.beginEdit()
                    if (incoming in keys.indices && incoming < keys.lastIndex) {
                        editor.replaceKey(layer.id, selected, incoming, keys[incoming].copy(type = KeyType.BEZIER, x2 = 0.667f, y2 = 1f), push = false)
                    }
                    if (selectedKey in keys.indices && selectedKey < keys.lastIndex) {
                        val latest = layer.p(selected).keys[selectedKey]
                        editor.replaceKey(layer.id, selected, selectedKey, latest.copy(type = KeyType.BEZIER, x1 = 0.333f, y1 = 0f), push = false)
                    }
                }
                Chip("Elastic", outgoing in keys.indices && keys[outgoing].type == KeyType.ELASTIC) {
                    setSegment(outgoing) { it.copy(type = KeyType.ELASTIC) }
                }
                Chip("Bounce", outgoing in keys.indices && keys[outgoing].type == KeyType.BOUNCE) {
                    setSegment(outgoing) { it.copy(type = KeyType.BOUNCE) }
                }
                Chip("Wiggle", prop.expression?.startsWith("wiggle", true) == true) {
                    editor.setExpression(layer.id, selected, if (prop.expression == null) "wiggle(2, 20)" else null)
                }
                Chip("Delete", false) { editor.deleteKey(layer.id, selected, selectedKey) }
            } else {
                Text(
                    if (speedGraph) "Select a keyframe, then drag its direction handles to change velocity and influence."
                    else "Drag keyframes vertically to change values. Drag Bézier handles to reshape the curve.",
                    color = KColors.Dim,
                    fontSize = 11.sp
                )
            }
        }
    }
}
