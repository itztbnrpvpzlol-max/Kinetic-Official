package com.kinetic.app.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinetic.app.export.ExportCapabilities
import com.kinetic.app.export.ExportCodec
import com.kinetic.app.export.ExportEngine
import com.kinetic.app.export.ExportFailure
import com.kinetic.app.export.ExportFrameRenderer
import com.kinetic.app.export.ExportPhase
import com.kinetic.app.export.ExportProgress
import com.kinetic.app.export.ExportQuality
import com.kinetic.app.export.ExportRange
import com.kinetic.app.export.ExportSettings
import com.kinetic.app.export.ExportStorage
import com.kinetic.app.export.formatBytes
import com.kinetic.app.export.formatClock
import com.kinetic.app.model.EditorState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

private enum class ExportStage { SETTINGS, RENDERING, DONE, FAILED }

/** Full-screen export flow: choose settings and check the result, render with live progress, then share. */
@Composable
fun ExportScreen(editor: EditorState, onClose: () -> Unit, backTick: Int = 0) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val comp = editor.compSettings

    var settings by remember { mutableStateOf(defaultExportSettings(editor)) }
    var range by remember { mutableStateOf(ExportRange.FULL) }
    var stage by remember { mutableStateOf(ExportStage.SETTINGS) }
    var progress by remember { mutableStateOf<ExportProgress?>(null) }
    var live by remember { mutableStateOf<Bitmap?>(null) }
    var resultUri by remember { mutableStateOf<Uri?>(null) }
    var resultBytes by remember { mutableStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var confirmCancel by remember { mutableStateOf(false) }
    var previewTime by remember { mutableFloatStateOf(editor.time.coerceIn(settings.start, settings.end)) }

    DisposableEffect(stage) {
        view.keepScreenOn = stage == ExportStage.RENDERING
        onDispose { view.keepScreenOn = false }
    }
    // Back presses arrive from the hosting dialog as ticks (a dialog window consumes them before Compose handlers run).
    androidx.compose.runtime.LaunchedEffect(backTick) {
        if (backTick > 0) { if (stage == ExportStage.RENDERING) confirmCancel = true else onClose() }
    }

    fun startExport() {
        editor.playing = false
        stage = ExportStage.RENDERING
        progress = null
        live = null
        error = null
        val layers = editor.projectPreviewLayers.toList()
        val compSnapshot = editor.compSettings
        val s = settings.copy(fileName = settings.safeFileName)
        job = scope.launch {
            val work = ExportStorage.workFile(ctx, s.safeFileName)
            try {
                val prepared = PreRender.cachedExport(layers, compSnapshot, s)
                if (prepared != null) {
                    withContext(Dispatchers.IO) { prepared.copyTo(work, overwrite = true) }
                } else {
                    ExportEngine.run(ctx, layers, compSnapshot, s, work, onProgress = { progress = it }, onPreview = { live = it })
                }
                resultBytes = work.length()
                resultUri = withContext(Dispatchers.IO) { ExportStorage.saveToGallery(ctx, work, s.safeFileName) }
                stage = ExportStage.DONE
            } catch (e: CancellationException) {
                work.delete()
                stage = ExportStage.SETTINGS
                throw e
            } catch (e: ExportFailure) {
                error = e.message
                stage = ExportStage.FAILED
            } catch (e: Throwable) {
                error = e.message ?: e.javaClass.simpleName
                stage = ExportStage.FAILED
            }
        }
    }

    Box(Modifier.fillMaxSize().background(KColors.Bg).statusBarsPadding().navigationBarsPadding()) {
        when (stage) {
            ExportStage.SETTINGS -> SettingsStage(
                editor = editor, settings = settings, range = range, previewTime = previewTime,
                onSettings = { settings = it },
                onRange = { r ->
                    range = r
                    val end = editor.duration
                    settings = when (r) {
                        ExportRange.FULL -> settings.copy(start = 0f, end = end)
                        ExportRange.FROM_PLAYHEAD -> settings.copy(start = min(editor.time, end - 0.1f).coerceAtLeast(0f), end = end)
                        ExportRange.TO_PLAYHEAD -> settings.copy(start = 0f, end = max(editor.time, 0.1f).coerceAtMost(end))
                    }
                    previewTime = previewTime.coerceIn(settings.start, settings.end)
                },
                onPreviewTime = { previewTime = it },
                onClose = onClose, onExport = ::startExport
            )
            ExportStage.RENDERING -> RenderingStage(progress, live, settings, onCancel = { confirmCancel = true })
            ExportStage.DONE -> DoneStage(
                ctx = ctx, settings = settings, thumbnail = live, uri = resultUri, bytes = resultBytes,
                onAgain = { stage = ExportStage.SETTINGS }, onClose = onClose
            )
            ExportStage.FAILED -> FailedStage(error ?: "Something went wrong.", onBack = { stage = ExportStage.SETTINGS }, onClose = onClose)
        }
        if (confirmCancel) ActionDialog("Stop exporting?", onDismiss = { confirmCancel = false }) {
            DialogItem("Keep exporting") { confirmCancel = false }
            DialogItem("Stop and discard", danger = true) { confirmCancel = false; job?.cancel() }
        }
    }
}

internal fun defaultExportSettings(editor: EditorState): ExportSettings {
    val comp = editor.compSettings
    val heights = ExportSettings.heightPresets.filter { h ->
        val (w, hh) = ExportSettings.sizeFor(comp.width, comp.height, h)
        ExportCapabilities.supports(ExportCodec.H264, w, hh, 30)
    }
    val wanted = comp.height.coerceAtMost(1080)
    val height = heights.filter { it <= wanted }.maxOrNull() ?: heights.minOrNull() ?: 720
    val (w, h) = ExportSettings.sizeFor(comp.width, comp.height, height)
    val fps = ExportSettings.frameRates.filter { ExportCapabilities.supports(ExportCodec.H264, w, h, it) }
        .minByOrNull { kotlin.math.abs(it - comp.fps) } ?: 30
    return ExportSettings(width = w, height = h, fps = fps, start = 0f, end = editor.duration, fileName = editor.projectName)
}

// ---------------------------------------------------------------------------------------------
// Settings

@Composable
private fun SettingsStage(
    editor: EditorState, settings: ExportSettings, range: ExportRange, previewTime: Float,
    onSettings: (ExportSettings) -> Unit, onRange: (ExportRange) -> Unit, onPreviewTime: (Float) -> Unit,
    onClose: () -> Unit, onExport: () -> Unit
) {
    val comp = editor.compSettings
    val hasAudio = remember(editor.projectPreviewLayers.hashCode(), settings.start, settings.end) {
        com.kinetic.app.export.ExportAudioProbe.hasAudibleLayers(editor.projectPreviewLayers, settings.start, settings.end)
    }
    val heights = remember(comp.width, comp.height, settings.codec, settings.fps) {
        ExportSettings.heightPresets.filter { h ->
            val (w, hh) = ExportSettings.sizeFor(comp.width, comp.height, h)
            ExportCapabilities.supports(settings.codec, w, hh, settings.fps)
        }
    }
    val rates = remember(settings.width, settings.height, settings.codec) {
        ExportSettings.frameRates.filter { ExportCapabilities.supports(settings.codec, settings.width, settings.height, it) }
    }
    val codecs = remember { ExportCodec.entries.filter { ExportCapabilities.supportsCodec(it) } }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                KIcon(IconKind.BACK, KColors.Text)
            }
            Text("Export", color = KColors.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            ExportPreview(editor, settings, previewTime, onPreviewTime)
            Spacer(Modifier.height(18.dp))

            Heading("Resolution")
            ChipRow {
                heights.forEach { h ->
                    val (w, hh) = ExportSettings.sizeFor(comp.width, comp.height, h)
                    Chip(if (h == 2160) "4K" else "${h}p", settings.height == hh) { onSettings(settings.copy(width = w, height = hh)) }
                }
            }
            Text("${settings.width}\u00D7${settings.height}", color = KColors.Dim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))

            Spacer(Modifier.height(14.dp))
            Heading("Frame rate")
            ChipRow { rates.forEach { r -> Chip("$r fps", settings.fps == r) { onSettings(settings.copy(fps = r)) } } }

            Spacer(Modifier.height(14.dp))
            Heading("Format")
            ChipRow {
                codecs.forEach { c -> Chip("${c.label} \u00B7 ${c.note}", settings.codec == c) { onSettings(settings.copy(codec = c)) } }
            }

            Spacer(Modifier.height(14.dp))
            Heading("Quality")
            ChipRow {
                ExportQuality.entries.forEach { q -> Chip("${q.label} \u00B7 ${q.note}", settings.quality == q) { onSettings(settings.copy(quality = q)) } }
            }

            Spacer(Modifier.height(14.dp))
            Heading("Range")
            ChipRow { ExportRange.entries.forEach { r -> Chip(r.label, range == r) { onRange(r) } } }
            Text(
                "${formatClock(settings.start)} \u2013 ${formatClock(settings.end)}  \u00B7  ${"%.1f".format(settings.duration)} s",
                color = KColors.Dim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)
            )

            Spacer(Modifier.height(14.dp))
            Heading("Audio")
            if (hasAudio) {
                ChipRow {
                    Chip("Include audio", settings.includeAudio) { onSettings(settings.copy(includeAudio = !settings.includeAudio)) }
                    if (settings.includeAudio) ExportSettings.audioBitrates.forEach { b ->
                        Chip("${b / 1000} kbps", settings.audioBitrate == b) { onSettings(settings.copy(audioBitrate = b)) }
                    }
                }
            } else {
                Text("No audio in this range.", color = KColors.Dim, fontSize = 12.sp)
            }

            Spacer(Modifier.height(14.dp))
            Heading("File name")
            NameField(settings.fileName) { onSettings(settings.copy(fileName = it)) }
            Text("Saved to ${ExportStorage.whereSaved()}.", color = KColors.Dim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))

            Spacer(Modifier.height(16.dp))
            SummaryCard(settings)
            Spacer(Modifier.height(20.dp))
        }
        Box(Modifier.fillMaxWidth().padding(16.dp)) {
            Box(
                Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp)).background(KColors.Accent).clickable(onClick = onExport),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KIcon(IconKind.EXPORT, Color.White, Modifier.size(20.dp))
                    Text("Export video", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** The finished picture at [time], drawn by the same renderer the export uses (effects and all). */
@Composable
private fun ExportPreview(editor: EditorState, settings: ExportSettings, time: Float, onTime: (Float) -> Unit) {
    val ctx = LocalContext.current
    val layersKey = editor.projectPreviewLayers.hashCode()
    val frame by produceState<Bitmap?>(null, time, settings.width, settings.height, layersKey, editor.compSettings) {
        delay(180)
        val layers = editor.projectPreviewLayers.toList()
        val comp = editor.compSettings
        value = withContext(Dispatchers.Default) {
            val k = min(1f, 720f / max(settings.width, settings.height))
            val pw = ((settings.width * k).toInt() and 1.inv()).coerceAtLeast(2)
            val ph = ((settings.height * k).toInt() and 1.inv()).coerceAtLeast(2)
            runCatching {
                ExportFrameRenderer(ctx, layers, comp, pw, ph, settings.fps).use { renderer ->
                    renderer.render(time).copy(Bitmap.Config.ARGB_8888, false)
                }
            }.getOrNull()
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(settings.width.toFloat() / settings.height).clip(RoundedCornerShape(12.dp))
                .background(Color.Black).border(1.dp, KColors.Line, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            val bmp = frame
            if (bmp != null) Image(bmp.asImageBitmap(), "Export preview", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else Text("Rendering preview\u2026", color = KColors.Dim, fontSize = 12.sp)
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(formatClock(time), color = KColors.Muted, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
            Slider(
                value = time.coerceIn(settings.start, max(settings.end, settings.start + 0.01f)), onValueChange = onTime,
                valueRange = settings.start..max(settings.end, settings.start + 0.01f), modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = KColors.Accent, activeTrackColor = KColors.Accent, inactiveTrackColor = KColors.Surface2)
            )
        }
        Text("Preview of the exported result at this time", color = KColors.Dim, fontSize = 11.sp)
    }
}

@Composable
private fun SummaryCard(s: ExportSettings) {
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(KColors.Surface).border(1.dp, KColors.Line, shape).padding(14.dp)) {
        SummaryRow("Output", "${s.width}\u00D7${s.height} \u00B7 ${s.fps} fps \u00B7 ${s.codec.label}")
        SummaryRow("Video bitrate", "%.1f Mbps".format(ExportCapabilities.clampBitrate(s.codec, s.videoBitrate) / 1_000_000f))
        SummaryRow("Length", "${"%.1f".format(s.duration)} s \u00B7 ${s.frameCount} frames")
        SummaryRow("Estimated size", "about ${formatBytes(s.estimatedBytes)}")
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = KColors.Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = KColors.Text, fontSize = 13.sp)
    }
}

@Composable
private fun Heading(text: String) {
    Text(text.uppercase(), color = KColors.Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun NameField(value: String, onChange: (String) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.fillMaxWidth().height(46.dp).clip(shape).background(KColors.Surface).border(1.dp, KColors.Line, shape).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = TextStyle(color = KColors.Text, fontSize = 15.sp), cursorBrush = SolidColor(KColors.Accent),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Rendering

@Composable
private fun RenderingStage(progress: ExportProgress?, live: Bitmap?, settings: ExportSettings, onCancel: () -> Unit) {
    val fraction = progress?.fraction ?: 0f
    val phase = when (progress?.phase) {
        ExportPhase.AUDIO -> "Mixing audio"
        ExportPhase.VIDEO -> "Rendering frame ${progress?.frame} of ${progress?.totalFrames}"
        ExportPhase.FINISHING -> "Finishing the file"
        else -> "Getting ready"
    }
    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Exporting", color = KColors.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(settings.safeFileName, color = KColors.Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.height(18.dp))
        Box(
            Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(12.dp)).background(Color.Black).border(1.dp, KColors.Line, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            val bmp = live
            if (bmp != null) Image(bmp.asImageBitmap(), "Frame being rendered", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else Text(if (progress?.phase == ExportPhase.AUDIO) "Preparing audio\u2026" else "Preparing\u2026", color = KColors.Dim, fontSize = 13.sp)
        }
        Spacer(Modifier.height(18.dp))
        LinearProgressIndicator(
            progress = { fraction }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = KColors.Accent, trackColor = KColors.Surface2
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(phase, color = KColors.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text("${(fraction * 100).toInt()}%", color = KColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            val elapsed = (progress?.elapsedMs ?: 0L) / 1000
            val eta = progress?.etaMs?.let { "about ${max(1L, it / 1000)} s left" } ?: "estimating\u2026"
            Text("Elapsed ${elapsed} s \u00B7 $eta", color = KColors.Dim, fontSize = 12.sp, modifier = Modifier.weight(1f))
            val fps = progress?.renderFps ?: 0f
            if (fps > 0f) Text("%.1f frames/s".format(fps), color = KColors.Dim, fontSize = 12.sp)
        }
        Text("Keep Kinetic open until the export finishes.", color = KColors.Dim, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
        Spacer(Modifier.height(16.dp))
        OutlineButton("Cancel", onClick = onCancel)
    }
}

// ---------------------------------------------------------------------------------------------
// Result

@Composable
private fun DoneStage(
    ctx: android.content.Context, settings: ExportSettings, thumbnail: Bitmap?, uri: Uri?, bytes: Long,
    onAgain: () -> Unit, onClose: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(8.dp))
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(26.dp)).background(KColors.Clip), contentAlignment = Alignment.Center) {
            KIcon(IconKind.CHECK, KColors.ClipText, Modifier.size(28.dp))
        }
        Text("Export complete", color = KColors.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
        Text(
            "${settings.safeFileName}.mp4 \u00B7 ${formatBytes(bytes)}\nSaved to ${ExportStorage.whereSaved()}",
            color = KColors.Muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp)
        )
        Spacer(Modifier.height(18.dp))
        Box(
            Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(12.dp)).background(Color.Black).border(1.dp, KColors.Line, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            val bmp = thumbnail
            if (bmp != null) Image(bmp.asImageBitmap(), "Last frame", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (uri != null) {
                FilledButton("Share", Modifier.weight(1f)) {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "video/mp4"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    ctx.startActivity(Intent.createChooser(send, "Share video").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                OutlineButton("Play", Modifier.weight(1f)) {
                    val view = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "video/mp4")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    runCatching { ctx.startActivity(view) }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlineButton("Export again", Modifier.weight(1f), onAgain)
            OutlineButton("Done", Modifier.weight(1f), onClose)
        }
    }
}

@Composable
private fun FailedStage(message: String, onBack: () -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(26.dp)).background(KColors.Surface2), contentAlignment = Alignment.Center) {
            KIcon(IconKind.CLOSE, KColors.Danger, Modifier.size(26.dp))
        }
        Text("Export didn't finish", color = KColors.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
        Text(message, color = KColors.Muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, bottom = 22.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledButton("Change settings", Modifier.weight(1f), onBack)
            OutlineButton("Close", Modifier.weight(1f), onClose)
        }
    }
}

@Composable
private fun FilledButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.height(48.dp).clip(RoundedCornerShape(12.dp)).background(KColors.Accent).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun OutlineButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.height(48.dp).clip(shape).background(KColors.Surface).border(1.dp, KColors.Line, shape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(label, color = KColors.Text, fontSize = 15.sp) }
}
