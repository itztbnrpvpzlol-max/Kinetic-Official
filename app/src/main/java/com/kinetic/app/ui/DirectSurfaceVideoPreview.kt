package com.kinetic.app.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.kinetic.app.engine.FfmpegVideoPlayer
import com.kinetic.app.engine.NativePlaybackEngine
import com.kinetic.app.model.COMP_H
import com.kinetic.app.model.COMP_W
import com.kinetic.app.model.EditorState
import com.kinetic.app.model.Layer
import com.kinetic.app.model.PropId
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
        layer.effects.isNotEmpty()
    ) return false

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
    return defaults.all { (id, expected) ->
        val prop = layer.p(id)
        prop.keys.isEmpty() && prop.expression == null &&
            kotlin.math.abs(prop.base - expected) < 0.01f
    }
}

private class DirectVideoTextureView(
    context: Context,
    private val player: FfmpegVideoPlayer
) : TextureView(context), TextureView.SurfaceTextureListener {
    private var outputSurface: Surface? = null

    init {
        surfaceTextureListener = this
        isOpaque = true
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        outputSurface?.release()
        outputSurface = Surface(surfaceTexture)
        player.attachSurface(checkNotNull(outputSurface))
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        player.detachSurface()
        outputSurface?.release()
        outputSurface = null
        return true
    }

    fun releaseOutput() {
        player.detachSurface()
        outputSurface?.release()
        outputSurface = null
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
        ((editor.time - layer.start).coerceAtLeast(0f) * layer.speed + layer.srcOffset)
            .coerceAtLeast(0f) * 1000f
    ).toLong()

    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val fitScale = minOf(maxWidth.value / COMP_W, maxHeight.value / COMP_H)
        AndroidView(
            factory = { textureView },
            modifier = Modifier.size(
                (layer.width * fitScale).dp,
                (layer.height * fitScale).dp
            )
        )
    }

    DisposableEffect(player, textureView) {
        onDispose {
            textureView.releaseOutput()
            player.release()
        }
    }

    LaunchedEffect(player, editor.playing, layer.speed) {
        player.setSpeed(layer.speed.coerceIn(0.1f, 4f))
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

    LaunchedEffect(player, playback, layer.start, layer.srcOffset, layer.speed) {
        if (playback != null) {
            val clipStartUs = (layer.start * 1_000_000.0).toLong()
            val sourceOffsetUs = (layer.srcOffset * 1_000_000.0).toLong()
            val clipSpeed = layer.speed.coerceIn(0.1f, 4f)
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
