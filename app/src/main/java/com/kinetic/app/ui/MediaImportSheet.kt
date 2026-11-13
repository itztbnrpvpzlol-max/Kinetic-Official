package com.kinetic.app.ui

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.media3.ui.PlayerView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.kinetic.app.data.MediaLoader
import com.kinetic.app.model.LayerType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ImportMediaEntry(
    val uri: Uri,
    val name: String,
    val type: LayerType,
    val duration: Float,
    val width: Int,
    val height: Int,
    val folder: String
)

@Composable
fun MediaImportSheet(
    onDismiss: () -> Unit,
    onImport: suspend (ImportMediaEntry, Float, Float) -> Unit
) {
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(player) { onDispose { player.release() } }
    var tab by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf<List<ImportMediaEntry>>(emptyList()) }
    var selected by remember { mutableStateOf<ImportMediaEntry?>(null) }
    var trim by remember { mutableStateOf(0f..5f) }
    var playingAudio by remember { mutableStateOf<String?>(null) }
    var videoIsPlaying by remember { mutableStateOf(false) }
    var folderMode by remember { mutableStateOf(false) }
    var folder by remember { mutableStateOf<String?>(null) }
    var hasPermission by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> hasPermission = result.values.any { it } }

    LaunchedEffect(tab) {
        val required = if (Build.VERSION.SDK_INT >= 34) {
            if (tab == 0) arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            ) else arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        } else if (Build.VERSION.SDK_INT >= 33) {
            if (tab == 0) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            else arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        } else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        hasPermission = required.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        if (!hasPermission) permissionLauncher.launch(required)
    }

    LaunchedEffect(tab, hasPermission) {
        if (!hasPermission) return@LaunchedEffect
        entries = withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val found = mutableListOf<ImportMediaEntry>()
            fun read(uri: Uri, projection: Array<String>, type: LayerType, hasDuration: Boolean) {
                runCatching {
                    resolver.query(uri, projection, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cursor ->
                        val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                        val durationCol = if (hasDuration) cursor.getColumnIndex(MediaStore.MediaColumns.DURATION) else -1
                        val widthCol = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH)
                        val heightCol = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
                        val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                        val pathCol = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(idCol)
                            val itemUri = ContentUris.withAppendedId(uri, id)
                            val seconds = if (durationCol >= 0) (cursor.getLong(durationCol) / 1000f).coerceAtLeast(0.1f) else 5f
                            val path = if (pathCol >= 0) cursor.getString(pathCol) else null
                            val dataPath = if (dataCol >= 0) cursor.getString(dataCol) else null
                            val legacyFolder = dataPath?.let { java.io.File(it).parentFile?.name }
                            val folderName = (path?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                                ?: legacyFolder?.takeIf { it.isNotBlank() } ?: "Other")
                            found.add(ImportMediaEntry(
                                itemUri, cursor.getString(nameCol) ?: "Media", type, seconds,
                                if (widthCol >= 0) cursor.getInt(widthCol) else 0,
                                if (heightCol >= 0) cursor.getInt(heightCol) else 0,
                                folderName
                            ))
                        }
                    }
                }
            }
            if (tab == 0) {
                val common = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.WIDTH,
                    MediaStore.MediaColumns.HEIGHT, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DATA)
                read(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    common + MediaStore.Video.VideoColumns.DURATION, LayerType.VIDEO, true)
                read(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, common, LayerType.IMAGE, false)
            } else {
                read(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Audio.Media._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.DATE_ADDED, MediaStore.Audio.AudioColumns.DURATION,
                        MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DATA),
                    LayerType.AUDIO, true)
            }
            found
        }
        selected = null
        folder = null
        folderMode = false
    }

    LaunchedEffect(selected?.uri, trim.start) {
        if (selected?.type == LayerType.VIDEO) {
            player.pause()
            player.seekTo((trim.start * 1000f).toLong())
        }
    }

    DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                videoIsPlaying = isPlaying
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(selected?.uri) {
        val item = selected
        videoIsPlaying = false
        if (item != null && item.type == LayerType.VIDEO) {
            player.setMediaItem(MediaItem.fromUri(item.uri))
            player.prepare()
            player.pause()
            trim = 0f..item.duration.coerceAtLeast(0.1f)
        } else if (item != null) {
            player.pause()
            trim = 0f..item.duration.coerceAtLeast(0.1f)
        } else {
            player.pause()
        }
    }
    val visibleEntries = entries.filter { folder == null || it.folder == folder }
    val folders = entries.map { it.folder }.distinct().sorted()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(
        usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false
    )) {
        Column(
            Modifier.fillMaxSize().background(KColors.Bg)
        ) {
            Row(
                Modifier.fillMaxWidth().background(KColors.Surface).padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                ImportIconButton(IconKind.CLOSE, "Cancel", onDismiss, buttonSize = 38.dp)
                Text(if (tab == 0) "Media" else "Audio", color = KColors.Text, fontSize = 15.sp,
                    modifier = Modifier.weight(1f), maxLines = 1)
                ImportIconButton(IconKind.VIDEO, "Videos and images", { tab = 0 },
                    selected = tab == 0, buttonSize = 38.dp)
                ImportIconButton(IconKind.MUSIC, "Audio", { tab = 1 },
                    selected = tab == 1, buttonSize = 38.dp)
                ImportIconButton(IconKind.GRID, "All media", {
                    folderMode = false
                    folder = null
                }, selected = !folderMode && folder == null, buttonSize = 38.dp)
                ImportIconButton(IconKind.FOLDER, "Folders", {
                    folderMode = !folderMode
                    folder = null
                }, selected = folderMode, buttonSize = 38.dp)
                ImportIconButton(
                    IconKind.PLUS, if (isImporting) "Adding" else "Add to timeline",
                    onClick = {
                        val item = selected ?: return@ImportIconButton
                        if (!isImporting) {
                            isImporting = true
                            scope.launch {
                                try {
                                    onImport(item, trim.start, trim.endInclusive)
                                    onDismiss()
                                } finally { isImporting = false }
                            }
                        }
                    },
                    enabled = selected != null && !isImporting,
                    buttonSize = 38.dp
                )
            }

            if (folderMode) {
                androidx.compose.foundation.lazy.LazyRow(
                    Modifier.fillMaxWidth().background(KColors.Surface2).padding(vertical = 8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item { ImportTab("All", folder == null) { folder = null; folderMode = false } }
                    items(folders.size) { index ->
                        val name = folders[index]
                        ImportTab(name, folder == name) { folder = name; folderMode = false; selected = null }
                    }
                }
            }

            if (!hasPermission) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Allow media access to browse files", color = KColors.Muted,
                        modifier = Modifier.clickable {
                            val permissions = if (Build.VERSION.SDK_INT >= 33)
                                arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
                            else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                            permissionLauncher.launch(permissions)
                        }.padding(20.dp))
                }
            } else if (tab == 0) {
                Column(Modifier.fillMaxWidth().wrapContentHeight().clipToBounds().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Box(
                        Modifier.fillMaxWidth().height(if (selected == null) 106.dp else 184.dp)
                            .clip(RoundedCornerShape(12.dp)).background(KColors.Surface2),
                        contentAlignment = Alignment.Center
                    ) {
                        when (selected?.type) {
                            LayerType.IMAGE -> ImportPreviewImage(selected!!)
                            LayerType.VIDEO -> AndroidView(
                                factory = { PlayerView(it).apply { this.player = player; useController = false } },
                                update = { it.player = player },
                                modifier = Modifier.fillMaxSize()
                            )
                            else -> Text("Select a video or image", color = KColors.Muted, fontSize = 13.sp)
                        }
                        if (selected?.type == LayerType.VIDEO) {
                            Box(
                                Modifier.align(Alignment.Center).size(48.dp)
                                    .clip(CircleShape).background(KColors.Bg.copy(alpha = 0.72f))
                                    .clickable {
                                        if (player.isPlaying) player.pause() else player.play()
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                KIcon(
                                    if (videoIsPlaying) IconKind.PAUSE else IconKind.PLAY,
                                    KColors.Text, Modifier.size(24.dp)
                                )
                            }
                            Box(
                                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 8.dp)
                            ) {
                                ImportTrimFilmstrip(item = selected!!, trim = trim,
                                    onTrimChange = { trim = it })
                            }
                        }
                    }
                    if (selected?.type == LayerType.IMAGE) {
                        Text("Image selected", color = KColors.Muted, fontSize = 11.sp,
                            modifier = Modifier.padding(vertical = 8.dp))
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(104.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(visibleEntries, key = { it.uri.toString() }) { item ->
                        Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp))
                            .background(if (selected?.uri == item.uri) KColors.Accent.copy(alpha = .28f) else KColors.Surface2)
                            .clickable {
                                selected = item
                                trim = 0f..item.duration.coerceAtLeast(0.1f)
                            }) {
                            ImportGridThumb(item)
                            if (item.type == LayerType.VIDEO) {
                                Box(Modifier.align(Alignment.BottomEnd).padding(6.dp)
                                    .clip(RoundedCornerShape(5.dp)).background(KColors.Bg.copy(alpha = .8f)).padding(4.dp)) {
                                    KIcon(IconKind.VIDEO, KColors.Text, Modifier.size(14.dp))
                                }
                            }
                            if (selected?.uri == item.uri) {
                                Box(Modifier.align(Alignment.TopEnd).padding(6.dp)
                                    .size(22.dp).clip(RoundedCornerShape(50)).background(KColors.Accent),
                                    contentAlignment = Alignment.Center) {
                                    KIcon(IconKind.CHECK, KColors.Text, Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(visibleEntries, key = { it.uri.toString() }) { item ->
                        val isPlaying = playingAudio == item.uri.toString()
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(if (selected?.uri == item.uri) KColors.Clip else KColors.Surface2)
                            .clickable {
                                selected = item
                                if (isPlaying) {
                                    player.pause()
                                    playingAudio = null
                                } else {
                                    player.setMediaItem(MediaItem.fromUri(item.uri))
                                    player.prepare()
                                    player.play()
                                    playingAudio = item.uri.toString()
                                }
                            }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(38.dp).clip(RoundedCornerShape(8.dp)).background(KColors.Bg),
                                contentAlignment = Alignment.Center) {
                                KIcon(IconKind.MUSIC, KColors.Text, Modifier.size(20.dp))
                            }
                            Column(Modifier.weight(1f).padding(start = 9.dp)) {
                                Text(item.name, color = KColors.Text, fontSize = 11.sp, maxLines = 1)
                                Text(item.folder + " · " + String.format("%.1fs", item.duration), color = KColors.Text, fontSize = 11.sp)
                            }
                            KIcon(if (isPlaying) IconKind.PAUSE else IconKind.PLAY, KColors.Accent, Modifier.size(22.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportTrimFilmstrip(
    item: ImportMediaEntry,
    trim: ClosedFloatingPointRange<Float>,
    onTrimChange: (ClosedFloatingPointRange<Float>) -> Unit
) {
    val latestTrim = rememberUpdatedState(trim)
    val duration = item.duration.coerceAtLeast(0.1f)
    val startFraction = (trim.start / duration).coerceIn(0f, 1f)
    val endFraction = (trim.endInclusive / duration).coerceIn(startFraction, 1f)
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(42.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(KColors.Surface2)
            .pointerInput(item.uri, duration) {
                var draggingEnd = false
                var currentStart = trim.start
                var currentEnd = trim.endInclusive
                detectDragGestures(
                    onDragStart = { point ->
                        currentStart = latestTrim.value.start
                        currentEnd = latestTrim.value.endInclusive
                        val startX = (currentStart / duration) * size.width
                        val endX = (currentEnd / duration) * size.width
                        draggingEnd = kotlin.math.abs(point.x - endX) <= kotlin.math.abs(point.x - startX)
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val delta = dragAmount.x / size.width.coerceAtLeast(1) * duration
                        val minGap = minOf(0.1f, duration)
                        if (draggingEnd) {
                            currentEnd = (currentEnd + delta).coerceIn(currentStart + minGap, duration)
                        } else {
                            currentStart = (currentStart + delta).coerceIn(0f, currentEnd - minGap)
                        }
                        onTrimChange(currentStart..currentEnd)
                    }
                )
            }
    ) {
        Row(Modifier.fillMaxSize()) {
            (0 until 6).forEach { index ->
                ImportTrimFrame(item, duration * (index + 0.5f) / 6f, Modifier.weight(1f).fillMaxHeight())
            }
        }
        Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(startFraction)
            .background(KColors.Bg.copy(alpha = 0.58f)))
        Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(1f - endFraction)
            .background(KColors.Bg.copy(alpha = 0.58f)))
        Box(Modifier.align(Alignment.CenterStart).offset(x = maxWidth * startFraction - 3.dp)
            .width(4.dp).fillMaxHeight().background(KColors.Accent))
        Box(Modifier.align(Alignment.CenterStart).offset(x = maxWidth * endFraction - 3.dp)
            .width(6.dp).fillMaxHeight().background(KColors.Accent))
    }
}

@Composable
private fun ImportTrimFrame(item: ImportMediaEntry, time: Float, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, item.uri, time) {
        value = MediaLoader.bitmapFor(
            context, item.uri, item.type, time,
            sampleFps = 3, maxEdge = 220, persistToDisk = false, lowPriorityRequest = true
        )
    }
    if (bitmap != null) {
        Image(bitmap!!.asImageBitmap(), null, modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(KColors.Surface2))
    }
}

@Composable
private fun ImportIconButton(
    icon: IconKind,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    selected: Boolean = false,
    buttonSize: androidx.compose.ui.unit.Dp = 44.dp
) {
    Box(Modifier.size(buttonSize).clip(RoundedCornerShape(10.dp))
        .background(when {
            !enabled -> KColors.Surface
            selected -> KColors.Clip
            else -> KColors.Surface2
        })
        .clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        KIcon(icon, if (enabled) KColors.Text else KColors.Muted, Modifier.size(20.dp))
    }
}

@Composable
private fun ImportTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(18.dp))
        .background(if (selected) KColors.Clip else KColors.Surface2)
        .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(label, color = if (selected) KColors.Text else KColors.Muted, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun ImportPreviewImage(item: ImportMediaEntry) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, item.uri) {
        value = MediaLoader.bitmapFor(context, item.uri, LayerType.IMAGE, 0f,
            sampleFps = 5, maxEdge = 960, persistToDisk = false)
    }
    if (bitmap != null) Image(bitmap!!.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
}

@Composable
private fun ImportGridThumb(item: ImportMediaEntry) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, item.uri) {
        value = MediaLoader.bitmapFor(context, item.uri, item.type, 0f,
            sampleFps = 3, maxEdge = 360, persistToDisk = false, lowPriorityRequest = true)
    }
    if (bitmap != null) {
        Image(bitmap!!.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            KIcon(if (item.type == LayerType.VIDEO) IconKind.VIDEO else IconKind.IMAGE,
                KColors.Muted, Modifier.size(26.dp))
        }
    }
}
