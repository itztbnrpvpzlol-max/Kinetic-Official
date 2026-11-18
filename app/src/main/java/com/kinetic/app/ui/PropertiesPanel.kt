package com.kinetic.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
        if (layer.type == LayerType.SHAPE || layer.type == LayerType.TEXT) {
            LayerAppearanceControls(editor, layer)
            Spacer(Modifier.height(8.dp))
        }
        if (layer.type == LayerType.SHAPE) {
            var showVectorPoints by remember(layer.id) { mutableStateOf(false) }
            val points = layer.vectorPoints.ifEmpty {
                listOf(com.kinetic.app.model.VectorPoint(0f, 0f), com.kinetic.app.model.VectorPoint(1f, 0f),
                    com.kinetic.app.model.VectorPoint(1f, 1f), com.kinetic.app.model.VectorPoint(0f, 1f))
            }
            Row(Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(6.dp))
                .background(KColors.Bg).clickable { showVectorPoints = !showVectorPoints }
                .padding(horizontal = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("VECTOR PATH  ·  ${layer.shapeKind.uppercase()}", color = KColors.Text, fontSize = 9.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(if (showVectorPoints) "Hide points" else "Edit ${points.size} points",
                    color = KColors.Accent, fontSize = 9.sp)
            }
            if (showVectorPoints) points.forEachIndexed { index, point -> VectorPointLine(editor, layer, index, point) }
            Spacer(Modifier.height(8.dp))
        }
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
        Row(Modifier.fillMaxWidth().height(34.dp).clickable(onClick = onParent).padding(horizontal = 2.dp),
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
fun PropertyLine(label: String, editor: EditorState, layer: Layer, ids: List<PropId>, onLongPress: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().height(35.dp).padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).pointerInput(layer.id, layer.scaleLinked, layer.positionLinked, ids) {
            detectTapGestures(
                onTap = { ids.firstOrNull()?.let { editor.selectProp(it) } },
                onLongPress = { onLongPress?.invoke() }
            )
        }, verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = if (ids.any { editor.selectedProp == it }) KColors.Accent else KColors.Text,
                fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
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
                editor.setPropValue(layer.id, id, current + drag / density * id.scrubStep * 4f)
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


@Composable
private fun VectorPointLine(editor: EditorState, layer: Layer, index: Int, point: com.kinetic.app.model.VectorPoint) {
    var xEditing by remember(layer.id, index) { mutableStateOf(false) }
    var yEditing by remember(layer.id, index) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("Point ${index + 1}", color = KColors.Text, fontSize = 9.sp, fontWeight = FontWeight.Medium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            val xId = com.kinetic.app.model.PropId.vectorPoint(index, 'X')
            val yId = com.kinetic.app.model.PropId.vectorPoint(index, 'Y')
            val xValue = xId?.let { layer.p(it).valueAt(editor.time) } ?: point.x
            val yValue = yId?.let { layer.p(it).valueAt(editor.time) } ?: point.y
            ScrubValueBar("X", xValue, Modifier.weight(1f), step = 0.004f,
                selected = editor.selectedProp == xId,
                onSelect = { xId?.let(editor::selectProp) }) {
                if (!xEditing) { editor.beginEdit(); xEditing = true }
                editor.updateShapePoint(layer.id, index, x = it)
            }
            Spacer(Modifier.width(5.dp))
            ScrubValueBar("Y", yValue, Modifier.weight(1f), step = 0.004f,
                selected = editor.selectedProp == yId,
                onSelect = { yId?.let(editor::selectProp) }) {
                if (!yEditing) { editor.beginEdit(); yEditing = true }
                editor.updateShapePoint(layer.id, index, y = it)
            }
        }
    }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ScrubValueBar(
    label: String, value: Float, modifier: Modifier = Modifier, step: Float = 0.5f,
    snapStep: Float? = null,
    selected: Boolean = false, onSelect: () -> Unit = {}, minValue: Float = 0f, maxValue: Float = 1f,
    valueFormat: String? = null, onScrubStart: () -> Unit = {}, onValueChange: (Float) -> Unit
) {
    val density = LocalDensity.current.density
    val latestValue by rememberUpdatedState(value)
    val latestChange by rememberUpdatedState(onValueChange)
    var editingValue by remember(label) { mutableStateOf(false) }
    Box(
        modifier.height(30.dp).clip(RoundedCornerShape(6.dp))
            .background(if (selected) KColors.Accent.copy(alpha = 0.14f) else KColors.Bg)
            .border(1.dp, if (selected) KColors.Accent.copy(alpha = 0.75f) else KColors.Line, RoundedCornerShape(6.dp))
            .combinedClickable(
                onClick = onSelect,
                onLongClick = { onSelect(); onScrubStart(); editingValue = true }
            )
            .pointerInput(step, snapStep) {
                var dragTotal = 0f
                var dragStartValue = latestValue
                detectHorizontalDragGestures(
                    onDragStart = { dragStartValue = latestValue; dragTotal = 0f; onScrubStart() }
                ) { change, drag ->
                    change.consume()
                    dragTotal += drag / density
                    val raw = dragStartValue + dragTotal * step * 4f
                    val increment = snapStep?.takeIf { it.isFinite() && it > 0f }
                    val next = if (increment != null) kotlin.math.round(raw / increment) * increment else raw
                    latestChange(next.coerceIn(minValue, maxValue))
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text("$label  " + (valueFormat?.let { String.format(java.util.Locale.US, it, value) } ?: "${(value * 100f).toInt()}%"), color = if (selected) KColors.Accent else KColors.Text, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
    if (editingValue) {
        TextInputDialog(
            title = label.ifBlank { "Value" },
            initial = fmt("%.4f", value),
            onDone = { input ->
                input.trim().toFloatOrNull()?.takeIf { it.isFinite() }?.let { latestChange(it.coerceIn(minValue, maxValue)) }
                editingValue = false
            },
            onDismiss = { editingValue = false }
        )
    }
}

@Composable
private fun LayerAppearanceControls(editor: EditorState, layer: Layer) {
    var editingText by remember(layer.id) { mutableStateOf(false) }
    val palette = listOf(
        0xFFFFFFFF.toInt(), 0xFF111111.toInt(), 0xFFFF5252.toInt(),
        0xFFFFB300.toInt(), 0xFFFFEB3B.toInt(), 0xFF69F0AE.toInt(),
        0xFF40C4FF.toInt(), 0xFF448AFF.toInt(), 0xFFB388FF.toInt(),
        0xFFFF80AB.toInt(), 0xFF8D6E63.toInt(), 0xFF9E9E9E.toInt()
    )
    Column(Modifier.fillMaxWidth()) {
        Text(if (layer.type == LayerType.TEXT) "TEXT & TYPOGRAPHY" else "FILL & STROKE",
            color = KColors.Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 1.1.sp, modifier = Modifier.padding(start = 2.dp, top = 3.dp, bottom = 5.dp))
        if (layer.type == LayerType.TEXT) {
            Row(Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(6.dp))
                .background(KColors.Bg).clickable { editingText = true }.padding(horizontal = 9.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Edit text content", color = KColors.Text, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text(layer.text.take(20), color = KColors.Muted, fontSize = 10.sp, maxLines = 1)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Font size", color = KColors.Text, fontSize = 11.sp, modifier = Modifier.weight(1f))
                ScrubValueBar("Size", layer.fontSize, Modifier.weight(1.2f), step = 2f,
                    minValue = 4f, maxValue = 600f, valueFormat = "%.0f",
                    onScrubStart = { editor.beginEdit() }) { value ->
                    editor.updateLayer(layer.id, push = false) { it.copy(fontSize = value.coerceAtLeast(4f)) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("Sans Serif", "Serif", "Monospace").forEach { family ->
                    AppearanceChoice(family, layer.fontFamily == family) {
                        editor.updateLayer(layer.id) { it.copy(fontFamily = family) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                AppearanceChoice("Bold", layer.textBold) {
                    editor.updateLayer(layer.id) { it.copy(textBold = !it.textBold) }
                }
                AppearanceChoice("Italic", layer.textItalic) {
                    editor.updateLayer(layer.id) { it.copy(textItalic = !it.textItalic) }
                }
                listOf("Left", "Center", "Right").forEach { alignment ->
                    AppearanceChoice(alignment, layer.textAlign == alignment) {
                        editor.updateLayer(layer.id) { it.copy(textAlign = alignment) }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Fill", color = KColors.Text, fontSize = 11.sp, modifier = Modifier.weight(1f))
            AppearanceChoice("On", layer.fillEnabled) {
                editor.updateLayer(layer.id) { it.copy(fillEnabled = true) }
            }
            AppearanceChoice("Off", !layer.fillEnabled) {
                editor.updateLayer(layer.id) { it.copy(fillEnabled = false) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            palette.forEach { color ->
                Box(Modifier.weight(1f).height(20.dp).clip(RoundedCornerShape(4.dp))
                    .background(Color(color))
                    .border(if (layer.color == color) 2.dp else 1.dp,
                        if (layer.color == color) KColors.Accent else KColors.Line, RoundedCornerShape(4.dp))
                    .clickable { editor.updateLayer(layer.id) { it.copy(color = color) } })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Stroke", color = KColors.Text, fontSize = 11.sp, modifier = Modifier.weight(1f))
            AppearanceChoice("On", layer.strokeEnabled) {
                editor.updateLayer(layer.id) { it.copy(strokeEnabled = true, strokeWidth = it.strokeWidth.coerceAtLeast(1f)) }
            }
            AppearanceChoice("Off", !layer.strokeEnabled) {
                editor.updateLayer(layer.id) { it.copy(strokeEnabled = false) }
            }
        }
        if (layer.strokeEnabled) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                palette.forEach { color ->
                    Box(Modifier.weight(1f).height(18.dp).clip(RoundedCornerShape(4.dp))
                        .background(Color(color))
                        .border(if (layer.strokeColor == color) 2.dp else 1.dp,
                            if (layer.strokeColor == color) KColors.Accent else KColors.Line, RoundedCornerShape(4.dp))
                        .clickable { editor.updateLayer(layer.id) { it.copy(strokeColor = color) } })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Stroke width", color = KColors.Text, fontSize = 10.sp, modifier = Modifier.weight(1f))
                ScrubValueBar("Width", layer.strokeWidth, Modifier.weight(1.2f), step = 0.5f,
                    minValue = 0f, maxValue = 80f, valueFormat = "%.1f",
                    onScrubStart = { editor.beginEdit() }) { value ->
                    editor.updateLayer(layer.id, push = false) { it.copy(strokeWidth = value) }
                }
            }
        }
    }
    if (editingText) {
        TextInputDialog(title = "Edit text", initial = layer.text, onDone = { value ->
            editor.updateLayer(layer.id) { it.copy(text = value) }
            editingText = false
        }, onDismiss = { editingText = false })
    }
}

@Composable
private fun AppearanceChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.padding(end = 2.dp).height(26.dp).clip(RoundedCornerShape(6.dp))
        .background(if (selected) KColors.Accent.copy(alpha = 0.14f) else KColors.Bg)
        .border(1.dp, if (selected) KColors.Accent.copy(alpha = 0.65f) else KColors.Line, RoundedCornerShape(6.dp))
        .clickable(onClick = onClick).padding(horizontal = 7.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (selected) KColors.Accent else KColors.Text,
            fontSize = 8.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
