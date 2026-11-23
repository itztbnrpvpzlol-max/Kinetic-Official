package com.kinetic.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Matrix
import android.graphics.Typeface
import com.kinetic.app.model.Layer
import com.kinetic.app.model.LayerType
import com.kinetic.app.model.VECTOR_STROKE
import com.kinetic.app.model.VectorPoint
import com.kinetic.app.model.buildVectorPath
import kotlin.math.roundToInt

/** Rasterizes a text or shape layer so picture effects can run on it like on any clip. */
internal object LayerRaster {
    /** [allowUpscale] renders larger than the layer's logical size, for export at high resolution. */
    fun render(
        layer: Layer, time: Float, maxEdge: Int, allowUpscale: Boolean = false,
        byId: Map<String, Layer>? = null
    ): Bitmap? {
        if (layer.type != LayerType.TEXT && layer.type != LayerType.SHAPE && layer.type != LayerType.PARTICULAR) return null
        val logicalWidth = layer.width.toInt().coerceIn(1, 4096)
        val logicalHeight = layer.height.toInt().coerceIn(1, 4096)
        val fit = maxEdge.toFloat() / maxOf(logicalWidth, logicalHeight)
        val scale = if (allowUpscale) fit else minOf(1f, fit)
        val w = (logicalWidth * scale).roundToInt().coerceAtLeast(1)
        val h = (logicalHeight * scale).roundToInt().coerceAtLeast(1)
        val bitmap = runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return null
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = layer.color }
        when (layer.type) {
            LayerType.TEXT -> TextEngine.draw(canvas, layer, time, logicalWidth.toFloat(), logicalHeight.toFloat())
            LayerType.SHAPE -> {
                val points = if (layer.vectorPoints.isEmpty() && !layer.isVector) {
                    listOf(VectorPoint(0f, 0f), VectorPoint(1f, 0f), VectorPoint(1f, 1f), VectorPoint(0f, 1f))
                } else layer.vectorPointsAt(time)
                if (points.size >= 2) {
                    val path = buildVectorPath(points, layer.vectorSmooth, layer.vectorClosed, logicalWidth.toFloat(), logicalHeight.toFloat())
                    if (!layer.vectorClosed) {
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = VECTOR_STROKE
                        paint.strokeCap = Paint.Cap.ROUND
                        paint.strokeJoin = Paint.Join.ROUND
                    }
                    canvas.drawPath(path, paint)
                }
            }
            LayerType.PARTICULAR -> drawParticles(canvas, layer, time, logicalWidth.toFloat(), logicalHeight.toFloat(), paint, byId)
            else -> Unit
        }
        return bitmap
    }

    private fun random01(seed: Int, index: Int, salt: Int): Float {
        var value = seed xor (index * 0x45d9f3b) xor (salt * 0x27d4eb2d)
        value = (value xor (value ushr 16)) * 0x45d9f3b
        value = (value xor (value ushr 16)) * 0x45d9f3b
        value = value xor (value ushr 16)
        return ((value ushr 8) and 0x00FFFFFF).toFloat() / 16777215f
    }

    private fun mixColor(start: Int, end: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        fun mix(a: Int, b: Int) = (a + (b - a) * t).toInt().coerceIn(0, 255)
        return android.graphics.Color.argb(
            mix(android.graphics.Color.alpha(start), android.graphics.Color.alpha(end)),
            mix(android.graphics.Color.red(start), android.graphics.Color.red(end)),
            mix(android.graphics.Color.green(start), android.graphics.Color.green(end)),
            mix(android.graphics.Color.blue(start), android.graphics.Color.blue(end))
        )
    }

    private fun drawParticles(
        canvas: Canvas, layer: Layer, time: Float, width: Float, height: Float, paint: Paint,
        byId: Map<String, Layer>?
    ) {
        val settings = layer.particles
        fun value(id: com.kinetic.app.model.PropId, fallback: Float = 0f) =
            layer.p(id).valueAt(time).takeIf { it.isFinite() } ?: fallback

        val rate = value(com.kinetic.app.model.PropId.PARTICLE_RATE, 60f).coerceIn(0f, 10000f)
        if (rate <= 0f) return
        val lifeBase = value(com.kinetic.app.model.PropId.PARTICLE_LIFETIME, 2f).coerceAtLeast(0.01f)
        val lifeRandom = value(com.kinetic.app.model.PropId.PARTICLE_LIFE_RANDOM, 20f) / 100f
        val preRoll = value(com.kinetic.app.model.PropId.PARTICLE_PREROLL, 1000f) / 1000f
        val elapsed = (time - layer.start + preRoll).coerceAtLeast(0f)
        val maxLife = lifeBase * (1f + lifeRandom)
        val lastEmitted = (elapsed * rate).toInt().coerceAtLeast(0)
        val firstEmitted = ((elapsed - maxLife).coerceAtLeast(0f) * rate).toInt().coerceAtLeast(0)
        val emitted = (lastEmitted - firstEmitted).coerceIn(0, 1200)
        val velocityBase = value(com.kinetic.app.model.PropId.PARTICLE_VELOCITY, 80f)
        val velocityRandom = value(com.kinetic.app.model.PropId.PARTICLE_VELOCITY_RANDOM, 20f) / 100f
        val spread = Math.toRadians(value(com.kinetic.app.model.PropId.PARTICLE_SPREAD, 28f).toDouble()).toFloat()
        val gravity = value(com.kinetic.app.model.PropId.PARTICLE_GRAVITY, 0f)
        val baseSize = value(com.kinetic.app.model.PropId.PARTICLE_SIZE, 6f).coerceAtLeast(0f)
        val sizeRandom = value(com.kinetic.app.model.PropId.PARTICLE_SIZE_RANDOM, 30f) / 100f
        val endSize = value(com.kinetic.app.model.PropId.PARTICLE_END_SIZE, 0f).coerceAtLeast(0f)
        val startOpacity = value(com.kinetic.app.model.PropId.PARTICLE_OPACITY, 100f).coerceIn(0f, 100f)
        val endOpacity = value(com.kinetic.app.model.PropId.PARTICLE_END_OPACITY, 0f).coerceIn(0f, 100f)
        val emitterX = width * 0.5f + value(com.kinetic.app.model.PropId.PARTICLE_POS_X)
        val emitterY = height * 0.5f + value(com.kinetic.app.model.PropId.PARTICLE_POS_Y)
        val emitterW = value(com.kinetic.app.model.PropId.PARTICLE_EMITTER_SIZE_X)
        val emitterH = value(com.kinetic.app.model.PropId.PARTICLE_EMITTER_SIZE_Y)
        val emitterD = value(com.kinetic.app.model.PropId.PARTICLE_EMITTER_SIZE_Z)
        val emitterZ = value(com.kinetic.app.model.PropId.PARTICLE_POS_Z)
        val turbulence = value(com.kinetic.app.model.PropId.PARTICLE_TURBULENCE)
        val drag = value(com.kinetic.app.model.PropId.PARTICLE_DRAG).coerceIn(0f, 100f) / 100f
        val rotationSpeed = value(com.kinetic.app.model.PropId.PARTICLE_ROTATION_SPEED)
        val seed = layer.id.hashCode()
        val projectionInverse = if (byId != null && com.kinetic.app.model.activeCamera(byId, time) != null) {
            Matrix().also { inverse ->
                if (!com.kinetic.app.model.layerMatrix(layer, time, byId).invert(inverse)) inverse.reset()
            }
        } else null

        for (index in firstEmitted until firstEmitted + emitted) {
            val r0 = random01(seed, index, 0)
            val r1 = random01(seed, index, 1)
            val r2 = random01(seed, index, 2)
            val r3 = random01(seed, index, 3)
            val r4 = random01(seed, index, 4)
            val birth = index / rate
            val age = elapsed - birth
            val life = (lifeBase * (1f + (r0 * 2f - 1f) * lifeRandom)).coerceAtLeast(0.05f)
            if (age < 0f || age > life) continue
            val progress = (age / life).coerceIn(0f, 1f)
            val angle = (-Math.PI / 2.0).toFloat() + (r1 * 2f - 1f) * spread
            val velocity = velocityBase * (1f + (r2 * 2f - 1f) * velocityRandom)
            val damping = kotlin.math.exp((-drag * age * 1.8f).toDouble()).toFloat()
            val depth = emitterZ + (r4 - 0.5f) * emitterD
            val x = emitterX + (r2 - 0.5f) * emitterW +
                kotlin.math.sin(angle) * velocity * age * damping +
                kotlin.math.sin(age * 4.2f + r3 * 6.28f) * turbulence * age * 0.12f
            val y = emitterY + (r3 - 0.5f) * emitterH -
                kotlin.math.cos(angle) * velocity * age * damping + 0.5f * gravity * age * age
            var drawX = x
            var drawY = y
            var cameraScale = 1f
            if (projectionInverse != null && byId != null) {
                val projected = com.kinetic.app.model.projectLayerPoint(layer, x, y, depth, time, byId)
                    ?: continue
                val localPoint = floatArrayOf(projected.x, projected.y)
                projectionInverse.mapPoints(localPoint)
                drawX = localPoint[0]
                drawY = localPoint[1]
                cameraScale = projected.sizeScale
            }
            val depthScale = (1f / (1f + kotlin.math.abs(depth) / 1400f)).coerceIn(0.35f, 1.4f)
            val size = (baseSize * (1f + (r0 - 0.5f) * 2f * sizeRandom) *
                (1f + ((endSize / baseSize.coerceAtLeast(0.1f)) - 1f) * progress) * depthScale * cameraScale).coerceAtLeast(0.35f)
            val opacity = (startOpacity + (endOpacity - startOpacity) * progress).coerceIn(0f, 100f)
            paint.color = mixColor(settings.color, settings.endColor, progress)
            paint.alpha = (opacity * 2.55f).toInt().coerceIn(0, 255)
            paint.style = Paint.Style.FILL
            val rotation = rotationSpeed * age + r1 * 360f
            canvas.save()
            canvas.rotate(rotation, drawX, drawY)
            when (settings.shape) {
                "Square" -> canvas.drawRect(drawX - size / 2f, drawY - size / 2f, drawX + size / 2f, drawY + size / 2f, paint)
                "Diamond" -> {
                    val path = android.graphics.Path().apply {
                        moveTo(drawX, drawY - size); lineTo(drawX + size, drawY); lineTo(drawX, drawY + size); lineTo(drawX - size, drawY); close()
                    }
                    canvas.drawPath(path, paint)
                }
                "Star" -> {
                    val path = android.graphics.Path()
                    for (point in 0 until 10) {
                        val radius = if (point % 2 == 0) size else size * 0.42f
                        val a = -Math.PI / 2.0 + point * Math.PI / 5.0
                        val px = drawX + kotlin.math.cos(a).toFloat() * radius
                        val py = drawY + kotlin.math.sin(a).toFloat() * radius
                        if (point == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    }
                    path.close()
                    canvas.drawPath(path, paint)
                }
                else -> canvas.drawCircle(drawX, drawY, size / 2f, paint)
            }
            canvas.restore()
        }
    }
}
