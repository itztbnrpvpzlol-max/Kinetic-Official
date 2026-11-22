package com.kinetic.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinetic.app.model.EditorState
import com.kinetic.app.model.Layer
import com.kinetic.app.model.PropId
import com.kinetic.app.model.TEXT_ANIMATOR_SLOTS
import com.kinetic.app.model.TA_FIELDS
import com.kinetic.app.model.TextAnimator
import com.kinetic.app.model.taId
import com.kinetic.app.model.VectorPoint
import com.kinetic.app.model.withBox
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import com.kinetic.app.model.TextStyle as TStyle

private val SWATCHES = listOf(0xFFFFFFFF, 0xFF000000, 0xFFFFD60A, 0xFFFF453A, 0xFF30D158, 0xFF0A84FF).map { it.toInt() }

/** Applies [f] to the text layer, then refits its box to the text (and any path / animator reach). */
private fun EditorState.editText(layer: Layer, push: Boolean = false, f: (Layer) -> Layer) {
    updateLayer(layer.id, push) { l ->
        val n = f(l)
        val (w, h) = TextEngine.autoFit(n)
        n.withBox(w, h)
    }
}

private fun fmtNum(v: Float, decimals: Int = 0): String =
    if (decimals <= 0) v.roundToInt().toString() else String.format(java.util.Locale.US, "%.${decimals}f", v)

/** Edit panel for TEXT layers: Text, Style, Layout (with Path Options) and Animator (Flow) tabs. */
@Composable
fun TextEditorPanel(editor: EditorState, layer: Layer, tab: String, onOpenMasking: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (tab) {
            "Text" -> TextTab(editor, layer)
            "Style" -> StyleTab(editor, layer)
            "Layout" -> LayoutTab(editor, layer, onOpenMasking)
            else -> AnimatorTab(editor, layer)
        }
    }
}

// ---------------------------------------------------------------- Text tab
@Composable
private fun TextTab(editor: EditorState, layer: Layer) {
    val st = layer.textStyle
    var showFonts by remember { mutableStateOf(false) }
    var local by remember(layer.id) { mutableStateOf(layer.text) }
    var editedOnce by remember(layer.id) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(KColors.Surface2).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = local,
            onValueChange = { v ->
                if (!editedOnce) { editor.beginEdit(); editedOnce = true }
                local = v
                editor.editText(layer) { it.copy(text = v, name = if (it.name == it.text || it.name == "Text") v.lineSequence().firstOrNull().orEmpty().ifBlank { "Text" } else it.name) }
            },
            textStyle = androidx.compose.ui.text.TextStyle(color = KColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium),
            cursorBrush = SolidColor(KColors.Accent),
            modifier = Modifier.weight(1f)
        )
        KIcon(IconKind.EDIT, KColors.Muted, Modifier.size(16.dp))
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(KColors.Surface2).clickable { showFonts = true }
        .padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(8.dp)).background(KColors.Bg), contentAlignment = Alignment.Center) {
            Text("Aa", color = KColors.Text, fontSize = 17.sp, fontFamily = FontFamily(TextEngine.typeface(st)))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Font", color = KColors.Muted, fontSize = 10.sp)
            Text(st.font, color = KColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Text("System", color = KColors.Dim, fontSize = 11.sp)
        Spacer(Modifier.width(6.dp))
        KIcon(IconKind.CHEVRON_DOWN, KColors.Muted, Modifier.size(14.dp))
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PillButton(active = st.italic, onClick = { editor.editText(layer, true) { it.copy(textStyle = st.copy(italic = !st.italic)) } }) {
            Text("I", color = if (st.italic) KColors.Accent else KColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
        }
        TextEngine.weights.forEach { (name, w) ->
            PillButton(active = st.weight == w, onClick = { editor.editText(layer, true) { it.copy(textStyle = st.copy(weight = w)) } }) {
                Text(name, color = if (st.weight == w) KColors.Accent else KColors.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    SliderRow("Size", st.size, 8f, 500f, 0, { editor.beginEdit() }) { v -> editor.editText(layer) { it.copy(textStyle = it.textStyle.copy(size = v)) } }
    if (showFonts) ActionDialog("Font", { showFonts = false }) {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            TextEngine.fonts.forEach { (name, _) ->
                val sample = st.copy(font = name)
                Row(Modifier.fillMaxWidth().clickable {
                    editor.editText(layer, true) { it.copy(textStyle = it.textStyle.copy(font = name)) }
                    showFonts = false
                }.padding(horizontal = 6.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, color = if (name == st.font) KColors.Accent else KColors.Text, fontSize = 17.sp,
                        fontFamily = FontFamily(TextEngine.typeface(sample)), modifier = Modifier.weight(1f))
                    if (name == st.font) KIcon(IconKind.CHECK, KColors.Accent, Modifier.size(16.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Style tab
@Composable
private fun StyleTab(editor: EditorState, layer: Layer) {
    val st = layer.textStyle
    var picker by remember { mutableStateOf<String?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Fill", color = KColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(78.dp))
        SwatchRow(layer.color, onPick = { c -> editor.editText(layer, true) { it.copy(color = c) } }, onMore = { picker = "fill" })
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(78.dp)) {
            PillButton(active = st.strokeEnabled, onClick = { editor.editText(layer, true) { it.copy(textStyle = st.copy(strokeEnabled = !st.strokeEnabled)) } }) {
                Text("Stroke", color = if (st.strokeEnabled) KColors.Accent else KColors.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        SwatchRow(st.strokeColor, dimmed = !st.strokeEnabled, onPick = { c ->
            editor.editText(layer, true) { it.copy(textStyle = st.copy(strokeColor = c, strokeEnabled = true)) }
        }, onMore = { picker = "stroke" })
    }
    if (st.strokeEnabled) SliderRow("Width", st.strokeWidth, 0.5f, 60f, 1, { editor.beginEdit() }) { v ->
        editor.editText(layer) { it.copy(textStyle = it.textStyle.copy(strokeWidth = v)) }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(KColors.Line))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Gradient", color = KColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        KSwitch(st.gradientEnabled) { on -> editor.editText(layer, true) { it.copy(textStyle = st.copy(gradientEnabled = on)) } }
    }
    if (st.gradientEnabled) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("To", color = KColors.Muted, fontSize = 12.sp, modifier = Modifier.width(78.dp))
            SwatchRow(st.gradientColor, onPick = { c -> editor.editText(layer, true) { it.copy(textStyle = st.copy(gradientColor = c)) } }, onMore = { picker = "gradient" })
        }
        SliderRow("Angle", st.gradientAngle, 0f, 360f, 0, { editor.beginEdit() }) { v ->
            editor.editText(layer) { it.copy(textStyle = it.textStyle.copy(gradientAngle = v)) }
        }
    }
    picker?.let { which ->
        val initial = when (which) { "fill" -> layer.color; "stroke" -> st.strokeColor; else -> st.gradientColor }
        ColorPickerDialog(initial, onDismiss = { picker = null }) { c ->
            picker = null
            editor.editText(layer, true) {
                when (which) {
                    "fill" -> it.copy(color = c)
                    "stroke" -> it.copy(textStyle = it.textStyle.copy(strokeColor = c, strokeEnabled = true))
                    else -> it.copy(textStyle = it.textStyle.copy(gradientColor = c))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Layout tab
@Composable
private fun LayoutTab(editor: EditorState, layer: Layer, onOpenMasking: () -> Unit) {
    val st = layer.textStyle
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Align", color = KColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(70.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(IconKind.ALIGN_L, IconKind.ALIGN_C, IconKind.ALIGN_R).forEachIndexed { i, icon ->
                Box(Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (st.align == i) KColors.Accent.copy(alpha = .2f) else KColors.Surface2)
                    .border(1.dp, if (st.align == i) KColors.Accent.copy(alpha = .6f) else Color.Transparent, RoundedCornerShape(8.dp))
                    .clickable { editor.editText(layer, true) { it.copy(textStyle = st.copy(align = i)) } }, contentAlignment = Alignment.Center) {
                    KIcon(icon, if (st.align == i) KColors.Accent else KColors.Text, Modifier.size(18.dp))
                }
            }
        }
    }
    SliderRow("Spacing", st.tracking, -20f, 100f, 0, { editor.beginEdit() }) { v -> editor.editText(layer) { it.copy(textStyle = it.textStyle.copy(tracking = v)) } }
    SliderRow("Line", st.lineHeight, 0.6f, 3f, 1, { editor.beginEdit() }) { v -> editor.editText(layer) { it.copy(textStyle = it.textStyle.copy(lineHeight = v)) } }
    Box(Modifier.fillMaxWidth().height(1.dp).background(KColors.Line))
    Text("Path Options", color = KColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    val hasPath = (layer.maskPath?.points?.size ?: 0) >= 2
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(KColors.Surface2).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("Path", color = KColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Box {
            Row(Modifier.clip(RoundedCornerShape(8.dp)).background(KColors.Bg).clickable { menu = true }.padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(if (st.pathOnMask) "Mask 1" else "None", color = KColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(6.dp))
                KIcon(IconKind.CHEVRON_DOWN, KColors.Muted, Modifier.size(12.dp))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = KColors.Surface) {
                DropdownMenuItem(text = { Text("None", color = KColors.Text) }, onClick = {
                    menu = false; editor.editText(layer, true) { it.copy(textStyle = st.copy(pathOnMask = false)) }
                })
                DropdownMenuItem(text = { Text("Mask 1", color = KColors.Text) }, onClick = {
                    menu = false; editor.editText(layer, true) { it.copy(textStyle = st.copy(pathOnMask = true)) }
                })
            }
        }
    }
    if (st.pathOnMask) {
        if (!hasPath) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(KColors.Accent.copy(alpha = .1f)).clickable(onClick = onOpenMasking).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("No mask yet. Draw a Pen or shape mask to use as the text path.", color = KColors.Text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text("Open Masking", color = KColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        ToggleRow("Reverse Path", "Flip the direction of the text", st.reversePath) { editor.editText(layer, true) { it.copy(textStyle = st.copy(reversePath = !st.reversePath)) } }
        ToggleRow("Perpendicular To Path", "Rotate each character with the curve", st.perpendicular) { editor.editText(layer, true) { it.copy(textStyle = st.copy(perpendicular = !st.perpendicular)) } }
        ToggleRow("Force Alignment", "Spread text between the first and last margin", st.forceAlign) { editor.editText(layer, true) { it.copy(textStyle = st.copy(forceAlign = !st.forceAlign)) } }
        PropertyLine("First Margin", editor, layer, listOf(PropId.TEXT_FIRST_MARGIN))
        PropertyLine("Last Margin", editor, layer, listOf(PropId.TEXT_LAST_MARGIN))
        Text("First Margin moves left-aligned text along the path, Last Margin moves right-aligned text. Force Alignment uses both.",
            color = KColors.Dim, fontSize = 10.sp)
    }
}

// ---------------------------------------------------------------- Animator tab
@Composable
private fun AnimatorTab(editor: EditorState, layer: Layer) {
    val anims = layer.textAnimators
    val freeSlot = (0 until TEXT_ANIMATOR_SLOTS).firstOrNull { s -> anims.none { it.slot == s } }
    if (anims.isEmpty()) {
        Text("No animators yet.", color = KColors.Muted, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().padding(top = 10.dp), textAlign = TextAlign.Center)
    }
    anims.forEachIndexed { index, a -> AnimatorCard(editor, layer, a, index, freeSlot) }
    Row(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp)).background(if (freeSlot != null) KColors.Accent else KColors.Surface2)
        .clickable(enabled = freeSlot != null) {
            editor.editText(layer, true) { it.copy(textAnimators = it.textAnimators + TextAnimator(slot = freeSlot!!, name = "Animation ${it.textAnimators.size + 1}")) }
        }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        KIcon(IconKind.PLUS, Color.White, Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (freeSlot != null) "Add Animator" else "Animator limit reached (${TEXT_ANIMATOR_SLOTS})", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

private fun Layer.clearAnimatorProps(slot: Int): Layer = copy(props = props - TA_FIELDS.map { taId(slot, it.key) }.toSet())

@Composable
private fun AnimatorCard(editor: EditorState, layer: Layer, a: TextAnimator, index: Int, freeSlot: Int?) {
    fun update(push: Boolean = true, f: (TextAnimator) -> TextAnimator) =
        editor.editText(layer, push) { l -> l.copy(textAnimators = l.textAnimators.map { if (it.id == a.id) f(it) else it }) }
    @Composable fun line(label: String, key: String) = PropertyLine(label, editor, layer, listOf(taId(a.slot, key)))
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(KColors.Row)) {
        Row(Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            KIcon(IconKind.HANDLE, KColors.Dim, Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Flow", color = KColors.Dim, fontSize = 10.sp)
            Spacer(Modifier.width(6.dp))
            Text(a.name, color = KColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconTap(if (a.enabled) IconKind.EYE else IconKind.EYE_OFF, if (a.enabled) KColors.Accent else KColors.Dim) { update { it.copy(enabled = !it.enabled) } }
            IconTap(if (a.collapsed) IconKind.CHEVRON_DOWN else IconKind.CHEVRON_UP, KColors.Muted) { update { it.copy(collapsed = !it.collapsed) } }
            IconTap(IconKind.DELETE, KColors.Danger) {
                editor.editText(layer, true) { l -> l.copy(textAnimators = l.textAnimators.filter { it.id != a.id }).clearAnimatorProps(a.slot) }
            }
            Box {
                IconTap(IconKind.MORE_V, KColors.Text) { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = KColors.Surface) {
                    DropdownMenuItem(text = { Text("Rename", color = KColors.Text) }, onClick = { menu = false; renaming = true })
                    if (freeSlot != null) DropdownMenuItem(text = { Text("Duplicate", color = KColors.Text) }, onClick = {
                        menu = false
                        editor.editText(layer, true) { l ->
                            val copy = a.copy(id = java.util.UUID.randomUUID().toString(), slot = freeSlot, name = a.name + " copy")
                            val copiedProps = TA_FIELDS.mapNotNull { f -> l.props[taId(a.slot, f.key)]?.let { taId(freeSlot, f.key) to it } }.toMap()
                            l.copy(textAnimators = l.textAnimators.toMutableList().also { it.add(index + 1, copy) }, props = l.props + copiedProps)
                        }
                    })
                    if (index > 0) DropdownMenuItem(text = { Text("Move up", color = KColors.Text) }, onClick = {
                        menu = false
                        editor.editText(layer, true) { l -> l.copy(textAnimators = l.textAnimators.toMutableList().also { java.util.Collections.swap(it, index, index - 1) }) }
                    })
                    if (index < layer.textAnimators.lastIndex) DropdownMenuItem(text = { Text("Move down", color = KColors.Text) }, onClick = {
                        menu = false
                        editor.editText(layer, true) { l -> l.copy(textAnimators = l.textAnimators.toMutableList().also { java.util.Collections.swap(it, index, index + 1) }) }
                    })
                    DropdownMenuItem(text = { Text("Reset values", color = KColors.Danger) }, onClick = {
                        menu = false
                        editor.editText(layer, true) { l -> l.clearAnimatorProps(a.slot) }
                    })
                }
            }
        }
        if (!a.collapsed) Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Section("Strength & Delay")
            line("Strength", "STRENGTH")
            line("Delay", "DELAY")
            Section("Transform")
            line("Opacity", "OPACITY")
            line("Position X", "POS_X")
            line("Position Y", "POS_Y")
            line(if (a.separateScale) "Scale X" else "Scale", "SCALE")
            if (a.separateScale) line("Scale Y", "SCALE_Y")
            line("Rotation", "ROTATION")
            line("Tracking", "TRACKING")
            line("Rotation X 3D", "ROT_X")
            line("Rotation Y 3D", "ROT_Y")
            Section("Wiggle")
            line("Wiggle X", "WIG_X")
            line("Wiggle Y", "WIG_Y")
            line("Wiggle Scale", "WIG_SCALE")
            line("Wiggle Rotation", "WIG_ROT")
            line("Speed", "WIG_SPEED")
            line("Hold", "HOLD")
            Section("Unit")
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                TextEngine.units.forEach { u ->
                    Box(Modifier.weight(1f).height(30.dp).clip(RoundedCornerShape(6.dp))
                        .background(if (a.unit == u) KColors.Accent.copy(alpha = .12f) else KColors.Bg)
                        .border(1.dp, if (a.unit == u) KColors.Accent.copy(alpha = .55f) else KColors.Line, RoundedCornerShape(6.dp))
                        .clickable { update { it.copy(unit = u) } }, contentAlignment = Alignment.Center) {
                        Text(u, color = if (a.unit == u) KColors.Accent else KColors.Text, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
            ToggleRow("Separate Scale", "Scale X and Y apart", a.separateScale) { update { it.copy(separateScale = !it.separateScale) } }
            Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Wiggle Seed", color = KColors.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text("Re-roll the random motion", color = KColors.Dim, fontSize = 9.sp)
                }
                Text(a.seed.toString(), color = KColors.Muted, fontSize = 11.sp)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.height(30.dp).clip(RoundedCornerShape(6.dp)).background(KColors.Bg).border(1.dp, KColors.Line, RoundedCornerShape(6.dp))
                    .clickable { update { it.copy(seed = (Math.random() * 9999).toInt()) } }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    Text("Random", color = KColors.Accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            ToggleRow("Reverse", "Stagger from the last unit", a.reverse) { update { it.copy(reverse = !it.reverse) } }
        }
    }
    if (renaming) TextInputDialog("Rename animator", a.name, onDone = { n -> renaming = false; update { it.copy(name = n.ifBlank { it.name }) } }, onDismiss = { renaming = false })
}

// ---------------------------------------------------------------- shared widgets
@Composable
private fun Section(title: String) {
    Text(title, color = KColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 2.dp, top = 6.dp))
}

@Composable
private fun IconTap(icon: IconKind, tint: Color, onClick: () -> Unit) {
    Box(Modifier.size(34.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        KIcon(icon, tint, Modifier.size(17.dp))
    }
}

@Composable
private fun PillButton(active: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.heightIn(min = 38.dp).widthIn(min = 44.dp).clip(RoundedCornerShape(10.dp))
        .background(if (active) KColors.Accent.copy(alpha = .2f) else KColors.Surface2)
        .border(1.dp, if (active) KColors.Accent.copy(alpha = .6f) else Color.Transparent, RoundedCornerShape(10.dp))
        .clickable(onClick = onClick).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun ChipRow(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = KColors.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(60.dp))
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            options.forEach { o ->
                Box(Modifier.height(30.dp).clip(RoundedCornerShape(8.dp)).background(if (o == selected) KColors.Accent else KColors.Bg)
                    .clickable { onSelect(o) }.padding(horizontal = 11.dp), contentAlignment = Alignment.Center) {
                    Text(o, color = if (o == selected) Color.White else KColors.Text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
fun KSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Box(Modifier.width(52.dp).height(30.dp).clip(RoundedCornerShape(15.dp))
        .background(if (checked) KColors.Accent else KColors.Bg)
        .border(1.dp, if (checked) KColors.Accent else KColors.Dim, RoundedCornerShape(15.dp))
        .clickable { onChange(!checked) }.padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(if (checked) Color.White else KColors.Muted))
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = KColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = KColors.Dim, fontSize = 10.sp)
        }
        KSwitch(checked) { onToggle() }
    }
}

@Composable
private fun SwatchRow(current: Int, dimmed: Boolean = false, onPick: (Int) -> Unit, onMore: () -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        SWATCHES.forEach { c ->
            val sel = c == current && !dimmed
            Box(Modifier.size(30.dp).clip(CircleShape).background(Color(if (dimmed) (c and 0x00FFFFFF) or 0x66000000 else c))
                .border(if (sel) 3.dp else 1.dp, if (sel) Color.White else KColors.Dim, CircleShape).clickable { onPick(c) })
        }
        Box(Modifier.size(30.dp).clip(CircleShape).background(KColors.Bg).clickable(onClick = onMore), contentAlignment = Alignment.Center) {
            KIcon(IconKind.MORE, KColors.Text, Modifier.size(16.dp))
        }
    }
}

/** Kinetic-style property row: label on the left, drag-to-scrub value box on the right (same look as the Properties panel). */
@Composable
private fun SliderRow(label: String, value: Float, min: Float, max: Float, decimals: Int, onStart: () -> Unit, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().height(35.dp).padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = KColors.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        ScrubValueBar("", value, Modifier.weight(1f), step = (max - min) / 300f, minValue = min, maxValue = max,
            valueFormat = "%.${decimals}f", onScrubStart = onStart, onValueChange = onChange)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(KColors.Line.copy(alpha = 0.55f)))
}

@Composable
private fun ColorPickerDialog(initial: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) } }
    var h by remember { mutableFloatStateOf(hsv[0]) }
    var s by remember { mutableFloatStateOf(hsv[1]) }
    var v by remember { mutableFloatStateOf(hsv[2]) }
    val color = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))
    var hex by remember { mutableStateOf(String.format("%06X", color and 0xFFFFFF)) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss, containerColor = KColors.Surface,
        title = { Text("Color", color = KColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(8.dp)).background(Color(color)))
                SliderRow("Hue", h, 0f, 360f, 0, {}) { h = it; hex = String.format("%06X", android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)) and 0xFFFFFF) }
                SliderRow("Sat", s * 100f, 0f, 100f, 0, {}) { s = it / 100f; hex = String.format("%06X", android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)) and 0xFFFFFF) }
                SliderRow("Value", v * 100f, 0f, 100f, 0, {}) { v = it / 100f; hex = String.format("%06X", android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)) and 0xFFFFFF) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Hex  #", color = KColors.Muted, fontSize = 13.sp)
                    BasicTextField(hex, { t ->
                        hex = t.filter { it.isLetterOrDigit() }.take(6).uppercase()
                        if (hex.length == 6) hex.toIntOrNull(16)?.let { rgb ->
                            val f = FloatArray(3); android.graphics.Color.colorToHSV(0xFF000000.toInt() or rgb, f); h = f[0]; s = f[1]; v = f[2]
                        }
                    }, textStyle = androidx.compose.ui.text.TextStyle(color = KColors.Text, fontSize = 14.sp), cursorBrush = SolidColor(KColors.Accent),
                        singleLine = true, modifier = Modifier.weight(1f))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(color) }) { Text("OK", color = KColors.Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = KColors.Muted) } }
    )
}
