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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kinetic.app.model.CompSettings
import com.kinetic.app.model.fmt
import kotlin.math.roundToInt

private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

private fun ratioText(w: Int, h: Int): String {
    if (w <= 0 || h <= 0) return ""
    val g = gcd(w, h)
    val rw = w / g
    val rh = h / g
    return if (rw <= 32 && rh <= 32) "$rw:$rh" else fmt("%.2f:1", w.toFloat() / h)
}

private fun hexOf(c: Long): String = fmt("%06X", c and 0xFFFFFFL)

private fun parseHex(s: String): Long? =
    if (s.length == 6 && s.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }) 0xFF000000L or s.toLong(16) else null

private fun trimFloat(v: Float): String =
    if (v == v.toInt().toFloat()) v.toInt().toString() else fmt("%.2f", v).trimEnd('0').trimEnd('.')

@Composable
fun CompSettingsDialog(
    title: String,
    confirmLabel: String,
    initial: CompSettings,
    onConfirm: (CompSettings) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initial.name) }
    var w by remember { mutableStateOf(initial.width.toString()) }
    var h by remember { mutableStateOf(initial.height.toString()) }
    var fps by remember { mutableIntStateOf(initial.fps) }
    var dur by remember { mutableStateOf(trimFloat(initial.duration)) }
    var bg by remember { mutableStateOf(initial.background) }
    var hex by remember { mutableStateOf(hexOf(initial.background)) }
    var lock by remember { mutableStateOf(false) }
    var ratio by remember { mutableStateOf(initial.width.toFloat() / initial.height.coerceAtLeast(1)) }

    val wi = w.toIntOrNull() ?: 0
    val hi = h.toIntOrNull() ?: 0
    val durValue = dur.toFloatOrNull()
    val shape = RoundedCornerShape(14.dp)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(14.dp).widthIn(max = 460.dp).fillMaxWidth().heightIn(max = 640.dp)
                .clip(shape).background(KColors.Surface).border(1.dp, KColors.Line, shape)
        ) {
            Text(title, color = KColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(16.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(KColors.Line))
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                FieldRow("Name") {
                    DialogField(name, { name = it.take(60) }, Modifier.fillMaxWidth(), KeyboardType.Text)
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Preset", color = KColors.Muted, fontSize = 12.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompSettings.PRESETS.forEach { p ->
                            Chip(p.label, p.width == wi && p.height == hi && p.fps == fps) {
                                w = p.width.toString(); h = p.height.toString(); fps = p.fps
                                ratio = p.width.toFloat() / p.height
                            }
                        }
                    }
                }
                FieldRow("Width") {
                    DialogField(w, { s ->
                        w = s.filter { it.isDigit() }.take(4)
                        val v = w.toIntOrNull()
                        if (lock && v != null && v > 0) h = (v / ratio).roundToInt().coerceAtLeast(1).toString()
                    }, Modifier.width(110.dp), KeyboardType.Number)
                    Text("px", color = KColors.Dim, fontSize = 12.sp)
                }
                FieldRow("Height") {
                    DialogField(h, { s ->
                        h = s.filter { it.isDigit() }.take(4)
                        val v = h.toIntOrNull()
                        if (lock && v != null && v > 0) w = (v * ratio).roundToInt().coerceAtLeast(1).toString()
                    }, Modifier.width(110.dp), KeyboardType.Number)
                    Text("px", color = KColors.Dim, fontSize = 12.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Chip("Lock aspect ratio", lock) {
                        lock = !lock
                        if (lock && wi > 0 && hi > 0) ratio = wi.toFloat() / hi
                    }
                    Text(ratioText(wi, hi), color = KColors.Muted, fontSize = 12.sp)
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Frame rate", color = KColors.Muted, fontSize = 12.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompSettings.FPS.forEach { f -> Chip("$f fps", f == fps) { fps = f } }
                    }
                }
                FieldRow("Duration") {
                    DialogField(dur, { s -> dur = s.filter { it.isDigit() || it == '.' }.take(8) }, Modifier.width(110.dp), KeyboardType.Decimal)
                    Text(
                        "sec" + (durValue?.let { "  ·  ${(it * fps).roundToInt()} frames" } ?: ""),
                        color = KColors.Dim, fontSize = 12.sp
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Background color", color = KColors.Muted, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CompSettings.BACKGROUNDS.forEach { c ->
                            val sw = RoundedCornerShape(7.dp)
                            Box(
                                Modifier.size(30.dp).clip(sw).background(Color(c))
                                    .border(if (c == bg) 2.dp else 1.dp, if (c == bg) KColors.Accent else KColors.Line, sw)
                                    .clickable { bg = c; hex = hexOf(c) }
                            )
                        }
                    }
                    FieldRow("Hex") {
                        DialogField(hex, { s ->
                            hex = s.uppercase().filter { it.isDigit() || it in 'A'..'F' }.take(6)
                            parseHex(hex)?.let { bg = it }
                        }, Modifier.width(110.dp), KeyboardType.Ascii)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(KColors.Line))
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Text("Cancel", color = KColors.Muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onDismiss).padding(horizontal = 14.dp, vertical = 10.dp))
                Spacer(Modifier.width(6.dp))
                Text(confirmLabel, color = KColors.Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                        onConfirm(
                            CompSettings(
                                name = name,
                                width = w.toIntOrNull() ?: initial.width,
                                height = h.toIntOrNull() ?: initial.height,
                                fps = fps,
                                duration = durValue ?: initial.duration,
                                background = bg
                            ).sanitized()
                        )
                    }.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
    }
}

@Composable
private fun FieldRow(label: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, color = KColors.Muted, fontSize = 12.sp, modifier = Modifier.width(64.dp))
        content()
    }
}

@Composable
private fun DialogField(value: String, onChange: (String) -> Unit, modifier: Modifier, keyboard: KeyboardType) {
    val shape = RoundedCornerShape(6.dp)
    BasicTextField(
        value = value, onValueChange = onChange, singleLine = true,
        textStyle = TextStyle(color = KColors.Text, fontSize = 14.sp, fontFamily = KineticFonts.Family),
        cursorBrush = SolidColor(KColors.Accent),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = modifier.clip(shape).background(KColors.Bg).border(1.dp, KColors.Line, shape)
            .padding(horizontal = 10.dp, vertical = 9.dp)
    )
}
