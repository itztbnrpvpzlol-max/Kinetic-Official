package com.kinetic.app.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Incrementally trained, on-device AMV style preference model.
 *
 * Unlike the previous centroid-only persistence, this keeps a bounded corpus
 * of the actual numeric training examples and retrains from that corpus plus
 * newly analysed reference shots. Only derived visual/pacing features are
 * stored: source media, names, URIs and audio are never copied into the model.
 *
 * This is a genuine trainable k-means preference model, not a neural network
 * or a semantic understanding model. Its output is one signal for the editor,
 * not a substitute for shot/transition quality evaluation.
 */
internal class AiStyleModel private constructor(
    private val centroids: List<FloatArray>,
    private val trainingData: List<FloatArray>,
    val trainingSamples: Int,
    val lastTrainedAt: Long
) {
    fun similarity(
        durationSeconds: Float,
        visualEnergy: Float,
        cutStrength: Float,
        motionEnergy: Float = 0f,
        stylizedVisualScore: Float = 0f
    ): Float {
        if (centroids.isEmpty()) return 0.5f
        val sample = features(durationSeconds, visualEnergy, cutStrength, motionEnergy, stylizedVisualScore)
        val distance = centroids.minOf { center -> euclidean(sample, center) }
        return exp(-distance * 2.4f).toFloat().coerceIn(0f, 1f)
    }

    companion object {
        private const val PREFS = "kinetic_ai_style_model"
        private const val KEY = "training_v3"
        private const val MAX_TRAINING_EXAMPLES = 2048
        private const val MAX_CENTROIDS = 8
        private const val TRAINING_ITERATIONS = 24

        /**
         * Adds new reference-shot examples to the persistent local corpus and
         * retrains from the full retained corpus. Repeated imports of the same
         * reference descriptors are de-duplicated to avoid accidental weighting.
         */
        @Synchronized
        fun trainOrLoad(context: Context, samples: List<FloatArray>): AiStyleModel {
            val previous = load(context)
            val valid = samples.asSequence()
                .filter { it.size >= 5 && it.take(5).all(Float::isFinite) }
                .map { sample ->
                    FloatArray(5) { axis ->
                        if (axis == 0) sample[axis].coerceIn(-4.0f, 5.0f)
                        else sample[axis].coerceIn(0f, 1f)
                    }
                }
                .toList()

            if (valid.isEmpty()) return previous ?: AiStyleModel(emptyList(), emptyList(), 0, 0L)

            val combined = LinkedHashMap<String, FloatArray>()
            previous?.trainingData.orEmpty().forEach { combined[sampleKey(it)] = it.copyOf() }
            valid.forEach { combined.putIfAbsent(sampleKey(it), it.copyOf()) }
            val retained = combined.values.toList().takeLast(MAX_TRAINING_EXAMPLES)
            val model = train(retained, retained.size, System.currentTimeMillis())
            persist(context, model)
            return model
        }

        private fun train(
            samples: List<FloatArray>,
            totalSamples: Int,
            trainedAt: Long
        ): AiStyleModel {
            if (samples.isEmpty()) return AiStyleModel(emptyList(), emptyList(), totalSamples, trainedAt)

            val count = minOf(MAX_CENTROIDS, samples.size)
            val centers = ArrayList<FloatArray>(count)
            centers.add(samples.first().copyOf())

            // Farthest-first initialization gives the clusters useful coverage
            // without introducing a random seed or nondeterministic model builds.
            while (centers.size < count) {
                val next = samples.maxByOrNull { sample ->
                    centers.minOf { center -> euclidean(sample, center) }
                } ?: break
                centers.add(next.copyOf())
            }

            repeat(TRAINING_ITERATIONS) {
                val buckets = List(centers.size) { ArrayList<FloatArray>() }
                samples.forEach { sample ->
                    val index = centers.indices.minByOrNull { euclidean(sample, centers[it]) } ?: 0
                    buckets[index].add(sample)
                }
                for (index in centers.indices) {
                    val bucket = buckets[index]
                    if (bucket.isNotEmpty()) {
                        centers[index] = FloatArray(5) { axis ->
                            bucket.sumOf { it[axis].toDouble() }.div(bucket.size).toFloat()
                        }
                    }
                }
            }
            return AiStyleModel(centers, samples.map { it.copyOf() }, totalSamples, trainedAt)
        }

        private fun persist(context: Context, model: AiStyleModel) {
            val json = JSONObject()
                .put("schemaVersion", 3)
                .put("samples", model.trainingSamples)
                .put("lastTrainedAt", model.lastTrainedAt)
                .put("centroids", JSONArray().apply {
                    model.centroids.forEach { center ->
                        put(JSONArray().apply { center.forEach { put(it.toDouble()) } })
                    }
                })
                .put("trainingData", JSONArray().apply {
                    model.trainingData.forEach { sample ->
                        put(JSONArray().apply { sample.forEach { put(it.toDouble()) } })
                    }
                })
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, json.toString()).apply()
        }

        private fun load(context: Context): AiStyleModel? {
            val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val raw = preferences.getString(KEY, null)
                ?: preferences.getString("kmeans_v2", null)
                ?: return null
            return runCatching {
                val json = JSONObject(raw)
                val trainingJson = json.optJSONArray("trainingData") ?: JSONArray()
                val trainingData = (0 until trainingJson.length()).mapNotNull { index ->
                    val values = trainingJson.optJSONArray(index) ?: return@mapNotNull null
                    if (values.length() < 5) return@mapNotNull null
                    FloatArray(5) { axis -> values.optDouble(axis, 0.0).toFloat() }
                        .takeIf { sample -> sample.all(Float::isFinite) }
                }.takeLast(MAX_TRAINING_EXAMPLES)

                val centroidsJson = json.optJSONArray("centroids") ?: JSONArray()
                val storedCentroids = (0 until centroidsJson.length()).mapNotNull { index ->
                    val values = centroidsJson.optJSONArray(index) ?: return@mapNotNull null
                    if (values.length() < 5) return@mapNotNull null
                    FloatArray(5) { axis -> values.optDouble(axis, 0.0).toFloat() }
                        .takeIf { center -> center.all(Float::isFinite) }
                }

                // Migrate the old centroid-only model once. Centroids are retained
                // as low-weight seed examples because the original training corpus
                // was not persisted by earlier app versions.
                val corpus = if (trainingData.isNotEmpty()) trainingData else storedCentroids
                val migrated = train(
                    corpus,
                    max(json.optInt("samples"), corpus.size),
                    json.optLong("lastTrainedAt", 0L)
                )
                if (json.optInt("schemaVersion") != 3 && migrated.trainingData.isNotEmpty()) {
                    persist(context, migrated)
                }
                migrated
            }.getOrNull()
        }

        private fun sampleKey(sample: FloatArray): String =
            sample.take(5).joinToString("|") { (it * 10_000f).roundToInt().toString() }

        private fun features(
            duration: Float,
            energy: Float,
            cut: Float,
            motion: Float,
            style: Float
        ) = floatArrayOf(
            ln(duration.coerceAtLeast(0.1f)).coerceIn(-4.0f, 5.0f),
            energy.coerceIn(0f, 1f),
            cut.coerceIn(0f, 1f),
            motion.coerceIn(0f, 1f),
            style.coerceIn(0f, 1f)
        )

        private fun euclidean(a: FloatArray, b: FloatArray): Float =
            sqrt((0..4).sumOf { axis ->
                val delta = (a[axis] - b[axis]).toDouble()
                delta * delta
            }).toFloat()
    }
}
