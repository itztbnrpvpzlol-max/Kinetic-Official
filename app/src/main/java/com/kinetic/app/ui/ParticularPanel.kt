package com.kinetic.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import com.kinetic.app.model.LayerType
import com.kinetic.app.model.PropId

private val particularPresets = com.kinetic.app.model.ParticlePresets.names
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
    val settings = layer.particles
    val spriteLayers = editor.layers.filter {
        it.id != layer.id && (it.type == LayerType.TEXT || it.type == LayerType.SHAPE)
    }
    Column(
        Modifier.fillMaxSize().background(KColors.Surface).verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SectionLabel("PRESETS")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            particularPresets.forEach { preset ->
                BoxChip(preset, settings.preset == preset) { editor.applyParticlePreset(layer.id, preset) }
            }
        }
        ParticularColorRow("Particle Color", settings.color) { color ->
            editor.updateLayer(layer.id, push = true) { it.copy(particleSettings = it.particles.copy(color = color)) }
        }
        ParticularColorRow("End Color", settings.endColor) { color ->
            editor.updateLayer(layer.id, push = true) { it.copy(particleSettings = it.particles.copy(endColor = color)) }
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
        ParticularToggleRow("Sphere Emitter", settings.sphereEmitter) { enabled ->
            editor.updateLayer(layer.id, push = true) { it.copy(particleSettings = it.particles.copy(sphereEmitter = enabled)) }
        }
        ParticularChoiceRow("Emitter Motion", settings.emitterMotion, listOf("Stationary", "Follow Layer", "Leave Trail")) { choice ->
            editor.updateLayer(layer.id, push = true) { it.copy(particleSettings = it.particles.copy(emitterMotion = choice)) }
        }

        SectionLabel("VELOCITY & DIRECTION")
        ParticleValueRow(editor, layer, "Velocity", PropId.PARTICLE_VELOCITY, -1000f, 1000f, 0.5f, "%.1f")
        ParticleValueRow(editor, layer, "Velocity Random", PropId.PARTICLE_VELOCITY_RANDOM, 0f, 100f, 0.2f, "%.0f%%")
        ParticleValueRow(editor, layer, "Direction Tilt", PropId.PARTICLE_DIRECTION_TILT, -90f, 90f, 0.2f, "%.1f°")
        ParticleValueRow(editor, layer, "Direction Spin", PropId.PARTICLE_DIRECTION_SPIN, -360f, 360f, 0.2f, "%.1f°")
        ParticleValueRow(editor, layer, "Spread", PropId.PARTICLE_SPREAD, 0f, 360f, 0.2f, "%.0f°")
        ParticularToggleRow("Velocity from Motion", settings.velocityFromMotion) { enabled ->
            editor.updateLayer(layer.id, push = true) { it.copy(particleSettings = it.particles.copy(velocityFromMotion = enabled)) }
        }

        SectionLabel("PHYSICS")
        ParticleValueRow(editor, layer, "Gravity", PropId.PARTICLE_GRAVITY, -1000f, 1000f, 0.5f, "%.1f")
        ParticleValueRow(editor, layer, "Wind X", PropId.PARTICLE_WIND_X, -1000f, 1000f, 0.5f, "%.1f")
        ParticleValueRow(editor, layer, "Wind Y", PropId.PARTICLE_WIND_Y, -1000f, 1000f, 0.5f, "%.1f")
        ParticleValueRow(editor, layer, "Wind Z", PropId.PARTICLE_WIND_Z, -1000f, 1000f, 0.5f, "%.1f")
        ParticleValueRow(editor, layer, "Air Drag", PropId.PARTICLE_DRAG, 0f, 100f, 0.2f, "%.0f%%")
        ParticleValueRow(editor, layer, "Turbulence", PropId.PARTICLE_TURBULENCE, 0f, 500f, 0.2f, "%.1f")
        ParticleValueRow(editor, layer, "Turbulence Speed", PropId.PARTICLE_TURBULENCE_SPEED, 0f, 20f, 0.05f, "%.2f")

        SectionLabel("PARTICLE")
        ParticleValueRow(editor, layer, "Life (ms)", PropId.PARTICLE_LIFETIME, 10f, 30000f, 20f, "%.0f ms", displayMultiplier = 1000f)
        ParticleValueRow(editor, layer, "Life Random", PropId.PARTICLE_LIFE_RANDOM, 0f, 100f, 0.2f, "%.0f%%")
        ParticleValueRow(editor, layer, "Size", PropId.PARTICLE_SIZE, 0f, 500f, 0.1f, "%.1f px")
        ParticleValueRow(editor, layer, "Size Random", PropId.PARTICLE_SIZE_RANDOM, 0f, 100f, 0.2f, "%.0f%%")
        ParticleValueRow(editor, layer, "End Size", PropId.PARTICLE_END_SIZE, 0f, 500f, 0.1f, "%.1f px")
        ParticleValueRow(editor, layer, "Opacity", PropId.PARTICLE_OPACITY, 0f, 100f, 0.2f, "%.0f%%")
        ParticleValueRow(editor, layer, "Fade In", PropId.PARTICLE_FADE_IN, 0f, 100f, 0.2f, "%.0f%%")
        ParticleValueRow(editor, layer, "Fade Out", PropId.PARTICLE_FADE_OUT, 0f, 100f, 0.2f, "%.0f%%")
        ParticleValueRow(editor, layer, "Feather", PropId.PARTICLE_FEATHER, 0f, 100f, 0.2f, "%.0f%%")

        SectionLabel("SPRITE")
        ParticularChoiceRow(
            "Sprite Layer",
            settings.spriteLayerId?.let { id -> spriteLayers.firstOrNull { it.id == id }?.name } ?: "None",
            listOf("None") + spriteLayers.map { it.name }
        ) { choice ->
            val id = spriteLayers.firstOrNull { it.name == choice }?.id
            editor.updateLayer(layer.id, push = true) { it.copy(particleSettings = it.particles.copy(spriteLayerId = id)) }
        }
        ParticularToggleRow("Sprite Tint", settings.spriteTint) { enabled ->
            editor.updateLayer(layer.id, push = true) { it.copy(particleSettings = it.particles.copy(spriteTint = enabled)) }
        }
        ParticleValueRow(editor, layer, "Rotation", PropId.PARTICLE_ROTATION, -360f, 360f, 0.5f, "%.1f°")
        ParticularToggleRow("Orient to Motion", settings.orientToMotion) { enabled ->
            editor.updateLayer(layer.id, push = true) { it.copy(particleSettings = it.particles.copy(orientToMotion = enabled)) }
        }
        ParticleValueRow(editor, layer, "Rotation Random", PropId.PARTICLE_ROTATION_RANDOM, 0f, 720f, 0.5f, "%.1f°")
        ParticleValueRow(editor, layer, "Spin (°/sec)", PropId.PARTICLE_SPIN, -720f, 720f, 0.5f, "%.1f")
        ParticleValueRow(editor, layer, "Spin Random", PropId.PARTICLE_SPIN_RANDOM, 0f, 720f, 0.5f, "%.1f")
        ParticleValueRow(editor, layer, "Motion Stretch", PropId.PARTICLE_MOTION_STRETCH, 0f, 1000f, 1f, "%.0f")

        SectionLabel("TAIL")
        ParticularToggleRow("Enable Tail", settings.tailEnabled) { enabled ->
            editor.updateLayer(layer.id, push = true) { it.copy(particleSettings = it.particles.copy(tailEnabled = enabled)) }
        }
        if (settings.tailEnabled) {
            ParticleValueRow(editor, layer, "Tail Length (ms)", PropId.PARTICLE_TAIL_LENGTH, 0f, 10000f, 5f, "%.0f")
            ParticleValueRow(editor, layer, "Tail Segments", PropId.PARTICLE_TAIL_SEGMENTS, 1f, 32f, 1f, "%.0f")
            ParticleValueRow(editor, layer, "Tail Width", PropId.PARTICLE_TAIL_WIDTH, 0f, 500f, 0.5f, "%.1f")
            ParticleValueRow(editor, layer, "Tail Taper", PropId.PARTICLE_TAIL_TAPER, 0f, 100f, 0.2f, "%.0f%%")
            ParticleValueRow(editor, layer, "Tail Opacity", PropId.PARTICLE_TAIL_OPACITY, 0f, 100f, 0.2f, "%.0f%%")
        }

        SectionLabel("PARTICLE TYPE")
        ParticularChoiceRow("Type", settings.particleType,
            listOf("Sphere", "Glow Sphere", "Star", "Cloudlet", "Streaklet", "Sprite")) { type ->
            editor.updateLayer(layer.id, push = true) {
                it.copy(particleSettings = it.particles.copy(particleType = type))
            }
        }

        SectionLabel("RENDER")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf("Circle", "Square", "Diamond", "Star").forEach { shape ->
                BoxChip(shape, settings.shape == shape) {
                    editor.updateLayer(layer.id, push = true) {
                        it.copy(particleSettings = it.particles.copy(shape = shape))
                    }
                }
            }
        }
        ParticularToggleRow("Add Mode", settings.additive) { enabled ->
            editor.updateLayer(layer.id, push = true) { it.copy(particleSettings = it.particles.copy(additive = enabled)) }
        }
        ParticularToggleRow("Show Layer", settings.showLayer) { enabled ->
            editor.updateLayer(layer.id, push = true) { it.copy(particleSettings = it.particles.copy(showLayer = enabled)) }
        }
        ParticleValueRow(editor, layer, "Random Seed", PropId.PARTICLE_RANDOM_SEED, 0f, 100000f, 1f, "%.0f")
        Text(
            "Particular is always 3D. Particles are projected through the active composition camera and follow the layer's 3D parent chain.",
            color = KColors.Muted, fontSize = 9.sp, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
        )
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
    Box(
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
        Box {
            Box(
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
private fun ParticularToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(5.dp))
            .clickable { onChange(!value) }.padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = KColors.Text, fontSize = 10.sp, modifier = Modifier.weight(1f))
        Box(
            Modifier.size(34.dp, 19.dp).clip(RoundedCornerShape(12.dp))
                .background(if (value) KColors.Accent else KColors.Surface2)
                .border(1.dp, if (value) KColors.Accent else KColors.Muted.copy(alpha = 0.35f), RoundedCornerShape(12.dp)),
            contentAlignment = if (value) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Box(Modifier.padding(horizontal = 2.dp).size(13.dp).clip(RoundedCornerShape(50)).background(Color.White))
        }
    }
}

@Composable
private fun ParticularChoiceRow(label: String, value: String, options: List<String>, onChange: (String) -> Unit) {
    var expanded by remember(label) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = KColors.Text, fontSize = 10.sp, modifier = Modifier.weight(1f))
        Box {
            Row(
                Modifier.clip(RoundedCornerShape(5.dp)).background(KColors.Bg)
                    .border(1.dp, KColors.Line, RoundedCornerShape(5.dp))
                    .clickable { expanded = true }.padding(horizontal = 9.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(value, color = KColors.Text, fontSize = 10.sp)
                Spacer(Modifier.width(8.dp))
                Text("⌄", color = KColors.Muted, fontSize = 11.sp)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(text = { Text(option, color = if (option == value) KColors.Accent else KColors.Text, fontSize = 11.sp) },
                        onClick = { onChange(option); expanded = false })
                }
            }
        }
    }
}

@Composable
private fun ParticleValueRow(
    editor: EditorState, layer: Layer, label: String, id: PropId,
    min: Float, max: Float, step: Float, format: String, displayMultiplier: Float = 1f
) {
    val value = layer.p(id).valueAt(editor.time) * displayMultiplier
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
            editor.setPropValue(layer.id, id, next / displayMultiplier)
        }
    }
}
