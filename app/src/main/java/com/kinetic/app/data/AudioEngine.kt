package com.kinetic.app.data

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import com.kinetic.app.model.Layer
import com.kinetic.app.model.LayerType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class AudioEngine(private val ctx: Context, private val scope: CoroutineScope) {
    private val players = HashMap<String, MediaPlayer>()
    private val pending = HashSet<String>()
    private var released = false

    fun sync(layers: List<Layer>, time: Float, playing: Boolean) {
        if (released) return
        val ids = HashSet<String>()
        for (l in layers) {
            val u = l.uri ?: continue
            if (l.type != LayerType.AUDIO && l.type != LayerType.VIDEO) continue
            ids.add(l.id)
            val want = playing && l.visible && l.activeAt(time)
            val p = players[l.id]
            if (p == null) {
                if (want && pending.add(l.id)) load(l.id, u)
                continue
            }
            try {
                if (!want) {
                    if (p.isPlaying) p.pause()
                } else {
                    val pos = ((time - l.start + l.srcOffset) * 1000f).toInt().coerceAtLeast(0)
                    if (!p.isPlaying) {
                        p.seekTo(pos)
                        p.start()
                    } else if (abs(p.currentPosition - pos) > 300) {
                        p.seekTo(pos)
                    }
                }
            } catch (_: Exception) {
            }
        }
        val iter = players.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            if (e.key !in ids) {
                try { e.value.release() } catch (_: Exception) { }
                iter.remove()
            }
        }
    }

    private fun load(id: String, uri: String) {
        scope.launch {
            val mp = withContext(Dispatchers.IO) {
                try {
                    MediaPlayer().apply { setDataSource(ctx, Uri.parse(uri)); prepare() }
                } catch (e: Exception) {
                    null
                }
            }
            pending.remove(id)
            if (mp == null) return@launch
            if (released) mp.release() else players[id] = mp
        }
    }

    fun pauseAll() {
        for (p in players.values) {
            try { if (p.isPlaying) p.pause() } catch (_: Exception) { }
        }
    }

    fun release() {
        released = true
        for (p in players.values) {
            try { p.release() } catch (_: Exception) { }
        }
        players.clear()
    }
}
