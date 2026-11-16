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
