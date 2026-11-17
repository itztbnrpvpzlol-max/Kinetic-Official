package com.kinetic.app.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.kinetic.app.engine.FfmpegVideoPlayer
import com.kinetic.app.data.PreviewDiagnostics
import com.kinetic.app.engine.NativePlaybackEngine
import com.kinetic.app.model.COMP_H
import com.kinetic.app.model.COMP_W
import com.kinetic.app.model.EditorState
import com.kinetic.app.model.Layer
import com.kinetic.app.model.PropId
import com.kinetic.app.model.speedAt
import com.kinetic.app.model.sourceElapsedAt
import kotlinx.coroutines.delay

/**
 * Direct-to-Surface preview for a simple single-video composition.
 *
 * No decoded Bitmap, YUV-to-RGB conversion, or CPU Canvas upload is performed:
 * the video decoder renders into TextureView's SurfaceTexture and Android
 * composites that texture. Complex timelines continue using the established
 * multi-layer preview renderer.
 */
internal fun canUseDirectSurfaceVideo(layer: Layer): Boolean {
    if (layer.type != com.kinetic.app.model.LayerType.VIDEO ||
        layer.parentId != null || layer.maskEnabled || layer.blendMode != "Normal" ||
        layer.effects.isNotEmpty() || layer.effectStack.any { it.enabled }
    ) return false

    val speedProp = layer.p(PropId.SPEED)
    if (speedProp.keys.isNotEmpty() || speedProp.expression != null) return false

    val defaults = mapOf(
        PropId.ANCHOR_X to layer.width / 2f,
        PropId.ANCHOR_Y to layer.height / 2f,
        PropId.POS_X to COMP_W / 2f,
        PropId.POS_Y to COMP_H / 2f,
        PropId.SCALE_X to 100f,
        PropId.SCALE_Y to 100f,
        PropId.ROTATION to 0f,
        PropId.OPACITY to 100f
    )
    // TextureView is reserved for clips whose transforms and opacity remain
    // static for the entire composition. Animated properties use GLES.
    return defaults.all { (id, expected) ->
        val prop = layer.p(id)
        // TextureView is reserved for truly static transforms. Keyframed
        // transforms must go through the frame-synchronous GLES compositor;
        // otherwise SurfaceView/TextureView presentation can lag the timeline
        // and expose a black frame during scale/position transitions.
        prop.keys.isEmpty() && prop.expression == null &&
            kotlin.math.abs(prop.base - expected) < 0.01f
    }
}

private class DirectVideoTextureView(
    context: Context,
    private val player: FfmpegVideoPlayer
) : TextureView(context), TextureView.SurfaceTextureListener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var outputSurface: Surface? = null

    init {
        surfaceTextureListener = this
        isOpaque = false
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        outputSurface?.release()
        surfaceTexture.setDefaultBufferSize(1280, 720)
        outputSurface = Surface(surfaceTexture)
        player.attachSurface(checkNotNull(outputSurface))
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        player.markFrameAvailable()
        PreviewDiagnostics.recordSurfaceVideoFrame()
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        val oldSurface = outputSurface
        outputSurface = null
        // TextureView must not destroy the consumer while FFmpeg/MediaCodec is
        // still producing into it. Return false and release both sides only
        // after the decoder executor confirms native teardown is complete.
        player.detachSurface {
            mainHandler.post {
                runCatching { oldSurface?.release() }
                runCatching { surfaceTexture.release() }
            }
        }
        return false
    }

    fun releaseOutput() {
        val oldSurface = outputSurface ?: return
        outputSurface = null
        player.detachSurface {
            mainHandler.post { runCatching { oldSurface.release() } }
        }
    }
}

@Composable
internal fun DirectSurfaceVideoPreview(
    editor: EditorState,
    layer: Layer,
    modifier: Modifier,
    playback: NativePlaybackEngine?
) {
    val context = LocalContext.current
    val uri = Uri.parse(checkNotNull(layer.uri))
    val player = remember(uri) { FfmpegVideoPlayer(context, uri) }
    val textureView = remember(context, player) { DirectVideoTextureView(context, player) }
    val sourcePositionMs = (
        (layer.sourceElapsedAt(editor.time) + layer.srcOffset).coerceAtLeast(0f) * 1000f
    ).toLong()

    BoxWithConstraints(
        modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        val fitScale = minOf(maxWidth.value / COMP_W, maxHeight.value / COMP_H)
        AndroidView(
            factory = { textureView },
            modifier = Modifier.size(
                (layer.width * fitScale).dp,
                (layer.height * fitScale).dp
            ).graphicsLayer {
                val anchorX = layer.p(PropId.ANCHOR_X).valueAt(editor.time)
                val anchorY = layer.p(PropId.ANCHOR_Y).valueAt(editor.time)
                transformOrigin = TransformOrigin(
                    (anchorX / layer.width.coerceAtLeast(1f)).coerceIn(0f, 1f),
                    (anchorY / layer.height.coerceAtLeast(1f)).coerceIn(0f, 1f)
                )
                scaleX = layer.p(PropId.SCALE_X).valueAt(editor.time) / 100f
                scaleY = layer.p(PropId.SCALE_Y).valueAt(editor.time) / 100f
                rotationZ = layer.p(PropId.ROTATION).valueAt(editor.time)
                alpha = (layer.p(PropId.OPACITY).valueAt(editor.time) / 100f).coerceIn(0f, 1f)
                translationX = (layer.p(PropId.POS_X).valueAt(editor.time) - COMP_W / 2f) * fitScale * density
                translationY = (layer.p(PropId.POS_Y).valueAt(editor.time) - COMP_H / 2f) * fitScale * density
            }
        )
    }

    DisposableEffect(player, textureView) {
        PreviewDiagnostics.setSurfacePreviewActive(true)
        onDispose {
            PreviewDiagnostics.setSurfacePreviewActive(false)
            textureView.releaseOutput()
            player.release()
        }
    }

    LaunchedEffect(player, editor.playing, layer.p(PropId.SPEED).base) {
        player.setSpeed(layer.speedAt(editor.time))
        if (editor.playing) player.play() else player.pause()
    }

    val previousTimelineTime = remember(layer.id) {
        androidx.compose.runtime.mutableFloatStateOf(editor.time)
    }
    LaunchedEffect(player, editor.time, editor.playing, layer.id) {
        player.updatePositionHint(sourcePositionMs)
        if (editor.playing) {
            // Exact seeks are reserved for reverse jumps; the master clock
            // drives ordinary playback without seeking on every UI frame.
            if (editor.time + 0.05f < previousTimelineTime.floatValue) {
                player.seekTo(sourcePositionMs)
            }
        } else {
            delay(24L)
            player.seekTo(sourcePositionMs)
        }
        previousTimelineTime.floatValue = editor.time
    }

    LaunchedEffect(player, playback, layer.start, layer.srcOffset, layer.p(PropId.SPEED).base) {
        if (playback != null) {
            val clipStartUs = (layer.start * 1_000_000.0).toLong()
            val sourceOffsetUs = (layer.srcOffset * 1_000_000.0).toLong()
            val clipSpeed = layer.speedAt(editor.time)
            while (true) {
                val clock = playback.clockSnapshot()
                player.syncMasterClock(
                    clock.positionUs, clock.monotonicNs, clock.speed, clock.playing,
                    clipStartUs, sourceOffsetUs, clipSpeed, clock.generation
                )
                delay(33L)
            }
        }
    }
}
