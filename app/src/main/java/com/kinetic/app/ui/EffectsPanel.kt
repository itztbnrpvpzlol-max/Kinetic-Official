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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinetic.app.model.EditorState
import com.kinetic.app.model.EffectInstance
import com.kinetic.app.model.Layer

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
    Column(Modifier.fillMaxSize().background(KColors.Surface).verticalScroll(rememberScrollState())
        .padding(horizontal = 12.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("EFFECT STACK", color = KColors.Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 1.1.sp, modifier = Modifier.weight(1f))
            Text(layer.effectStack.size.toString() + " applied", color = KColors.Dim, fontSize = 9.sp)
        }
        if (layer.effectStack.isEmpty()) Text("No effects on this layer yet.", color = KColors.Muted, fontSize = 10.sp)
        layer.effectStack.forEach { effect ->
            val expanded = expandedEffectId == effect.id
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(KColors.Bg)
                .border(1.dp, KColors.Line, RoundedCornerShape(9.dp)).padding(horizontal = 9.dp, vertical = 7.dp)) {
                Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).fillMaxHeight().clickable {
                        expandedEffectId = if (expanded) null else effect.id
                    }, contentAlignment = Alignment.CenterStart) {
                        Text(effect.name, color = if (effect.enabled) KColors.Text else KColors.Dim,
                            fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Box(Modifier.size(28.dp).clip(RoundedCornerShape(5.dp)).clickable {
                        expandedEffectId = if (expanded) null else effect.id
                    }, contentAlignment = Alignment.Center) {
                        Text(if (expanded) "⌃" else "⌄", color = KColors.Muted, fontSize = 17.sp)
                    }
                    Box(Modifier.size(28.dp).clip(RoundedCornerShape(5.dp)).clickable {
                        editor.setEffectEnabled(layer.id, effect.id, !effect.enabled)
                    }, contentAlignment = Alignment.Center) {
                        KIcon(if (effect.enabled) IconKind.EYE else IconKind.EYE_OFF,
                            if (effect.enabled) KColors.Accent else KColors.Dim, Modifier.size(15.dp))
                    }
                    Box(Modifier.size(28.dp).clip(RoundedCornerShape(5.dp)).clickable {
                        editor.removeEffect(layer.id, effect.id)
                        if (expandedEffectId == effect.id) expandedEffectId = null
                    }, contentAlignment = Alignment.Center) {
                        KIcon(IconKind.DELETE, KColors.Muted, Modifier.size(14.dp))
                    }
                }
                if (expanded) {
                    Spacer(Modifier.height(5.dp))
                    controlsFor(effect).forEach { EffectParameterLine(editor, layer, effect, it) }
                }
            }
        }
        Box(
            Modifier.fillMaxWidth().height(38.dp).clip(RoundedCornerShape(7.dp))
                .background(KColors.Accent).clickable(onClick = onBrowseEffects),
            contentAlignment = Alignment.Center
        ) {
            Text("Add Effect  +", color = androidx.compose.ui.graphics.Color.White,
                fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun EffectParameterLine(editor: EditorState, layer: Layer, effect: EffectInstance, spec: EffectControlSpec) {
    val current = editor.layers.firstOrNull { it.id == layer.id }?.effectStack
        ?.firstOrNull { it.id == effect.id }?.parameters?.get(spec.key) ?: spec.default
    Row(Modifier.fillMaxWidth().height(38.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(spec.label, color = KColors.Text, fontSize = 10.sp, modifier = Modifier.weight(1f))
        when {
            spec.toggle -> Switch(checked = current >= 0.5f, onCheckedChange = {
                editor.beginEdit()
                editor.updateEffectParameter(layer.id, effect.id, spec.key, if (it) 1f else 0f)
            }, modifier = Modifier.size(width = 42.dp, height = 28.dp))
            spec.choices.isNotEmpty() -> {
                var menuOpen by remember(effect.id, spec.key) { mutableStateOf(false) }
                Box {
                    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(KColors.Surface2)
                        .border(1.dp, KColors.Line, RoundedCornerShape(6.dp)).clickable { menuOpen = true }
                        .padding(horizontal = 10.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
                        Text(spec.choices.minByOrNull { kotlin.math.abs(it.second - current) }?.first ?: "Select",
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
                    spec.label, current, Modifier.width(132.dp), step = spec.step,
                    minValue = spec.min, maxValue = spec.max, valueFormat = valueFormat,
                    onScrubStart = { editor.beginEdit() }
                ) { value ->
                    editor.updateEffectParameter(layer.id, effect.id, spec.key, value.coerceIn(spec.min, spec.max))
                }
            }
        }
    }
}
