package com.kinetic.app.ui

import android.net.Uri
import android.app.ActivityManager
import android.content.Context
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.snapshotFlow
import android.os.SystemClock
import com.kinetic.app.engine.NativePlaybackEngine
import com.kinetic.app.engine.KreEngine
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.kinetic.app.data.MediaWorkers
import com.kinetic.app.data.PreviewDiagnostics
import com.kinetic.app.data.ProjectStore
import com.kinetic.app.model.COMP_H
import com.kinetic.app.model.COMP_W
import com.kinetic.app.model.EditorState
import com.kinetic.app.model.Layer
import com.kinetic.app.model.LayerType
import com.kinetic.app.model.PropId
import com.kinetic.app.model.isDescendantOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun EditorScreen(editor: EditorState, onExit: () -> Unit, onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    var showImport by remember { mutableStateOf(false) }
    var showLayers by remember { mutableStateOf(false) }
    var showLeave by remember { mutableStateOf(false) }
    var panelTab by remember { mutableIntStateOf(0) }
    var showPropertiesPanel by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Layer?>(null) }
    var parentPicker by remember { mutableStateOf(false) }
    var previewExpanded by remember { mutableStateOf(false) }
    var previewHeight by remember { mutableFloatStateOf(210f) }
    var dockTool by remember { mutableStateOf("Select") }
    var showToolSheet by remember { mutableStateOf(false) }
    LaunchedEffect(editor.selectedId) {
        showToolSheet = false
        if (editor.selectedId == null) {
            showPropertiesPanel = false
            dockTool = "Select"
        } else {
            // Selection and panel visibility are deliberately independent.
            dockTool = "Properties"
        }
    }

    fun leave() { if (editor.layers.isEmpty()) onExit() else showLeave = true }

    // Full-clip disk prewarming is intentionally disabled. Timeline rows
    // request only their visible representative thumbnails; scanning hundreds
    // of frames after import can hold the same retriever lock as live preview.
    // Playback starts immediately from original media; thumbnail caching is
    // independent and does not block the transport.
    fun togglePlayback() {
        editor.playing = !editor.playing
    }
    BackHandler { leave() }
    BackHandler(enabled = previewExpanded) { previewExpanded = false }

    val audio = remember { AudioEngine(ctx) }
    val playback = remember { NativePlaybackEngine() }
    DisposableEffect(Unit) {
        onDispose {
            playback.release()
            audio.release()
            MediaLoader.releaseAll()
        }
    }
    DisposableEffect(ctx) {
        val callbacks = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                MediaLoader.trimMemory(level)
                when {
                    level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                        KreEngine.clearCalculationCache()
                        KreEngine.setCalculationCacheBudget(64 * 1024)
                    }
                    level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                        // LRU residency may have changed; discard the old
                        // working-set indicator and rebuild it after pressure.
                        KreEngine.setCalculationCacheBudget(64 * 1024)
                    }
                }
            }

            override fun onLowMemory() {
                MediaLoader.trimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
                KreEngine.clearCalculationCache()
                KreEngine.setCalculationCacheBudget(64 * 1024)
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit
        }
        ctx.applicationContext.registerComponentCallbacks(callbacks)
        onDispose { ctx.applicationContext.unregisterComponentCallbacks(callbacks) }
    }
    val projectStore = remember { ProjectStore(ctx) }
    LaunchedEffect(editor.playing) {
        KreEngine.setCalculationCacheBudget(
            if (MediaWorkers.isConstrainedDevice) 512 * 1024 else 2 * 1024 * 1024
        )
    }
    LaunchedEffect(editor.layers.map { it.id to it.uri }) { audio.prepare(editor.layers) }

    // Preview frames are prepared once during media import and read from disk.

    LaunchedEffect(editor.layers, editor.duration, editor.resolution, editor.projectName) {
        delay(400)
        withContext(Dispatchers.IO) { runCatching { projectStore.save(editor) } }
    }

    LaunchedEffect(editor.playing, editor.duration, editor.settings.loop, editor.fps) {
        if (editor.playing) {
            if (editor.time >= editor.duration - 0.01f) editor.time = 0f
            playback.configure(editor.duration, editor.settings.loop, 1f)
            playback.seek(editor.time)
            playback.play()
            PreviewDiagnostics.resetSession()

            // Keep the native clock and audio at full precision, but cap Compose
            // playhead publication at 30 Hz. Publishing 60-120 UI states per
            // second needlessly recomposes the editor and timeline on low-RAM
            // phones; video output is scheduled directly from the native clock.
            val uiFps = editor.fps.coerceIn(1, 30)
            var publishedFrame = (editor.time * uiFps).toInt()
            var publishedGeneration = playback.clockSnapshot().generation
            var lastAudioSyncMs = 0L
            audio.sync(editor.layers, editor.time, true)

            while (editor.playing) {
                delay(16L)
                // Sample frame position and transport time from one clock snapshot.
                val clock = playback.clockSnapshot()
                val elapsedSinceSnapshot = if (clock.playing) {
                    ((System.nanoTime() - clock.monotonicNs).coerceAtLeast(0L) /
                        1_000_000_000.0 * clock.speed).toFloat()
                } else 0f
                val transportTime = ((clock.positionUs / 1_000_000.0).toFloat() + elapsedSinceSnapshot)
                    .coerceIn(0f, editor.duration.coerceAtLeast(0f))
                val nextFrame = (transportTime * uiFps).toInt().coerceIn(0, Int.MAX_VALUE)

                // Ignore small backwards samples inside one clock generation.
                // A generation change represents an intentional seek or loop wrap.
                if (nextFrame != publishedFrame &&
                    (nextFrame > publishedFrame || clock.generation != publishedGeneration)
                ) {
                    editor.time = (nextFrame / uiFps.toFloat()).coerceAtMost(editor.duration)
                    publishedFrame = nextFrame
                }
                publishedGeneration = clock.generation

                val now = SystemClock.elapsedRealtime()
                if (now - lastAudioSyncMs >= 33L) {
                    audio.sync(editor.layers, transportTime, playback.isPlaying())
                    lastAudioSyncMs = now
                }
            }

            val completed = editor.playing && !playback.isPlaying() && !editor.settings.loop
            if (completed) {
                editor.time = editor.duration
                editor.playing = false
            }
            audio.sync(editor.layers, editor.time, false)
        } else {
            playback.pause()
            audio.pauseAll()
        }
    }

    fun importUris(uris: List<Uri>, type: LayerType) {
        scope.launch {
            for (u in uris) {
                runCatching { ctx.contentResolver.takePersistableUriPermission(u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                // Keep the provider URI as the source. Import only reads metadata;
                // it does not copy the file or pre-render/cache video frames.
                val info = withContext(MediaWorkers.cpuDispatcher) { MediaLoader.probe(ctx, u, type) }
                val name = MediaLoader.displayName(ctx, u)
                // Initialize the embedded audio decoder before the clip becomes
                // visible on the timeline. Clips without an audio stream continue normally.
                if (type == LayerType.VIDEO || type == LayerType.AUDIO) {
                    audio.prepareSource(u.toString())
                }
                editor.addMedia(type, name, u.toString(), info.width, info.height, info.length)
            }
        }
    }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { importUris(it, LayerType.VIDEO) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { importUris(it, LayerType.IMAGE) }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { importUris(it, LayerType.AUDIO) }


    BoxWithConstraints(Modifier.fillMaxSize().background(KColors.Bg)) {
        val windowMaxHeight = maxHeight
        val compactHeight = windowMaxHeight < 520.dp
        val maxPreviewHeight = (windowMaxHeight.value * if (compactHeight) 0.30f else 0.48f).coerceAtLeast(100f)
        val adaptivePreviewHeight = previewHeight.coerceIn(100f, maxPreviewHeight)
        Column(Modifier.widthIn(max = 1440.dp).fillMaxSize().align(Alignment.Center)
            .statusBarsPadding().navigationBarsPadding()) {
        TopBar(editor, onMenuHome = { leave() }, onOpenSettings = {
            editor.playing = false; onOpenSettings()
        }, onExport = { Toast.makeText(ctx, "Export isn't built yet", Toast.LENGTH_SHORT).show() })

        if (previewExpanded) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
            PreviewCanvas(editor, Modifier.fillMaxSize(), editor.settings.previewQualityIndex, playback = playback)
            PreviewQualityControl(editor, Modifier.align(Alignment.TopStart).padding(8.dp))
                PreviewDiagnosticsBadge(editor.playing, Modifier.align(Alignment.BottomEnd).padding(8.dp))
            }
        } else {
            val visiblePreviewHeight = if (showPropertiesPanel) minOf(adaptivePreviewHeight, 150f) else adaptivePreviewHeight
            Box(Modifier.fillMaxWidth().height(visiblePreviewHeight.dp)) {
                PreviewCanvas(editor, Modifier.fillMaxSize(), editor.settings.previewQualityIndex, playback = playback)
                PreviewQualityControl(editor, Modifier.align(Alignment.TopStart).padding(8.dp))
                PreviewDiagnosticsBadge(editor.playing, Modifier.align(Alignment.BottomEnd).padding(8.dp))
                }
            Box(Modifier.fillMaxWidth().height(10.dp).background(KColors.Bg).pointerInput(Unit) {
                detectVerticalDragGestures { change, drag -> change.consume(); previewHeight = (previewHeight + drag / density).coerceIn(100f, maxPreviewHeight) }
            }, contentAlignment = Alignment.Center) {
                Box(Modifier.width(44.dp).height(4.dp).clip(CircleShape).background(KColors.Muted))
            }
        }
        PreviewToolsBar(
            editor,
            isExpanded = previewExpanded,
            onAddLayer = { showLayers = true },
            onTogglePreview = { previewExpanded = !previewExpanded },
            onPlay = { togglePlayback() }
        )
        if (!previewExpanded) {
            TimelinePane(editor, Modifier.weight(1f))
            val activeLayer = editor.selected
            if (activeLayer != null) {
                AlightCutBar(editor, activeLayer)
                if (showPropertiesPanel && !compactHeight) {
                    // A stable shared workspace height keeps Properties and Graph usable without manual resizing.
                    val adaptivePanelHeight = (windowMaxHeight.value * 0.25f).coerceIn(176f, 218f)
                    EditPanel(editor, activeLayer, panelTab, { panelTab = it }, { showPropertiesPanel = false },
                        onRename = { renameTarget = activeLayer }, onParent = { parentPicker = true },
                        modifier = Modifier.fillMaxWidth().height(adaptivePanelHeight.dp))
                }
            }
            FloatingEditingDock(
                dockTool,
                activeLayer != null,
                activeLayer?.props?.values?.any { prop -> prop.keys.isNotEmpty() } == true
            ) { tool ->
                dockTool = tool
                when (tool) {
                    "Select" -> editor.select(null)
                    "Properties" -> if (activeLayer != null) { panelTab = 0; showPropertiesPanel = true }
                    "Graph" -> if (activeLayer != null) { panelTab = 1; showPropertiesPanel = true }
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
                    // Finish media inspection and audio decoder preparation before
                    // the clip is inserted into the timeline.
                    runCatching {
                        ctx.contentResolver.takePersistableUriPermission(
                            item.uri,
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                    val info = withContext(MediaWorkers.cpuDispatcher) {
                        MediaLoader.probe(ctx, item.uri, item.type)
                    }
                    if (item.type == LayerType.VIDEO || item.type == LayerType.AUDIO) {
                        audio.prepareSource(item.uri.toString())
                    }
                    editor.addMediaTrimmed(item.type, item.name, item.uri.toString(),
                        if (info.width > 0) info.width else item.width,
                        if (info.height > 0) info.height else item.height,
                        if (item.type == LayerType.IMAGE) null else item.duration,
                        trimIn, trimOut)
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
            IconButton(onClick = { menu = true }) { KIcon(IconKind.MENU, KColors.Text) }
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
        IconButton(onClick = onOpenSettings) { KIcon(IconKind.SETTINGS, KColors.Text) }
    }
}


@Composable
private fun FloatingEditingDock(active: String, hasSelection: Boolean, hasGraphKeys: Boolean, onSelect: (String) -> Unit) {
    // Keep Import inside the capsule, but visually separate it from the tools.
    val tools = if (hasSelection) {
        listOf(
            "Properties" to IconKind.SLIDERS,
            "Speed" to IconKind.STOPWATCH,
            "Volume" to IconKind.VOLUME,
            "Extract audio" to IconKind.WAVEFORM,
            "Masking" to IconKind.MASK,
            "Effects" to IconKind.EFFECTS,
            "Blending" to IconKind.BLEND,
            "Blend modes" to IconKind.GRID
        ) + if (hasGraphKeys) listOf("Graph" to IconKind.GRAPH) else emptyList()
    } else {
        listOf(
            "Select" to IconKind.SELECT,
            "Text" to IconKind.TEXT,
            "Shapes" to IconKind.SHAPES,
            "Layers" to IconKind.LAYERS,
            "Adjust" to IconKind.ADJ
        )
    }
    val shape = RoundedCornerShape(32.dp)
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            Modifier.widthIn(max = 720.dp).fillMaxWidth().height(72.dp)
                .clip(shape)
                .background(KColors.Row)
                .border(1.dp, KColors.Line, shape)
                .padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier.weight(1f).fillMaxHeight()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                tools.forEach { (label, icon) ->
                    Column(
                        Modifier.width(72.dp)
                            .height(54.dp).clip(RoundedCornerShape(11.dp))
                            .clickable { onSelect(label) }
                            .padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        KIcon(icon, if (active == label) KColors.Accent else KColors.Muted, Modifier.size(22.dp))
                        Text(
                            label,
                            color = if (active == label) KColors.Accent else KColors.Muted,
                            fontSize = 9.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Box(
                Modifier.padding(horizontal = 7.dp)
                    .width(1.dp).height(42.dp)
                    .background(KColors.Line)
            )
            Box(
                Modifier.padding(start = 2.dp)
                    .size(54.dp).clip(CircleShape)
                    .background(KColors.Fab)
                    .border(1.dp, KColors.Line, CircleShape)
                    .clickable { onSelect("Import") },
                contentAlignment = Alignment.Center
            ) {
                KIcon(IconKind.PLUS, KColors.Text, Modifier.size(27.dp))
            }
        }
    }
}
@Composable
private fun DockToolControls(
    tool: String, editor: EditorState, layer: Layer,
    onClose: () -> Unit, onExtractAudio: () -> Unit
) {
    val displayTime = rememberEditorDisplayTime(editor)
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
                    val opacity = layer.p(PropId.OPACITY).valueAt(displayTime).coerceIn(0f, 100f)
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
private fun rememberEditorDisplayTime(editor: EditorState): Float {
    val displayed = remember(editor) { mutableFloatStateOf(0f) }
    LaunchedEffect(editor, editor.playing) {
        displayed.floatValue = editor.time
        // Freeze secondary property/control UI while playing. The preview has
        // its own draw-phase clock; these controls catch up immediately on pause.
        if (!editor.playing) {
            snapshotFlow { editor.time }.collect { displayed.floatValue = it }
        }
    }
    return displayed.floatValue
}

@Composable
private fun AlightCutBar(editor: EditorState, layer: Layer) {
    val displayTime = rememberEditorDisplayTime(editor)
    val inside = displayTime > layer.start + editor.frame && displayTime < layer.end - editor.frame
    Row(Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        if (inside) {
            CutAction("Split", IconKind.SPLIT, Modifier.weight(1f)) { editor.splitSelected() }
            CutAction("Trim start", IconKind.CUT, Modifier.weight(1f)) { editor.trimLeft(layer.id, editor.time - layer.start) }
            CutAction("Trim end", IconKind.CUT, Modifier.weight(1f)) { editor.moveLayerEndToPlayhead(layer.id) }
        } else if (displayTime < layer.start) {
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
private fun PreviewToolsBar(
    editor: EditorState,
    isExpanded: Boolean,
    onAddLayer: () -> Unit,
    onTogglePreview: () -> Unit,
    onPlay: () -> Unit
) {
    // Transport skip controls step through the selected property's keyframes.
    val selectedLayer = editor.selected
    val selectedProp = editor.selectedProp
    val selectedKeyframes = selectedLayer?.let { layer ->
        if (selectedProp != null) layer.p(selectedProp).keys.map { selectedProp to it }
        else layer.props.flatMap { (id, prop) -> prop.keys.map { id to it } }
    }.orEmpty().sortedBy { it.second.t }
    val keyframeTolerance = editor.frame / 2f
    val canSkipPrevious = selectedKeyframes.any { it.second.t < editor.time - keyframeTolerance } ||
        (selectedLayer != null && editor.time > selectedLayer.start + keyframeTolerance) ||
        editor.time > keyframeTolerance
    val canSkipNext = selectedKeyframes.any { it.second.t > editor.time + keyframeTolerance } ||
        (selectedLayer != null && editor.time < selectedLayer.end - keyframeTolerance) ||
        editor.time < editor.duration - keyframeTolerance

    // Undo/redo stay on the left; Add Layer and fullscreen controls are grouped
    // on the right. Transport remains centered against the full preview width.
    Box(Modifier.fillMaxWidth().height(48.dp)) {
        Row(
            Modifier.align(Alignment.CenterStart).padding(start = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            PreviewTool(IconKind.UNDO, editor.canUndo) { editor.undo() }
            PreviewTool(IconKind.REDO, editor.canRedo) { editor.redo() }
        }
        Row(
            Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            PreviewTool(IconKind.BACK, canSkipPrevious) {
                editor.playing = false
                val layer = editor.selected
                val previous = selectedKeyframes.lastOrNull { it.second.t < editor.time - keyframeTolerance }
                when {
                    previous != null && layer != null -> {
                        val (id, key) = previous
                        editor.selectProp(id)
                        editor.selectKey(id, layer.p(id).keys.indexOf(key), key.t)
                    }
                    layer != null && editor.time > layer.start + keyframeTolerance -> editor.seek(layer.start)
                    else -> editor.seek(0f)
                }
            }
            PreviewTool(if (editor.playing) IconKind.PAUSE else IconKind.PLAY, onClick = onPlay)
            PreviewTool(IconKind.NEXT, canSkipNext) {
                editor.playing = false
                val layer = editor.selected
                val next = selectedKeyframes.firstOrNull { it.second.t > editor.time + keyframeTolerance }
                when {
                    next != null && layer != null -> {
                        val (id, key) = next
                        editor.selectProp(id)
                        editor.selectKey(id, layer.p(id).keys.indexOf(key), key.t)
                    }
                    layer != null && editor.time < layer.end - keyframeTolerance -> editor.seek(layer.end)
                    else -> editor.seek(editor.duration)
                }
            }
        }
        Row(
            Modifier.align(Alignment.CenterEnd).padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            PreviewTool(IconKind.PLUS, onClick = onAddLayer)
            PreviewTool(if (isExpanded) IconKind.FIT else IconKind.FULLSCREEN, onClick = onTogglePreview)
        }
    }
}

@Composable
private fun PreviewDiagnosticsBadge(playing: Boolean, modifier: Modifier = Modifier) {
    var snapshot by remember { mutableStateOf(PreviewDiagnostics.snapshot()) }
    LaunchedEffect(playing) {
        if (!playing) {
            snapshot = PreviewDiagnostics.snapshot()
            return@LaunchedEffect
        }
        while (true) {
            snapshot = PreviewDiagnostics.snapshot()
            delay(500)
        }
    }
    if (playing) {
        val previewLabel = when {
            snapshot.surfaceActive -> {
                "SURFACE ${snapshot.surfaceFps.toInt()}fps · frames ${snapshot.surfaceFrames}"
            }
            snapshot.cpuDrawFps > 0f -> {
                "CPU ${snapshot.cpuDrawFps.toInt()}fps · draw ${"%.1f".format(snapshot.cpuDrawMs)}ms · dec ${"%.1f".format(snapshot.cpuDecodeMs)}ms · q ${"%.1f".format(snapshot.cpuQueueMs)}ms · RAM hit ${snapshot.cpuRamHitPercent}%"
            }
            snapshot.glFps > 0f -> {
                "GL ${snapshot.glFps.toInt()}fps · submit ${"%.1f".format(snapshot.glSubmitMs)}ms"
            }
            else -> "CPU preview starting"
        }
        Text(
            "$previewLabel · dropped ${snapshot.droppedVideoFrames}",
            modifier = modifier.clip(RoundedCornerShape(7.dp))
                .background(KColors.Row.copy(alpha = 0.92f))
                .padding(horizontal = 8.dp, vertical = 5.dp),
            color = KColors.Muted,
            fontSize = 9.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun PreviewQualityControl(editor: EditorState, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Box(
            modifier.size(54.dp, 30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(KColors.Row.copy(alpha = 0.92f))
                .border(1.dp, KColors.Line, RoundedCornerShape(8.dp))
                .clickable { expanded = true },
            contentAlignment = Alignment.Center
        ) {
            Text(
                AppSettings.PREVIEW_QUALITIES[editor.settings.previewQualityIndex].uppercase(),
                color = KColors.Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, containerColor = KColors.Surface2) {
            AppSettings.PREVIEW_QUALITIES.forEachIndexed { index, label ->
                DropdownMenuItem(
                    text = { Text(label, color = if (index == editor.settings.previewQualityIndex) KColors.Accent else KColors.Text) },
                    onClick = { expanded = false; editor.settings.updatePreviewQuality(index) }
                )
            }
        }
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
    val displayTime = rememberEditorDisplayTime(editor)
    var menu by remember { mutableStateOf(false) }
    val activeProp = editor.selectedProp
    val hasLayerKeys = layer.props.values.any { it.keys.isNotEmpty() }
    val hasKey = activeProp?.let { prop ->
        layer.p(prop).keys.any { kotlin.math.abs(it.t - editor.time) <= editor.frame / 2f }
    } == true
    val shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
    Column(modifier.clip(shape).background(KColors.Surface).border(1.dp, KColors.Line, shape)) {
        Row(Modifier.fillMaxWidth().height(38.dp).padding(start = 13.dp, end = 5.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(layer.name, color = KColors.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                    KIcon(IconKind.MORE, KColors.Muted, Modifier.size(18.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = KColors.Surface2) {
                    DropdownMenuItem(text = { Text(if (layer.type == LayerType.TEXT) "Edit text" else "Rename", color = KColors.Text) },
                        onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Duplicate", color = KColors.Text) },
                        onClick = { menu = false; editor.duplicate(layer.id) })
                    if (displayTime > layer.start + editor.frame && displayTime < layer.end - editor.frame) {
                        DropdownMenuItem(text = { Text("Split at playhead", color = KColors.Text) },
                            onClick = { menu = false; editor.splitSelected() })
                    }
                    DropdownMenuItem(text = { Text("Parent", color = KColors.Text) },
                        onClick = { menu = false; onParent() })
                    DropdownMenuItem(text = { Text("Delete", color = KColors.Danger) },
                        onClick = { menu = false; editor.deleteLayer(layer.id) })
                }
            }
            Box(Modifier.size(32.dp).clickable(onClick = onCollapse), contentAlignment = Alignment.Center) {
                KIcon(IconKind.CLOSE, KColors.Muted, Modifier.size(16.dp))
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.width(44.dp).fillMaxHeight().background(KColors.Bg)
                .padding(vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                RailAction(IconKind.SLIDERS, tab == 0) { onTab(0) }
                if (hasLayerKeys) RailAction(IconKind.GRAPH, tab == 1) { onTab(1) }
                Box(Modifier.padding(vertical = 5.dp).width(24.dp).height(1.dp).background(KColors.Line))
                RailAction(if (hasKey) IconKind.DIAMOND_FILLED else IconKind.DIAMOND, hasKey, enabled = activeProp != null) {
                    activeProp?.let { editor.toggleKeyAtTime(layer.id, it) }
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (tab == 0 || !hasLayerKeys) PropertiesList(editor, layer, onParent) else GraphPanel(editor, layer)
            }
        }
    }
}

@Composable
private fun RailAction(icon: IconKind, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.padding(vertical = 2.dp).size(34.dp).alpha(if (enabled) 1f else 0.32f)
        .clip(RoundedCornerShape(7.dp))
        .background(if (selected) KColors.Accent.copy(alpha = 0.14f) else Color.Transparent)
        .clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        KIcon(icon, if (selected && enabled) KColors.Accent else KColors.Muted, Modifier.size(18.dp))
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