package com.kinetic.app.data

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import com.kinetic.app.model.Layer
import com.kinetic.app.model.LayerType
import kotlin.math.abs

/**
 * Timeline-aware audio transport.
 *
 * ExoPlayer's playback clock advances continuously between sync points. The editor
 * only seeks when a clip starts/resumes or when measured drift exceeds the correction
 * threshold, avoiding the repeated seek/rebuffer loop that causes audible stutter.
 */
class AudioEngine(context: Context) {
    private val appContext = context.applicationContext
    private val players = HashMap<String, ExoPlayer>()
    private val appliedSpeed = HashMap<String, Float>()
    private val appliedVolume = HashMap<String, Float>()
    private val lastCorrectionMs = HashMap<String, Long>()
    private var released = false

    fun prepare(layers: List<Layer>) {
        if (released) return
        for (layer in layers) {
            val uri = layer.uri ?: continue
            if (layer.type != LayerType.AUDIO && layer.type != LayerType.VIDEO) continue
            if (!players.containsKey(layer.id)) players[layer.id] = createPlayer(uri)
        }
    }

    fun sync(layers: List<Layer>, time: Float, playing: Boolean) {
        if (released) return

        val activeIds = HashSet<String>()
        for (layer in layers) {
            val uri = layer.uri ?: continue
            if (layer.type != LayerType.AUDIO && layer.type != LayerType.VIDEO) continue
            activeIds.add(layer.id)

            val shouldPlay = playing && layer.visible && layer.activeAt(time)
            var player = players[layer.id]
            if (player == null && shouldPlay) {
                player = createPlayer(uri)
                players[layer.id] = player
            }
            if (player == null) continue

            if (!shouldPlay) {
                if (player.playWhenReady) player.playWhenReady = false
                continue
            }

            val speed = layer.speed.coerceIn(0.1f, 4f)
            if (appliedSpeed[layer.id] != speed) {
                player.setPlaybackSpeed(speed)
                appliedSpeed[layer.id] = speed
            }
            val volume = layer.volume.coerceIn(0f, 2f)
            if (appliedVolume[layer.id] != volume) {
                player.volume = volume
                appliedVolume[layer.id] = volume
            }

            val targetMs = sourcePositionMs(layer, time)
            if (!player.playWhenReady) {
                // A paused clip may have been left at a stale source position.
                player.seekTo(targetMs)
                player.playWhenReady = true
                lastCorrectionMs[layer.id] = SystemClock.elapsedRealtime()
                continue
            }

            // Never seek a player while it is buffering. Once ready, correct only
            // meaningful drift and rate-limit corrections to avoid oscillation.
            if (player.playbackState == Player.STATE_READY) {
                val drift = abs(player.currentPosition - targetMs)
                val now = SystemClock.elapsedRealtime()
                val last = lastCorrectionMs[layer.id] ?: 0L
                if (drift > DRIFT_CORRECTION_MS && now - last >= CORRECTION_COOLDOWN_MS) {
                    player.seekTo(targetMs)
                    lastCorrectionMs[layer.id] = now
                }
            }
        }

        val stale = players.keys.filter { it !in activeIds }
        for (id in stale) {
            players.remove(id)?.release()
            appliedSpeed.remove(id)
            appliedVolume.remove(id)
            lastCorrectionMs.remove(id)
        }
    }

    private fun createPlayer(uri: String): ExoPlayer =
        ExoPlayer.Builder(appContext).build().apply {
            trackSelectionParameters = TrackSelectionParameters.Builder(appContext)
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .build()
            setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
            repeatMode = Player.REPEAT_MODE_OFF
            volume = 1f
            prepare()
        }

    private fun sourcePositionMs(layer: Layer, time: Float): Long =
        (((time - layer.start).coerceAtLeast(0f) * layer.speed + layer.srcOffset)
            .coerceAtLeast(0f) * 1000f).toLong()

    fun pauseAll() {
        for (player in players.values) player.playWhenReady = false
    }

    fun release() {
        if (released) return
        released = true
        for (player in players.values) player.release()
        players.clear()
        appliedSpeed.clear()
        appliedVolume.clear()
        lastCorrectionMs.clear()
    }

    companion object {
        // Keep correction tight enough to avoid obvious lip-sync drift without
        // turning small scheduling jitter into repeated decoder seeks.
        private const val DRIFT_CORRECTION_MS = 220L
        private const val CORRECTION_COOLDOWN_MS = 350L
    }
}
