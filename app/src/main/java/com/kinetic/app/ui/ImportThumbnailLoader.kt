package com.kinetic.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.CancellationSignal
import android.util.Size
import android.util.LruCache
import com.kinetic.app.data.MediaLoader
import com.kinetic.app.data.MediaWorkers
import com.kinetic.app.model.LayerType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Fast, bounded thumbnail path for the import browser.
 *
 * MediaStore's provider-managed thumbnails avoid repeatedly decoding full
 * source videos just to fill small grid cells. A small LRU keeps revisited
 * items instant without consuming a large part of the editor's 2 GB device RAM.
 */
internal object ImportThumbnailLoader {
    private const val THUMBNAIL_EDGE = 192
    private val decodeSlots = Semaphore(if (MediaWorkers.isConstrainedDevice) 1 else 3)
    private val memoryCache = object : LruCache<String, Bitmap>(
        if (MediaWorkers.isConstrainedDevice) 4 * 1024 * 1024 else 10 * 1024 * 1024
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount.coerceAtLeast(1)
    }

    suspend fun load(context: Context, item: ImportMediaEntry): Bitmap? =
        withContext(Dispatchers.IO) {
            val key = item.uri.toString() + "#import-thumb-$THUMBNAIL_EDGE"
            memoryCache.get(key)?.takeUnless { it.isRecycled }?.let { return@withContext it }

            decodeSlots.withPermit {
                memoryCache.get(key)?.takeUnless { it.isRecycled }?.let { return@withPermit it }

                val providerThumbnail = if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    item.uri.scheme == "content" &&
                    (item.type == LayerType.VIDEO || item.type == LayerType.IMAGE)
                ) {
                    runCatching {
                        context.contentResolver.loadThumbnail(
                            item.uri,
                            Size(THUMBNAIL_EDGE, THUMBNAIL_EDGE),
                            CancellationSignal()
                        )
                    }.getOrNull()
                } else null

                val bitmap = providerThumbnail ?: MediaLoader.bitmapFor(
                    context,
                    item.uri,
                    item.type,
                    0f,
                    sampleFps = 2,
                    maxEdge = THUMBNAIL_EDGE,
                    persistToDisk = false,
                    lowPriorityRequest = true
                )

                if (bitmap != null && !bitmap.isRecycled) {
                    memoryCache.put(key, bitmap)
                }
                bitmap
            }
        }

    fun trimMemory() {
        memoryCache.evictAll()
    }
}
