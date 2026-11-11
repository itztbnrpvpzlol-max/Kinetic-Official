package com.kinetic.app.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinetic.app.data.AppSettings
import com.kinetic.app.data.AudioEngine
import com.kinetic.app.data.MediaLoader
import com.kinetic.app.data.ProjectStore
import com.kinetic.app.model.EditorState
import com.kinetic.app.model.Layer
import com.kinetic.app.model.LayerType
import com.kinetic.app.model.PropId
import com.kinetic.app.model.isDescendantOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun EditorScreen(editor: EditorState, onExit: () -> Unit, onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    var showImport by remember { mutableStateOf(false) }
    var showLayers by remember { mutableStateOf(false) }
    var showLeave by remember { mutableStateOf(false) }
    var panelHeight by remember { mutableFloatStateOf(210f) }
    var panelTab by remember { mutableIntStateOf(0) }
    var renameTarget by remember { mutableStateOf<Layer?>(null) }
    var parentPicker by remember { mutableStateOf(false) }
    var previewExpanded by remember { mutableStateOf(false) }
    var previewHeight by remember { mutableFloatStateOf(210f) }
    var dockTool by remember { mutableStateOf("Select") }
    var showToolSheet by remember { mutableStateOf(false) }

    LaunchedEffect(editor.selectedId) {
        dockTool = if (editor.selectedId == null) "Select" else "Properties"
        showToolSheet = false
    }

    fun leave() { if (editor.layers.isEmpty()) onExit() else showLeave = true }
    BackHandler { leave() }
    BackHandler(enabled = previewExpanded) { previewExpanded = false }

    val audio = remember { AudioEngine(ctx) }
    DisposableEffect(Unit) { onDispose { audio.release(); MediaLoader.releaseAll() } }
    val projectStore = remember { ProjectStore(ctx) }
    LaunchedEffect(editor.settings.previewMemoryMb) { MediaLoader.configureCache(editor.settings.previewMemoryMb) }
    LaunchedEffect(editor.layers.map { it.id to it.uri }) { audio.prepare(editor.layers) }

    LaunchedEffect(editor.layers, editor.duration, editor.resolution, editor.projectName) {
        delay(400)
        withContext(Dispatchers.IO) { runCatching { projectStore.save(editor) } }
    }

    LaunchedEffect(editor.playing) {
        if (editor.playing) {
            if (editor.time >= editor.duration - 0.01f) editor.time = 0f
            var last = withFrameNanos { it }
            // Advance the transport on composition frames instead of publishing a new
            // Compose state value on every display refresh (often 60/90/120 Hz).
            // This prevents the whole timeline/property tree from being invalidated
            // more often than the composition can actually display frames.
            var transportTime = editor.time
            var publishedFrame = (transportTime * editor.fps).toInt()
            var audioAccumulator = 0f
            while (editor.playing) {
                val now = withFrameNanos { it }
                val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.1f)
                last = now
                transportTime += dt
                audioAccumulator += dt
                if (transportTime >= editor.duration) {
                    if (editor.settings.loop) {
                        transportTime = 0f
                        publishedFrame = -1
                    } else {
                        transportTime = editor.duration
                        editor.time = editor.duration
                        editor.playing = false
                    }
                }
                val nextFrame = (transportTime * editor.fps).toInt()
                if (nextFrame != publishedFrame && editor.playing) {
                    editor.time = (nextFrame / editor.fps.toFloat()).coerceAtMost(editor.duration)
                    publishedFrame = nextFrame
                }
                // Audio transport work is deliberately decoupled from the visual frame clock.
                if (audioAccumulator >= 0.08f || !editor.playing) {
                    audio.sync(editor.layers, transportTime, editor.playing)
                    audioAccumulator = 0f
                }
            }
        }
        audio.pauseAll()
    }

    fun importUris(uris: List<Uri>, type: LayerType) {
        scope.launch {
            for (u in uris) {
                runCatching { ctx.contentResolver.takePersistableUriPermission(u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                val info = withContext(Dispatchers.IO) { MediaLoader.probe(ctx, u, type) }
                val name = MediaLoader.displayName(ctx, u)
                editor.addMedia(type, name, u.toString(), info.width, info.height, info.length)
            }
        }
    }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { importUris(it, LayerType.VIDEO) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { importUris(it, LayerType.IMAGE) }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { importUris(it, LayerType.AUDIO) }


    Column(Modifier.fillMaxSize().background(KColors.Bg).statusBarsPadding().navigationBarsPadding()) {
        TopBar(editor, onMenuHome = { leave() }, onOpenSettings = {
            editor.playing = false; onOpenSettings()
        }, onExport = { Toast.makeText(ctx, "Export isn't built yet", Toast.LENGTH_SHORT).show() })

        if (previewExpanded) {
            PreviewCanvas(editor, Modifier.weight(1f).fillMaxWidth())
        } else {
            PreviewCanvas(editor, Modifier.fillMaxWidth().height(previewHeight.dp))
            Box(Modifier.fillMaxWidth().height(10.dp).background(KColors.Bg).pointerInput(Unit) {
                detectVerticalDragGestures { change, drag -> change.consume(); previewHeight = (previewHeight + drag / density).coerceIn(120f, 420f) }
            }, contentAlignment = Alignment.Center) {
                Box(Modifier.width(44.dp).height(4.dp).clip(CircleShape).background(KColors.Muted))
            }
        }
        PreviewToolsBar(editor, onAddLayer = { showLayers = true }, onTogglePreview = { previewExpanded = !previewExpanded })
        if (!previewExpanded) {
            TimelinePane(editor, Modifier.weight(1f))
            val activeLayer = editor.selected
            if (activeLayer != null) {
                AlightCutBar(editor, activeLayer)
                ResizeHandle(density) { panelHeight = it }
                EditPanel(editor, activeLayer, panelTab, { panelTab = it }, { editor.select(null) },
                    onRename = { renameTarget = activeLayer }, onParent = { parentPicker = true },
                    modifier = Modifier.fillMaxWidth().height(panelHeight.dp))
            }
            FloatingEditingDock(dockTool, activeLayer != null) { tool ->
                dockTool = tool
                when (tool) {
                    "Select" -> editor.select(null)
                    "Properties" -> if (activeLayer != null) panelTab = 0
                    "Graph" -> if (activeLayer != null) panelTab = 1
                    "Speed", "Volume", "Extract audio", "Masking", "Effects", "Blending", "Blend modes" -> {
                        if (activeLayer != null) showToolSheet = true
                        else Toast.makeText(ctx, "Select a layer first", Toast.LENGTH_SHORT).show()
                    }
                    "Text" -> editor.addText()
                    "Shapes" -> editor.addShape()
                    "Layers" -> showLayers = true
                    "Adjust" -> editor.addAdjustment()
                    "Import" -> showImport = true
                }
            }
        }
    }

    if (showToolSheet && editor.selected != null) {
        val target = editor.selected!!
        ModalBottomSheet(
            onDismissRequest = { showToolSheet = false },
            containerColor = KColors.Surface,
            dragHandle = { Box(Modifier.padding(vertical = 8.dp).size(width = 42.dp, height = 4.dp).clip(CircleShape).background(KColors.Muted)) }
        ) {
            DockToolControls(
                tool = dockTool, editor = editor, layer = target,
                onClose = { showToolSheet = false },
                onExtractAudio = { editor.extractAudio(target.id); showToolSheet = false }
            )
        }
    }
    if (showImport) {
        ModalBottomSheet(
            onDismissRequest = { showImport = false },
            containerColor = KColors.Surface,
            dragHandle = { Box(Modifier.padding(vertical = 8.dp).size(width = 42.dp, height = 4.dp).clip(CircleShape).background(KColors.Muted)) }
        ) {
            MediaImportSheet(
                onDismiss = { showImport = false },
                onImport = { item, trimIn, trimOut ->
                    scope.launch {
                        val info = withContext(Dispatchers.IO) { MediaLoader.probe(ctx, item.uri, item.type) }
                        editor.addMediaTrimmed(item.type, item.name, item.uri.toString(),
                            if (info.width > 0) info.width else item.width,
                            if (info.height > 0) info.height else item.height,
                            if (item.type == LayerType.IMAGE) null else item.duration,
                            trimIn, trimOut)
                    }
                }
            )
        }
    }
    if (showLayers) {
        ModalBottomSheet(
            onDismissRequest = { showLayers = false },
            containerColor = KColors.Surface,
            dragHandle = { Box(Modifier.padding(vertical = 8.dp).size(width = 42.dp, height = 4.dp).clip(CircleShape).background(KColors.Muted)) }
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Text("ADD LAYER", color = KColors.Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
                DialogItem("Null object") { showLayers = false; editor.addNull() }
                DialogItem("Adjustment layer") { showLayers = false; editor.addAdjustment() }
                DialogItem("Text") { showLayers = false; editor.addText() }
                DialogItem("Shape") { showLayers = false; editor.addShape() }
            }
        }
    }
    if (renameTarget != null) {
        val target = renameTarget!!
        TextInputDialog(
            title = "Rename layer", initial = target.name,
            onDone = { editor.renameLayer(target.id, it.trim().ifBlank { target.name }); renameTarget = null },
            onDismiss = { renameTarget = null }
        )
    }
    if (parentPicker) {
        val target = editor.selected
        ActionDialog("Set parent", { parentPicker = false }) {
            DialogItem("No parent") { target?.let { editor.setParent(it.id, null) }; parentPicker = false }
            editor.layers.filter { candidate ->
                candidate.id != target?.id && target != null && !isDescendantOf(candidate, target.id, editor.layers.associateBy { it.id })
            }.forEach { candidate ->
                DialogItem(candidate.name) { target?.let { editor.setParent(it.id, candidate.id) }; parentPicker = false }
            }
        }
    }
    if (showLeave) {
        AlertDialog(
            onDismissRequest = { showLeave = false },
            containerColor = KColors.Surface,
            title = { Text("Leave composition?", color = KColors.Text) },
            text = { Text("Compositions aren't saved yet, so this one will be discarded.", color = KColors.Muted) },
            confirmButton = { TextButton(onClick = { showLeave = false; onExit() }) { Text("Leave", color = KColors.Danger) } },
            dismissButton = { TextButton(onClick = { showLeave = false }) { Text("Stay", color = KColors.Muted) } }
        )
    }
}

@Composable
private fun TopBar(editor: EditorState, onMenuHome: () -> Unit, onOpenSettings: () -> Unit, onExport: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var resMenu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.Menu, "Menu", tint = KColors.Text) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = KColors.Surface2) {
                DropdownMenuItem(text = { Text("Home", color = KColors.Text) }, onClick = { menu = false; onMenuHome() })
                DropdownMenuItem(text = { Text("Preferences", color = KColors.Text) }, onClick = { menu = false; onOpenSettings() })
            }
        }
        Text(
            "Untitled", color = KColors.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).padding(start = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Box {
            val shape = RoundedCornerShape(10.dp)
            Row(
                Modifier.clip(shape).background(KColors.Surface).border(1.dp, KColors.Line, shape)
                    .clickable { resMenu = true }.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(AppSettings.RESOLUTIONS[editor.resolution], color = KColors.Text, fontSize = 14.sp)
                KIcon(IconKind.CHEVRON_DOWN, KColors.Muted, Modifier.size(18.dp))
            }
            DropdownMenu(expanded = resMenu, onDismissRequest = { resMenu = false }, containerColor = KColors.Surface2) {
                AppSettings.RESOLUTIONS.forEachIndexed { i, r ->
                    DropdownMenuItem(text = { Text(r, color = KColors.Text) }, onClick = { resMenu = false; editor.resolution = i })
                }
            }
        }
        IconButton(onClick = onExport) { KIcon(IconKind.PLAY, KColors.Text, Modifier.size(24.dp)) }
        IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Preferences", tint = KColors.Text) }
    }
}


@Composable
private fun FloatingEditingDock(active: String, hasSelection: Boolean, onSelect: (String) -> Unit) {
    // Keep the original floating capsule. Only its available actions change with selection.
    val tools = if (hasSelection) {
        listOf(
            "Properties" to IconKind.SLIDERS,
            "Speed" to IconKind.STOPWATCH,
            "Volume" to IconKind.VOLUME,
            "Extract audio" to IconKind.WAVEFORM,
            "Masking" to IconKind.MASK,
            "Effects" to IconKind.EFFECTS,
            "Blending" to IconKind.BLEND,
            "Blend modes" to IconKind.GRID,
            "Graph" to IconKind.GRID
        )
    } else {
        listOf(
            "Select" to IconKind.SELECT,
            "Text" to IconKind.TEXT,
            "Shapes" to IconKind.SHAPES,
            "Layers" to IconKind.LAYERS,
            "Adjust" to IconKind.ADJ
        )
    }
    val shape = RoundedCornerShape(30.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)
            .clip(shape).background(KColors.Surface)
            .border(1.dp, KColors.Line, shape)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tools.forEach { (label, icon) ->
            Column(
                Modifier.width(if (hasSelection) 68.dp else 62.dp)
                    .height(48.dp).clip(RoundedCornerShape(10.dp))
                    .clickable { onSelect(label) }.padding(vertical = 3.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                KIcon(icon, if (active == label) KColors.Accent else KColors.Muted, Modifier.size(20.dp))
                Text(label, color = if (active == label) KColors.Accent else KColors.Muted,
                    fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(Modifier.width(1.dp).height(34.dp).background(KColors.Line))
        Column(
            Modifier.padding(start = 8.dp, end = 2.dp).width(50.dp).height(48.dp)
                .clip(CircleShape).clickable { onSelect("Import") },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(KColors.Fab), contentAlignment = Alignment.Center) {
                KIcon(IconKind.PLUS, KColors.Text, Modifier.size(22.dp))
            }
            Text("Import", color = KColors.Muted, fontSize = 8.sp)
        }
    }
}
@Composable
private fun DockToolControls(
    tool: String, editor: EditorState, layer: Layer,
    onClose: () -> Unit, onExtractAudio: () -> Unit
) {
    var sliderEditing by remember(tool, layer.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
        Text(tool, color = KColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        when (tool) {
            "Speed" -> {
                Text("Playback speed  ·  ${String.format("%.2f×", layer.speed)}", color = KColors.Text, fontSize = 14.sp)
                Slider(value = layer.speed, onValueChange = { v -> if (!sliderEditing) { editor.beginEdit(); sliderEditing = true }; editor.updateLayer(layer.id, push = false) { it.copy(speed = v) } }, onValueChangeFinished = { sliderEditing = false }, valueRange = 0.1f..4f)
                Text("Applies to video preview and linked audio playback.", color = KColors.Muted, fontSize = 12.sp)
            }
            "Volume" -> {
                Text("Clip volume  ·  ${(layer.volume * 100).toInt()}%", color = KColors.Text, fontSize = 14.sp)
                Slider(value = layer.volume, onValueChange = { v -> if (!sliderEditing) { editor.beginEdit(); sliderEditing = true }; editor.updateLayer(layer.id, push = false) { it.copy(volume = v) } }, onValueChangeFinished = { sliderEditing = false }, valueRange = 0f..2f)
                Text("Audio layers use this level during playback.", color = KColors.Muted, fontSize = 12.sp)
            }
            "Extract audio" -> {
                Text(if (layer.type == LayerType.VIDEO) "Create a linked audio layer using this video's audio track." else "Select a video layer to extract its audio.", color = KColors.Muted, fontSize = 13.sp)
                DialogItem("Extract audio from video") { if (layer.type == LayerType.VIDEO) onExtractAudio() }
            }
            "Masking" -> {
                Text("Rectangular layer mask", color = KColors.Text, fontSize = 14.sp)
                DialogItem((if (layer.maskEnabled) "✓  " else "") + "Enable mask (80% frame)") { editor.updateLayer(layer.id) { it.copy(maskEnabled = !it.maskEnabled) } }
            }
            "Effects" -> {
                Text("Basic preview effect", color = KColors.Text, fontSize = 14.sp)
                DialogItem((if ("Grayscale" in layer.effects) "✓  " else "") + "Toggle grayscale") {
                    editor.updateLayer(layer.id) { it.copy(effects = if ("Grayscale" in it.effects) it.effects - "Grayscale" else it.effects + "Grayscale") }
                }
            }
            "Blending", "Blend modes" -> {
                if (tool == "Blending") {
                    val opacity = layer.p(PropId.OPACITY).valueAt(editor.time).coerceIn(0f, 100f)
                    Text("Opacity  ·  ${opacity.toInt()}%", color = KColors.Text, fontSize = 14.sp)
                    Slider(value = opacity, onValueChange = { if (!sliderEditing) { editor.beginEdit(); sliderEditing = true }; editor.setPropValue(layer.id, PropId.OPACITY, it) }, onValueChangeFinished = { sliderEditing = false }, valueRange = 0f..100f)
                }
                Text("Layer blend mode", color = KColors.Text, fontSize = 14.sp)
                listOf("Normal", "Multiply", "Screen", "Add").forEach { mode ->
                    DialogItem((if (layer.blendMode == mode) "✓  " else "") + mode) {
                        editor.updateLayer(layer.id) { it.copy(blendMode = mode) }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onClose) { Text("Done", color = KColors.Accent) }
    }
}

@Composable
private fun ResizeHandle(density: Float, onHeight: (Float) -> Unit) {
    var current by remember { mutableFloatStateOf(210f) }
    Box(Modifier.fillMaxWidth().height(9.dp).background(KColors.Bg).pointerInput(Unit) {
        detectVerticalDragGestures { change, drag -> change.consume(); current = (current - drag / density).coerceIn(130f, 360f); onHeight(current) }
    }, contentAlignment = Alignment.Center) {
        Box(Modifier.width(42.dp).height(4.dp).clip(CircleShape).background(KColors.Muted))
    }
}

@Composable
private fun AlightCutBar(editor: EditorState, layer: Layer) {
    val inside = editor.time > layer.start + editor.frame && editor.time < layer.end - editor.frame
    Row(Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        if (inside) {
            CutAction("Split", IconKind.SPLIT, Modifier.weight(1f)) { editor.splitSelected() }
            CutAction("Trim start", IconKind.CUT, Modifier.weight(1f)) { editor.trimLeft(layer.id, editor.time - layer.start) }
            CutAction("Trim end", IconKind.CUT, Modifier.weight(1f)) { editor.moveLayerEndToPlayhead(layer.id) }
        } else if (editor.time < layer.start) {
            CutAction("Move to playhead", IconKind.PREV, Modifier.weight(1f)) { editor.moveLayerStartToPlayhead(layer.id) }
        } else {
            CutAction("Extend to playhead", IconKind.NEXT, Modifier.weight(1f)) { editor.extendLayerToPlayhead(layer.id) }
        }
        CutAction("Duplicate", IconKind.COPY, Modifier.weight(1f)) { editor.duplicate(layer.id) }
        CutAction("Delete", IconKind.DELETE, Modifier.weight(1f)) { editor.deleteLayer(layer.id) }
    }
}

@Composable
private fun CutAction(label: String, icon: IconKind, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(modifier.height(34.dp).clip(RoundedCornerShape(8.dp)).background(KColors.Surface2).clickable(onClick = onClick).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        KIcon(icon, KColors.Text, Modifier.size(15.dp))
        Spacer(Modifier.width(3.dp))
        Text(label, color = KColors.Text, fontSize = 9.sp, maxLines = 1)
    }
}

@Composable
private fun AlightDock(active: String, onSelect: (String) -> Unit) {
    val tools = listOf(
        "Properties" to IconKind.SLIDERS, "Speed" to IconKind.STOPWATCH,
        "Volume" to IconKind.VOLUME, "Extract audio" to IconKind.WAVEFORM,
        "Masking" to IconKind.MASK, "Effects" to IconKind.EFFECTS,
        "Blending & opacity" to IconKind.BLEND, "Blend modes" to IconKind.GRID
    )
    Row(Modifier.fillMaxWidth().height(64.dp).background(KColors.Surface).horizontalScroll(rememberScrollState()).padding(horizontal = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        tools.forEach { (label, icon) ->
            Column(Modifier.width(72.dp).fillMaxHeight().clip(RoundedCornerShape(7.dp)).clickable { onSelect(label) }.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                KIcon(icon, if (active == label) KColors.Accent else KColors.Muted, Modifier.size(19.dp))
                Text(label, color = if (active == label) KColors.Accent else KColors.Muted, fontSize = 7.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun PreviewToolsBar(editor: EditorState, onAddLayer: () -> Unit, onTogglePreview: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
        PreviewTool(IconKind.UNDO, editor.canUndo) { editor.undo() }
        PreviewTool(IconKind.REDO, editor.canRedo) { editor.redo() }
        PreviewTool(IconKind.BACK) { editor.playing = false; editor.seek(0f) }
        PreviewTool(if (editor.playing) IconKind.PAUSE else IconKind.PLAY) { editor.playing = !editor.playing }
        PreviewTool(IconKind.NEXT) { editor.playing = false; editor.seek(editor.duration) }
        PreviewTool(IconKind.PLUS, onClick = onAddLayer)
        PreviewTool(if (editor.playing) IconKind.FIT else IconKind.FULLSCREEN, onClick = onTogglePreview)
    }
}

@Composable
private fun PreviewTool(icon: IconKind, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.size(42.dp).alpha(if (enabled) 1f else 0.35f).clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        KIcon(icon, KColors.Text, Modifier.size(23.dp))
    }
}

@Composable
private fun EditPanel(
    editor: EditorState, layer: Layer, tab: Int, onTab: (Int) -> Unit, onCollapse: () -> Unit,
    onRename: () -> Unit, onParent: () -> Unit, modifier: Modifier = Modifier
) {
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
    Column(
        modifier.clip(shape).background(KColors.Surface).border(1.dp, KColors.Line, shape)
    ) {
        Row(Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TabLabel("Properties", tab == 0) { onTab(0) }
            TabLabel("Graph", tab == 1) { onTab(1) }
            Spacer(Modifier.weight(1f))
            Text(layer.name, color = KColors.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 100.dp))
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Layer actions", tint = KColors.Muted) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = KColors.Surface2) {
                    DropdownMenuItem(text = { Text(if (layer.type == LayerType.TEXT) "Edit text" else "Rename", color = KColors.Text) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Duplicate", color = KColors.Text) }, onClick = { menu = false; editor.duplicate(layer.id) })
                    if (editor.time > layer.start + editor.frame && editor.time < layer.end - editor.frame) {
                        DropdownMenuItem(text = { Text("Split at playhead", color = KColors.Text) }, onClick = { menu = false; editor.splitSelected() })
                        DropdownMenuItem(text = { Text("Trim end to playhead", color = KColors.Text) }, onClick = { menu = false; editor.moveLayerEndToPlayhead(layer.id) })
                    }
                    if (editor.time < layer.start) {
                        DropdownMenuItem(text = { Text("Move layer to playhead", color = KColors.Text) }, onClick = { menu = false; editor.moveLayerStartToPlayhead(layer.id) })
                    }
                    if (editor.time > layer.end) {
                        DropdownMenuItem(text = { Text("Extend to playhead", color = KColors.Text) }, onClick = { menu = false; editor.extendLayerToPlayhead(layer.id) })
                    }
                    DropdownMenuItem(text = { Text("Parent", color = KColors.Text) }, onClick = { menu = false; onParent() })
                    DropdownMenuItem(text = { Text("Delete", color = KColors.Danger) }, onClick = { menu = false; editor.deleteLayer(layer.id) })
                }
            }
            IconButton(onClick = onCollapse) { KIcon(IconKind.CHEVRON_DOWN, KColors.Muted, Modifier.size(22.dp)) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (tab == 0) PropertiesList(editor, layer, onParent) else GraphPanel(editor, layer)
        }
    }
}

@Composable
private fun BottomToolbar(
    onSelect: () -> Unit, onText: () -> Unit, onShapes: () -> Unit,
    onLayers: () -> Unit, onAdjust: () -> Unit, onAdd: () -> Unit
) {
    val shape = RoundedCornerShape(30.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)
            .clip(shape).background(KColors.Surface).border(1.dp, KColors.Line, shape).padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToolItem("Select", IconKind.SELECT, Modifier.weight(1f), onSelect)
        ToolItem("Text", IconKind.TEXT, Modifier.weight(1f), onText)
        ToolItem("Shapes", IconKind.SHAPES, Modifier.weight(1f), onShapes)
        ToolItem("Layers", IconKind.LAYERS, Modifier.weight(1f), onLayers)
        ToolItem("Adjust", IconKind.SLIDERS, Modifier.weight(1f), onAdjust)
        Box(Modifier.width(1.dp).height(36.dp).background(KColors.Line))
        Box(
            Modifier.padding(start = 8.dp, end = 2.dp).size(50.dp).clip(CircleShape).background(KColors.Fab).clickable(onClick = onAdd),
            contentAlignment = Alignment.Center
        ) { KIcon(IconKind.PLUS, KColors.Text, Modifier.size(26.dp)) }
    }
}

@Composable
private fun ToolItem(label: String, icon: IconKind, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        KIcon(icon, KColors.ClipText, Modifier.size(24.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, color = KColors.Muted, fontSize = 10.sp, maxLines = 1)
    }
}
