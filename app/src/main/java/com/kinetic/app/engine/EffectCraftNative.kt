package com.kinetic.app.engine

/**
 * Optional JNI entry point for the EffectCraft session.
 *
 * Kinetic's existing renderer remains the fallback until the Rust library is built,
 * packaged, and validated on the target Android ABI.
 */
internal object EffectCraftNative {
    val available: Boolean = runCatching {
        System.loadLibrary("kinetic_effectcraft")
        true
    }.getOrDefault(false)

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
}
