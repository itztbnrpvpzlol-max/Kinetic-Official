package com.kinetic.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.kinetic.app.model.COMP_H
import com.kinetic.app.model.COMP_W
import com.kinetic.app.model.Layer
import com.kinetic.app.model.LayerType
import com.kinetic.app.model.PropId
import com.kinetic.app.model.SceneProjector
import com.kinetic.app.model.childSpaceWorld4
import com.kinetic.app.model.transformPoint4
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Stateless, deterministic 3D particle system.
 *
 * Particles live in WORLD space: they are born from the emitter's world transform (which includes
 * its whole parent chain), simulated with gravity / wind / drag / turbulence, and then every single
 * particle is projected through the active camera. The layer itself is never warped as a plane, so
 * a camera parented to a null (or animated in any way) moves the view of the particles, not the
 * particles' layer. Scrubbing and export give identical frames because the result depends only on
 * the layer, the camera and the time.
 */
internal object ParticleRenderer {
    private const val STEP = 1f / 30f
    private const val MAX_LIFE = 15f

    /** Everything that can be keyed, sampled once per simulation step. */
    private data class StepParams(
        val t: Float,
        val rate: Float, val life: Float, val lifeRandom: Float,
        val velocity: Float, val velocityRandom: Float,
        val spread: Float, val tilt: Float, val spin: Float,
        val size: Float, val sizeRandom: Float, val endSize: Float,
        val opacity: Float, val endOpacity: Float,
        val fadeIn: Float, val fadeOut: Float, val feather: Float,
        val rotation: Float, val rotationRandom: Float, val spinDeg: Float, val spinRandom: Float,
        val stretch: Float,
        val gravity: Float, val windX: Float, val windY: Float, val windZ: Float,
        val drag: Float, val turbulence: Float, val turbulenceSpeed: Float,
        val tailLength: Float, val tailSegments: Int, val tailWidth: Float, val tailTaper: Float, val tailOpacity: Float,
        val offX: Float, val offY: Float, val offZ: Float,
        val sizeX: Float, val sizeY: Float, val sizeZ: Float,
        val emitter: FloatArray
    )

    private class Particle(val params: StepParams, val index: Int) {
        var x = 0f; var y = 0f; var z = 0f
        var vx = 0f; var vy = 0f; var vz = 0f
        var age = 0f
        var life = 1f
        var progress = 0f
        var depth = 0f
        var r0 = 0f; var r1 = 0f; var r2 = 0f; var r3 = 0f; var r4 = 0f
        var histAge: FloatArray? = null
        var histPos: FloatArray? = null
        var histCount = 0
    }

    private class Sim(val particles: List<Particle>, val last: StepParams, val nowEmitter: FloatArray)
    private val simCache = HashMap<String, Pair<Long, Sim>>()

    /** Closed-form position/velocity after [t] seconds under constant acceleration (ax, ay, az) and linear drag [k]. */
    private fun kin(p0: FloatArray, v0: FloatArray, ax: Float, ay: Float, az: Float, k: Float, t: Float, o: FloatArray) {
        if (k > 1e-4f) {
            val e = exp((-k * t).toDouble()).toFloat()
            val fac = (1f - e) / k
            val ux = ax / k; val uy = ay / k; val uz = az / k
            o[0] = p0[0] + ux * t + (v0[0] - ux) * fac
            o[1] = p0[1] + uy * t + (v0[1] - uy) * fac
            o[2] = p0[2] + uz * t + (v0[2] - uz) * fac
            o[3] = ux + (v0[0] - ux) * e
            o[4] = uy + (v0[1] - uy) * e
            o[5] = uz + (v0[2] - uz) * e
        } else {
            o[0] = p0[0] + v0[0] * t + 0.5f * ax * t * t
            o[1] = p0[1] + v0[1] * t + 0.5f * ay * t * t
            o[2] = p0[2] + v0[2] * t + 0.5f * az * t * t
            o[3] = v0[0] + ax * t; o[4] = v0[1] + ay * t; o[5] = v0[2] + az * t
        }
    }

    private fun hash(seed: Int, a: Int, salt: Int): Float {
        var v = seed xor (a * 0x45d9f3b) xor (salt * 0x27d4eb2d)
        v = (v xor (v ushr 16)) * 0x45d9f3b
        v = (v xor (v ushr 16)) * 0x45d9f3b
        v = v xor (v ushr 16)
        return ((v ushr 8) and 0x00FFFFFF).toFloat() / 16777215f
    }

    private fun mixRgb(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun m(x: Int, y: Int) = (x + (y - x) * k).toInt().coerceIn(0, 255)
        return Color.rgb(m(Color.red(a), Color.red(b)), m(Color.green(a), Color.green(b)), m(Color.blue(a), Color.blue(b)))
    }

    // ---------- soft sprite cache ----------
    private val sprites = HashMap<Int, Bitmap>()
    private fun softSprite(feather: Float): Bitmap {
        val key = (feather.coerceIn(0f, 1f) * 12f).toInt()
        return sprites.getOrPut(key) {
            val n = 128
            val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
            val hardness = (1f - key / 12f).coerceIn(0f, 0.97f)
            // eased (smoothstep) falloff from the hard core to the rim: no visible rings or flat discs
            val stops = 12
            val pos = FloatArray(stops + 1)
            val col = IntArray(stops + 1)
            for (i in 0..stops) {
                val u = i / stops.toFloat()
                pos[i] = hardness + (1f - hardness) * u
                val e = u * u * (3f - 2f * u)
                col[i] = Color.argb(((1f - e) * 255f).toInt().coerceIn(0, 255), 255, 255, 255)
            }
            pos[stops] = 1f
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
            paint.shader = RadialGradient(
                n / 2f, n / 2f, n / 2f - 0.5f,
                intArrayOf(Color.WHITE) + col, floatArrayOf(0f) + pos, Shader.TileMode.CLAMP
            )
            Canvas(bmp).drawCircle(n / 2f, n / 2f, n / 2f, paint)
            bmp
        }
    }

    private val addMode = PorterDuffXfermode(PorterDuff.Mode.ADD)

    // ---------- emitter / physics helpers ----------
    private fun axisDirection(tilt: Float, spin: Float, spread: Float, r1: Float, r2: Float): FloatArray {
        // base axis: up on screen. Tilt pitches it along depth, spin rolls it on screen.
        var ax = 0f
        var ay = -cos(tilt)
        var az = sin(tilt)
        val cs = cos(spin); val sn = sin(spin)
        val rx = ax * cs - ay * sn
        val ry = ax * sn + ay * cs
        ax = rx; ay = ry
        // uniform direction inside a cone of half-angle [spread] around the axis
        val half = spread.coerceIn(0f, PI.toFloat())
        val cosT = 1f - r1 * (1f - cos(half))
        val sinT = sqrt((1f - cosT * cosT).coerceAtLeast(0f))
        val phi = r2 * 2f * PI.toFloat()
        // orthonormal basis around the axis
        val hx = if (abs(ax) < 0.9f) 1f else 0f
        val hy = if (abs(ax) < 0.9f) 0f else 1f
        var ux = ay * 0f - az * hy; var uy = az * hx - ax * 0f; var uz = ax * hy - ay * hx
        val ul = sqrt(ux * ux + uy * uy + uz * uz).coerceAtLeast(1e-6f)
        ux /= ul; uy /= ul; uz /= ul
        val vx = ay * uz - az * uy; val vy = az * ux - ax * uz; val vz = ax * uy - ay * ux
        val cp = cos(phi) * sinT; val sp = sin(phi) * sinT
        return floatArrayOf(ax * cosT + ux * cp + vx * sp, ay * cosT + uy * cp + vy * sp, az * cosT + uz * cp + vz * sp)
    }

    private fun worldDir(e: FloatArray, d: FloatArray): FloatArray {
        val x = e[0] * d[0] + e[1] * d[1] + e[2] * d[2]
        val y = e[4] * d[0] + e[5] * d[1] + e[6] * d[2]
        val z = e[8] * d[0] + e[9] * d[1] + e[10] * d[2]
        val l = sqrt(x * x + y * y + z * z).coerceAtLeast(1e-6f)
        return floatArrayOf(x / l, y / l, z / l)
    }

    /**
     * Draws the layer's particles into [canvas], which maps composition pixels 1:1
     * (callers scale the canvas for preview/export resolution).
     */
    fun draw(
        canvas: Canvas, layer: Layer, time: Float, byId: Map<String, Layer>,
        maxParticles: Int = 4000
    ) {
        val settings = layer.particles
        fun value(id: PropId, fallback: Float = 0f, t: Float = time) =
            layer.p(id).valueAt(t).takeIf { it.isFinite() } ?: fallback

        fun simulate(): Sim? {
            // --- window of simulation steps that can still have living particles ---
            val lifeProp = layer.p(PropId.PARTICLE_LIFETIME)
            val randProp = layer.p(PropId.PARTICLE_LIFE_RANDOM)
            var lifeCeil = max(lifeProp.base, value(PropId.PARTICLE_LIFETIME, 2f))
            lifeProp.keys.forEach { lifeCeil = max(lifeCeil, it.v) }
            var randCeil = max(randProp.base, value(PropId.PARTICLE_LIFE_RANDOM, 20f))
            randProp.keys.forEach { randCeil = max(randCeil, it.v) }
            val maxLife = (lifeCeil.coerceAtLeast(0.05f) * (1f + randCeil / 100f)).coerceIn(0.05f, MAX_LIFE)
            val preroll = (value(PropId.PARTICLE_PREROLL, 1000f) / 1000f).coerceIn(0f, 30f)
            val startT = layer.start - preroll
            val endT = min(time, layer.end)
            if (endT < startT) return null
            val jNow = floor(time / STEP).toInt()
            val jFirst = max(floor(startT / STEP).toInt() , floor((time - maxLife) / STEP).toInt() - 1)
            if (jNow < jFirst) return null

            val seedValue = value(PropId.PARTICLE_RANDOM_SEED).toInt()
            val seed = if (seedValue == 0) layer.id.hashCode() else seedValue

            // --- per-step parameter table ---
            var chainAnimated = false
            var cursor: Layer? = layer
            var guard = 0
            while (cursor != null && guard++ < 16 && !chainAnimated) {
                chainAnimated = cursor.props.any { (id, pr) -> !id.isParticleProp && (pr.keys.size > 1 || pr.expression != null) }
                cursor = cursor.parentId?.let { byId[it] }
            }
            val emitterStatic = !chainAnimated
            val staticEmitter = if (emitterStatic) childSpaceWorld4(layer, time, byId) else null
            val count = jNow - jFirst + 1
            val table = ArrayList<StepParams>(count)
            fun makeParams(t: Float): StepParams {
                fun v(id: PropId, fb: Float = 0f) = value(id, fb, t)
                return StepParams(
                    t = t,
                    rate = v(PropId.PARTICLE_RATE, 60f).coerceIn(0f, 20000f),
                    life = v(PropId.PARTICLE_LIFETIME, 2f).coerceAtLeast(0.02f),
                    lifeRandom = v(PropId.PARTICLE_LIFE_RANDOM, 20f) / 100f,
                    velocity = v(PropId.PARTICLE_VELOCITY, 80f),
                    velocityRandom = v(PropId.PARTICLE_VELOCITY_RANDOM, 20f) / 100f,
                    spread = Math.toRadians(v(PropId.PARTICLE_SPREAD, 28f).toDouble()).toFloat(),
                    tilt = Math.toRadians(v(PropId.PARTICLE_DIRECTION_TILT).toDouble()).toFloat(),
                    spin = Math.toRadians(v(PropId.PARTICLE_DIRECTION_SPIN).toDouble()).toFloat(),
                    size = v(PropId.PARTICLE_SIZE, 6f).coerceAtLeast(0f),
                    sizeRandom = v(PropId.PARTICLE_SIZE_RANDOM, 30f) / 100f,
                    endSize = v(PropId.PARTICLE_END_SIZE, 0f).coerceAtLeast(0f),
                    opacity = v(PropId.PARTICLE_OPACITY, 100f).coerceIn(0f, 100f) / 100f,
                    endOpacity = v(PropId.PARTICLE_END_OPACITY, 0f).coerceIn(0f, 100f) / 100f,
                    fadeIn = v(PropId.PARTICLE_FADE_IN).coerceIn(0f, 100f) / 100f,
                    fadeOut = v(PropId.PARTICLE_FADE_OUT).coerceIn(0f, 100f) / 100f,
                    feather = v(PropId.PARTICLE_FEATHER).coerceIn(0f, 100f) / 100f,
                    rotation = v(PropId.PARTICLE_ROTATION),
                    rotationRandom = v(PropId.PARTICLE_ROTATION_RANDOM),
                    spinDeg = v(PropId.PARTICLE_SPIN) + v(PropId.PARTICLE_ROTATION_SPEED),
                    spinRandom = v(PropId.PARTICLE_SPIN_RANDOM),
                    stretch = v(PropId.PARTICLE_MOTION_STRETCH).coerceIn(0f, 1000f),
                    gravity = v(PropId.PARTICLE_GRAVITY),
                    windX = v(PropId.PARTICLE_WIND_X), windY = v(PropId.PARTICLE_WIND_Y), windZ = v(PropId.PARTICLE_WIND_Z),
                    drag = v(PropId.PARTICLE_DRAG).coerceIn(0f, 100f) / 20f,
                    turbulence = v(PropId.PARTICLE_TURBULENCE),
                    turbulenceSpeed = v(PropId.PARTICLE_TURBULENCE_SPEED, 1f).coerceIn(0f, 20f),
                    tailLength = v(PropId.PARTICLE_TAIL_LENGTH).coerceIn(0f, 10000f) / 1000f,
                    tailSegments = v(PropId.PARTICLE_TAIL_SEGMENTS, 8f).toInt().coerceIn(1, 32),
                    tailWidth = v(PropId.PARTICLE_TAIL_WIDTH, 100f).coerceIn(0f, 500f) / 100f,
                    tailTaper = v(PropId.PARTICLE_TAIL_TAPER).coerceIn(0f, 100f) / 100f,
                    tailOpacity = v(PropId.PARTICLE_TAIL_OPACITY, 100f).coerceIn(0f, 100f) / 100f,
                    offX = v(PropId.PARTICLE_POS_X), offY = v(PropId.PARTICLE_POS_Y), offZ = v(PropId.PARTICLE_POS_Z),
                    sizeX = v(PropId.PARTICLE_EMITTER_SIZE_X), sizeY = v(PropId.PARTICLE_EMITTER_SIZE_Y), sizeZ = v(PropId.PARTICLE_EMITTER_SIZE_Z),
                    emitter = if (emitterStatic) staticEmitter!! else childSpaceWorld4(layer, t, byId)
                )
            }
            // Nothing keyed on the particle properties (the usual case): build the parameter set ONCE and share it
            // across every step instead of sampling ~60 properties for each of hundreds of steps per frame.
            val paramsStatic = layer.props.none { (id, pr) -> id.isParticleProp && (pr.keys.size > 1 || pr.expression != null) }
            val base = if (paramsStatic) makeParams(time) else null
            for (j in jFirst..jNow) {
                val t = j * STEP
                table += when {
                    base == null -> makeParams(t)
                    emitterStatic -> base
                    else -> base.copy(emitter = childSpaceWorld4(layer, t, byId))
                }
            }
            fun stepAt(t: Float): StepParams = table[(floor(t / STEP).toInt() - jFirst).coerceIn(0, table.size - 1)]
            val stationary = settings.emitterMotion == "Stationary"
            val follow = settings.emitterMotion == "Follow Layer"
            val fixedEmitter = if (stationary) childSpaceWorld4(layer, layer.start, byId) else null
            val nowEmitter = table.last().emitter

            // --- spawn + simulate ---
            val particles = ArrayList<Particle>()
            var jStep = jNow
            val tailOn = settings.tailEnabled
            while (jStep >= jFirst && particles.size < maxParticles) {
                val sp = table[jStep - jFirst]
                val j = jStep
                jStep--
                val stepT = j * STEP
                if (stepT < startT - STEP || stepT > endT) continue
                val expected = sp.rate * STEP
                val n = floor(expected + hash(seed, j, 77)).toInt()
                for (m in 0 until n) {
                    if (particles.size >= maxParticles) break
                    val id = j * 64 + m
                    val r0 = hash(seed, id, 0); val r1 = hash(seed, id, 1); val r2 = hash(seed, id, 2)
                    val r3 = hash(seed, id, 3); val r4 = hash(seed, id, 4); val r5 = hash(seed, id, 5)
                    val tb = stepT + r5 * STEP
                    if (tb < startT || tb > time) continue
                    val age = time - tb
                    val life = (sp.life * (1f + (r0 * 2f - 1f) * sp.lifeRandom)).coerceIn(0.03f, MAX_LIFE)
                    if (age > life) continue

                    val particle = Particle(sp, id)
                    particle.r0 = r0; particle.r1 = r1; particle.r2 = r2; particle.r3 = r3; particle.r4 = r4
                    particle.life = life; particle.age = age; particle.progress = (age / life).coerceIn(0f, 1f)

                    // birth transform: fixed (stationary), current (follow) or at the birth time (trail)
                    val birthEmitter = fixedEmitter ?: if (follow) nowEmitter else sp.emitter
                    val local = FloatArray(3)
                    if (settings.sphereEmitter) {
                        val rad = cbrt(r2.toDouble()).toFloat()
                        val cy = r0 * 2f - 1f
                        val ring = sqrt((1f - cy * cy).coerceAtLeast(0f))
                        val ang = r1 * 2f * PI.toFloat()
                        local[0] = cos(ang) * ring * rad * sp.sizeX * 0.5f
                        local[1] = cy * rad * sp.sizeY * 0.5f
                        local[2] = sin(ang) * ring * rad * sp.sizeZ * 0.5f
                    } else {
                        local[0] = (r2 - 0.5f) * sp.sizeX
                        local[1] = (r3 - 0.5f) * sp.sizeY
                        local[2] = (r4 - 0.5f) * sp.sizeZ
                    }
                    local[0] += sp.offX; local[1] += sp.offY; local[2] += sp.offZ
                    val dirLocal = axisDirection(sp.tilt, sp.spin, sp.spread, r1, r3)
                    val speed = sp.velocity * (1f + (r2 * 2f - 1f) * sp.velocityRandom)

                    // Follow Layer simulates in emitter space; everything else directly in world space.
                    var px: Float; var py: Float; var pz: Float
                    var vx: Float; var vy: Float; var vz: Float
                    if (follow) {
                        px = local[0]; py = local[1]; pz = local[2]
                        vx = dirLocal[0] * speed; vy = dirLocal[1] * speed; vz = dirLocal[2] * speed
                    } else {
                        val w = transformPoint4(birthEmitter, local[0], local[1], local[2])
                        px = w[0]; py = w[1]; pz = w[2]
                        val d = worldDir(birthEmitter, dirLocal)
                        vx = d[0] * speed; vy = d[1] * speed; vz = d[2] * speed
                        if (settings.velocityFromMotion && !stationary) {
                            val prev = table[max(0, (j - jFirst) - 1)].emitter
                            vx += (sp.emitter[3] - prev[3]) / STEP
                            vy += (sp.emitter[7] - prev[7]) / STEP
                            vz += (sp.emitter[11] - prev[11]) / STEP
                        }
                    }

                    if (base != null) {
                        // Nothing keyed: gravity, wind and drag have a closed form, so a particle costs O(1)
                        // no matter how old it is (instead of one integration step per 1/30 s of its life).
                        val p0 = floatArrayOf(px, py, pz); val v0 = floatArrayOf(vx, vy, vz)
                        val o = FloatArray(6)
                        val ax = base.windX; val ay = base.gravity + base.windY; val az = base.windZ
                        kin(p0, v0, ax, ay, az, base.drag, age, o)
                        px = o[0]; py = o[1]; pz = o[2]; vx = o[3]; vy = o[4]; vz = o[5]
                        if (tailOn && sp.tailLength > 0f) {
                            val segs = sp.tailSegments
                            val hAge = FloatArray(segs + 1); val hPos = FloatArray((segs + 1) * 3)
                            for (i in 0..segs) {
                                val a2 = (age - sp.tailLength * (segs - i) / segs).coerceAtLeast(0f)
                                kin(p0, v0, ax, ay, az, base.drag, a2, o)
                                hAge[i] = a2; hPos[i * 3] = o[0]; hPos[i * 3 + 1] = o[1]; hPos[i * 3 + 2] = o[2]
                            }
                            particle.histAge = hAge; particle.histPos = hPos; particle.histCount = segs + 1
                        }
                    } else {
                        if (tailOn) {
                            val cap = (age / STEP).toInt() + 3
                            particle.histAge = FloatArray(cap); particle.histPos = FloatArray(cap * 3)
                            particle.histAge!![0] = 0f
                            particle.histPos!![0] = px; particle.histPos!![1] = py; particle.histPos!![2] = pz
                            particle.histCount = 1
                        }

                        // integrate on the global step grid so keyed physics is honoured
                        var t = tb
                        while (t < time - 1e-5f) {
                            val gridEnd = (floor(t / STEP + 1e-4f) + 1f) * STEP
                            val tn = min(gridEnd, time)
                            val dt = tn - t
                            if (dt <= 1e-6f) { t = tn; continue }
                            val ph = stepAt(t)
                            vy += ph.gravity * dt
                            vx += ph.windX * dt; vy += ph.windY * dt; vz += ph.windZ * dt
                            if (ph.drag > 0f) {
                                val damp = exp((-ph.drag * dt).toDouble()).toFloat()
                                vx *= damp; vy *= damp; vz *= damp
                            }
                            px += vx * dt; py += vy * dt; pz += vz * dt
                            t = tn
                            val hist = particle.histPos
                            if (hist != null && particle.histCount < particle.histAge!!.size) {
                                val h = particle.histCount++
                                particle.histAge!![h] = t - tb
                                hist[h * 3] = px; hist[h * 3 + 1] = py; hist[h * 3 + 2] = pz
                            }
                        }
                    }
                    particle.vx = vx; particle.vy = vy; particle.vz = vz
                    // turbulence: smooth 3D wobble that grows in over the first moments of life
                    if (sp.turbulence != 0f) {
                        val w = sp.turbulenceSpeed * 2f * PI.toFloat() * 0.5f
                        val amp = sp.turbulence * min(1f, age * 2f)
                        px += amp * (sin(age * w + r1 * 6.2832f) + 0.5f * sin(age * w * 2.3f + r2 * 6.2832f)) * 0.66f
                        py += amp * (sin(age * w * 1.3f + r3 * 6.2832f) + 0.5f * sin(age * w * 1.9f + r4 * 6.2832f)) * 0.66f
                        pz += amp * (sin(age * w * 0.9f + r0 * 6.2832f) + 0.5f * sin(age * w * 2.7f + r1 * 6.2832f)) * 0.66f
                    }
                    if (follow) {
                        val w = transformPoint4(nowEmitter, px, py, pz)
                        px = w[0]; py = w[1]; pz = w[2]
                        val hist = particle.histPos
                        if (hist != null) for (h in 0 until particle.histCount) {
                            val hw = transformPoint4(nowEmitter, hist[h * 3], hist[h * 3 + 1], hist[h * 3 + 2])
                            hist[h * 3] = hw[0]; hist[h * 3 + 1] = hw[1]; hist[h * 3 + 2] = hw[2]
                        }
                    }
                    particle.x = px; particle.y = py; particle.z = pz
                    particles += particle
                }
            }

            return Sim(particles, table.last(), nowEmitter)
        }

        // The simulation depends on the layer, its parent chain and the time, but NOT on the camera.
        // Cache it, so moving/animating the camera (or a null it hangs from) only re-projects.
        var simKey = layer.hashCode().toLong() * 31L + time.toBits() + maxParticles
        run {
            var c: Layer? = layer.parentId?.let { byId[it] }
            var g = 0
            while (c != null && g++ < 16) {
                simKey = simKey * 31L + c.props.hashCode() + (c.parentId?.hashCode() ?: 0) + c.width.toBits() + c.height.toBits()
                c = c.parentId?.let { byId[it] }
            }
        }
        val sim: Sim = synchronized(simCache) {
            simCache[layer.id]?.takeIf { it.first == simKey }?.second
        } ?: (simulate() ?: return).also { fresh ->
            synchronized(simCache) {
                if (simCache.size > 8) simCache.clear()
                simCache[layer.id] = simKey to fresh
            }
        }
        val particles = ArrayList(sim.particles)
        val nowEmitter = sim.nowEmitter
        val tailOn = settings.tailEnabled
        if (particles.isEmpty() && !settings.showLayer) return

        // --- projection through the scene camera ---
        val projector = SceneProjector(byId, time)
        val out = FloatArray(4)
        val out2 = FloatArray(4)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        paint.xfermode = if (settings.additive) addMode else null
        val spriteLayer = settings.spriteLayerId?.let { byId[it] }
            ?.takeIf { it.type == LayerType.TEXT || it.type == LayerType.SHAPE }
        val spriteBitmap = spriteLayer?.let { LayerRaster.render(it, time, 256, byId = byId) }

        // far to near
        for (p in particles) {
            p.depth = if (projector.project(p.x, p.y, p.z, out)) out[3] else -1f
        }
        particles.sortByDescending { it.depth }

        val path = Path()
        val rect = RectF()
        var lastTint = 0
        var lastFilter: PorterDuffColorFilter? = null
        fun tint(color: Int): PorterDuffColorFilter {
            val cached = lastFilter
            if (cached != null && lastTint == color) return cached
            val f = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
            lastTint = color; lastFilter = f
            return f
        }

        for (p in particles) {
            if (p.depth <= 0f) continue
            val sp = p.params
            if (!projector.project(p.x, p.y, p.z, out)) continue
            val k = out[2]
            val sx = out[0]; val sy = out[1]
            if (sx < -COMP_W || sx > COMP_W * 2f || sy < -COMP_H || sy > COMP_H * 2f) continue
            val progress = p.progress
            val randSize = 1f + (p.r0 * 2f - 1f) * sp.sizeRandom
            val worldSize = (sp.size + (sp.endSize - sp.size) * progress) * randSize.coerceAtLeast(0.05f)
            val diameter = worldSize * k
            if (diameter < 0.3f) continue
            val fadeIn = if (sp.fadeIn <= 0f) 1f else (progress / sp.fadeIn).coerceIn(0f, 1f)
            val fadeOut = if (sp.fadeOut <= 0f) 1f else ((1f - progress) / sp.fadeOut).coerceIn(0f, 1f)
            val envelope = min(fadeIn, fadeOut)
            val opacity = ((sp.opacity + (sp.endOpacity - sp.opacity) * progress) * envelope).coerceIn(0f, 1f)
            if (opacity <= 0.004f) continue
            val color = mixRgb(settings.color, settings.endColor, progress)
            val alpha = (opacity * 255f).toInt().coerceIn(0, 255)

            // camera-relative screen direction of motion (for orient / stretch) and roll
            var motionAngle = 0f
            var motionLen = 0f
            val speed3 = sqrt(p.vx * p.vx + p.vy * p.vy + p.vz * p.vz)
            if (speed3 > 1e-3f &&
                projector.project(p.x - p.vx * 0.04f, p.y - p.vy * 0.04f, p.z - p.vz * 0.04f, out2)) {
                val dx = sx - out2[0]; val dy = sy - out2[1]
                motionLen = sqrt(dx * dx + dy * dy)
                if (motionLen > 1e-3f) motionAngle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
            }
            var roll = 0f
            if (projector.project(p.x + projector.right[0] * 20f, p.y + projector.right[1] * 20f, p.z + projector.right[2] * 20f, out2)) {
                roll = Math.toDegrees(atan2((out2[1] - sy).toDouble(), (out2[0] - sx).toDouble())).toFloat()
            }
            val angle = roll + sp.rotation + (p.r4 * 2f - 1f) * sp.rotationRandom +
                (sp.spinDeg + (p.r0 * 2f - 1f) * sp.spinRandom) * p.age +
                if (settings.orientToMotion) motionAngle + 90f else 0f
            val stretch = 1f + (sp.stretch / 100f) * (motionLen / max(diameter, 1f)).coerceAtMost(6f) * 0.25f

            // tail: re-projected history so it bends correctly under any camera. Drawn as ONE tapered
            // ribbon with a colour gradient (no overlapping round caps, so no "beads" along the streak).
            val hist = p.histPos
            if (tailOn && hist != null && sp.tailLength > 0f && sp.tailOpacity > 0f && p.histCount > 1) {
                val segs = sp.tailSegments
                val n = segs + 1
                val xs = FloatArray(n); val ys = FloatArray(n); val ws = FloatArray(n)
                xs[0] = sx; ys[0] = sy; ws[0] = diameter * sp.tailWidth * 0.5f
                var used = 1
                val ages = p.histAge!!
                for (s2 in 1..segs) {
                    val f = s2.toFloat() / segs
                    val a = (p.age - sp.tailLength * f).coerceAtLeast(0f)
                    var i = 0
                    while (i < p.histCount - 1 && ages[i + 1] < a) i++
                    val i2 = min(i + 1, p.histCount - 1)
                    val span = (ages[i2] - ages[i]).coerceAtLeast(1e-5f)
                    val u = ((a - ages[i]) / span).coerceIn(0f, 1f)
                    val hx = hist[i * 3] + (hist[i2 * 3] - hist[i * 3]) * u
                    val hy = hist[i * 3 + 1] + (hist[i2 * 3 + 1] - hist[i * 3 + 1]) * u
                    val hz = hist[i * 3 + 2] + (hist[i2 * 3 + 2] - hist[i * 3 + 2]) * u
                    if (!projector.project(hx, hy, hz, out2)) break
                    xs[s2] = out2[0]; ys[s2] = out2[1]
                    ws[s2] = diameter * sp.tailWidth * 0.5f * (out2[2] / k).coerceIn(0.2f, 3f) * (1f - sp.tailTaper * f).coerceAtLeast(0f)
                    used = s2 + 1
                }
                val dxT = xs[used - 1] - sx; val dyT = ys[used - 1] - sy
                if (used > 1 && dxT * dxT + dyT * dyT > 0.5f) {
                    path.reset()
                    // left edge forward, right edge back
                    for (i in 0 until used) {
                        val pi = max(0, i - 1); val ni = min(used - 1, i + 1)
                        var tx = xs[ni] - xs[pi]; var ty = ys[ni] - ys[pi]
                        val tl = sqrt(tx * tx + ty * ty).coerceAtLeast(1e-4f)
                        tx /= tl; ty /= tl
                        val ox = -ty * ws[i]; val oy = tx * ws[i]
                        if (i == 0) path.moveTo(xs[i] + ox, ys[i] + oy) else path.lineTo(xs[i] + ox, ys[i] + oy)
                    }
                    for (i in used - 1 downTo 0) {
                        val pi = max(0, i - 1); val ni = min(used - 1, i + 1)
                        var tx = xs[ni] - xs[pi]; var ty = ys[ni] - ys[pi]
                        val tl = sqrt(tx * tx + ty * ty).coerceAtLeast(1e-4f)
                        tx /= tl; ty /= tl
                        path.lineTo(xs[i] + ty * ws[i], ys[i] - tx * ws[i])
                    }
                    path.close()
                    val headA = (alpha * sp.tailOpacity).toInt().coerceIn(0, 255)
                    val rgb = color and 0x00FFFFFF
                    paint.style = Paint.Style.FILL
                    paint.colorFilter = null
                    paint.alpha = 255
                    paint.shader = android.graphics.LinearGradient(
                        sx, sy, xs[used - 1], ys[used - 1],
                        intArrayOf((headA shl 24) or rgb, ((headA * 0.45f).toInt() shl 24) or rgb, rgb),
                        floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
                    )
                    canvas.drawPath(path, paint)
                    paint.shader = null
                }
            }

            paint.style = Paint.Style.FILL
            paint.shader = null
            paint.alpha = alpha
            canvas.save()
            canvas.translate(sx, sy)
            canvas.rotate(angle)
            canvas.scale(1f, stretch)
            val r = diameter * 0.5f
            val bitmapSprite = spriteBitmap != null && settings.particleType == "Sprite"
            if (bitmapSprite) {
                paint.colorFilter = if (settings.spriteTint) tint(color) else null
                rect.set(-r, -r, r, r)
                canvas.drawBitmap(spriteBitmap!!, null, rect, paint)
                paint.colorFilter = null
            } else when (settings.particleType) {
                "Glow Sphere" -> {
                    paint.colorFilter = tint(color)
                    val soft = softSprite(1f)
                    paint.alpha = (alpha * 0.55f).toInt()
                    rect.set(-r * 2.6f, -r * 2.6f, r * 2.6f, r * 2.6f); canvas.drawBitmap(soft, null, rect, paint)
                    paint.alpha = (alpha * 0.85f).toInt()
                    val mid = softSprite(0.6f)
                    rect.set(-r * 1.4f, -r * 1.4f, r * 1.4f, r * 1.4f); canvas.drawBitmap(mid, null, rect, paint)
                    paint.alpha = alpha
                    val core = softSprite(0.15f)
                    rect.set(-r * 0.6f, -r * 0.6f, r * 0.6f, r * 0.6f); canvas.drawBitmap(core, null, rect, paint)
                    paint.colorFilter = null
                }
                "Cloudlet" -> {
                    paint.colorFilter = tint(color)
                    val soft = softSprite(0.9f)
                    val ox = floatArrayOf(-0.30f, 0.25f, 0.34f, -0.18f, 0.02f)
                    val oy = floatArrayOf(-0.10f, -0.24f, 0.18f, 0.28f, 0.02f)
                    for (c in 0 until 5) {
                        paint.alpha = (alpha * if (c == 4) 0.6f else 0.34f).toInt()
                        val jitter = (0.72f + hash(0x5bd1e995, p.index, c + 20) * 0.52f) * r * 1.5f
                        rect.set(ox[c] * diameter - jitter, oy[c] * diameter - jitter, ox[c] * diameter + jitter, oy[c] * diameter + jitter)
                        canvas.drawBitmap(soft, null, rect, paint)
                    }
                    paint.colorFilter = null
                }
                "Streaklet" -> {
                    paint.colorFilter = tint(color)
                    val soft = softSprite(0.7f)
                    paint.alpha = (alpha * 0.35f).toInt()
                    rect.set(-r * 0.45f, -r * 2.3f, r * 0.45f, r * 2.3f); canvas.drawBitmap(soft, null, rect, paint)
                    paint.alpha = alpha
                    rect.set(-r * 0.25f, -r * 1.6f, r * 0.25f, r * 1.6f); canvas.drawBitmap(soft, null, rect, paint)
                    paint.colorFilter = null
                }
                "Star" -> {
                    // Particular's star: a four-pointed glint with a soft glow behind it
                    paint.colorFilter = tint(color)
                    paint.alpha = (alpha * 0.5f).toInt()
                    rect.set(-r * 1.3f, -r * 1.3f, r * 1.3f, r * 1.3f)
                    canvas.drawBitmap(softSprite(1f), null, rect, paint)
                    paint.colorFilter = null
                    paint.color = color; paint.alpha = alpha
                    path.reset()
                    for (i in 0 until 8) {
                        val rad = if (i % 2 == 0) r * 1.5f else r * 0.2f
                        val a = -PI / 2.0 + i * PI / 4.0
                        val x = cos(a).toFloat() * rad; val y = sin(a).toFloat() * rad
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    path.close(); canvas.drawPath(path, paint)
                }
                else -> when (settings.shape) {
                    "Square" -> { paint.color = color; paint.alpha = alpha; canvas.drawRect(-r, -r, r, r, paint) }
                    "Diamond" -> {
                        paint.color = color; paint.alpha = alpha
                        path.reset(); path.moveTo(0f, -r); path.lineTo(r, 0f); path.lineTo(0f, r); path.lineTo(-r, 0f); path.close()
                        canvas.drawPath(path, paint)
                    }
                    else -> {
                        paint.colorFilter = tint(color)
                        val soft = softSprite(sp.feather)
                        rect.set(-r, -r, r, r)
                        canvas.drawBitmap(soft, null, rect, paint)
                        paint.colorFilter = null
                    }
                }
            }
            canvas.restore()
        }

        // emitter guide ("Show Layer"): the emitter volume, projected like everything else
        if (settings.showLayer) {
            val sp = sim.last
            val e = nowEmitter
            paint.xfermode = null
            paint.colorFilter = null
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = 0xFF2BD68A.toInt()
            paint.alpha = 200
            val hx = max(sp.sizeX, 8f) / 2f; val hy = max(sp.sizeY, 8f) / 2f; val hz = max(sp.sizeZ, 8f) / 2f
            val corners = Array(8) { i ->
                val c = transformPoint4(e, sp.offX + if (i and 1 == 0) -hx else hx,
                    sp.offY + if (i and 2 == 0) -hy else hy, sp.offZ + if (i and 4 == 0) -hz else hz)
                val o = FloatArray(4)
                if (projector.project(c[0], c[1], c[2], o)) o else null
            }
            val edges = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 0, 2, 1, 3, 4, 6, 5, 7, 0, 4, 1, 5, 2, 6, 3, 7)
            var i = 0
            while (i < edges.size) {
                val a = corners[edges[i]]; val b = corners[edges[i + 1]]
                if (a != null && b != null) canvas.drawLine(a[0], a[1], b[0], b[1], paint)
                i += 2
            }
        }
        spriteBitmap?.let { if (!it.isRecycled) it.recycle() }
    }
}
