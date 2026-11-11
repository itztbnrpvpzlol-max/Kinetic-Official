package com.kinetic.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import com.kinetic.app.model.LayerType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MediaLoader {
    class Info(val length: Float?, val width: Int, val height: Int)

    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val retrievers = HashMap<String, MediaMetadataRetriever>()

    @Synchronized
    fun releaseAll() {
        retrievers.values.forEach { runCatching { it.release() } }
        retrievers.clear()
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

    @Synchronized
    private fun videoFrame(ctx: Context, uri: Uri, sec: Float, maxEdge: Int): Bitmap? {
        val r = retrievers.getOrPut(uri.toString()) {
            MediaMetadataRetriever().apply { setDataSource(ctx, uri) }
        }
        val f = r.getFrameAtTime((sec * 1_000_000f).toLong(), MediaMetadataRetriever.OPTION_CLOSEST)
            ?: return null
        val m = maxOf(f.width, f.height)
        if (m <= maxEdge) return f
        val s = maxEdge.toFloat() / m
        return Bitmap.createScaledBitmap(f, (f.width * s).toInt(), (f.height * s).toInt(), true)
    }
}
