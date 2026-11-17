package com.kinetic.app.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only view of EffectCraft's built-in effect registry.
 *
 * Discovery is separate from effect application: Kinetic's current preview renderer
 * only accepts its existing native effects, so entries outside that set are displayed
 * as catalog-only until the render pipeline is migrated.
 */
data class EffectCraftEffectInfo(
    val id: String,
    val name: String,
    val category: String,
    val gpu: Boolean,
    val floatPrecision: Boolean,
    val parameterCount: Int
)

object EffectCraftCatalog {
    suspend fun load(): Result<List<EffectCraftEffectInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            check(EffectCraftBridge.initialize()) { "EffectCraft native engine is unavailable" }
            val response = JSONObject(EffectCraftBridge.execute("effect.list"))
            check(response.optBoolean("ok")) {
                response.optString("error").ifBlank { "EffectCraft could not list its effects" }
            }
            val items = response.optJSONArray("result")
                ?: throw IllegalStateException("EffectCraft returned an invalid effect catalog")
            buildList {
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    val id = item.optString("id").trim()
                    val name = item.optString("name").trim()
                    if (id.isBlank() || name.isBlank()) continue
                    val params = item.optJSONArray("params") ?: JSONArray()
                    add(
                        EffectCraftEffectInfo(
                            id = id,
                            name = name,
                            category = item.optString("category", "Other").ifBlank { "Other" },
                            gpu = item.optBoolean("gpu"),
                            floatPrecision = item.optBoolean("float"),
                            parameterCount = params.length()
                        )
                    )
                }
            }.also { check(it.isNotEmpty()) { "EffectCraft returned an empty effect catalog" } }
        }
    }
}
