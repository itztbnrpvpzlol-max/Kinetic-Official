package com.kinetic.app.data

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.os.Process
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import com.kinetic.app.model.Layer
import com.kinetic.app.model.LayerType
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Timeline audio transport.
 *
 * All ExoPlayer instances are owned by a dedicated playback thread. UI-frame
 * callbacks only publish the newest transport snapshot; they never perform
 * player work or seek decoders on the Compose thread.
 */
class AudioEngine(context: Context) {
    private val appContext = context.applicationContext
    private val playbackThread = HandlerThread("KineticAudioPlayback", Process.THREAD_PRIORITY_AUDIO).apply { start() }
    private val handler = Handler(playbackThread.looper)
    private val players = HashMap<String, ExoPlayer>()
    private val playerUris = HashMap<String, String>()
    private val appliedSpeed = HashMap<String, Float>()
    private val appliedVolume = HashMap<String, Float>()
    private val lastCorrectionMs = HashMap<String, Long>()
    private val inactiveSinceMs = HashMap<String, Long>()
    private val syncQueued = AtomicBoolean(false)

    @Volatile private var released = false
    @Volatile private var latestSync: SyncRequest? = null

    private data class SyncRequest(
        val layers: List<Layer>,
        val time: Float,
        val playing: Boolean
    )

    /**
     * Reconcile the known media sources without eagerly creating a decoder for
     * every clip in the project. Players are created only when a clip becomes
     * active, which prevents long timelines from keeping dozens of decoders alive.
     */
    fun prepare(layers: List<Layer>) {
        if (released) return
        val sources = layers.asSequence()
            .filter { it.type == LayerType.AUDIO || it.type == LayerType.VIDEO }
            .mapNotNull { layer -> layer.uri?.let { layer.id to it } }
            .toMap()

        handler.post {
            if (released) return@post
            val removed = players.keys.filter { id -> sources[id] != playerUris[id] }
            removed.forEach(::releasePlayer)
        }
    }

    fun sync(layers: List<Layer>, time: Float, playing: Boolean) {
        if (released) return
        latestSync = SyncRequest(layers, time, playing)
        enqueueLatestSync()
    }

    private fun enqueueLatestSync() {
        if (released || !syncQueued.compareAndSet(false, true)) return
        handler.post {
            val request = latestSync
            try {
                if (!released && request != null) applySync(request)
            } finally {
                syncQueued.set(false)
                // If a newer transport snapshot arrived while this one was being
                // applied, process only that newest snapshot instead of queueing
                // every intermediate UI frame.
                if (!released && latestSync !== request) enqueueLatestSync()
            }
        }
    }

    private fun applySync(request: SyncRequest) {
        val now = SystemClock.elapsedRealtime()
        val activeIds = HashSet<String>()

        for (layer in request.layers) {
            val uri = layer.uri ?: continue
            if (layer.type != LayerType.AUDIO && layer.type != LayerType.VIDEO) continue
            activeIds.add(layer.id)

            val shouldPlay = request.playing && layer.visible && layer.volume > 0f && layer.activeAt(request.time)
            var player = players[layer.id]

            if (player != null && playerUris[layer.id] != uri) {
                releasePlayer(layer.id)
                player = null
            }

            if (!shouldPlay) {
                if (player != null) {
                    if (player.playWhenReady) player.playWhenReady = false
                    inactiveSinceMs.putIfAbsent(layer.id, now)
                    // Keep recently used players warm for quick scrubbing, but
                    // release idle decoders so large projects do not exhaust the device.
                    val inactiveAt = inactiveSinceMs[layer.id] ?: now
                    if (now - inactiveAt >= IDLE_PLAYER_RELEASE_MS) releasePlayer(layer.id)
                }
                continue
            }

            inactiveSinceMs.remove(layer.id)
            if (player == null) {
                player = createPlayer(uri)
                players[layer.id] = player
                playerUris[layer.id] = uri
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

            val targetMs = sourcePositionMs(layer, request.time)
            if (!player.playWhenReady) {
                player.seekTo(targetMs)
                player.playWhenReady = true
                lastCorrectionMs[layer.id] = now
                continue
            }

            // ExoPlayer owns the continuous audio clock. Correct only substantial
            // drift and do so infrequently; frequent seeks cause audible gaps.
            if (player.playbackState == Player.STATE_READY) {
                val drift = abs(player.currentPosition - targetMs)
                val last = lastCorrectionMs[layer.id] ?: 0L
                if (drift > DRIFT_CORRECTION_MS && now - last >= CORRECTION_COOLDOWN_MS) {
                    player.seekTo(targetMs)
                    lastCorrectionMs[layer.id] = now
                }
            }
        }

        val stale = players.keys.filter { it !in activeIds }
        stale.forEach(::releasePlayer)
    }

    private fun createPlayer(uri: String): ExoPlayer =
        ExoPlayer.Builder(appContext)
            .setLooper(playbackThread.looper)
            .build()
            .apply {
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

    private fun releasePlayer(id: String) {
        players.remove(id)?.release()
        playerUris.remove(id)
        appliedSpeed.remove(id)
        appliedVolume.remove(id)
        lastCorrectionMs.remove(id)
        inactiveSinceMs.remove(id)
    }

    fun pauseAll() {
        if (released) return
        handler.post {
            if (released) return@post
            val now = SystemClock.elapsedRealtime()
            players.forEach { (id, player) ->
                player.playWhenReady = false
                inactiveSinceMs.putIfAbsent(id, now)
            }
            // Release paused decoders after a short grace period. If playback
            // resumes first, applySync clears the inactive timestamp and keeps
            // the player warm.
            handler.postDelayed({
                if (!released) {
                    val expired = inactiveSinceMs.filterValues {
                        SystemClock.elapsedRealtime() - it >= IDLE_PLAYER_RELEASE_MS
                    }.keys.toList()
                    expired.forEach(::releasePlayer)
                }
            }, IDLE_PLAYER_RELEASE_MS)
        }
    }

    fun release() {
        if (released) return
        released = true
        latestSync = null
        handler.post {
            players.values.forEach { runCatching { it.release() } }
            players.clear()
            playerUris.clear()
            appliedSpeed.clear()
            appliedVolume.clear()
            lastCorrectionMs.clear()
            inactiveSinceMs.clear()
            playbackThread.quitSafely()
        }
    }

    companion object {
        private const val DRIFT_CORRECTION_MS = 700L
        private const val CORRECTION_COOLDOWN_MS = 1_500L
        private const val IDLE_PLAYER_RELEASE_MS = 2_500L
    }
}
