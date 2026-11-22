package com.kinetic.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinetic.app.model.EditorState
import com.kinetic.app.model.KeyType
import com.kinetic.app.model.Keyframe
import com.kinetic.app.model.Layer
import com.kinetic.app.model.Prop
import com.kinetic.app.model.PropId
import com.kinetic.app.model.defaultCurveParams
import com.kinetic.app.model.keyProgress
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private enum class GraphMode { EASE, VALUE, SPEED }

private data class GraphPreset(
    val label: String, val type: KeyType,
    val x1: Float = 0.42f, val y1: Float = 0f, val x2: Float = 0.58f, val y2: Float = 1f,
    val p1: Float = 0f, val p2: Float = 0f
) {
    fun asKey() = Keyframe(0f, 0f, type, x1, y1, x2, y2, p1, p2)
}

private val GreenHandle = Color(0xFF42D982)
private val OrangeHandle = Color(0xFFFFB45B)

private val curveTypes = listOf(
    KeyType.BEZIER to "Bezier", KeyType.ELASTIC to "Elastic", KeyType.BOUNCE to "Bounce",
    KeyType.WAVE to "Wave", KeyType.STEPS to "Steps"
)

private val presetsByType: Map<KeyType, List<GraphPreset>> = mapOf(
    KeyType.BEZIER to listOf(
        GraphPreset("Linear", KeyType.LINEAR),
        GraphPreset("Ease In", KeyType.BEZIER, 0.42f, 0f, 1f, 1f),
        GraphPreset("Ease Out", KeyType.BEZIER, 0f, 0f, 0.58f, 1f),
        GraphPreset("Easy Ease", KeyType.BEZIER, 0.42f, 0f, 0.58f, 1f),
        GraphPreset("Strong In", KeyType.BEZIER, 0.64f, 0f, 0.78f, 0f),
        GraphPreset("Strong Out", KeyType.BEZIER, 0.22f, 1f, 0.36f, 1f),
        GraphPreset("Back Out", KeyType.BEZIER, 0.34f, 1.56f, 0.64f, 1f),
        GraphPreset("Anticipate", KeyType.BEZIER, 0.36f, 0f, 0.66f, -0.56f),
        GraphPreset("Hold", KeyType.HOLD)
    ),
    KeyType.ELASTIC to listOf(
        GraphPreset("Soft", KeyType.ELASTIC, p1 = 0.1f, p2 = 2f),
        GraphPreset("Classic", KeyType.ELASTIC, p1 = 0.23f, p2 = 3f),
        GraphPreset("Springy", KeyType.ELASTIC, p1 = 0.4f, p2 = 4f),
        GraphPreset("Wild", KeyType.ELASTIC, p1 = 0.7f, p2 = 6f)
    ),
    KeyType.BOUNCE to listOf(
        GraphPreset("Light", KeyType.BOUNCE, p1 = 2f, p2 = 0.25f),
        GraphPreset("Ball", KeyType.BOUNCE, p1 = 3f, p2 = 0.35f),
        GraphPreset("Rubber", KeyType.BOUNCE, p1 = 5f, p2 = 0.5f),
        GraphPreset("Endless", KeyType.BOUNCE, p1 = 8f, p2 = 0.65f)
    ),
    KeyType.WAVE to listOf(
        GraphPreset("Ripple", KeyType.WAVE, p1 = 1f, p2 = 0.12f),
        GraphPreset("Sway", KeyType.WAVE, p1 = 2f, p2 = 0.18f),
        GraphPreset("Shake", KeyType.WAVE, p1 = 4f, p2 = 0.1f),
        GraphPreset("Swell", KeyType.WAVE, p1 = 1.5f, p2 = 0.3f)
    ),
    KeyType.STEPS to listOf(
        GraphPreset("3 Steps", KeyType.STEPS, p1 = 3f),
        GraphPreset("5 Steps", KeyType.STEPS, p1 = 5f),
        GraphPreset("8 Steps", KeyType.STEPS, p1 = 8f),
        GraphPreset("Stair Up", KeyType.STEPS, p1 = 4f, p2 = 1f)
    )
)

/** Presets the user saved with the + button (kept while the app is open). */
private val userPresets = mutableStateListOf<GraphPreset>()

private fun Keyframe.family(): KeyType = when (type) {
    KeyType.LINEAR, KeyType.HOLD -> KeyType.BEZIER
    else -> type
}

private fun graphSegment(keys: List<Keyframe>, selected: Int): Int =
    if (keys.size < 2) -1 else when {
        selected in 0 until keys.lastIndex -> selected
        selected > 0 && selected in keys.indices -> selected - 1
        else -> 0
    }

// ---------------------------------------------------------------------------------------------
// Panels
// ---------------------------------------------------------------------------------------------

@Composable
fun GraphPanel(editor: EditorState, layer: Layer, modifier: Modifier = Modifier) {
    val currentLayer = editor.layers.firstOrNull { it.id == layer.id } ?: layer
    val graphItems = remember(currentLayer.props, currentLayer.scaleLinked, editor.selectedProp) {
        val keyed = currentLayer.props.filter { it.value.keys.isNotEmpty() }.keys
        buildList {
            listOf(PropId.POS_X, PropId.POS_Y, PropId.POS_Z).forEach { id -> if (id in keyed) add(id.label to id) }
            if (currentLayer.scaleLinked) {
                if (PropId.SCALE_X in keyed || PropId.SCALE_Y in keyed) add("Scale" to (if (PropId.SCALE_X in keyed) PropId.SCALE_X else PropId.SCALE_Y))
            } else {
                if (PropId.SCALE_X in keyed) add("Scale X" to PropId.SCALE_X)
                if (PropId.SCALE_Y in keyed) add("Scale Y" to PropId.SCALE_Y)
            }
            val skip = setOf(PropId.POS_X, PropId.POS_Y, PropId.POS_Z, PropId.SCALE_X, PropId.SCALE_Y)
            keyed.filter { it !in skip }.sortedBy { it.ordinal }.forEach { add(it.label to it) }
            val sel = editor.selectedProp
            if (sel != null && none { it.second == sel }) add(sel.label to sel)
        }
    }
    val selected = editor.selectedProp?.let { selectedProp ->
        graphItems.firstOrNull { item ->
            item.second == selectedProp || (item.first == "Scale" && selectedProp in setOf(PropId.SCALE_X, PropId.SCALE_Y))
        }?.second
    } ?: graphItems.firstOrNull()?.second ?: PropId.OPACITY
    var mode by remember(layer.id) { mutableStateOf(GraphMode.EASE) }
    val keys = currentLayer.p(selected).keys
    val segment = graphSegment(keys, editor.selectedKey)

    Column(modifier.fillMaxSize().background(KColors.Surface)) {
        GraphHeader(mode, { mode = it }) {
            if (graphItems.size > 1) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    graphItems.forEach { (label, id) ->
                        val active = id == selected
                        Box(Modifier.clip(RoundedCornerShape(6.dp))
                            .background(if (active) KColors.Accent.copy(alpha = 0.16f) else KColors.Bg)
                            .border(1.dp, if (active) KColors.Accent.copy(alpha = 0.6f) else KColors.Line, RoundedCornerShape(6.dp))
                            .clickable { editor.selectProp(id) }.padding(horizontal = 8.dp, vertical = 5.dp)) {
                            Text(label, color = if (active) KColors.Accent else KColors.Muted, fontSize = 9.sp)
                        }
                    }
                }
            } else Spacer(Modifier.weight(1f))
        }
        if (keys.size < 2) {
            NoCurveHint(if (keys.isEmpty()) "No keyframes on ${selected.label}" else "Add a second keyframe to shape a transition")
        } else {
            CurveEditor(
                keys = keys, segment = segment, mode = mode, modifier = Modifier.weight(1f).fillMaxWidth(),
                onBeginEdit = { editor.beginEdit() },
                onKeyChange = { index, key -> editor.replaceKey(layer.id, selected, index, key, push = false) }
            )
        }
    }
}

@Composable
fun EffectGraphPanel(editor: EditorState, layer: Layer, modifier: Modifier = Modifier) {
    val effect = layer.effectStack.firstOrNull { it.id == editor.selectedEffectId }
    val spec = effect?.let { selected -> controlsFor(selected).firstOrNull { it.key == editor.selectedEffectParameter } }
    if (effect == null || spec == null) {
        Box(modifier.fillMaxSize().background(KColors.Surface), contentAlignment = Alignment.Center) {
            Text("Select an effect property to edit its animation curve.", color = KColors.Muted, fontSize = 10.sp)
        }
        return
    }
    val liveEffect = editor.layers.firstOrNull { it.id == layer.id }?.effectStack?.firstOrNull { it.id == effect.id } ?: effect
    val keys = liveEffect.parameterKeys[spec.key].orEmpty()
    var mode by remember(effect.id, spec.key) { mutableStateOf(GraphMode.EASE) }
    val graphSelection = editor.selectedEffectKey.takeIf { it in keys.indices } ?: 0
    val segment = graphSegment(keys, graphSelection)

    Column(modifier.fillMaxSize().background(KColors.Surface)) {
        GraphHeader(mode, { mode = it }) {
            Text(effect.name + " · " + spec.label, color = KColors.Text, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f), maxLines = 1)
        }
        if (keys.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().height(30.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                keys.forEachIndexed { index, key ->
                    val sel = index == graphSelection
                    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(if (sel) KColors.Accent.copy(alpha = 0.16f) else KColors.Bg)
                        .border(1.dp, if (sel) KColors.Accent else KColors.Line, RoundedCornerShape(6.dp))
                        .clickable { editor.selectEffectKey(index) }.padding(horizontal = 8.dp, vertical = 5.dp)) {
                        Text("K" + (index + 1) + " · " + String.format(java.util.Locale.US, "%.2fs", key.t),
                            color = if (sel) KColors.Accent else KColors.Muted, fontSize = 8.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        if (keys.size < 2) {
            NoCurveHint(if (keys.isEmpty()) "No keyframes on " + spec.label else "Add a second keyframe to shape a transition")
        } else {
            CurveEditor(
                keys = keys, segment = segment, mode = mode, modifier = Modifier.weight(1f).fillMaxWidth(),
                onBeginEdit = { editor.beginEdit() },
                onKeyChange = { index, key -> editor.replaceEffectParameterKey(layer.id, effect.id, spec.key, index, key) }
            )
        }
    }
}

@Composable
private fun GraphHeader(mode: GraphMode, onMode: (GraphMode) -> Unit, left: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        left()
        Spacer(Modifier.width(6.dp))
        listOf(GraphMode.EASE to "Ease", GraphMode.VALUE to "Value", GraphMode.SPEED to "Speed").forEachIndexed { i, (m, label) ->
            if (i > 0) Spacer(Modifier.width(4.dp))
            Box(Modifier.clip(RoundedCornerShape(7.dp))
                .background(if (mode == m) KColors.Accent.copy(alpha = 0.17f) else KColors.Bg)
                .border(1.dp, if (mode == m) KColors.Accent.copy(alpha = 0.65f) else KColors.Line, RoundedCornerShape(7.dp))
                .clickable { onMode(m) }.padding(horizontal = 10.dp, vertical = 6.dp)) {
                Text(label, color = if (mode == m) KColors.Accent else KColors.Muted, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun NoCurveHint(text: String) {
    Box(Modifier.fillMaxSize().padding(18.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(text, color = KColors.Text, fontSize = 13.sp, textAlign = TextAlign.Center)
            Text("Add another keyframe from the timeline or the diamond control.", color = KColors.Muted, fontSize = 10.sp, textAlign = TextAlign.Center)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Curve editor: canvas + type picker + presets. Edits are applied locally while dragging and
// pushed to the project at ~30 Hz so the rest of the UI is not recomposed on every touch event.
// ---------------------------------------------------------------------------------------------

@Composable
private fun CurveEditor(
    keys: List<Keyframe>, segment: Int, mode: GraphMode, modifier: Modifier,
    onBeginEdit: () -> Unit, onKeyChange: (Int, Keyframe) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var dragKeys by remember { mutableStateOf<List<Keyframe>?>(null) }
    var activeHandle by remember { mutableStateOf(0) }
    var dragRange by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var lastCommit by remember { mutableLongStateOf(0L) }
    var dirtyIndex by remember { mutableStateOf(0) }
    val latestKeys by rememberUpdatedState(keys)
    val latestOnKeyChange by rememberUpdatedState(onKeyChange)
    val latestOnBegin by rememberUpdatedState(onBeginEdit)
    val shown = dragKeys ?: keys
    val seg = segment.coerceIn(0, shown.lastIndex - 1)
    val a = shown[seg]
    var typeMenu by remember { mutableStateOf(false) }

    fun applyLive(index: Int, key: Keyframe, force: Boolean = false) {
        dirtyIndex = index
        dragKeys = (dragKeys ?: latestKeys).toMutableList().also { it[index] = key }
        val now = System.nanoTime()
        if (force || now - lastCommit > 30_000_000L) { lastCommit = now; latestOnKeyChange(index, key) }
    }

    // Parameter scrubbing has no "end" callback: once edits stop, commit the final value and drop the local copy.
    LaunchedEffect(dragKeys, activeHandle) {
        val local = dragKeys
        if (local != null && activeHandle == 0) {
            delay(150)
            if (dirtyIndex in local.indices) latestOnKeyChange(dirtyIndex, local[dirtyIndex])
            dragKeys = null
        }
    }
    fun applyDiscrete(key: Keyframe) {
        onBeginEdit()
        dragKeys = null
        onKeyChange(seg, key)
    }

    val latestShown by rememberUpdatedState(shown)
    val latestSeg by rememberUpdatedState(seg)
    val latestMode by rememberUpdatedState(mode)

    Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(KColors.Bg)) {
            // ---- canvas
            Canvas(Modifier.fillMaxSize().clipToBounds().pointerInput(Unit) {
                val pad = 16.dp.toPx()
                val rail = 14.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val ks = latestShown
                    val s = latestSeg
                    if (s !in 0 until ks.lastIndex) return@awaitEachGesture
                    val key = ks[s]
                    val range = if (latestMode == GraphMode.EASE)
                        GraphGeometry(size.width.toFloat(), size.height.toFloat(), pad, rail, ks, s, latestMode, null).let { it.easeLo to it.easeHi }
                    else null
                    val g = GraphGeometry(size.width.toFloat(), size.height.toFloat(), pad, rail, ks, s, latestMode, range)
                    var handle = 0
                    var valueIndex = -1
                    val hit = 46.dp.toPx()
                    if (latestMode == GraphMode.EASE) {
                        if (key.family() == KeyType.BEZIER) {
                            val h = easeHandles(key)
                            val p1 = g.pt(h[0], h[1]); val p2 = g.pt(h[2], h[3])
                            val d1 = (down.position - p1).getDistance(); val d2 = (down.position - p2).getDistance()
                            if (min(d1, d2) <= hit) handle = if (d1 <= d2) 1 else 2
                        } else {
                            val ph = paramHandles(key, g)
                            var best = hit
                            for (i in 0..1) ph[i]?.let { p ->
                                val d = (down.position - p).getDistance()
                                if (d <= best) { best = d; handle = 3 + i }
                            }
                        }
                    } else if (latestMode == GraphMode.VALUE) {
                        val p0 = g.valuePt(0f, ks[s].v); val p1 = g.valuePt(1f, ks[s + 1].v)
                        val d0 = (down.position - p0).getDistance(); val d1 = (down.position - p1).getDistance()
                        if (min(d0, d1) <= hit) valueIndex = if (d0 <= d1) s else s + 1
                    }
                    if (handle == 0 && valueIndex < 0) return@awaitEachGesture
                    down.consume()
                    latestOnBegin()
                    activeHandle = handle
                    dragRange = range
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    var base = key
                    if ((handle == 1 || handle == 2) && key.type != KeyType.BEZIER) {
                        val h = easeHandles(key)
                        base = key.copy(type = KeyType.BEZIER, x1 = h[0], y1 = h[1], x2 = h[2], y2 = h[3])
                    }
                    fun move(pos: Offset) {
                        when (handle) {
                            1, 2 -> {
                                val nx = ((pos.x - g.left) / g.w).coerceIn(0f, 1f)
                                val ny = g.unY(pos.y).coerceIn(g.easeLo + 0.02f, g.easeHi - 0.02f)
                                val sy = snap(ny, floatArrayOf(0f, 1f), 0.035f)
                                val sx = snap(nx, floatArrayOf(0f, 1f / 3f, 0.5f, 2f / 3f, 1f), 0.02f)
                                val updated = if (handle == 1) base.copy(x1 = sx, y1 = sy) else base.copy(x2 = sx, y2 = sy)
                                applyLive(s, updated)
                                base = updated
                            }
                            3, 4 -> {
                                val updated = dragParam(base, handle, pos, g)
                                applyLive(s, updated)
                                base = updated
                            }
                            else -> applyLive(valueIndex, latestShown[valueIndex].copy(v = g.unValue(pos.y)))
                        }
                    }
                    move(down.position)
                    while (true) {
                        val event = awaitPointerEvent()
                        val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) { ch.consume(); break }
                        ch.consume()
                        move(ch.position)
                    }
                    val finalKeys = dragKeys
                    if (finalKeys != null) {
                        val idx = if (handle != 0) s else valueIndex
                        latestOnKeyChange(idx, finalKeys[idx])
                    }
                    dragKeys = null
                    activeHandle = 0
                    dragRange = null
                }
            }) {
                drawCurveScene(shown, seg, mode, activeHandle, dragRange)
            }

            // ---- overlays
            if (mode == GraphMode.EASE) {
                val family = a.family()
                Box(Modifier.align(Alignment.TopStart).padding(8.dp)) {
                    Row(Modifier.clip(RoundedCornerShape(9.dp)).background(KColors.Surface.copy(alpha = 0.92f))
                        .border(1.dp, KColors.Line, RoundedCornerShape(9.dp)).clickable { typeMenu = true }
                        .padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        CurveGlyph(a, true, Modifier.size(width = 18.dp, height = 12.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(curveTypes.first { it.first == family }.second, color = KColors.Text, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(4.dp))
                        KIcon(IconKind.CHEVRON_DOWN, KColors.Muted, Modifier.size(10.dp))
                    }
                    DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }, containerColor = KColors.Surface) {
                        curveTypes.forEach { (type, label) ->
                            DropdownMenuItem(
                                leadingIcon = { CurveGlyph(typeSample(type), type == family, Modifier.size(width = 22.dp, height = 14.dp)) },
                                text = { Text(label, color = if (type == family) KColors.Accent else KColors.Text, fontSize = 13.sp,
                                    fontWeight = if (type == family) FontWeight.SemiBold else FontWeight.Normal) },
                                onClick = {
                                    typeMenu = false
                                    if (type != family) applyDiscrete(switchType(a, type))
                                }
                            )
                        }
                    }
                }
                Text(formulaText(a), color = KColors.Muted, fontSize = 8.sp, maxLines = 1,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 10.dp))
                if (family == KeyType.STEPS) {
                    Row(Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 8.dp).clip(RoundedCornerShape(9.dp))
                        .background(KColors.Surface.copy(alpha = 0.94f)).border(1.dp, KColors.Line, RoundedCornerShape(9.dp))) {
                        listOf("End" to 0f, "Start" to 1f).forEach { (label, jump) ->
                            val on = (a.p2 >= 0.5f) == (jump >= 0.5f)
                            Box(Modifier.height(26.dp).clip(RoundedCornerShape(9.dp))
                                .background(if (on) KColors.Accent.copy(alpha = 0.2f) else Color.Transparent)
                                .clickable { applyDiscrete(seeded(a).copy(p2 = jump)) }.padding(horizontal = 9.dp), contentAlignment = Alignment.Center) {
                                Text(label, color = if (on) KColors.Accent else KColors.Muted, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            } else {
                Text(if (mode == GraphMode.VALUE) "Drag the end points up or down to change their values"
                    else "Speed follows the value curve of this segment",
                    color = KColors.Muted, fontSize = 8.sp, maxLines = 1, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp))
            }
        }

        if (mode == GraphMode.EASE) {
            PresetColumn(a, Modifier.width(78.dp).fillMaxHeight()) { applyDiscrete(it) }
        }
    }
}

private fun switchType(from: Keyframe, type: KeyType): Keyframe = when (type) {
    KeyType.BEZIER -> if (from.family() == KeyType.BEZIER) from else from.copy(type = KeyType.BEZIER, x1 = 0.42f, y1 = 0f, x2 = 0.58f, y2 = 1f)
    else -> { val (p1, p2) = defaultCurveParams(type); from.copy(type = type, p1 = p1, p2 = p2) }
}

private fun typeSample(type: KeyType): Keyframe = when (type) {
    KeyType.BEZIER -> Keyframe(0f, 0f, KeyType.BEZIER, 0.42f, 0f, 0.58f, 1f)
    else -> { val (p1, p2) = defaultCurveParams(type); Keyframe(0f, 0f, type, p1 = p1, p2 = p2) }
}

private fun snap(v: Float, targets: FloatArray, tol: Float): Float {
    for (t in targets) if (abs(v - t) <= tol) return t
    return v
}

/** Bezier control points of a segment key; straight segments expose the handles of a straight line. */
private fun easeHandles(k: Keyframe): FloatArray =
    if (k.type == KeyType.BEZIER) floatArrayOf(k.x1, k.y1, k.x2, k.y2)
    else floatArrayOf(1f / 3f, 1f / 3f, 2f / 3f, 2f / 3f)

private fun fmt2(v: Float) = String.format(java.util.Locale.US, "%.2f", v)

private fun formulaText(k: Keyframe): String = when (k.type) {
    KeyType.BEZIER -> "cubic-bezier(${fmt2(k.x1)}, ${fmt2(k.y1)}, ${fmt2(k.x2)}, ${fmt2(k.y2)})"
    KeyType.LINEAR -> "linear"
    KeyType.HOLD -> "hold"
    KeyType.ELASTIC -> if (k.p1 > 0f) "elastic(${fmt2(k.p1)}, ${fmt2(if (k.p2 > 0f) k.p2 else 3f)}, out)" else "elastic"
    KeyType.BOUNCE -> if (k.p1 > 0f) "bounce(${k.p1.toInt()}, ${fmt2(k.p2)})" else "bounce"
    KeyType.WAVE -> "wave(${fmt2(if (k.p1 > 0f) k.p1 else 2f)}, ${fmt2(k.p2)})"
    KeyType.STEPS -> "steps(${(if (k.p1 > 0f) k.p1 else 5f).toInt()}, ${if (k.p2 >= 0.5f) "start" else "end"})"
}

// ---------------------------------------------------------------------------------------------
// Parameter chips (Elastic / Bounce / Wave / Steps)
// ---------------------------------------------------------------------------------------------

// ---------------------------------------------------------------------------------------------
// Presets
// ---------------------------------------------------------------------------------------------

@Composable
private fun PresetColumn(a: Keyframe, modifier: Modifier, onPick: (Keyframe) -> Unit) {
    val family = a.family()
    val list = (presetsByType[family] ?: emptyList()) + userPresets.filter { p ->
        (if (p.type == KeyType.LINEAR || p.type == KeyType.HOLD) KeyType.BEZIER else p.type) == family
    }
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(KColors.Bg).border(1.dp, KColors.Line, RoundedCornerShape(12.dp))
        .verticalScroll(rememberScrollState()).padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("PRESETS", color = KColors.Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Box(Modifier.size(22.dp).clip(CircleShape).background(KColors.Surface).clickable {
                userPresets.add(GraphPreset("Custom ${userPresets.size + 1}", a.type, a.x1, a.y1, a.x2, a.y2, a.p1, a.p2))
            }, contentAlignment = Alignment.Center) { KIcon(IconKind.PLUS, KColors.Text, Modifier.size(12.dp)) }
        }
        list.forEach { preset ->
            val selected = presetMatches(a, preset)
            val isUser = preset in userPresets
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
                .background(if (selected) KColors.Accent.copy(alpha = 0.14f) else KColors.Surface)
                .border(1.dp, if (selected) KColors.Accent else KColors.Line, RoundedCornerShape(9.dp))
                .pointerInput(preset) {
                    detectTapGestures(
                        onTap = { onPick(a.copy(type = preset.type, x1 = preset.x1, y1 = preset.y1, x2 = preset.x2, y2 = preset.y2, p1 = preset.p1, p2 = preset.p2)) },
                        onLongPress = { if (isUser) userPresets.remove(preset) }
                    )
                }.padding(vertical = 5.dp, horizontal = 3.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                CurveGlyph(preset.asKey(), selected, Modifier.fillMaxWidth().height(22.dp).padding(horizontal = 6.dp))
                Text(preset.label, color = if (selected) KColors.Accent else KColors.Text, fontSize = 8.sp, maxLines = 1, textAlign = TextAlign.Center)
            }
        }
    }
}

private fun presetMatches(a: Keyframe, p: GraphPreset): Boolean {
    if (a.type != p.type) return false
    return when (p.type) {
        KeyType.LINEAR, KeyType.HOLD -> true
        KeyType.BEZIER -> abs(a.x1 - p.x1) < 0.02f && abs(a.y1 - p.y1) < 0.02f && abs(a.x2 - p.x2) < 0.02f && abs(a.y2 - p.y2) < 0.02f
        else -> abs(a.p1 - p.p1) < 0.05f && abs(a.p2 - p.p2) < 0.02f
    }
}

/** Tiny static curve preview used by presets and the type menu. */
@Composable
private fun CurveGlyph(key: Keyframe, highlighted: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        val n = 28
        var lo = 0f; var hi = 1f
        val ys = FloatArray(n + 1) { keyProgress(key, it / n.toFloat()) }
        for (y in ys) { lo = min(lo, y); hi = max(hi, y) }
        val path = Path()
        val padY = 2.dp.toPx()
        for (i in 0..n) {
            val x = size.width * i / n
            val y = size.height - padY - (size.height - 2 * padY) * ((ys[i] - lo) / (hi - lo))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, if (highlighted) KColors.Accent else KColors.Text, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

// ---------------------------------------------------------------------------------------------
// Drawing
// ---------------------------------------------------------------------------------------------

private 
private class GraphGeometry(
    width: Float, height: Float, pad: Float, val rail: Float, keys: List<Keyframe>, segment: Int,
    val mode: GraphMode, fixedEase: Pair<Float, Float>?
) {
    val left = pad
    val right = width - pad
    val top = pad
    val bottom = height - pad
    val w = (right - left).coerceAtLeast(1f)
    val h = (bottom - top).coerceAtLeast(1f)
    var valueLow = 0f
    var valueHigh = 1f
    var easeLo = -0.25f
    var easeHi = 1.25f

    init {
        if (segment in 0 until keys.lastIndex) {
            val a = keys[segment]; val b = keys[segment + 1]
            val lo = min(a.v, b.v); val hi = max(a.v, b.v)
            val span = (hi - lo).let { if (it < 0.0001f) max(abs(hi) * 0.2f, 1f) else it }
            valueLow = lo - span * 0.25f
            valueHigh = hi + span * 0.25f
            if (fixedEase != null) {
                easeLo = fixedEase.first; easeHi = fixedEase.second
            } else {
                var mn = 0f; var mx = 1f
                when (a.type) {
                    KeyType.BEZIER -> { mn = min(mn, min(a.y1, a.y2)); mx = max(mx, max(a.y1, a.y2)) }
                    KeyType.LINEAR, KeyType.HOLD -> {}
                    else -> for (i in 0..80) { val y = keyProgress(a, i / 80f); mn = min(mn, y); mx = max(mx, y) }
                }
                // Quantised so the view only rescales when a curve really needs more room.
                easeLo = kotlin.math.floor((mn - 0.1f) / 0.25f) * 0.25f
                easeHi = kotlin.math.ceil((mx + 0.1f) / 0.25f) * 0.25f
            }
        }
    }

    fun pt(nx: Float, ny: Float) = Offset(left + w * nx, bottom - h * (ny - easeLo) / (easeHi - easeLo))
    fun unY(py: Float) = easeLo + (bottom - py) / h * (easeHi - easeLo)
    fun valuePt(nx: Float, v: Float) = Offset(left + w * nx, bottom - h * ((v - valueLow) / (valueHigh - valueLow)))
    fun unValue(py: Float) = valueLow + ((bottom - py) / h) * (valueHigh - valueLow)
    fun railPt(nx: Float) = Offset(left + w * nx.coerceIn(0f, 1f), bottom - rail)
}

// ---- parameter handles drawn on the curve (Elastic / Bounce / Wave / Steps) ----------------
private fun seeded(k: Keyframe): Keyframe =
    if (k.p1 > 0f) k else { val (p1, p2) = defaultCurveParams(k.type); k.copy(p1 = p1, p2 = if (k.type == KeyType.STEPS) k.p2 else p2) }

private fun bounceApex(n: Int, e: Float): Float {
    var total = 1f; var h = 1f
    for (i in 1..n) { h *= e; total += 2f * kotlin.math.sqrt(h) }
    return (1f + kotlin.math.sqrt(e)) / total
}

/** [0] = value handle on the curve (green), [1] = count/speed handle on the bottom rail (orange). */
private fun paramHandles(key: Keyframe, g: GraphGeometry): Array<Offset?> {
    val k = seeded(key)
    return when (key.type) {
        KeyType.ELASTIC -> arrayOf(g.pt(1f / (2f * k.p2 + 1f), 1f + k.p1), g.railPt((k.p2 - 1f) / 9f))
        KeyType.BOUNCE -> arrayOf(g.pt(bounceApex(k.p1.roundToInt().coerceIn(1, 10), k.p2), 1f - k.p2), g.railPt((k.p1 - 1f) / 9f))
        KeyType.WAVE -> {
            val c = ((k.p1 * 2f).roundToInt() / 2f).coerceAtLeast(0.5f)
            arrayOf(g.pt(1f / (4f * c), 1f / (4f * c) + k.p2), g.railPt((c - 0.5f) / 9.5f))
        }
        KeyType.STEPS -> arrayOf(null, g.railPt((k.p1 - 2f) / 22f))
        else -> arrayOf(null, null)
    }
}

private fun dragParam(base: Keyframe, handle: Int, pos: Offset, g: GraphGeometry): Keyframe {
    val k = seeded(base)
    val nx = ((pos.x - g.left) / g.w).coerceIn(0f, 1f)
    val y = g.unY(pos.y)
    return when (k.type) {
        KeyType.ELASTIC -> if (handle == 3) k.copy(p1 = y - 1f)
            else k.copy(p2 = ((1f + nx * 9f) * 20f).roundToInt() / 20f)
        KeyType.BOUNCE -> if (handle == 3) k.copy(p2 = (1f - y).coerceIn(0.05f, 0.9f))
            else k.copy(p1 = (1f + nx * 9f).roundToInt().toFloat())
        KeyType.WAVE -> {
            if (handle == 3) {
                val c = ((k.p1 * 2f).roundToInt() / 2f).coerceAtLeast(0.5f)
                k.copy(p2 = (y - 1f / (4f * c)).coerceIn(0f, 0.5f))
            } else k.copy(p1 = ((0.5f + nx * 9.5f) * 2f).roundToInt() / 2f)
        }
        KeyType.STEPS -> k.copy(p1 = (2f + nx * 22f).roundToInt().toFloat())
        else -> k
    }
}

private fun paramLabels(k: Keyframe): Pair<String, String> {
    val s = seeded(k)
    return when (k.type) {
        KeyType.ELASTIC -> "+${(s.p1 * 100f).roundToInt()}%" to "Oscillations ${fmt2(s.p2)}"
        KeyType.BOUNCE -> "${(s.p2 * 100f).roundToInt()}%" to "Bounces ${s.p1.roundToInt()}"
        KeyType.WAVE -> fmt2(s.p2) to "Cycles ${String.format(java.util.Locale.US, "%.1f", s.p1)}"
        KeyType.STEPS -> "" to "Steps ${s.p1.roundToInt()}"
        else -> "" to ""
    }
}

private val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
    color = 0xFFE9EDF2.toInt()
    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
}

private fun DrawScope.drawCurveScene(keys: List<Keyframe>, seg: Int, mode: GraphMode, activeHandle: Int, fixedRange: Pair<Float, Float>?) {
    if (seg !in 0 until keys.lastIndex) return
    val pad = 16.dp.toPx()
    val g = GraphGeometry(size.width, size.height, pad, 14.dp.toPx(), keys, seg, mode, fixedRange)
    val a = keys[seg]; val b = keys[seg + 1]
    val accent = KColors.Accent

    // ---- grid
    val gridMinor = KColors.Line.copy(alpha = 0.5f)
    for (i in 0..4) {
        val x = g.left + g.w * i / 4f
        drawLine(gridMinor, Offset(x, g.top), Offset(x, g.bottom), 1.dp.toPx())
    }
    if (mode == GraphMode.EASE) {
        val dash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
        for (rail in floatArrayOf(0f, 1f)) {
            val y = g.pt(0f, rail).y
            drawLine(KColors.Muted.copy(alpha = 0.55f), Offset(g.left, y), Offset(g.right, y), 1.dp.toPx(), pathEffect = dash)
        }
        for (q in floatArrayOf(0.25f, 0.5f, 0.75f)) {
            val y = g.pt(0f, q).y
            drawLine(gridMinor, Offset(g.left, y), Offset(g.right, y), 1.dp.toPx())
        }
    } else {
        for (i in 0..4) {
            val y = g.top + g.h * i / 4f
            drawLine(gridMinor, Offset(g.left, y), Offset(g.right, y), 1.dp.toPx())
        }
    }

    // ---- sample curve
    val n = 96
    val pts = FloatArray((n + 1) * 2)
    val prop = if (mode != GraphMode.EASE) Prop(0f, listOf(a, b)) else null
    for (i in 0..n) {
        val u = i / n.toFloat()
        val p: Offset = when (mode) {
            GraphMode.EASE -> g.pt(u, keyProgress(a, u))
            GraphMode.VALUE -> g.valuePt(u, prop!!.valueAt(a.t + (b.t - a.t) * u))
            GraphMode.SPEED -> {
                val t = a.t + (b.t - a.t) * u
                val dt = max((b.t - a.t) / 400f, 0.0005f)
                val before = prop!!.valueAt((t - dt).coerceAtLeast(a.t))
                val after = prop.valueAt((t + dt).coerceAtMost(b.t))
                val speed = abs(after - before) / max(2f * dt, 0.0001f)
                val peak = abs(b.v - a.v) / max(b.t - a.t, 0.001f)
                Offset(g.left + g.w * u, g.bottom - g.h * (speed / max(peak * 3f, 1f)).coerceIn(0f, 1f))
            }
        }
        pts[i * 2] = p.x; pts[i * 2 + 1] = p.y
    }
    val line = Path().apply {
        moveTo(pts[0], pts[1])
        for (i in 1..n) lineTo(pts[i * 2], pts[i * 2 + 1])
    }
    val baseline = if (mode == GraphMode.EASE) g.pt(0f, 0f).y else g.bottom
    val fill = Path().apply {
        moveTo(pts[0], baseline)
        for (i in 0..n) lineTo(pts[i * 2], pts[i * 2 + 1])
        lineTo(pts[n * 2], baseline)
        close()
    }
    drawPath(fill, Brush.verticalGradient(listOf(accent.copy(alpha = 0.22f), accent.copy(alpha = 0f)), startY = g.top, endY = g.bottom))
    drawPath(line, accent.copy(alpha = 0.22f), style = Stroke(8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(line, Color.White, style = Stroke(2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

    // ---- axes
    drawLine(KColors.Muted.copy(alpha = 0.7f), Offset(g.left, g.bottom), Offset(g.right, g.bottom), 1.dp.toPx())
    drawLine(KColors.Muted.copy(alpha = 0.7f), Offset(g.left, g.top), Offset(g.left, g.bottom), 1.dp.toPx())

    // ---- anchors + handles
    if (mode == GraphMode.EASE) {
        val start = g.pt(0f, 0f); val end = g.pt(1f, 1f)
        if (a.family() == KeyType.BEZIER) {
            val h = easeHandles(a)
            val c1 = g.pt(h[0], h[1]); val c2 = g.pt(h[2], h[3])
            val ghost = if (a.type == KeyType.BEZIER) 1f else 0.55f
            drawHandle(start, c1, GreenHandle, ghost, activeHandle == 1)
            drawHandle(end, c2, OrangeHandle, ghost, activeHandle == 2)
        } else {
            val ph = paramHandles(a, g)
            val labels = paramLabels(a)
            ph[0]?.let { drawParamDot(it, GreenHandle, labels.first, activeHandle == 3, size.width) }
            ph[1]?.let { drawParamDot(it, OrangeHandle, labels.second, activeHandle == 4, size.width) }
        }
        drawAnchor(start, accent, true)
        drawAnchor(end, Color.White, false)
    } else {
        val s = if (mode == GraphMode.VALUE) g.valuePt(0f, a.v) else Offset(g.left, g.bottom)
        val e = if (mode == GraphMode.VALUE) g.valuePt(1f, b.v) else Offset(g.right, g.bottom)
        if (mode == GraphMode.VALUE) {
            drawLine(accent.copy(alpha = 0.4f), s, Offset(s.x, g.bottom), 1.dp.toPx())
            drawLine(Color.White.copy(alpha = 0.3f), e, Offset(e.x, g.bottom), 1.dp.toPx())
        }
        drawAnchor(s, accent, true)
        drawAnchor(e, Color.White, false)
    }
}

private fun DrawScope.drawParamDot(c: Offset, color: Color, label: String, active: Boolean, canvasWidth: Float) {
    val scale = if (active) 1.25f else 1f
    drawCircle(color.copy(alpha = if (active) 0.34f else 0.2f), 17.dp.toPx() * scale, c)
    drawCircle(color, 8.dp.toPx() * scale, c)
    drawCircle(Color.White, 3.dp.toPx() * scale, c)
    if (label.isNotEmpty()) {
        labelPaint.textSize = 10.sp.toPx()
        val width = labelPaint.measureText(label)
        val x = if (c.x + 14.dp.toPx() + width > canvasWidth - 6.dp.toPx()) c.x - 14.dp.toPx() - width else c.x + 14.dp.toPx()
        drawContext.canvas.nativeCanvas.drawText(label, x, c.y + 4.dp.toPx(), labelPaint)
    }
}

private fun DrawScope.drawAnchor(c: Offset, color: Color, filledInner: Boolean) {
    drawCircle(color.copy(alpha = 0.22f), 11.dp.toPx(), c)
    drawCircle(color, 6.dp.toPx(), c)
    drawCircle(Color(0xFF111418), 2.6.dp.toPx(), c)
    if (!filledInner) drawCircle(Color.White, 1.4.dp.toPx(), c)
}

private fun DrawScope.drawHandle(anchor: Offset, c: Offset, color: Color, alpha: Float, active: Boolean) {
    drawLine(color.copy(alpha = 0.85f * alpha), anchor, c, 1.6.dp.toPx(), cap = StrokeCap.Round)
    val scale = if (active) 1.25f else 1f
    drawCircle(color.copy(alpha = (if (active) 0.32f else 0.18f) * alpha), 19.dp.toPx() * scale, c)
    drawCircle(Color(0xFF0B0D10).copy(alpha = alpha), 9.5.dp.toPx() * scale, c)
    drawCircle(color.copy(alpha = alpha), 8.dp.toPx() * scale, c)
    drawCircle(Color.White.copy(alpha = alpha), 3.2.dp.toPx() * scale, c)
}
