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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
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
import com.kinetic.app.engine.EffectCraftCatalog
import com.kinetic.app.engine.EffectCraftEffectInfo
import com.kinetic.app.engine.EffectCraftParameterInfo
import com.kinetic.app.model.EditorState
import com.kinetic.app.model.EffectInstance
import com.kinetic.app.model.Layer
import kotlin.math.abs

internal data class EffectControlSpec(
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

internal fun controlsFor(effect: EffectInstance): List<EffectControlSpec> = when (effect.name) {
    "Motion Tile" -> listOf(
        number("Tile Width", "Tile Width", 10f, 300f, 1f, 100f, 0, "%"),
        number("Tile Height", "Tile Height", 10f, 300f, 1f, 100f, 0, "%"),
        number("Output Width", "Output Width", 100f, 2000f, 5f, 2000f, 0, "%"),
        number("Output Height", "Output Height", 100f, 2000f, 5f, 2000f, 0, "%"),
        toggle("Mirror Edges", "Mirror Edges", true),
        number("Phase X", "Phase X", -2f, 2f, 0.01f, 0f, 2),
        number("Phase Y", "Phase Y", -2f, 2f, 0.01f, 0f, 2)
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
    "Morphosis", "Deep Glow" -> listOf(
        number("Threshold", "Threshold", 0f, 1f, 0.05f, 0.65f),
        number("Radius", "Radius", 0.001f, 0.08f, 0.002f, 0.012f, 3),
        number("Intensity", "Intensity", 0f, 4f, 0.1f, 1.2f),
        choice("Quality", "Quality", listOf("Performance", "High")),
        number("Spread", "Spread", 0.25f, 3f, 0.25f, 1f, 2, "×"),
        number("Saturation", "Saturation", 0f, 2f, 0.1f, 1f),
        toggle("Glow Only", "Glow Only")
    )
    "Exposure And Gamma" -> listOf(
        number("Exposure", "Exposure", -5f, 5f, 0.4f, 0f, 2, " stops"),
        number("Gamma", "Gamma", 0.1f, 3f, 0.05f, 1f),
        number("Offset", "Offset", -1f, 1f, 0.05f, 0f),
        choice("Channel", "Channel", listOf("Master", "Red", "Green", "Blue"))
    )
    else -> emptyList()
}
@Composable
fun EffectControlsPanel(editor: EditorState, layer: Layer, onBrowseEffects: () -> Unit) {
    // Reopening the panel never auto-expands the most recently added effect.
    // Expansion is a deliberate user action for this panel session.
    var expandedEffectId by remember(layer.id) { mutableStateOf<String?>(null) }
    var effectCraftCatalog by remember { mutableStateOf<Map<String, EffectCraftEffectInfo>>(emptyMap()) }
    LaunchedEffect(Unit) {
        EffectCraftCatalog.load().onSuccess { effects -> effectCraftCatalog = effects.associateBy { it.id } }
    }
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
                        if (effect.effectCraftId != null) {
                            val definition = effectCraftCatalog[effect.effectCraftId]
                            if (definition == null) {
                                Text("Loading EffectCraft parameters…", color = KColors.Muted, fontSize = 9.sp,
                                    modifier = Modifier.padding(8.dp))
                            } else {
                                definition.params.forEach { parameter ->
                                    EffectCraftParameterLine(editor, layer, liveEffect, parameter)
                                }
                            }
                        } else {
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
                    ScrubValueBar(spec.label, current, Modifier.weight(1f), step = spec.step / 10f, snapStep = spec.step,
                        selected = selected, onSelect = onSelect,
                        minValue = spec.min, maxValue = spec.max, valueFormat = valueFormat,
                        onScrubStart = { onSelect(); editor.beginEdit() }) { value ->
                        editor.updateEffectParameter(layer.id, effect.id, spec.key, value.coerceIn(spec.min, spec.max))
                    }
                }
            }
        }
    }
}


@Composable
private fun EffectCraftParameterLine(
    editor: EditorState,
    layer: Layer,
    effect: EffectInstance,
    parameter: EffectCraftParameterInfo
) {
    val raw = effect.effectCraftParameters[parameter.id] ?: parameter.defaultJson
    val type = parameter.type.lowercase()
    val booleanValue = raw.equals("true", true) || raw == "1"
    var editing by remember(effect.id, parameter.id) { mutableStateOf(false) }
    var input by remember(effect.id, parameter.id, raw) { mutableStateOf(raw.trim('"')) }
    var menuOpen by remember(effect.id, parameter.id) { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().background(KColors.Surface.copy(alpha = 0.22f))
            .padding(horizontal = 5.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(parameter.name, color = KColors.Text, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
        when {
            type.contains("bool") || type.contains("toggle") -> {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (booleanValue) "On" else "Off", color = KColors.Muted, fontSize = 9.sp,
                        modifier = Modifier.weight(1f))
                    Switch(checked = booleanValue, onCheckedChange = {
                        editor.beginEdit()
                        editor.updateEffectCraftParameter(layer.id, effect.id, parameter.id, it.toString())
                    }, modifier = Modifier.size(width = 42.dp, height = 28.dp))
                }
            }
            parameter.options.isNotEmpty() -> {
                Box {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(KColors.Bg)
                        .clickable { menuOpen = true }.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(parameter.options.firstOrNull { it.valueJson == raw }?.label ?: raw,
                            color = KColors.Text, fontSize = 9.sp, modifier = Modifier.weight(1f))
                        Text("▾", color = KColors.Muted, fontSize = 10.sp)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        parameter.options.forEach { option ->
                            DropdownMenuItem(text = { Text(option.label, fontSize = 10.sp) }, onClick = {
                                editor.beginEdit()
                                editor.updateEffectCraftParameter(layer.id, effect.id, parameter.id, option.valueJson)
                                menuOpen = false
                            })
                        }
                    }
                }
            }
            else -> {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(KColors.Bg)
                    .clickable { input = raw.trim('"'); editing = true }
                    .padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(raw, color = KColors.Text, fontSize = 9.sp, modifier = Modifier.weight(1f), maxLines = 2)
                    Text("EDIT", color = KColors.Accent, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(parameter.name) },
            text = {
                OutlinedTextField(value = input, onValueChange = { input = it },
                    label = { Text(parameter.type) }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = input.trim()
                    val encoded = when {
                        type.contains("string") || type.contains("color") || type.contains("file") ->
                            org.json.JSONObject.quote(trimmed)
                        else -> runCatching {
                            val parsed = org.json.JSONTokener(trimmed).nextValue()
                            when (parsed) {
                                is Number -> {
                                    val number = parsed.toDouble()
                                    if (!number.isFinite()) null
                                    else number.coerceIn(parameter.min ?: Double.NEGATIVE_INFINITY,
                                        parameter.max ?: Double.POSITIVE_INFINITY).toString()
                                }
                                is Boolean, is org.json.JSONArray, is org.json.JSONObject -> parsed.toString()
                                else -> org.json.JSONObject.quote(trimmed)
                            }
                        }.getOrNull()
                    }
                    if (encoded != null) {
                        editor.beginEdit()
                        editor.updateEffectCraftParameter(layer.id, effect.id, parameter.id, encoded)
                    }
                    editing = false
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } }
        )
    }
}
