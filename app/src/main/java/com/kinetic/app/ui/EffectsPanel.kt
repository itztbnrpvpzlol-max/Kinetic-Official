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
import androidx.compose.foundation.shape.RoundedCornerShape
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
    // Reopening the panel never auto-expands the most recently added effect.
    // Expansion is a deliberate user action for this panel session.
    var expandedEffectId by remember(layer.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(layer.id) { editor.clearEffectParameterSelection() }
    Column(
        Modifier.fillMaxSize().background(KColors.Surface)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("EFFECTS", color = KColors.Text, fontSize = 12.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.0.sp)
            Spacer(Modifier.weight(1f))
            Text("${layer.effectStack.size} ACTIVE", color = KColors.Accent,
                fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
        }

        layer.effectStack.forEachIndexed { index, effect ->
            val expanded = expandedEffectId == effect.id
            val keyedParameters = effect.parameterKeys.values.count { it.isNotEmpty() }
            val accent = if (effect.enabled) KColors.Accent else KColors.Dim
            Column(
                Modifier.fillMaxWidth()
                    .background(KColors.Bg)
            ) {
                Row(
                    Modifier.fillMaxWidth().height(42.dp).clickable {
                        if (!expanded && editor.selectedEffectId != effect.id) {
                            editor.clearEffectParameterSelection()
                        }
                        expandedEffectId = if (expanded) null else effect.id
                    }.padding(horizontal = 7.dp),
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
                    val liveEffect = editor.layers.firstOrNull { it.id == layer.id }
                        ?.effectStack?.firstOrNull { it.id == effect.id } ?: effect
                    Column(
                        Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, bottom = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(KColors.Line))
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

        Row(
            Modifier.fillMaxWidth().height(42.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(KColors.Accent)
                .clickable(onClick = onBrowseEffects),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("+", color = KColors.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(7.dp))
            Text("ADD EFFECT", color = KColors.Text,
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
private fun EffectParameterLine(
    editor: EditorState, layer: Layer, effect: EffectInstance, spec: EffectControlSpec,
    selected: Boolean, onSelect: () -> Unit
) {
    val current = effect.parameterAt(spec.key, editor.time, spec.default)
    Column(
        Modifier.fillMaxWidth()
            .background(if (selected) KColors.Accent.copy(alpha = 0.055f) else KColors.Surface.copy(alpha = 0.22f))
            .padding(horizontal = 2.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(Modifier.fillMaxWidth().height(24.dp).clickable(onClick = onSelect),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(2.dp).height(16.dp).background(if (selected) KColors.Accent else KColors.Dim))
            Spacer(Modifier.width(6.dp))
            Text(spec.label, color = if (selected) KColors.Accent else KColors.Text,
                fontSize = 9.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (effect.parameterKeys[spec.key].orEmpty().isNotEmpty()) {
                Text("ANIMATED", color = KColors.Accent, fontSize = 7.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            when {
                spec.toggle -> Switch(checked = current >= 0.5f, onCheckedChange = {
                    onSelect()
                    editor.beginEdit()
                    editor.updateEffectParameter(layer.id, effect.id, spec.key, if (it) 1f else 0f)
                }, modifier = Modifier.size(width = 42.dp, height = 28.dp))
                spec.choices.isNotEmpty() -> {
                    var menuOpen by remember(effect.id, spec.key) { mutableStateOf(false) }
                    Box {
                        Box(Modifier.clip(RoundedCornerShape(7.dp))
                            .background(if (selected) KColors.Accent.copy(alpha = 0.10f) else KColors.Bg)
                            .clickable { onSelect(); menuOpen = true }
                            .padding(horizontal = 11.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center) {
                            Text(spec.choices.minByOrNull { abs(it.second - current) }?.first ?: "Select",
                                color = if (selected) KColors.Accent else KColors.Text,
                                fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            spec.choices.forEach { (label, value) ->
                                DropdownMenuItem(text = { Text(label, fontSize = 10.sp) }, onClick = {
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
                    ScrubValueBar(spec.label, current * scale, Modifier.weight(1f), step = spec.step * scale / 10f, snapStep = spec.step * scale,
                        selected = selected, onSelect = onSelect,
                        minValue = spec.min * scale, maxValue = spec.max * scale, valueFormat = valueFormat,
                        onScrubStart = { onSelect(); editor.beginEdit() }) { value ->
                        editor.updateEffectParameter(layer.id, effect.id, spec.key, (value / scale).coerceIn(spec.min, spec.max))
                    }
                }
            }
        }
    }
}

