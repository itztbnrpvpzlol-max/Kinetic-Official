package com.kinetic.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.kinetic.app.R
import com.kinetic.app.engine.EcEffect
import com.kinetic.app.engine.EffectCategories
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
    private const val DIR = "effthumb_v1"
    private val cache = LruCache<String, ImageBitmap>(180)
    private val sources = HashMap<Int, Bitmap>()
    private val gate = Semaphore(1)

    private val pools: Map<String, IntArray> = mapOf(
        EffectCategories.COLOR_LIGHT to intArrayOf(R.drawable.smp_lake, R.drawable.smp_closeup, R.drawable.smp_haze),
        EffectCategories.STYLIZE to intArrayOf(R.drawable.smp_stars, R.drawable.smp_closeup, R.drawable.smp_lake),
        EffectCategories.DRAWING_EDGE to intArrayOf(R.drawable.smp_stars, R.drawable.smp_door, R.drawable.smp_closeup),
        EffectCategories.MOTION to intArrayOf(R.drawable.smp_door, R.drawable.smp_closeup, R.drawable.smp_lake),
        EffectCategories.DISTORTION to intArrayOf(R.drawable.smp_closeup, R.drawable.smp_blush, R.drawable.smp_stars),
        EffectCategories.PROCEDURAL to intArrayOf(R.drawable.smp_haze, R.drawable.smp_lake),
        EffectCategories.BLUR to intArrayOf(R.drawable.smp_lake, R.drawable.smp_stars, R.drawable.smp_closeup),
        EffectCategories.THREE_D to intArrayOf(R.drawable.smp_lake, R.drawable.smp_haze, R.drawable.smp_door),
        EffectCategories.TEXT to intArrayOf(R.drawable.smp_haze),
        EffectCategories.AUDIO to intArrayOf(R.drawable.smp_laugh),
        EffectCategories.OTHER to intArrayOf(R.drawable.smp_blush, R.drawable.smp_laugh, R.drawable.smp_haze)
    )

    fun imageFor(effect: EcEffect): Int {
        val pool = pools[effect.category] ?: pools.getValue(EffectCategories.OTHER)
        return pool[abs(effect.id.hashCode()) % pool.size]
    }

    fun peek(id: String): ImageBitmap? = cache.get(id)

    private fun source(ctx: Context, res: Int): Bitmap = synchronized(sources) {
        sources.getOrPut(res) {
            val raw = BitmapFactory.decodeResource(ctx.resources, res)
            Bitmap.createScaledBitmap(raw, SIZE, SIZE, true).also { if (it !== raw) raw.recycle() }
        }
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
                val src = source(ctx, imageFor(effect))
                val inst = createEffect(effect.id)
                val out = if (inst == null || effect.audio) null else runCatching {
                    EffectStackRunner.processBitmap(src, listOf(inst), 0f, 0.6f, 30f, 1080f, 1080f)
                }.getOrNull()
                val result = out ?: src.copy(Bitmap.Config.ARGB_8888, false)
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
