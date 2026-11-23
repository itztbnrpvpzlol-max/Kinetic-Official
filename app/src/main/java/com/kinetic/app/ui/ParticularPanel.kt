package com.kinetic.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinetic.app.model.EditorState
import com.kinetic.app.model.Layer
import com.kinetic.app.model.PropId

private val particularPresets = listOf("Rain", "Snow", "Fire", "Sparks", "Fireworks", "Dust", "Bokeh", "Trail", "Comet")
private val particularPalette = listOf(
    "White" to 0xFFFFFFFF.toInt(),
    "Ice" to 0xFFBDEBFF.toInt(),
    "Blue" to 0xFF63B8FF.toInt(),
    "Cyan" to 0xFF68F0E4.toInt(),
    "Gold" to 0xFFFFD36B.toInt(),
    "Orange" to 0xFFFF923D.toInt(),
    "Red" to 0xFFFF5353.toInt(),
    "Violet" to 0xFFB18CFF.toInt()
)

@Composable
fun ParticularPanel(editor: EditorState, layer: Layer) {
    val settings = layer.particleSettings
    Column(
        Modifier.fillMaxSize().background(KColors.Surface).verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text("PRESETS", color = KColors.Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            particularPresets.forEach { preset ->
                val selected = settings.preset == preset
                BoxChip(preset, selected) { editor.applyParticlePreset(layer.id, preset) }
            }
        }

        ParticularColorRow("Particle Color", settings.color) { color ->
            editor.updateLayer(layer.id, push = true) { it.copy(particleSettings = it.particleSettings.copy(color = color)) }
        }
        ParticularColorRow("End Color", settings.endColor) { color ->
            editor.updateLayer(layer.id, push = true) { it.copy(particleSettings = it.particleSettings.copy(endColor = color)) }
        }

        SectionLabel("EMITTER")
        ParticleValueRow(editor, layer, "Particles / sec", PropId.PARTICLE_RATE, 0f, 1000f, 1f, "%.0f")
        ParticleValueRow(editor, layer, "Pre-roll (ms)", PropId.PARTICLE_PREROLL, 0f, 10000f, 5f, "%.0f")
        ParticleValueRow(editor, layer, "Position X", PropId.PARTICLE_POS_X, -2000f, 2000f, 0.5f, "%.1f px")
        ParticleValueRow(editor, layer, "Position Y", PropId.PARTICLE_POS_Y, -2000f, 2000f, 0.5f, "%.1f px")
        ParticleValueRow(editor, layer, "Position Z", PropId.PARTICLE_POS_Z, -4000f, 4000f, 0.5f, "%.1f px")
        ParticleValueRow(editor, layer, "Emitter Size X", PropId.PARTICLE_EMITTER_SIZE_X, 0f, 2000f, 0.5f, "%.1f px")
        ParticleValueRow(editor, layer, "Emitter Size Y", PropId.PARTICLE_EMITTER_SIZE_Y, 0f, 2000f, 0.5f, "%.1f px")
        ParticleValueRow(editor, layer, "Emitter Size Z", PropId.PARTICLE_EMITTER_SIZE_Z, 0f, 2000f, 0.5f, "%.1f px")

        SectionLabel("PARTICLE")
        ParticleValueRow(editor, layer, "Life", PropId.PARTICLE_LIFETIME, 0.01f, 30f, 0.02f, "%.2f s")
        ParticleValueRow(editor, layer, "Life Random", PropId.PARTICLE_LIFE_RANDOM, 0f, 100f, 0.2f, "%.0f%%")
        ParticleValueRow(editor, layer, "Velocity", PropId.PARTICLE_VELOCITY, -1000f, 1000f, 0.5f, "%.1f")
        ParticleValueRow(editor, layer, "Velocity Random", PropId.PARTICLE_VELOCITY_RANDOM, 0f, 100f, 0.2f, "%.0f%%")
        ParticleValueRow(editor, layer, "Direction Spread", PropId.PARTICLE_SPREAD, 0f, 360f, 0.2f, "%.0f°")
        ParticleValueRow(editor, layer, "Gravity", PropId.PARTICLE_GRAVITY, -1000f, 1000f, 0.5f, "%.1f")
        ParticleValueRow(editor, layer, "Size", PropId.PARTICLE_SIZE, 0f, 500f, 0.1f, "%.1f px")
        ParticleValueRow(editor, layer, "Size Random", PropId.PARTICLE_SIZE_RANDOM, 0f, 100f, 0.2f, "%.0f%%")
        ParticleValueRow(editor, layer, "End Size", PropId.PARTICLE_END_SIZE, 0f, 500f, 0.1f, "%.1f px")
        ParticleValueRow(editor, layer, "Opacity", PropId.PARTICLE_OPACITY, 0f, 100f, 0.2f, "%.0f%%")
        ParticleValueRow(editor, layer, "End Opacity", PropId.PARTICLE_END_OPACITY, 0f, 100f, 0.2f, "%.0f%%")
        ParticleValueRow(editor, layer, "Rotation Speed", PropId.PARTICLE_ROTATION_SPEED, -720f, 720f, 0.5f, "%.1f°/s")
        ParticleValueRow(editor, layer, "Turbulence", PropId.PARTICLE_TURBULENCE, 0f, 500f, 0.2f, "%.1f")
        ParticleValueRow(editor, layer, "Air Resistance", PropId.PARTICLE_DRAG, 0f, 100f, 0.2f, "%.0f%%")

        SectionLabel("RENDER")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf("Circle", "Square", "Diamond", "Star").forEach { shape ->
                BoxChip(shape, settings.shape == shape) {
                    editor.updateLayer(layer.id, push = true) {
                        it.copy(particleSettings = it.particleSettings.copy(shape = shape))
                    }
                }
            }
        }
        Text("Particular is a 3D layer. Its emitter plane follows the composition camera and its parent chain.",
            color = KColors.Muted, fontSize = 9.sp, modifier = Modifier.padding(top = 3.dp, bottom = 4.dp))
    }
}

@Composable
private fun SectionLabel(label: String) {
    Text(label, color = KColors.Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp, modifier = Modifier.padding(start = 2.dp, top = 7.dp, bottom = 1.dp))
}

@Composable
private fun BoxChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    androidx.compose.foundation.layout.Box(
        Modifier.height(29.dp).clip(shape)
            .background(if (selected) KColors.Accent.copy(alpha = 0.14f) else KColors.Bg)
            .border(1.dp, if (selected) KColors.Accent.copy(alpha = 0.65f) else KColors.Line, shape)
            .clickable(onClick = onClick).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) KColors.Accent else KColors.Text, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ParticularColorRow(label: String, value: Int, onChange: (Int) -> Unit) {
    var expanded by remember(label) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = KColors.Text, fontSize = 11.sp, modifier = Modifier.weight(1f))
        androidx.compose.foundation.layout.Box {
            androidx.compose.foundation.layout.Box(
                Modifier.size(25.dp).clip(RoundedCornerShape(50)).background(Color(value))
                    .border(1.dp, KColors.Muted, RoundedCornerShape(50)).clickable { expanded = true }
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                particularPalette.forEach { (name, color) ->
                    DropdownMenuItem(text = { Text(name, color = KColors.Text, fontSize = 11.sp) },
                        onClick = { onChange(color); expanded = false })
                }
            }
        }
    }
}

@Composable
private fun ParticleValueRow(
    editor: EditorState, layer: Layer, label: String, id: PropId,
    min: Float, max: Float, step: Float, format: String
) {
    val value = layer.p(id).valueAt(editor.time)
    Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = if (editor.selectedProp == id) KColors.Accent else KColors.Text,
            fontSize = 10.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(108.dp))
        ScrubValueBar(
            label = "", value = value, modifier = Modifier.weight(1f), step = step,
            selected = editor.selectedProp == id, minValue = min, maxValue = max, valueFormat = format,
            onSelect = { editor.selectProp(id) },
            onScrubStart = { editor.selectProp(id); editor.beginEdit() }
        ) { next ->
            editor.selectProp(id)
            editor.setPropValue(layer.id, id, next)
        }
    }
}
