package com.kinetic.app.data

import android.content.Context
import android.os.Build
import android.app.ActivityManager
import android.graphics.Bitmap
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import com.kinetic.app.model.LayerType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max

object MediaLoader {
    class Info(val length: Float?, val width: Int, val height: Int)

    // Bitmap memory is expensive on mobile. Keep the preview cache proportional
    // to the app heap instead of allowing a user preference to consume hundreds
    // of megabytes and trigger GC pauses or process-level memory pressure.
    private val cacheBudgetMb = MediaWorkers.bitmapCacheBudgetMb
    private val cache = object : LruCache<String, Bitmap>(cacheBudgetMb * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val retrievers = ConcurrentHashMap<String, MediaMetadataRetriever>()
    private val retrieverLocks = ConcurrentHashMap<String, Any>()
    private val retrieverRegistryLock = Any()
    private val retrieverLru = LinkedHashMap<String, Unit>(8, 0.75f, true)
    @Volatile private var maxCachedRetrievers = if (MediaWorkers.isConstrainedDevice) 1 else 4
    private val waveformCache = object : LruCache<String, FloatArray>(if (MediaWorkers.isConstrainedDevice) 2 * 1024 * 1024 else 8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: FloatArray): Int = value.size * 4
    }
    private val sourceProxyWrites = AtomicInteger()

    /**
     * Calculate a safe look-ahead count from the actual source-frame edge
     * and the current byte budget. The 448px default keeps the cache useful
     * for preview while preventing large decoded-frame bursts.
     */
    fun recommendedPrefetchFrameCount(
        activeVideoLayers: Int,
        maxEdge: Int = 448
    ): Int {
        val layers = activeVideoLayers.coerceAtLeast(1)
        val edge = maxEdge.coerceIn(240, 960).toLong()
        val worstCaseFrameBytes = edge * edge * 4L
        return (cache.maxSize().toLong() / worstCaseFrameBytes / layers)
            .toInt().coerceIn(1, 15)
    }

    /**
     * Keep decoded frames in a bounded RAM LRU. The user preference is only a
     * requested ceiling: the app heap and currently available system memory
     * always win, especially on 2 GB devices.
     */
    fun configureCache(context: Context, memoryMb: Int) {
        val manager = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        runCatching { manager?.getMemoryInfo(memoryInfo) }
        val availableMb = (memoryInfo.availMem / (1024L * 1024L)).toInt()
        val requested = memoryMb.coerceIn(16, 512).coerceAtMost(cacheBudgetMb)
        val adaptiveLimit = when {
            memoryInfo.lowMemory || availableMb < 160 -> 4
            availableMb < 256 -> 6
            availableMb < 384 -> 8
            else -> requested
        }
        cache.resize(minOf(requested, adaptiveLimit).coerceAtLeast(4) * 1024 * 1024)
        maxCachedRetrievers = if (
            memoryInfo.lowMemory || availableMb < 160 || MediaWorkers.isConstrainedDevice
        ) 1 else 4
        trimRetrieverCache(maxCachedRetrievers)
    }

    /** Called from Android memory callbacks. Keep only a small warm cache under pressure. */
    fun trimMemory(level: Int) {
        when {
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                cache.resize(4 * 1024 * 1024)
                cache.evictAll()
                maxCachedRetrievers = 1
            }
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> {
                val reduced = (cache.maxSize() / 4).coerceAtLeast(2 * 1024 * 1024)
                cache.resize(reduced)
                cache.trimToSize(reduced)
                maxCachedRetrievers = 1
            }
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                val reduced = (cache.maxSize() / 2).coerceAtLeast(2 * 1024 * 1024)
                cache.resize(reduced)
                cache.trimToSize(reduced)
                maxCachedRetrievers = if (MediaWorkers.isConstrainedDevice) 1 else 2
            }
        }
        when {
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ->
                trimRetrieverCache(0)
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ->
                trimRetrieverCache(if (MediaWorkers.isConstrainedDevice) 1 else 2)
        }
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            waveformCache.evictAll()
        }
    }

    fun releaseAll() {
        val toRelease = synchronized(retrieverRegistryLock) {
            val snapshot = retrievers.entries.map { it.key to it.value }
            retrievers.clear()
            retrieverLru.clear()
            snapshot
        }
        toRelease.forEach { (key, retriever) ->
            synchronized(retrieverLocks.getOrPut(key) { Any() }) {
                runCatching { retriever.release() }
            }
        }
        retrieverLocks.clear()
        waveformCache.evictAll()
    }

    private fun trimRetrieverCache(targetCount: Int) {
        val evicted = synchronized(retrieverRegistryLock) {
            val removed = ArrayList<Pair<String, MediaMetadataRetriever>>()
            while (retrieverLru.size > targetCount.coerceAtLeast(0)) {
                val oldestKey = retrieverLru.entries.iterator().next().key
                retrieverLru.remove(oldestKey)
                retrievers.remove(oldestKey)?.let { removed.add(oldestKey to it) }
            }
            removed
        }
        evicted.forEach { (key, retriever) ->
            val lock = retrieverLocks.getOrPut(key) { Any() }
            synchronized(lock) { runCatching { retriever.release() } }
        }
    }

    /** Obtain a retriever from a tiny device-aware LRU; release evictions safely. */
    private fun obtainRetriever(ctx: Context, uri: Uri, key: String): MediaMetadataRetriever {
        val evicted = ArrayList<Pair<String, MediaMetadataRetriever>>()
        val selected = synchronized(retrieverRegistryLock) {
            val existing = retrievers[key]
            if (existing != null) {
                retrieverLru[key] = Unit
                existing
            } else {
                val created = MediaMetadataRetriever().apply { setDataSource(ctx, uri) }
                retrievers[key] = created
                retrieverLru[key] = Unit
                while (retrieverLru.size > maxCachedRetrievers) {
                    val oldestKey = retrieverLru.entries.iterator().next().key
                    retrieverLru.remove(oldestKey)
                    retrievers.remove(oldestKey)?.let { evicted.add(oldestKey to it) }
                }
                created
            }
        }
        evicted.forEach { (oldKey, retriever) ->
            val lock = retrieverLocks.getOrPut(oldKey) { Any() }
            synchronized(lock) { runCatching { retriever.release() } }
        }
        return selected
    }

    fun displayName(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0).substringBeforeLast('.')
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment ?: "Media"
    }

    /** Blocking: call from Dispatchers.IO. */
    fun probe(ctx: Context, uri: Uri, type: LayerType): Info {
        return try {
            if (type == LayerType.IMAGE) {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
                Info(null, o.outWidth, o.outHeight)
            } else {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(ctx, uri)
                    val len = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.div(1000f)
                    var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    if (rot == 90 || rot == 270) { val tmp = w; w = h; h = tmp }
                    Info(len, w, h)
                } finally {
                    r.release()
                }
            }
        } catch (e: Exception) {
            Info(null, 0, 0)
        }
    }

    /** Image bitmap, or a video frame at [timeSec] (cached frame sampling, cached). */
    suspend fun bitmapFor(
        ctx: Context,
        uri: Uri,
        type: LayerType,
        timeSec: Float,
        sampleFps: Int = 5,
        maxEdge: Int = 960,
        persistToDisk: Boolean = true,
        lowPriorityPrefetch: Boolean = false
    ): Bitmap? = withContext(if (lowPriorityPrefetch) MediaWorkers.cacheDispatcher else MediaWorkers.cpuDispatcher) {
        val bucket = if (type == LayerType.VIDEO) (timeSec * sampleFps.toFloat()).toInt() else 0
        val sourceKey = mediaCacheIdentity(uri)
        val key = if (type == LayerType.IMAGE) "$sourceKey@image@$maxEdge" else "$sourceKey@$sampleFps@$maxEdge@$bucket"
        synchronized(key.intern()) {
            val hit = cache.get(key)
            if (hit != null) return@synchronized hit
            val diskFile = if (persistToDisk) previewFrameFile(ctx, key, type) else null
            val diskBitmap = diskFile?.takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.absolutePath) }
            val bmp = diskBitmap ?: try {
                if (type == LayerType.IMAGE) decodeImage(ctx, uri, maxEdge) else videoFrame(ctx, uri, bucket / sampleFps.toFloat(), maxEdge)
            } catch (e: Exception) {
                null
            }
            if (bmp != null) {
                cache.put(key, bmp)
                if (diskFile != null && diskBitmap == null) {
                    runCatching {
                        diskFile.parentFile?.mkdirs()
                        FileOutputStream(diskFile).use { output ->
                            val format = if (type == LayerType.IMAGE) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                            bmp.compress(format, 86, output)
                        }
                        // Keep source proxy frames bounded; the OS may also evict
                        // this cache directory when device storage gets tight.
                        if (sourceProxyWrites.incrementAndGet() % 32 == 0) {
                            trimPreviewDiskCache(ctx)
                        }
                    }
                }
            }
            bmp
        }
    }

    /**
     * Synchronous readiness probe used by the constrained-device transport.
     * It checks the exact same frame bucket and source fingerprint as bitmapFor.
     */
    fun hasCachedVideoFrame(
        ctx: Context,
        uri: Uri,
        timeSec: Float,
        sampleFps: Int,
        maxEdge: Int
    ): Boolean {
        val fps = sampleFps.coerceAtLeast(1)
        val bucket = (timeSec.coerceAtLeast(0f) * fps.toFloat()).toInt()
        val key = "${mediaCacheIdentity(uri)}@$fps@$maxEdge@$bucket"
        if (cache.get(key) != null) return true
        return previewFrameFile(ctx, key, LayerType.VIDEO).isFile
    }

    private fun mediaCacheIdentity(uri: Uri): String {
        val file = uri.path?.let(::File)
        return if (file?.isFile == true) {
            "${uri}|${file.length()}|${file.lastModified()}"
        } else uri.toString()
    }

    private fun previewFrameFile(ctx: Context, key: String, type: LayerType): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val extension = if (type == LayerType.IMAGE) "png" else "jpg"
        return File(File(ctx.cacheDir, "preview-frames"), "$digest.$extension")
    }

    private fun trimPreviewDiskCache(ctx: Context) {
        val directory = File(ctx.cacheDir, "preview-frames")
        val files = directory.listFiles()?.filter { it.isFile } ?: return
        var total = files.fold(0L) { sum, file -> sum + file.length() }
        if (total <= 256L * 1024L * 1024L) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= 192L * 1024L * 1024L) break
            val length = file.length()
            if (file.delete()) total -= length
        }
    }

    /**
     * Pre-decode a short look-ahead window into the RAM LRU while playback runs.
     * It is deliberately tiny and never writes to disk: playback remains the
     * priority, and the cache evicts older frames automatically.
     */
    suspend fun prefetchUpcomingVideoFrames(
        ctx: Context,
        uri: Uri,
        firstFrameTimeSec: Float,
        frameCount: Int = 2,
        sampleFps: Int = 12,
        maxEdge: Int = 240,
        sourceStepSec: Float = 1f / sampleFps
    ) = withContext(MediaWorkers.cacheDispatcher) {
        val count = frameCount.coerceIn(1, 15)
        for (index in 0 until count) {
            currentCoroutineContext().ensureActive()
            bitmapFor(
                ctx, uri, LayerType.VIDEO,
                firstFrameTimeSec + index * sourceStepSec.coerceAtLeast(0.001f),
                sampleFps, maxEdge, persistToDisk = false, lowPriorityPrefetch = true
            )
        }
    }

    /**
     * Fill a low-resolution, persistent frame cache in the background. This is
     * deliberately independent from playback: the editor never waits for it.
     * A maximum of 240 samples per clip bounds storage and decoder work.
     */
    suspend fun cacheVideoPreviewFrames(
        ctx: Context,
        uri: Uri,
        durationSec: Float,
        startOffsetSec: Float = 0f,
        playbackSpeed: Float = 1f,
        onProgress: suspend (Float) -> Unit
    ) = withContext(MediaWorkers.cacheDispatcher) {
        val duration = durationSec.coerceAtLeast(0.1f)
        // Cache a uniform sample across the whole clip, not just its first
        // four minutes. The 240-frame ceiling bounds disk use and decode work.
        val sampleCount = (duration * 2f).toInt().coerceIn(1, 240)
        val intervalSec = duration / sampleCount.toFloat()
        // TimelinePane requests the same 2 fps buckets for its four clip-strip
        // thumbnails, so these prebuilt samples are reusable by the timeline.
        val sampleFps = 2
        val speed = playbackSpeed.coerceIn(0.1f, 4f)
        trimPreviewDiskCache(ctx)
        for (index in 0 until sampleCount) {
            currentCoroutineContext().ensureActive()
            bitmapFor(
                ctx, uri, LayerType.VIDEO,
                startOffsetSec + index * intervalSec * speed,
                sampleFps, 240, persistToDisk = true, lowPriorityPrefetch = true
            )
            onProgress((index + 1).toFloat() / sampleCount)
        }
        trimPreviewDiskCache(ctx)
    }

    /**
     * Copies an imported content URI into app-private storage before it is added
     * to a project. This removes dependence on temporary document-provider access
     * and gives the playback pipeline a stable, seekable local source.
     */
    suspend fun cacheMedia(ctx: Context, uri: Uri): Uri = withContext(Dispatchers.IO) {
        if (uri.scheme != "content") return@withContext uri
        val resolver = ctx.contentResolver
        var displayName = "media"
        var expectedSize = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    displayName = cursor.getString(0) ?: "media"
                    if (!cursor.isNull(1)) expectedSize = cursor.getLong(1)
                }
            }
        }
        val extension = displayName.substringAfterLast('.', "").lowercase()
            .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }?.let { ".$it" } ?: ""
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((uri.toString() + "|" + expectedSize).toByteArray())
            .joinToString("") { "%02x".format(it) }
        val directory = File(ctx.filesDir, "media-cache").apply { mkdirs() }
        val destination = File(directory, digest + extension)
        if (destination.isFile && (expectedSize < 0L || destination.length() == expectedSize)) {
            return@withContext Uri.fromFile(destination)
        }
        val temporary = File(directory, "$digest.part")
        try {
            val input = resolver.openInputStream(uri) ?: error("Unable to open imported media")
            input.use { source ->
                FileOutputStream(temporary).use { output -> source.copyTo(output, 256 * 1024) }
            }
            if (expectedSize >= 0L && temporary.length() != expectedSize) {
                error("Imported media copy was incomplete")
            }
            if (destination.exists()) destination.delete()
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }
            Uri.fromFile(destination)
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    /**
     * Decodes the audio stream to PCM off the UI thread and caches normalized
     * peak amplitudes. The returned array is small and safe to retain per clip.
     */
    suspend fun waveform(ctx: Context, uri: Uri, bins: Int = 240): FloatArray? =
        withContext(MediaWorkers.cpuDispatcher) {
            val key = uri.toString() + "#waveform#" + bins
            waveformCache.get(key)?.clone() ?: decodeWaveform(ctx, uri, bins.coerceIn(32, 1024))?.also {
                waveformCache.put(key, it)
            }?.clone()
        }

    private fun decodeWaveform(ctx: Context, uri: Uri, bins: Int): FloatArray? {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(ctx, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            val inputFormat = extractor.getTrackFormat(track)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null
            val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION))
                inputFormat.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(1L) else 0L
            var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(1)
            var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            val peaks = FloatArray(bins)
            var sampleCursor = 0L
            val samplesPerBin = if (durationUs > 0L)
                durationUs.toDouble() * sampleRate * channels / 1_000_000.0 / bins else 0.0

            extractor.selectTrack(track)
            val codec = MediaCodec.createDecoderByType(mime)
            decoder = codec
            codec.configure(inputFormat, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val deadline = SystemClock.elapsedRealtime() + 120_000L

            while (!outputDone && SystemClock.elapsedRealtime() < deadline) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        if (inputBuffer == null) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            inputBuffer.clear()
                            val size = extractor.readSampleData(inputBuffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000L)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = codec.outputFormat
                        if (outputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                            sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(1)
                        if (outputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                            channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING))
                            pcmEncoding = outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> if (outputIndex >= 0) {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            codec.getOutputBuffer(outputIndex)?.let { raw ->
                                raw.position(info.offset)
                                raw.limit(info.offset + info.size)
                                val pcm = raw.slice().order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                    while (pcm.remaining() >= 4) {
                                        val amplitude = abs(pcm.float).coerceIn(0f, 1f)
                                        val bin = if (samplesPerBin > 0.0)
                                            (sampleCursor / samplesPerBin).toInt().coerceIn(0, bins - 1)
                                        else (sampleCursor % bins.toLong()).toInt()
                                        peaks[bin] = max(peaks[bin], amplitude)
                                        sampleCursor++
                                    }
                                } else {
                                    while (pcm.remaining() >= 2) {
                                        val amplitude = abs(pcm.short.toInt() / 32768f).coerceIn(0f, 1f)
                                        val bin = if (samplesPerBin > 0.0)
                                            (sampleCursor / samplesPerBin).toInt().coerceIn(0, bins - 1)
                                        else (sampleCursor % bins.toLong()).toInt()
                                        peaks[bin] = max(peaks[bin], amplitude)
                                        sampleCursor++
                                    }
                                }
                            }
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            return peaks.takeIf { it.any { peak -> peak > 0f } }
        } catch (_: Throwable) {
            return null
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun decodeImage(ctx: Context, uri: Uri, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxEdge.coerceAtLeast(240)) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun videoFrame(ctx: Context, uri: Uri, sec: Float, maxEdge: Int): Bitmap? {
        val key = uri.toString()
        val lock = retrieverLocks.computeIfAbsent(key) { Any() }
        return synchronized(lock) {
            val r = obtainRetriever(ctx, uri, key)
            val timeUs = (sec * 1_000_000f).toLong()
            if (Build.VERSION.SDK_INT >= 27) {
                var sourceW = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: maxEdge
                var sourceH = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: maxEdge
                val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (rotation == 90 || rotation == 270) { val swap = sourceW; sourceW = sourceH; sourceH = swap }
                val scale = minOf(1f, maxEdge.toFloat() / maxOf(sourceW, sourceH, 1))
                val targetW = (sourceW * scale).toInt().coerceAtLeast(1)
                val targetH = (sourceH * scale).toInt().coerceAtLeast(1)
                r.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, targetW, targetH)
            } else {
                val frame = r.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST) ?: return@synchronized null
                val longest = maxOf(frame.width, frame.height)
                if (longest <= maxEdge) frame else {
                    val scale = maxEdge.toFloat() / longest
                    Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt(), (frame.height * scale).toInt(), true)
                }
            }
        }
    }
}
