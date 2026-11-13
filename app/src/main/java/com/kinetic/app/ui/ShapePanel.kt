package com.kinetic.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinetic.app.model.VectorPoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class ShapePreset(val name: String, val category: String, val points: List<VectorPoint>)
private fun xy(vararg p: Pair<Float, Float>) = p.map { VectorPoint(it.first, it.second) }
private fun regular(n: Int, rotation: Float = -90f): List<VectorPoint> = (0 until n).map {
    val a = (rotation + it * 360f / n) * PI.toFloat() / 180f
    VectorPoint(.5f + .48f * cos(a), .5f + .48f * sin(a))
}
private fun star(n: Int, inner: Float = .43f): List<VectorPoint> = (0 until n * 2).map {
    val a = (-90f + it * 180f / n) * PI.toFloat() / 180f
    val r = if (it % 2 == 0) .49f else .49f * inner
    VectorPoint(.5f + r * cos(a), .5f + r * sin(a))
}
private fun ellipse(rx: Float = .48f, ry: Float = .48f): List<VectorPoint> = (0 until 36).map {
    val a = it * 2f * PI.toFloat() / 36f
    VectorPoint(.5f + rx * cos(a), .5f + ry * sin(a))
}
private fun heart(): List<VectorPoint> = (0..48).map {
    val t = it / 48f * 2f * PI.toFloat()
    val x = 16f * sin(t).let { v -> v * v * v }
    val y = 13f * cos(t) - 5f * cos(2f*t) - 2f*cos(3f*t) - cos(4f*t)
    VectorPoint(.5f + x / 36f, .48f - y / 34f)
}
private fun pointsFor(name: String): List<VectorPoint> = when (name) {
    "Square" -> xy(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f)
    "Rectangle" -> xy(.05f to .22f, .95f to .22f, .95f to .78f, .05f to .78f)
    "Circle" -> ellipse()
    "Ellipse" -> ellipse(.49f, .31f)
    "Triangle" -> regular(3)
    "Right triangle" -> xy(.08f to .9f, .08f to .1f, .92f to .9f)
    "Diamond" -> xy(.5f to .02f, .98f to .5f, .5f to .98f, .02f to .5f)
    "Pentagon" -> regular(5)
    "Hexagon" -> regular(6)
    "Heptagon" -> regular(7)
    "Octagon" -> regular(8)
    "Nonagon" -> regular(9)
    "Decagon" -> regular(10)
    "Trapezoid" -> xy(.25f to .12f, .75f to .12f, .98f to .88f, .02f to .88f)
    "Parallelogram" -> xy(.28f to .1f, .98f to .1f, .72f to .9f, .02f to .9f)
    "Kite" -> xy(.5f to .02f, .94f to .42f, .5f to .98f, .12f to .42f)
    "Star 4" -> star(4,.62f)
    "Star 5" -> star(5)
    "Star 6" -> star(6)
    "Star 7" -> star(7)
    "Star 8" -> star(8)
    "Star 9" -> star(9)
    "Star 10" -> star(10)
    "Star 12" -> star(12)
    "Burst" -> star(16,.78f)
    "Arrow up" -> xy(.38f to .96f,.62f to .96f,.62f to .4f,.86f to .4f,.5f to .04f,.14f to .4f,.38f to .4f)
    "Arrow down" -> pointsFor("Arrow up").map { VectorPoint(it.x,1f-it.y) }
    "Arrow right" -> pointsFor("Arrow up").map { VectorPoint(it.y,it.x) }
    "Arrow left" -> pointsFor("Arrow right").map { VectorPoint(1f-it.x,it.y) }
    "Double arrow" -> xy(.04f to .5f,.3f to .18f,.3f to .38f,.7f to .38f,.7f to .18f,.96f to .5f,.7f to .82f,.7f to .62f,.3f to .62f,.3f to .82f)
    "Chevron" -> xy(.12f to .08f,.58f to .08f,.94f to .5f,.58f to .92f,.12f to .92f,.48f to .5f)
    "Chevron left" -> pointsFor("Chevron").map { VectorPoint(1f-it.x,it.y) }
    "Chevron right" -> pointsFor("Chevron")
    "Plus" -> xy(.38f to .04f,.62f to .04f,.62f to .38f,.96f to .38f,.96f to .62f,.62f to .62f,.62f to .96f,.38f to .96f,.38f to .62f,.04f to .62f,.04f to .38f,.38f to .38f)
    "Cross" -> xy(.32f to .02f,.68f to .02f,.68f to .32f,.98f to .32f,.98f to .68f,.68f to .68f,.68f to .98f,.32f to .98f,.32f to .68f,.02f to .68f,.02f to .32f,.32f to .32f)
    "X" -> xy(.08f to .22f,.22f to .08f,.5f to .36f,.78f to .08f,.92f to .22f,.64f to .5f,.92f to .78f,.78f to .92f,.5f to .64f,.22f to .92f,.08f to .78f,.36f to .5f)
    "Heart" -> heart()
    "Lightning" -> xy(.58f to .02f,.18f to .56f,.44f to .56f,.34f to .98f,.84f to .38f,.56f to .38f)
    "Speech bubble" -> xy(.08f to .12f,.92f to .12f,.92f to .72f,.58f to .72f,.34f to .96f,.36f to .72f,.08f to .72f)
    "Cloud" -> xy(.12f to .7f,.08f to .59f,.1f to .46f,.18f to .38f,.3f to .37f,.34f to .22f,.48f to .14f,.64f to .18f,.72f to .33f,.85f to .32f,.94f to .43f,.96f to .58f,.9f to .7f)
    "Gear" -> star(12,.82f)
    "Flower" -> star(10,.68f)
    "Leaf" -> (0..32).map { i -> val a=i/32f*2f*PI.toFloat(); VectorPoint(.5f+.46f*cos(a)*sin(a/2f),.5f+.46f*sin(a)) }
    "Moon" -> ((0..20).map { i -> val a=(-70f+i*220f/20f)*PI.toFloat()/180f; VectorPoint(.5f+.48f*cos(a),.5f+.48f*sin(a)) } + (20 downTo 0).map { i -> val a=(-55f+i*190f/20f)*PI.toFloat()/180f; VectorPoint(.64f+.34f*cos(a),.5f+.42f*sin(a)) })
    "Shield" -> xy(.12f to .1f,.88f to .1f,.84f to .6f,.5f to .96f,.16f to .6f)
    "Home" -> xy(.04f to .48f,.5f to .06f,.96f to .48f,.82f to .48f,.82f to .94f,.58f to .94f,.58f to .66f,.4f to .66f,.4f to .94f,.18f to .94f,.18f to .48f)
    "Tag" -> xy(.08f to .12f,.66f to .12f,.94f to .5f,.66f to .88f,.08f to .88f)
    "Bookmark" -> xy(.22f to .06f,.78f to .06f,.78f to .94f,.5f to .72f,.22f to .94f)
    "Bracket" -> xy(.76f to .08f,.36f to .08f,.18f to .24f,.18f to .76f,.36f to .92f,.76f to .92f,.76f to .76f,.42f to .76f,.34f to .68f,.34f to .32f,.42f to .24f,.76f to .24f)
    else -> xy(.04f to .32f,.2f to .16f,.36f to .32f,.52f to .48f,.68f to .32f,.84f to .16f,.96f to .32f,.96f to .68f,.84f to .84f,.68f to .68f,.52f to .52f,.36f to .68f,.2f to .84f,.04f to .68f)
}

private val shapeNames = listOf(
    "Square","Rectangle","Circle","Ellipse","Triangle","Right triangle",
    "Diamond","Pentagon","Hexagon","Heptagon","Octagon","Nonagon","Decagon","Trapezoid","Parallelogram","Kite",
    "Star 4","Star 5","Star 6","Star 7","Star 8","Star 9","Star 10","Star 12","Burst",
    "Arrow up","Arrow down","Arrow left","Arrow right","Double arrow","Chevron","Chevron left","Chevron right",
    "Plus","Cross","X","Heart","Lightning","Speech bubble","Cloud","Gear","Flower","Leaf","Moon","Shield","Home","Tag","Bookmark","Bracket","Wave"
)
private fun category(name: String) = when (name) {
    "Square","Rectangle","Circle","Ellipse","Triangle","Right triangle" -> "Basic"
    "Diamond","Pentagon","Hexagon","Heptagon","Octagon","Nonagon","Decagon","Trapezoid","Parallelogram","Kite" -> "Polygons"
    "Star 4","Star 5","Star 6","Star 7","Star 8","Star 9","Star 10","Star 12","Burst" -> "Stars"
    "Arrow up","Arrow down","Arrow left","Arrow right","Double arrow","Chevron","Chevron left","Chevron right" -> "Arrows"
    else -> "Symbols"
}
val ShapePresets: List<ShapePreset> = shapeNames.map { ShapePreset(it,category(it),pointsFor(it)) }

@Composable
fun ShapeLibraryPanel(onClose: () -> Unit, onSelect: (ShapePreset) -> Unit, modifier: Modifier = Modifier) {
    var category by remember { mutableStateOf("Basic") }
    val categories = listOf("Basic","Polygons","Stars","Arrows","Symbols")
    val visible = remember(category) { ShapePresets.filter { it.category == category } }
    val panelShape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
    Column(modifier.clip(panelShape).background(KColors.Surface).border(1.dp,KColors.Line,panelShape)) {
        Row(Modifier.fillMaxWidth().height(38.dp).padding(start=13.dp,end=5.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("Shape Library  ·  ${ShapePresets.size} vector shapes",color=KColors.Text,fontSize=12.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
            Box(Modifier.size(32.dp).clickable(onClick=onClose),contentAlignment=Alignment.Center) { KIcon(IconKind.CLOSE,KColors.Muted,Modifier.size(16.dp)) }
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.width(54.dp).fillMaxHeight().background(KColors.Bg).padding(vertical=5.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                categories.forEach { item ->
                    val selected=category==item
                    Box(Modifier.padding(vertical=3.dp).width(46.dp).height(34.dp).clip(RoundedCornerShape(7.dp))
                        .background(if(selected) KColors.Accent.copy(alpha=.14f) else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { category=item },contentAlignment=Alignment.Center) {
                        Text(item.take(3).uppercase(),color=if(selected) KColors.Accent else KColors.Muted,fontSize=8.sp,fontWeight=FontWeight.Bold)
                    }
                }
            }
            LazyVerticalGrid(columns=GridCells.Adaptive(minSize=66.dp),modifier=Modifier.weight(1f).fillMaxHeight().padding(horizontal=7.dp,vertical=6.dp),
                verticalArrangement=Arrangement.spacedBy(5.dp),horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                items(visible,key={it.name}) { preset ->
                    Column(Modifier.height(65.dp).clip(RoundedCornerShape(8.dp)).background(KColors.Bg)
                        .border(1.dp,KColors.Line,RoundedCornerShape(8.dp)).clickable { onSelect(preset) }.padding(5.dp),
                        horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                        Canvas(Modifier.weight(1f).fillMaxWidth()) {
                            val path=Path()
                            preset.points.forEachIndexed { i,p -> val o=Offset(p.x*size.width,p.y*size.height); if(i==0) path.moveTo(o.x,o.y) else path.lineTo(o.x,o.y) }
                            path.close(); drawPath(path,KColors.Accent.copy(alpha=.88f))
                            drawPath(path,KColors.Text.copy(alpha=.8f),style=Stroke(width=1.dp.toPx(),cap=StrokeCap.Round))
                        }
                        Text(preset.name,color=KColors.Text,fontSize=8.sp,maxLines=1,textAlign=TextAlign.Center)
                    }
                }
            }
        }
    }
}
