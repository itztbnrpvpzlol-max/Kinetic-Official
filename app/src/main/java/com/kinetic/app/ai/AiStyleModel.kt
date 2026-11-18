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
    fun similarity(durationSeconds: Float, visualEnergy: Float, cutStrength: Float, motionEnergy: Float = 0f, stylizedVisualScore: Float = 0f): Float {
        if (centroids.isEmpty()) return 0.5f
        val sample = features(durationSeconds, visualEnergy, cutStrength, motionEnergy, stylizedVisualScore)
        val distance = centroids.minOf { center -> euclidean(sample, center) }
        return exp(-distance * 2.4f).toFloat().coerceIn(0f, 1f)
    }

    companion object {
        private const val PREFS = "kinetic_ai_style_model"
        private const val KEY = "kmeans_v2"

        fun trainOrLoad(context: Context, samples: List<FloatArray>): AiStyleModel {
            val valid = samples.filter { it.size >= 5 && it.all(Float::isFinite) }
                .map { sample -> FloatArray(5) { axis -> if (axis == 0) sample[0] else sample[axis].coerceIn(0f, 1f) } }
            val previous = load(context)
            if (valid.isNotEmpty()) {
                // Retain learned centroids as replay samples so new references refine,
                // rather than erase, the user's accumulated style preferences.
                val retained = previous?.centroids.orEmpty().flatMap { center ->
                    listOf(center.copyOf(), center.copyOf())
                }
                val model = train(valid + retained, (previous?.trainingSamples ?: 0) + valid.size)
                persist(context, model)
                return model
            }
            return previous ?: AiStyleModel(emptyList(), 0)
        }

        private fun train(samples: List<FloatArray>, totalSamples: Int = samples.size): AiStyleModel {
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
                        centers[i] = FloatArray(5) { axis ->
                            bucket.map { it[axis] }.average().toFloat()
                        }
                    }
                }
            }
            return AiStyleModel(centers, totalSamples)
        }

        private fun persist(context: Context, model: AiStyleModel) {
            val json = JSONObject()
                .put("samples", model.trainingSamples)
                .put("centroids", JSONArray().apply {
                    model.centroids.forEach { center ->
                        put(JSONArray().apply { center.forEach { put(it.toDouble()) } })
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
                    FloatArray(5) { axis -> values.optDouble(axis, 0.0).toFloat() }
                }
                AiStyleModel(centers, json.optInt("samples"))
            }.getOrNull()
        }

        private fun features(duration: Float, energy: Float, cut: Float, motion: Float, style: Float) =
            floatArrayOf(ln(duration.coerceAtLeast(0.1f)), energy.coerceIn(0f, 1f), cut.coerceIn(0f, 1f), motion.coerceIn(0f, 1f), style.coerceIn(0f, 1f))

        private fun euclidean(a: FloatArray, b: FloatArray): Float =
            sqrt((0..4).sumOf { axis ->
                val delta = (a[axis] - b[axis]).toDouble()
                delta * delta
            }).toFloat()
    }
}
