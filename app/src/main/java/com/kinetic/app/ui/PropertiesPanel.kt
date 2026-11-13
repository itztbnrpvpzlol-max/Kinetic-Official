package com.kinetic.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

@Composable
fun PropertiesList(editor: EditorState, layer: Layer, onParent: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(KColors.Surface).verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text("TRANSFORM", color = KColors.Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp,
            modifier = Modifier.padding(start = 2.dp, top = 3.dp, bottom = 5.dp))
        PropertyLine("Position", editor, layer, listOf(PropId.POS_X, PropId.POS_Y))
        if (layer.type != LayerType.AUDIO) {
            PropertyLine("Scale", editor, layer,
                if (layer.scaleLinked) listOf(PropId.SCALE_X) else listOf(PropId.SCALE_X, PropId.SCALE_Y),
                onLongPress = { editor.toggleScaleLink(layer.id) })
            PropertyLine("Rotation", editor, layer, listOf(PropId.ROTATION))
        }
        PropertyLine("Opacity", editor, layer, listOf(PropId.OPACITY))
        if (layer.type != LayerType.AUDIO) {
            Spacer(Modifier.height(9.dp))
            Text("ANCHOR & ALIGN", color = KColors.Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp,
                modifier = Modifier.padding(start = 2.dp, top = 3.dp, bottom = 5.dp))
            PropertyLine("Anchor", editor, layer, listOf(PropId.ANCHOR_X, PropId.ANCHOR_Y))
        }
        Row(Modifier.fillMaxWidth().height(42.dp).clickable(onClick = onParent).padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Parent", color = KColors.Text, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(layer.parentId?.let { id -> editor.layers.firstOrNull { it.id == id }?.name } ?: "None",
                color = KColors.Muted, fontSize = 11.sp)
            Spacer(Modifier.width(7.dp))
            KIcon(IconKind.CHEVRON_DOWN, KColors.Muted, Modifier.size(14.dp))
        }
    }
}

@Composable
private fun PropertyLine(label: String, editor: EditorState, layer: Layer, ids: List<PropId>, onLongPress: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().height(43.dp).padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = if (ids.any { editor.selectedProp == it }) KColors.Accent else KColors.Text,
            fontSize = 12.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f).pointerInput(layer.id, layer.scaleLinked, ids) {
                detectTapGestures(
                    onTap = { ids.firstOrNull()?.let { editor.selectProp(it) } },
                    onLongPress = { onLongPress?.invoke() }
                )
            })
        ids.forEachIndexed { index, id ->
            if (ids.size > 1 && index > 0) Spacer(Modifier.width(5.dp))
            ValueController(editor, layer, id, if (ids.size > 1) if (index == 0) "X" else "Y" else "", Modifier.weight(1f))
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(KColors.Line.copy(alpha = 0.55f)))
}

@Composable
private fun ValueController(editor: EditorState, layer: Layer, id: PropId, axis: String, modifier: Modifier = Modifier) {
    val prop = layer.p(id)
    val value = prop.valueAt(editor.time)
    val density = LocalDensity.current.density
    var typing by remember(layer.id, id) { mutableStateOf(false) }
    Box(modifier.height(30.dp).clip(RoundedCornerShape(6.dp))
        .background(if (editor.selectedProp == id) KColors.Accent.copy(alpha = 0.12f) else KColors.Bg)
        .border(1.dp, if (editor.selectedProp == id) KColors.Accent.copy(alpha = 0.65f) else KColors.Line, RoundedCornerShape(6.dp))
        .clickable { editor.selectProp(id); typing = true }
        .pointerInput(layer.id, id) {
            detectHorizontalDragGestures(onDragStart = { editor.selectProp(id); editor.beginEdit() }) { change, drag ->
                change.consume()
                val current = editor.layers.firstOrNull { it.id == layer.id }?.p(id)?.valueAt(editor.time) ?: value
                editor.setPropValue(layer.id, id, current + drag / density * id.scrubStep)
            }
        }, contentAlignment = Alignment.Center) {
        Text((if (axis.isNotEmpty()) "$axis  " else "") + id.format(value),
            color = if (prop.animated) KColors.Accent else KColors.Text, fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
    if (typing) TextInputDialog(title = id.label, initial = fmt("%.2f", value), onDone = { input ->
        input.trim().toFloatOrNull()?.let { editor.beginEdit(); editor.setPropValue(layer.id, id, it) }
        typing = false
    }, onDismiss = { typing = false })
}
