package com.kinetic.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.util.LruCache
import com.kinetic.app.model.COMP_H
import com.kinetic.app.model.COMP_W
import com.kinetic.app.model.Layer
import com.kinetic.app.model.LayerType
import com.kinetic.app.model.PropId
import com.kinetic.app.model.layerMatrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.max

/**
 * Stores fully composited preview frames. A cache key describes the evaluated
 * visual scene at a specific composition time, so edits only invalidate frames
 * whose rendered result actually changes.
 */
object CompositionFrameCache {
    private const val DIRECTORY = "kinetic-rendered-frames"
    private const val JPEG_QUALITY = 90
    private var memoryLimitBytes = 64 * 1024 * 1024
    private val memory = object : LruCache<String, Bitmap>(memoryLimitBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    @Volatile private var knownDiskBytes = -1L

    @Synchronized
    fun configure(memoryMb: Int) {
        val heapBudgetMb = (Runtime.getRuntime().maxMemory() / (1024L * 1024L) / 8L)
            .toInt().coerceIn(16, 128)
        memoryLimitBytes = memoryMb.coerceIn(16, 256).coerceAtMost(heapBudgetMb) * 1024 * 1024
        memory.resize(memoryLimitBytes)
        knownDiskBytes = -1L
    }

    fun frameKey(
        projectId: String,
        layers: List<Layer>,
        time: Float,
        fps: Int,
        width: Int,
        height: Int
    ): String {
        val byId = layers.associateBy { it.id }
        val signature = buildString {
            append("kinetic-frame-v2|").append(projectId).append('|')
                .append(fps).append('|').append(width).append('x').append(height)
            layers.asReversed().forEach { layer ->
                if (!layer.visible || !layer.activeAt(time) ||
                    layer.type == LayerType.AUDIO || layer.type == LayerType.NULL ||
                    layer.type == LayerType.ADJUSTMENT) return@forEach
                append("|L:").append(layer.id).append(':').append(layer.type)
                    .append(':').append(layer.uri).append(':').append(layer.text)
                    .append(':').append(layer.color).append(':')
                    .append(layer.width).append('x').append(layer.height)
                    .append(':').append(layer.maskEnabled).append(':').append(layer.blendMode)
                    .append(':').append(layer.effects.sorted().joinToString(","))
                if (layer.type == LayerType.VIDEO) {
                    val sourceTime = ((time - layer.start).coerceAtLeast(0f) * layer.speed + layer.srcOffset)
                        .coerceAtLeast(0f)
                    append(":src=").append(sourceTime.toBits())
                }
                PropId.values().forEach { prop ->
                    append(':').append(layer.p(prop).valueAt(time).toBits())
                }
                val matrix = layerMatrix(layer, time, byId)
                val values = FloatArray(9)
                matrix.getValues(values)
                values.forEach { append(':').append(it.toBits()) }
            }
        }
        return sha256(signature)
    }

    fun isCached(context: Context, key: String): Boolean {
        if (memory.get(key) != null) return true
        return frameFile(context, key).isFile
    }

    suspend fun hasCapacity(context: Context, diskLimitMb: Int): Boolean = withContext(Dispatchers.IO) {
        val directory = frameDirectory(context)
        if (!directory.exists()) return@withContext true
        val used = if (knownDiskBytes >= 0L) knownDiskBytes else {
            directory.listFiles()?.filter { it.isFile && it.extension == "jpg" }
                ?.sumOf { it.length() } ?: 0L
        }
        knownDiskBytes = used
        used < diskLimitMb.coerceIn(128, 4096) * 1024L * 1024L
    }

    suspend fun loadFrame(context: Context, key: String): Bitmap? {
        memory.get(key)?.let { return it }
        val file = frameFile(context, key)
        val decoded = withContext(Dispatchers.IO) {
            if (file.isFile) BitmapFactory.decodeFile(file.absolutePath) else null
        } ?: return null
        memory.put(key, decoded)
        return decoded
    }

    suspend fun renderAndStore(
        context: Context,
        projectId: String,
        layers: List<Layer>,
        time: Float,
        fps: Int,
        width: Int,
        height: Int,
        diskLimitMb: Int
    ): String? {
        val key = frameKey(projectId, layers, time, fps, width, height)
        if (isCached(context, key)) return key
        val rendered = withContext(MediaWorkers.cpuDispatcher) {
            renderCompositionFrame(context, layers, time, fps, width, height)
        } ?: return null
        val saved = withContext(Dispatchers.IO) {
            val directory = frameDirectory(context).apply { mkdirs() }
            val target = frameFile(context, key)
            if (target.isFile) return@withContext true
            val temporary = File(directory, "$key.part")
            val encoded = runCatching {
                FileOutputStream(temporary).use { output ->
                    rendered.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
                }
            }.getOrDefault(false)
            if (!encoded) {
                temporary.delete()
                return@withContext false
            }
            val maxBytes = diskLimitMb.coerceIn(128, 4096) * 1024L * 1024L
            val currentBytes = if (knownDiskBytes < 0L) {
                directory.listFiles()?.filter { it.isFile && it.extension == "jpg" }
                    ?.sumOf { it.length() } ?: 0L
            } else knownDiskBytes
            if (currentBytes + temporary.length() > maxBytes) {
                temporary.delete()
                knownDiskBytes = currentBytes
                return@withContext false
            }
            val length = temporary.length()
            val moved = temporary.renameTo(target)
            if (!moved) {
                runCatching { temporary.copyTo(target, overwrite = true); temporary.delete() }
            }
            if (target.isFile) {
                knownDiskBytes = currentBytes + length
                true
            } else false
        }
        if (!saved) {
            rendered.recycle()
            return null
        }
        memory.put(key, rendered)
        return key
    }

    suspend fun clear(context: Context) {
        withContext(Dispatchers.IO) {
            synchronized(memory) { memory.evictAll() }
            frameDirectory(context).deleteRecursively()
            knownDiskBytes = 0L
        }
    }

    private fun frameDirectory(context: Context) = File(context.filesDir, DIRECTORY)
    private fun frameFile(context: Context, key: String) = File(frameDirectory(context), "$key.jpg")

    private suspend fun renderCompositionFrame(
        context: Context,
        layers: List<Layer>,
        time: Float,
        fps: Int,
        width: Int,
        height: Int
    ): Bitmap? {
        val output = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK)
        canvas.scale(width / COMP_W, height / COMP_H)
        val byId = layers.associateBy { it.id }

        for (layer in layers.asReversed()) {
            if (!layer.visible || !layer.activeAt(time) ||
                layer.type == LayerType.AUDIO || layer.type == LayerType.NULL ||
                layer.type == LayerType.ADJUSTMENT) continue

            val opacity = (layer.p(PropId.OPACITY).valueAt(time) / 100f).coerceIn(0f, 1f)
            if (opacity <= 0f) continue
            val bitmap = when (layer.type) {
                LayerType.IMAGE -> layer.uri?.let {
                    MediaLoader.bitmapFor(context, Uri.parse(it), LayerType.IMAGE, 0f, 1, max(width, height), persistToDisk = false)
                }
                LayerType.VIDEO -> {
                    val uri = layer.uri ?: continue
                    val sourceTime = ((time - layer.start).coerceAtLeast(0f) * layer.speed + layer.srcOffset)
                        .coerceAtLeast(0f)
                    MediaLoader.bitmapFor(context, Uri.parse(uri), LayerType.VIDEO, sourceTime, fps, max(width, height), persistToDisk = false)
                }
                LayerType.TEXT, LayerType.SHAPE -> makeTextOrShapeBitmap(layer)
                else -> null
            }
            if (bitmap == null) {
                if (layer.type == LayerType.IMAGE || layer.type == LayerType.VIDEO) {
                    output.recycle()
                    return null
                }
                continue
            }

            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                alpha = (opacity * 255f).toInt()
                if ("Grayscale" in layer.effects) {
                    val matrix = ColorMatrix().apply { setSaturation(0f) }
                    colorFilter = ColorMatrixColorFilter(matrix)
                }
                xfermode = when (layer.blendMode) {
                    "Multiply" -> PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
                    "Screen" -> PorterDuffXfermode(PorterDuff.Mode.SCREEN)
                    "Add" -> PorterDuffXfermode(PorterDuff.Mode.ADD)
                    else -> null
                }
            }
            val matrix = layerMatrix(layer, time, byId)
            canvas.save()
            canvas.concat(matrix)
            if (layer.maskEnabled) canvas.clipRect(0f, 0f, layer.width * 0.8f, layer.height * 0.8f)
            canvas.drawBitmap(
                bitmap,
                Rect(0, 0, bitmap.width, bitmap.height),
                RectF(0f, 0f, layer.width, layer.height),
                paint
            )
            canvas.restore()
            paint.xfermode = null
            paint.colorFilter = null
            if (layer.type == LayerType.TEXT || layer.type == LayerType.SHAPE) bitmap.recycle()
        }
        return output
    }

    private fun makeTextOrShapeBitmap(layer: Layer): Bitmap {
        val width = layer.width.toInt().coerceIn(1, 4096)
        val height = layer.height.toInt().coerceIn(1, 4096)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = layer.color }
            if (layer.type == LayerType.TEXT) {
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = 120f
                paint.typeface = Typeface.DEFAULT_BOLD
                canvas.drawText(layer.text, width / 2f, height / 2f + 40f, paint)
            } else {
                canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 24f, 24f, paint)
            }
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
