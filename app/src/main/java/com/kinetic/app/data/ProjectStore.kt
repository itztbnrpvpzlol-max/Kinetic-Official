package com.kinetic.app.data

import android.content.Context
import com.kinetic.app.model.EditorState
import com.kinetic.app.model.KeyType
import com.kinetic.app.model.Keyframe
import com.kinetic.app.model.Layer
import com.kinetic.app.model.LayerType
import com.kinetic.app.model.Prop
import com.kinetic.app.model.PropId
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Small, versioned project store. Project writes are atomic so a crash cannot leave half a JSON file. */
class ProjectStore(context: Context) {
    private val root = File(context.filesDir, "projects").apply { mkdirs() }

    data class ProjectInfo(val id: String, val name: String, val updatedAt: Long, val layerCount: Int)
    data class LoadedProject(val id: String, val name: String, val duration: Float, val resolution: Int, val time: Float, val layers: List<Layer>)

    fun list(): List<ProjectInfo> = root.listFiles { f -> f.isFile && f.extension == "kinetic" }
        ?.mapNotNull { file ->
            try {
                val json = JSONObject(file.readText())
                ProjectInfo(json.optString("id", file.nameWithoutExtension), json.optString("name", "Untitled"),
                    json.optLong("updatedAt", file.lastModified()), json.optJSONArray("layers")?.length() ?: 0)
            } catch (_: Exception) { null }
        }?.sortedByDescending { it.updatedAt } ?: emptyList()

    fun save(editor: EditorState) {
        val layers = JSONArray()
        editor.layers.forEach { layer -> layers.put(layerToJson(layer)) }
        val json = JSONObject()
            .put("version", 1)
            .put("id", editor.projectId)
            .put("name", editor.projectName)
            .put("updatedAt", System.currentTimeMillis())
            .put("duration", editor.duration.toDouble())
            .put("resolution", editor.resolution)
            .put("time", editor.time.toDouble())
            .put("layers", layers)
        val target = File(root, "${editor.projectId}.kinetic")
        val temp = File(root, "${editor.projectId}.tmp")
        temp.writeText(json.toString())
        if (!temp.renameTo(target)) {
            target.writeText(json.toString())
            temp.delete()
        }
    }

    fun load(id: String): LoadedProject? = try {
        val json = JSONObject(File(root, "$id.kinetic").readText())
        val layersJson = json.optJSONArray("layers") ?: JSONArray()
        val layers = (0 until layersJson.length()).mapNotNull { i ->
            try { layerFromJson(layersJson.getJSONObject(i)) } catch (_: Exception) { null }
        }
        LoadedProject(json.optString("id", id), json.optString("name", "Untitled"),
            json.optDouble("duration", 5.0).toFloat().coerceAtLeast(0.1f),
            json.optInt("resolution", 1), json.optDouble("time", 0.0).toFloat(), layers)
    } catch (_: Exception) { null }

    fun delete(id: String) { File(root, "$id.kinetic").delete() }

    private fun layerToJson(l: Layer): JSONObject {
        val props = JSONObject()
        l.props.forEach { (id, p) ->
            val keys = JSONArray()
            p.keys.forEach { k -> keys.put(JSONObject().put("t", k.t.toDouble()).put("v", k.v.toDouble())
                .put("type", k.type.name).put("x1", k.x1.toDouble()).put("y1", k.y1.toDouble())
                .put("x2", k.x2.toDouble()).put("y2", k.y2.toDouble())) }
            val pj = JSONObject().put("base", p.base.toDouble()).put("keys", keys)
            p.expression?.let { pj.put("expression", it) }
            props.put(id.name, pj)
        }
        return JSONObject().put("id", l.id).put("type", l.type.name).put("name", l.name)
            .put("uri", l.uri).put("start", l.start.toDouble()).put("duration", l.duration.toDouble())
            .put("srcOffset", l.srcOffset.toDouble()).put("srcLength", if (l.srcLength.isFinite()) l.srcLength.toDouble() else -1.0)
            .put("width", l.width.toDouble()).put("height", l.height.toDouble()).put("visible", l.visible)
            .put("parentId", l.parentId).put("scaleLinked", l.scaleLinked).put("text", l.text)
            .put("color", l.color).put("props", props)
            .put("effects", JSONArray(l.effects)).put("speed", l.speed.toDouble()).put("volume", l.volume.toDouble())
            .put("maskEnabled", l.maskEnabled).put("blendMode", l.blendMode)
    }

    private fun layerFromJson(j: JSONObject): Layer {
        val propsJson = j.optJSONObject("props") ?: JSONObject()
        val props = PropId.values().associateWith { id ->
            val p = propsJson.optJSONObject(id.name) ?: JSONObject().put("base", when (id) {
                PropId.ANCHOR_X, PropId.ANCHOR_Y -> 0.0
                PropId.POS_X -> 960.0; PropId.POS_Y -> 540.0
                PropId.SCALE_X, PropId.SCALE_Y, PropId.OPACITY -> 100.0
                else -> 0.0
            })
            val arr = p.optJSONArray("keys") ?: JSONArray()
            val keys = (0 until arr.length()).mapNotNull { i ->
                val k = arr.optJSONObject(i) ?: return@mapNotNull null
                Keyframe(k.optDouble("t").toFloat(), k.optDouble("v").toFloat(),
                    runCatching { KeyType.valueOf(k.optString("type", "LINEAR")) }.getOrDefault(KeyType.LINEAR),
                    k.optDouble("x1", 0.42).toFloat(), k.optDouble("y1", 0.0).toFloat(),
                    k.optDouble("x2", 0.58).toFloat(), k.optDouble("y2", 1.0).toFloat())
            }
            Prop(p.optDouble("base", 0.0).toFloat(), keys, p.optString("expression").takeIf { it.isNotBlank() })
        }
        val srcLength = j.optDouble("srcLength", -1.0).toFloat().let { if (it < 0f) Float.POSITIVE_INFINITY else it }
        return Layer(id = j.optString("id", UUID.randomUUID().toString()),
            type = runCatching { LayerType.valueOf(j.optString("type", "SHAPE")) }.getOrDefault(LayerType.SHAPE),
            name = j.optString("name", "Layer"), uri = j.optString("uri").takeIf { it.isNotBlank() && it != "null" },
            start = j.optDouble("start", 0.0).toFloat(), duration = j.optDouble("duration", 5.0).toFloat(),
            srcOffset = j.optDouble("srcOffset", 0.0).toFloat(), srcLength = srcLength,
            width = j.optDouble("width", 100.0).toFloat(), height = j.optDouble("height", 100.0).toFloat(),
            visible = j.optBoolean("visible", true), parentId = j.optString("parentId").takeIf { it.isNotBlank() && it != "null" },
            scaleLinked = j.optBoolean("scaleLinked", false), text = j.optString("text", "Text"),
            color = j.optInt("color", 0xFF8D8BA3.toInt()), props = props,
            effects = (j.optJSONArray("effects") ?: JSONArray()).let { a -> (0 until a.length()).map { a.optString(it) } },
            speed = j.optDouble("speed", 1.0).toFloat().coerceIn(0.1f, 4f),
            volume = j.optDouble("volume", 1.0).toFloat().coerceIn(0f, 2f),
            maskEnabled = j.optBoolean("maskEnabled", false), blendMode = j.optString("blendMode", "Normal"))
    }
}
