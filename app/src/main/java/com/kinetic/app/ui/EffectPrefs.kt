package com.kinetic.app.ui

import android.content.Context
import com.kinetic.app.engine.EcEffect

object EffectPrefs {
    private const val FILE = "kinetic_effects"
    private const val MAX_RECENT = 14

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun recent(ctx: Context): List<String> =
        sp(ctx).getString("recent", "").orEmpty().split(',').filter { it.isNotEmpty() }

    fun favourites(ctx: Context): Set<String> = sp(ctx).getStringSet("fav", emptySet()).orEmpty().toSet()

    fun counts(ctx: Context): Map<String, Int> =
        sp(ctx).getString("counts", "").orEmpty().split(';').mapNotNull {
            val p = it.split('=')
            if (p.size == 2) p[1].toIntOrNull()?.let { n -> p[0] to n } else null
        }.toMap()

    fun record(ctx: Context, id: String) {
        val p = sp(ctx)
        val recent = (listOf(id) + recent(ctx).filter { it != id }).take(MAX_RECENT)
        val counts = counts(ctx).toMutableMap()
        counts[id] = (counts[id] ?: 0) + 1
        p.edit().putString("recent", recent.joinToString(","))
            .putString("counts", counts.entries.joinToString(";") { it.key + "=" + it.value }).apply()
    }

    fun toggleFavourite(ctx: Context, id: String): Set<String> {
        val next = favourites(ctx).toMutableSet()
        if (!next.add(id)) next.remove(id)
        sp(ctx).edit().putStringSet("fav", next).apply()
        return next
    }

    private val starters = listOf(
        "Glow", "Gaussian Blur", "Drop Shadow", "Fractal Noise", "Lens Flare", "Find Edges", "Posterize",
        "Kaleidoscope", "Cartoon", "Vignette", "Light Sweep", "Hue/Saturation", "Wave Warp", "Mosaic",
        "Directional Blur", "Echo", "Tint", "Invert"
    )

    class Suggestions(val personal: Boolean, val effects: List<EcEffect>)

    fun suggestions(all: List<EcEffect>, counts: Map<String, Int>, limit: Int = 10): Suggestions {
        if (all.isEmpty()) return Suggestions(false, emptyList())
        val byId = all.associateBy { it.id }
        val used = counts.filterKeys { byId.containsKey(it) }
        if (used.isEmpty()) {
            val byName = all.associateBy { it.name.lowercase() }
            val picked = starters.mapNotNull { byName[it.lowercase()] }.toMutableList()
            if (picked.size < limit) {
                val seen = picked.map { it.id }.toMutableSet()
                all.filter { !it.audio }.groupBy { it.category }.values.forEach { group ->
                    group.sortedBy { it.name }.firstOrNull { seen.add(it.id) }?.let { picked.add(it) }
                }
            }
            return Suggestions(false, picked.take(limit))
        }
        val top = used.entries.sortedByDescending { it.value }.take(3).mapNotNull { byId[it.key] }
        val categoryWeight = HashMap<String, Int>()
        used.forEach { (id, n) -> byId[id]?.let { categoryWeight[it.category] = (categoryWeight[it.category] ?: 0) + n } }
        val seen = top.map { it.id }.toMutableSet()
        val related = ArrayList<EcEffect>()
        categoryWeight.entries.sortedByDescending { it.value }.take(3).forEach { (category, _) ->
            all.filter { it.category == category && it.id !in used }.sortedBy { it.name }.take(4).forEach {
                if (seen.add(it.id)) related.add(it)
            }
        }
        return Suggestions(true, (top + related).take(limit))
    }
}
