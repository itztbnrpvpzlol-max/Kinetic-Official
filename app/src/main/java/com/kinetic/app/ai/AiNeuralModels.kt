package com.kinetic.app.ai

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Optional, fully on-device neural vision models.
 *
 * RAFT weights are the OpenCV Zoo INT8 model (BSD-3-Clause original / MIT ONNX
 * conversion). The anime identity encoder is exported from AnimeCV's released
 * ResNet18 checkpoint; reference matching is user-enrolled and never uploads
 * images. Models are stored in app-private files and can be deleted with app data.
 */
object AiNeuralModels {
    private const val FLOW_URL =
        "https://huggingface.co/opencv/optical_flow_estimation_raft/resolve/main/optical_flow_estimation_raft_2023aug_int8bq.onnx"
    private const val IDENTITY_URL =
        "https://github.com/itztbnrpvpzlol-max/Kinetic-Official/releases/download/kinetic-ai-models-v1/anime-character-resnet18.onnx"
    private const val FLOW_FILE = "raft-optical-flow-int8.onnx"
    private const val IDENTITY_FILE = "anime-character-resnet18.onnx"
    private const val FLOW_WIDTH = 480
    private const val FLOW_HEIGHT = 360
    private const val IDENTITY_SIZE = 224

    private val environment: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    @Volatile private var flowSession: OrtSession? = null
    @Volatile private var identitySession: OrtSession? = null

    data class ModelAvailability(
        val opticalFlow: Boolean,
        val animeIdentity: Boolean,
        val message: String
    )

    suspend fun prepare(context: Context): ModelAvailability {
        val flow = runCatching {
            val file = downloadModel(context, FLOW_URL, FLOW_FILE, 35L * 1024L * 1024L)
            openFlow(file)
            true
        }.getOrDefault(false)
        val identity = runCatching {
            val file = downloadModel(context, IDENTITY_URL, IDENTITY_FILE, 5L * 1024L * 1024L)
            openIdentity(file)
            true
        }.getOrDefault(false)
        return ModelAvailability(
            opticalFlow = flow,
            animeIdentity = identity,
            message = buildList {
                if (flow) add("RAFT optical flow ready") else add("RAFT model unavailable; motion-analysis fallback active")
                if (identity) add("Anime character reference encoder ready") else add("Anime identity model unavailable; reference matching fallback active")
            }.joinToString(" • ")
        )
    }

    private fun downloadModel(context: Context, address: String, filename: String, minimumBytes: Long): File {
        val target = File(context.filesDir, filename)
        if (target.exists() && target.length() >= minimumBytes) return target
        target.delete()
        val temporary = File(context.filesDir, "$filename.part")
        temporary.delete()
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 90_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Kinetic-AI-Editor/1.0")
        }
        try {
            connection.connect()
            check(connection.responseCode in 200..299) { "Model download failed: HTTP ${connection.responseCode}" }
            connection.inputStream.use { input ->
                temporary.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            check(temporary.length() >= minimumBytes) { "Downloaded model is incomplete." }
            check(temporary.renameTo(target)) { "Could not install model file." }
        } finally {
            connection.disconnect()
        }
        return target
    }

    private fun openFlow(file: File): OrtSession {
        flowSession?.let { return it }
        return synchronized(this) {
            flowSession ?: environment.createSession(
                file.absolutePath,
                OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
            ).also { flowSession = it }
        }
    }

    private fun openIdentity(file: File): OrtSession {
        identitySession?.let { return it }
        return synchronized(this) {
            identitySession ?: environment.createSession(
                file.absolutePath,
                OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
            ).also { identitySession = it }
        }
    }

    /** 500-dimensional AnimeCV embedding from a full frame or user reference image. */
    fun animeEmbedding(bitmap: Bitmap): FloatArray? {
        val session = identitySession ?: return null
        val resized = Bitmap.createScaledBitmap(bitmap, IDENTITY_SIZE, IDENTITY_SIZE, true)
        val pixels = IntArray(IDENTITY_SIZE * IDENTITY_SIZE)
        resized.getPixels(pixels, 0, IDENTITY_SIZE, 0, 0, IDENTITY_SIZE, IDENTITY_SIZE)
        if (resized !== bitmap) resized.recycle()
        val values = FloatArray(3 * IDENTITY_SIZE * IDENTITY_SIZE)
        val plane = IDENTITY_SIZE * IDENTITY_SIZE
        for (index in pixels.indices) {
            val pixel = pixels[index]
            val red = ((pixel shr 16) and 255) / 255f
            val green = ((pixel shr 8) and 255) / 255f
            val blue = (pixel and 255) / 255f
            values[index] = (red - 0.485f) / 0.229f
            values[plane + index] = (green - 0.456f) / 0.224f
            values[2 * plane + index] = (blue - 0.406f) / 0.225f
        }
        val inputName = session.inputNames.first()
        val outputName = session.outputNames.first()
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(values), longArrayOf(1, 3, 224, 224)).use { input ->
            session.run(mapOf(inputName to input)).use { result ->
                val output = result.get(outputName).orElse(null) as? OnnxTensor ?: return null
                val buffer = output.floatBuffer
                return FloatArray(buffer.remaining()).also { buffer.get(it) }
            }
        }
    }

    fun cosineSimilarity(left: FloatArray, right: FloatArray): Float {
        if (left.size != right.size || left.isEmpty()) return 0f
        var dot = 0.0
        var a = 0.0
        var b = 0.0
        for (i in left.indices) {
            dot += left[i] * right[i]
            a += left[i] * left[i]
            b += right[i] * right[i]
        }
        val denominator = kotlin.math.sqrt(a * b)
        return if (denominator <= 1e-9) 0f else (dot / denominator).toFloat().coerceIn(-1f, 1f)
    }

    private fun inferFlow(session: OrtSession, first: Bitmap, second: Bitmap): FloatArray {
        val a = tensorPixels(first)
        val b = tensorPixels(second)
        val names = session.inputNames.toList()
        require(names.size >= 2) { "RAFT expects two frame inputs." }
        val shape = longArrayOf(1, 3, FLOW_HEIGHT.toLong(), FLOW_WIDTH.toLong())
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(a), shape).use { inputA ->
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(b), shape).use { inputB ->
                session.run(mapOf(names[0] to inputA, names[1] to inputB)).use { result ->
                    val outputName = session.outputNames.last()
                    val output = result.get(outputName).orElseThrow() as OnnxTensor
                    val dimensions = output.info.shape
                    require(dimensions.size == 4 && dimensions[1] == 2L &&
                        dimensions[2] == FLOW_HEIGHT.toLong() && dimensions[3] == FLOW_WIDTH.toLong()) {
                        "Unsupported RAFT output tensor: ${dimensions.contentToString()}"
                    }
                    val buffer = output.floatBuffer
                    return FloatArray(buffer.remaining()).also { buffer.get(it) }
                }
            }
        }
    }

    private fun tensorPixels(source: Bitmap): FloatArray {
        val resized = Bitmap.createScaledBitmap(source, FLOW_WIDTH, FLOW_HEIGHT, true)
        val pixels = IntArray(FLOW_WIDTH * FLOW_HEIGHT)
        resized.getPixels(pixels, 0, FLOW_WIDTH, 0, 0, FLOW_WIDTH, FLOW_HEIGHT)
        if (resized !== source) resized.recycle()
        val plane = FLOW_WIDTH * FLOW_HEIGHT
        val data = FloatArray(plane * 3)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            data[i] = ((pixel shr 16) and 255).toFloat()
            data[plane + i] = ((pixel shr 8) and 255).toFloat()
            data[2 * plane + i] = (pixel and 255).toFloat()
        }
        return data
    }

    /**
     * Neural motion-compensated intermediate frame at t in [0,1].
     * Returns the model's 480x360 working resolution; caller can scale it.
     * Forward and backward RAFT fields are both evaluated to reduce occlusion holes.
     */
    fun interpolateFrame(first: Bitmap, second: Bitmap, t: Float = 0.5f): Bitmap? {
        val session = flowSession ?: return null
        val amount = t.coerceIn(0.05f, 0.95f)
        val forward = inferFlow(session, first, second)
        val backward = inferFlow(session, second, first)
        val a = Bitmap.createScaledBitmap(first, FLOW_WIDTH, FLOW_HEIGHT, true)
        val b = Bitmap.createScaledBitmap(second, FLOW_WIDTH, FLOW_HEIGHT, true)
        val pixelsA = IntArray(FLOW_WIDTH * FLOW_HEIGHT)
        val pixelsB = IntArray(FLOW_WIDTH * FLOW_HEIGHT)
        a.getPixels(pixelsA, 0, FLOW_WIDTH, 0, 0, FLOW_WIDTH, FLOW_HEIGHT)
        b.getPixels(pixelsB, 0, FLOW_WIDTH, 0, 0, FLOW_WIDTH, FLOW_HEIGHT)
        if (a !== first) a.recycle()
        if (b !== second) b.recycle()

        val accum = FloatArray(FLOW_WIDTH * FLOW_HEIGHT * 3)
        val weights = FloatArray(FLOW_WIDTH * FLOW_HEIGHT)
        splat(pixelsA, forward, amount, accum, weights)
        splat(pixelsB, backward, 1f - amount, accum, weights)

        val output = IntArray(FLOW_WIDTH * FLOW_HEIGHT)
        for (i in output.indices) {
            val weight = weights[i]
            if (weight <= 1e-5f) {
                output[i] = if (amount < 0.5f) pixelsA[i] else pixelsB[i]
            } else {
                val offset = i * 3
                val red = (accum[offset] / weight).toInt().coerceIn(0, 255)
                val green = (accum[offset + 1] / weight).toInt().coerceIn(0, 255)
                val blue = (accum[offset + 2] / weight).toInt().coerceIn(0, 255)
                output[i] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
        return Bitmap.createBitmap(output, FLOW_WIDTH, FLOW_HEIGHT, Bitmap.Config.ARGB_8888)
    }

    private fun splat(
        source: IntArray,
        flow: FloatArray,
        amount: Float,
        accum: FloatArray,
        weights: FloatArray
    ) {
        val plane = FLOW_WIDTH * FLOW_HEIGHT
        for (y in 0 until FLOW_HEIGHT) {
            for (x in 0 until FLOW_WIDTH) {
                val index = y * FLOW_WIDTH + x
                val dx = flow[index] * amount
                val dy = flow[plane + index] * amount
                val targetX = x + dx
                val targetY = y + dy
                val x0 = targetX.toInt()
                val y0 = targetY.toInt()
                val fx = targetX - x0
                val fy = targetY - y0
                val pixel = source[index]
                val red = ((pixel shr 16) and 255).toFloat()
                val green = ((pixel shr 8) and 255).toFloat()
                val blue = (pixel and 255).toFloat()
                for (oy in 0..1) for (ox in 0..1) {
                    val tx = x0 + ox
                    val ty = y0 + oy
                    if (tx !in 0 until FLOW_WIDTH || ty !in 0 until FLOW_HEIGHT) continue
                    val weight = (if (ox == 0) 1f - fx else fx) *
                        (if (oy == 0) 1f - fy else fy)
                    val outIndex = ty * FLOW_WIDTH + tx
                    val offset = outIndex * 3
                    accum[offset] += red * weight
                    accum[offset + 1] += green * weight
                    accum[offset + 2] += blue * weight
                    weights[outIndex] += weight
                }
            }
        }
    }
}
