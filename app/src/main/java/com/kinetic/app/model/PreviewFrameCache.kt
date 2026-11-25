package com.kinetic.app.model

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.FileOutputStream

/**
 * Per-frame validity signatures for the flattened root composition. A frame is reusable only
 * while every visible layer contributing to it and the composition settings remain unchanged.
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
 * Disk-backed composition prerender. JPEG frames survive RAM pressure and app restarts; only a
 * tiny decoded window stays in memory. Writes are atomic so an interrupted render never exposes
 * a partially-written frame. The timeline ranges represent verified frames on disk.
 */
class PreviewFrameCache {
    private class Entry(val sig: Long, val file: File, val size: Long)

    private val lock = Any()
    private val entries = HashMap<Int, Entry>()
    private val decoded = object : LinkedHashMap<Int, Pair<Long, Bitmap>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Pair<Long, Bitmap>>?): Boolean {
            val remove = size > DECODED_MAX
            if (remove) eldest?.value?.second?.let { if (!it.isRecycled) it.recycle() }
            return remove
        }
    }
    private var directory: File? = null
    private var bytes = 0L

    @Volatile var signatures: FrameSignatures? = null
        private set
    @Volatile var playheadFrame = 0
    var budgetBytes: Long = 512L * 1024L * 1024L

    var enabled by mutableStateOf(true)
    /** Valid prerendered frame ranges, displayed as the green timeline bar. */
    var ranges by mutableStateOf<List<IntRange>>(emptyList())
        private set
    var cachedFrames by mutableIntStateOf(0)
        private set
    var cachedBytes by mutableLongStateOf(0L)
        private set

    /** Attach this project's persistent cache directory and index existing frame files. */
    fun configureDisk(dir: File, maxBytes: Long) {
        synchronized(lock) {
            if (directory?.absolutePath == dir.absolutePath) {
                budgetBytes = maxBytes.coerceAtLeast(8L * 1024L * 1024L)
                return
            }
            decoded.values.forEach { if (!it.second.isRecycled) it.second.recycle() }
            decoded.clear()
            entries.clear()
            bytes = 0L
            directory = dir
            budgetBytes = maxBytes.coerceAtLeast(8L * 1024L * 1024L)
            dir.mkdirs()
            dir.listFiles()?.forEach { file ->
                if (!file.isFile || !file.name.endsWith(".jpg")) return@forEach
                val stem = file.name.removeSuffix(".jpg")
                val split = stem.indexOf('-')
                if (split <= 0) { file.delete(); return@forEach }
                val frame = stem.substring(0, split).toIntOrNull()
                val sig = stem.substring(split + 1).toLongOrNull()
                if (frame == null || sig == null || file.length() <= 0L) {
                    file.delete()
                } else {
                    val old = entries.put(frame, Entry(sig, file, file.length()))
                    if (old != null) { bytes -= old.size; old.file.delete() }
                    bytes += file.length()
                }
            }
            evictToBudget()
        }
    }

    fun setSignatures(sigs: FrameSignatures?) { signatures = sigs }

    /** Stop at the configured disk budget instead of evicting already-prerendered timeline frames. */
    fun isFull(): Boolean = synchronized(lock) { bytes >= budgetBytes * 0.98 }

    fun hasValid(frame: Int): Boolean {
        val sig = signatures?.sigAt(frame) ?: return false
        synchronized(lock) {
            val entry = entries[frame] ?: return false
            if (entry.sig != sig || !entry.file.isFile || entry.file.length() != entry.size) {
                removeEntry(frame)
                return false
            }
            return true
        }
    }

    /** Called from the background prerender worker. */
    fun put(frame: Int, sig: Long, data: ByteArray) {
        synchronized(lock) {
            val dir = directory ?: return
            if (data.isEmpty()) return
            val target = File(dir, "${frame}-${sig}.jpg")
            val temp = File(dir, "${frame}-${sig}.tmp")
            try {
                FileOutputStream(temp).use { stream ->
                    stream.write(data)
                    stream.fd.sync()
                }
                // Remove the old index/file before installing the replacement. Doing
                // this in the opposite order can delete the freshly-renamed target.
                removeEntry(frame)
                if (target.exists()) target.delete()
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                entries[frame] = Entry(sig, target, target.length())
                bytes += target.length()
                decoded.remove(frame)?.second?.let { if (!it.isRecycled) it.recycle() }
                evictToBudget()
            } catch (_: Throwable) {
                temp.delete()
                target.delete()
            }
        }
    }

    private fun removeEntry(frame: Int) {
        entries.remove(frame)?.let {
            bytes -= it.size
            it.file.delete()
        }
        decoded.remove(frame)?.second?.let { if (!it.isRecycled) it.recycle() }
    }

    private fun evictToBudget() {
        if (bytes <= budgetBytes) return
        val target = (budgetBytes * 0.92).toLong()
        // Keep the earliest contiguous prerender range intact. Evict the
        // far end first so the green progress bar remains meaningful.
        val order = entries.keys.sortedDescending()
        for (frame in order) {
            if (bytes <= target) break
            removeEntry(frame)
        }
    }

    private fun decode(file: File): Bitmap? = runCatching {
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }.getOrNull()

    /** Only returns a bitmap already decoded off the UI thread. */
    fun lookup(frame: Int): Bitmap? {
        if (!enabled) return null
        val sig = signatures?.sigAt(frame) ?: return null
        return synchronized(lock) {
            decoded[frame]?.let { cached ->
                if (cached.first == sig && !cached.second.isRecycled) return cached.second
            }
            null
        }
    }

    /** Read/decode a small forward window on the background worker before playback needs it. */
    fun prefetchDecode(from: Int, count: Int, total: Int) {
        for (frame in from until minOf(total, from + count)) {
            val sig = signatures?.sigAt(frame) ?: return
            val file = synchronized(lock) {
                val entry = entries[frame]
                if (decoded[frame]?.first == sig) null
                else entry?.takeIf { it.sig == sig && it.file.isFile }?.file
            } ?: continue
            val bitmap = decode(file) ?: continue
            synchronized(lock) {
                val current = entries[frame]
                if (current?.sig == sig && signatures?.sigAt(frame) == sig) {
                    decoded.put(frame, sig to bitmap)
                } else if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
    }

    /** Removes invalid frames and publishes exact contiguous on-disk ranges. */
    fun refreshRanges(total: Int) {
        val sigs = signatures ?: return
        val out = ArrayList<IntRange>()
        var start = -1
        var count = 0
        var size = 0L
        synchronized(lock) {
            val invalid = entries.keys.filter { frame ->
                frame !in 0 until total || entries[frame]?.let { it.sig != sigs.sigAt(frame) || !it.file.isFile } != false
            }
            invalid.forEach(::removeEntry)
            for (frame in 0 until total) {
                if (entries.containsKey(frame)) {
                    count++
                    if (start < 0) start = frame
                } else if (start >= 0) {
                    out += start..(frame - 1)
                    start = -1
                }
            }
            if (start >= 0) out += start..(total - 1)
            size = bytes
        }
        ranges = out
        cachedFrames = count
        cachedBytes = size
    }

    fun clear() {
        synchronized(lock) {
            entries.keys.toList().forEach(::removeEntry)
            decoded.values.forEach { if (!it.second.isRecycled) it.second.recycle() }
            decoded.clear()
            directory?.listFiles()?.forEach { it.delete() }
            bytes = 0L
        }
        ranges = emptyList()
        cachedFrames = 0
        cachedBytes = 0L
    }

    private companion object { const val DECODED_MAX = 16 }
}
