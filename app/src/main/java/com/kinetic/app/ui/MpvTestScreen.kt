package com.kinetic.app.ui

import android.content.Context
import android.net.Uri
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.jdtech.mpv.MPVLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Standalone MPV diagnostic player. It intentionally does not replace Kinetic's
 * editor decoder; it provides a controlled A/B test using the same source file.
 */
@Composable
fun MpvTestScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { MpvTestController(context.applicationContext) }
    var status by remember { mutableStateOf("Starting MPV…") }
    var selectedName by remember { mutableStateOf("No video selected") }
    var selectedFile by remember { mutableStateOf<File?>(null) }
    var hardwareMode by remember { mutableStateOf(true) }
    var paused by remember { mutableStateOf(false) }
    var position by remember { mutableStateOf(0.0) }
    var duration by remember { mutableStateOf(0.0) }
    var decoder by remember { mutableStateOf("—") }
    var fps by remember { mutableStateOf(0.0) }
    var dropped by remember { mutableStateOf(0) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                status = "Copying selected video for MPV…"
                val copied = runCatching {
                    withContext(Dispatchers.IO) { copyUriToCache(context, uri) }
                }.getOrElse {
                    status = "Could not read this video: ${it.message ?: "unknown error"}"
                    null
                }
                if (copied != null) {
                    selectedFile?.delete()
                    selectedFile = copied
                    selectedName = copied.name
                    controller.load(copied.absolutePath)
                    status = "Loaded in MPV"
                    paused = false
                }
            }
        }
    }

    LaunchedEffect(controller) {
        runCatching {
            withContext(Dispatchers.IO) { controller.initialize(if (hardwareMode) "auto-safe" else "no") }
        }.onSuccess {
            status = "MPV ready — choose a video"
        }.onFailure {
            status = "MPV failed to initialize: ${it.message ?: it.javaClass.simpleName}"
        }
    }

    LaunchedEffect(controller) {
        while (true) {
            val snapshot = withContext(Dispatchers.IO) { controller.snapshot() }
            position = snapshot.position
            duration = snapshot.duration
            decoder = snapshot.decoder
            fps = snapshot.fps
            dropped = snapshot.dropped
            paused = snapshot.paused
            delay(500)
        }
    }

    DisposableEffect(controller) {
        onDispose {
            controller.release()
            selectedFile?.delete()
        }
    }

    val surfaceView = remember(controller) {
        SurfaceView(context).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    controller.setSurface(holder.surface)
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    controller.setSurface(holder.surface)
                }
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    controller.setSurface(null)
                }
            })
        }
    }

    Column(
        Modifier.fillMaxSize().background(KColors.Bg).statusBarsPadding().navigationBarsPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KColors.Text)
            }
            Column(Modifier.weight(1f)) {
                Text("MPV decoder test", color = KColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("Standalone playback comparison", color = KColors.Dim, fontSize = 11.sp)
            }
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp))
                    .background(androidx.compose.ui.graphics.Color.Black)
            ) {
                AndroidView(factory = { surfaceView }, modifier = Modifier.fillMaxSize())
                if (selectedFile == null) {
                    Text("Choose a video to begin", color = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.align(Alignment.Center), fontSize = 14.sp)
                }
            }

            Text(selectedName, color = KColors.Text, fontSize = 13.sp, maxLines = 1)
            Text(status, color = KColors.Muted, fontSize = 12.sp)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { picker.launch(arrayOf("video/*")) }, modifier = Modifier.weight(1f)) {
                    Text("Choose video")
                }
                Button(
                    onClick = {
                        controller.togglePause()
                        paused = !paused
                    },
                    enabled = selectedFile != null,
                    modifier = Modifier.weight(1f)
                ) { Text(if (paused) "Play" else "Pause") }
            }

            Text("Decoder mode", color = KColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        if (!hardwareMode) {
                            hardwareMode = true
                            status = "Restarting MPV with automatic hardware decoding…"
                            scope.launch {
                                runCatching { withContext(Dispatchers.IO) { controller.reconfigure("auto-safe") } }
                                    .onSuccess { status = if (selectedFile == null) "MPV ready — choose a video" else "Replaying with automatic hardware decoding" }
                                    .onFailure { status = "MPV restart failed: ${it.message}" }
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(if (hardwareMode) "✓ Auto hardware" else "Auto hardware") }
                OutlinedButton(
                    onClick = {
                        if (hardwareMode) {
                            hardwareMode = false
                            status = "Restarting MPV with software decoding…"
                            scope.launch {
                                runCatching { withContext(Dispatchers.IO) { controller.reconfigure("no") } }
                                    .onSuccess { status = if (selectedFile == null) "MPV ready — choose a video" else "Replaying with software decoding" }
                                    .onFailure { status = "MPV restart failed: ${it.message}" }
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(if (!hardwareMode) "✓ Software only" else "Software only") }
            }

            Text(
                String.format(Locale.US, "%.1f / %.1f sec", position, duration),
                color = KColors.Muted, fontSize = 12.sp
            )
            Text("Active decoder: $decoder", color = KColors.Text, fontSize = 13.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(String.format(Locale.US, "FPS: %.1f", fps), color = KColors.Muted, fontSize = 12.sp)
                Text("Dropped frames: $dropped", color = KColors.Muted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "This screen plays the selected original file directly through libmpv. It does not create proxies or change the editor's decoder.",
                color = KColors.Dim, fontSize = 11.sp
            )
        }
    }
}

private data class MpvSnapshot(
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val decoder: String = "—",
    val fps: Double = 0.0,
    val dropped: Int = 0,
    val paused: Boolean = false
)

private class MpvTestController(context: Context) {
    private val appContext = context.applicationContext
    @Volatile private var player: MPVLib? = null
    @Volatile private var surface: Surface? = null
    @Volatile private var initialized = false
    @Volatile private var surfaceAttached = false
    @Volatile private var pendingPath: String? = null

    @Synchronized
    fun initialize(hwdec: String) {
        if (initialized) return
        val instance = MPVLib.create(appContext) ?: error("libmpv could not be created")
        check(instance.setOptionString("config", "no") >= 0) { "MPV rejected config option" }
        check(instance.setOptionString("vo", "gpu") >= 0) { "MPV GPU output is unavailable" }
        check(instance.setOptionString("gpu-context", "android") >= 0) { "MPV Android GPU context is unavailable" }
        check(instance.setOptionString("hwdec", hwdec) >= 0) { "MPV rejected decoder mode $hwdec" }
        check(instance.setOptionString("keep-open", "yes") >= 0) { "MPV rejected keep-open option" }
        check(instance.setOptionString("terminal", "no") >= 0) { "MPV rejected terminal option" }
        instance.init()
        player = instance
        initialized = true
        attachPendingSurfaceAndLoad()
    }

    @Synchronized
    fun reconfigure(hwdec: String) {
        val path = pendingPath
        destroyInstance()
        pendingPath = path
        initialize(hwdec)
    }

    @Synchronized
    fun setSurface(next: Surface?) {
        if (next == null) {
            if (surfaceAttached) runCatching { player?.detachSurface() }
            surface = null
            surfaceAttached = false
            return
        }
        surface = next
        if (initialized && next.isValid) attachPendingSurfaceAndLoad()
    }

    @Synchronized
    fun load(path: String) {
        pendingPath = path
        if (initialized && surfaceAttached) {
            player?.command(arrayOf("loadfile", path, "replace"))
        }
    }

    @Synchronized
    fun togglePause() {
        val instance = player ?: return
        val current = instance.getPropertyBoolean("pause") ?: false
        instance.setPropertyBoolean("pause", !current)
    }

    fun snapshot(): MpvSnapshot {
        val instance = player ?: return MpvSnapshot()
        return MpvSnapshot(
            position = instance.getPropertyDouble("time-pos") ?: 0.0,
            duration = instance.getPropertyDouble("duration") ?: 0.0,
            decoder = instance.getPropertyString("hwdec-current") ?: "software / pending",
            fps = instance.getPropertyDouble("estimated-vf-fps") ?: 0.0,
            dropped = instance.getPropertyInt("decoder-frame-drop-count") ?: 0,
            paused = instance.getPropertyBoolean("pause") ?: false
        )
    }

    @Synchronized
    fun release() = destroyInstance()

    private fun attachPendingSurfaceAndLoad() {
        val instance = player ?: return
        val currentSurface = surface
        if (currentSurface != null && currentSurface.isValid && !surfaceAttached) {
            instance.attachSurface(currentSurface)
            surfaceAttached = true
        }
        val path = pendingPath
        if (surfaceAttached && path != null) {
            instance.command(arrayOf("loadfile", path, "replace"))
            pendingPath = null
        }
    }

    private fun destroyInstance() {
        initialized = false
        surfaceAttached = false
        val instance = player
        player = null
        if (instance != null) {
            runCatching { instance.detachSurface() }
            runCatching { instance.destroy() }
        }
    }
}

private fun copyUriToCache(context: Context, uri: Uri): File {
    val extension = context.contentResolver.getType(uri)
        ?.substringAfter('/')?.takeIf { it.matches(Regex("[A-Za-z0-9.+-]{1,12}")) }
        ?: "video"
    val output = File(context.cacheDir, "mpv-test-${System.currentTimeMillis()}.$extension")
    try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            output.outputStream().buffered().use { target -> input.copyTo(target, 256 * 1024) }
        } ?: error("Android could not open the selected video")
        return output
    } catch (error: Throwable) {
        output.delete()
        throw error
    }
}
