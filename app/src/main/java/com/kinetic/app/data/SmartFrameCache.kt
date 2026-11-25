package com.kinetic.app.data

import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.kinetic.app.model.LayerType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt

/** Where a layer's source playhead is (or is about to be) and how it is moving. */
class PrefetchTarget(val bucket: Int, val playing: Boolean, val speed: Float, val preroll: Boolean)

/**
 * Decoded-frame cache for preview playback.
 *
 * - Frames are kept decoded in RAM so a hit returns the shared immutable bitmap instead of a
 *   decompress; callers must not recycle cache-owned bitmaps.
 *   recycles bitmaps stays safe.
 * - A per-layer prefetcher decodes ahead of the playhead (window grows when
 *   decoding is slow), fills around the playhead when idle, and prerolls the
 *   first frames of clips that are about to start.
 * - Eviction is distance and direction aware: frames behind the playhead while
 *   playing go first, then the furthest ahead; expensive-to-decode frames are
 *   kept longer; other media and stale playheads are evicted before the active one.
 * - When the exact frame is not ready during playback the closest cached frame
 *   is shown while the exact one decodes, instead of stalling.
 * - Decode time is tracked and preview resolution steps down when decoding
 *   cannot keep up, then recovers.
 */
object SmartFrameCache {
    private data class Key(val media: String, val fps: Int, val edge: Int, val bucket: Int)
    private class Entry(val bitmap: Bitmap, val bytes: Int, val costMs: Float)
    private class Head(var bucket: Int, var fps: Int, var playing: Boolean, var touchedMs: Long)

    private val lock = Any()
    private val frames = HashMap<Key, Entry>()
    private val heads = HashMap<String, Head>()
    private var bytes = 0L
    private val inFlight = ConcurrentHashMap.newKeySet<Key>()
    private val demand = AtomicInteger(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var budgetScale = 1f
    @Volatile private var decodeEmaMs = 0f
    @Volatile private var tier = 0

    private val baseBudget: Long = run {
        val max = Runtime.getRuntime().maxMemory()
        val share = if (MediaWorkers.isConstrainedDevice) max / 12L else max / 8L
        share.coerceIn(8L * 1024L * 1024L, 64L * 1024L * 1024L)
    }

    private fun budget(): Long = (baseBudget * budgetScale).toLong()

    /** Preview edge to request. Steps down only while playing and decode is slower than real time. */
    fun edgeFor(playing: Boolean, preferred: Int): Int {
        if (!playing) return preferred
        return when (tier) {
            2 -> minOf(preferred, 192)
            1 -> minOf(preferred, 240)
            else -> preferred
        }
    }

    private fun recordDecode(ms: Float, fps: Int) {
        val frameMs = 1000f / fps.coerceAtLeast(1)
        val e = if (decodeEmaMs <= 0f) ms else decodeEmaMs * 0.8f + ms * 0.2f
        decodeEmaMs = e
        tier = when {
            e > frameMs * 1.8f -> 2
            e > frameMs * 1.1f -> if (tier == 2 && e > frameMs * 1.4f) 2 else 1
            e < frameMs * 0.6f -> 0
            e < frameMs * 0.9f && tier == 2 -> 1
            else -> tier
        }
    }

    private fun noteHead(media: String, bucket: Int, fps: Int, playing: Boolean) {
        synchronized(lock) {
            val head = heads.getOrPut(media) { Head(bucket, fps, playing, 0L) }
            head.bucket = bucket
            head.fps = fps
            head.playing = playing
            head.touchedMs = System.currentTimeMillis()
        }
    }

    private fun score(key: Key, entry: Entry, now: Long): Float {
        val head = heads[key.media]
        val frameMs = 1000f / key.fps.coerceAtLeast(1)
        val keep = 1f + (entry.costMs / frameMs).coerceAtMost(4f) * 0.25f
        if (head == null || now - head.touchedMs > 4000L) {
            val age = if (head == null) 600f else (now - head.touchedMs) / 1000f
            return (1000f + age) / keep
        }
        val d = key.bucket / key.fps.toFloat() - head.bucket / head.fps.toFloat()
        val s = if (head.playing) {
            if (d >= 0f) d else -d * 4f + 0.1f
        } else abs(d)
        return s / keep
    }

    private fun trimLocked() {
        val limit = budget()
        if (bytes <= limit) return
        val now = System.currentTimeMillis()
        while (bytes > limit && frames.isNotEmpty()) {
            var worst: Key? = null
            var worstScore = -1f
            for ((key, entry) in frames) {
                val s = score(key, entry, now)
                if (s > worstScore) {
                    worstScore = s
                    worst = key
                }
            }
            val victim = worst ?: break
            frames.remove(victim)?.let { bytes -= it.bytes.toLong() }
        }
    }

    private fun put(key: Key, bitmap: Bitmap, costMs: Float) {
        if (bitmap.isRecycled) return
        val size = bitmap.byteCount
        synchronized(lock) {
            val old = frames.put(key, Entry(bitmap, size, costMs))
            if (old != null) bytes -= old.bytes.toLong()
            bytes += size.toLong()
            trimLocked()
        }
    }

    private fun has(key: Key): Boolean = synchronized(lock) { frames.containsKey(key) }

    private fun nearest(media: String, fps: Int, edge: Int, bucket: Int, maxLag: Int): Bitmap? = synchronized(lock) {
        var found: Bitmap? = null
        for (lag in 1..maxLag) {
            val older = frames[Key(media, fps, edge, bucket - lag)]
            if (older != null) { found = older.bitmap; break }
            val newer = frames[Key(media, fps, edge, bucket + lag)]
            if (newer != null) { found = newer.bitmap; break }
        }
        found
    }

    /**
     * Frame for [timeSec]. A cache hit is returned immediately. While playing, a miss
     * returns the closest cached frame (and decodes the exact one in the background)
     * so playback keeps moving; while paused the exact frame is awaited.
     */
    suspend fun fetch(
        ctx: Context, uri: Uri, timeSec: Float, fps: Int, edge: Int, playing: Boolean, persistToDisk: Boolean
    ): Bitmap? {
        val media = uri.toString()
        val bucket = (timeSec * fps).toInt()
        val key = Key(media, fps, edge, bucket)
        val exact = synchronized(lock) { frames[key]?.bitmap }
        if (exact != null) return exact.takeUnless { it.isRecycled }
        if (playing) {
            val near = nearest(media, fps, edge, bucket, maxOf(2, fps / 8))
            if (near != null) {
                scope.launch { decodeInto(ctx, uri, key) }
                return near.takeUnless { it.isRecycled }
            }
        }
        demand.incrementAndGet()
        try {
            // Only one decoder may own a frame key at a time. Prefetch and
            // foreground requests share this gate, so a slow decode cannot
            // trigger a second identical decode after an arbitrary 60 ms wait.
            while (true) {
                val ready = synchronized(lock) { frames[key]?.bitmap }
                if (ready != null) return ready.takeUnless { it.isRecycled }
                if (inFlight.add(key)) break
                delay(3L)
            }
            try {
                // Recheck after acquiring the gate: another decoder may have
                // populated the cache between the first lookup and our claim.
                val ready = synchronized(lock) { frames[key]?.bitmap }
                if (ready != null) return ready.takeUnless { it.isRecycled }
                val startedNs = System.nanoTime()
                val bitmap = MediaLoader.bitmapFor(ctx, uri, LayerType.VIDEO, timeSec, fps, edge, persistToDisk) ?: return null
                val ms = (System.nanoTime() - startedNs) / 1_000_000f
                recordDecode(ms, fps)
                put(key, bitmap, ms)
                return bitmap
            } finally {
                inFlight.remove(key)
            }
        } finally {
            demand.decrementAndGet()
        }
    }

    private suspend fun decodeInto(ctx: Context, uri: Uri, key: Key): Boolean {
        if (!inFlight.add(key)) return false
        try {
            if (has(key)) return false
            val startedNs = System.nanoTime()
            val bitmap = MediaLoader.bitmapFor(
                ctx, uri, LayerType.VIDEO, (key.bucket + 0.5f) / key.fps, key.fps, key.edge, false
            ) ?: return false
            val ms = (System.nanoTime() - startedNs) / 1_000_000f
            recordDecode(ms, key.fps)
            put(key, bitmap, ms)
            return true
        } finally {
            inFlight.remove(key)
        }
    }

    private fun plan(media: String, fps: Int, edge: Int, t: PrefetchTarget): List<Int> {
        val step = maxOf(1, abs(t.speed).roundToInt())
        val frameMs = 1000f / fps
        val slow = if (decodeEmaMs > 0f) (decodeEmaMs / frameMs).coerceIn(0.6f, 3f) else 1f
        val ahead = when {
            t.preroll -> maxOf(4, fps / 2)
            t.playing -> (fps * 0.6f * slow).toInt().coerceIn(maxOf(4, fps / 3), fps * 2)
            else -> maxOf(4, fps / 3)
        }
        val behind = if (t.playing || t.preroll) 0 else maxOf(2, fps / 4)
        val perFrame = (edge * edge * 9L / 16L * 4L).coerceAtLeast(1L)
        val cap = ((budget() / 2L) / perFrame).toInt().coerceAtLeast(4)
        val out = ArrayList<Int>()
        var k = 0
        while (out.size < cap && (k <= ahead || k <= behind)) {
            if (k <= ahead) {
                val b = t.bucket + k * step
                if (!has(Key(media, fps, edge, b))) out.add(b)
            }
            if (k in 1..behind) {
                val b = t.bucket - k * step
                if (b >= 0 && !has(Key(media, fps, edge, b))) out.add(b)
            }
            k++
        }
        return out
    }

    /**
     * Starts the background prefetcher for one video layer. [target] reports where the
     * layer's source playhead is, or null when nothing should be prefetched. The job is
     * tied to [scope], so it stops with the effect that launched it.
     */
    fun startPrefetch(
        scope: CoroutineScope, ctx: Context, uri: Uri, fps: Int,
        edgeOf: (Boolean) -> Int, target: () -> PrefetchTarget?
    ): Job = scope.launch {
        val media = uri.toString()
        while (isActive) {
            val t = target()
            if (t == null) {
                delay(80L)
                continue
            }
            val edge = edgeOf(t.playing)
            noteHead(media, t.bucket, fps, t.playing)
            var worked = false
            for (b in plan(media, fps, edge, t)) {
                if (!isActive) break
                if (demand.get() > 0) {
                    delay(4L)
                    break
                }
                val live = target() ?: break
                if (abs(live.bucket - t.bucket) > maxOf(3, fps / 3)) break
                if (decodeInto(ctx, uri, Key(media, fps, edge, b))) worked = true
            }
            delay(if (worked) 1L else if (t.playing) 10L else 70L)
        }
    }

    fun trim(level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> clear()
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                budgetScale = 0.5f
                synchronized(lock) { trimLocked() }
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> {
                budgetScale = 0.75f
                synchronized(lock) { trimLocked() }
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            frames.clear()
            heads.clear()
            bytes = 0L
        }
    }
}
