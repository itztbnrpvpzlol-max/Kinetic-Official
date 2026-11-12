package com.kinetic.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.kinetic.app.model.EditorState
import com.kinetic.app.model.Layer
import com.kinetic.app.model.LayerType
import com.kinetic.app.model.PropId
import com.kinetic.app.model.fmt
import kotlin.math.abs

@Composable
fun PropertiesList(editor: EditorState, layer: Layer, onParent: () -> Unit) {
    var tab by remember(layer.id) { mutableStateOf(0) }
    Column(Modifier.fillMaxSize().background(Color(0xFF080809))) {
        Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            PropertyTab("Attributes", tab == 0) { tab = 0 }
            PropertyTab("Anchor & Align", tab == 1) { tab = 1 }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (tab == 0) {
                PropertyGroup("Position") {
                    AxisValue(editor, layer, PropId.POS_X, "X")
                    AxisValue(editor, layer, PropId.POS_Y, "Y")
                }
                if (layer.type != LayerType.AUDIO) {
                    PropertyGroup("Scale (%)") {
                        AxisValue(editor, layer, PropId.SCALE_X, "X")
                        AxisValue(editor, layer, PropId.SCALE_Y, "Y")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            SmallAction(if (layer.scaleLinked) "LINKED" else "LINK", layer.scaleLinked) { editor.toggleScaleLink(layer.id) }
                        }
                    }
                    PropertyGroup("Rotation") { AxisValue(editor, layer, PropId.ROTATION, "Z") }
                }
                PropertyGroup("Opacity (%)") { AxisValue(editor, layer, PropId.OPACITY, "") }
            } else {
                if (layer.type != LayerType.AUDIO) {
                    PropertyGroup("Anchor Point") {
                        AxisValue(editor, layer, PropId.ANCHOR_X, "X")
                        AxisValue(editor, layer, PropId.ANCHOR_Y, "Y")
                    }
                }
                PropertyGroup("Layer alignment") {
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Parent layer", color = KColors.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text("Attach this layer to another layer", color = KColors.Muted, fontSize = 9.sp)
                        }
                        SmallAction(layer.parentId?.let { id -> editor.layers.firstOrNull { it.id == id }?.name } ?: "None", false, onParent)
                    }
                }
            }
        }
    }
}

@Composable
private fun PropertyTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(160.dp).fillMaxSize().clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(label, color = if (selected) Color(0xFF36C96B) else KColors.Muted, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        Spacer(Modifier.height(7.dp))
        Box(Modifier.fillMaxWidth().height(2.dp).background(if (selected) Color(0xFF36C96B) else Color.Transparent))
    }
}

@Composable
private fun PropertyGroup(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(Color(0xFF222224))
        .border(1.dp, Color(0xFF29292D), RoundedCornerShape(11.dp)).padding(horizontal = 10.dp, vertical = 7.dp)) {
        Text(title, color = KColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 3.dp))
        content()
    }
}

@Composable
private fun AxisValue(editor: EditorState, layer: Layer, id: PropId, axis: String) {
    val prop = layer.p(id)
    val value = prop.valueAt(editor.time)
    var typing by remember(layer.id, id) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(47.dp), verticalAlignment = Alignment.CenterVertically) {
        if (axis.isNotEmpty()) Box(Modifier.width(22.dp), contentAlignment = Alignment.CenterStart) {
            Text(axis, color = KColors.Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f)) { ValueRuler(editor, layer, id, value) }
        Box(Modifier.width(70.dp).height(35.dp).clip(RoundedCornerShape(7.dp)).background(Color(0xFF09090A))
            .border(1.dp, Color(0xFF151518), RoundedCornerShape(7.dp)).clickable { typing = true }, contentAlignment = Alignment.Center) {
            Text(id.format(value), color = if (prop.animated) Color(0xFF36C96B) else Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
        Box(Modifier.size(30.dp).clickable { editor.toggleKeyAtTime(layer.id, id) }, contentAlignment = Alignment.Center) {
            KIcon(if (prop.keys.any { abs(it.t - editor.time) <= editor.frame / 2f }) IconKind.DIAMOND_FILLED else IconKind.DIAMOND,
                if (prop.animated) KColors.Accent else KColors.Muted, Modifier.size(15.dp))
        }
    }
    if (typing) TextInputDialog(title = id.label, initial = fmt("%.2f", value), onDone = { input ->
        input.trim().toFloatOrNull()?.let { editor.beginEdit(); editor.setPropValue(layer.id, id, it) }
        typing = false
    }, onDismiss = { typing = false })
}

@Composable
private fun ValueRuler(editor: EditorState, layer: Layer, id: PropId, value: Float) {
    val density = LocalDensity.current.density
    Canvas(Modifier.fillMaxWidth().height(38.dp).pointerInput(layer.id, id) {
        detectHorizontalDragGestures(onDragStart = { editor.beginEdit() }) { change, drag ->
            change.consume()
            val step = when (id) {
                PropId.POS_X, PropId.POS_Y, PropId.ANCHOR_X, PropId.ANCHOR_Y -> 1.5f
                PropId.ROTATION -> 0.5f
                else -> 0.35f
            }
            val current = editor.layers.firstOrNull { it.id == layer.id }?.p(id)?.valueAt(editor.time) ?: value
            editor.setPropValue(layer.id, id, current + drag / density * step)
        }
    }) {
        val center = size.width * 0.5f
        val baseline = size.height - 7.dp.toPx()
        val minor = Color(0xFF48484D)
        for (i in -18..18) {
            val x = center + i * 5.dp.toPx()
            val major = i % 5 == 0
            drawLine(minor, Offset(x, baseline - if (major) 13.dp.toPx() else 7.dp.toPx()), Offset(x, baseline), if (major) 1.3.dp.toPx() else 0.8.dp.toPx())
        }
        drawLine(Color(0xFF36C96B), Offset(center, 3.dp.toPx()), Offset(center, baseline + 1.dp.toPx()), 2.dp.toPx())
        drawCircle(Color(0xFF36C96B), 3.dp.toPx(), Offset(center, 4.dp.toPx()))
    }
}

@Composable
private fun SmallAction(label: String, active: Boolean, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(if (active) KColors.Accent.copy(alpha = 0.18f) else Color(0xFF0A0A0B))
        .border(1.dp, if (active) KColors.Accent else KColors.Line, RoundedCornerShape(6.dp)).clickable(onClick = onClick)
        .padding(horizontal = 9.dp, vertical = 7.dp)) {
        Text(label, color = if (active) KColors.Accent else KColors.Text, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}
