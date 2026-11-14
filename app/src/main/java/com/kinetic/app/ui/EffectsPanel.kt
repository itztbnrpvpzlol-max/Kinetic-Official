package com.kinetic.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.kinetic.app.model.EffectInstance
import com.kinetic.app.model.Layer

private data class EffectControlSpec(
    val label: String, val key: String, val min: Float, val max: Float,
    val stepPerDp: Float, val default: Float, val decimals: Int = 2, val suffix: String = ""
)

private fun controlsFor(effect: EffectInstance): List<EffectControlSpec> = when (effect.name) {
    "Motion Tile" -> listOf(
        EffectControlSpec("Scale", "Scale", 1f, 8f, 0.035f, 2f, 1, "×"),
        EffectControlSpec("Mirror Edges", "Mirror Edges", 0f, 1f, 0.01f, 1f, 0, "")
    )
    "Wave Warp" -> listOf(
        EffectControlSpec("Amount", "Amount", 0f, 0.08f, 0.00035f, 0.012f, 3),
        EffectControlSpec("Frequency", "Frequency", 0.2f, 20f, 0.08f, 4f, 1),
        EffectControlSpec("Speed", "Speed", -8f, 8f, 0.06f, 1f, 1)
    )
    "Warp Shake" -> listOf(
        EffectControlSpec("Amount", "Amount", 0f, 0.08f, 0.00035f, 0.008f, 3),
        EffectControlSpec("Frequency", "Frequency", 0.1f, 30f, 0.12f, 8f, 1)
    )
    "RGB Split" -> listOf(
        EffectControlSpec("Amount", "Amount", 0f, 0.06f, 0.0003f, 0.006f, 3),
        EffectControlSpec("Angle", "Angle", 0f, 360f, 1.8f, 0f, 0, "°")
    )
    "Oscillate" -> listOf(
        EffectControlSpec("X Amount", "X Amount", 0f, 0.15f, 0.0007f, 0.02f, 3),
        EffectControlSpec("Y Amount", "Y Amount", 0f, 0.15f, 0.0007f, 0.02f, 3),
        EffectControlSpec("Frequency", "Frequency", 0.1f, 12f, 0.05f, 1f, 1)
    )
    "Deep Glow" -> listOf(
        EffectControlSpec("Threshold", "Threshold", 0f, 1f, 0.005f, 0.65f, 2),
        EffectControlSpec("Radius", "Radius", 0.001f, 0.08f, 0.00035f, 0.012f, 3),
        EffectControlSpec("Intensity", "Intensity", 0f, 4f, 0.025f, 1.2f, 2)
    )
    "Exposure And Gamma" -> listOf(
        EffectControlSpec("Exposure", "Exposure", -5f, 5f, 0.05f, 0f, 2, " EV"),
        EffectControlSpec("Gamma", "Gamma", 0.1f, 3f, 0.025f, 1f, 2)
    )
    else -> emptyList()
}

@Composable
fun EffectControlsPanel(editor: EditorState, layer: Layer, onAddEffect: () -> Unit) {
    Column(Modifier.fillMaxSize().background(KColors.Surface).verticalScroll(rememberScrollState())
        .padding(horizontal = 12.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("EFFECT STACK", color = KColors.Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 1.1.sp, modifier = Modifier.weight(1f))
            Text("${layer.effectStack.size} applied", color = KColors.Dim, fontSize = 9.sp)
        }
        if (layer.effectStack.isEmpty()) {
            Text("No effects on this layer yet.", color = KColors.Muted, fontSize = 10.sp,
                modifier = Modifier.padding(vertical = 4.dp))
        }
        layer.effectStack.forEach { effect ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(KColors.Bg).border(1.dp, KColors.Line, RoundedCornerShape(8.dp))
                .padding(horizontal = 9.dp, vertical = 7.dp)) {
                Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(effect.name, color = if (effect.enabled) KColors.Text else KColors.Dim,
                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Box(Modifier.clip(RoundedCornerShape(5.dp))
                        .background(if (effect.enabled) KColors.Accent.copy(alpha = .15f) else KColors.Surface2)
                        .clickable { editor.setEffectEnabled(layer.id, effect.id, !effect.enabled) }
                        .padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Text(if (effect.enabled) "ON" else "OFF", color = if (effect.enabled) KColors.Accent else KColors.Muted,
                            fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.size(8.dp))
                    Box(Modifier.size(25.dp).clip(RoundedCornerShape(5.dp)).clickable {
                        editor.removeEffect(layer.id, effect.id)
                    }, contentAlignment = Alignment.Center) {
                        KIcon(IconKind.DELETE, KColors.Muted, Modifier.size(14.dp))
                    }
                }
                if (effect.enabled) controlsFor(effect).forEach { spec ->
                    EffectParameterLine(editor, layer, effect, spec)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(7.dp))
            .background(KColors.Accent).clickable(onClick = onAddEffect), contentAlignment = Alignment.Center) {
            Text("Add Effect", color = androidx.compose.ui.graphics.Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun EffectParameterLine(
    editor: EditorState, layer: Layer, effect: EffectInstance, spec: EffectControlSpec
) {
    val density = LocalDensity.current.density
    val current = editor.layers.firstOrNull { it.id == layer.id }?.effectStack
        ?.firstOrNull { it.id == effect.id }?.parameters?.get(spec.key) ?: spec.default
    var dragAccum by remember(effect.id, spec.key) { mutableFloatStateOf(0f) }
    Row(Modifier.fillMaxWidth().height(31.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(spec.label, color = KColors.Text, fontSize = 10.sp, modifier = Modifier.weight(1f))
        Box(Modifier.size(106.dp, 27.dp).clip(RoundedCornerShape(5.dp))
            .background(KColors.Surface2).border(1.dp, KColors.Line, RoundedCornerShape(5.dp))
            .pointerInput(layer.id, effect.id, spec.key, density) {
                detectHorizontalDragGestures(
                    onDragStart = { dragAccum = 0f; editor.beginEdit() },
                    onDragEnd = { dragAccum = 0f },
                    onDragCancel = { dragAccum = 0f }
                ) { change, delta ->
                    change.consume()
                    dragAccum += delta
                    val latest = editor.layers.firstOrNull { it.id == layer.id }?.effectStack
                        ?.firstOrNull { it.id == effect.id }?.parameters?.get(spec.key) ?: spec.default
                    val next = (latest + delta / density * spec.stepPerDp).coerceIn(spec.min, spec.max)
                    editor.updateEffectParameter(layer.id, effect.id, spec.key, next)
                }
            }, contentAlignment = Alignment.Center) {
            val formatted = "%.${spec.decimals}f".format(java.util.Locale.US, current)
            Text(formatted + spec.suffix, color = KColors.Accent, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
