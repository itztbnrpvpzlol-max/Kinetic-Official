package com.kinetic.app.plugins

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

data class PluginParameter(
    val id: String,
    val label: String,
    val type: String,
    val defaultValue: Float,
    val minimum: Float = 0f,
    val maximum: Float = 1f,
    val displayScale: Float = 1f,
    val decimals: Int = 2,
    val group: String? = null,
    val options: List<String> = emptyList(),
    val holdOnly: Boolean = false
)

data class PluginPass(
    val shader: String,
    val scale: Float = 1f,
    val previousPass: Int? = null,
    val floatTarget: Boolean = false,
    val requiredParameter: String? = null,
    val requiredMinimum: Float = 0f
)

data class PluginEffect(
    val packId: String,
    val id: String,
    val name: String,
    val category: String,
    val parameters: List<PluginParameter>,
    val passes: List<PluginPass>,
    val presets: List<Pair<String, Map<String, Float>>>,
    val newInstanceValues: Map<String, Float>,
    val thumbnailValues: Map<String, Float>
) {
    val projectKey: String get() = "plg:$packId/$id"
}

data class InstalledEffectPack(
    val id: String,
    val name: String,
    val version: String,
    val versionCode: Int,
    val author: String,
    val description: String,
    val effects: List<PluginEffect>,
    val directory: File
)

sealed class PluginInstallResult {
    data class Success(val pack: InstalledEffectPack, val updated: Boolean) : PluginInstallResult()
    data class Failure(val message: String) : PluginInstallResult()
}

/**
 * Kinetic's data-only effect pack loader. Packs contain JSON and bounded GLSL
 * source only; no dex, native libraries, scripts, network access or reflection.
 */
class EffectPluginManager(context: Context) {
    private val root = File(context.applicationContext.filesDir, "effect-plugins").apply { mkdirs() }

    fun installedPacks(): List<InstalledEffectPack> =
        root.listFiles { file -> file.isDirectory && File(file, "manifest.json").isFile }
            ?.mapNotNull { directory -> runCatching { readInstalled(directory) }.getOrNull() }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()

    fun install(uri: Uri): PluginInstallResult {
        return try {
            val bytes = contextBytes(uri) ?: return PluginInstallResult.Failure("Couldn't read the selected file.")
            if (bytes.size > MAX_PACK_BYTES) return PluginInstallResult.Failure("Pack exceeds the 2 MB compressed limit.")
            val entries = readArchive(bytes)
            val manifestBytes = entries["manifest.json"]
                ?: return PluginInstallResult.Failure("manifest.json must be at the root of the pack.")
            val manifest = JSONObject(manifestBytes.toString(Charsets.UTF_8))
            validateManifest(manifest)
            val packId = manifest.getString("id")
            val effectIds = manifest.getJSONArray("effects")
            if (effectIds.length() == 0) return PluginInstallResult.Failure("The pack doesn't contain any effects.")
            if (effectIds.length() > MAX_ENTRIES) return PluginInstallResult.Failure("Too many effects in this pack.")

            val parsedEffects = ArrayList<PluginEffect>()
            for (i in 0 until effectIds.length()) {
                val effectId = effectIds.getString(i)
                if (!EFFECT_ID.matches(effectId)) return PluginInstallResult.Failure("Invalid effect id: $effectId")
                val base = "effects/$effectId/"
                val jsonBytes = entries[base + "effect.json"]
                    ?: return PluginInstallResult.Failure("Missing $base" + "effect.json")
                val effectJson = JSONObject(jsonBytes.toString(Charsets.UTF_8))
                val parsed = parseEffect(packId, effectId, effectJson, entries, base)
                parsedEffects += parsed
            }

            val temp = File(root, ".$packId.installing").apply {
                deleteRecursively()
                mkdirs()
            }
            entries.forEach { (name, data) ->
                val target = File(temp, name)
                if (!target.canonicalPath.startsWith(temp.canonicalPath + File.separator)) {
                    temp.deleteRecursively()
                    return PluginInstallResult.Failure("Unsafe path in pack.")
                }
                target.parentFile?.mkdirs()
                target.writeBytes(data)
            }
            val manifestFile = File(temp, "manifest.json")
            if (!manifestFile.exists()) manifestFile.writeBytes(manifestBytes)
            val existing = File(root, packId)
            val old = existing.exists()
            val oldPack = if (old) runCatching { readInstalled(existing) }.getOrNull() else null
            val versionCode = manifest.getInt("versionCode")
            if (oldPack != null && versionCode <= oldPack.versionCode) {
                temp.deleteRecursively()
                return PluginInstallResult.Failure("This pack version is already installed. Increase versionCode to update it.")
            }
            val backup = File(root, ".$packId.previous")
            backup.deleteRecursively()
            if (existing.exists() && !existing.renameTo(backup)) {
                temp.deleteRecursively()
                return PluginInstallResult.Failure("Couldn't safely replace the installed pack.")
            }
            if (!temp.renameTo(existing)) {
                if (backup.exists()) backup.renameTo(existing)
                temp.deleteRecursively()
                return PluginInstallResult.Failure("Couldn't finish installing the pack.")
            }
            backup.deleteRecursively()
            val installed = readInstalled(existing).copy(effects = parsedEffects)
            PluginInstallResult.Success(installed, old)
        } catch (error: Exception) {
            PluginInstallResult.Failure(error.message ?: "The pack is invalid.")
        }
    }

    fun remove(packId: String): Boolean {
        if (!PACK_ID.matches(packId)) return false
        return File(root, packId).deleteRecursively()
    }

    fun shaderSource(effect: PluginEffect, pass: PluginPass): String? {
        if (!PACK_ID.matches(effect.packId) || !EFFECT_ID.matches(effect.id)) return null
        val file = File(root, "${effect.packId}/effects/${effect.id}/${pass.shader}")
        return runCatching {
            if (!file.canonicalPath.startsWith(root.canonicalPath + File.separator) ||
                !file.isFile || file.length() > MAX_SHADER_BYTES) null
            else file.readText(Charsets.UTF_8)
        }.getOrNull()
    }

    private fun contextBytes(uri: Uri): ByteArray? {
        // Context is held by the application-scoped manager's resolver closure.
        return resolver(uri)
    }

    private val resolver: (Uri) -> ByteArray? = { uri ->
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_PACK_BYTES) throw IllegalArgumentException("Pack exceeds the 2 MB compressed limit.")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private val appContext = context.applicationContext

    private fun readArchive(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        var expanded = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (entry.isDirectory) continue
                if (result.size >= MAX_ENTRIES) throw IllegalArgumentException("Pack contains more than 64 files.")
                if (!SAFE_PATH.matches(name) || name.split('/').any { it == ".." }) {
                    throw IllegalArgumentException("Unsafe file path: $name")
                }
                val extension = name.substringAfterLast('.', "")
                if (extension !in ALLOWED_EXTENSIONS) throw IllegalArgumentException("Unsupported file type: $name")
                if (result.containsKey(name)) throw IllegalArgumentException("Duplicate file: $name")
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    expanded += count
                    if (expanded > MAX_EXPANDED_BYTES) throw IllegalArgumentException("Pack exceeds the 8 MB extracted limit.")
                    if (extension == "frag" && output.size() + count > MAX_SHADER_BYTES) {
                        throw IllegalArgumentException("Shader exceeds the 32 KB limit: $name")
                    }
                    output.write(buffer, 0, count)
                }
                val data = output.toByteArray()
                if (name == "icon.png" && data.size > MAX_ICON_BYTES) throw IllegalArgumentException("icon.png is too large.")
                result[name] = data
            }
        }
        return result
    }

    private fun validateManifest(json: JSONObject) {
        if (json.getInt("format") != API_VERSION || json.getInt("api") != API_VERSION) {
            throw IllegalArgumentException("This pack uses an unsupported plugin format or API version.")
        }
        val id = json.getString("id")
        if (!PACK_ID.matches(id)) throw IllegalArgumentException("Invalid pack id.")
        listOf("name", "version", "author", "description", "license").forEach {
            if (json.optString(it).isBlank()) throw IllegalArgumentException("manifest.json is missing $it.")
        }
        if (json.getInt("versionCode") < 1) throw IllegalArgumentException("versionCode must be at least 1.")
        if (!json.has("effects") || json.getJSONArray("effects").length() == 0) {
            throw IllegalArgumentException("manifest.json must list at least one effect.")
        }
    }

    private fun parseEffect(
        packId: String,
        expectedId: String,
        json: JSONObject,
        files: Map<String, ByteArray>,
        base: String
    ): PluginEffect {
        val id = json.getString("id")
        if (id != expectedId) throw IllegalArgumentException("$base" + "effect.json id doesn't match its folder.")
        val name = json.getString("name").takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Effect name can't be empty.")
        val category = json.optString("category", "Other")
        val paramsJson = json.optJSONArray("params") ?: JSONArray()
        if (paramsJson.length() > MAX_PARAMETERS) throw IllegalArgumentException("$name has more than 40 parameters.")
        val ids = HashSet<String>()
        val params = (0 until paramsJson.length()).map { index ->
            val p = paramsJson.getJSONObject(index)
            val paramId = p.getString("id")
            if (!PARAM_ID.matches(paramId) || !ids.add(paramId)) throw IllegalArgumentException("$name has an invalid or duplicate parameter id.")
            val type = p.optString("type", "scalar")
            if (type !in setOf("scalar", "bool", "choice")) throw IllegalArgumentException("Unsupported parameter type: $type")
            val min = p.optDouble("min", 0.0).toFloat()
            val max = p.optDouble("max", 1.0).toFloat()
            val default = p.optDouble("default", 0.0).toFloat()
            if (!min.isFinite() || !max.isFinite() || !default.isFinite() || min > max || default !in min..max) {
                throw IllegalArgumentException("Invalid range/default for $paramId.")
            }
            val optionsJson = p.optJSONArray("options") ?: JSONArray()
            val options = (0 until optionsJson.length()).map { optionsJson.getString(it) }
            if (type == "bool" && default != 0f && default != 1f) throw IllegalArgumentException("Bool default must be 0 or 1.")
            if (type == "choice" && options.size < 2) throw IllegalArgumentException("Choice $paramId needs at least two options.")
            if (type == "choice" && (default % 1f != 0f || default.toInt() !in options.indices)) {
                throw IllegalArgumentException("Choice default for $paramId is invalid.")
            }
            PluginParameter(
                paramId, p.optString("label", paramId), type, default, min, max,
                p.optDouble("displayScale", 1.0).toFloat().takeIf { it.isFinite() && it > 0f } ?: 1f,
                p.optInt("decimals", 2).coerceIn(0, 5), p.optString("group").takeIf { it.isNotBlank() },
                options, p.optBoolean("holdOnly", false)
            )
        }
        val passesJson = json.optJSONArray("passes") ?: JSONArray()
        if (passesJson.length() !in 1..MAX_PASSES) throw IllegalArgumentException("$name must define between 1 and 8 shader passes.")
        val passes = (0 until passesJson.length()).map { index ->
            val pass = passesJson.getJSONObject(index)
            val shader = pass.getString("shader")
            if (!SAFE_SHADER_NAME.matches(shader) || !files.containsKey(base + shader)) {
                throw IllegalArgumentException("Missing or unsafe shader in $name: $shader")
            }
            val source = files.getValue(base + shader).toString(Charsets.UTF_8)
            validateShader(source, name, shader, ids)
            val scale = pass.optDouble("scale", 1.0).toFloat()
            if (scale !in setOf(1f, 0.5f, 0.25f, 0.125f, 0.0625f, 0.03125f, 0.015625f, 0.0078125f, 0.00390625f)) {
                throw IllegalArgumentException("Invalid pass scale in $shader.")
            }
            val previous = if (pass.has("prevPass")) pass.getInt("prevPass") else null
            if (previous != null && previous !in 0 until index) throw IllegalArgumentException("prevPass must refer to an earlier pass.")
            val required = pass.optString("requiredParam").takeIf { it.isNotBlank() }
            if (required != null && required !in ids) throw IllegalArgumentException("Unknown requiredParam $required.")
            PluginPass(shader, scale, previous, pass.optBoolean("floatTarget", false), required,
                pass.optDouble("requiredMinimum", 0.0).toFloat())
        }
        val presets = parsePresets(json.optJSONArray("presets"), params)
        val newValues = readValues(json.optJSONObject("newInstanceValues"), params)
        val thumbValues = readValues(json.optJSONObject("thumbnailValues"), params)
        return PluginEffect(packId, id, name, category, params, passes, presets, newValues, thumbValues)
    }

    private fun parsePresets(array: JSONArray?, params: List<PluginParameter>): List<Pair<String, Map<String, Float>>> {
        if (array == null) return emptyList()
        val known = params.associateBy { it.id }
        return (0 until array.length()).map { i ->
            val preset = array.getJSONObject(i)
            val values = readValues(preset.optJSONObject("values"), params)
            preset.optString("label", preset.optString("id", "Preset")) to values.filter { (key, value) ->
                val p = known[key]
                p != null && value in p.minimum..p.maximum
            }
        }
    }

    private fun readValues(json: JSONObject?, params: List<PluginParameter>): Map<String, Float> {
        if (json == null) return emptyMap()
        val values = LinkedHashMap<String, Float>()
        val keys = json.keys()
        val known = params.associateBy { it.id }
        while (keys.hasNext()) {
            val key = keys.next()
            val p = known[key] ?: throw IllegalArgumentException("Unknown parameter value: $key")
            val value = json.getDouble(key).toFloat()
            if (!value.isFinite() || value !in p.minimum..p.maximum) throw IllegalArgumentException("Value for $key is outside its range.")
            values[key] = value
        }
        return values
    }

    private fun validateShader(source: String, effect: String, file: String, parameterIds: Set<String>) {
        if (source.isBlank() || source.contains("#version") || source.contains("#extension")) {
            throw IllegalArgumentException("$effect/$file uses a forbidden shader directive.")
        }
        if (Regex("\\b(while|do)\\b").containsMatchIn(source)) {
            throw IllegalArgumentException("$effect/$file uses an unsupported loop.")
        }
        val loops = Regex("for\\s*\\(\\s*int\\s+\\w+\\s*=\\s*0\\s*;\\s*\\w+\\s*<\\s*(\\d+)\\s*;[^)]*\\)").findAll(source)
        if (Regex("\\bfor\\s*\\(").findAll(source).count() != loops.count()) {
            throw IllegalArgumentException("$effect/$file has a loop without a constant integer bound.")
        }
        if (loops.any { (it.groupValues[1].toIntOrNull() ?: 257) > 256 }) {
            throw IllegalArgumentException("$effect/$file exceeds the 256-iteration loop limit.")
        }
        val uniforms = Regex("uniform\\s+float\\s+u([A-Z][A-Za-z0-9_]*)\\s*;").findAll(source)
        val declared = parameterIds.map { it.replaceFirstChar { c -> c.uppercase() } }.toSet()
        uniforms.forEach { match ->
            val id = match.groupValues[1].replaceFirstChar { it.lowercase() }
            if (id !in declared) throw IllegalArgumentException("$effect/$file declares an unknown parameter uniform u${match.groupValues[1]}.")
        }
    }

    private fun readInstalled(directory: File): InstalledEffectPack {
        val manifest = JSONObject(File(directory, "manifest.json").readText())
        val id = manifest.getString("id")
        if (!PACK_ID.matches(id) || directory.name != id) throw IllegalArgumentException("Invalid installed pack directory.")
        val effects = manifest.getJSONArray("effects")
        val parsed = (0 until effects.length()).map { effectIndex ->
            val effectId = effects.getString(effectIndex)
            val effectFile = File(directory, "effects/$effectId/effect.json")
            val json = JSONObject(effectFile.readText())
            val files = HashMap<String, ByteArray>()
            val effectDir = effectFile.parentFile!!
            effectDir.listFiles()?.filter { it.isFile && it.extension == "frag" }?.forEach { files["effects/$effectId/${it.name}"] = it.readBytes() }
            parseEffect(id, effectId, json, files, "effects/$effectId/")
        }
        return InstalledEffectPack(id, manifest.getString("name"), manifest.getString("version"),
            manifest.getInt("versionCode"), manifest.optString("author"), manifest.optString("description"),
            parsed, directory)
    }

    companion object {
        private const val API_VERSION = 1
        private const val MAX_PACK_BYTES = 2 * 1024 * 1024
        private const val MAX_EXPANDED_BYTES = 8 * 1024 * 1024
        private const val MAX_SHADER_BYTES = 32 * 1024
        private const val MAX_ICON_BYTES = 256 * 1024
        private const val MAX_ENTRIES = 64
        private const val MAX_PARAMETERS = 40
        private const val MAX_PASSES = 8
        private val PACK_ID = Regex("[a-z0-9._-]{2,80}")
        private val EFFECT_ID = Regex("[a-z0-9_-]{1,32}")
        private val PARAM_ID = Regex("[A-Za-z][A-Za-z0-9]{0,31}")
        private val SAFE_PATH = Regex("[A-Za-z0-9._/-]{1,180}")
        private val SAFE_SHADER_NAME = Regex("[A-Za-z0-9_-]{1,48}\\.frag")
        private val ALLOWED_EXTENSIONS = setOf("json", "frag", "png", "txt")
    }
}
