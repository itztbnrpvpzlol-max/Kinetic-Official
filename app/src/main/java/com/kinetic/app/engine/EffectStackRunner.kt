package com.kinetic.app.engine

import android.graphics.Bitmap
import com.kinetic.app.model.EffectInstance
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer

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

    private val directBuffers = object : ThreadLocal<ByteBuffer?>() {}

    private fun scratch(bytes: Int): ByteBuffer {
        val cached = directBuffers.get()
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
        frameRate: Float, adjustment: Boolean = false
    ): Bitmap? {
        val active = pictureEffects(effects)
        if (active.isEmpty() || !EffectCraftNative.available || source.isRecycled) return null
        val output = runCatching { source.copy(Bitmap.Config.ARGB_8888, true) }.getOrNull() ?: return null
        val applied = runCatching {
            val buffer = scratch(output.width * output.height * 4)
            output.copyPixelsToBuffer(buffer)
            val count = EffectCraftNative.nativeProcessImage(
                buffer, output.width, output.height, stackJson(active, time),
                layerTime.toDouble(), frameRate.toDouble(), adjustment
            )
            if (count > 0) {
                buffer.rewind()
                output.copyPixelsFromBuffer(buffer)
            }
            count
        }.getOrDefault(-1)
        if (applied > 0) return output
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
        layerWidth: Float, layerHeight: Float, maxPixels: Int, adjustment: Boolean = false
    ): Effected? {
        val active = pictureEffects(effects)
        if (active.isEmpty() || !EffectCraftNative.available || source.isRecycled) return null
        return runCatching {
            val w = source.width
            val h = source.height
            val input = scratch(w * h * 4)
            val copy = if (source.config == Bitmap.Config.ARGB_8888) source else source.copy(Bitmap.Config.ARGB_8888, false)
            copy.copyPixelsToBuffer(input)
            if (copy !== source) copy.recycle()
            val info = IntArray(6)
            val count = EffectCraftNative.nativeProcessImageGrow(
                input, w, h, stackJson(active, time), layerTime.toDouble(), frameRate.toDouble(),
                adjustment, maxPixels, info
            )
            if (count <= 0) return@runCatching null
            val (ow, oh) = info[0] to info[1]
            if (ow <= 0 || oh <= 0) return@runCatching null
            val out = ByteBuffer.allocateDirect(ow * oh * 4)
            if (EffectCraftNative.nativeTakeGrown(out) < 0) return@runCatching null
            out.rewind()
            val bitmap = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(out)
            val sx = layerWidth / w
            val sy = layerHeight / h
            Effected(bitmap, info[2] * sx, info[3] * sy, info[4] * sx, info[5] * sy)
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
