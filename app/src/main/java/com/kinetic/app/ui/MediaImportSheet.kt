package com.kinetic.app.ui

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.MediaItem
import androidx.media3.ui.PlayerView
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.kinetic.app.data.MediaLoader
import com.kinetic.app.model.LayerType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ImportMediaEntry(
    val uri: Uri,
    val name: String,
    val type: LayerType,
    val duration: Float,
    val width: Int,
    val height: Int
)

@Composable
fun MediaImportSheet(
    onDismiss: () -> Unit,
    onImport: (ImportMediaEntry, Float, Float) -> Unit
) {
    val context = LocalContext.current
    val previewPlayer = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(previewPlayer) { onDispose { previewPlayer.release() } }
    var tab by remember { mutableStateOf(0) }
    var entries by remember { mutableStateOf<List<ImportMediaEntry>>(emptyList()) }
    var selected by remember { mutableStateOf<ImportMediaEntry?>(null) }
    var trimStart by remember { mutableFloatStateOf(0f) }
    var previewAt by remember { mutableFloatStateOf(0f) }
    var trimEnd by remember { mutableFloatStateOf(5f) }
    var hasPermission by remember { mutableStateOf(false) }

    LaunchedEffect(selected?.uri) {
        previewPlayer.stop()
        val item = selected
        if (item != null && item.type != LayerType.IMAGE) {
            previewPlayer.setMediaItem(MediaItem.fromUri(item.uri))
            previewPlayer.prepare()
        }
    }
    LaunchedEffect(selected?.uri, previewAt) {
        if (selected?.type == LayerType.VIDEO) previewPlayer.seekTo((previewAt * 1000f).toLong())
    }
    val permissions = remember {
        if (Build.VERSION.SDK_INT >= 34) arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        ) else if (Build.VERSION.SDK_INT >= 33) arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO
        ) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        hasPermission = result.values.any { it }
    }
    LaunchedEffect(tab) {
        val required = if (Build.VERSION.SDK_INT >= 33) {
            if (tab == 0 && Build.VERSION.SDK_INT >= 34) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            else if (tab == 0) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
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
                        val durationCol = if (hasDuration) cursor.getColumnIndex(MediaStore.Video.VideoColumns.DURATION) else -1
                        val widthCol = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH)
                        val heightCol = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(idCol)
                            val itemUri = ContentUris.withAppendedId(uri, id)
                            val seconds = if (durationCol >= 0) (cursor.getLong(durationCol) / 1000f).coerceAtLeast(0.1f) else 5f
                            found.add(ImportMediaEntry(
                                itemUri, cursor.getString(nameCol) ?: "Media", type, seconds,
                                if (widthCol >= 0) cursor.getInt(widthCol) else 0,
                                if (heightCol >= 0) cursor.getInt(heightCol) else 0
                            ))
                        }
                    }
                }
            }
            if (tab == 0) {
                read(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Video.Media._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.Video.VideoColumns.DURATION, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT),
                    LayerType.VIDEO, true)
                read(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT),
                    LayerType.IMAGE, false)
            } else {
                read(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Audio.Media._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.Audio.AudioColumns.DURATION, MediaStore.MediaColumns.DATE_ADDED),
                    LayerType.AUDIO, true)
            }
            found.sortedByDescending { it.uri.lastPathSegment ?: "" }
        }
        selected = null
    }

    val previewTime = 0f
    val preview by produceState<android.graphics.Bitmap?>(null, selected?.uri, previewTime) {
        val item = selected
        value = if (item?.type == LayerType.IMAGE) {
            MediaLoader.bitmapFor(context, item.uri, item.type, previewTime, 8, 720)
        } else null
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        Text("Import media", color = KColors.Text, fontSize = 19.sp)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(KColors.Surface2)) {
            listOf("Video & Images", "Audio").forEachIndexed { index, label ->
                Box(Modifier.weight(1f).clickable { tab = index }.background(if (tab == index) KColors.Clip else KColors.Surface2).padding(vertical = 11.dp), contentAlignment = Alignment.Center) {
                    Text(label, color = if (tab == index) KColors.Text else KColors.Muted, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        if (!hasPermission) {
            Text("Allow media access to browse files on this device.", color = KColors.Muted, fontSize = 13.sp)
            Text("Grant access", color = KColors.Accent, modifier = Modifier.clickable { permissionLauncher.launch(permissions) }.padding(top = 10.dp))
        } else {
            Row(Modifier.fillMaxWidth().height(180.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(10.dp)).background(KColors.Bg), contentAlignment = Alignment.Center) {
                    when (selected?.type) {
                        LayerType.IMAGE -> if (preview != null) Image(preview!!.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        LayerType.VIDEO, LayerType.AUDIO -> AndroidView(
                            factory = { viewContext -> PlayerView(viewContext).apply { player = previewPlayer; useController = true } },
                            update = { it.player = previewPlayer },
                            modifier = Modifier.fillMaxSize()
                        )
                        else -> Text("Select media", color = KColors.Muted, fontSize = 12.sp)
                    }
                }
                Column(Modifier.weight(1f).fillMaxSize()) {
                    Text(selected?.name ?: "No selection", color = KColors.Text, fontSize = 12.sp, maxLines = 2)
                    if (selected != null) {
                        if (selected!!.type == LayerType.VIDEO) {
                            Text("Preview  ${String.format("%.2f", previewAt)}s", color = KColors.Muted, fontSize = 11.sp)
                            Slider(value = previewAt, onValueChange = { previewAt = it }, valueRange = 0f..selected!!.duration.coerceAtLeast(0.1f))
                        }
                        Text("In  ${String.format("%.2f", trimStart)}s", color = KColors.Muted, fontSize = 11.sp)
                        Slider(value = trimStart, onValueChange = { trimStart = it.coerceAtMost(trimEnd - 0.1f) }, valueRange = 0f..selected!!.duration.coerceAtLeast(0.1f))
                        Text("Out  ${String.format("%.2f", trimEnd)}s", color = KColors.Muted, fontSize = 11.sp)
                        Slider(value = trimEnd, onValueChange = { trimEnd = it.coerceAtLeast(trimStart + 0.1f) }, valueRange = (trimStart + 0.1f)..selected!!.duration.coerceAtLeast(trimStart + 0.1f))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.fillMaxWidth().height(220.dp)) {
                items(entries, key = { it.uri.toString() }) { item ->
                    Row(Modifier.fillMaxWidth().clickable {
                        selected = item
                        trimStart = 0f
                        previewAt = 0f
                        trimEnd = item.duration.coerceAtLeast(0.1f)
                    }.padding(vertical = 9.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(34.dp).clip(RoundedCornerShape(7.dp)).background(KColors.Surface2), contentAlignment = Alignment.Center) {
                            KIcon(if (item.type == LayerType.AUDIO) IconKind.MUSIC else if (item.type == LayerType.VIDEO) IconKind.VIDEO else IconKind.IMAGE, KColors.Muted, Modifier.size(19.dp))
                        }
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(item.name, color = if (selected?.uri == item.uri) KColors.Accent else KColors.Text, fontSize = 12.sp, maxLines = 1)
                            Text(if (item.type == LayerType.IMAGE) "Image" else "${String.format("%.1f", item.duration)} sec", color = KColors.Muted, fontSize = 10.sp)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Cancel", color = KColors.Muted, modifier = Modifier.weight(1f).clickable(onClick = onDismiss).padding(12.dp))
                Text("Add to timeline", color = KColors.Accent, modifier = Modifier.weight(1f).clickable(enabled = selected != null) {
                    selected?.let { onImport(it, trimStart, trimEnd) }
                    onDismiss()
                }.padding(12.dp))
            }
        }
    }
}
