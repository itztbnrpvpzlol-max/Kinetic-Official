package com.kinetic.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.SwitchDefaults
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinetic.app.engine.EcKind
import com.kinetic.app.engine.EcParam
import com.kinetic.app.engine.EffectCatalog
import com.kinetic.app.model.EditorState
import com.kinetic.app.model.EffectInstance
import com.kinetic.app.model.Layer
import kotlin.math.abs

internal data class EffectControlSpec(
    val label: String, val key: String, val min: Float = 0f, val max: Float = 1f,
    val step: Float = 0.1f, val default: Float = 0f, val decimals: Int = 2,
    val suffix: String = "", val choices: List<Pair<String, Float>> = emptyList(),
    val toggle: Boolean = false,
    /** Display multiplier: min, max, step and values stay in stored units; the bar shows value * scale. */
    val scale: Float = 1f
)

private fun niceStep(range: Float, decimals: Int): Float {
    val floor = Math.pow(10.0, -decimals.toDouble()).toFloat()
    val raw = (range / 200f).coerceAtLeast(floor)
    // Round to 1, 2 or 5 times a power of ten so snapping lands on friendly values.
    val exp = Math.floor(Math.log10(raw.toDouble())).toInt()
    val base = Math.pow(10.0, exp.toDouble()).toFloat()
    val f = raw / base
    val snapped = when { f <= 1.5f -> 1f; f <= 3.5f -> 2f; f <= 7.5f -> 5f; else -> 10f }
    return (snapped * base).coerceAtLeast(floor)
}

/** Controls for one EffectCraft parameter: one row per animatable component. */
private fun controlsForParam(p: EcParam): List<EffectControlSpec> = when (p.kind) {
    EcKind.CHECKBOX -> listOf(EffectControlSpec(p.name, p.id, default = if ((p.defaults.firstOrNull() ?: 0f) >= 0.5f) 1f else 0f, toggle = true))
    EcKind.POPUP -> listOf(EffectControlSpec(p.name, p.id, default = p.defaults.firstOrNull() ?: 0f,
        choices = p.options.mapIndexed { i, label -> label to i.toFloat() }))
    EcKind.POINT, EcKind.POINT3 -> p.components.map { c ->
        EffectControlSpec(p.name + c.suffix, c.key, p.min, p.max, 0.01f, c.default, 1, "%", scale = 100f)
    }
    EcKind.COLOR -> p.components.map { c ->
        EffectControlSpec(p.name + c.suffix, c.key, 0f, 1f, 0.01f, c.default, 2)
    }
    EcKind.PERCENT -> listOf(EffectControlSpec(p.name, p.id, p.min, p.max, niceStep(p.max - p.min, p.decimals), p.defaults.firstOrNull() ?: 0f, p.decimals, "%"))
    EcKind.ANGLE -> listOf(EffectControlSpec(p.name, p.id, p.min, p.max, niceStep(p.max - p.min, p.decimals), p.defaults.firstOrNull() ?: 0f, p.decimals, "°"))
    EcKind.PIXELS -> listOf(EffectControlSpec(p.name, p.id, p.min, p.max, niceStep(p.max - p.min, p.decimals), p.defaults.firstOrNull() ?: 0f, p.decimals, " px"))
    EcKind.NUMBER, EcKind.SLIDER, EcKind.MASK -> listOf(EffectControlSpec(p.name, p.id, p.min, p.max, niceStep(p.max - p.min, p.decimals), p.defaults.firstOrNull() ?: 0f, p.decimals))
    // Layer references, paths, gradients, text and files have no numeric control yet; the
    // engine keeps their defaults.
    else -> emptyList()
}

/** Controls for [effect], generated from the EffectCraft parameter definitions. */
internal fun controlsFor(effect: EffectInstance): List<EffectControlSpec> {
    val spec = EffectCatalog.find(effect.effectId) ?: return emptyList()
    return spec.params.flatMap { controlsForParam(it) }
}

@Composable
fun EffectControlsPanel(editor: EditorState, layer: Layer, onBrowseEffects: () -> Unit) {
    var expandedEffectId by remember(layer.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(layer.id) { editor.clearEffectParameterSelection() }
    Column(
        Modifier.fillMaxSize().background(KColors.Surface)
            .verticalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        layer.effectStack.forEachIndexed { index, effect ->
            if (expandedEffectId == null || expandedEffectId == effect.id) key(effect.id) {
                val expanded = expandedEffectId == effect.id
                val toggleExpanded = {
                    if (!expanded && editor.selectedEffectId != effect.id) editor.clearEffectParameterSelection()
                    expandedEffectId = if (expanded) null else effect.id
                }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(KColors.Surface2)) {
                    Row(
                        Modifier.fillMaxWidth().height(44.dp).clickable(onClick = toggleExpanded).padding(start = 2.dp, end = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.width(30.dp).fillMaxHeight().pointerInput(effect.id) {
                                var acc = 0f
                                val th = 44.dp.toPx()
                                detectVerticalDragGestures(
                                    onDragStart = { acc = 0f }, onDragEnd = { acc = 0f }, onDragCancel = { acc = 0f }
                                ) { change, d ->
                                    change.consume()
                                    acc += d
                                    if (acc > th) { editor.moveEffect(layer.id, effect.id, 1); acc -= th }
                                    else if (acc < -th) { editor.moveEffect(layer.id, effect.id, -1); acc += th }
                                }
                            },
                            contentAlignment = Alignment.Center
                        ) { KIcon(IconKind.HANDLE, KColors.Dim, Modifier.size(15.dp)) }
                        Text(effect.name, color = if (effect.enabled) KColors.Text else KColors.Dim,
                            fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                            modifier = Modifier.weight(1f).padding(start = 4.dp))
                        CardIcon(if (effect.enabled) IconKind.EYE else IconKind.EYE_OFF,
                            if (effect.enabled) KColors.Accent else KColors.Dim) {
                            editor.setEffectEnabled(layer.id, effect.id, !effect.enabled)
                        }
                        CardIcon(if (expanded) IconKind.CHEVRON_UP else IconKind.CHEVRON_DOWN,
                            if (expanded) KColors.Accent else KColors.Muted, toggleExpanded)
                        CardIcon(IconKind.DELETE, KColors.Danger) {
                            editor.removeEffect(layer.id, effect.id)
                            if (expandedEffectId == effect.id) expandedEffectId = null
                        }
                        EffectMenu(editor, layer, effect, index, layer.effectStack.size)
                    }
                    if (expanded) {
                        val liveEffect = editor.layers.firstOrNull { it.id == layer.id }
                            ?.effectStack?.firstOrNull { it.id == effect.id } ?: effect
                        Column(
                            Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            controlsFor(effect).forEach { spec ->
                                val selected = editor.selectedEffectId == effect.id &&
                                    editor.selectedEffectParameter == spec.key
                                EffectParameterLine(editor, layer, liveEffect, spec, selected) {
                                    editor.selectEffectParameter(layer.id, effect.id, spec.key)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (expandedEffectId == null) Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .background(KColors.Accent.copy(alpha = 0.55f)).padding(bottom = 2.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(10.dp))
                    .background(KColors.Accent).clickable(onClick = onBrowseEffects),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
            ) {
                KIcon(IconKind.PLUS, Color.White, Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add Effect", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun CardIcon(kind: IconKind, tint: Color, onClick: () -> Unit) {
    Box(Modifier.size(34.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        KIcon(kind, tint, Modifier.size(17.dp))
    }
}

@Composable
private fun MenuRow(label: String, icon: IconKind, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, color = if (enabled) KColors.Text else KColors.Dim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
        leadingIcon = { KIcon(icon, if (enabled) KColors.Muted else KColors.Dim, Modifier.size(16.dp)) },
        enabled = enabled, onClick = onClick
    )
}

@Composable
private fun EffectMenu(editor: EditorState, layer: Layer, effect: EffectInstance, index: Int, count: Int) {
    var open by remember(effect.id) { mutableStateOf(false) }
    val hasClip = editor.effectClipboard.isNotEmpty()
    Box {
        CardIcon(IconKind.MORE_V, KColors.Muted) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(KColors.Surface2)) {
            MenuRow("Copy Effect", IconKind.COPY) { editor.copyEffects(layer.id, effect.id); open = false }
            MenuRow("Duplicate", IconKind.DUPLICATE) { editor.duplicateEffect(layer.id, effect.id); open = false }
            MenuRow(if (hasClip) "Paste After" else "No effect copied", IconKind.PASTE, hasClip) {
                editor.pasteEffects(layer.id, effect.id); open = false
            }
            MenuRow("Move Up", IconKind.CHEVRON_UP, index > 0) { editor.moveEffect(layer.id, effect.id, -1); open = false }
            MenuRow("Move Down", IconKind.CHEVRON_DOWN, index < count - 1) { editor.moveEffect(layer.id, effect.id, 1); open = false }
            MenuRow("Reset", IconKind.UNDO) { editor.resetEffect(layer.id, effect.id); open = false }
        }
    }
}

@Composable
internal fun EffectRailMenu(editor: EditorState, layer: Layer) {
    var open by remember(layer.id) { mutableStateOf(false) }
    val hasClip = editor.effectClipboard.isNotEmpty()
    Box {
        Box(Modifier.padding(vertical = 2.dp).size(34.dp).clip(RoundedCornerShape(7.dp)).clickable { open = true },
            contentAlignment = Alignment.Center) { KIcon(IconKind.MORE_V, KColors.Muted, Modifier.size(18.dp)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(KColors.Surface2)) {
            MenuRow("Copy All Effects", IconKind.COPY, layer.effectStack.isNotEmpty()) { editor.copyEffects(layer.id); open = false }
            MenuRow(if (hasClip) "Paste Effect" else "No effect copied", IconKind.PASTE, hasClip) {
                editor.pasteEffects(layer.id); open = false
            }
        }
    }
}

@Composable
private fun EffectParameterLine(
    editor: EditorState, layer: Layer, effect: EffectInstance, spec: EffectControlSpec,
    selected: Boolean, onSelect: () -> Unit
) {
    val current by remember(effect, spec) { derivedStateOf { effect.parameterAt(spec.key, editor.time, spec.default) } }
    val animated = effect.parameterKeys[spec.key].orEmpty().isNotEmpty()
    val rowShape = RoundedCornerShape(6.dp)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.weight(1f)) {
            when {
                spec.toggle -> Row(
                    Modifier.fillMaxWidth().height(36.dp).clip(rowShape)
                        .background(if (selected) KColors.Accent.copy(alpha = 0.14f) else KColors.Bg)
                        .border(1.dp, if (selected) KColors.Accent.copy(alpha = 0.75f) else KColors.Line, rowShape)
                        .clickable(onClick = onSelect).padding(start = 10.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(spec.label, color = if (selected) KColors.Accent else KColors.Text, fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Switch(checked = current >= 0.5f, onCheckedChange = {
                        onSelect()
                        editor.beginEdit()
                        editor.updateEffectParameter(layer.id, effect.id, spec.key, if (it) 1f else 0f)
                    }, modifier = Modifier.size(width = 42.dp, height = 28.dp), colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White, checkedTrackColor = KColors.Accent,
                        uncheckedThumbColor = KColors.Muted, uncheckedTrackColor = KColors.Surface2,
                        uncheckedBorderColor = KColors.Dim))
                }
                spec.choices.isNotEmpty() -> {
                    var menuOpen by remember(effect.id, spec.key) { mutableStateOf(false) }
                    Box {
                        Row(
                            Modifier.fillMaxWidth().height(36.dp).clip(rowShape)
                                .background(if (selected) KColors.Accent.copy(alpha = 0.14f) else KColors.Bg)
                                .border(1.dp, if (selected) KColors.Accent.copy(alpha = 0.75f) else KColors.Line, rowShape)
                                .clickable { onSelect(); menuOpen = true }.padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(spec.label, color = if (selected) KColors.Accent else KColors.Text, fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text(spec.choices.minByOrNull { abs(it.second - current) }?.first ?: "Select",
                                color = if (selected) KColors.Accent else KColors.Muted,
                                fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(4.dp))
                            KIcon(IconKind.CHEVRON_DOWN, KColors.Muted, Modifier.size(12.dp))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false },
                            modifier = Modifier.background(KColors.Surface2)) {
                            spec.choices.forEach { (label, value) ->
                                DropdownMenuItem(text = { Text(label, color = KColors.Text, fontSize = 10.sp) }, onClick = {
                                    onSelect()
                                    editor.beginEdit()
                                    editor.updateEffectParameter(layer.id, effect.id, spec.key, value)
                                    menuOpen = false
                                })
                            }
                        }
                    }
                }
                else -> {
                    val safeSuffix = spec.suffix.replace("%", "%%")
                    val valueFormat = "%." + spec.decimals + "f" + safeSuffix
                    val scale = spec.scale
                    ScrubValueBar(spec.label, current * scale, Modifier.fillMaxWidth(), step = spec.step * scale / 10f, snapStep = spec.step * scale,
                        selected = selected, onSelect = onSelect,
                        minValue = spec.min * scale, maxValue = spec.max * scale, valueFormat = valueFormat,
                        onScrubStart = { onSelect(); editor.beginEdit() }) { value ->
                        editor.updateEffectParameter(layer.id, effect.id, spec.key, (value / scale).coerceIn(spec.min, spec.max))
                    }
                }
            }
        }
        if (animated) KIcon(IconKind.DIAMOND_FILLED, KColors.Accent, Modifier.size(11.dp))
    }
}
