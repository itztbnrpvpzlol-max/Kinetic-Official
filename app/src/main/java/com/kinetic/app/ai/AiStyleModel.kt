package com.kinetic.app.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Tiny on-device unsupervised style model.
 *
 * It learns clusters of shot duration, visual energy and cut strength from
 * user-provided reference edits using k-means. No media leaves the device.
 * This is a trainable preference model, not a semantic anime-recognition model.
 */
internal class AiStyleModel private constructor(
    private val centroids: List<FloatArray>,
    val trainingSamples: Int
) {
    fun similarity(durationSeconds: Float, visualEnergy: Float, cutStrength: Float): Float {
        if (centroids.isEmpty()) return 0.5f
        val sample = features(durationSeconds, visualEnergy, cutStrength)
        val distance = centroids.minOf { center -> euclidean(sample, center) }
        return exp(-distance * 2.4f).toFloat().coerceIn(0f, 1f)
    }

    companion object {
        private const val PREFS = "kinetic_ai_style_model"
        private const val KEY = "kmeans_v1"

        fun trainOrLoad(context: Context, samples: List<FloatArray>): AiStyleModel {
            val valid = samples.filter { it.size >= 3 && it.all(Float::isFinite) }
                .map { floatArrayOf(it[0], it[1].coerceIn(0f, 1f), it[2].coerceIn(0f, 1f)) }
            if (valid.isNotEmpty()) {
                val model = train(valid)
                persist(context, model)
                return model
            }
            return load(context) ?: AiStyleModel(emptyList(), 0)
        }

        private fun train(samples: List<FloatArray>): AiStyleModel {
            val count = minOf(4, samples.size)
            val centers = ArrayList<FloatArray>(count)
            centers.add(samples.first().copyOf())
            while (centers.size < count) {
                val next = samples.maxByOrNull { sample ->
                    centers.minOf { euclidean(sample, it) }
                } ?: break
                centers.add(next.copyOf())
            }
            repeat(18) {
                val buckets = List(centers.size) { ArrayList<FloatArray>() }
                samples.forEach { sample ->
                    val index = centers.indices.minByOrNull { euclidean(sample, centers[it]) } ?: 0
                    buckets[index].add(sample)
                }
                for (i in centers.indices) {
                    val bucket = buckets[i]
                    if (bucket.isNotEmpty()) {
                        centers[i] = FloatArray(3) { axis ->
                            bucket.map { it[axis] }.average().toFloat()
                        }
                    }
                }
            }
            return AiStyleModel(centers, samples.size)
        }

        private fun persist(context: Context, model: AiStyleModel) {
            val json = JSONObject()
                .put("samples", model.trainingSamples)
                .put("centroids", JSONArray().apply {
                    model.centroids.forEach { center ->
                        put(JSONArray().put(center[0].toDouble()).put(center[1].toDouble()).put(center[2].toDouble()))
                    }
                })
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, json.toString()).apply()
        }

        private fun load(context: Context): AiStyleModel? {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
            return runCatching {
                val json = JSONObject(raw)
                val array = json.getJSONArray("centroids")
                val centers = (0 until array.length()).map { index ->
                    val values = array.getJSONArray(index)
                    floatArrayOf(values.getDouble(0).toFloat(), values.getDouble(1).toFloat(), values.getDouble(2).toFloat())
                }
                AiStyleModel(centers, json.optInt("samples"))
            }.getOrNull()
        }

        private fun features(duration: Float, energy: Float, cut: Float) =
            floatArrayOf(ln(duration.coerceAtLeast(0.1f)), energy.coerceIn(0f, 1f), cut.coerceIn(0f, 1f))

        private fun euclidean(a: FloatArray, b: FloatArray): Float =
            sqrt((0..2).sumOf { axis ->
                val delta = (a[axis] - b[axis]).toDouble()
                delta * delta
            }).toFloat()
    }
}
