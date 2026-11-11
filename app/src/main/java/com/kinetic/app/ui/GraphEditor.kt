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

private const val PAD_L = 40f
private const val PAD_R = 14f
private const val PAD_V = 16f

private class GMap(
    val w: Float, val h: Float, val dur: Float, val lo: Float, val hi: Float,
    val padL: Float, val padR: Float, val padV: Float
) {
    fun x(t: Float) = padL + t / dur * (w - padL - padR)
    fun y(v: Float) = padV + (1f - (v - lo) / (hi - lo)) * (h - 2f * padV)
    fun t(x: Float) = (x - padL) / (w - padL - padR) * dur
    fun v(y: Float) = lo + (1f - (y - padV) / (h - 2f * padV)) * (hi - lo)
}

private fun gmap(w: Float, h: Float, dur: Float, r: Pair<Float, Float>, d: Float) =
    GMap(w, h, dur, r.first, r.second, PAD_L * d, PAD_R * d, PAD_V * d)

private fun speedAt(p: Prop, t: Float): Float {
    val d = 0.02f
    return abs(p.valueAt(t + d) - p.valueAt(t - d)) / (2f * d)
}

private fun computeRange(p: Prop, dur: Float, speed: Boolean): Pair<Float, Float> {
    var lo = Float.MAX_VALUE
    var hi = -Float.MAX_VALUE
    val n = 120
    for (i in 0..n) {
        val t = dur * i / n
        val v = if (speed) speedAt(p, t) else p.valueAt(t)
        lo = min(lo, v); hi = max(hi, v)
    }
    if (!speed) for (k in p.keys) { lo = min(lo, k.v); hi = max(hi, k.v) }
    if (speed) {
        lo = 0f
        hi = max(hi, 1f)
        return 0f to hi * 1.18f
    }
    if (hi - lo < 1e-3f) { lo -= 10f; hi += 10f }
    val pad = (hi - lo) * 0.18f
    return (lo - pad) to (hi + pad)
}

private fun segIndex(selected: Int, count: Int): Int = when {
    count < 2 || selected < 0 -> -1
    selected < count - 1 -> selected
    else -> selected - 1
}

private fun handlePts(keys: List<Keyframe>, i: Int): List<Pair<Float, Float>>? {
    if (i !in 0 until keys.size - 1) return null
    val a = keys[i]
    val b = keys[i + 1]
    if (a.type != KeyType.BEZIER) return null
    val dt = b.t - a.t
    val dv = b.v - a.v
    return listOf((a.t + a.x1 * dt) to (a.v + a.y1 * dv), (a.t + a.x2 * dt) to (a.v + a.y2 * dv))
}

private fun hitKey(keys: List<Keyframe>, g: GMap, x: Float, y: Float, r: Float): Int {
    var best = -1
    var bd = r
    keys.forEachIndexed { i, k ->
        val d = hypot(g.x(k.t) - x, g.y(k.v) - y)
        if (d <= bd) { bd = d; best = i }
    }
    return best
}

@Composable
fun GraphPanel(editor: EditorState, layer: Layer, modifier: Modifier = Modifier) {
    val animated = PropId.values().filter { layer.p(it).animated }
    val sel: PropId? = editor.selectedProp?.takeIf { layer.p(it).animated } ?: animated.firstOrNull()
    var speed by remember { mutableStateOf(false) }

    if (sel == null) {
        Box(modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Text(
                "Turn on a stopwatch in Properties to add keyframes, then shape them here.",
                color = KColors.Muted, fontSize = 13.sp, textAlign = TextAlign.Center
            )
        }
        return
    }

    val prop = layer.p(sel)
    val keys = prop.keys
    val dur = editor.duration
    var frozen by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    val range = frozen ?: computeRange(prop, dur, speed)

    val latestLayer by rememberUpdatedState(layer)
    val latestSel by rememberUpdatedState(sel)
    val latestSpeed by rememberUpdatedState(speed)
    val latestRange by rememberUpdatedState(range)
    val labelPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8D8BA3.toInt() } }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp).height(34.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            for (p in animated) Chip(p.label, p == sel) { editor.selectProp(p) }
            Box(Modifier.padding(horizontal = 4.dp))
            Chip("Value", !speed) { speed = false }
            Chip("Speed", speed) { speed = true }
        }

        Canvas(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTapGestures { o ->
                        val g = gmap(size.width.toFloat(), size.height.toFloat(), editor.duration, latestRange, density)
                        val ks = latestLayer.p(latestSel).keys
                        val k = if (latestSpeed) -1 else hitKey(ks, g, o.x, o.y, 24.dp.toPx())
                        if (k >= 0) editor.selectKey(latestSel, k, ks[k].t) else editor.seek(g.t(o.x))
                    }
                }
                .pointerInput(Unit) {
                    var mode = 0 // 0 scrub, 1 key, 2 out-handle, 3 in-handle
                    var idx = -1
                    val hitR = 24.dp.toPx()
                    detectDragGestures(
                        onDragStart = { o ->
                            val l = latestLayer
                            val s = latestSel
                            val ks = l.p(s).keys
                            frozen = latestRange
                            val g = gmap(size.width.toFloat(), size.height.toFloat(), editor.duration, latestRange, density)
                            mode = 0; idx = -1
                            if (!latestSpeed) {
                                val seg = segIndex(editor.selectedKey, ks.size)
                                val hp = if (seg >= 0) handlePts(ks, seg) else null
                                if (hp != null) {
                                    if (hypot(g.x(hp[0].first) - o.x, g.y(hp[0].second) - o.y) < hitR) { mode = 2; idx = seg }
                                    else if (hypot(g.x(hp[1].first) - o.x, g.y(hp[1].second) - o.y) < hitR) { mode = 3; idx = seg }
                                }
                                if (mode == 0) {
                                    val k = hitKey(ks, g, o.x, o.y, hitR)
                                    if (k >= 0) { mode = 1; idx = k; editor.selectKey(s, k, null) }
                                }
                            }
                            if (mode != 0) editor.beginEdit() else editor.seek(g.t(o.x))
                        },
                        onDragEnd = { frozen = null; mode = 0 },
                        onDragCancel = { frozen = null; mode = 0 }
                    ) { change, _ ->
                        change.consume()
                        val l = latestLayer
                        val s = latestSel
                        val ks = l.p(s).keys
                        val g = gmap(size.width.toFloat(), size.height.toFloat(), editor.duration, latestRange, density)
                        val x = change.position.x
                        val y = change.position.y
                        when (mode) {
                            1 -> if (idx in ks.indices) {
                                val prevT = if (idx > 0) ks[idx - 1].t + editor.frame / 2f else 0f
                                val nextT = if (idx < ks.size - 1) ks[idx + 1].t - editor.frame / 2f else editor.duration
                                // A value-graph point edits the value only. Retiming is performed
                                // with the keyframe diamond in the timeline lane.
                                var nv = g.v(y)
                                if (s == PropId.OPACITY) nv = nv.coerceIn(0f, 100f)
                                editor.replaceKey(l.id, s, idx, ks[idx].copy(v = nv), push = false)
                            }
                            2, 3 -> if (idx in 0 until ks.size - 1) {
                                val a = ks[idx]
                                val b = ks[idx + 1]
                                val dt = max(b.t - a.t, 0.01f)
                                val dv = b.v - a.v
                                val tt = ((g.t(x) - a.t) / dt).coerceIn(0f, 1f)
                                val vv = (if (abs(dv) > 1e-4f) (g.v(y) - a.v) / dv else 0f).coerceIn(-2f, 3f)
                                val nk = if (mode == 2) a.copy(type = KeyType.BEZIER, x1 = tt, y1 = vv)
                                else a.copy(type = KeyType.BEZIER, x2 = tt, y2 = vv)
                                editor.replaceKey(l.id, s, idx, nk, push = false)
                            }
                            else -> editor.seek(g.t(x))
                        }
                    }
                }
        ) {
            val g = gmap(size.width, size.height, dur, range, density)
            labelPaint.textSize = 10.sp.toPx()
            val grid = Color(0xFF24242F)
            for (i in 0..4) {
                val v = g.lo + (g.hi - g.lo) * i / 4f
                val yy = g.y(v)
                drawLine(grid, Offset(g.padL, yy), Offset(size.width - g.padR, yy), 1f)
                drawIntoCanvas { it.nativeCanvas.drawText(fmt("%.0f", v), 2.dp.toPx(), yy + 3.dp.toPx(), labelPaint) }
            }
            val step = if (dur <= 10f) 1 else if (dur <= 30f) 5 else 10
            var sec = 0
            while (sec <= dur) {
                val xx = g.x(sec.toFloat())
                drawLine(grid, Offset(xx, g.padV), Offset(xx, size.height - g.padV), 1f)
                sec += step
            }

            val curve = Path()
            val n = 200
            for (i in 0..n) {
                val t = dur * i / n
                val v = if (speed) speedAt(prop, t) else prop.valueAt(t)
                if (i == 0) curve.moveTo(g.x(t), g.y(v)) else curve.lineTo(g.x(t), g.y(v))
            }
            drawPath(curve, KColors.Accent, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

            if (speed) {
                for (k in keys) drawLine(Color(0x558466FF), Offset(g.x(k.t), g.padV), Offset(g.x(k.t), size.height - g.padV), 1.dp.toPx())
            } else {
                val seg = segIndex(editor.selectedKey, keys.size)
                val hp = if (seg >= 0) handlePts(keys, seg) else null
                if (hp != null) {
                    val ka = keys[seg]
                    val kb = keys[seg + 1]
                    val o1 = Offset(g.x(hp[0].first), g.y(hp[0].second))
                    val o2 = Offset(g.x(hp[1].first), g.y(hp[1].second))
                    drawLine(KColors.Muted, Offset(g.x(ka.t), g.y(ka.v)), o1, 1.5.dp.toPx())
                    drawLine(KColors.Muted, Offset(g.x(kb.t), g.y(kb.v)), o2, 1.5.dp.toPx())
                    drawCircle(KColors.Text, 5.dp.toPx(), o1)
                    drawCircle(KColors.Text, 5.dp.toPx(), o2)
                }
                keys.forEachIndexed { i, k ->
                    val c = Offset(g.x(k.t), g.y(k.v))
                    if (i == editor.selectedKey) {
                        drawCircle(Color.White, 8.dp.toPx(), c)
                        drawCircle(KColors.Accent, 5.dp.toPx(), c)
                    } else {
                        drawCircle(KColors.Accent, 6.dp.toPx(), c)
                    }
                }
            }
            val px = g.x(editor.time)
            drawLine(Color(0xCCFFFFFF), Offset(px, 0f), Offset(px, size.height), 1.5.dp.toPx())
        }

        val sk = editor.selectedKey
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp).height(40.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            if (sk in keys.indices) {
                val k = keys[sk]
                fun set(nk: Keyframe) = editor.replaceKey(layer.id, sel, sk, nk, push = true)
                Text(
                    "${fmt("%.2f", k.t)}s  ${sel.format(k.v)}",
                    color = KColors.Muted, fontSize = 11.sp, modifier = Modifier.padding(end = 4.dp)
                )
                Chip("Linear", k.type == KeyType.LINEAR) { set(k.copy(type = KeyType.LINEAR)) }
                Chip("Hold", k.type == KeyType.HOLD) { set(k.copy(type = KeyType.HOLD)) }
                Chip("Ease", k.type == KeyType.BEZIER && k.x1 == 0.42f && k.x2 == 0.58f) {
                    set(k.copy(type = KeyType.BEZIER, x1 = 0.42f, y1 = 0f, x2 = 0.58f, y2 = 1f))
                }
                Chip("Ease in", k.type == KeyType.BEZIER && k.x1 == 0.42f && k.x2 == 1f) {
                    set(k.copy(type = KeyType.BEZIER, x1 = 0.42f, y1 = 0f, x2 = 1f, y2 = 1f))
                }
                Chip("Ease out", k.type == KeyType.BEZIER && k.x1 == 0f && k.x2 == 0.58f) {
                    set(k.copy(type = KeyType.BEZIER, x1 = 0f, y1 = 0f, x2 = 0.58f, y2 = 1f))
                }
                Chip("Elastic", k.type == KeyType.ELASTIC) { set(k.copy(type = KeyType.ELASTIC)) }
                Chip("Bounce", k.type == KeyType.BOUNCE) { set(k.copy(type = KeyType.BOUNCE)) }
                Chip("Wiggle", prop.expression?.startsWith("wiggle", true) == true) {
                    editor.setExpression(layer.id, sel, if (prop.expression == null) "wiggle(2, 20)" else null)
                }
                Chip("Delete", false) { editor.deleteKey(layer.id, sel, sk) }
            } else {
                Text(
                    "Drag points vertically to change value. Drag timeline diamonds to retime.",
                    color = KColors.Dim, fontSize = 11.sp
                )
            }
        }
    }
}
