package com.kinetic.app.engine

/**
 * Optional JNI entry point for the EffectCraft session.
 *
 * Kinetic's existing renderer remains the fallback until the Rust library is built,
 * packaged, and validated on the target Android ABI.
 */
internal object EffectCraftNative {
    private val loadResult = runCatching {
        System.loadLibrary("kinetic_effectcraft")
        true
    }

    val available: Boolean = loadResult.getOrDefault(false)
    val loadError: String?
        get() = loadResult.exceptionOrNull()?.let { it.javaClass.simpleName + ": " + (it.message ?: "no detail") }

    @JvmStatic
    external fun nativeInitialize(): Boolean

    /**
     * Executes one EffectCraft command.
     *
     * Both input parameters and the response use the versioned JSON bridge protocol.
     * Call from a worker thread; engine commands are synchronous.
     */
    @JvmStatic
    external fun nativeExecute(commandId: String, paramsJson: String): String

    /** Executes ordered commands against the same persistent EffectCraft session. */
    @JvmStatic
    external fun nativeExecuteBatch(commandsJson: String): String

    /** Renders a decoded Kinetic clip frame through EffectCraft's compositor and effect registry. */
    @JvmStatic
    external fun nativeRenderFrame(
        cacheKey: String,
        frameRgba: ByteArray,
        width: Int,
        height: Int,
        effectsJson: String,
        timeSeconds: Float,
        maxSide: Int
    ): ByteArray?
    
    /** Decodes a local video frame through EffectCraft MediaPool's decoder and bounded LRU. */
    @JvmStatic
    external fun nativeDecodeVideoFrame(path: String, timeSeconds: Float, maxSide: Int): ByteArray?
    
    /** Drops decoded EffectCraft frames while keeping the native bridge reusable. */
    @JvmStatic
    external fun nativeClearMediaFrames()
}


