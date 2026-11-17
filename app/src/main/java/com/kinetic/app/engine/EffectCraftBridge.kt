package com.kinetic.app.engine

/**
 * Versioned Kotlin-facing boundary for EffectCraft.
 *
 * This deliberately does not replace NativeRenderEngine. It gives Kinetic a stable
 * command/event API while the renderer and media pipeline are migrated incrementally.
 */
object EffectCraftBridge {
    const val PROTOCOL_VERSION = 1

    val available: Boolean
        get() = EffectCraftNative.available

    fun initialize(): Boolean {
        return EffectCraftNative.available && EffectCraftNative.nativeInitialize()
    }

    /**
     * Executes a command using a JSON object for params and returns the engine's JSON response.
     * The caller should invoke this from a background dispatcher/thread.
     */
    fun execute(commandId: String, paramsJson: String = "{}"): String {
        if (!EffectCraftNative.available) {
            return """{"protocolVersion":1,"ok":false,"error":"EffectCraft native library is not packaged"}"""
        }
        return runCatching {
            EffectCraftNative.nativeExecute(commandId, paramsJson)
        }.getOrElse {
            """{"protocolVersion":1,"ok":false,"error":"Native bridge call failed"}"""
        }
    }
}
