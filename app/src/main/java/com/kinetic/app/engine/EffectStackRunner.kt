package com.kinetic.app.engine

import android.graphics.Bitmap
import com.kinetic.app.model.EffectInstance
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.LinkedHashMap

/**
 * Runs a layer's EffectCraft effect stack. Keyframes are evaluated here (Kinetic owns animation)
 * and handed to the stateless native library as plain values.
 */
object EffectStackRunner {
    /** Effects that act on pictures (everything except the audio effects). */
    fun pictureEffects(stack: List<EffectInstance>): List<EffectInstance> =
        stack.filter { it.enabled && EffectCatalog.find(it.effectId)?.audio == false }

    fun audioEffects(stack: List<EffectInstance>): List<EffectInstance> =
        stack.filter { it.enabled && EffectCatalog.find(it.effectId)?.audio == true }

    fun hasPictureEffects(stack: List<EffectInstance>): Boolean = pictureEffects(stack).isNotEmpty()

    /** True when the picture output can change from one frame to the next on its own. */
    fun isTimeDependent(stack: List<EffectInstance>): Boolean =
        pictureEffects(stack).any { effect ->
            effect.parameterKeys.values.any { it.isNotEmpty() } ||
                EffectCatalog.find(effect.effectId)?.sourceCategory in TIME_DEPENDENT_SOURCES
        }

    private val TIME_DEPENDENT_SOURCES = setOf("Simulation", "Time", "Transition", "Noise & Grain", "Generate")

    /**
     * JSON handed to the native engine for [effects] at timeline time [time]:
     * `[{ "id": "ec.blur.gaussian", "enabled": true, "seed": 7, "params": { "blurriness": 12.0 } }]`.
     */
    fun stackJson(effects: List<EffectInstance>, time: Float): String {
        val array = JSONArray()
        for (effect in effects) {
            val spec = EffectCatalog.find(effect.effectId) ?: continue
            val params = JSONObject()
            for (param in spec.params) {
                fun v(component: EcComponent): Double = effect.parameterAt(component.key, time, component.default).toDouble()
                val parts = param.components
                if (parts.isEmpty()) {
                    val text = effect.texts[param.id]
                    if (text != null) params.put(param.id, text)
                    continue
                }
                when (param.kind) {
                    EcKind.POINT, EcKind.POINT3, EcKind.COLOR ->
                        params.put(param.id, JSONArray().also { a -> parts.forEach { a.put(v(it)) } })
                    EcKind.CHECKBOX -> params.put(param.id, v(parts[0]) >= 0.5)
                    EcKind.POPUP, EcKind.MASK -> params.put(param.id, Math.round(v(parts[0])).toInt())
                    else -> params.put(param.id, v(parts[0]))
                }
            }
            array.put(JSONObject().put("id", effect.effectId).put("enabled", effect.enabled)
                .put("seed", effect.seed).put("params", params))
        }
        return array.toString()
    }

    private data class CachedEffectBitmap(
        val bitmap: Bitmap,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    )

    // Bounded layer-effect cache. Cached bitmaps are immutable; callers receive
    // their own copy because existing render paths recycle their results.
    private val effectCacheLock = Any()
    private val effectBitmapCache = LinkedHashMap<String, CachedEffectBitmap>(24, 0.75f, true)
    private var effectBitmapCacheBytes = 0L
    private val effectBitmapCacheLimit = 24L * 1024L * 1024L

    private fun effectCacheKey(
        source: Bitmap, effects: List<EffectInstance>, time: Float, layerTime: Float,
        frameRate: Float, layerWidth: Float, layerHeight: Float, mode: String
    ): String {
        val active = if (mode.startsWith("audio")) audioEffects(effects) else pictureEffects(effects)
        val timeDependent = active.any { effect ->
            effect.parameterKeys.values.any { it.isNotEmpty() } ||
                EffectCatalog.find(effect.effectId)?.sourceCategory in TIME_DEPENDENT_SOURCES
        }
        val evaluatedTime = if (timeDependent) time else 0f
        return buildString {
            append(mode).append('|')
            append(System.identityHashCode(source)).append(':').append(source.generationId).append(':')
            append(source.width).append('x').append(source.height).append('|')
            append(stackJson(active, evaluatedTime)).append('|')
            append(layerTime.takeIf { timeDependent } ?: 0f).append('|')
            append(frameRate).append('|').append(layerWidth).append('x').append(layerHeight)
        }
    }

    private fun cachedEffect(key: String): CachedEffectBitmap? = synchronized(effectCacheLock) {
        val cached = effectBitmapCache[key] ?: return@synchronized null
        if (cached.bitmap.isRecycled) {
            effectBitmapCache.remove(key)
            effectBitmapCacheBytes -= cached.bitmap.allocationByteCount.toLong()
            return@synchronized null
        }
        cached
    }

    private fun rememberEffect(key: String, bitmap: Bitmap, left: Float = 0f, top: Float = 0f, right: Float = 0f, bottom: Float = 0f) {
        val copy = runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: return
        synchronized(effectCacheLock) {
            effectBitmapCache.remove(key)?.let { old ->
                effectBitmapCacheBytes -= old.bitmap.allocationByteCount.toLong()
                // Cache entries may be read concurrently; drop ownership and let GC reclaim.
            }
            effectBitmapCache[key] = CachedEffectBitmap(copy, left, top, right, bottom)
            effectBitmapCacheBytes += copy.allocationByteCount.toLong()
            val iterator = effectBitmapCache.entries.iterator()
            while (effectBitmapCacheBytes > effectBitmapCacheLimit && iterator.hasNext()) {
                val old = iterator.next().value
                iterator.remove()
                effectBitmapCacheBytes -= old.bitmap.allocationByteCount.toLong()
                // Do not recycle a bitmap another render thread may currently be copying.
            }
        }
    }

    /** Explicitly drops retained effect results, for memory-pressure handling. */
    fun clearEffectCache() = synchronized(effectCacheLock) {
        // Effect results are immutable and may be read concurrently. Drop references
        // instead of recycling bitmaps that another render thread may be copying.
        effectBitmapCache.clear()
        effectBitmapCacheBytes = 0L
    }

    private val directBuffers = object : ThreadLocal<ByteBuffer?>() {}
    // Keep reusable native memory modest on low-RAM devices. Large export frames
    // get a temporary buffer instead of pinning their full size to a worker thread.
    private const val MAX_RETAINED_SCRATCH_BYTES = 16 * 1024 * 1024

    private fun pixelBufferBytes(width: Int, height: Int): Int? {
        if (width <= 0 || height <= 0) return null
        val bytes = width.toLong() * height.toLong() * 4L
        return bytes.takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt()
    }

    private fun scratch(bytes: Int): ByteBuffer {
        require(bytes > 0) { "Invalid effect buffer size: $bytes" }
        val cached = directBuffers.get()
        if (bytes > MAX_RETAINED_SCRATCH_BYTES) {
            // Do not retain unusually large export buffers for the lifetime of this thread.
            return ByteBuffer.allocateDirect(bytes)
        }
        if (cached != null && cached.capacity() >= bytes) return cached.also { it.clear() }
        return ByteBuffer.allocateDirect(bytes).also { directBuffers.set(it) }
    }

    /**
     * Returns a processed copy of [source], or null when nothing was applied or the engine is
     * unavailable. The shared decoded bitmap is never modified. [time] is timeline time (keyframes);
     * [layerTime] is time since the layer started (what time-based effects animate against).
     * [adjustment] runs the stack the way an adjustment layer would on the composite below it.
     */
    fun processBitmap(
        source: Bitmap, effects: List<EffectInstance>, time: Float, layerTime: Float,
        frameRate: Float, layerWidth: Float, layerHeight: Float, adjustment: Boolean = false,
        cacheResult: Boolean = true
    ): Bitmap? {
        val active = pictureEffects(effects)
        if (active.isEmpty() || !EffectCraftNative.available || source.isRecycled) return null
        val bufferBytes = pixelBufferBytes(source.width, source.height) ?: return null
        val key = if (cacheResult) effectCacheKey(source, active, time, layerTime, frameRate, layerWidth, layerHeight, "bitmap:$adjustment") else null
        if (key != null) cachedEffect(key)?.let { cached ->
            return runCatching { cached.bitmap.copy(Bitmap.Config.ARGB_8888, true) }.getOrNull()
        }
        val output = runCatching { source.copy(Bitmap.Config.ARGB_8888, true) }.getOrNull() ?: return null
        val applied = runCatching {
            val buffer = scratch(bufferBytes)
            output.copyPixelsToBuffer(buffer)
            val count = EffectCraftNative.nativeProcessImage(
                buffer, output.width, output.height, layerWidth.toDouble(), layerHeight.toDouble(), stackJson(active, time),
                layerTime.toDouble(), frameRate.toDouble(), adjustment
            )
            if (count > 0) {
                buffer.rewind()
                output.copyPixelsFromBuffer(buffer)
            }
            count
        }.getOrDefault(-1)
        if (applied > 0) {
            if (key != null) rememberEffect(key, output)
            return output
        }
        if (!output.isRecycled) output.recycle()
        return null
    }

    /** An effected frame plus the room its effects took outside the layer, in layer units. */
    class Effected(val bitmap: Bitmap, val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val grown: Boolean get() = left > 0f || top > 0f || right > 0f || bottom > 0f
    }

    /**
     * Like [processBitmap] but keeps everything the effects painted beyond the clip, shape or text
     * bounds. [layerWidth]/[layerHeight] are the layer's logical size, used to express the margins
     * in layer units so callers can enlarge the drawn quad.
     */
    fun processBitmapGrown(
        source: Bitmap, effects: List<EffectInstance>, time: Float, layerTime: Float, frameRate: Float,
        layerWidth: Float, layerHeight: Float, maxPixels: Int, adjustment: Boolean = false,
        cacheResult: Boolean = true
    ): Effected? {
        val active = pictureEffects(effects)
        if (active.isEmpty() || !EffectCraftNative.available || source.isRecycled) return null
        val key = if (cacheResult) effectCacheKey(source, active, time, layerTime, frameRate, layerWidth, layerHeight, "grown:$adjustment:$maxPixels") else null
        if (key != null) cachedEffect(key)?.let { cached ->
            val copy = runCatching { cached.bitmap.copy(Bitmap.Config.ARGB_8888, true) }.getOrNull()
            if (copy != null) return Effected(copy, cached.left, cached.top, cached.right, cached.bottom)
        }
        return runCatching {
            val w = source.width
            val h = source.height
            val inputBytes = pixelBufferBytes(w, h) ?: return@runCatching null
            val input = scratch(inputBytes)
            val copy = if (source.config == Bitmap.Config.ARGB_8888) source else source.copy(Bitmap.Config.ARGB_8888, false)
            copy.copyPixelsToBuffer(input)
            if (copy !== source) copy.recycle()
            val info = IntArray(6)
            val count = EffectCraftNative.nativeProcessImageGrow(
                input, w, h, layerWidth.toDouble(), layerHeight.toDouble(), stackJson(active, time), layerTime.toDouble(), frameRate.toDouble(),
                adjustment, maxPixels, info
            )
            if (count <= 0) return@runCatching null
            val (ow, oh) = info[0] to info[1]
            val outputBytes = pixelBufferBytes(ow, oh) ?: return@runCatching null
            val out = scratch(outputBytes)
            if (EffectCraftNative.nativeTakeGrown(out) != outputBytes) return@runCatching null
            out.rewind()
            val bitmap = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(out)
            val sx = layerWidth / w
            val sy = layerHeight / h
            val left = info[2] * sx
            val top = info[3] * sy
            val right = info[4] * sx
            val bottom = info[5] * sy
            if (key != null) rememberEffect(key, bitmap, left, top, right, bottom)
            Effected(bitmap, left, top, right, bottom)
        }.getOrNull()
    }

    /**
     * Applies the audio effects of [stack] to interleaved stereo [samples] that start at
     * [startSeconds] on the layer. Returns true when the block was changed.
     */
    fun processAudio(samples: FloatArray, rate: Int, startSeconds: Double, stack: List<EffectInstance>, time: Float): Boolean {
        val active = audioEffects(stack)
        if (active.isEmpty() || !EffectCraftNative.available) return false
        return runCatching {
            EffectCraftNative.nativeProcessAudio(samples, rate, startSeconds, stackJson(active, time)) > 0
        }.getOrDefault(false)
    }

    fun audioPreroll(stack: List<EffectInstance>, time: Float): Double {
        val active = audioEffects(stack)
        if (active.isEmpty() || !EffectCraftNative.available) return 0.0
        return runCatching { EffectCraftNative.nativeAudioPreroll(stackJson(active, time)) }.getOrDefault(0.0)
    }
}
