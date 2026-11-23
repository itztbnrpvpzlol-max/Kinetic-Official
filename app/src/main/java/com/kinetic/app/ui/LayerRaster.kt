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
import kotlin.math.pow

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
        if (!settings.showLayer) return
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
        val firstAlive = ((elapsed - maxLife).coerceAtLeast(0f) * rate).toInt().coerceAtLeast(0)
        val firstEmitted = maxOf(firstAlive, lastEmitted - 1200)
        val emitted = (lastEmitted - firstEmitted).coerceAtLeast(0)
        val velocityBase = value(com.kinetic.app.model.PropId.PARTICLE_VELOCITY, 80f)
        val velocityRandom = value(com.kinetic.app.model.PropId.PARTICLE_VELOCITY_RANDOM, 20f) / 100f
        val spread = Math.toRadians(value(com.kinetic.app.model.PropId.PARTICLE_SPREAD, 28f).toDouble()).toFloat()
        val directionTilt = Math.toRadians(value(com.kinetic.app.model.PropId.PARTICLE_DIRECTION_TILT).toDouble()).toFloat()
        val directionSpin = Math.toRadians(value(com.kinetic.app.model.PropId.PARTICLE_DIRECTION_SPIN).toDouble()).toFloat()
        val gravity = value(com.kinetic.app.model.PropId.PARTICLE_GRAVITY)
        val windX = value(com.kinetic.app.model.PropId.PARTICLE_WIND_X)
        val windY = value(com.kinetic.app.model.PropId.PARTICLE_WIND_Y)
        val windZ = value(com.kinetic.app.model.PropId.PARTICLE_WIND_Z)
        val baseSize = value(com.kinetic.app.model.PropId.PARTICLE_SIZE, 6f).coerceAtLeast(0f)
        val sizeRandom = value(com.kinetic.app.model.PropId.PARTICLE_SIZE_RANDOM, 30f) / 100f
        val endSize = value(com.kinetic.app.model.PropId.PARTICLE_END_SIZE, 0f).coerceAtLeast(0f)
        val startOpacity = value(com.kinetic.app.model.PropId.PARTICLE_OPACITY, 100f).coerceIn(0f, 100f)
        val endOpacity = value(com.kinetic.app.model.PropId.PARTICLE_END_OPACITY, 0f).coerceIn(0f, 100f)
        val fadeIn = value(com.kinetic.app.model.PropId.PARTICLE_FADE_IN).coerceIn(0f, 100f) / 100f
        val fadeOut = value(com.kinetic.app.model.PropId.PARTICLE_FADE_OUT).coerceIn(0f, 100f) / 100f
        val feather = value(com.kinetic.app.model.PropId.PARTICLE_FEATHER).coerceIn(0f, 100f) / 100f
        val emitterX = width * 0.5f + value(com.kinetic.app.model.PropId.PARTICLE_POS_X)
        val emitterY = height * 0.5f + value(com.kinetic.app.model.PropId.PARTICLE_POS_Y)
        val emitterW = value(com.kinetic.app.model.PropId.PARTICLE_EMITTER_SIZE_X)
        val emitterH = value(com.kinetic.app.model.PropId.PARTICLE_EMITTER_SIZE_Y)
        val emitterD = value(com.kinetic.app.model.PropId.PARTICLE_EMITTER_SIZE_Z)
        val emitterZ = value(com.kinetic.app.model.PropId.PARTICLE_POS_Z)
        val turbulence = value(com.kinetic.app.model.PropId.PARTICLE_TURBULENCE)
        val turbulenceSpeed = value(com.kinetic.app.model.PropId.PARTICLE_TURBULENCE_SPEED, 1f).coerceIn(0f, 20f)
        val drag = value(com.kinetic.app.model.PropId.PARTICLE_DRAG).coerceIn(0f, 100f) / 100f
        val rotation = value(com.kinetic.app.model.PropId.PARTICLE_ROTATION)
        val rotationRandom = value(com.kinetic.app.model.PropId.PARTICLE_ROTATION_RANDOM)
        val spin = value(com.kinetic.app.model.PropId.PARTICLE_SPIN) + value(com.kinetic.app.model.PropId.PARTICLE_ROTATION_SPEED)
        val spinRandom = value(com.kinetic.app.model.PropId.PARTICLE_SPIN_RANDOM)
        val motionStretch = value(com.kinetic.app.model.PropId.PARTICLE_MOTION_STRETCH).coerceIn(0f, 1000f)
        val tailLength = value(com.kinetic.app.model.PropId.PARTICLE_TAIL_LENGTH).coerceIn(0f, 10000f) / 1000f
        val tailSegments = value(com.kinetic.app.model.PropId.PARTICLE_TAIL_SEGMENTS, 8f).toInt().coerceIn(1, 32)
        val tailWidth = value(com.kinetic.app.model.PropId.PARTICLE_TAIL_WIDTH, 100f).coerceIn(0f, 500f) / 100f
        val tailTaper = value(com.kinetic.app.model.PropId.PARTICLE_TAIL_TAPER).coerceIn(0f, 100f) / 100f
        val tailOpacity = value(com.kinetic.app.model.PropId.PARTICLE_TAIL_OPACITY, 100f).coerceIn(0f, 100f) / 100f
        val seedValue = value(com.kinetic.app.model.PropId.PARTICLE_RANDOM_SEED).toInt()
        val seed = if (seedValue == 0) layer.id.hashCode() else seedValue
        val projectionInverse = if (byId != null && com.kinetic.app.model.activeCamera(byId, time) != null) {
            Matrix().let { inverse ->
                if (com.kinetic.app.model.layerMatrix(layer, time, byId).invert(inverse)) inverse else null
            }
        } else null
        val spriteLayer = settings.spriteLayerId?.let { byId?.get(it) }
            ?.takeIf { it.type == LayerType.TEXT || it.type == LayerType.SHAPE }
        val spriteBitmap = spriteLayer?.let { render(it, time, 256, byId = byId) }

        fun project(x: Float, y: Float, z: Float): FloatArray? {
            if (projectionInverse == null || byId == null) return floatArrayOf(x, y, 1f)
            val projected = com.kinetic.app.model.projectLayerPoint(layer, x, y, z, time, byId) ?: return null
            val point = floatArrayOf(projected.x, projected.y)
            projectionInverse.mapPoints(point)
            return floatArrayOf(point[0], point[1], projected.sizeScale)
        }

        val motionStep = 1f / 30f
        val motionVelocityX = if (settings.velocityFromMotion) {
            (layer.p(com.kinetic.app.model.PropId.POS_X).valueAt(time) -
                layer.p(com.kinetic.app.model.PropId.POS_X).valueAt((time - motionStep).coerceAtLeast(layer.start))) / motionStep
        } else 0f
        val motionVelocityY = if (settings.velocityFromMotion) {
            (layer.p(com.kinetic.app.model.PropId.POS_Y).valueAt(time) -
                layer.p(com.kinetic.app.model.PropId.POS_Y).valueAt((time - motionStep).coerceAtLeast(layer.start))) / motionStep
        } else 0f

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
            val angle = (-Math.PI / 2.0).toFloat() + directionSpin + (r1 * 2f - 1f) * spread
            val velocity = velocityBase * (1f + (r2 * 2f - 1f) * velocityRandom)
            val horizontalVelocity = velocity * kotlin.math.cos(directionTilt)
            val verticalVelocity = velocity * kotlin.math.sin(directionTilt)
            val sphereRadius = kotlin.math.cbrt(r2).toFloat()
            val sphereY = (r0 * 2f - 1f)
            val sphereRing = kotlin.math.sqrt((1f - sphereY * sphereY).coerceAtLeast(0f))
            val sphereAngle = r1 * (Math.PI.toFloat() * 2f)
            val sphereXOffset = kotlin.math.cos(sphereAngle) * sphereRing * sphereRadius * emitterW * 0.5f
            val sphereYOffset = sphereY * sphereRadius * emitterH * 0.5f
            val sphereZOffset = kotlin.math.sin(sphereAngle) * sphereRing * sphereRadius * emitterD * 0.5f
            val emitterOffsetX = when (settings.emitterMotion) {
                "Stationary" -> layer.p(com.kinetic.app.model.PropId.POS_X).valueAt(layer.start) -
                    layer.p(com.kinetic.app.model.PropId.POS_X).valueAt(time)
                "Leave Trail" -> layer.p(com.kinetic.app.model.PropId.POS_X).valueAt(birth) -
                    layer.p(com.kinetic.app.model.PropId.POS_X).valueAt(time)
                else -> 0f
            }
            val emitterOffsetY = when (settings.emitterMotion) {
                "Stationary" -> layer.p(com.kinetic.app.model.PropId.POS_Y).valueAt(layer.start) -
                    layer.p(com.kinetic.app.model.PropId.POS_Y).valueAt(time)
                "Leave Trail" -> layer.p(com.kinetic.app.model.PropId.POS_Y).valueAt(birth) -
                    layer.p(com.kinetic.app.model.PropId.POS_Y).valueAt(time)
                else -> 0f
            }
            val originX = emitterX + emitterOffsetX + if (settings.sphereEmitter) sphereXOffset else (r2 - 0.5f) * emitterW
            val originY = emitterY + emitterOffsetY + if (settings.sphereEmitter) sphereYOffset else (r3 - 0.5f) * emitterH
            val depth = emitterZ + if (settings.sphereEmitter) sphereZOffset else (r4 - 0.5f) * emitterD

            fun positionAt(sampleAge: Float): FloatArray {
                val damping = kotlin.math.exp((-drag * sampleAge * 1.8f).toDouble()).toFloat()
                val turbulenceOffset = kotlin.math.sin(sampleAge * turbulenceSpeed * 6.283185f + r3 * 6.28f) *
                    turbulence * sampleAge * 0.12f
                val x = originX + kotlin.math.sin(angle) * horizontalVelocity * sampleAge * damping +
                    motionVelocityX * sampleAge + 0.5f * windX * sampleAge * sampleAge + turbulenceOffset
                val y = originY - kotlin.math.cos(angle) * horizontalVelocity * sampleAge * damping +
                    motionVelocityY * sampleAge + 0.5f * (gravity + windY) * sampleAge * sampleAge -
                    turbulenceOffset * 0.35f
                val z = depth + verticalVelocity * sampleAge * damping + 0.5f * windZ * sampleAge * sampleAge
                return floatArrayOf(x, y, z)
            }

            val currentPosition = positionAt(age)
            val point = project(currentPosition[0], currentPosition[1], currentPosition[2]) ?: continue
            val drawX = point[0]
            val drawY = point[1]
            val depthScale = (1f / (1f + kotlin.math.abs(currentPosition[2]) / 1400f)).coerceIn(0.35f, 1.4f)
            val size = (baseSize * (1f + (r0 - 0.5f) * 2f * sizeRandom) *
                (1f + ((endSize / baseSize.coerceAtLeast(0.1f)) - 1f) * progress) * depthScale * point[2]).coerceAtLeast(0.35f)
            val fadeInAmount = if (fadeIn <= 0f) 1f else (progress / fadeIn).coerceIn(0f, 1f)
            val fadeOutAmount = if (fadeOut <= 0f) 1f else ((1f - progress) / fadeOut).coerceIn(0f, 1f)
            val fadeEnvelope = minOf(fadeInAmount, fadeOutAmount).let {
                if (feather <= 0f) it else it.toDouble().pow(1.0 + feather * 3.0).toFloat()
            }
            val opacity = (startOpacity + (endOpacity - startOpacity) * progress).coerceIn(0f, 100f) * fadeEnvelope
            paint.color = mixColor(settings.color, settings.endColor, progress)
            paint.alpha = (opacity * 2.55f).toInt().coerceIn(0, 255)
            paint.style = Paint.Style.FILL
            paint.xfermode = if (settings.additive) android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD) else null

            if (settings.tailEnabled && tailLength > 0f && tailOpacity > 0f) {
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                for (segment in 0 until tailSegments) {
                    val f0 = segment.toFloat() / tailSegments
                    val f1 = (segment + 1f) / tailSegments
                    val p0Age = (age - tailLength * f0).coerceAtLeast(0f)
                    val p1Age = (age - tailLength * f1).coerceAtLeast(0f)
                    val p0 = positionAt(p0Age)
                    val p1 = positionAt(p1Age)
                    val a = project(p0[0], p0[1], p0[2]) ?: continue
                    val b = project(p1[0], p1[1], p1[2]) ?: continue
                    val taper = (1f - tailTaper * f0).coerceAtLeast(0.05f)
                    paint.strokeWidth = (size * tailWidth * taper).coerceAtLeast(0.5f)
                    paint.alpha = (opacity * tailOpacity * (1f - f0 * 0.55f) * 2.55f).toInt().coerceIn(0, 255)
                    canvas.drawLine(a[0], a[1], b[0], b[1], paint)
                }
                paint.style = Paint.Style.FILL
                paint.alpha = (opacity * 2.55f).toInt().coerceIn(0, 255)
            }

            val randomRotation = (r4 * 2f - 1f) * rotationRandom
            val randomSpin = (r0 * 2f - 1f) * spinRandom
            val velocityAngle = Math.toDegrees(kotlin.math.atan2(
                -kotlin.math.cos(angle) * horizontalVelocity + motionVelocityY,
                kotlin.math.sin(angle) * horizontalVelocity + motionVelocityX
            ).toDouble()).toFloat() + 90f
            val particleRotation = rotation + randomRotation + (spin + randomSpin) * age +
                if (settings.orientToMotion) velocityAngle else 0f
            val stretch = 1f + (motionStretch / 1000f) * (kotlin.math.abs(velocity) / 200f).coerceAtMost(5f)
            canvas.save()
            canvas.rotate(particleRotation, drawX, drawY)
            canvas.scale(1f, stretch, drawX, drawY)
            if (spriteBitmap != null && settings.particleType == "Sprite") {
                paint.colorFilter = if (settings.spriteTint) {
                    android.graphics.PorterDuffColorFilter(paint.color, android.graphics.PorterDuff.Mode.SRC_IN)
                } else null
                val half = size * 0.5f
                canvas.drawBitmap(spriteBitmap, null,
                    android.graphics.RectF(drawX - half, drawY - half, drawX + half, drawY + half), paint)
                paint.colorFilter = null
            } else {
                when (settings.particleType) {
                    "Glow Sphere" -> {
                        val coreAlpha = paint.alpha
                        paint.alpha = (coreAlpha * 0.22f).toInt()
                        canvas.drawCircle(drawX, drawY, size * 1.45f, paint)
                        paint.alpha = (coreAlpha * 0.42f).toInt()
                        canvas.drawCircle(drawX, drawY, size * 0.92f, paint)
                        paint.alpha = coreAlpha
                        canvas.drawCircle(drawX, drawY, size * 0.5f, paint)
                    }
                    "Cloudlet" -> {
                        val alpha = paint.alpha
                        val radius = size * 0.38f
                        val offsets = arrayOf(
                            floatArrayOf(-0.30f, -0.10f), floatArrayOf(0.25f, -0.24f),
                            floatArrayOf(0.34f, 0.18f), floatArrayOf(-0.18f, 0.28f),
                            floatArrayOf(0.02f, 0.02f)
                        )
                        offsets.forEachIndexed { cloudIndex, offset ->
                            paint.alpha = (alpha * if (cloudIndex == 4) 0.68f else 0.34f).toInt()
                            val jitter = 0.72f + random01(seed, index, cloudIndex + 20) * 0.52f
                            canvas.drawCircle(drawX + offset[0] * size, drawY + offset[1] * size, radius * jitter, paint)
                        }
                        paint.alpha = alpha
                    }
                    "Streaklet" -> {
                        val alpha = paint.alpha
                        paint.alpha = (alpha * 0.32f).toInt()
                        canvas.drawOval(android.graphics.RectF(drawX - size * 0.22f, drawY - size * 1.15f,
                            drawX + size * 0.22f, drawY + size * 1.15f), paint)
                        paint.alpha = alpha
                        canvas.drawOval(android.graphics.RectF(drawX - size * 0.12f, drawY - size * 0.8f,
                            drawX + size * 0.12f, drawY + size * 0.8f), paint)
                    }
                    "Star" -> {
                        val path = android.graphics.Path()
                        for (pointIndex in 0 until 10) {
                            val radius = if (pointIndex % 2 == 0) size else size * 0.42f
                            val a = -Math.PI / 2.0 + pointIndex * Math.PI / 5.0
                            val px = drawX + kotlin.math.cos(a).toFloat() * radius
                            val py = drawY + kotlin.math.sin(a).toFloat() * radius
                            if (pointIndex == 0) path.moveTo(px, py) else path.lineTo(px, py)
                        }
                        path.close()
                        canvas.drawPath(path, paint)
                    }
                    else -> when (settings.shape) {
                        "Square" -> canvas.drawRect(drawX - size / 2f, drawY - size / 2f, drawX + size / 2f, drawY + size / 2f, paint)
                        "Diamond" -> {
                            val path = android.graphics.Path().apply {
                                moveTo(drawX, drawY - size); lineTo(drawX + size, drawY); lineTo(drawX, drawY + size); lineTo(drawX - size, drawY); close()
                            }
                            canvas.drawPath(path, paint)
                        }
                        "Star" -> {
                            val path = android.graphics.Path()
                            for (pointIndex in 0 until 10) {
                                val radius = if (pointIndex % 2 == 0) size else size * 0.42f
                                val a = -Math.PI / 2.0 + pointIndex * Math.PI / 5.0
                                val px = drawX + kotlin.math.cos(a).toFloat() * radius
                                val py = drawY + kotlin.math.sin(a).toFloat() * radius
                                if (pointIndex == 0) path.moveTo(px, py) else path.lineTo(px, py)
                            }
                            path.close()
                            canvas.drawPath(path, paint)
                        }
                        else -> canvas.drawCircle(drawX, drawY, size / 2f, paint)
                    }
                }
            }
            canvas.restore()
        }
        paint.xfermode = null
        paint.colorFilter = null
        paint.style = Paint.Style.FILL
        paint.alpha = 255
    }
}
