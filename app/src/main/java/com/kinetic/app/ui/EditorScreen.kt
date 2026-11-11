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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
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
import com.kinetic.app.model.isDescendantOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun EditorScreen(editor: EditorState, onExit: () -> Unit, onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var showImport by remember { mutableStateOf(false) }
    var showLayers by remember { mutableStateOf(false) }
    var showLeave by remember { mutableStateOf(false) }

    fun leave() { if (editor.layers.isEmpty()) onExit() else showLeave = true }
    BackHandler { leave() }

    val audio = remember { AudioEngine(ctx, scope) }
    DisposableEffect(Unit) { onDispose { audio.release(); MediaLoader.releaseAll() } }
    val projectStore = remember { ProjectStore(ctx) }
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

        PreviewCanvas(editor, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        TransportBar(editor)
        TimelinePane(editor, Modifier.weight(1f))

        BottomToolbar(
            onSelect = { editor.select(null) },
            onText = { editor.addText() },
            onShapes = { editor.addShape() },
            onLayers = { showLayers = true },
            onAdjust = { editor.addAdjustment() },
            onAdd = { showImport = true }
        )
    }

    if (showImport) {
        ActionDialog("Import media", { showImport = false }) {
            DialogItem("Video") { showImport = false; pickVideo.launch(arrayOf("video/*")) }
            DialogItem("Image") { showImport = false; pickImage.launch(arrayOf("image/*")) }
            DialogItem("Audio") { showImport = false; pickAudio.launch(arrayOf("audio/*")) }
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
private fun TransportBar(editor: EditorState) {
    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f)) {
            Text(editor.settings.formatTime(editor.time), color = KColors.Text, fontSize = 15.sp)
            Text(" / ${editor.settings.formatTime(editor.duration)}", color = KColors.Muted, fontSize = 15.sp)
        }
        Box(
            Modifier.size(44.dp).clip(CircleShape).clickable { editor.playing = !editor.playing },
            contentAlignment = Alignment.Center
        ) { KIcon(if (editor.playing) IconKind.PAUSE else IconKind.PLAY, KColors.Text, Modifier.size(26.dp)) }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).alpha(if (editor.canUndo) 1f else 0.35f).clickable(enabled = editor.canUndo) { editor.undo() },
                contentAlignment = Alignment.Center
            ) { KIcon(IconKind.UNDO, KColors.Text, Modifier.size(24.dp)) }
            Box(
                Modifier.size(44.dp).alpha(if (editor.canRedo) 1f else 0.35f).clickable(enabled = editor.canRedo) { editor.redo() },
                contentAlignment = Alignment.Center
            ) { KIcon(IconKind.REDO, KColors.Text, Modifier.size(24.dp)) }
        }
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
                    DropdownMenuItem(text = { Text("Split at playhead", color = KColors.Text) }, onClick = { menu = false; editor.splitSelected() })
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
