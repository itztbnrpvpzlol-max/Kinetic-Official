package com.kinetic.app.ui

import android.content.Context
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kinetic.app.export.ExportCodec
import com.kinetic.app.export.ExportEngine
import com.kinetic.app.export.ExportFailure
import com.kinetic.app.export.ExportQuality
import com.kinetic.app.export.ExportSettings
import com.kinetic.app.model.CompSettings
import com.kinetic.app.model.EditorState
import com.kinetic.app.model.Layer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

object PreRender {
    enum class Kind { RENDER, EXPORT }

    private class Result(val key: Long, val file: File)

    var running by mutableStateOf<Kind?>(null)
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var version by mutableIntStateOf(0)
        private set

    private val results = HashMap<Kind, Result>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    fun keyFor(layers: List<Layer>, comp: CompSettings, settings: ExportSettings): Long {
        var hash = layers.hashCode().toLong()
        hash = hash * 31L + comp.hashCode()
        hash = hash * 31L + settings.copy(fileName = "").hashCode()
        return hash
    }

    fun settingsFor(kind: Kind, editor: EditorState): ExportSettings {
        val base = defaultExportSettings(editor)
        if (kind == Kind.EXPORT) return base
        val comp = editor.compSettings
        val (w, h) = ExportSettings.sizeFor(comp.width, comp.height, 540)
        return base.copy(width = w, height = h, quality = ExportQuality.DRAFT, codec = ExportCodec.H264)
    }

    fun cachedExport(layers: List<Layer>, comp: CompSettings, settings: ExportSettings): File? {
        val entry = results[Kind.EXPORT] ?: return null
        return entry.file.takeIf { entry.key == keyFor(layers, comp, settings) && it.isFile && it.length() > 0L }
    }

    fun isReady(kind: Kind, editor: EditorState): Boolean {
        if (version < 0) return false
        val entry = results[kind] ?: return false
        val key = keyFor(editor.projectPreviewLayers.toList(), editor.compSettings, settingsFor(kind, editor))
        return entry.key == key && entry.file.isFile && entry.file.length() > 0L
    }

    fun readyFile(kind: Kind): File? = results[kind]?.file?.takeIf { it.isFile }

    fun start(context: Context, kind: Kind, editor: EditorState) {
        if (running != null) return
        val appContext = context.applicationContext
        val layers = editor.projectPreviewLayers.toList()
        val comp = editor.compSettings
        val settings = settingsFor(kind, editor)
        val key = keyFor(layers, comp, settings)
        val target = File(File(appContext.cacheDir, "prerender"), "${kind.name.lowercase()}.mp4")
        error = null
        progress = 0f
        running = kind
        job = scope.launch {
            try {
                ExportEngine.run(
                    appContext, layers, comp, settings, target,
                    onProgress = { progress = it.fraction }, onPreview = { }, wantPreviews = false
                )
                results[kind] = Result(key, target)
                version++
            } catch (e: CancellationException) {
                target.delete()
                throw e
            } catch (e: ExportFailure) {
                error = e.message
            } catch (e: Throwable) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                running = null
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }
}

@Composable
fun PreRenderDialog(editor: EditorState, onClose: () -> Unit) {
    val context = LocalContext.current
    var playing by remember { mutableStateOf<File?>(null) }
    val running = PreRender.running
    val renderReady = PreRender.isReady(PreRender.Kind.RENDER, editor)
    val exportReady = PreRender.isReady(PreRender.Kind.EXPORT, editor)
    val shape = RoundedCornerShape(16.dp)

    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.fillMaxWidth().clip(shape).background(KColors.Surface).border(1.dp, KColors.Line, shape).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Render", color = KColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            RenderRow(
                title = "Pre-render",
                body = if (renderReady) "Ready. Plays back the whole composition smoothly." else "Draft-quality render for smooth, real-time playback.",
                active = running == PreRender.Kind.RENDER,
                busy = running != null,
                ready = renderReady,
                actionLabel = if (renderReady) "Play" else "Start",
                onAction = {
                    if (renderReady) playing = PreRender.readyFile(PreRender.Kind.RENDER)
                    else PreRender.start(context, PreRender.Kind.RENDER, editor)
                }
            )
            RenderRow(
                title = "Pre-export",
                body = if (exportReady) "Ready. Export will finish instantly while nothing changes." else "Renders the final video now with the default export settings.",
                active = running == PreRender.Kind.EXPORT,
                busy = running != null,
                ready = exportReady,
                actionLabel = if (exportReady) "Redo" else "Start",
                onAction = {
                    if (exportReady) PreRender.start(context, PreRender.Kind.EXPORT, editor)
                    else PreRender.start(context, PreRender.Kind.EXPORT, editor)
                }
            )
            if (running != null) {
                LinearProgressIndicator(
                    progress = { PreRender.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = KColors.Accent,
                    trackColor = KColors.Line
                )
                Text("Cancel", color = KColors.Accent, fontSize = 13.sp, modifier = Modifier.clickable { PreRender.cancel() }.padding(vertical = 4.dp))
            }
            PreRender.error?.let { Text(it, color = Color(0xFFE5484D), fontSize = 12.sp) }
            Text("Close", color = KColors.Dim, fontSize = 13.sp, modifier = Modifier.clickable { onClose() }.padding(vertical = 4.dp))
        }
    }
    playing?.let { file -> PreRenderPlayer(file) { playing = null } }
}

@Composable
private fun RenderRow(
    title: String, body: String, active: Boolean, busy: Boolean, ready: Boolean,
    actionLabel: String, onAction: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(KColors.Bg).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = KColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(if (active) "Rendering…" else body, color = KColors.Dim, fontSize = 11.sp)
        }
        Box(
            Modifier.padding(start = 10.dp).clip(RoundedCornerShape(8.dp))
                .background(if (busy) KColors.Line else KColors.Accent)
                .clickable(enabled = !busy, onClick = onAction)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(actionLabel, color = if (busy) KColors.Dim else Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun PreRenderPlayer(file: File, onClose: () -> Unit) {
    val video = remember { arrayOfNulls<VideoView>(1) }
    DisposableEffect(file) { onDispose { runCatching { video[0]?.stopPlayback() } } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { ctx ->
                    val frame = FrameLayout(ctx)
                    val view = VideoView(ctx)
                    frame.addView(view, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
                    view.setVideoPath(file.absolutePath)
                    view.setOnPreparedListener { it.isLooping = true; view.start() }
                    video[0] = view
                    frame
                },
                modifier = Modifier.fillMaxSize()
            )
            Text(
                "Close", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp).clickable { onClose() }
            )
        }
    }
}
