package com.kinetic.app.data

import android.content.Context
import com.kinetic.app.model.EditorState
import com.kinetic.app.model.EffectInstance
import com.kinetic.app.model.createEffect
import com.kinetic.app.model.KeyType
import com.kinetic.app.model.Keyframe
import com.kinetic.app.model.Layer
import com.kinetic.app.model.LayerMask
import com.kinetic.app.model.MaskStroke
import com.kinetic.app.model.LayerType
import com.kinetic.app.model.Prop
import com.kinetic.app.model.PropId
import com.kinetic.app.model.VectorPoint
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
            .put("version", 2)
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
        val projectVersion = json.optInt("version", 1)
        val layersJson = json.optJSONArray("layers") ?: JSONArray()
        val layers = (0 until layersJson.length()).mapNotNull { i ->
            try {
                val layerJson = layersJson.getJSONObject(i)
                // Version 1 called the original ghost-trail treatment "Deep Glow".
                // Preserve those saved projects by migrating it to Morphosis.
                if (projectVersion < 2) {
                    val stack = layerJson.optJSONArray("effectStack") ?: JSONArray()
                    for (effectIndex in 0 until stack.length()) {
                        val effect = stack.optJSONObject(effectIndex) ?: continue
                        if (effect.optString("name") == "Deep Glow") effect.put("name", "Morphosis")
                    }
                }
                layerFromJson(layerJson)
            } catch (_: Exception) { null }
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
        val maskJson: Any = l.maskPath?.let { mask ->
            val points = JSONArray().also { array ->
                mask.points.forEach { p -> array.put(JSONObject().put("x", p.x.toDouble()).put("y", p.y.toDouble())) }
            }
            val strokes = JSONArray().also { array ->
                mask.strokes.forEach { stroke ->
                    val strokePoints = JSONArray().also { ps ->
                        stroke.points.forEach { p -> ps.put(JSONObject().put("x", p.x.toDouble()).put("y", p.y.toDouble())) }
                    }
                    array.put(JSONObject().put("points", strokePoints).put("width", stroke.width.toDouble())
                        .put("foreground", stroke.foreground))
                }
            }
            JSONObject().put("kind", mask.kind).put("points", points).put("closed", mask.closed)
                .put("inverted", mask.inverted).put("mode", mask.mode).put("opacity", mask.opacity.toDouble())
                .put("feather", mask.feather.toDouble()).put("expansion", mask.expansion.toDouble())
                .put("brushSize", mask.brushSize.toDouble()).put("strokes", strokes).put("frozen", mask.frozen)
        } ?: JSONObject.NULL
        return JSONObject().put("id", l.id).put("type", l.type.name).put("name", l.name)
            .put("uri", l.uri).put("start", l.start.toDouble()).put("duration", l.duration.toDouble())
            .put("srcOffset", l.srcOffset.toDouble()).put("srcLength", if (l.srcLength.isFinite()) l.srcLength.toDouble() else -1.0)
            .put("width", l.width.toDouble()).put("height", l.height.toDouble()).put("visible", l.visible)
            .put("parentId", l.parentId).put("scaleLinked", l.scaleLinked).put("text", l.text)
            .put("color", l.color).put("strokeColor", l.strokeColor)
            .put("strokeWidth", l.strokeWidth.toDouble()).put("fillEnabled", l.fillEnabled)
            .put("strokeEnabled", l.strokeEnabled).put("fontSize", l.fontSize.toDouble())
            .put("fontFamily", l.fontFamily).put("textBold", l.textBold)
            .put("textItalic", l.textItalic).put("textAlign", l.textAlign).put("props", props)
            .put("effects", JSONArray(l.effects))
            .put("effectStack", JSONArray().also { array ->
                l.effectStack.forEach { effect ->
                    val parameters = JSONObject()
                    effect.parameters.forEach { (key, value) -> parameters.put(key, value.toDouble()) }
                    val parameterKeys = JSONObject()
                    effect.parameterKeys.forEach { (parameter, keys) ->
                        parameterKeys.put(parameter, JSONArray().also { keyArray ->
                            keys.forEach { key ->
                                keyArray.put(JSONObject().put("t", key.t.toDouble()).put("v", key.v.toDouble())
                                    .put("type", key.type.name).put("x1", key.x1.toDouble()).put("y1", key.y1.toDouble())
                                    .put("x2", key.x2.toDouble()).put("y2", key.y2.toDouble()))
                            }
                        })
                    }
                    array.put(JSONObject().put("id", effect.id).put("name", effect.name)
                        .put("enabled", effect.enabled).put("parameters", parameters)
                        .put("parameterKeys", parameterKeys)
                        .put("effectCraftId", effect.effectCraftId)
                        .put("effectCraftParameters", JSONObject().also { values ->
                            effect.effectCraftParameters.forEach { (key, value) -> values.put(key, value) }
                        }))
                }
            })
            .put("speed", l.p(PropId.SPEED).base.toDouble()).put("volume", l.p(PropId.VOLUME).base.toDouble())
            .put("maskEnabled", l.maskEnabled).put("blendMode", l.blendMode)
            .put("shapeKind", l.shapeKind)
            .put("vectorPoints", JSONArray().also { points -> l.vectorPoints.forEach { point -> points.put(JSONObject().put("x", point.x.toDouble()).put("y", point.y.toDouble())) } })
            .put("maskPath", maskJson)
    }

    private fun layerFromJson(j: JSONObject): Layer {
        val propsJson = j.optJSONObject("props") ?: JSONObject()
        val props = PropId.values().associateWith { id ->
            val p = propsJson.optJSONObject(id.name) ?: JSONObject().put("base", when (id) {
                PropId.ANCHOR_X, PropId.ANCHOR_Y -> 0.0
                PropId.POS_X -> 960.0; PropId.POS_Y -> 540.0
                PropId.SCALE_X, PropId.SCALE_Y, PropId.OPACITY -> 100.0
                PropId.SPEED -> j.optDouble("speed", 1.0)
                PropId.VOLUME -> j.optDouble("volume", 1.0)
                else -> if (id.isVectorPoint) {
                    val point = j.optJSONArray("vectorPoints")?.optJSONObject(id.vectorPointIndex)
                    point?.optDouble(if (id.vectorPointAxis == 'X') "x" else "y", 0.5) ?: 0.5
                } else 0.0
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
        val maskPath = j.optJSONObject("maskPath")?.let { mask ->
            val pointsJson = mask.optJSONArray("points") ?: JSONArray()
            val points = (0 until pointsJson.length()).mapNotNull { index ->
                pointsJson.optJSONObject(index)?.let { p ->
                    VectorPoint(p.optDouble("x", 0.5).toFloat().coerceIn(0f, 1f),
                        p.optDouble("y", 0.5).toFloat().coerceIn(0f, 1f))
                }
            }
            val strokesJson = mask.optJSONArray("strokes") ?: JSONArray()
            val strokes = (0 until strokesJson.length()).mapNotNull { index ->
                strokesJson.optJSONObject(index)?.let { stroke ->
                    val strokePointsJson = stroke.optJSONArray("points") ?: JSONArray()
                    val strokePoints = (0 until strokePointsJson.length()).mapNotNull { pointIndex ->
                        strokePointsJson.optJSONObject(pointIndex)?.let { p ->
                            VectorPoint(p.optDouble("x", 0.5).toFloat().coerceIn(0f, 1f),
                                p.optDouble("y", 0.5).toFloat().coerceIn(0f, 1f))
                        }
                    }
                    MaskStroke(strokePoints, stroke.optDouble("width", 0.045).toFloat().coerceIn(0.002f, 0.5f),
                        stroke.optBoolean("foreground", true))
                }
            }
            LayerMask(
                kind = mask.optString("kind", "Pen"), points = points,
                closed = mask.optBoolean("closed", false), inverted = mask.optBoolean("inverted", false),
                mode = mask.optString("mode", "Add"), opacity = mask.optDouble("opacity", 100.0).toFloat().coerceIn(0f, 100f),
                feather = mask.optDouble("feather", 0.0).toFloat().coerceIn(0f, 200f),
                expansion = mask.optDouble("expansion", 0.0).toFloat().coerceIn(-200f, 200f),
                brushSize = mask.optDouble("brushSize", 0.045).toFloat().coerceIn(0.002f, 0.5f),
                strokes = strokes, frozen = mask.optBoolean("frozen", false)
            )
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
            color = j.optInt("color", 0xFF8D8BA3.toInt()),
            strokeColor = j.optInt("strokeColor", 0xFF000000.toInt()),
            strokeWidth = j.optDouble("strokeWidth", 0.0).toFloat().coerceIn(0f, 200f),
            fillEnabled = j.optBoolean("fillEnabled", true), strokeEnabled = j.optBoolean("strokeEnabled", false),
            fontSize = j.optDouble("fontSize", 120.0).toFloat().coerceIn(1f, 1000f),
            fontFamily = j.optString("fontFamily", "Sans Serif"), textBold = j.optBoolean("textBold", true),
            textItalic = j.optBoolean("textItalic", false), textAlign = j.optString("textAlign", "Center"), props = props,
            effects = (j.optJSONArray("effects") ?: JSONArray()).let { a -> (0 until a.length()).map { a.optString(it) } },
            effectStack = (j.optJSONArray("effectStack") ?: JSONArray()).let { array ->
                (0 until array.length()).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    val name = item.optString("name")
                    val effectCraftId = item.optString("effectCraftId").takeIf { it.isNotBlank() && it != "null" }
                    val defaults = if (effectCraftId != null) emptyMap() else createEffect(name)?.parameters ?: return@mapNotNull null
                    val jsonParameters = item.optJSONObject("parameters") ?: JSONObject()
                    val parameters = defaults.toMutableMap()
                    val iterator = jsonParameters.keys()
                    while (iterator.hasNext()) {
                        val key = iterator.next()
                        parameters[key] = jsonParameters.optDouble(key, parameters[key]?.toDouble() ?: 0.0).toFloat()
                    }
                    val keyJson = item.optJSONObject("parameterKeys") ?: JSONObject()
                    val parameterKeys = mutableMapOf<String, List<Keyframe>>()
                    val keyNames = keyJson.keys()
                    while (keyNames.hasNext()) {
                        val parameter = keyNames.next()
                        val keyArray = keyJson.optJSONArray(parameter) ?: JSONArray()
                        val keys = (0 until keyArray.length()).mapNotNull { keyIndex ->
                            val key = keyArray.optJSONObject(keyIndex) ?: return@mapNotNull null
                            Keyframe(key.optDouble("t").toFloat(), key.optDouble("v").toFloat(),
                                runCatching { KeyType.valueOf(key.optString("type", "LINEAR")) }.getOrDefault(KeyType.LINEAR),
                                key.optDouble("x1", 0.42).toFloat(), key.optDouble("y1", 0.0).toFloat(),
                                key.optDouble("x2", 0.58).toFloat(), key.optDouble("y2", 1.0).toFloat())
                        }.sortedBy { it.t }
                        if (keys.isNotEmpty()) parameterKeys[parameter] = keys
                    }
                    val effectCraftJson = item.optJSONObject("effectCraftParameters") ?: JSONObject()
                    val effectCraftParameters = buildMap {
                        val keys = effectCraftJson.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            put(key, effectCraftJson.optString(key, "null"))
                        }
                    }
                    EffectInstance(item.optString("id", UUID.randomUUID().toString()), name,
                        item.optBoolean("enabled", true), parameters, parameterKeys,
                        effectCraftId, effectCraftParameters)
                }
            },
            speed = j.optDouble("speed", 1.0).toFloat().coerceIn(0.1f, 4f),
            volume = j.optDouble("volume", 1.0).toFloat().coerceIn(0f, 2f),
            maskEnabled = j.optBoolean("maskEnabled", false), blendMode = j.optString("blendMode", "Normal"),
            shapeKind = j.optString("shapeKind", "Rectangle"),
            vectorPoints = (j.optJSONArray("vectorPoints") ?: JSONArray()).let { points -> (0 until points.length()).mapNotNull { index -> points.optJSONObject(index)?.let { point -> VectorPoint(point.optDouble("x", 0.5).toFloat(), point.optDouble("y", 0.5).toFloat()) } } },
            maskPath = maskPath)
    }
}
