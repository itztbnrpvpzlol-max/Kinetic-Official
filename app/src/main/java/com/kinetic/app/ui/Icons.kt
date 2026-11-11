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

/**
 * Kinetic's compact outline icon set. The matching SVG source sprite lives in
 * app/src/main/assets/icons/kinetic-icons.svg; these Compose paths keep the
 * editor's frequently redrawn controls native and allocation-light.
 */
enum class IconKind {
    EYE, EYE_OFF, HANDLE, PLAY, PAUSE, UNDO, REDO, DIAMOND, DIAMOND_FILLED, STOPWATCH,
    SELECT, SHAPES, STAR, LAYERS, SLIDERS, PREV, NEXT, CHEVRON_DOWN, CHEVRON_UP, PLUS,
    IMAGE, MUSIC, VIDEO, NULL, ADJ, TEXT, PARENT,
    LOCK, UNLOCK, SETTINGS, EXPORT, IMPORT, DELETE, CUT, SPLIT, CROP, ROTATE, FLIP,
    COPY, MORE, ZOOM_IN, ZOOM_OUT, EFFECTS, FILTER, MASK, BLEND, WAVEFORM, SEARCH,
    BACK, CLOSE, CHECK, SHARE, FOLDER, HOME, MIC, MUTE, VOLUME, GRID, ALIGN, FIT,
    FULLSCREEN, DOWNLOAD, UPLOAD, INFO
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
        fun outlinedCircle(cx: Float, cy: Float, r: Float) = drawCircle(tint, w * r, pt(cx, cy), style = st)
        fun filledCircle(cx: Float, cy: Float, r: Float) = drawCircle(tint, w * r, pt(cx, cy))
        fun rect(x: Float, y: Float, rw: Float, rh: Float, radius: Float = .06f) =
            drawRoundRect(tint, pt(x, y), Size(w * rw, h * rh), CornerRadius(w * radius), style = st)

        when (kind) {
            IconKind.EYE, IconKind.EYE_OFF -> {
                val p = Path().apply {
                    moveTo(w * .08f, h * .5f)
                    cubicTo(w * .25f, h * .2f, w * .75f, h * .2f, w * .92f, h * .5f)
                    cubicTo(w * .75f, h * .8f, w * .25f, h * .8f, w * .08f, h * .5f)
                    close()
                }
                drawPath(p, tint, style = st); filledCircle(.5f, .5f, .14f)
                if (kind == IconKind.EYE_OFF) line(pt(.15f, .85f), pt(.85f, .15f))
            }
            IconKind.HANDLE -> { line(pt(.18f,.3f),pt(.82f,.3f)); line(pt(.18f,.5f),pt(.82f,.5f)); line(pt(.18f,.7f),pt(.82f,.7f)) }
            IconKind.PARENT -> { outlinedCircle(.5f,.2f,.16f); outlinedCircle(.5f,.8f,.16f); line(pt(.5f,.36f),pt(.5f,.64f)) }
            IconKind.PLAY -> drawPath(poly(true,pt(.3f,.15f),pt(.85f,.5f),pt(.3f,.85f)),tint)
            IconKind.PAUSE -> { drawRect(tint,pt(.22f,.15f),Size(w*.2f,h*.7f)); drawRect(tint,pt(.58f,.15f),Size(w*.2f,h*.7f)) }
            IconKind.UNDO, IconKind.REDO -> {
                val flip = if (kind == IconKind.REDO) -1f else 1f
                scale(flip,1f,pivot=Offset(w/2f,h/2f)) {
                    val p=Path().apply { moveTo(w*.82f,h*.78f); cubicTo(w*.92f,h*.45f,w*.6f,h*.32f,w*.28f,h*.42f) }
                    drawPath(p,tint,style=st); drawPath(poly(false,pt(.4f,.22f),pt(.22f,.43f),pt(.44f,.6f)),tint,style=st)
                }
            }
            IconKind.DIAMOND, IconKind.DIAMOND_FILLED -> {
                val d=poly(true,pt(.5f,.12f),pt(.88f,.5f),pt(.5f,.88f),pt(.12f,.5f))
                if(kind==IconKind.DIAMOND_FILLED) drawPath(d,tint) else drawPath(d,tint,style=st)
            }
            IconKind.STOPWATCH -> { outlinedCircle(.5f,.58f,.32f); line(pt(.5f,.58f),pt(.5f,.4f)); line(pt(.38f,.1f),pt(.62f,.1f)); line(pt(.5f,.1f),pt(.5f,.24f)) }
            IconKind.SELECT -> drawPath(poly(true,pt(.28f,.12f),pt(.28f,.78f),pt(.44f,.62f),pt(.56f,.9f),pt(.67f,.84f),pt(.55f,.58f),pt(.78f,.58f)),tint,style=st)
            IconKind.SHAPES -> { outlinedCircle(.36f,.36f,.2f); rect(.42f,.42f,.46f,.46f) }
            IconKind.STAR, IconKind.EFFECTS -> {
                val star=Path()
                for(i in 0 until 10){ val r=if(i%2==0).42f else .18f; val a=-Math.PI/2+i*Math.PI/5; val x=w*(.5f+r*cos(a).toFloat()); val y=h*(.5f+r*sin(a).toFloat()); if(i==0)star.moveTo(x,y) else star.lineTo(x,y) }
                star.close(); drawPath(star,tint,style=st)
                if(kind==IconKind.EFFECTS){ line(pt(.78f,.12f),pt(.78f,.28f)); line(pt(.7f,.2f),pt(.86f,.2f)) }
            }
            IconKind.LAYERS -> { drawPath(poly(true,pt(.1f,.38f),pt(.5f,.18f),pt(.9f,.38f),pt(.5f,.58f)),tint,style=st); drawPath(poly(false,pt(.1f,.54f),pt(.5f,.74f),pt(.9f,.54f)),tint,style=st); drawPath(poly(false,pt(.1f,.7f),pt(.5f,.9f),pt(.9f,.7f)),tint,style=st) }
            IconKind.SLIDERS -> { line(pt(.12f,.25f),pt(.88f,.25f)); line(pt(.12f,.5f),pt(.88f,.5f)); line(pt(.12f,.75f),pt(.88f,.75f)); filledCircle(.35f,.25f,.09f); filledCircle(.65f,.5f,.09f); filledCircle(.45f,.75f,.09f) }
            IconKind.PREV, IconKind.BACK -> { line(pt(.34f,.18f),pt(.34f,.82f)); drawPath(poly(false,pt(.68f,.2f),pt(.32f,.5f),pt(.68f,.8f)),tint,style=st) }
            IconKind.NEXT -> { line(pt(.66f,.18f),pt(.66f,.82f)); drawPath(poly(false,pt(.32f,.2f),pt(.68f,.5f),pt(.32f,.8f)),tint,style=st) }
            IconKind.CHEVRON_DOWN -> drawPath(poly(false,pt(.25f,.38f),pt(.5f,.64f),pt(.75f,.38f)),tint,style=st)
            IconKind.CHEVRON_UP -> drawPath(poly(false,pt(.25f,.62f),pt(.5f,.36f),pt(.75f,.62f)),tint,style=st)
            IconKind.PLUS, IconKind.ZOOM_IN -> { line(pt(.5f,.2f),pt(.5f,.8f)); line(pt(.2f,.5f),pt(.8f,.5f)); if(kind==IconKind.ZOOM_IN){ outlinedCircle(.42f,.42f,.27f); line(pt(.62f,.62f),pt(.88f,.88f)) } }
            IconKind.IMAGE -> { rect(.12f,.16f,.76f,.68f,.08f); filledCircle(.34f,.36f,.07f); drawPath(poly(false,pt(.16f,.78f),pt(.42f,.52f),pt(.6f,.68f),pt(.72f,.56f),pt(.86f,.72f)),tint,style=st) }
            IconKind.MUSIC, IconKind.MIC -> {
                if(kind==IconKind.MUSIC){ filledCircle(.32f,.76f,.12f); line(pt(.44f,.76f),pt(.44f,.2f)); line(pt(.44f,.2f),pt(.78f,.3f)) }
                else { rect(.38f,.12f,.24f,.5f,.12f); drawArc(tint,0f,180f,true,pt(.22f,.28f),Size(w*.56f,h*.56f),style=st); line(pt(.5f,.84f),pt(.5f,.96f)); line(pt(.32f,.96f),pt(.68f,.96f)) }
            }
            IconKind.VIDEO -> { rect(.1f,.18f,.8f,.64f,.08f); drawPath(poly(true,pt(.42f,.36f),pt(.62f,.5f),pt(.42f,.64f)),tint) }
            IconKind.NULL -> { drawRect(tint,pt(.18f,.18f),Size(w*.64f,h*.64f),style=st); line(pt(.5f,.06f),pt(.5f,.94f)); line(pt(.06f,.5f),pt(.94f,.5f)) }
            IconKind.ADJ -> { outlinedCircle(.5f,.5f,.36f); drawArc(tint,-90f,180f,true,pt(.14f,.14f),Size(w*.72f,h*.72f)) }
            IconKind.TEXT -> { line(pt(.2f,.2f),pt(.8f,.2f)); line(pt(.5f,.2f),pt(.5f,.82f)) }
            IconKind.LOCK, IconKind.UNLOCK -> {
                rect(.2f,.43f,.6f,.46f,.08f)
                val shackle=Path().apply { moveTo(w*.32f,h*.44f); lineTo(w*.32f,h*.32f); cubicTo(w*.32f,h*.08f,w*.68f,h*.08f,w*.68f,h*.32f); if(kind==IconKind.LOCK) lineTo(w*.68f,h*.44f) else lineTo(w*.58f,h*.44f) }
                drawPath(shackle,tint,style=st)
            }
            IconKind.SETTINGS -> { outlinedCircle(.5f,.5f,.32f); outlinedCircle(.5f,.5f,.11f); for(i in 0 until 8){ val a=i*Math.PI/4; line(pt(.5f+.34f*cos(a).toFloat(),.5f+.34f*sin(a).toFloat()),pt(.5f+.44f*cos(a).toFloat(),.5f+.44f*sin(a).toFloat())) } }
            IconKind.EXPORT, IconKind.UPLOAD -> { line(pt(.5f,.68f),pt(.5f,.14f)); drawPath(poly(false,pt(.28f,.36f),pt(.5f,.14f),pt(.72f,.36f)),tint,style=st); line(pt(.18f,.72f),pt(.18f,.88f)); line(pt(.18f,.88f),pt(.82f,.88f)); line(pt(.82f,.88f),pt(.82f,.72f)) }
            IconKind.IMPORT, IconKind.DOWNLOAD -> { line(pt(.5f,.14f),pt(.5f,.68f)); drawPath(poly(false,pt(.28f,.46f),pt(.5f,.68f),pt(.72f,.46f)),tint,style=st); line(pt(.18f,.72f),pt(.18f,.88f)); line(pt(.18f,.88f),pt(.82f,.88f)); line(pt(.82f,.88f),pt(.82f,.72f)) }
            IconKind.DELETE -> { rect(.24f,.28f,.52f,.62f,.04f); line(pt(.16f,.22f),pt(.84f,.22f)); line(pt(.38f,.12f),pt(.62f,.12f)); line(pt(.42f,.4f),pt(.42f,.78f)); line(pt(.58f,.4f),pt(.58f,.78f)) }
            IconKind.CUT, IconKind.SPLIT -> { outlinedCircle(.28f,.25f,.13f); outlinedCircle(.28f,.75f,.13f); line(pt(.38f,.32f),pt(.84f,.82f)); line(pt(.38f,.68f),pt(.84f,.18f)); if(kind==IconKind.SPLIT) line(pt(.5f,.08f),pt(.5f,.92f)) }
            IconKind.CROP -> { line(pt(.22f,.08f),pt(.22f,.78f)); line(pt(.22f,.78f),pt(.92f,.78f)); line(pt(.08f,.22f),pt(.78f,.22f)); line(pt(.78f,.22f),pt(.78f,.92f)) }
            IconKind.ROTATE -> { drawArc(tint,-35f,285f,true,pt(.12f,.12f),Size(w*.76f,h*.76f),style=st); drawPath(poly(true,pt(.72f,.08f),pt(.9f,.28f),pt(.66f,.3f)),tint) }
            IconKind.FLIP -> { line(pt(.5f,.12f),pt(.5f,.88f)); drawPath(poly(true,pt(.12f,.3f),pt(.42f,.5f),pt(.12f,.7f)),tint,style=st); drawPath(poly(true,pt(.88f,.3f),pt(.58f,.5f),pt(.88f,.7f)),tint,style=st) }
            IconKind.COPY -> { rect(.3f,.12f,.56f,.62f); rect(.14f,.28f,.56f,.62f) }
            IconKind.MORE -> { filledCircle(.2f,.5f,.07f); filledCircle(.5f,.5f,.07f); filledCircle(.8f,.5f,.07f) }
            IconKind.ZOOM_OUT -> { outlinedCircle(.42f,.42f,.27f); line(pt(.62f,.62f),pt(.88f,.88f)); line(pt(.29f,.42f),pt(.55f,.42f)) }
            IconKind.FILTER -> { drawPath(poly(true,pt(.12f,.18f),pt(.88f,.18f),pt(.58f,.52f),pt(.58f,.84f),pt(.42f,.92f),pt(.42f,.52f)),tint,style=st) }
            IconKind.MASK -> { val p=Path().apply{moveTo(w*.12f,h*.5f); cubicTo(w*.28f,h*.12f,w*.72f,h*.12f,w*.88f,h*.5f); cubicTo(w*.72f,h*.88f,w*.28f,h*.88f,w*.12f,h*.5f)}; drawPath(p,tint,style=st); line(pt(.5f,.18f),pt(.5f,.82f)) }
            IconKind.BLEND -> { outlinedCircle(.4f,.5f,.28f); outlinedCircle(.6f,.5f,.28f) }
            IconKind.WAVEFORM -> { val xs=floatArrayOf(.16f,.28f,.4f,.52f,.64f,.76f,.88f); val hs=floatArrayOf(.22f,.46f,.3f,.72f,.5f,.36f,.62f); for(i in xs.indices) line(pt(xs[i],.5f-hs[i]/2f),pt(xs[i],.5f+hs[i]/2f)) }
            IconKind.SEARCH -> { outlinedCircle(.42f,.42f,.27f); line(pt(.62f,.62f),pt(.88f,.88f)) }
            IconKind.CLOSE -> { line(pt(.22f,.22f),pt(.78f,.78f)); line(pt(.78f,.22f),pt(.22f,.78f)) }
            IconKind.CHECK -> drawPath(poly(false,pt(.16f,.52f),pt(.4f,.76f),pt(.86f,.24f)),tint,style=st)
            IconKind.SHARE -> { filledCircle(.22f,.5f,.1f); filledCircle(.78f,.22f,.1f); filledCircle(.78f,.78f,.1f); line(pt(.31f,.45f),pt(.68f,.27f)); line(pt(.31f,.55f),pt(.68f,.73f)) }
            IconKind.FOLDER -> { val p=Path().apply{moveTo(w*.1f,h*.28f); lineTo(w*.42f,h*.28f); lineTo(w*.52f,h*.4f); lineTo(w*.9f,h*.4f); lineTo(w*.86f,h*.84f); lineTo(w*.14f,h*.84f); close()}; drawPath(p,tint,style=st) }
            IconKind.HOME -> { drawPath(poly(false,pt(.1f,.46f),pt(.5f,.12f),pt(.9f,.46f)),tint,style=st); rect(.22f,.44f,.56f,.44f); line(pt(.44f,.88f),pt(.44f,.62f)); line(pt(.56f,.88f),pt(.56f,.62f)) }
            IconKind.MUTE -> { drawPath(poly(true,pt(.12f,.4f),pt(.32f,.4f),pt(.56f,.2f),pt(.56f,.8f),pt(.32f,.6f),pt(.12f,.6f)),tint,style=st); line(pt(.68f,.34f),pt(.9f,.66f)); line(pt(.9f,.34f),pt(.68f,.66f)) }
            IconKind.VOLUME -> { drawPath(poly(true,pt(.12f,.4f),pt(.32f,.4f),pt(.56f,.2f),pt(.56f,.8f),pt(.32f,.6f),pt(.12f,.6f)),tint,style=st); drawArc(tint,-55f,110f,false,pt(.42f,.25f),Size(w*.48f,h*.5f),style=st) }
            IconKind.GRID -> { for(x in 0..2) for(y in 0..2) rect(.14f+x*.25f,.14f+y*.25f,.16f,.16f,.02f) }
            IconKind.ALIGN -> { line(pt(.2f,.2f),pt(.8f,.2f)); line(pt(.2f,.5f),pt(.65f,.5f)); line(pt(.2f,.8f),pt(.72f,.8f)); line(pt(.2f,.1f),pt(.2f,.9f)) }
            IconKind.FIT, IconKind.FULLSCREEN -> { line(pt(.12f,.34f),pt(.12f,.12f)); line(pt(.12f,.12f),pt(.34f,.12f)); line(pt(.66f,.12f),pt(.88f,.12f)); line(pt(.88f,.12f),pt(.88f,.34f)); line(pt(.12f,.66f),pt(.12f,.88f)); line(pt(.12f,.88f),pt(.34f,.88f)); line(pt(.66f,.88f),pt(.88f,.88f)); line(pt(.88f,.88f),pt(.88f,.66f)) }
            IconKind.INFO -> { outlinedCircle(.5f,.5f,.38f); filledCircle(.5f,.32f,.045f); line(pt(.5f,.46f),pt(.5f,.72f)) }
        }
    }
}
