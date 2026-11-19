package com.kinetic.app.engine

/**
 * JNI surface for the AI's isolated EffectCraft project session.
 *
 * This deliberately exposes EffectCraft commands and native composition renders,
 * not Kinetic Layer objects, editor controls, or the interactive editor session.
 */
internal object EffectCraftAiNative {
    private val loadResult = runCatching {
        System.loadLibrary("kinetic_effectcraft")
        true
    }

    val available: Boolean = loadResult.getOrDefault(false)
    val loadError: String?
        get() = loadResult.exceptionOrNull()?.let {
            it.javaClass.simpleName + ": " + (it.message ?: "no detail")
        }

    @JvmStatic external fun nativeInitialize(): Boolean
    @JvmStatic external fun nativeReset(): Boolean
    @JvmStatic external fun nativeDiscover(): String
    @JvmStatic external fun nativeExecute(commandId: String, paramsJson: String): String
    @JvmStatic external fun nativeExecuteBatch(commandsJson: String): String
    @JvmStatic external fun nativeRenderFrame(compId: Long, timeSeconds: Float, maxSide: Int): ByteArray?
}

/**
 * Full-capability AI bridge. The command/effect/preset catalogue is discovered
 * from the EffectCraft build at runtime, so the planner does not maintain a
 * manually curated shortlist that silently becomes stale as the engine grows.
 */
internal object EffectCraftAiBridge {
    @Synchronized
    fun initialize(): Result<Unit> = runCatching {
        check(EffectCraftAiNative.available) {
            "EffectCraft AI native library is unavailable: ${EffectCraftAiNative.loadError ?: "unknown linker error"}"
        }
        check(EffectCraftAiNative.nativeInitialize()) {
            "EffectCraft AI session could not be initialized."
        }
    }

    @Synchronized
    fun reset(): Result<Unit> = runCatching {
        initialize().getOrThrow()
        check(EffectCraftAiNative.nativeReset()) {
            "EffectCraft AI session could not be reset."
        }
    }

    @Synchronized
    fun discover(): Result<String> = runCatching {
        initialize().getOrThrow()
        val response = EffectCraftAiNative.nativeDiscover()
        check(response.contains("\"ok\":true")) {
            "EffectCraft capability discovery failed: ${response.take(500)}"
        }
        response
    }

    @Synchronized
    fun execute(commandId: String, paramsJson: String = "{}"): Result<String> = runCatching {
        initialize().getOrThrow()
        require(commandId.isNotBlank()) { "EffectCraft command ID cannot be empty." }
        val response = EffectCraftAiNative.nativeExecute(commandId, paramsJson)
        check(response.contains("\"ok\":true")) {
            "EffectCraft command '$commandId' failed: ${response.take(1000)}"
        }
        response
    }

    @Synchronized
    fun executeBatch(commandsJson: String): Result<String> = runCatching {
        initialize().getOrThrow()
        val response = EffectCraftAiNative.nativeExecuteBatch(commandsJson)
        check(response.contains("\"ok\":true")) {
            "EffectCraft command batch failed: ${response.take(1000)}"
        }
        response
    }

    @Synchronized
    fun renderFrame(compId: Long, timeSeconds: Float, maxSide: Int = 1280): Result<ByteArray> = runCatching {
        initialize().getOrThrow()
        require(compId >= 0L && timeSeconds.isFinite() && maxSide > 0) {
            "Invalid EffectCraft composition render request."
        }
        EffectCraftAiNative.nativeRenderFrame(compId, timeSeconds, maxSide)
            ?: throw IllegalStateException("EffectCraft could not render the requested AI composition frame.")
    }
}
