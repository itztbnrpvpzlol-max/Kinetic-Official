package com.kinetic.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

enum class IconKind {
    EYE, EYE_OFF, HANDLE, PLAY, PAUSE, UNDO, REDO, DIAMOND, DIAMOND_FILLED, STOPWATCH,
    SELECT, SHAPES, STAR, LAYERS, SLIDERS, PREV, NEXT, CHEVRON_DOWN, CHEVRON_UP, PLUS,
    IMAGE, MUSIC, VIDEO, NULL, ADJ, TEXT, PARENT
}

@Composable
fun KIcon(kind: IconKind, tint: Color, modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.09f
        val st = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun pt(x: Float, y: Float) = Offset(w * x, h * y)
        fun poly(closed: Boolean, vararg pts: Any): Path = Path().apply {
            moveTo((pts[0] as Offset).x, (pts[0] as Offset).y)
            for (i in 1 until pts.size) lineTo((pts[i] as Offset).x, (pts[i] as Offset).y)
            if (closed) close()
        }
        fun line(a: Offset, b: Offset) = drawLine(tint, a, b, strokeWidth = sw, cap = StrokeCap.Round)

        when (kind) {
            IconKind.EYE, IconKind.EYE_OFF -> {
                val p = Path().apply {
                    moveTo(w * .08f, h * .5f)
                    cubicTo(w * .25f, h * .2f, w * .75f, h * .2f, w * .92f, h * .5f)
                    cubicTo(w * .75f, h * .8f, w * .25f, h * .8f, w * .08f, h * .5f)
                    close()
                }
                drawPath(p, tint, style = st)
                drawCircle(tint, w * .14f, pt(.5f, .5f))
                if (kind == IconKind.EYE_OFF) line(pt(.15f, .85f), pt(.85f, .15f))
            }
            IconKind.HANDLE -> {
                line(pt(.18f, .3f), pt(.82f, .3f)); line(pt(.18f, .5f), pt(.82f, .5f)); line(pt(.18f, .7f), pt(.82f, .7f))
            }
            IconKind.PARENT -> {
                drawCircle(tint, w * .16f, pt(.5f, .2f), style = st)
                drawCircle(tint, w * .16f, pt(.5f, .8f), style = st)
                line(pt(.5f, .36f), pt(.5f, .64f))
            }
            IconKind.PLAY -> drawPath(poly(true, pt(.3f, .15f), pt(.85f, .5f), pt(.3f, .85f)), tint)
            IconKind.PAUSE -> {
                drawRect(tint, pt(.22f, .15f), Size(w * .2f, h * .7f))
                drawRect(tint, pt(.58f, .15f), Size(w * .2f, h * .7f))
            }
            IconKind.UNDO, IconKind.REDO -> {
                val flip = if (kind == IconKind.REDO) -1f else 1f
                scale(flip, 1f, pivot = Offset(w / 2f, h / 2f)) {
                    val p = Path().apply {
                        moveTo(w * .82f, h * .78f)
                        cubicTo(w * .92f, h * .45f, w * .6f, h * .32f, w * .28f, h * .42f)
                    }
                    drawPath(p, tint, style = st)
                    drawPath(poly(false, pt(.4f, .22f), pt(.22f, .43f), pt(.44f, .6f)), tint, style = st)
                }
            }
            IconKind.DIAMOND, IconKind.DIAMOND_FILLED -> {
                val d = poly(true, pt(.5f, .12f), pt(.88f, .5f), pt(.5f, .88f), pt(.12f, .5f))
                if (kind == IconKind.DIAMOND_FILLED) drawPath(d, tint) else drawPath(d, tint, style = st)
            }
            IconKind.STOPWATCH -> {
                drawCircle(tint, w * .32f, pt(.5f, .58f), style = st)
                line(pt(.5f, .58f), pt(.5f, .4f))
                line(pt(.38f, .1f), pt(.62f, .1f)); line(pt(.5f, .1f), pt(.5f, .24f))
            }
            IconKind.SELECT -> drawPath(
                poly(true, pt(.28f, .12f), pt(.28f, .78f), pt(.44f, .62f), pt(.56f, .9f), pt(.67f, .84f), pt(.55f, .58f), pt(.78f, .58f)),
                tint, style = st
            )
            IconKind.SHAPES -> {
                drawCircle(tint, w * .2f, pt(.36f, .36f), style = st)
                drawRoundRect(tint, pt(.42f, .42f), Size(w * .46f, h * .46f), CornerRadius(w * .06f), style = st)
            }
            IconKind.STAR -> {
                val star = Path()
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) .42f else .18f
                    val a = -Math.PI / 2 + i * Math.PI / 5
                    val x = w * (.5f + r * cos(a).toFloat())
                    val y = h * (.5f + r * sin(a).toFloat())
                    if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
                }
                star.close()
                drawPath(star, tint, style = st)
            }
            IconKind.LAYERS -> {
                drawPath(poly(true, pt(.1f, .38f), pt(.5f, .18f), pt(.9f, .38f), pt(.5f, .58f)), tint, style = st)
                drawPath(poly(false, pt(.1f, .54f), pt(.5f, .74f), pt(.9f, .54f)), tint, style = st)
                drawPath(poly(false, pt(.1f, .7f), pt(.5f, .9f), pt(.9f, .7f)), tint, style = st)
            }
            IconKind.SLIDERS -> {
                line(pt(.12f, .25f), pt(.88f, .25f)); line(pt(.12f, .5f), pt(.88f, .5f)); line(pt(.12f, .75f), pt(.88f, .75f))
                drawCircle(tint, w * .09f, pt(.35f, .25f)); drawCircle(tint, w * .09f, pt(.65f, .5f)); drawCircle(tint, w * .09f, pt(.45f, .75f))
            }
            IconKind.PREV -> drawPath(poly(false, pt(.65f, .2f), pt(.3f, .5f), pt(.65f, .8f)), tint, style = st)
            IconKind.NEXT -> drawPath(poly(false, pt(.35f, .2f), pt(.7f, .5f), pt(.35f, .8f)), tint, style = st)
            IconKind.CHEVRON_DOWN -> drawPath(poly(false, pt(.25f, .38f), pt(.5f, .64f), pt(.75f, .38f)), tint, style = st)
            IconKind.CHEVRON_UP -> drawPath(poly(false, pt(.25f, .62f), pt(.5f, .36f), pt(.75f, .62f)), tint, style = st)
            IconKind.PLUS -> { line(pt(.5f, .2f), pt(.5f, .8f)); line(pt(.2f, .5f), pt(.8f, .5f)) }
            IconKind.IMAGE -> {
                drawRoundRect(tint, pt(.12f, .16f), Size(w * .76f, h * .68f), CornerRadius(w * .08f), style = st)
                drawCircle(tint, w * .07f, pt(.34f, .36f))
                drawPath(poly(false, pt(.16f, .78f), pt(.42f, .52f), pt(.6f, .68f), pt(.72f, .56f), pt(.86f, .72f)), tint, style = st)
            }
            IconKind.MUSIC -> {
                drawCircle(tint, w * .12f, pt(.32f, .76f))
                line(pt(.44f, .76f), pt(.44f, .2f)); line(pt(.44f, .2f), pt(.78f, .3f))
            }
            IconKind.VIDEO -> {
                drawRoundRect(tint, pt(.1f, .18f), Size(w * .8f, h * .64f), CornerRadius(w * .08f), style = st)
                drawPath(poly(true, pt(.42f, .36f), pt(.62f, .5f), pt(.42f, .64f)), tint)
            }
            IconKind.NULL -> {
                drawRect(tint, pt(.18f, .18f), Size(w * .64f, h * .64f), style = st)
                line(pt(.5f, .06f), pt(.5f, .94f)); line(pt(.06f, .5f), pt(.94f, .5f))
            }
            IconKind.ADJ -> {
                drawCircle(tint, w * .36f, pt(.5f, .5f), style = st)
                drawArc(tint, -90f, 180f, true, pt(.14f, .14f), Size(w * .72f, h * .72f))
            }
            IconKind.TEXT -> { line(pt(.2f, .2f), pt(.8f, .2f)); line(pt(.5f, .2f), pt(.5f, .82f)) }
        }
    }
}
