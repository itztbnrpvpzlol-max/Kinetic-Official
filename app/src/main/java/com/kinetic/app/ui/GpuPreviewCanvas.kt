package com.kinetic.app.ui

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.kinetic.app.data.MediaLoader
import com.kinetic.app.model.COMP_H
import com.kinetic.app.model.COMP_W
import com.kinetic.app.model.EditorState
import com.kinetic.app.model.LayerType
import com.kinetic.app.model.PropId
import com.kinetic.app.model.layerMatrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
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
    val blend: String
)

private data class GpuScene(
    val layers: List<GpuLayer>,
    val selected: GpuLayer?,
    val background: Int = 0xFF000000.toInt()
)

private fun supportsGles2(context: Context): Boolean = runCatching {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    manager.deviceConfigurationInfo.reqGlEsVersion >= 0x20000
}.getOrDefault(false)

@Composable
fun PreviewCanvas(
    editor: EditorState,
    modifier: Modifier = Modifier,
    qualityIndex: Int = 0,
    onVideoReady: (Boolean) -> Unit = {}
) {
    // The import browser already uses Media3's hardware video decoder. Use the
    // same playback path in the editor for a simple full-frame video composition,
    // rather than decoding still frames with MediaMetadataRetriever at 12 fps.
    val context = LocalContext.current
    val activeVisualLayers = editor.layers.filter { layer ->
        layer.visible && layer.activeAt(editor.time) &&
            layer.type != LayerType.AUDIO &&
            layer.type != LayerType.NULL &&
            layer.type != LayerType.ADJUSTMENT
    }
    val qualityScale = resolvePreviewScale(context, qualityIndex, activeVisualLayers.size)
    val multiVideo = activeVisualLayers.takeIf { layers ->
        layers.size > 1 && layers.all { it.type == LayerType.VIDEO &&
            !it.maskEnabled && it.effects.isEmpty() && it.blendMode == "Normal" }
    }
    val directVideo = activeVisualLayers.singleOrNull()?.takeIf { layer ->
        layer.type == LayerType.VIDEO &&
            layer.width > 0f && layer.height > 0f &&
            (layer.width / COMP_W).coerceAtMost(1f) > 0f &&
            (layer.height / COMP_H).coerceAtMost(1f) > 0f &&
            kotlin.math.abs(layer.width / COMP_W - layer.height / COMP_H) < 0.002f &&
            layer.width <= COMP_W && layer.height <= COMP_H &&
            !layer.maskEnabled && layer.effects.isEmpty() && layer.blendMode == "Normal" &&
            kotlin.math.abs(layer.p(PropId.OPACITY).valueAt(editor.time) - 100f) < 0.01f &&
            kotlin.math.abs(layer.p(PropId.POS_X).valueAt(editor.time) - COMP_W / 2f) < 0.01f &&
            kotlin.math.abs(layer.p(PropId.POS_Y).valueAt(editor.time) - COMP_H / 2f) < 0.01f &&
            kotlin.math.abs(layer.p(PropId.SCALE_X).valueAt(editor.time) - 100f) < 0.01f &&
            kotlin.math.abs(layer.p(PropId.SCALE_Y).valueAt(editor.time) - 100f) < 0.01f &&
            kotlin.math.abs(layer.p(PropId.ROTATION).valueAt(editor.time)) < 0.01f
    }

    when {
        multiVideo != null -> MultiVideoPreviewCanvas(editor, multiVideo, modifier, qualityScale, onVideoReady)
        directVideo != null -> DirectVideoPreviewCanvas(editor, directVideo, modifier, qualityScale, onVideoReady)
        else -> {
            LaunchedEffect(editor.layers.map { it.id }, editor.playing) { onVideoReady(true) }
            GpuCompositionPreviewCanvas(editor, modifier, qualityScale)
        }
    }
}

private fun resolvePreviewScale(context: Context, qualityIndex: Int, activeLayers: Int): Float {
    if (qualityIndex == 1) return 1f
    if (qualityIndex == 2) return 0.5f
    if (qualityIndex == 3) return 0.25f
    val memory = android.app.ActivityManager.MemoryInfo()
    (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(memory)
    val totalGb = memory.totalMem / (1024.0 * 1024.0 * 1024.0)
    val availableGb = memory.availMem / (1024.0 * 1024.0 * 1024.0)
    val base = when {
        totalGb >= 8.0 && availableGb >= 2.0 -> 1f
        totalGb >= 6.0 && availableGb >= 1.0 -> 0.5f
        else -> 0.25f
    }
    return if (activeLayers >= 3) (base * 0.5f).coerceAtLeast(0.25f) else base
}


@Composable
private fun MultiVideoPreviewCanvas(
    editor: EditorState,
    layers: List<com.kinetic.app.model.Layer>,
    modifier: Modifier,
    qualityScale: Float,
    onVideoReady: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val readyById = remember(layers.map { it.id }) {
        androidx.compose.runtime.mutableStateMapOf<String, Boolean>().apply {
            layers.forEach { put(it.id, false) }
        }
    }
    val allReady = layers.isNotEmpty() && layers.all { readyById[it.id] == true }
    LaunchedEffect(layers.map { it.id }, allReady, editor.playing) { onVideoReady(allReady) }
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val fitScale = minOf(maxWidth.value / COMP_W, maxHeight.value / COMP_H)
        val compositionWidth = (COMP_W * fitScale).dp
        val compositionHeight = (COMP_H * fitScale).dp
        Box(Modifier.size(compositionWidth, compositionHeight).background(androidx.compose.ui.graphics.Color.Black)) {
            // Oldest layers draw first; newer layers remain visually on top.
            layers.asReversed().forEach { layer ->
                key(layer.id) {
                    val uri = layer.uri
                    if (uri != null) {
                        val maxW = (COMP_W * qualityScale).toInt().coerceAtLeast(240)
                        val maxH = (COMP_H * qualityScale).toInt().coerceAtLeast(135)
                        val player = remember(uri) {
                            ExoPlayer.Builder(context).build().apply {
                                trackSelectionParameters = TrackSelectionParameters.Builder(context)
                                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                                    .setMaxVideoSize(maxW, maxH)
                                    .build()
                                repeatMode = Player.REPEAT_MODE_OFF
                                setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
                                prepare()
                            }
                        }
                        DisposableEffect(player) {
                            onDispose { player.release() }
                        }
                        HardwareVideoLayer(
                            editor, layer, player, fitScale, allReady
                        ) { id, ready -> readyById[id] = ready }
                    }
                }
            }
        }
    }
}

@Composable
private fun HardwareVideoLayer(
    editor: EditorState,
    layer: com.kinetic.app.model.Layer,
    player: ExoPlayer,
    fitScale: Float,
    canStartPlayback: Boolean,
    onReadyChanged: (String, Boolean) -> Unit
) {
    var videoReady by remember(player) { androidx.compose.runtime.mutableStateOf(player.playbackState == Player.STATE_READY) }
    DisposableEffect(player, layer.id) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                videoReady = playbackState == Player.STATE_READY
                onReadyChanged(layer.id, videoReady)
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // A failed video should not hold the entire composition's audio
                // transport behind a readiness barrier forever.
                videoReady = true
                onReadyChanged(layer.id, true)
            }
        }
        player.addListener(listener)
        onReadyChanged(layer.id, videoReady)
        onDispose {
            player.removeListener(listener)
            onReadyChanged(layer.id, false)
        }
    }
    val sourceTime = ((editor.time - layer.start).coerceAtLeast(0f) * layer.speed + layer.srcOffset)
        .coerceAtLeast(0f)
    val sourcePositionMs = (sourceTime * 1000f).toLong()
    LaunchedEffect(editor.playing, canStartPlayback, videoReady, layer.id, layer.speed, player) {
        if (editor.playing && canStartPlayback && videoReady) {
            player.setPlaybackSpeed(layer.speed.coerceIn(0.1f, 4f))
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
        if (editor.playing) {
            if (editor.time + 0.05f < previousTimelineTime.floatValue) player.seekTo(sourcePositionMs)
        } else {
            kotlinx.coroutines.delay(24)
            player.seekTo(sourcePositionMs)
        }
        previousTimelineTime.floatValue = editor.time
    }

    val left = (layer.p(PropId.POS_X).valueAt(editor.time) - layer.p(PropId.ANCHOR_X).valueAt(editor.time)) * fitScale
    val top = (layer.p(PropId.POS_Y).valueAt(editor.time) - layer.p(PropId.ANCHOR_Y).valueAt(editor.time)) * fitScale
    val width = (layer.width * fitScale).coerceAtLeast(1f)
    val height = (layer.height * fitScale).coerceAtLeast(1f)
    val scaleX = layer.p(PropId.SCALE_X).valueAt(editor.time) / 100f
    val scaleY = layer.p(PropId.SCALE_Y).valueAt(editor.time) / 100f
    val rotation = layer.p(PropId.ROTATION).valueAt(editor.time)
    val opacity = (layer.p(PropId.OPACITY).valueAt(editor.time) / 100f).coerceIn(0f, 1f)

    AndroidView(
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                this.player = player
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        modifier = Modifier
            .offset(x = left.dp, y = top.dp)
            .size(width.dp, height.dp)
            .graphicsLayer {
                this.scaleX = scaleX
                this.scaleY = scaleY
                rotationZ = rotation
                alpha = opacity
            },
        update = { if (it.player !== player) it.player = player }
    )
}

@Composable
private fun DirectVideoPreviewCanvas(
    editor: EditorState,
    layer: com.kinetic.app.model.Layer,
    modifier: Modifier,
    qualityScale: Float,
    onVideoReady: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val uri = layer.uri ?: return
    val maxWidth = (COMP_W * qualityScale).toInt().coerceAtLeast(240)
    val maxHeight = (COMP_H * qualityScale).toInt().coerceAtLeast(135)
    val player = remember(context, qualityScale) {
        ExoPlayer.Builder(context).build().apply {
            trackSelectionParameters = TrackSelectionParameters.Builder(context)
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .setMaxVideoSize(maxWidth, maxHeight)
                .build()
            repeatMode = Player.REPEAT_MODE_OFF
        }
    }
    var videoReady by remember(player) { androidx.compose.runtime.mutableStateOf(player.playbackState == Player.STATE_READY) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                videoReady = playbackState == Player.STATE_READY
                onVideoReady(videoReady)
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                videoReady = true
                onVideoReady(true)
            }
        }
        player.addListener(listener)
        onVideoReady(videoReady)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(editor.playing, videoReady) { onVideoReady(videoReady) }

    val sourceTime = ((editor.time - layer.start).coerceAtLeast(0f) * layer.speed + layer.srcOffset)
        .coerceAtLeast(0f)
    val sourcePositionMs = (sourceTime * 1000f).toLong()

    LaunchedEffect(layer.id, uri, layer.speed) {
        player.pause()
        player.setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
        player.setPlaybackSpeed(layer.speed.coerceIn(0.1f, 4f))
        player.seekTo(sourcePositionMs)
        player.prepare()
    }

    LaunchedEffect(editor.playing, videoReady, layer.id, layer.speed) {
        if (editor.playing && videoReady) {
            player.seekTo(sourcePositionMs)
            player.play()
        } else {
            player.pause()
        }
    }

    val previousTimelineTime = remember(layer.id) {
        androidx.compose.runtime.mutableFloatStateOf(editor.time)
    }
    LaunchedEffect(editor.time, editor.playing, layer.id, layer.speed) {
        if (editor.playing) {
            // A composition loop wraps the timeline clock. Re-align the decoder
            // once at that boundary; otherwise let Media3 own continuous playback.
            if (editor.time + 0.05f < previousTimelineTime.floatValue) {
                player.seekTo(sourcePositionMs)
            }
        } else {
            // Coalesce rapid scrub updates so only the latest requested position
            // reaches the decoder.
            kotlinx.coroutines.delay(24)
            player.seekTo(sourcePositionMs)
        }
        previousTimelineTime.floatValue = editor.time
    }

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    this.player = player
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            modifier = Modifier
                .fillMaxWidth((layer.width / COMP_W).coerceIn(0.01f, 1f))
                .fillMaxHeight((layer.height / COMP_H).coerceIn(0.01f, 1f)),
            update = { view -> if (view.player !== player) view.player = player }
        )
    }
}

@Composable
private fun GpuCompositionPreviewCanvas(editor: EditorState, modifier: Modifier = Modifier, qualityScale: Float) {
    val context = LocalContext.current
    val frames = remember { mutableStateMapOf<String, Bitmap>() }
    val textTextures = remember { mutableMapOf<String, Pair<Int, Bitmap>>() }
    LaunchedEffect(editor.layers.map { it.id }) {
        val currentIds = editor.layers.mapTo(HashSet<String>()) { it.id }
        val stale = textTextures.keys.filter { it !in currentIds }
        stale.forEach { id -> textTextures.remove(id) }
    }

    for (layer in editor.layers) {
        val uri = layer.uri
        if (uri != null && (layer.type == LayerType.IMAGE || layer.type == LayerType.VIDEO)) {
            val active = layer.activeAt(editor.time)
            val sourceTime = ((editor.time - layer.start).coerceAtLeast(0f) * layer.speed + layer.srcOffset).coerceAtLeast(0f)
            val bucket = if (!active) -1 else if (layer.type == LayerType.VIDEO) (sourceTime * 12f).toInt() else 0
            key(layer.id) {
                DisposableEffect(layer.id) {
                    onDispose { frames.remove(layer.id) }
                }
                LaunchedEffect(uri, bucket) {
                    if (bucket >= 0) {
                        MediaLoader.bitmapFor(
                            context, Uri.parse(uri), layer.type, sourceTime,
                            if (qualityScale >= 0.75f) 12 else if (qualityScale >= 0.45f) 8 else 5,
                            (1920f * qualityScale).toInt().coerceIn(240, 1920)
                        )?.let { frames[layer.id] = it }
                    } else frames.remove(layer.id)
                }
            }
        }
    }

    val byId = editor.layers.associateBy { it.id }
    val renderLayers = ArrayList<GpuLayer>()
    for (layer in editor.layers.asReversed()) {
        if (!layer.visible || !layer.activeAt(editor.time) || layer.type == LayerType.AUDIO ||
            layer.type == LayerType.NULL || layer.type == LayerType.ADJUSTMENT) continue
        val bitmap = when (layer.type) {
            LayerType.IMAGE, LayerType.VIDEO -> frames[layer.id]
            LayerType.TEXT, LayerType.SHAPE -> {
                val signature = if (layer.type == LayerType.TEXT)
                    layer.text.hashCode() * 31 + layer.color
                else layer.color * 31 + layer.width.toInt() * 7 + layer.height.toInt()
                val cached = textTextures[layer.id]
                if (cached == null || cached.first != signature || cached.second.isRecycled) {
                    val w = layer.width.toInt().coerceIn(1, 4096)
                    val h = layer.height.toInt().coerceIn(1, 4096)
                    val generated = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = AndroidCanvas(generated)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = layer.color }
                    if (layer.type == LayerType.TEXT) {
                        paint.textAlign = Paint.Align.CENTER
                        paint.textSize = 120f
                        paint.typeface = Typeface.DEFAULT_BOLD
                        canvas.drawText(layer.text, w / 2f, h / 2f + 40f, paint)
                    } else {
                        canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 24f, 24f, paint)
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
            "Grayscale" in layer.effects, layer.blendMode
        )
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
    val scene = GpuScene(renderLayers, selectedLayer)
    val gpuFailed = remember { androidx.compose.runtime.mutableStateOf(false) }

    if (gpuFailed.value || !supportsGles2(context)) {
        CpuPreviewCanvas(editor, modifier)
    } else {
        val view = remember(context) { GpuPreviewSurfaceView(context) { gpuFailed.value = true } }
        AndroidView(
            factory = { view },
            modifier = modifier,
            update = { it.submit(scene) }
        )
        DisposableEffect(view) {
            onDispose { view.releaseRenderer() }
        }
    }
}

private class GpuPreviewSurfaceView(context: Context, onGpuFailure: () -> Unit) : GLSurfaceView(context) {
    private val gpuRenderer = CompositionGlRenderer(onGpuFailure)
    init {
        setEGLContextClientVersion(2)
        setPreserveEGLContextOnPause(true)
        setRenderer(gpuRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        setBackgroundColor(android.graphics.Color.BLACK)
    }

    fun submit(scene: GpuScene) {
        gpuRenderer.submit(scene)
        requestRender()
    }

    fun releaseRenderer() {
        queueEvent { gpuRenderer.release() }
    }
}

private class CompositionGlRenderer(private val onGpuFailure: () -> Unit) : GLSurfaceView.Renderer {
    @Volatile private var scene = GpuScene(emptyList(), null)
    private var program = 0
    private var solidProgram = 0
    private var positionHandle = 0
    private var uvHandle = 0
    private var samplerHandle = 0
    private var alphaHandle = 0
    private var grayHandle = 0
    private var solidPositionHandle = 0
    private var solidColorHandle = 0
    private val textures = HashMap<String, TextureEntry>()
    private var viewportWidth = 1
    private var viewportHeight = 1
    private val vertices = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val solidVertices = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private data class TextureEntry(val id: Int, val bitmap: Bitmap)

    fun submit(next: GpuScene) { scene = next }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.02f, 0.02f, 0.04f, 1f)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        // A recreated EGL context invalidates every texture/program from the old context.
        textures.clear()
        program = 0
        solidProgram = 0
        try {
            program = linkProgram(VERTEX_SHADER, TEXTURE_SHADER)
            solidProgram = linkProgram(VERTEX_SHADER, SOLID_SHADER)
            positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            uvHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
            samplerHandle = GLES20.glGetUniformLocation(program, "uTexture")
            alphaHandle = GLES20.glGetUniformLocation(program, "uAlpha")
            grayHandle = GLES20.glGetUniformLocation(program, "uGray")
            solidPositionHandle = GLES20.glGetAttribLocation(solidProgram, "aPosition")
            solidColorHandle = GLES20.glGetUniformLocation(solidProgram, "uColor")
        } catch (_: Throwable) {
            program = 0
            solidProgram = 0
            Handler(Looper.getMainLooper()).post { onGpuFailure() }
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (program == 0) return
        val current = scene
        val activeTextureIds = current.layers.mapTo(HashSet<String>()) { it.id }
        val staleIds = textures.keys.filter { it !in activeTextureIds }
        staleIds.forEach { id -> textures.remove(id)?.let { GLES20.glDeleteTextures(1, intArrayOf(it.id), 0) } }
        val scale = minOf(viewportWidth / COMP_W, viewportHeight / COMP_H)
        val offsetX = (viewportWidth - COMP_W * scale) / 2f
        val offsetY = (viewportHeight - COMP_H * scale) / 2f
        for (layer in current.layers) drawLayer(layer, scale, offsetX, offsetY)
        current.selected?.let { drawSelection(it, scale, offsetX, offsetY) }
    }

    private fun drawLayer(layer: GpuLayer, scale: Float, ox: Float, oy: Float) {
        val bitmap = layer.bitmap ?: return
        if (bitmap.isRecycled || layer.opacity <= 0f) return
        val texture = textureFor(layer.id, bitmap) ?: return
        val right = if (layer.mask) layer.width * 0.8f else layer.width
        val bottom = if (layer.mask) layer.height * 0.8f else layer.height
        val uvMax = if (layer.mask) 0.8f else 1f
        val points = floatArrayOf(0f, 0f, 0f, bottom, right, 0f, right, bottom)
        layer.matrix.mapPoints(points)
        val data = floatArrayOf(
            ndcX(points[0], scale, ox), ndcY(points[1], scale, oy), 0f, 0f,
            ndcX(points[2], scale, ox), ndcY(points[3], scale, oy), 0f, uvMax,
            ndcX(points[4], scale, ox), ndcY(points[5], scale, oy), uvMax, 0f,
            ndcX(points[6], scale, ox), ndcY(points[7], scale, oy), uvMax, uvMax
        )
        vertices.clear(); vertices.put(data); vertices.position(0)
        GLES20.glUseProgram(program)
        vertices.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 16, vertices)
        vertices.position(2)
        GLES20.glEnableVertexAttribArray(uvHandle)
        GLES20.glVertexAttribPointer(uvHandle, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(samplerHandle, 0)
        GLES20.glUniform1f(alphaHandle, layer.opacity)
        GLES20.glUniform1f(grayHandle, if (layer.grayscale) 1f else 0f)
        when (layer.blend) {
            "Multiply" -> GLES20.glBlendFunc(GLES20.GL_DST_COLOR, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            "Screen" -> GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_COLOR)
            "Add" -> GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
            else -> GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        }
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(uvHandle)
    }

    private fun textureFor(layerId: String, bitmap: Bitmap): Int? {
        val old = textures[layerId]
        if (old != null && old.bitmap === bitmap && old.id != 0) return old.id
        if (old != null) GLES20.glDeleteTextures(1, intArrayOf(old.id), 0)
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        if (ids[0] == 0) return null
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        textures[layerId] = TextureEntry(ids[0], bitmap)
        return ids[0]
    }

    private fun drawSelection(layer: GpuLayer, scale: Float, ox: Float, oy: Float) {
        val p = floatArrayOf(0f, 0f, layer.width, 0f, layer.width, layer.height, 0f, layer.height)
        layer.matrix.mapPoints(p)
        val data = floatArrayOf(
            ndcX(p[0], scale, ox), ndcY(p[1], scale, oy),
            ndcX(p[2], scale, ox), ndcY(p[3], scale, oy),
            ndcX(p[4], scale, ox), ndcY(p[5], scale, oy),
            ndcX(p[6], scale, ox), ndcY(p[7], scale, oy)
        )
        solidVertices.clear(); solidVertices.put(data); solidVertices.position(0)
        GLES20.glUseProgram(solidProgram)
        GLES20.glEnableVertexAttribArray(solidPositionHandle)
        GLES20.glVertexAttribPointer(solidPositionHandle, 2, GLES20.GL_FLOAT, false, 0, solidVertices)
        GLES20.glUniform4f(solidColorHandle, 0.52f, 0.40f, 1f, 1f)
        GLES20.glLineWidth(2f)
        GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, 4)
        GLES20.glDisableVertexAttribArray(solidPositionHandle)
    }

    private fun ndcX(x: Float, scale: Float, ox: Float) = ((ox + x * scale) / viewportWidth) * 2f - 1f
    private fun ndcY(y: Float, scale: Float, oy: Float) = 1f - ((oy + y * scale) / viewportHeight) * 2f

    fun release() {
        if (textures.isNotEmpty()) {
            GLES20.glDeleteTextures(textures.size, textures.values.map { it.id }.toIntArray(), 0)
            textures.clear()
        }
        if (program != 0) GLES20.glDeleteProgram(program)
        if (solidProgram != 0) GLES20.glDeleteProgram(solidProgram)
        program = 0; solidProgram = 0
    }

    private fun linkProgram(vertex: String, fragment: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertex)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragment)
        val result = GLES20.glCreateProgram()
        GLES20.glAttachShader(result, vs); GLES20.glAttachShader(result, fs)
        GLES20.glLinkProgram(result)
        val status = IntArray(1)
        GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vs); GLES20.glDeleteShader(fs)
        check(status[0] == GLES20.GL_TRUE) { GLES20.glGetProgramInfoLog(result) }
        return result
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source); GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { GLES20.glGetShaderInfoLog(shader) }
        return shader
    }

    companion object {
        private const val VERTEX_SHADER = "attribute vec2 aPosition; attribute vec2 aTexCoord; varying vec2 vTexCoord; void main(){ gl_Position=vec4(aPosition,0.0,1.0); vTexCoord=aTexCoord; }"
        private const val TEXTURE_SHADER = "precision mediump float; uniform sampler2D uTexture; uniform float uAlpha; uniform float uGray; varying vec2 vTexCoord; void main(){ vec4 c=texture2D(uTexture,vTexCoord); if(uGray>0.5){ float y=dot(c.rgb,vec3(0.299,0.587,0.114)); c.rgb=vec3(y); } gl_FragColor=vec4(c.rgb*uAlpha,c.a*uAlpha); }"
        private const val SOLID_SHADER = "precision mediump float; uniform vec4 uColor; void main(){ gl_FragColor=uColor; }"
    }
}
