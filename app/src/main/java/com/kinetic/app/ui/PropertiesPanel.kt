package com.kinetic.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
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
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth().height(36.dp).clickable(onClick = onParent).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Parent", color = KColors.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(
                layer.parentId?.let { byId[it]?.name } ?: "None",
                color = KColors.Accent, fontSize = 13.sp, maxLines = 1
            )
        }
        if (layer.type != LayerType.AUDIO) {
            Row(Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Link Scale X / Y", color = KColors.Text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (layer.scaleLinked) KColors.Accent.copy(alpha = 0.22f) else KColors.Surface2).clickable { editor.toggleScaleLink(layer.id) }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(if (layer.scaleLinked) "LINKED" else "UNLINKED", color = if (layer.scaleLinked) KColors.Accent else KColors.Muted, fontSize = 10.sp)
                }
            }
        }
        if (layer.type == LayerType.AUDIO) {
            Text(
                "Audio layers have no transform properties yet.",
                color = KColors.Dim, fontSize = 12.sp, modifier = Modifier.padding(14.dp)
            )
        } else {
            for (id in PropId.values()) PropRow(editor, layer, id)
        }
    }
}

@Composable
private fun PropRow(editor: EditorState, layer: Layer, id: PropId) {
    val p = layer.p(id)
    val value = p.valueAt(editor.time)
    val isSel = editor.selectedProp == id
    val atKey = p.keys.any { abs(it.t - editor.time) <= editor.frame / 2f }
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(if (isSel) KColors.RowSel else Color.Transparent)
            .clickable { editor.selectProp(id) }
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(34.dp).clickable { editor.toggleStopwatch(layer.id, id) },
            contentAlignment = Alignment.Center
        ) { KIcon(IconKind.STOPWATCH, if (p.animated) KColors.Accent else KColors.Dim) }
        Text(id.label, color = KColors.Text, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1)
        ScrubValue(editor, layer, id, value)
        Box(
            Modifier.size(30.dp).clickable(enabled = p.animated) { editor.jumpKey(layer.id, id, -1) },
            contentAlignment = Alignment.Center
        ) { KIcon(IconKind.PREV, if (p.animated) KColors.Muted else KColors.Dim, Modifier.size(16.dp)) }
        Box(
            Modifier.size(30.dp).clickable { editor.toggleKeyAtTime(layer.id, id) },
            contentAlignment = Alignment.Center
        ) {
            KIcon(
                if (atKey) IconKind.DIAMOND_FILLED else IconKind.DIAMOND,
                if (atKey) KColors.Accent else KColors.Muted, Modifier.size(18.dp)
            )
        }
        Box(
            Modifier.size(30.dp).clickable(enabled = p.animated) { editor.jumpKey(layer.id, id, 1) },
            contentAlignment = Alignment.Center
        ) { KIcon(IconKind.NEXT, if (p.animated) KColors.Muted else KColors.Dim, Modifier.size(16.dp)) }
    }
}

/** After Effects style scrubby value: drag left/right to change, tap to type. */
@Composable
private fun ScrubValue(editor: EditorState, layer: Layer, id: PropId, value: Float) {
    val dens = LocalDensity.current.density
    var typing by remember { mutableStateOf(false) }
    Box(
        Modifier
            .width(94.dp)
            .height(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(KColors.Surface2)
            .pointerInput(layer.id, id) {
                detectHorizontalDragGestures(onDragStart = { editor.beginEdit() }) { change, dx ->
                    change.consume()
                    val cur = editor.layers.firstOrNull { it.id == layer.id }?.p(id)?.valueAt(editor.time)
                    if (cur != null) {
                        editor.setPropValue(layer.id, id, cur + dx / dens * id.scrubStep * editor.settings.scrubMultiplier)
                    }
                }
            }
            .pointerInput(layer.id, id) { detectTapGestures { typing = true } },
        contentAlignment = Alignment.Center
    ) { Text(id.format(value), color = KColors.Accent, fontSize = 12.sp, maxLines = 1) }

    if (typing) {
        TextInputDialog(
            title = id.label, initial = fmt("%.2f", value),
            onDone = { s ->
                s.trim().toFloatOrNull()?.let { editor.beginEdit(); editor.setPropValue(layer.id, id, it) }
                typing = false
            },
            onDismiss = { typing = false }
        )
    }
}
