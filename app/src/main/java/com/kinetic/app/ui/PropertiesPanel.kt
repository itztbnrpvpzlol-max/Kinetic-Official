package com.kinetic.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinetic.app.model.EditorState
import com.kinetic.app.model.Layer
import com.kinetic.app.model.LayerType
import com.kinetic.app.model.PropId
import com.kinetic.app.model.fmt
import kotlin.math.abs

@Composable
fun PropertiesList(editor: EditorState, layer: Layer, onParent: () -> Unit) {
    val byId = editor.layers.associateBy { it.id }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text("LAYER", color = KColors.Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(KColors.Surface2).border(1.dp, KColors.Line, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().height(38.dp).clickable(onClick = onParent),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Parent layer", color = KColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("Layer hierarchy", color = KColors.Muted, fontSize = 10.sp)
                }
                Text(layer.parentId?.let { byId[it]?.name } ?: "None",
                    color = KColors.Accent, fontSize = 12.sp, maxLines = 1)
            }
            if (layer.type != LayerType.AUDIO) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(KColors.Line))
                Row(Modifier.fillMaxWidth().height(42.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Link scale", color = KColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("Keep horizontal and vertical scale together", color = KColors.Muted, fontSize = 10.sp)
                    }
                    Box(
                        Modifier.clip(RoundedCornerShape(8.dp))
                            .background(if (layer.scaleLinked) KColors.Accent.copy(alpha = 0.18f) else KColors.Surface)
                            .border(1.dp, if (layer.scaleLinked) KColors.Accent.copy(alpha = 0.55f) else KColors.Line, RoundedCornerShape(8.dp))
                            .clickable { editor.toggleScaleLink(layer.id) }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(if (layer.scaleLinked) "LINKED" else "UNLINKED",
                            color = if (layer.scaleLinked) KColors.Accent else KColors.Muted,
                            fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("TRANSFORM", color = KColors.Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
            Spacer(Modifier.weight(1f))
            Text("AT ${fmt("%.2f", editor.time)}s", color = KColors.Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        if (layer.type == LayerType.AUDIO) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(KColors.Surface2)
                    .padding(16.dp)
            ) {
                Text("No transform controls", color = KColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("Audio layers use their own volume and playback controls.", color = KColors.Muted, fontSize = 11.sp)
            }
        } else {
            PropId.values().forEach { id -> PropertyControlCard(editor, layer, id) }
        }
    }
}

@Composable
private fun PropertyControlCard(editor: EditorState, layer: Layer, id: PropId) {
    val prop = layer.p(id)
    val value = prop.valueAt(editor.time)
    val selected = editor.selectedProp == id
    val atKey = prop.keys.any { abs(it.t - editor.time) <= editor.frame / 2f }
    val range = when (id) {
        PropId.OPACITY -> 0f..100f
        PropId.ROTATION -> -360f..360f
        PropId.SCALE_X, PropId.SCALE_Y -> 0f..300f
        else -> -1000f..1000f
    }
    var editing by remember(layer.id, id) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp))
            .background(if (selected) KColors.RowSel else KColors.Surface2)
            .border(1.dp, if (selected) KColors.Accent.copy(alpha = 0.45f) else KColors.Line, RoundedCornerShape(13.dp))
            .clickable { editor.selectProp(id) }
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(id.label, color = KColors.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(if (prop.animated) "KEYFRAMED" else "STATIC", color = KColors.Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            }
            Box(
                Modifier.clip(RoundedCornerShape(7.dp)).background(KColors.Surface)
                    .clickable { editor.selectProp(id) }.padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(id.format(value), color = KColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Box(Modifier.size(34.dp).clickable { editor.toggleStopwatch(layer.id, id) }, contentAlignment = Alignment.Center) {
                KIcon(IconKind.STOPWATCH, if (prop.animated) KColors.Accent else KColors.Muted, Modifier.size(17.dp))
            }
            Box(Modifier.size(30.dp).clickable { editor.toggleKeyAtTime(layer.id, id) }, contentAlignment = Alignment.Center) {
                KIcon(if (atKey) IconKind.DIAMOND_FILLED else IconKind.DIAMOND,
                    if (atKey) KColors.Accent else KColors.Muted, Modifier.size(17.dp))
            }
        }
        if (editor.settings.afterEffectsScrubbing) {
            ScrubValue(editor, layer, id, value)
        } else {
            Slider(
                value = value.coerceIn(range),
                onValueChange = {
                    if (!editing) { editor.beginEdit(); editing = true }
                    editor.setPropValue(layer.id, id, it)
                },
                onValueChangeFinished = { editing = false },
                valueRange = range
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).clickable(enabled = prop.animated) { editor.jumpKey(layer.id, id, -1) }, contentAlignment = Alignment.Center) {
                KIcon(IconKind.PREV, if (prop.animated) KColors.Muted else KColors.Dim, Modifier.size(14.dp))
            }
            Text("${prop.keys.size} keyframes", color = KColors.Muted, fontSize = 9.sp, modifier = Modifier.weight(1f))
            Box(Modifier.size(28.dp).clickable(enabled = prop.animated) { editor.jumpKey(layer.id, id, 1) }, contentAlignment = Alignment.Center) {
                KIcon(IconKind.NEXT, if (prop.animated) KColors.Muted else KColors.Dim, Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun ScrubValue(editor: EditorState, layer: Layer, id: PropId, value: Float) {
    val dens = LocalDensity.current.density
    var typing by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(8.dp))
            .background(KColors.Surface)
            .pointerInput(layer.id, id) {
                detectHorizontalDragGestures(onDragStart = { editor.beginEdit() }) { change, dx ->
                    change.consume()
                    val cur = editor.layers.firstOrNull { it.id == layer.id }?.p(id)?.valueAt(editor.time)
                    if (cur != null) editor.setPropValue(layer.id, id,
                        cur + dx / dens * id.scrubStep * editor.settings.scrubMultiplier)
                }
            }
            .clickable { typing = true },
        contentAlignment = Alignment.Center
    ) {
        Text(id.format(value), color = KColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
    if (typing) {
        TextInputDialog(
            title = id.label,
            initial = fmt("%.2f", value),
            onDone = { input ->
                input.trim().toFloatOrNull()?.let { editor.beginEdit(); editor.setPropValue(layer.id, id, it) }
                typing = false
            },
            onDismiss = { typing = false }
        )
    }
}
