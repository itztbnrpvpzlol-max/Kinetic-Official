package com.kinetic.app.ui

import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PathMeasure
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.Xfermode
import android.os.Build
import com.kinetic.app.model.COMP_H
import com.kinetic.app.model.COMP_W
import com.kinetic.app.model.Layer
import com.kinetic.app.model.TextAnimator
import com.kinetic.app.model.PropId
import com.kinetic.app.model.TextStyle
import com.kinetic.app.model.taId
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Text layout + rendering shared by the CPU preview, the GPU preview texture and the exporter.
 * Handles multi-line layout, fonts, fill/stroke/gradient, text-on-mask-path and Flow animators.
 */
internal object TextEngine {
    /** Display name to Android system font family. */
    val fonts: List<Pair<String, String>> = listOf(
        "Default" to "sans-serif",
        "Light" to "sans-serif-light",
        "Thin" to "sans-serif-thin",
        "Condensed" to "sans-serif-condensed",
        "Medium" to "sans-serif-medium",
        "Black" to "sans-serif-black",
        "Serif" to "serif",
        "Mono" to "monospace",
        "Serif Mono" to "serif-monospace",
        "Casual" to "casual",
        "Cursive" to "cursive",
        "Small Caps" to "sans-serif-smallcaps"
    )
    val weights: List<Pair<String, Int>> = listOf(
        "Thin" to 100, "Light" to 300, "Regular" to 400, "Medium" to 500,
        "SemiBold" to 600, "Bold" to 700, "Black" to 900
    )
    val eases = listOf("Linear", "Ease Out", "Ease In-Out", "Back", "Bounce", "Elastic")
    val units = listOf("All", "Per Character", "Per Word", "Per Line")

    private val typefaceCache = HashMap<String, Typeface>()

    fun typeface(style: TextStyle): Typeface = synchronized(typefaceCache) {
        typefaceCache.getOrPut("${style.font}|${style.weight}|${style.italic}") {
            val family = fonts.firstOrNull { it.first == style.font }?.second ?: "sans-serif"
            val base = Typeface.create(family, Typeface.NORMAL)
            if (Build.VERSION.SDK_INT >= 28) Typeface.create(base, style.weight, style.italic)
            else Typeface.create(base, when {
                style.weight >= 600 && style.italic -> Typeface.BOLD_ITALIC
                style.weight >= 600 -> Typeface.BOLD
                style.italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            })
        }
    }

    class Glyph(
        val s: String, val adv: Float, val line: Int, val word: Int, val col: Int, val index: Int,
        var px: Float = 0f, var py: Float = 0f, var angle: Float = 0f, var visible: Boolean = true
    )

    class Layout(
        val glyphs: List<Glyph>, val lineCount: Int, val wordCount: Int,
        val maxLineWidth: Float, val onPath: Boolean, val midOffset: Float
    )

    private fun newPaint(style: TextStyle): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = typeface(style)
        textSize = style.size.coerceAtLeast(1f)
        textAlign = Paint.Align.LEFT
    }

    private fun pathFor(layer: Layer, time: Float, boxW: Float, boxH: Float): android.graphics.Path? {
        if (!layer.textStyle.pathOnMask) return null
        val mask = layer.maskAt(time) ?: return null
        if (mask.points.size < 2) return null
        return buildMaskContour(mask.points, boxW, boxH, mask.closed)
    }

    fun layout(layer: Layer, time: Float, boxW: Float, boxH: Float, paint: Paint = newPaint(layer.textStyle)): Layout {
        val st = layer.textStyle
        val fm = paint.fontMetrics
        val midOffset = (fm.ascent + fm.descent) / 2f
        val lines = layer.text.split('\n')
        val glyphs = ArrayList<Glyph>()
        val lineWidths = FloatArray(lines.size)
        var word = 0
        var index = 0
        lines.forEachIndexed { li, line ->
            var col = 0
            var inSpace = false
            var i = 0
            var w = 0f
            while (i < line.length) {
                val cp = line.codePointAt(i)
                val s = String(Character.toChars(cp))
                i += s.length
                val isSpace = Character.isWhitespace(cp)
                if (!isSpace && inSpace) word++
                inSpace = isSpace
                val adv = paint.measureText(s) + st.tracking
                glyphs.add(Glyph(s, adv, li, word, col, index))
                w += adv
                col++; index++
            }
            lineWidths[li] = max(0f, w - st.tracking)
            word++
        }
        val maxW = lineWidths.maxOrNull() ?: 0f
        val lineH = st.size * st.lineHeight
        val n = lines.size
        val path = pathFor(layer, time, boxW, boxH)
        if (path != null) {
            layoutOnPath(glyphs, lineWidths, path, st, lineH,
                layer.p(PropId.TEXT_FIRST_MARGIN).valueAt(time), layer.p(PropId.TEXT_LAST_MARGIN).valueAt(time))
            return Layout(glyphs, n, word, maxW, true, midOffset)
        }
        val padX = max(0f, (boxW - maxW) / 2f).coerceAtMost(boxW / 2f)
        val cursor = FloatArray(n)
        for (g in glyphs) {
            val lw = lineWidths[g.line]
            val startX = when (st.align) {
                0 -> padX
                2 -> boxW - padX - lw
                else -> (boxW - lw) / 2f
            }
            g.px = startX + cursor[g.line] + g.adv / 2f
            cursor[g.line] += g.adv
            g.py = boxH / 2f - midOffset + (g.line - (n - 1) / 2f) * lineH
        }
        return Layout(glyphs, n, word, maxW, false, midOffset)
    }

    private fun layoutOnPath(glyphs: List<Glyph>, lineWidths: FloatArray, path: android.graphics.Path, st: TextStyle, lineH: Float, firstMargin: Float, lastMargin: Float) {
        val pm = PathMeasure(path, false)
        val len = pm.length
        val pos = FloatArray(2); val tan = FloatArray(2)
        val perLineCursor = FloatArray(lineWidths.size)
        glyphs.forEach { g ->
            val lw = lineWidths[g.line]
            val count = glyphs.count { it.line == g.line }
            val avail = len - firstMargin - lastMargin
            val extra = if (st.forceAlign && count > 1) (avail - lw) / (count - 1) else 0f
            val start = when {
                st.forceAlign -> firstMargin
                st.align == 0 -> firstMargin
                st.align == 2 -> len - lastMargin - lw
                else -> firstMargin + (avail - lw) / 2f
            }
            val d = start + perLineCursor[g.line] + extra * g.col + g.adv / 2f
            perLineCursor[g.line] += g.adv
            val dd = if (st.reversePath) len - d else d
            if (len <= 0f || d < 0f || d > len || !pm.getPosTan(dd.coerceIn(0f, len), pos, tan)) {
                g.visible = false
                return@forEach
            }
            var ang = Math.toDegrees(atan2(tan[1].toDouble(), tan[0].toDouble())).toFloat()
            if (st.reversePath) ang += 180f
            val rad = Math.toRadians(ang.toDouble())
            val shift = g.line * lineH
            g.px = pos[0] + (-sin(rad)).toFloat() * shift
            g.py = pos[1] + cos(rad).toFloat() * shift
            g.angle = if (st.perpendicular) ang else 0f
        }
    }

    /** Box (w, h) that fits the text, its stroke and the reach of its animators; comp-sized when on a path. */
    fun autoFit(layer: Layer): Pair<Float, Float> {
        val st = layer.textStyle
        if (st.pathOnMask) return COMP_W to COMP_H
        val paint = newPaint(st)
        val lines = layer.text.split('\n')
        var maxW = 0f
        var maxChars = 1
        for (l in lines) {
            var w = 0f
            var i = 0
            var c = 0
            while (i < l.length) {
                val s = String(Character.toChars(l.codePointAt(i)))
                i += s.length; c++
                w += paint.measureText(s) + st.tracking
            }
            maxW = max(maxW, w - st.tracking)
            maxChars = max(maxChars, c)
        }
        val lineH = st.size * st.lineHeight
        var padX = st.size * 0.3f + (if (st.strokeEnabled) st.strokeWidth else 0f)
        var padY = padX
        fun peak(id: PropId): Float {
            val p = layer.p(id)
            return max(abs(p.base), p.keys.maxOfOrNull { abs(it.v) } ?: 0f)
        }
        for (a in layer.textAnimators.filter { it.enabled }) {
            fun pk(key: String) = peak(taId(a.slot, key))
            padX += pk("POS_X") + pk("WIG_X") + pk("TRACKING") * maxChars / 2f +
                max(0f, pk("SCALE") - 100f) / 100f * maxW / 2f
            padY += pk("POS_Y") + pk("WIG_Y") +
                max(0f, (if (a.separateScale) pk("SCALE_Y") else pk("SCALE")) - 100f) / 100f * lineH * lines.size / 2f
            if (pk("ROTATION") != 0f || pk("ROT_X") != 0f || pk("ROT_Y") != 0f || pk("WIG_ROT") != 0f) {
                val extra = st.size * 0.5f
                padX += extra; padY += extra
            }
        }
        val w = (maxW + 2f * padX).coerceIn(48f, 4096f)
        val h = ((lines.size - 1) * lineH + st.size * 1.4f + 2f * padY).coerceIn(48f, 4096f)
        return w to h
    }

    // ---------- animators ----------
    private class UnitFx {
        var opacity = 1f; var dx = 0f; var dy = 0f
        var sx = 1f; var sy = 1f; var rot = 0f
        var track = 0f; var rotX = 0f; var rotY = 0f
        fun reset() { opacity = 1f; dx = 0f; dy = 0f; sx = 1f; sy = 1f; rot = 0f; track = 0f; rotX = 0f; rotY = 0f }
    }

    fun ease(name: String, t: Float): Float = when (name) {
        "Linear" -> t
        "Ease In-Out" -> t * t * t * (t * (t * 6f - 15f) + 10f)
        "Back" -> { val c1 = 1.70158f; val c3 = c1 + 1f; 1f + c3 * (t - 1f).pow(3) + c1 * (t - 1f).pow(2) }
        "Bounce" -> {
            val n1 = 7.5625f; val d1 = 2.75f
            when {
                t < 1f / d1 -> n1 * t * t
                t < 2f / d1 -> { val u = t - 1.5f / d1; n1 * u * u + 0.75f }
                t < 2.5f / d1 -> { val u = t - 2.25f / d1; n1 * u * u + 0.9375f }
                else -> { val u = t - 2.625f / d1; n1 * u * u + 0.984375f }
            }
        }
        "Elastic" -> if (t <= 0f) 0f else if (t >= 1f) 1f
            else (2.0.pow(-10.0 * t) * sin((t * 10.0 - 0.75) * (2.0 * Math.PI / 3.0)) + 1.0).toFloat()
        else -> 1f - (1f - t).pow(3)
    }

    private fun hash(seed: Int, unit: Int, chan: Int, i: Int): Float {
        var h = seed * 374761393 + unit * 668265263 + chan * 2147483647 + i * 1274126177
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return ((h and 0xFFFF) / 32767.5f) - 1f
    }

    private fun noise(seed: Int, unit: Int, chan: Int, x: Float): Float {
        val i = floor(x).toInt()
        val f = x - i
        val u = f * f * (3f - 2f * f)
        val a = hash(seed, unit, chan, i)
        val b = hash(seed, unit, chan, i + 1)
        return a + (b - a) * u
    }

    private fun applyAnimator(a: TextAnimator, unit: Int, maxUnit: Int, layer: Layer, time: Float, fx: UnitFx) {
        // Every value is a plain keyframeable property that is applied directly (no hidden "from" state).
        // Delay staggers the units: unit N reads all the curves N * Delay later. Reverse staggers from the last unit.
        val order = if (a.reverse) maxUnit - unit else unit
        val delaySec = layer.p(taId(a.slot, "DELAY")).valueAt(time) / 1000f
        val t = time - order * delaySec
        fun v(key: String) = layer.p(taId(a.slot, key)).valueAt(t)
        // Strength scales how far every value is pushed away from the resting state.
        val k = v("STRENGTH") / 100f
        fx.opacity *= (1f + (v("OPACITY") / 100f - 1f) * k).coerceIn(0f, 1f)
        fx.dx += v("POS_X") * k
        fx.dy += v("POS_Y") * k
        val sxFrom = v("SCALE") / 100f
        val syFrom = (if (a.separateScale) v("SCALE_Y") else v("SCALE")) / 100f
        fx.sx *= 1f + (sxFrom - 1f) * k
        fx.sy *= 1f + (syFrom - 1f) * k
        fx.rot += v("ROTATION") * k
        fx.track += v("TRACKING") * k
        fx.rotX += v("ROT_X") * k
        fx.rotY += v("ROT_Y") * k
        val wx = v("WIG_X"); val wy = v("WIG_Y"); val wsc = v("WIG_SCALE"); val wr = v("WIG_ROT")
        if (wx != 0f || wy != 0f || wsc != 0f || wr != 0f) {
            val u = (time - layer.start) * v("WIG_SPEED")
            val hold = (v("HOLD") / 100f).coerceIn(0f, 1f)
            fun n(chan: Int): Float {
                val smooth = noise(a.seed, unit, chan, u)
                if (hold <= 0f) return smooth
                val stepped = noise(a.seed, unit, chan, floor(u))
                return smooth + (stepped - smooth) * hold
            }
            fx.dx += wx * n(0)
            fx.dy += wy * n(1)
            val ws = 1f + wsc / 100f * n(2)
            fx.sx *= ws; fx.sy *= ws
            fx.rot += wr * n(3)
        }
    }

    private fun unitOf(a: TextAnimator, g: Glyph): Int = when (a.unit) {
        "All" -> 0
        "Per Word" -> g.word
        "Per Line" -> g.line
        else -> g.index
    }

    /**
     * Draws the layer's text into a canvas whose origin is the layer's top-left corner.
     * [alpha], [xfermode] and [colorFilter] let the live preview apply layer opacity and blending.
     */
    fun draw(
        canvas: Canvas, layer: Layer, time: Float, boxW: Float, boxH: Float,
        alpha: Int = 255, xfermode: Xfermode? = null, colorFilter: ColorFilter? = null
    ) {
        val st = layer.textStyle
        if (layer.text.isEmpty()) return
        val paint = newPaint(st)
        val lay = layout(layer, time, boxW, boxH, paint)
        if (lay.glyphs.isEmpty()) return
        val animators = layer.textAnimators.filter { it.enabled }

        // Unit centers (per animator unit mode) so transforms pivot around the unit, not the layer.
        val unitCenters = HashMap<String, FloatArray>()
        fun center(a: TextAnimator, g: Glyph): FloatArray {
            val key = a.unit + ":" + unitOf(a, g)
            return unitCenters.getOrPut(key) {
                var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var sy = 0f; var c = 0
                for (o in lay.glyphs) if (o.visible && unitOf(a, o) == unitOf(a, g)) {
                    minX = min(minX, o.px - o.adv / 2f); maxX = max(maxX, o.px + o.adv / 2f); sy += o.py + lay.midOffset; c++
                }
                if (c == 0) floatArrayOf(g.px, g.py + lay.midOffset) else floatArrayOf((minX + maxX) / 2f, sy / c)
            }
        }

        // Gradient spans the whole text block.
        var shader: Shader? = null
        if (st.gradientEnabled) {
            var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            for (g in lay.glyphs) if (g.visible) {
                minX = min(minX, g.px - g.adv / 2f); maxX = max(maxX, g.px + g.adv / 2f)
                minY = min(minY, g.py - st.size); maxY = max(maxY, g.py + st.size * 0.3f)
            }
            if (minX < maxX) {
                val cx = (minX + maxX) / 2f; val cy = (minY + maxY) / 2f
                val rad = Math.toRadians(st.gradientAngle.toDouble())
                val dx = cos(rad).toFloat(); val dy = sin(rad).toFloat()
                val half = abs(dx) * (maxX - minX) / 2f + abs(dy) * (maxY - minY) / 2f
                shader = LinearGradient(cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half,
                    layer.color, st.gradientColor, Shader.TileMode.CLAMP)
            }
        }

        val fill = Paint(paint).apply {
            style = Paint.Style.FILL
            color = layer.color
            this.shader = shader
            this.xfermode = xfermode
            this.colorFilter = colorFilter
        }
        val stroke = if (st.strokeEnabled && st.strokeWidth > 0f) Paint(paint).apply {
            style = Paint.Style.STROKE
            color = st.strokeColor
            strokeWidth = st.strokeWidth * 2f
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            this.xfermode = xfermode
            this.colorFilter = colorFilter
        } else null

        val camera = Camera()
        val camMatrix = Matrix()
        val unitFxCache = HashMap<String, UnitFx>()
        val maxUnits = HashMap<String, Int>()
        val lineCols = IntArray(lay.lineCount)
        lay.glyphs.forEach { lineCols[it.line] = max(lineCols[it.line], it.col + 1) }
        val unitPivot = FloatArray(2)

        for (g in lay.glyphs) {
            if (!g.visible || g.s.isBlank()) continue
            var opacity = 1f
            canvas.save()
            for (a in animators) {
                val unit = unitOf(a, g)
                val key = a.id + ":" + unit
                val f = unitFxCache.getOrPut(key) {
                    val maxU = maxUnits.getOrPut(a.id) { lay.glyphs.maxOf { unitOf(a, it) } }
                    UnitFx().also { applyAnimator(a, unit, maxU, layer, time, it) }
                }
                val c = center(a, g)
                unitPivot[0] = c[0]; unitPivot[1] = c[1]
                // Tracking FROM value spreads characters symmetrically around their line.
                val trackDx = f.track * (g.col - (lineCols[g.line] - 1) / 2f)
                opacity *= f.opacity
                canvas.translate(f.dx + trackDx, f.dy)
                canvas.translate(unitPivot[0], unitPivot[1])
                if (f.rotX != 0f || f.rotY != 0f) {
                    camera.save()
                    camera.rotateX(f.rotX); camera.rotateY(f.rotY)
                    camera.getMatrix(camMatrix)
                    camera.restore()
                    canvas.concat(camMatrix)
                }
                if (f.rot != 0f) canvas.rotate(f.rot)
                if (f.sx != 1f || f.sy != 1f) canvas.scale(f.sx, f.sy)
                canvas.translate(-unitPivot[0], -unitPivot[1])
            }
            val a255 = (alpha * opacity.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
            if (a255 > 0) {
                canvas.translate(g.px, g.py)
                if (g.angle != 0f) canvas.rotate(g.angle)
                if (stroke != null) { stroke.alpha = a255; canvas.drawText(g.s, -g.adv / 2f, 0f, stroke) }
                fill.alpha = a255
                canvas.drawText(g.s, -g.adv / 2f, 0f, fill)
            }
            canvas.restore()
        }
    }
}
