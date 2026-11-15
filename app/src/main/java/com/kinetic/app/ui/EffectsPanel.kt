package com.kinetic.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinetic.app.model.EditorState
import com.kinetic.app.model.EffectInstance
import com.kinetic.app.model.Layer
import kotlin.math.abs
import kotlin.math.sqrt

private data class EffectControlSpec(
    val label: String, val key: String, val min: Float = 0f, val max: Float = 1f,
    val step: Float = 0.1f, val default: Float = 0f, val decimals: Int = 2,
    val suffix: String = "", val choices: List<Pair<String, Float>> = emptyList(),
    val toggle: Boolean = false
)
private fun number(label: String, key: String, min: Float, max: Float, step: Float, default: Float, decimals: Int = 2, suffix: String = "") =
    EffectControlSpec(label, key, min, max, step, default, decimals, suffix)
private fun choice(label: String, key: String, values: List<String>) =
    EffectControlSpec(label, key, default = 0f, choices = values.mapIndexed { i, v -> v to i.toFloat() })
private fun toggle(label: String, key: String, default: Boolean = false) =
    EffectControlSpec(label, key, default = if (default) 1f else 0f, toggle = true)

private fun controlsFor(effect: EffectInstance): List<EffectControlSpec> = when (effect.name) {
    "Motion Tile" -> listOf(
        number("Tile Width", "Tile Width", 10f, 300f, 10f, 100f, 0, "%"),
        number("Tile Height", "Tile Height", 10f, 300f, 10f, 100f, 0, "%"),
        number("Output Width", "Output Width", 100f, 500f, 25f, 200f, 0, "%"),
        number("Output Height", "Output Height", 100f, 500f, 25f, 200f, 0, "%"),
        toggle("Mirror Edges", "Mirror Edges", true),
        number("Phase X", "Phase X", -2f, 2f, 0.05f, 0f),
        number("Phase Y", "Phase Y", -2f, 2f, 0.05f, 0f)
    )
    "Wave Warp" -> listOf(
        choice("Wave Type", "Wave Type", listOf("Sine", "Square", "Triangle", "Sawtooth")),
        number("Wave Height", "Wave Height", 0f, 0.08f, 0.002f, 0.012f, 3),
        number("Wave Width", "Wave Width", 1f, 24f, 1f, 4f, 0),
        number("Direction", "Direction", 0f, 360f, 15f, 0f, 0, "°"),
        number("Wave Speed", "Wave Speed", -8f, 8f, 0.25f, 1f),
        number("Phase", "Phase", 0f, 360f, 15f, 0f, 0, "°"),
        choice("Pinning", "Pinning", listOf("None", "Top & Bottom", "Left & Right", "All Edges"))
    )
    "Warp Shake" -> listOf(
        number("X Amount", "X Amount", 0f, 0.08f, 0.002f, 0.008f, 3),
        number("Y Amount", "Y Amount", 0f, 0.08f, 0.002f, 0.008f, 3),
        number("Frequency", "Frequency", 0.1f, 30f, 0.5f, 8f, 1, " Hz"),
        choice("Motion", "Motion", listOf("Smooth", "Jitter")),
        number("Direction", "Direction", 0f, 360f, 15f, 0f, 0, "°"),
        number("Seed", "Seed", 0f, 100f, 1f, 0f, 0)
    )
    "RGB Split" -> listOf(
        number("Red Offset", "Red Offset", -0.04f, 0.04f, 0.001f, 0.006f, 3),
        number("Green Offset", "Green Offset", -0.04f, 0.04f, 0.001f, 0f, 3),
        number("Blue Offset", "Blue Offset", -0.04f, 0.04f, 0.001f, -0.006f, 3),
        number("Angle", "Angle", 0f, 360f, 5f, 0f, 0, "°"),
        toggle("Radial Split", "Radial Split"),
        number("Center X", "Center X", 0f, 1f, 0.05f, 0.5f),
        number("Center Y", "Center Y", 0f, 1f, 0.05f, 0.5f)
    )
    "Oscillate" -> listOf(
        number("X Amplitude", "X Amplitude", 0f, 0.15f, 0.005f, 0.02f, 3),
        number("Y Amplitude", "Y Amplitude", 0f, 0.15f, 0.005f, 0.02f, 3),
        number("Frequency", "Frequency", 0.1f, 12f, 0.25f, 1f, 2, " Hz"),
        choice("Wave Shape", "Wave Shape", listOf("Sine", "Triangle", "Square", "Sawtooth")),
        number("Phase", "Phase", 0f, 360f, 15f, 0f, 0, "°"),
        toggle("Half Range", "Half Range")
    )
    "Deep Glow" -> listOf(
        number("Threshold", "Threshold", 0f, 1f, 0.05f, 0.65f),
        number("Radius", "Radius", 0.001f, 0.08f, 0.002f, 0.012f, 3),
        number("Intensity", "Intensity", 0f, 4f, 0.1f, 1.2f),
        choice("Quality", "Quality", listOf("Performance", "High")),
        number("Spread", "Spread", 0.25f, 3f, 0.25f, 1f, 2, "×"),
        number("Saturation", "Saturation", 0f, 2f, 0.1f, 1f),
        toggle("Glow Only", "Glow Only")
    )
    "Exposure And Gamma" -> listOf(
        number("Exposure", "Exposure", -5f, 5f, 0.25f, 0f, 2, " stops"),
        number("Gamma", "Gamma", 0.1f, 3f, 0.05f, 1f),
        number("Offset", "Offset", -1f, 1f, 0.05f, 0f),
        choice("Channel", "Channel", listOf("Master", "Red", "Green", "Blue"))
    )
    else -> emptyList()
}
@Composable
fun EffectControlsPanel(editor: EditorState, layer: Layer, onBrowseEffects: () -> Unit) {
    var expandedEffectId by remember(layer.id, layer.effectStack.lastOrNull()?.id) {
        mutableStateOf(layer.effectStack.lastOrNull()?.id)
    }
    Column(
        Modifier.fillMaxSize().background(KColors.Surface)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text("EFFECTS", color = KColors.Text, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 1.0.sp)
                Text("Build and animate this layer's look", color = KColors.Muted, fontSize = 8.sp)
            }
            Box(
                Modifier.clip(RoundedCornerShape(20.dp))
                    .background(KColors.Accent.copy(alpha = 0.13f))
                    .border(1.dp, KColors.Accent.copy(alpha = 0.28f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text("${layer.effectStack.size} ACTIVE", color = KColors.Accent,
                    fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
            }
        }

        if (layer.effectStack.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(KColors.Bg).border(1.dp, KColors.Line, RoundedCornerShape(14.dp))
                    .padding(vertical = 22.dp, horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Text("◇", color = KColors.Accent, fontSize = 25.sp)
                Text("Your effect stack is empty", color = KColors.Text, fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold)
                Text("Add an effect to start shaping this layer.", color = KColors.Muted, fontSize = 9.sp)
            }
        }

        layer.effectStack.forEachIndexed { index, effect ->
            val expanded = expandedEffectId == effect.id
            val keyedParameters = effect.parameterKeys.values.count { it.isNotEmpty() }
            val accent = if (effect.enabled) KColors.Accent else KColors.Dim
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(KColors.Bg)
                    .border(1.dp, if (expanded) KColors.Accent.copy(alpha = 0.48f) else KColors.Line,
                        RoundedCornerShape(8.dp))
            ) {
                Row(
                    Modifier.fillMaxWidth().height(43.dp).clickable {
                        expandedEffectId = if (expanded) null else effect.id
                    }.padding(horizontal = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.width(3.dp).height(31.dp).clip(RoundedCornerShape(4.dp))
                            .background(accent)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(effect.name, color = if (effect.enabled) KColors.Text else KColors.Dim,
                            fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(
                            when {
                                !effect.enabled -> "DISABLED"
                                keyedParameters > 0 -> "$keyedParameters animated parameters"
                                else -> "EFFECT  ${index + 1}"
                            },
                            color = if (keyedParameters > 0) KColors.Accent else KColors.Muted,
                            fontSize = 8.sp, letterSpacing = 0.55.sp
                        )
                    }
                    EffectAction(if (expanded) "⌃" else "⌄", expanded) {
                        expandedEffectId = if (expanded) null else effect.id
                    }
                    EffectIconAction(if (effect.enabled) IconKind.EYE else IconKind.EYE_OFF, effect.enabled) {
                        editor.setEffectEnabled(layer.id, effect.id, !effect.enabled)
                    }
                    EffectIconAction(IconKind.DELETE, false) {
                        editor.removeEffect(layer.id, effect.id)
                        if (expandedEffectId == effect.id) expandedEffectId = null
                    }
                }
                if (expanded) {
                    Column(
                        Modifier.fillMaxWidth().padding(start = 5.dp, end = 5.dp, bottom = 5.dp, top = 1.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(KColors.Line))
                        controlsFor(effect).forEach { spec ->
                            val liveEffect = editor.layers.firstOrNull { it.id == layer.id }
                                ?.effectStack?.firstOrNull { it.id == effect.id } ?: effect
                            EffectParameterLine(editor, layer, liveEffect, spec)
                        }
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp))
                .background(KColors.Accent).clickable(onClick = onBrowseEffects)
                .padding(vertical = 13.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("+", color = androidx.compose.ui.graphics.Color.White, fontSize = 17.sp,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(7.dp))
            Text("ADD EFFECT", color = androidx.compose.ui.graphics.Color.White,
                fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        }
    }
}

@Composable
private fun EffectAction(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.padding(start = 4.dp).size(30.dp).clip(RoundedCornerShape(8.dp))
            .background(if (active) KColors.Accent.copy(alpha = 0.14f) else KColors.Surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (active) KColors.Accent else KColors.Muted,
            fontSize = if (label == "×") 18.sp else 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun EffectIconAction(kind: IconKind, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.padding(start = 4.dp).size(30.dp).clip(RoundedCornerShape(8.dp))
            .background(if (active) KColors.Accent.copy(alpha = 0.14f) else KColors.Surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        KIcon(kind, if (active) KColors.Accent else KColors.Muted, Modifier.size(15.dp))
    }
}

@Composable
private fun EffectParameterLine(editor: EditorState, layer: Layer, effect: EffectInstance, spec: EffectControlSpec) {
    val current = effect.parameterAt(spec.key, editor.time)
    val keys = effect.parameterKeys[spec.key].orEmpty()
    val keyAtTime = keys.any { abs(it.t - editor.time) <= editor.frame / 2f }
    var graphOpen by remember(effect.id, spec.key) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth()
            .background(KColors.Surface.copy(alpha = 0.32f))
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(spec.label, color = KColors.Text, fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Box(
                Modifier.clip(RoundedCornerShape(6.dp))
                    .background(if (keyAtTime) KColors.Accent.copy(alpha = 0.18f) else KColors.Bg)
                    .border(1.dp, if (keyAtTime) KColors.Accent.copy(alpha = 0.55f) else KColors.Line,
                        RoundedCornerShape(6.dp))
                    .clickable {
                        editor.toggleEffectParameterKey(layer.id, effect.id, spec.key)
                    }.padding(horizontal = 8.dp, vertical = 5.dp)
            ) {
                Text(if (keyAtTime) "◆ KEY" else "◇ KEY",
                    color = if (keyAtTime) KColors.Accent else KColors.Muted,
                    fontSize = 8.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(5.dp))
            Box(
                Modifier.clip(RoundedCornerShape(6.dp))
                    .background(if (graphOpen) KColors.Accent.copy(alpha = 0.18f) else KColors.Bg)
                    .border(1.dp, if (graphOpen) KColors.Accent.copy(alpha = 0.55f) else KColors.Line,
                        RoundedCornerShape(6.dp))
                    .clickable { graphOpen = !graphOpen }
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            ) {
                Text("GRAPH", color = if (graphOpen) KColors.Accent else KColors.Muted,
                    fontSize = 8.sp, fontWeight = FontWeight.Bold)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            when {
                spec.toggle -> Switch(checked = current >= 0.5f, onCheckedChange = {
                    editor.beginEdit()
                    editor.updateEffectParameter(layer.id, effect.id, spec.key, if (it) 1f else 0f)
                }, modifier = Modifier.size(width = 42.dp, height = 28.dp))
                spec.choices.isNotEmpty() -> {
                    var menuOpen by remember(effect.id, spec.key) { mutableStateOf(false) }
                    Box {
                        Box(
                            Modifier.clip(RoundedCornerShape(7.dp)).background(KColors.Bg)
                                .border(1.dp, KColors.Line, RoundedCornerShape(7.dp))
                                .clickable { menuOpen = true }
                                .padding(horizontal = 11.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(spec.choices.minByOrNull { abs(it.second - current) }?.first ?: "Select",
                                color = KColors.Accent, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            spec.choices.forEach { (label, value) ->
                                DropdownMenuItem(text = { Text(label, fontSize = 10.sp) }, onClick = {
                                    editor.beginEdit()
                                    editor.updateEffectParameter(layer.id, effect.id, spec.key, value)
                                    menuOpen = false
                                })
                            }
                        }
                    }
                }
                else -> {
                    val valueFormat = "%." + spec.decimals + "f" + spec.suffix
                    ScrubValueBar(
                        spec.label, current, Modifier.weight(1f), step = spec.step,
                        minValue = spec.min, maxValue = spec.max, valueFormat = valueFormat,
                        onScrubStart = { editor.beginEdit() }
                    ) { value ->
                        editor.updateEffectParameter(layer.id, effect.id, spec.key, value.coerceIn(spec.min, spec.max))
                    }
                }
            }
        }
        if (graphOpen) {
            EffectParameterGraph(editor, layer, effect, spec)
        }
    }
}

@Composable
private fun EffectParameterGraph(
    editor: EditorState, layer: Layer, effect: EffectInstance, spec: EffectControlSpec
) {
    val keys = effect.parameterKeys[spec.key].orEmpty()
    val density = LocalDensity.current.density
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(KColors.Bg).border(1.dp, KColors.Line, RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("VALUE GRAPH", color = KColors.Muted, fontSize = 8.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp,
                modifier = Modifier.weight(1f))
            Text("${keys.size} keys", color = KColors.Accent, fontSize = 8.sp)
        }
        if (keys.isEmpty()) {
            Text("Tap KEY at the current playhead to create the first point.",
                color = KColors.Muted, fontSize = 8.sp)
        } else {
            Canvas(
                Modifier.fillMaxWidth().height(112.dp)
                    .pointerInput(effect.id, spec.key, spec.min, spec.max, layer.start, layer.duration) {
                        var activeIndex = -1
                        detectDragGestures(
                            onDragStart = { offset ->
                                val graphKeys = editor.layers.firstOrNull { it.id == layer.id }
                                    ?.effectStack?.firstOrNull { it.id == effect.id }
                                    ?.parameterKeys?.get(spec.key).orEmpty()
                                val minX = 10f
                                val maxX = (size.width - 10f).coerceAtLeast(11f)
                                val minY = 10f
                                val maxY = (size.height - 10f).coerceAtLeast(11f)
                                activeIndex = graphKeys.indices.minByOrNull { index ->
                                    val key = graphKeys[index]
                                    val x = minX + ((key.t - layer.start) / layer.duration.coerceAtLeast(0.001f)).coerceIn(0f, 1f) * (maxX - minX)
                                    val y = maxY - ((key.v - spec.min) / (spec.max - spec.min).coerceAtLeast(0.0001f)).coerceIn(0f, 1f) * (maxY - minY)
                                    val dx = offset.x - x
                                    val dy = offset.y - y
                                    dx * dx + dy * dy
                                } ?: -1
                                val nearest = activeIndex.takeIf { it >= 0 }?.let { index ->
                                    val key = graphKeys[index]
                                    val x = minX + ((key.t - layer.start) / layer.duration.coerceAtLeast(0.001f)).coerceIn(0f, 1f) * (maxX - minX)
                                    val y = maxY - ((key.v - spec.min) / (spec.max - spec.min).coerceAtLeast(0.0001f)).coerceIn(0f, 1f) * (maxY - minY)
                                    sqrt((offset.x - x) * (offset.x - x) + (offset.y - y) * (offset.y - y))
                                } ?: Float.MAX_VALUE
                                if (nearest <= 28f * density) editor.beginEdit() else activeIndex = -1
                            },
                            onDragEnd = { activeIndex = -1 },
                            onDragCancel = { activeIndex = -1 }
                        ) { change, _ ->
                            if (activeIndex in keys.indices) {
                                val minY = 10f
                                val maxY = (size.height - 10f).coerceAtLeast(11f)
                                val normalized = (1f - ((change.position.y - minY) / (maxY - minY))).coerceIn(0f, 1f)
                                val key = editor.layers.firstOrNull { it.id == layer.id }
                                    ?.effectStack?.firstOrNull { it.id == effect.id }
                                    ?.parameterKeys?.get(spec.key)?.getOrNull(activeIndex) ?: keys[activeIndex]
                                val value = spec.min + normalized * (spec.max - spec.min)
                                editor.replaceEffectParameterKey(layer.id, effect.id, spec.key,
                                    activeIndex, key.copy(v = value))
                                change.consume()
                            }
                        }
                    }
            ) {
                val left = 10.dp.toPx()
                val right = (size.width - 10.dp.toPx()).coerceAtLeast(left + 1f)
                val top = 10.dp.toPx()
                val bottom = (size.height - 10.dp.toPx()).coerceAtLeast(top + 1f)
                for (row in 0..4) {
                    val y = top + (bottom - top) * row / 4f
                    drawLine(KColors.Line, Offset(left, y), Offset(right, y), 1.dp.toPx())
                }
                val playheadX = left + ((editor.time - layer.start) / layer.duration.coerceAtLeast(0.001f))
                    .coerceIn(0f, 1f) * (right - left)
                drawLine(KColors.Accent.copy(alpha = 0.45f), Offset(playheadX, top),
                    Offset(playheadX, bottom), 1.dp.toPx())
                if (keys.isNotEmpty()) {
                    val path = Path()
                    keys.forEachIndexed { index, key ->
                        val x = left + ((key.t - layer.start) / layer.duration.coerceAtLeast(0.001f))
                            .coerceIn(0f, 1f) * (right - left)
                        val y = bottom - ((key.v - spec.min) / (spec.max - spec.min).coerceAtLeast(0.0001f))
                            .coerceIn(0f, 1f) * (bottom - top)
                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, KColors.Accent, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
                    keys.forEach { key ->
                        val x = left + ((key.t - layer.start) / layer.duration.coerceAtLeast(0.001f))
                            .coerceIn(0f, 1f) * (right - left)
                        val y = bottom - ((key.v - spec.min) / (spec.max - spec.min).coerceAtLeast(0.0001f))
                            .coerceIn(0f, 1f) * (bottom - top)
                        drawCircle(KColors.Accent, radius = 5.dp.toPx(), center = Offset(x, y))
                        drawCircle(KColors.Bg, radius = 2.dp.toPx(), center = Offset(x, y))
                    }
                }
            }
            Text("Drag a point vertically to edit its value. Use KEY at the playhead to add or remove points.",
                color = KColors.Muted, fontSize = 8.sp)
        }
    }
}
