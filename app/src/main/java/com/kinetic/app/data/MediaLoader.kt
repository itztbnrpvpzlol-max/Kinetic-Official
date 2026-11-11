package com.kinetic.app.data

import android.content.Context
import android.os.Build
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import com.kinetic.app.model.LayerType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

object MediaLoader {
    class Info(val length: Float?, val width: Int, val height: Int)

    private val cache = object : LruCache<String, Bitmap>(128 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val retrievers = ConcurrentHashMap<String, MediaMetadataRetriever>()
    private val retrieverLocks = ConcurrentHashMap<String, Any>()

    fun configureCache(memoryMb: Int) { cache.resize(memoryMb.coerceIn(32, 512) * 1024 * 1024) }

    fun releaseAll() {
        retrievers.forEach { (key, retriever) ->
            synchronized(retrieverLocks.getOrPut(key) { Any() }) { runCatching { retriever.release() } }
        }
        retrievers.clear()
        retrieverLocks.clear()
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
    suspend fun bitmapFor(ctx: Context, uri: Uri, type: LayerType, timeSec: Float, sampleFps: Int = 5, maxEdge: Int = 960): Bitmap? =
        withContext(Dispatchers.IO) {
            val bucket = if (type == LayerType.VIDEO) (timeSec * sampleFps.toFloat()).toInt() else 0
            val key = if (type == LayerType.IMAGE) "$uri@image" else "$uri@$sampleFps@$maxEdge@$bucket"
            val hit = cache.get(key)
            if (hit != null) return@withContext hit
            val bmp = try {
                if (type == LayerType.IMAGE) decodeImage(ctx, uri) else videoFrame(ctx, uri, bucket / sampleFps.toFloat(), maxEdge)
            } catch (e: Exception) {
                null
            }
            if (bmp != null) cache.put(key, bmp)
            bmp
        }

    private fun decodeImage(ctx: Context, uri: Uri): Bitmap? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, b) }
        var sample = 1
        while (maxOf(b.outWidth, b.outHeight) / sample > 1600) sample *= 2
        val o = BitmapFactory.Options().apply { inSampleSize = sample }
        return ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
    }

    private fun videoFrame(ctx: Context, uri: Uri, sec: Float, maxEdge: Int): Bitmap? {
        val key = uri.toString()
        val lock = retrieverLocks.computeIfAbsent(key) { Any() }
        return synchronized(lock) {
            val r = retrievers.computeIfAbsent(key) {
                MediaMetadataRetriever().apply { setDataSource(ctx, uri) }
            }
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
