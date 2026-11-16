package com.kinetic.app.engine

import android.graphics.Bitmap
import com.kinetic.app.model.EffectInstance

/**
 * Standalone CPU effects engine. This library deliberately has no GLES or
 * FFmpeg linkage, so shader/decoder loading cannot prevent effect creation.
 */
object NativeEffectsEngine {
    private val loadResult = runCatching { System.loadLibrary("kinetic_effects") }
    val available: Boolean = loadResult.isSuccess
    val loadError: String? = loadResult.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }

    external fun nativeCreateEffect(name: String): Array<String>
    private external fun nativeEvaluateParameter(base: Float, keyframeData: FloatArray, time: Float): Float

    fun evaluateParameter(base: Float, keys: List<com.kinetic.app.model.Keyframe>, time: Float): Float {
        if (!available || keys.isEmpty()) return base
        val data = FloatArray(keys.size * 7)
        keys.forEachIndexed { index, key ->
            val offset = index * 7
            data[offset] = key.t
            data[offset + 1] = key.v
            data[offset + 2] = key.type.ordinal.toFloat()
            data[offset + 3] = key.x1
            data[offset + 4] = key.y1
            data[offset + 5] = key.x2
            data[offset + 6] = key.y2
        }
        return runCatching { nativeEvaluateParameter(base, data, time) }
            .getOrDefault(base).takeIf { it.isFinite() } ?: base
    }

    private external fun nativeRenderMotionTileBackground(source: Bitmap, target: Bitmap, tileWidth: Float, tileHeight: Float, mirrorEdges: Boolean, phaseX: Float, phaseY: Float, foregroundWidth: Float, foregroundHeight: Float): Boolean

    fun createMotionTileBackground(source: Bitmap, effect: EffectInstance, time: Float): Bitmap? {
        if (!available || source.isRecycled || effect.name != "Motion Tile") return null
        val outputWidth = effect.parameterAt("Output Width", time, 2000f).coerceIn(100f, 2000f)
        val outputHeight = effect.parameterAt("Output Height", time, 2000f).coerceIn(100f, 2000f)
        val maxEdge = if (com.kinetic.app.data.MediaWorkers.isConstrainedDevice) 640 else 2048
        val scale = minOf(1f, maxEdge.toFloat() / (maxOf(source.width * outputWidth, source.height * outputHeight) / 100f))
        val width = (source.width * outputWidth / 100f * scale).toInt().coerceIn(1, maxEdge)
        val height = (source.height * outputHeight / 100f * scale).toInt().coerceIn(1, maxEdge)
        val output = runCatching { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return null
        val success = runCatching {
            nativeRenderMotionTileBackground(
                source, output,
                effect.parameterAt("Tile Width", time, 100f),
                effect.parameterAt("Tile Height", time, 100f),
                effect.parameterAt("Mirror Edges", time, 1f) >= 0.5f,
                effect.parameterAt("Phase X", time, 0f),
                effect.parameterAt("Phase Y", time, 0f),
                source.width * scale,
                source.height * scale
            )
        }.getOrDefault(false)
        if (success) return output
        if (!output.isRecycled) output.recycle()
        return null
    }

    private external fun nativeProcessBitmap(
        bitmap: Bitmap,
        effectNames: Array<String>,
        enabled: BooleanArray,
        parameterNames: Array<String>,
        parameterValues: FloatArray,
        parameterEffectIndices: IntArray,
        keyframeParameterIndices: IntArray,
        keyframeData: FloatArray,
        time: Float
    ): Boolean

    /** Returns a processed copy; the shared decoded source bitmap is never modified. */
    fun processBitmap(source: Bitmap, effects: List<EffectInstance>, time: Float): Bitmap? {
        val active = effects.filter { it.enabled }.take(8)
        if (active.isEmpty() || !available || source.isRecycled) return null
        val output = runCatching { source.copy(Bitmap.Config.ARGB_8888, true) }.getOrNull() ?: return null
        val names = ArrayList<String>()
        val values = ArrayList<Float>()
        val owners = ArrayList<Int>()
        val keyOwners = ArrayList<Int>()
        val keyData = ArrayList<Float>()
        active.forEachIndexed { effectIndex, effect ->
            (effect.parameters.keys + effect.parameterKeys.keys).distinct().forEach { name ->
                val parameterIndex = names.size
                names += name
                values += effect.parameters[name] ?: 0f
                owners += effectIndex
                effect.parameterKeys[name].orEmpty().forEach { key ->
                    keyOwners += parameterIndex
                    keyData += key.t
                    keyData += key.v
                    keyData += key.type.ordinal.toFloat()
                    keyData += key.x1
                    keyData += key.y1
                    keyData += key.x2
                    keyData += key.y2
                }
            }
        }
        val success = runCatching {
            nativeProcessBitmap(
                output, active.map { it.name }.toTypedArray(), BooleanArray(active.size) { true },
                names.toTypedArray(), values.toFloatArray(), owners.toIntArray(),
                keyOwners.toIntArray(), keyData.toFloatArray(), time
            )
        }.getOrDefault(false)
        if (success) return output
        if (!output.isRecycled) output.recycle()
        return null
    }
}
