package com.kinetic.app.data

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Presentation
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Creates real, silent, low-resolution H.264 preview proxies.
 *
 * The original source remains untouched and is still used by audio playback
 * and any future export path. A failed/cancelled proxy build always falls back
 * to the original URI.
 */
@OptIn(UnstableApi::class)
object PreviewProxyStore {
    private const val MAX_CACHE_BYTES = 256L * 1024L * 1024L
    private const val TRIM_TO_BYTES = 192L * 1024L * 1024L
    private val buildMutex = Mutex()
    private val failedKeys = ConcurrentHashMap.newKeySet<String>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun findExisting(context: Context, source: Uri, targetHeight: Int): Uri? {
        val key = cacheKey(source, targetHeight)
        val file = proxyFile(context, key)
        return file.takeIf { it.isFile && it.length() > 1024L }?.also {
            it.setLastModified(System.currentTimeMillis())
        }?.let(Uri::fromFile)
    }

    suspend fun getOrCreate(context: Context, source: Uri, targetHeight: Int): Uri? {
        val appContext = context.applicationContext
        val safeHeight = targetHeight.coerceIn(240, 1080)
        findExisting(appContext, source, safeHeight)?.let { return it }
        val key = cacheKey(source, safeHeight)
        if (key in failedKeys) return null

        return buildMutex.withLock {
            findExisting(appContext, source, safeHeight)?.let { return@withLock it }
            if (key in failedKeys) return@withLock null

            val directory = File(appContext.cacheDir, "kinetic-preview-proxies").apply { mkdirs() }
            val output = proxyFile(appContext, key)
            val temporary = File(directory, "$key.partial.mp4")
            temporary.delete()

            val completed = try {
                transform(appContext, source, safeHeight, temporary)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                temporary.delete()
                throw cancelled
            } catch (_: Throwable) {
                false
            }

            if (!completed || !temporary.isFile || temporary.length() <= 1024L) {
                temporary.delete()
                failedKeys.add(key)
                return@withLock null
            }

            if (output.exists()) output.delete()
            if (!temporary.renameTo(output)) {
                runCatching { temporary.copyTo(output, overwrite = true); temporary.delete() }
            }
            if (!output.isFile || output.length() <= 1024L) {
                output.delete()
                temporary.delete()
                failedKeys.add(key)
                return@withLock null
            }

            if (output.length() > MAX_CACHE_BYTES) {
                output.delete()
                failedKeys.add(key)
                return@withLock null
            }
            output.setLastModified(System.currentTimeMillis())
            trimCache(directory, output)
            if (!output.isFile) return@withLock null
            Uri.fromFile(output)
        }
    }

    private suspend fun transform(
        context: Context,
        source: Uri,
        targetHeight: Int,
        output: File
    ): Boolean = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            try {
                val listener = object : Transformer.Listener {
                    override fun onCompleted(
                        composition: androidx.media3.transformer.Composition,
                        exportResult: ExportResult
                    ) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onError(
                        composition: androidx.media3.transformer.Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                }

                val activeTransformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .addListener(listener)
                    .build()

                val editedItem = EditedMediaItem.Builder(MediaItem.fromUri(source))
                    .setRemoveAudio(true)
                    .setEffects(
                        Effects(
                            emptyList(),
                            listOf(Presentation.createForHeight(targetHeight))
                        )
                    )
                    .build()

                continuation.invokeOnCancellation {
                    mainHandler.post { runCatching { activeTransformer.cancel() } }
                }
                activeTransformer.start(editedItem, output.absolutePath)
            } catch (_: Throwable) {
                if (continuation.isActive) continuation.resume(false)
            }
        }
    }

    private fun cacheKey(source: Uri, targetHeight: Int): String {
        val sourceFile = source.path?.let(::File)
        val fingerprint = buildString {
            append(source.toString())
            if (sourceFile?.isFile == true) {
                append('|').append(sourceFile.length())
                append('|').append(sourceFile.lastModified())
            }
            append("|h=").append(targetHeight)
            append("|v=1")
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(fingerprint.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun proxyFile(context: Context, key: String): File =
        File(File(context.cacheDir, "kinetic-preview-proxies"), "$key.mp4")

    private fun trimCache(directory: File, keep: File) {
        val files = directory.listFiles()?.filter { it.isFile && it.extension == "mp4" } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_CACHE_BYTES) return
        for (file in files.filter { it.absolutePath != keep.absolutePath }.sortedBy { it.lastModified() }) {
            if (total <= TRIM_TO_BYTES) break
            val size = file.length()
            if (file.delete()) total -= size
        }
        if (keep.length() > MAX_CACHE_BYTES) keep.delete()
    }
}
