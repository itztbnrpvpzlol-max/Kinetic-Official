package com.kinetic.app.model

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Decides whether a cached frame is still correct. The signature of a frame covers the composition
 * settings plus every layer that is on screen at that moment (and its whole parent chain: nulls,
 * cameras, precomps). Editing a layer therefore changes the signature of exactly the frames that layer
 * appears in; every other frame stays valid.
 */
class FrameSignatures(private val layers: List<Layer>, comp: CompSettings) {
    val fps: Int = comp.fps.coerceIn(1, 120)
    private val base: Long = ((comp.width * 31L + comp.height) * 31L + comp.fps) * 31L + comp.background + comp.shutterAngle.toBits()
    private val byId = layers.associateBy { it.id }
    private val chain = HashMap<String, Int>(layers.size * 2)

    init {
        fun chainHash(l: Layer, depth: Int): Int {
            val parent = l.parentId?.let { byId[it] }?.takeIf { depth < 16 && it.id != l.id }
            return l.hashCode() * 31 + (parent?.let { chainHash(it, depth + 1) } ?: 0)
        }
        layers.forEach { chain[it.id] = chainHash(it, 0) }
    }

    fun sigAt(frame: Int): Long {
        val t = frame / fps.toFloat()
        var h = base
        for (l in layers) {
            if (!l.visible || l.type == LayerType.AUDIO || !l.activeAt(t)) continue
            h = h * 1_000_003L + (chain[l.id] ?: 0)
        }
        return h
    }
}

/**
 * RAM frame cache for the preview. Frames are stored as small JPEGs (about 10x lighter than raw
 * bitmaps, so far more of the timeline fits), validated against [FrameSignatures] on every read, and
 * decoded just-in-time (a few frames ahead of the playhead while playing).
 */
class PreviewFrameCache {
    private class Entry(val sig: Long, val data: ByteArray)

    private val lock = Any()
    private val entries = HashMap<Int, Entry>()
    private val decoded = object : LinkedHashMap<Int, Pair<Long, Bitmap>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Pair<Long, Bitmap>>?): Boolean = size > DECODED_MAX
    }
    private var bytes = 0L

    @Volatile var signatures: FrameSignatures? = null
        private set
    @Volatile var playheadFrame = 0
    var budgetBytes: Long = 160L * 1024L * 1024L

    var enabled by mutableStateOf(true)
    /** Frame ranges (inclusive) that are cached AND still valid; drawn as the green bar on the timeline. */
    var ranges by mutableStateOf<List<IntRange>>(emptyList())
        private set
    var cachedFrames by mutableIntStateOf(0)
        private set
    var cachedBytes by mutableLongStateOf(0L)
        private set

    fun setSignatures(sigs: FrameSignatures?) { signatures = sigs }

    fun hasValid(frame: Int): Boolean {
        val sigs = signatures ?: return false
        val sig = sigs.sigAt(frame)
        synchronized(lock) { return entries[frame]?.sig == sig }
    }

    fun put(frame: Int, sig: Long, data: ByteArray) {
        synchronized(lock) {
            entries.put(frame, Entry(sig, data))?.let { bytes -= it.data.size }
            bytes += data.size
            decoded.remove(frame)
            if (bytes > budgetBytes) evictFarthest()
        }
    }

    private fun evictFarthest() {
        val head = playheadFrame
        val target = (budgetBytes * 0.9).toLong()
        val order = entries.keys.sortedByDescending { kotlin.math.abs(it - head) }
        for (f in order) {
            if (bytes <= target) break
            entries.remove(f)?.let { bytes -= it.data.size }
            decoded.remove(f)
        }
    }

    /** True when the budget is used up; the filler then only works on frames nearer the playhead than what it would evict. */
    fun isFull(): Boolean = synchronized(lock) { bytes > budgetBytes * 0.92 }

    fun farthestDistance(): Int = synchronized(lock) {
        val head = playheadFrame
        entries.keys.maxOfOrNull { kotlin.math.abs(it - head) } ?: 0
    }

    private fun decode(data: ByteArray): Bitmap? = runCatching {
        BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }.getOrNull()

    /** The cached picture for [frame], or null when it is missing or out of date. */
    fun lookup(frame: Int): Bitmap? {
        if (!enabled) return null
        val sigs = signatures ?: return null
        val sig = sigs.sigAt(frame)
        // Never decompress a JPEG on the composition/UI thread. The cache host
        // decodes a small playhead window on Dispatchers.Default instead.
        return synchronized(lock) {
            decoded[frame]?.let { cached ->
                if (cached.first == sig && !cached.second.isRecycled) return cached.second
            }
            null
        }
    }

    /** Decodes the next [count] frames after [from] into the small decoded cache so playback never waits on JPEG decode. */
    fun prefetchDecode(from: Int, count: Int, total: Int) {
        for (f in from until minOf(total, from + count)) {
            val sigs = signatures ?: return
            val sig = sigs.sigAt(f)
            val data = synchronized(lock) {
                if (decoded[f]?.first == sig) null else entries[f]?.takeIf { it.sig == sig }?.data
            } ?: continue
            val bmp = decode(data) ?: continue
            synchronized(lock) { decoded[f] = sig to bmp }
        }
    }

    /** Drops stale frames and republishes the green ranges. Cheap enough to call a few times a second. */
    fun refreshRanges(total: Int) {
        val sigs = signatures ?: return
        val out = ArrayList<IntRange>()
        var start = -1
        var count = 0
        var size = 0L
        synchronized(lock) {
            val it = entries.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (e.key >= total || e.value.sig != sigs.sigAt(e.key)) { bytes -= e.value.data.size; decoded.remove(e.key); it.remove() }
            }
            for (f in 0 until total) {
                if (entries.containsKey(f)) {
                    count++
                    if (start < 0) start = f
                } else if (start >= 0) { out += start..(f - 1); start = -1 }
            }
            if (start >= 0) out += start..(total - 1)
            size = bytes
        }
        ranges = out
        cachedFrames = count
        cachedBytes = size
    }

    fun clear() {
        synchronized(lock) { entries.clear(); decoded.clear(); bytes = 0L }
        ranges = emptyList(); cachedFrames = 0; cachedBytes = 0L
    }

    private companion object { const val DECODED_MAX = 8 }
}
