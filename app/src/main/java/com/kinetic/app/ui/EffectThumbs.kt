package com.kinetic.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.kinetic.app.R
import com.kinetic.app.engine.EcEffect
import com.kinetic.app.engine.EffectCategories
import com.kinetic.app.engine.EffectCraftNative
import com.kinetic.app.engine.EffectStackRunner
import com.kinetic.app.model.createEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

object EffectThumbs {
    private const val SIZE = 160
    private const val DIR = "effthumb_v2"
    private val cache = LruCache<String, ImageBitmap>(180)
    private val sources = HashMap<Int, Bitmap>()
    private val gate = Semaphore(1)

    private val landscapes = intArrayOf(R.drawable.smp_lake, R.drawable.smp_haze)
    private val girls = intArrayOf(R.drawable.smp_stars, R.drawable.smp_door, R.drawable.smp_closeup, R.drawable.smp_blush, R.drawable.smp_laugh)

    private val pools: Map<String, IntArray> = mapOf(
        EffectCategories.COLOR_LIGHT to landscapes,
        EffectCategories.STYLIZE to intArrayOf(
            R.drawable.smp_lake, R.drawable.smp_haze, R.drawable.smp_lake, R.drawable.smp_haze, R.drawable.smp_lake,
            R.drawable.smp_haze, R.drawable.smp_stars, R.drawable.smp_door, R.drawable.smp_closeup
        ),
        EffectCategories.DRAWING_EDGE to intArrayOf(
            R.drawable.smp_stars, R.drawable.smp_door, R.drawable.smp_closeup, R.drawable.smp_blush, R.drawable.smp_laugh,
            R.drawable.smp_stars, R.drawable.smp_door, R.drawable.smp_lake, R.drawable.smp_closeup, R.drawable.smp_haze
        ),
        EffectCategories.MOTION to girls,
        EffectCategories.DISTORTION to intArrayOf(
            R.drawable.smp_lake, R.drawable.smp_closeup, R.drawable.smp_haze, R.drawable.smp_blush,
            R.drawable.smp_lake, R.drawable.smp_stars, R.drawable.smp_haze, R.drawable.smp_door
        ),
        EffectCategories.BLUR to intArrayOf(
            R.drawable.smp_lake, R.drawable.smp_closeup, R.drawable.smp_haze, R.drawable.smp_door,
            R.drawable.smp_lake, R.drawable.smp_stars, R.drawable.smp_haze, R.drawable.smp_blush
        ),
        EffectCategories.THREE_D to landscapes,
        EffectCategories.OTHER to intArrayOf(
            R.drawable.smp_blush, R.drawable.smp_lake, R.drawable.smp_door, R.drawable.smp_haze,
            R.drawable.smp_laugh, R.drawable.smp_stars, R.drawable.smp_closeup
        )
    )

    fun imageFor(effect: EcEffect): Int {
        val pool = pools[effect.category] ?: pools.getValue(EffectCategories.OTHER)
        return pool[abs(effect.id.hashCode()) % pool.size]
    }

    fun usesPhoto(effect: EcEffect): Boolean = !effect.audio &&
        effect.category != EffectCategories.PROCEDURAL && effect.category != EffectCategories.TEXT

    fun peek(id: String): ImageBitmap? = cache.get(id)

    private fun photo(ctx: Context, res: Int): Bitmap = synchronized(sources) {
        sources.getOrPut(res) {
            val raw = BitmapFactory.decodeResource(ctx.resources, res)
            Bitmap.createScaledBitmap(raw, SIZE, SIZE, true).also { if (it !== raw) raw.recycle() }
        }
    }

    private fun blackBase(): Bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.BLACK) }

    private fun textBase(): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFF1B1B1F.toInt()
        p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        p.textSize = SIZE * 0.34f
        val word = "Text"
        val scale = SIZE * 0.74f / p.measureText(word)
        p.textSize *= scale
        val m = p.fontMetrics
        c.drawText(word, (SIZE - p.measureText(word)) / 2f, SIZE / 2f - (m.ascent + m.descent) / 2f, p)
        return bmp
    }

    private fun audioBase(effect: EcEffect): Bitmap {
        val rate = 44100
        val n = rate * 3 / 2
        val dry = FloatArray(n * 2)
        val rnd = java.util.Random(7)
        for (i in 0 until n) {
            val t = i / rate.toDouble()
            var env = 0.0
            for (at in doubleArrayOf(0.05, 0.62, 1.05)) if (t >= at) env += Math.exp(-(t - at) / 0.09)
            val v = (0.55 * env * (Math.sin(6.2831853 * 180.0 * t) + 0.5 * Math.sin(6.2831853 * 540.0 * t) + 0.12 * (rnd.nextDouble() - 0.5))).toFloat()
            dry[i * 2] = v
            dry[i * 2 + 1] = v
        }
        val wet = dry.copyOf()
        val inst = createEffect(effect.id)
        if (inst != null) runCatching {
            EffectCraftNative.nativeProcessAudio(wet, rate, 0.0, EffectStackRunner.stackJson(listOf(inst), 0f))
        }
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF101C17.toInt())
        val bars = 30
        val seg = n / bars
        val cell = SIZE * 0.84f / bars
        val x0 = SIZE * 0.08f
        val mid = SIZE / 2f
        val ghost = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x38FFFFFF }
        val live = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF79D8A7.toInt() }
        val axis = Paint().apply { color = 0x26FFFFFF; strokeWidth = 1f }
        c.drawLine(x0, mid, SIZE - x0, mid, axis)
        fun peak(a: FloatArray, b: Int): Float {
            var m = 0f
            for (i in b * seg until (b + 1) * seg step 6) m = maxOf(m, kotlin.math.abs(a[i * 2]))
            return m.coerceIn(0f, 1f)
        }
        for (b in 0 until bars) {
            val x = x0 + b * cell
            val dh = (peak(dry, b) * SIZE * 0.42f).coerceAtLeast(1.5f)
            val wh = (peak(wet, b) * SIZE * 0.42f).coerceAtLeast(1.5f)
            c.drawRoundRect(RectF(x, mid - dh, x + cell * 0.86f, mid + dh), 2f, 2f, ghost)
            c.drawRoundRect(RectF(x + cell * 0.2f, mid - wh, x + cell * 0.66f, mid + wh), 2f, 2f, live)
        }
        return bmp
    }

    suspend fun load(ctx: Context, effect: EcEffect): ImageBitmap? {
        cache.get(effect.id)?.let { return it }
        return withContext(Dispatchers.Default) {
            gate.withPermit {
                cache.get(effect.id)?.let { return@withPermit it }
                val file = File(File(ctx.cacheDir, DIR), effect.id + ".png")
                if (file.exists()) {
                    runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull()?.let {
                        cache.put(effect.id, it)
                        return@withPermit it
                    }
                }
                val result: Bitmap = if (effect.audio) {
                    audioBase(effect)
                } else {
                    val src = when (effect.category) {
                        EffectCategories.PROCEDURAL -> blackBase()
                        EffectCategories.TEXT -> textBase()
                        else -> photo(ctx, imageFor(effect))
                    }
                    val inst = createEffect(effect.id)
                    val out = if (inst == null) null else runCatching {
                        EffectStackRunner.processBitmap(src, listOf(inst), 0f, 0.6f, 30f, 1080f, 1080f)
                    }.getOrNull()
                    out ?: src.copy(Bitmap.Config.ARGB_8888, false)
                }
                runCatching {
                    file.parentFile?.mkdirs()
                    file.outputStream().use { result.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                val image = result.asImageBitmap()
                cache.put(effect.id, image)
                image
            }
        }
    }
}
