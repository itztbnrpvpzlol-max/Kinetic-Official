package com.kinetic.app.engine

import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import com.kinetic.app.model.EffectInstance

/**
 * Versioned Kotlin-facing boundary for EffectCraft.
 *
 * This deliberately does not replace NativeRenderEngine. It gives Kinetic a stable
 * command/event API while the renderer and media pipeline are migrated incrementally.
 */
object EffectCraftBridge {
    const val PROTOCOL_VERSION = 1

    val available: Boolean
        get() = EffectCraftNative.available

    fun initialize(): Boolean = initializeWithError() == null

    @Synchronized
    fun initializeWithError(): String? {
        if (!EffectCraftNative.available) {
            return "EffectCraft library failed to load: " + (EffectCraftNative.loadError ?: "unknown linker error")
        }
        return runCatching {
            if (EffectCraftNative.nativeInitialize()) null else "EffectCraft nativeInitialize returned false"
        }.getOrElse { error ->
            "EffectCraft initialization threw " + error.javaClass.simpleName + ": " + (error.message ?: "no detail")
        }
    }

    /**
     * Executes a command using a JSON object for params and returns the engine's JSON response.
     * The caller should invoke this from a background dispatcher/thread.
     */
    @Synchronized
    fun execute(commandId: String, paramsJson: String = "{}"): String {
        if (!EffectCraftNative.available) {
            return """{"protocolVersion":1,"ok":false,"error":"EffectCraft native library is not packaged"}"""
        }
        return runCatching {
            EffectCraftNative.nativeExecute(commandId, paramsJson)
        }.getOrElse {
            """{"protocolVersion":1,"ok":false,"error":"Native bridge call failed"}"""
        }
    }
    /**
     * Executes ordered commands against the same persistent EffectCraft session.
     * Stops at the first failed command; earlier successful commands remain committed.
     * The caller is responsible for invoking this from a worker thread.
     */
    @Synchronized
    fun executeBatch(commandsJson: String): String {
        if (!EffectCraftNative.available) {
            return """{"protocolVersion":1,"ok":false,"error":"EffectCraft native library is not packaged"}"""
        }
        return runCatching {
            EffectCraftNative.nativeExecuteBatch(commandsJson)
        }.getOrElse { error ->
            """{"protocolVersion":1,"ok":false,"error":"Native batch call failed: ${error.javaClass.simpleName}"}"""
        }
    }


    /**
     * Sends the actual decoded Kinetic clip frame through EffectCraft's compositor.
     * Returns null only when the native renderer rejects the frame or cannot render it.
     */
    @Synchronized
    fun renderFrame(
        source: Bitmap,
        effects: List<EffectInstance>,
        timeSeconds: Float,
        maxSide: Int,
        cacheKey: String
    ): Bitmap? {
        if (!available || source.isRecycled || source.width <= 0 || source.height <= 0) return null
        return runCatching {
            if (!EffectCraftNative.nativeInitialize()) return null
            val width = source.width
            val height = source.height
            val pixels = IntArray(width * height)
            source.getPixels(pixels, 0, width, 0, 0, width, height)
            val rgba = ByteArray(width * height * 4)
            var offset = 0
            for (pixel in pixels) {
                rgba[offset++] = ((pixel ushr 16) and 0xFF).toByte()
                rgba[offset++] = ((pixel ushr 8) and 0xFF).toByte()
                rgba[offset++] = (pixel and 0xFF).toByte()
                rgba[offset++] = ((pixel ushr 24) and 0xFF).toByte()
            }
            val effectList = JSONArray()
            effects.forEach { effect ->
                val effectId = effect.effectCraftId ?: return@forEach
                val parameters = JSONObject()
                effect.effectCraftParameters.forEach { (name, raw) ->
                    val base = raw.toFloatOrNull()
                    val value: Any = if (base != null) {
                        effect.effectCraftParameterAt(name, timeSeconds, base).toDouble()
                    } else {
                        runCatching { JSONTokener(raw).nextValue() }.getOrDefault(raw)
                    }
                    parameters.put(name, value)
                }
                effectList.put(JSONObject().put("id", effectId).put("values", parameters))
            }
            val encoded = EffectCraftNative.nativeRenderFrame(
                cacheKey, rgba, width, height, effectList.toString(), timeSeconds, maxSide
            ) ?: return null
            if (encoded.size < 8) return null
            val header = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)
            val outWidth = header.int
            val outHeight = header.int
            if (outWidth <= 0 || outHeight <= 0 || encoded.size != 8 + outWidth * outHeight * 4) return null
            val output = IntArray(outWidth * outHeight)
            var i = 8
            for (p in output.indices) {
                val red = encoded[i++].toInt() and 0xFF
                val green = encoded[i++].toInt() and 0xFF
                val blue = encoded[i++].toInt() and 0xFF
                val alpha = encoded[i++].toInt() and 0xFF
                output[p] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            }
            Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888).also {
                it.setPixels(output, 0, outWidth, 0, 0, outWidth, outHeight)
            }
        }.getOrNull()
    }


    /**
     * Decode a local video frame using EffectCraft's FilmCraft decoder and MediaPool.
     * Call from a media worker. Null means the caller should use its compatibility decoder.
     */
    fun decodeVideoFrame(path: String, timeSeconds: Float, maxSide: Int): Bitmap? {
        if (!available || path.isBlank() || !timeSeconds.isFinite()) return null
        return runCatching {
            if (!EffectCraftNative.nativeInitialize()) return null
            val encoded = EffectCraftNative.nativeDecodeVideoFrame(
                path, timeSeconds.coerceAtLeast(0f), maxSide.coerceIn(128, 2048)
            ) ?: return null
            if (encoded.size < 8) return null
            val header = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)
            val width = header.int
            val height = header.int
            if (width <= 0 || height <= 0 ||
                width.toLong() * height.toLong() > 8_388_608L ||
                encoded.size.toLong() != 8L + width.toLong() * height.toLong() * 4L
            ) return null
            val pixels = IntArray(width * height)
            var offset = 8
            for (index in pixels.indices) {
                val red = encoded[offset++].toInt() and 0xFF
                val green = encoded[offset++].toInt() and 0xFF
                val blue = encoded[offset++].toInt() and 0xFF
                val alpha = encoded[offset++].toInt() and 0xFF
                pixels[index] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            }
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                it.setPixels(pixels, 0, width, 0, 0, width, height)
            }
        }.getOrNull()
    }


    /** Clear EffectCraft's decoded-frame LRU on Android memory pressure. */
    fun trimMediaCache() {
        if (!available) return
        runCatching { EffectCraftNative.nativeClearMediaFrames() }
    }

}
