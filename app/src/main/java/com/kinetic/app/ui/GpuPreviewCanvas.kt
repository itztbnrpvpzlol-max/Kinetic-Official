package com.kinetic.app.ui

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import android.view.Surface
import android.opengl.GLES11Ext
import java.util.concurrent.ConcurrentHashMap
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.kinetic.app.data.MediaLoader
import com.kinetic.app.engine.NativeRenderEngine
import com.kinetic.app.engine.NativePlaybackEngine
import com.kinetic.app.data.MediaWorkers
import com.kinetic.app.data.PreviewDiagnostics
import com.kinetic.app.model.COMP_H
import com.kinetic.app.model.COMP_W
import com.kinetic.app.model.EditorState
import com.kinetic.app.model.LayerType
import com.kinetic.app.model.PropId
import com.kinetic.app.model.layerMatrix
import com.kinetic.app.model.buildNativeEffectUniforms
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import android.net.Uri

private data class GpuLayer(
    val id: String,
    val bitmap: Bitmap?,
    val matrix: Matrix,
    val width: Float,
    val height: Float,
    val opacity: Float,
    val mask: Boolean,
    val grayscale: Boolean,
    val blend: String,
    val isVideo: Boolean = false,
    val effects: FloatArray = FloatArray(0)
)

private data class GpuScene(
    val layers: List<GpuLayer>,
    val selected: GpuLayer?,
    val background: Int = 0xFF000000.toInt(),
    val cacheAllowed: Boolean = true,
    val time: Float = 0f
)

private fun supportsGles3(context: Context): Boolean = runCatching {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    manager.deviceConfigurationInfo.reqGlEsVersion >= 0x30000
}.getOrDefault(false)

/**
 * Prefer SurfaceTexture video decoding whenever GLES3 is available. The
 * renderer reports real initialization/draw failures and falls back to Canvas;
 * low RAM alone must not force every decoded frame through CPU YUV conversion.
 */

@Composable
fun PreviewCanvas(
    editor: EditorState,
    modifier: Modifier = Modifier,
    qualityIndex: Int = 0,
    onVideoReady: (Boolean) -> Unit = {},
    playback: NativePlaybackEngine? = null
) {
    val context = LocalContext.current

    LaunchedEffect(editor.layers.map { it.id }, editor.playing) {
        onVideoReady(true)
    }

    // A single video with completely static default transforms can bypass
    // Bitmap/YUV conversion through TextureView. Any animated transform,
    // effect, or multi-layer video composition uses the synchronized GLES
    // compositor and SurfaceTexture external-OES texture path.
    val activeVisualLayers = editor.layers.filter {
        it.visible && it.activeAt(editor.time) &&
            it.type != LayerType.AUDIO && it.type != LayerType.NULL &&
            it.type != LayerType.ADJUSTMENT
    }
    val videoLayers = activeVisualLayers.filter { it.type == LayerType.VIDEO && it.uri != null }
    // TextureView is a bottom-layer surface. Keep it only when the one active
    // video is the lowest visual layer; Canvas then draws stills/text/shapes
    // above it without ever copying the decoded video frame into a Bitmap.
    val hasGpuEffects = activeVisualLayers.any { layer -> layer.effectStack.any { it.enabled } }
    val hasComplexMasks = activeVisualLayers.any { it.maskEnabled && it.maskPath != null }
    val directVideo = videoLayers.singleOrNull()
        ?.takeIf {
            activeVisualLayers.lastOrNull()?.id == it.id && !it.maskEnabled &&
                !hasGpuEffects && canUseDirectSurfaceVideo(it)
        }

    if (directVideo != null) {
        Box(modifier.fillMaxSize()) {
            DirectSurfaceVideoPreview(editor, directVideo, Modifier.fillMaxSize(), playback)
            CpuPreviewCanvas(editor, Modifier.fillMaxSize(), directVideoLayerId = directVideo.id)
        }
    } else if ((videoLayers.isNotEmpty() || hasGpuEffects) && supportsGles3(context) && !hasComplexMasks) {
        // All transformed, multi-layer and effect-bearing footage enters the
        // GLSurfaceView compositor. Video frames remain external-OES textures
        // from SurfaceTexture through the final GPU draw.
        val qualityScale = resolvePreviewScale(context, qualityIndex, activeVisualLayers.size)
        GpuCompositionPreviewCanvas(editor, modifier, qualityScale, playback)
    } else if (videoLayers.isNotEmpty() || hasComplexMasks || !supportsGles3(context)) {
        // Complex masks and devices without GLES3 retain the bounded CPU
        // compatibility renderer. Normal GLES3 video compositions never take
        // this bitmap path, including clips with transform keyframes.
        CpuPreviewCanvas(editor, modifier)
    } else {
        // Still images, text and shapes use the established Canvas renderer.
        // Keep the GLES compositor for video compositions so transformed and
        // animated clips remain on the SurfaceTexture path above.
        CpuPreviewCanvas(editor, modifier)
    }
}

@Composable
private fun rememberPlaybackUri(uri: String?, playing: Boolean): Uri? =
    uri?.let(Uri::parse)

internal fun resolvePreviewScale(context: Context, qualityIndex: Int, activeLayers: Int): Float {
    // Explicit quality choices remain user-controlled.
    if (qualityIndex == 1) return 1f
    if (qualityIndex == 2) return 0.5f
    if (qualityIndex == 3) return 0.25f

    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val memory = ActivityManager.MemoryInfo()
    manager?.getMemoryInfo(memory)
    val totalGb = memory.totalMem / (1024.0 * 1024.0 * 1024.0)
    val availableGb = memory.availMem / (1024.0 * 1024.0 * 1024.0)
    val lowRam = manager?.isLowRamDevice == true ||
        totalGb < 3.0 || MediaWorkers.isConstrainedDevice

    // Auto targets a 360p-class preview on 2 GB / low-RAM phones rather than
    // decoding full-resolution frames and scaling them down only at display time.
    val lowRamScale = minOf(360f / COMP_H.toFloat(), 640f / COMP_W.toFloat())
    val base = when {
        lowRam -> lowRamScale
        totalGb >= 10.0 && availableGb >= 2.0 -> 1f
        totalGb >= 8.0 && availableGb >= 1.5 -> 0.75f
        totalGb >= 6.0 && availableGb >= 1.0 -> 0.5f
        totalGb >= 3.0 && availableGb >= 0.5 -> 0.5f
        else -> lowRamScale
    }
    val pressureAdjusted = if (availableGb < 0.25) minOf(base, 0.25f) else base
    // More simultaneous layers should lower the preview scale, never raise
    // a 360p low-memory preview to 480p just to satisfy a minimum clamp.
    return if (activeLayers >= 3) (pressureAdjusted * 0.75f).coerceAtLeast(0.125f)
        else pressureAdjusted
}


@Composable
private fun GpuCompositionPreviewCanvas(
    editor: EditorState,
    modifier: Modifier = Modifier,
    qualityScale: Float,
    playback: NativePlaybackEngine?
) {
    val gpuFailed = remember { androidx.compose.runtime.mutableStateOf(false) }
    if (gpuFailed.value) {
        // Once a GPU failure is known, do not keep rebuilding the abandoned
        // GPU scene on every playback tick.
        CpuPreviewCanvas(editor, modifier)
        return
    }

    val context = LocalContext.current
    val frames = remember { mutableStateMapOf<String, Bitmap>() }
    // Preserve a recent decoded sample across adjacent split segments that share a source URI.
    val recentVideoFrames = remember { mutableStateMapOf<String, RecentVideoPreviewFrame>() }
    val textTextures = remember { mutableMapOf<String, Pair<Int, Bitmap>>() }
    val failedVideoSources = remember { mutableStateMapOf<String, String>() }
    // Native decoder instances are scarce system resources, not a thread pool.
    // Excess video layers use the existing bitmap preview path rather than
    // exhausting MediaCodec resources.
    val decoderLimit = com.kinetic.app.data.MediaWorkers.maxConcurrentVideoDecoders
    val activeVideoLayers = editor.layers.filter {
        it.visible && it.activeAt(editor.time) && it.type == LayerType.VIDEO && it.uri != null
    }
    // All healthy video decoders feed the GLES compositor through external
    // textures. The external-OES fragment shader applies the same bounded
    // effect uniforms as the 2D texture shader, avoiding per-frame bitmap
    // extraction and uploads for effect-bearing footage on low-RAM devices.
    val videoLayers = activeVideoLayers
        .filter { failedVideoSources[it.id] != it.uri }
        .take(decoderLimit)
    val textureVideoIds = videoLayers.mapTo(HashSet<String>()) { it.id }

    LaunchedEffect(editor.layers.map { it.id to it.uri }) {
        val currentIds = editor.layers.mapTo(HashSet<String>()) { it.id }
        val stale = textTextures.keys.filter { it !in currentIds }
        stale.forEach { textTextures.remove(it) }
        failedVideoSources.keys.filter { it !in currentIds }.toList()
            .forEach { failedVideoSources.remove(it) }
    }

    // Video uses SurfaceTexture as a GPU source texture; GLSL processes the
    // frame in the compositor before it is blended with the rest of the scene.
    // Bitmap decoding is reserved for decoder fallback and still-image inputs.
    val compositionFps = editor.fps.coerceIn(1, 60)
    val latestTimelineTime = androidx.compose.runtime.rememberUpdatedState(editor.time)
    val latestPlaying = androidx.compose.runtime.rememberUpdatedState(editor.playing)

    // Bound the CPU-side fallback/still bitmap cache as well as the GL texture
    // cache. Keep frames for currently visible active layers; evict older
    // inactive frames first when a project has many clips.
    fun trimPreviewFrameCache(protectedId: String) {
        val budget = if (MediaWorkers.isConstrainedDevice) {
            3L * 1024L * 1024L
        } else {
            12L * 1024L * 1024L
        }
        var bytes = frames.values.sumOf { it.byteCount.toLong() }
        if (bytes <= budget) return
        val now = latestTimelineTime.value
        val activeIds = editor.layers.asSequence()
            .filter { it.visible && it.activeAt(now) }
            .map { it.id }
            .toHashSet()
        for (id in frames.keys.toList()) {
            if (bytes <= budget) break
            if (id == protectedId || id in activeIds) continue
            val removed = frames.remove(id) ?: continue
            bytes = (bytes - removed.byteCount.toLong()).coerceAtLeast(0L)
        }
    }

    for (layer in editor.layers) {
        val uri = layer.uri
        val needsBitmap = layer.visible && (
            layer.type == LayerType.IMAGE ||
                (layer.type == LayerType.VIDEO && layer.id !in textureVideoIds)
        )
        if (uri != null && needsBitmap) {
            key(layer.id) {
                DisposableEffect(layer.id) {
                    onDispose { frames.remove(layer.id) }
                }
                LaunchedEffect(
                    uri, layer.type, layer.start, layer.duration, layer.visible,
                    layer.srcOffset, layer.speed, qualityScale, compositionFps
                ) {
                    val source = Uri.parse(uri)
                    if (layer.type == LayerType.IMAGE) {
                        // Don't decode hidden/future stills until they can appear
                        // in the composition. This avoids eagerly retaining every
                        // imported image in a large project.
                        while (true) {
                            if (layer.visible && layer.activeAt(latestTimelineTime.value)) {
                                val bitmap = MediaLoader.bitmapFor(
                                    context, source, LayerType.IMAGE, 0f,
                                    sampleFps = compositionFps,
                                    maxEdge = if (MediaWorkers.isConstrainedDevice) 320 else 448
                                )
                                if (bitmap != null && layer.visible &&
                                    layer.activeAt(latestTimelineTime.value)
                                ) {
                                    frames[layer.id] = bitmap
                                    trimPreviewFrameCache(layer.id)
                                    break
                                }
                                // The playhead may leave this still while its
                                // decode is running. Keep waiting so it can be
                                // decoded when the layer becomes active again.
                                kotlinx.coroutines.delay(if (bitmap == null) 300L else 80L)
                                continue
                            }
                            kotlinx.coroutines.delay(80L)
                        }
                    } else {
                        var lastBucket = Int.MIN_VALUE
                        var wasActive = false
                        while (true) {
                            val timelineTime = latestTimelineTime.value
                            if (!layer.activeAt(timelineTime)) {
                                // Preserve the latest frame for the next active
                                // interval; this avoids a blank/gray-looking
                                // handoff while the decoder catches up.
                                wasActive = false
                                lastBucket = Int.MIN_VALUE
                                kotlinx.coroutines.delay(16L)
                                continue
                            }
                            wasActive = true
                            val sourceTime = (
                                (timelineTime - layer.start).coerceAtLeast(0f) *
                                    layer.speed + layer.srcOffset
                            ).coerceAtLeast(0f)
                            if (frames[layer.id] == null) {
                                val recent = recentVideoFrames[uri]
                                if (recent != null && kotlin.math.abs(recent.sourceTime - sourceTime) <= 0.75f) {
                                    frames[layer.id] = recent.bitmap
                                    trimPreviewFrameCache(layer.id)
                                }
                            }
                            val requestedBucket = (sourceTime * compositionFps).toInt()
                            if (requestedBucket != lastBucket) {
                                val frame = MediaLoader.bitmapFor(
                                    context, source, LayerType.VIDEO, sourceTime,
                                    sampleFps = compositionFps,
                                    maxEdge = if (latestPlaying.value) 320 else 448,
                                    persistToDisk = !latestPlaying.value
                                )
                                val currentTime = latestTimelineTime.value
                                val currentSourceTime = (
                                    (currentTime - layer.start).coerceAtLeast(0f) *
                                        layer.speed + layer.srcOffset
                                ).coerceAtLeast(0f)
                                val currentBucket = (currentSourceTime * compositionFps).toInt()
                                val acceptableLag = maxOf(3, compositionFps / 4)
                                if (frame != null &&
                                    kotlin.math.abs(currentBucket - requestedBucket) <= acceptableLag
                                ) {
                                    frames[layer.id] = frame
                                    trimPreviewFrameCache(layer.id)
                                    recentVideoFrames[uri] = RecentVideoPreviewFrame(sourceTime, frame)
                                    val recentFrameLimit = if (MediaWorkers.isConstrainedDevice) 3 else 8
                                    while (recentVideoFrames.size > recentFrameLimit) {
                                        val oldest = recentVideoFrames.keys.firstOrNull { it != uri } ?: break
                                        recentVideoFrames.remove(oldest)
                                    }
                                    // Mark a bucket complete only after a usable frame was
                                    // accepted. A transient null/stale decode must be retried
                                    // while paused, otherwise an effected clip can stay black
                                    // until the playhead moves to another frame.
                                    lastBucket = requestedBucket
                                } else {
                                    kotlinx.coroutines.delay(120L)
                                }
                            }
                            kotlinx.coroutines.delay(maxOf(1L, 1000L / compositionFps))
                        }
                    }
                }
            }
        }
    }

    val byId = editor.layers.associateBy { it.id }
    val renderLayers = ArrayList<GpuLayer>()
    val activeTextTextureIds = HashSet<String>()
    for (layer in editor.layers.asReversed()) {
        if (!layer.visible || !layer.activeAt(editor.time) || layer.type == LayerType.AUDIO ||
            layer.type == LayerType.NULL || layer.type == LayerType.ADJUSTMENT) continue
        if (layer.type == LayerType.VIDEO && layer.id in textureVideoIds) {
            val opacity = (layer.p(PropId.OPACITY).valueAt(editor.time) / 100f).coerceIn(0f, 1f)
            renderLayers += GpuLayer(
                layer.id, null, Matrix(layerMatrix(layer, editor.time, byId)),
                layer.width, layer.height, opacity, layer.maskEnabled,
                "Grayscale" in layer.effects, layer.blendMode, true, buildNativeEffectUniforms(layer.effectStack, editor.time)
            )
            continue
        }
        val bitmap = when (layer.type) {
            LayerType.IMAGE, LayerType.VIDEO -> frames[layer.id]
            LayerType.TEXT, LayerType.SHAPE -> {
                activeTextTextureIds.add(layer.id)
                val logicalWidth = layer.width.toInt().coerceIn(1, 4096)
                val logicalHeight = layer.height.toInt().coerceIn(1, 4096)
                val signature = if (layer.type == LayerType.TEXT)
                    ((layer.text.hashCode() * 31 + layer.color) * 31 +
                        logicalWidth * 7 + logicalHeight)
                else (((layer.color * 31 + logicalWidth * 7 + logicalHeight) * 31 +
                    layer.shapeKind.hashCode()) * 31 + layer.vectorPointsAt(editor.time).hashCode())
                val cached = textTextures[layer.id]
                if (cached == null || cached.first != signature || cached.second.isRecycled) {
                    // Text and vector layers are rasterized at preview scale.
                    // A 4096x4096 ARGB bitmap would otherwise consume 64 MiB
                    // for one layer, before its GPU copy is considered.
                    val maxTextureEdge = if (MediaWorkers.isConstrainedDevice) 320 else 768
                    val bitmapScale = minOf(
                        1f, maxTextureEdge.toFloat() / maxOf(logicalWidth, logicalHeight)
                    )
                    val w = (logicalWidth * bitmapScale).roundToInt().coerceAtLeast(1)
                    val h = (logicalHeight * bitmapScale).roundToInt().coerceAtLeast(1)
                    val generated = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = AndroidCanvas(generated)
                    canvas.scale(bitmapScale, bitmapScale)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = layer.color }
                    if (layer.type == LayerType.TEXT) {
                        paint.textAlign = Paint.Align.CENTER
                        paint.textSize = 120f
                        paint.typeface = Typeface.DEFAULT_BOLD
                        canvas.drawText(layer.text, logicalWidth / 2f, logicalHeight / 2f + 40f, paint)
                    } else {
                        val points = if (layer.vectorPoints.isEmpty()) {
                            listOf(
                                com.kinetic.app.model.VectorPoint(0f, 0f),
                                com.kinetic.app.model.VectorPoint(1f, 0f),
                                com.kinetic.app.model.VectorPoint(1f, 1f),
                                com.kinetic.app.model.VectorPoint(0f, 1f)
                            )
                        } else layer.vectorPointsAt(editor.time)
                        val path = android.graphics.Path()
                        points.forEachIndexed { index, point ->
                            val x = point.x.coerceIn(0f, 1f) * logicalWidth
                            val y = point.y.coerceIn(0f, 1f) * logicalHeight
                            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        path.close()
                        canvas.drawPath(path, paint)
                    }
                    textTextures[layer.id] = signature to generated
                    generated
                } else cached.second
            }
            else -> null
        } ?: continue
        val opacity = (layer.p(PropId.OPACITY).valueAt(editor.time) / 100f).coerceIn(0f, 1f)
        renderLayers += GpuLayer(
            layer.id, bitmap, Matrix(layerMatrix(layer, editor.time, byId)),
            layer.width, layer.height, opacity, layer.maskEnabled,
            "Grayscale" in layer.effects, layer.blendMode, false, buildNativeEffectUniforms(layer.effectStack, editor.time)
        )
    }
    // Keep only a small warm set of inactive text/vector bitmaps. Active
    // textures are protected for this composition; old project layers are
    // discarded before they can accumulate without bound.
    val textTextureBudget = if (MediaWorkers.isConstrainedDevice) {
        2L * 1024L * 1024L
    } else {
        8L * 1024L * 1024L
    }
    var textTextureBytes = textTextures.values.sumOf { it.second.byteCount.toLong() }
    if (textTextureBytes > textTextureBudget) {
        val iterator = textTextures.entries.iterator()
        while (iterator.hasNext() && textTextureBytes > textTextureBudget) {
            val entry = iterator.next()
            if (entry.key in activeTextTextureIds) continue
            textTextureBytes -= entry.value.second.byteCount.toLong()
            iterator.remove()
        }
    }
    val selectedLayer = editor.layers.firstOrNull {
        it.id == editor.selectedId && it.visible && it.activeAt(editor.time) && it.type != LayerType.AUDIO
    }?.let { layer ->
        GpuLayer(
            layer.id, null,
            Matrix(layerMatrix(layer, editor.time, byId)), layer.width, layer.height, 1f,
            false, false, "Normal"
        )
    }
    val scene = GpuScene(renderLayers, selectedLayer, cacheAllowed = !editor.playing, time = editor.time)
    // FFmpeg's MediaCodec decoder renders into a SurfaceTexture buffer. Give
    // the producer and decoder the same explicit preview-sized output geometry.
    val videoOutputWidth = (COMP_W * qualityScale).roundToInt().coerceAtLeast(16)
    val videoOutputHeight = (COMP_H * qualityScale).roundToInt().coerceAtLeast(16)
    val view = remember(context, videoOutputWidth, videoOutputHeight) {
        GpuPreviewSurfaceView(context, videoOutputWidth, videoOutputHeight) { gpuFailed.value = true }
    }
        BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val fitScale = minOf(maxWidth.value / COMP_W, maxHeight.value / COMP_H)
            val compositionWidth = (COMP_W * fitScale).dp
            val compositionHeight = (COMP_H * fitScale).dp
            Box(
                Modifier.size(compositionWidth, compositionHeight)
                    .background(androidx.compose.ui.graphics.Color.Black)
            ) {
                AndroidView(
                    factory = { view },
                    modifier = Modifier.fillMaxSize(),
                    update = { it.submit(scene) }
                )
                // Video decoders output directly into SurfaceTexture-backed
                // external GL textures and share the same ordered scene pass.
                videoLayers.forEach { layer ->
                    key(layer.id) {
                        val uri = layer.uri
                        if (uri != null) {
                            val playbackUri = rememberPlaybackUri(uri, editor.playing)
                            val player = remember(playbackUri, qualityScale) {
                                com.kinetic.app.engine.FfmpegVideoPlayer(
                                    context, playbackUri ?: Uri.parse(uri),
                                    videoOutputWidth, videoOutputHeight
                                )
                            }
                            GpuVideoPlayerLayer(
                                editor, layer, player, view, playback,
                                onDecoderFailed = { failedVideoSources[layer.id] = layer.uri.orEmpty() }
                            )
                        }
                    }
                }
            }
        }
    DisposableEffect(view) {
        onDispose { view.releaseRenderer() }
    }
}

@Composable
private fun GpuVideoPlayerLayer(
    editor: EditorState,
    layer: com.kinetic.app.model.Layer,
    player: com.kinetic.app.engine.FfmpegVideoPlayer,
    surfaceView: GpuPreviewSurfaceView,
    playback: NativePlaybackEngine?,
    onDecoderFailed: (String) -> Unit
) {
    val sourceTime = ((editor.time - layer.start).coerceAtLeast(0f) * layer.speed + layer.srcOffset)
        .coerceAtLeast(0f)
    val sourcePositionMs = (sourceTime * 1000f).toLong()

    DisposableEffect(player, surfaceView, layer.id) {
        surfaceView.attachVideo(layer.id, player)
        onDispose {
            surfaceView.detachVideo(layer.id, player)
            player.release()
        }
    }

    LaunchedEffect(player, layer.id) {
        // A decoder being initialized is not proof that it produced a frame.
        // Wait for the SurfaceTexture callback; otherwise a stalled FFmpeg
        // surface/output failure can leave the preview black indefinitely.
        var waitedMs = 0
        while (waitedMs < 12_000 && !player.hasReceivedFrame()) {
            if (player.decoderState() == -1) {
                Log.e("KineticVideo", "FFmpeg decoder failed for layer ${layer.id}; using bitmap fallback")
                onDecoderFailed(layer.id)
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(50L)
            waitedMs += 50
        }
        if (!player.hasReceivedFrame()) {
            Log.w("KineticVideo", "No SurfaceTexture frame for layer ${layer.id} after ${waitedMs}ms; using bitmap fallback")
            onDecoderFailed(layer.id)
        }
    }

    LaunchedEffect(player, layer.id, playback, layer.start, layer.srcOffset, layer.speed) {
        if (playback != null) {
            val clipStartUs = (layer.start * 1_000_000.0).toLong()
            val sourceOffsetUs = (layer.srcOffset * 1_000_000.0).toLong()
            val clipSpeed = layer.speed.coerceIn(0.1f, 4f)
            while (true) {
                val clock = playback.clockSnapshot()
                player.syncMasterClock(clock.positionUs, clock.monotonicNs, clock.speed,
                    clock.playing, clipStartUs, sourceOffsetUs, clipSpeed, clock.generation)
                kotlinx.coroutines.delay(33L)
            }
        }
    }

    LaunchedEffect(layer.id, editor.playing, layer.speed, player) {
        player.setSpeed(layer.speed.coerceIn(0.1f, 4f))
        if (editor.playing) {
            player.seekTo(sourcePositionMs)
            player.play()
        } else {
            player.pause()
        }
    }

    val previousTimelineTime = remember(layer.id) {
        androidx.compose.runtime.mutableFloatStateOf(editor.time)
    }
    LaunchedEffect(editor.time, editor.playing, layer.id, player) {
        player.updatePositionHint(sourcePositionMs)
        if (editor.playing) {
            if (editor.time + 0.05f < previousTimelineTime.floatValue) {
                player.seekTo(sourcePositionMs)
            }
        } else {
            kotlinx.coroutines.delay(24)
            player.seekTo(sourcePositionMs)
        }
        previousTimelineTime.floatValue = editor.time
    }
}

private class GpuPreviewSurfaceView(
    context: Context,
    videoOutputWidth: Int,
    videoOutputHeight: Int,
    onGpuFailure: () -> Unit
) : GLSurfaceView(context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val attachedPlayers = ConcurrentHashMap<String, com.kinetic.app.engine.FfmpegVideoPlayer>()
    private val frameNotifications = AtomicInteger(0)
    @Volatile private var videoLayerIds: Set<String> = emptySet()
    private val gpuRenderer = CompositionGlRenderer(
        videoOutputWidth = videoOutputWidth,
        videoOutputHeight = videoOutputHeight,
        onGpuFailure = onGpuFailure,
        onVideoSurfaceAvailable = { id, surface ->
            mainHandler.post {
                Log.i("KineticVideo", "GL output Surface ready for layer $id")
                attachedPlayers[id]?.attachSurface(surface)
            }
        },
        onFrameAvailable = { id ->
            attachedPlayers[id]?.markFrameAvailable()
            val count = frameNotifications.incrementAndGet()
            if (count <= 5) Log.i("KineticVideo", "SurfaceTexture frame notification #$count layer=$id")
            requestRender()
        }
    )
    init {
        setEGLContextClientVersion(3)
        // Let Android reclaim GL resources when a constrained phone backgrounds
        // the editor; larger devices can retain the context for faster resume.
        setPreserveEGLContextOnPause(!MediaWorkers.isConstrainedDevice)
        setRenderer(gpuRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        setBackgroundColor(android.graphics.Color.BLACK)
    }
    fun submit(scene: GpuScene) {
        gpuRenderer.submit(scene)
        val nextVideoIds = scene.layers.filter { it.isVideo }.mapTo(HashSet()) { it.id }
        if (nextVideoIds != videoLayerIds) {
            videoLayerIds = nextVideoIds
            queueEvent { gpuRenderer.updateVideoLayers(nextVideoIds) }
        }
        requestRender()
    }
    fun attachVideo(id: String, player: com.kinetic.app.engine.FfmpegVideoPlayer) {
        attachedPlayers[id] = player
        queueEvent { gpuRenderer.ensureVideoOutput(id) }
    }
    fun detachVideo(id: String, player: com.kinetic.app.engine.FfmpegVideoPlayer) {
        attachedPlayers.remove(id, player)
        mainHandler.post { player.detachSurface() }
        queueEvent { gpuRenderer.releaseVideoOutput(id) }
    }
    fun releaseRenderer() {
        attachedPlayers.values.forEach { player -> mainHandler.post { player.detachSurface() } }
        attachedPlayers.clear()
        queueEvent { gpuRenderer.release() }
    }
}

private class CompositionGlRenderer(
    private val videoOutputWidth: Int,
    private val videoOutputHeight: Int,
    private val onGpuFailure: () -> Unit,
    private val onVideoSurfaceAvailable: (String, Surface) -> Unit,
    private val onFrameAvailable: (String) -> Unit
) : GLSurfaceView.Renderer {
    @Volatile private var scene = GpuScene(emptyList(), null)
    private var program = 0
    private var solidProgram = 0
    private var externalProgram = 0
    private val videoOutputs = HashMap<String, VideoOutput>()
    // GPU source-frame texture cache: access-ordered and byte-budgeted. Inactive
    // textures remain warm for quick timeline revisits, but active scene textures
    // are protected from eviction while a frame is being composed.
    private val textures = LinkedHashMap<String, TextureEntry>(16, 0.75f, true)
    private var gpuTextureCacheBytes = 0L
    private val gpuTextureCacheBudgetBytes = if (MediaWorkers.isConstrainedDevice) {
        4L * 1024L * 1024L
    } else {
        24L * 1024L * 1024L
    }
    // Reuse frame geometry arrays on the GL thread to avoid per-frame heap
    // churn and garbage-collection pauses during composition playback.
    private val pointScratch = FloatArray(8)
    private val selectionScratch = FloatArray(8)
    private val activeTextureIds = HashSet<String>()
    private val fingerprintMatrixScratch = FloatArray(9)
    // Keep the original per-layer quad path rather than batching scene draws.
    private val quadScratch = FloatArray(16)
    private var compositionCacheAllocated = false
    private var cachedCompositionFingerprint = Long.MIN_VALUE
    private var viewportWidth = 1
    private var viewportHeight = 1
    @Volatile private var gpuFailureReported = false
    private var consecutiveFrameFailures = 0

    private fun reportGpuFailure(error: Throwable) {
        if (gpuFailureReported) return
        gpuFailureReported = true
        Log.e("KineticGpu", "GPU composition failed; switching to Canvas preview", error)
        Handler(Looper.getMainLooper()).post { onGpuFailure() }
    }

    private fun checkGlErrors(stage: String) {
        var error = GLES20.glGetError()
        if (error == GLES20.GL_NO_ERROR) return
        val errors = ArrayList<String>(2)
        while (error != GLES20.GL_NO_ERROR && errors.size < 8) {
            errors += "0x${error.toString(16)}"
            error = GLES20.glGetError()
        }
        throw IllegalStateException("OpenGL error during $stage: ${errors.joinToString()}")
    }

    private data class TextureEntry(val id: Int, val bitmap: Bitmap, val bytes: Long)
    private data class VideoOutput(
        val textureId: Int,
        val texture: SurfaceTexture,
        val surface: Surface,
        val transform: FloatArray = FloatArray(16),
        @Volatile var hasFrame: Boolean = false
    )
    fun submit(next: GpuScene) { scene = next }
    fun updateVideoLayers(ids: Set<String>) {
        videoOutputs.keys.filter { it !in ids }.toList().forEach(::releaseVideoOutput)
        ids.forEach(::ensureVideoOutput)
    }
    fun ensureVideoOutput(id: String) {
        val existing = videoOutputs[id]
        if (existing != null) {
            // A player can attach after EGL created the output; hand the existing
            // Surface to every newly attached player.
            onVideoSurfaceAvailable(id, existing.surface)
            return
        }
        if (program == 0) return
        val names = IntArray(1)
        GLES20.glGenTextures(1, names, 0)
        if (names[0] == 0) return
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, names[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val texture = SurfaceTexture(names[0]).apply {
            setDefaultBufferSize(videoOutputWidth, videoOutputHeight)
        }
        val surface = Surface(texture)
        val output = VideoOutput(names[0], texture, surface)
        videoOutputs[id] = output
        texture.setOnFrameAvailableListener {
            output.hasFrame = true
            onFrameAvailable(id)
        }
        Log.i("KineticVideo", "Created OES output for layer $id at ${videoOutputWidth}x${videoOutputHeight}")
        onVideoSurfaceAvailable(id, surface)
    }
    fun releaseVideoOutput(id: String) {
        val output = videoOutputs.remove(id) ?: return
        runCatching { output.surface.release() }
        runCatching { output.texture.setOnFrameAvailableListener(null) }
        runCatching { output.texture.release() }
        GLES20.glDeleteTextures(1, intArrayOf(output.textureId), 0)
    }
    private fun refreshVideoFrames(): Long {
        var signature = -3750763034362895579L
        videoOutputs.forEach { (id, output) ->
            if (output.hasFrame) {
                try {
                    output.texture.updateTexImage()
                    output.texture.getTransformMatrix(output.transform)
                } catch (error: Throwable) {
                    Log.e("KineticVideo", "updateTexImage failed for layer $id", error)
                }
            }
            signature = (signature xor output.texture.timestamp) * 1099511628211L
            signature = (signature xor id.hashCode().toLong()) * 1099511628211L
        }
        return signature
    }

    private fun mixFingerprint(hash: Long, value: Long): Long =
        (hash xor value) * 1099511628211L

    /** Fingerprints every input that can change the composed output. */
    private fun sceneFingerprint(current: GpuScene, videoSignature: Long): Long {
        var hash = mixFingerprint(-3750763034362895579L, videoSignature)
        hash = mixFingerprint(hash, viewportWidth.toLong())
        hash = mixFingerprint(hash, viewportHeight.toLong())
        hash = mixFingerprint(hash, current.background.toLong())
        hash = mixFingerprint(hash, if (current.cacheAllowed) 1L else 0L)
        for (layer in current.layers) {
            hash = mixFingerprint(hash, layer.id.hashCode().toLong())
            hash = mixFingerprint(hash, System.identityHashCode(layer.bitmap).toLong())
            hash = mixFingerprint(hash, java.lang.Float.floatToIntBits(layer.opacity).toLong())
            hash = mixFingerprint(hash, java.lang.Float.floatToIntBits(layer.width).toLong())
            hash = mixFingerprint(hash, java.lang.Float.floatToIntBits(layer.height).toLong())
            hash = mixFingerprint(hash, if (layer.mask) 1L else 0L)
            hash = mixFingerprint(hash, if (layer.grayscale) 1L else 0L)
            hash = mixFingerprint(hash, layer.blend.hashCode().toLong())
            hash = mixFingerprint(hash, if (layer.isVideo) 1L else 0L)
            layer.matrix.getValues(fingerprintMatrixScratch)
            for (value in fingerprintMatrixScratch) {
                hash = mixFingerprint(hash, java.lang.Float.floatToIntBits(value).toLong())
            }
        }
        current.selected?.let { selected ->
            hash = mixFingerprint(hash, selected.id.hashCode().toLong())
            hash = mixFingerprint(hash, java.lang.Float.floatToIntBits(selected.width).toLong())
            hash = mixFingerprint(hash, java.lang.Float.floatToIntBits(selected.height).toLong())
            selected.matrix.getValues(fingerprintMatrixScratch)
            for (value in fingerprintMatrixScratch) {
                hash = mixFingerprint(hash, java.lang.Float.floatToIntBits(value).toLong())
            }
        }
        return hash
    }

    /** The native engine owns one byte-budgeted composition framebuffer. */
    private fun ensureCompositeTarget(width: Int, height: Int): Boolean {
        val cacheBudgetBytes = if (MediaWorkers.isConstrainedDevice) {
            8L * 1024L * 1024L
        } else {
            16L * 1024L * 1024L
        }
        val ready = NativeRenderEngine.nativeGlEnsureCompositionCache(
            width, height, cacheBudgetBytes
        )
        compositionCacheAllocated = ready
        if (!ready) cachedCompositionFingerprint = Long.MIN_VALUE
        return ready
    }

    private fun releaseCompositeTarget() {
        if (compositionCacheAllocated) {
            NativeRenderEngine.nativeGlReleaseCompositionCache()
        }
        compositionCacheAllocated = false
        cachedCompositionFingerprint = Long.MIN_VALUE
    }

    private fun presentCompositeTarget(): Boolean =
        NativeRenderEngine.nativeGlPresentCompositionCache(viewportWidth, viewportHeight)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A recreated EGL context invalidates every texture/program from the old context.
        textures.clear()
        gpuTextureCacheBytes = 0L
        // The previous EGL context is gone; its GL names must not be deleted in
        // the new context, only forgotten.
        compositionCacheAllocated = false
        cachedCompositionFingerprint = Long.MIN_VALUE
        videoOutputs.values.forEach { output -> runCatching { output.surface.release(); output.texture.release() } }
        videoOutputs.clear()
        program = 0
        solidProgram = 0
        externalProgram = 0
        try {
            check(NativeRenderEngine.available && NativeRenderEngine.nativeGlInit()) {
                "Native OpenGL renderer initialization failed"
            }
            checkGlErrors("renderer initialization")
            // Non-zero sentinels keep the existing lifecycle checks compact;
            // all shader programs and draw calls are owned by the C++ renderer.
            program = 1
            solidProgram = 1
            externalProgram = 1
        } catch (error: Throwable) {
            program = 0
            solidProgram = 0
            externalProgram = 0
            reportGpuFailure(error)
        }
        if (program != 0) scene.layers.filter { it.isVideo }.forEach { ensureVideoOutput(it.id) }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
    }

    override fun onDrawFrame(gl: GL10?) {
        val startedNs = System.nanoTime()
        try {
            if (program == 0) return

            // SurfaceTexture/driver callbacks can leave a stale GL error from
            // outside this frame. Drain it before measuring our own commands so
            // one unrelated error cannot permanently disable effect rendering.
            var staleError = GLES20.glGetError()
            var staleCount = 0
            while (staleError != GLES20.GL_NO_ERROR && staleCount < 8) {
                Log.w("KineticGpu", "Ignoring pre-existing GL error 0x${staleError.toString(16)}")
                staleError = GLES20.glGetError()
                staleCount++
            }

            // Render directly to the GLSurfaceView window framebuffer.
            // The previous paused-preview FBO/blit cache could report a
            // successful present while leaving the device preview black on
            // some GLES drivers. Visual layers must not depend on that cache.
            if (compositionCacheAllocated) releaseCompositeTarget()
            cachedCompositionFingerprint = Long.MIN_VALUE

            val current = scene
            refreshVideoFrames()
            NativeRenderEngine.nativeGlBeginFrame(
                current.background, viewportWidth, viewportHeight, false
            )
            checkGlErrors("begin frame")

            activeTextureIds.clear()
            for (layer in current.layers) {
                if (!layer.isVideo && layer.bitmap != null) activeTextureIds.add(layer.id)
            }

            val scale = minOf(
                viewportWidth.toFloat() / COMP_W.toFloat(),
                viewportHeight.toFloat() / COMP_H.toFloat()
            )
            val offsetX = (viewportWidth - COMP_W * scale) / 2f
            val offsetY = (viewportHeight - COMP_H * scale) / 2f

            drawComposition(current.layers, scale, offsetX, offsetY)
            checkGlErrors("composition draw")
            trimGpuTextureCache(activeTextureIds)
            current.selected?.let { drawSelection(it, scale, offsetX, offsetY) }
            checkGlErrors("selection draw")
            // A single transient driver/texture error should skip one frame,
            // not permanently switch to the CPU renderer (which cannot apply
            // the full effect stack). Fall back only after repeated failures.
            consecutiveFrameFailures = 0
        } catch (error: Throwable) {
            consecutiveFrameFailures++
            if (consecutiveFrameFailures >= 3) {
                reportGpuFailure(error)
            } else {
                Log.w(
                    "KineticGpu",
                    "Transient composition frame failure " +
                        "$consecutiveFrameFailures/3; keeping GPU effects renderer alive",
                    error
                )
            }
        } finally {
            PreviewDiagnostics.recordGlFrame(System.nanoTime() - startedNs)
        }
    }

    /**
     * Draw the ordered scene using the established per-layer GLES calls.
     * This avoids the newer packed native batch/transform path.
     */
    private fun drawComposition(layers: List<GpuLayer>, scale: Float, ox: Float, oy: Float) {
        for (layer in layers) {
            if (layer.isVideo) drawVideoLayer(layer, scale, ox, oy)
            else drawLayer(layer, scale, ox, oy)
        }
    }

    private fun drawLayer(layer: GpuLayer, scale: Float, ox: Float, oy: Float) {
        val bitmap = layer.bitmap ?: return
        if (bitmap.isRecycled || layer.opacity <= 0f) return
        val texture = textureFor(layer.id, bitmap) ?: return
        val right = if (layer.mask) layer.width * 0.8f else layer.width
        val bottom = if (layer.mask) layer.height * 0.8f else layer.height
        val uvMax = if (layer.mask) 0.8f else 1f
        val points = pointScratch
        points[0] = 0f; points[1] = 0f
        points[2] = 0f; points[3] = bottom
        points[4] = right; points[5] = 0f
        points[6] = right; points[7] = bottom
        layer.matrix.mapPoints(points)
        val data = quadScratch
        data[0] = ndcX(points[0], scale, ox); data[1] = ndcY(points[1], scale, oy); data[2] = 0f; data[3] = 0f
        data[4] = ndcX(points[2], scale, ox); data[5] = ndcY(points[3], scale, oy); data[6] = 0f; data[7] = uvMax
        data[8] = ndcX(points[4], scale, ox); data[9] = ndcY(points[5], scale, oy); data[10] = uvMax; data[11] = 0f
        data[12] = ndcX(points[6], scale, ox); data[13] = ndcY(points[7], scale, oy); data[14] = uvMax; data[15] = uvMax
        NativeRenderEngine.nativeGlDrawTexture(
            texture, data, layer.opacity, if (layer.grayscale) 1f else 0f,
            blendModeCode(layer.blend), layer.effects, scene.time
        )
    }

    private fun drawVideoLayer(layer: GpuLayer, scale: Float, ox: Float, oy: Float) {
        val output = videoOutputs[layer.id] ?: return
        if (!output.hasFrame || layer.opacity <= 0f || externalProgram == 0) return
        val right = if (layer.mask) layer.width * 0.8f else layer.width
        val bottom = if (layer.mask) layer.height * 0.8f else layer.height
        val uvMax = if (layer.mask) 0.8f else 1f
        val points = pointScratch
        points[0] = 0f; points[1] = 0f
        points[2] = 0f; points[3] = bottom
        points[4] = right; points[5] = 0f
        points[6] = right; points[7] = bottom
        layer.matrix.mapPoints(points)
        val data = quadScratch
        data[0] = ndcX(points[0], scale, ox); data[1] = ndcY(points[1], scale, oy); data[2] = 0f; data[3] = 0f
        data[4] = ndcX(points[2], scale, ox); data[5] = ndcY(points[3], scale, oy); data[6] = 0f; data[7] = uvMax
        data[8] = ndcX(points[4], scale, ox); data[9] = ndcY(points[5], scale, oy); data[10] = uvMax; data[11] = 0f
        data[12] = ndcX(points[6], scale, ox); data[13] = ndcY(points[7], scale, oy); data[14] = uvMax; data[15] = uvMax
        NativeRenderEngine.nativeGlDrawExternalTexture(
            output.textureId, data, layer.opacity, if (layer.grayscale) 1f else 0f,
            output.transform, blendModeCode(layer.blend), layer.effects, scene.time
        )
    }

    private fun textureFor(layerId: String, bitmap: Bitmap): Int? {
        val old = textures[layerId]
        if (old != null && old.bitmap === bitmap && old.id != 0) return old.id
        if (old != null) {
            GLES20.glDeleteTextures(1, intArrayOf(old.id), 0)
            gpuTextureCacheBytes = (gpuTextureCacheBytes - old.bytes).coerceAtLeast(0L)
            textures.remove(layerId)
        }
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        if (ids[0] == 0) return null
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        val bytes = bitmap.byteCount.toLong().coerceAtLeast(0L)
        textures[layerId] = TextureEntry(ids[0], bitmap, bytes)
        gpuTextureCacheBytes += bytes
        return ids[0]
    }

    private fun trimGpuTextureCache(protectedIds: Set<String>) {
        if (gpuTextureCacheBytes <= gpuTextureCacheBudgetBytes) return
        val iterator = textures.entries.iterator()
        while (iterator.hasNext() && gpuTextureCacheBytes > gpuTextureCacheBudgetBytes) {
            val entry = iterator.next()
            if (entry.key in protectedIds) continue
            GLES20.glDeleteTextures(1, intArrayOf(entry.value.id), 0)
            gpuTextureCacheBytes = (gpuTextureCacheBytes - entry.value.bytes).coerceAtLeast(0L)
            iterator.remove()
        }
    }

    private fun drawSelection(layer: GpuLayer, scale: Float, ox: Float, oy: Float) {
        val p = pointScratch
        p[0] = 0f; p[1] = 0f
        p[2] = layer.width; p[3] = 0f
        p[4] = layer.width; p[5] = layer.height
        p[6] = 0f; p[7] = layer.height
        layer.matrix.mapPoints(p)
        val data = selectionScratch
        data[0] = ndcX(p[0], scale, ox); data[1] = ndcY(p[1], scale, oy)
        data[2] = ndcX(p[2], scale, ox); data[3] = ndcY(p[3], scale, oy)
        data[4] = ndcX(p[4], scale, ox); data[5] = ndcY(p[5], scale, oy)
        data[6] = ndcX(p[6], scale, ox); data[7] = ndcY(p[7], scale, oy)
        NativeRenderEngine.nativeGlDrawSelection(data)
    }

    private fun blendModeCode(mode: String): Int = when (mode) {
        "Multiply" -> 1
        "Screen" -> 2
        "Add" -> 3
        else -> 0
    }

    private fun ndcX(x: Float, scale: Float, ox: Float) = ((ox + x * scale) / viewportWidth) * 2f - 1f
    private fun ndcY(y: Float, scale: Float, oy: Float) = 1f - ((oy + y * scale) / viewportHeight) * 2f

    fun release() {
        videoOutputs.keys.toList().forEach(::releaseVideoOutput)
        releaseCompositeTarget()
        if (textures.isNotEmpty()) {
            GLES20.glDeleteTextures(textures.size, textures.values.map { it.id }.toIntArray(), 0)
            textures.clear()
            gpuTextureCacheBytes = 0L
        }
        NativeRenderEngine.nativeGlRelease()
        program = 0; solidProgram = 0; externalProgram = 0
    }


}
