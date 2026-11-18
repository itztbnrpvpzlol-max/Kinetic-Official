package com.kinetic.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.StatFs
import com.kinetic.app.model.COMP_H
import com.kinetic.app.model.COMP_W
import com.kinetic.app.model.EditorState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil

/**
 * Disk-backed cache for fully composited timeline frames.
 *
 * The renderer is deliberately injected: this class owns cache identity,
 * invalidation, persistence and scheduling, while the preview renderer owns
 * the actual frame composition. A frame passed to [renderTimeline] remains
 * owned by the caller and is never recycled here.
 */
class TimelinePreRenderCache(context: Context, projectId: String) {
    data class Session internal constructor(
        val projectId: String,
        val fingerprint: String,
        val fps: Int,
        val frameCount: Int,
        val width: Int,
        val height: Int,
        internal val generation: Long
    )

    data class Progress(
        val completedFrames: Int,
        val totalFrames: Int,
        val cachedBytes: Long
    ) {
        val fraction: Float
            get() = if (totalFrames <= 0) 1f else completedFrames.toFloat() / totalFrames
    }

    data class RenderResult(
        val completed: Boolean,
        val completedFrames: Int,
        val totalFrames: Int,
        val cachedBytes: Long,
        val stoppedBecauseOfStorage: Boolean = false
    )

    private val root = File(context.applicationContext.cacheDir, "kinetic-pre-render")
        .also { it.mkdirs() }
    private val projectRoot = File(root, safeName(projectId)).also { it.mkdirs() }
    private val lock = Any()
    private val generation = AtomicLong(0L)
    @Volatile private var activeFingerprint: String? = null
    @Volatile private var activeDirectory: File? = null

    private val maxCacheBytes: Long = if (MediaWorkers.isConstrainedDevice) {
        384L * 1024L * 1024L
    } else {
        2L * 1024L * 1024L * 1024L
    }

    /**
     * Starts or resumes a cache session. A changed timeline fingerprint
     * invalidates the previous session before any new frames are written.
     */
    fun beginSession(editor: EditorState, width: Int, height: Int): Session {
        val fps = editor.fps.coerceIn(1, 120)
        val frameCount = ceil(editor.duration.coerceAtLeast(0.1f) * fps).toInt().coerceAtLeast(1)
        val fingerprint = editor.timelineRenderFingerprint(width, height)
        synchronized(lock) {
            if (activeFingerprint != fingerprint || activeDirectory?.exists() != true) {
                generation.incrementAndGet()
                projectRoot.listFiles()?.forEach { it.deleteRecursively() }
                val directory = File(projectRoot, fingerprint).also { it.mkdirs() }
                File(directory, "session.meta").writeText(
                    "fps=$fps\nframes=$frameCount\nwidth=$width\nheight=$height\n"
                )
                activeFingerprint = fingerprint
                activeDirectory = directory
            }
            return Session(
                editor.projectId, fingerprint, fps, frameCount,
                width.coerceAtLeast(1), height.coerceAtLeast(1), generation.get()
            )
        }
    }

    /** True only while this session still represents the active timeline. */
    fun isCurrent(session: Session): Boolean =
        generation.get() == session.generation &&
            activeFingerprint == session.fingerprint &&
            activeDirectory?.exists() == true

    /** Returns a decoded cached frame, or null when it has not been rendered. */
    suspend fun readFrame(session: Session, frameIndex: Int): Bitmap? = withContext(Dispatchers.IO) {
        if (!isCurrent(session) || frameIndex !in 0 until session.frameCount) return@withContext null
        val file = frameFile(session, frameIndex)
        if (!file.isFile || file.length() <= 0L) return@withContext null
        BitmapFactory.decodeFile(file.absolutePath)
    }

    /**
     * Render every missing frame in timeline order. Rendering is sequential by
     * design for predictable memory use on 32-bit devices. The supplied
     * callback must return the fully composited frame at the requested time.
     */
    suspend fun renderTimeline(
        session: Session,
        renderFrame: suspend (timeSeconds: Float, frameIndex: Int) -> Bitmap?,
        onProgress: (Progress) -> Unit = {}
    ): RenderResult {
        if (!isCurrent(session)) {
            return RenderResult(false, 0, session.frameCount, 0L)
        }
        var completed = 0
        var bytes = directoryBytes(session)
        for (index in 0 until session.frameCount) {
            coroutineContext.ensureActive()
            if (!isCurrent(session)) {
                return RenderResult(false, completed, session.frameCount, bytes)
            }
            val destination = frameFile(session, index)
            if (destination.isFile && destination.length() > 0L) {
                completed++
                onProgress(Progress(completed, session.frameCount, bytes))
                continue
            }

            val bitmap = renderFrame(index.toFloat() / session.fps, index)
            if (bitmap == null) {
                return RenderResult(false, completed, session.frameCount, bytes)
            }
            try {
                val written = withContext(Dispatchers.IO) {
                    if (!isCurrent(session)) return@withContext 0L
                    val temporary = File(destination.parentFile, "${destination.name}.tmp")
                    val ok = runCatching {
                        FileOutputStream(temporary).use { output ->
                            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) {
                                throw IllegalStateException("Frame compression failed")
                            }
                            output.flush()
                            output.fd.sync()
                        }
                        if (!temporary.renameTo(destination)) {
                            temporary.copyTo(destination, overwrite = true)
                            temporary.delete()
                        }
                        destination.length()
                    }.getOrElse {
                        temporary.delete()
                        0L
                    }
                    ok
                }
                if (written <= 0L) {
                    return RenderResult(false, completed, session.frameCount, bytes)
                }
                bytes += written
            } finally {
                // Ownership stays with the callback; it may reuse or recycle the bitmap.
            }
            completed++
            onProgress(Progress(completed, session.frameCount, bytes))
            if (bytes > maxCacheBytes) {
                return RenderResult(false, completed, session.frameCount, bytes, true)
            }
            yield()
        }
        return RenderResult(isCurrent(session) && completed == session.frameCount,
            completed, session.frameCount, bytes)
    }

    /** Clears all preview frames for this project and invalidates running work. */
    fun invalidate() {
        synchronized(lock) {
            generation.incrementAndGet()
            activeFingerprint = null
            activeDirectory = null
            projectRoot.listFiles()?.forEach { it.deleteRecursively() }
        }
    }

    fun cachedFrameCount(session: Session): Int {
        if (!isCurrent(session)) return 0
        return (0 until session.frameCount).count { frameFile(session, it).isFile }
    }

    fun availableDiskBytes(): Long = runCatching {
        StatFs(root.absolutePath).availableBytes
    }.getOrDefault(0L)

    private fun frameFile(session: Session, index: Int): File =
        File(activeDirectory ?: File(projectRoot, session.fingerprint), "frame_${index.toString().padStart(8, '0')}.jpg")

    private fun directoryBytes(session: Session): Long =
        activeDirectory?.takeIf { isCurrent(session) }?.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".jpg") }
            ?.sumOf { it.length() } ?: 0L

    companion object {
        private fun safeName(value: String): String =
            value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96).ifBlank { "project" }
    }
}
