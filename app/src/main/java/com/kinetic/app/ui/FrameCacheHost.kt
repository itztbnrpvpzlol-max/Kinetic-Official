package com.kinetic.app.ui

import android.graphics.Bitmap
import android.os.StatFs
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.kinetic.app.export.ExportFrameRenderer
import com.kinetic.app.model.COMP_H
import com.kinetic.app.model.COMP_W
import com.kinetic.app.model.EditorState
import com.kinetic.app.model.FrameSignatures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.ceil

/**
 * Maintains a small playhead-local RAM preview cache.
 * Expensive composition rendering is paused during playback and while the user is scrubbing.
 */
@Composable
fun FrameCacheHost(editor: EditorState) {
    val context = LocalContext.current.applicationContext
    val cache = editor.frameCache
    val atRoot = editor.timelinePath.isEmpty()
    val layers = editor.projectPreviewLayers
    val comp = editor.compSettings
    val duration = editor.duration
    val key = layers.hashCode() * 31 + comp.hashCode()

    LaunchedEffect(key, atRoot, cache.enabled, duration) {
        if (!atRoot || !cache.enabled) { cache.setSignatures(null); return@LaunchedEffect }
        withContext(Dispatchers.Default) {
            val fps = comp.fps.coerceIn(1, 60)
            val total = ceil(duration * fps).toInt().coerceAtLeast(1)
            val sigs = FrameSignatures(layers, comp)
            val constrained = com.kinetic.app.data.MediaWorkers.isConstrainedDevice
            val diskDir = File(context.cacheDir, "kinetic-prerender/${editor.projectId}")
            diskDir.mkdirs()
            val availableDisk = runCatching { StatFs(diskDir.absolutePath).availableBytes }.getOrDefault(256L * 1024L * 1024L)
            val diskCap = if (constrained) 768L * 1024L * 1024L else 2L * 1024L * 1024L * 1024L
            val diskBudget = minOf(diskCap, (availableDisk / 3L).coerceAtLeast(64L * 1024L * 1024L))
            cache.configureDisk(diskDir, diskBudget)
            cache.setSignatures(sigs)
            // Revalidate existing on-disk frames against the current composition immediately.
            cache.refreshRanges(total)
            // Let a drag / typing settle before spending CPU re-rendering.
            delay(250)
            val w = (if (constrained) 320 else 480)
                .coerceAtMost(COMP_W.toInt().coerceAtLeast(2)).let { it - it % 2 }
            val h = (w * COMP_H / COMP_W).toInt().coerceAtLeast(2).let { it - it % 2 }
            var renderer: ExportFrameRenderer? = null
            val failed = HashSet<Int>()
            // Cache generation runs only while paused; default priority avoids needlessly
            // throttling every frame behind background work.
            try {
                var lastRefresh = System.currentTimeMillis()
                var lastHead = -1
                var lastMoveAt = 0L
                val out = ByteArrayOutputStream(64 * 1024)
                while (isActive) {
                    val head = (editor.time * fps).toInt().coerceIn(0, total - 1)
                    cache.playheadFrame = head
                    val tick = System.currentTimeMillis()
                    if (head != lastHead) { lastHead = head; lastMoveAt = tick }
                    // JPEG decompression is done here, off the UI thread, in a tiny window.
                    cache.prefetchDecode(head, 16, total)
                    if (editor.playing) {
                        // Never run the expensive composition renderer during live playback.
                        delay(33L)
                        continue
                    } else if (tick - lastMoveAt < 300L) {
                        // Scrubbing / seeking: do not compete with the preview while the playhead is moving.
                        delay(80L); continue
                    }
                    // Prerender from the beginning of the composition. This produces a
                    // genuine left-to-right progress range instead of a small playhead window.
                    var next = -1
                    for (frame in 0 until total) {
                        if (frame !in failed && !cache.hasValid(frame)) {
                            next = frame
                            break
                        }
                    }
                    val now = System.currentTimeMillis()
                    if (next < 0 || cache.isFull()) {
                        if (now - lastRefresh > 750) { cache.refreshRanges(total); lastRefresh = now }
                        delay(180L)
                        continue
                    }
                    try {
                        val r = renderer ?: ExportFrameRenderer(context, layers, comp, w, h, fps).also { renderer = it }
                        val bmp: Bitmap = r.render(next / fps.toFloat())
                        out.reset()
                        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
                        cache.put(next, sigs.sigAt(next), out.toByteArray())
                    } catch (e: Throwable) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        failed += next
                        runCatching { renderer?.close() }
                        renderer = null
                    }
                    if (now - lastRefresh > 1500) { cache.refreshRanges(total); lastRefresh = now }
                    // Do not impose an artificial 25 FPS ceiling on cache generation.
                    // Yield cooperatively so UI / playback coroutines still get scheduled.
                    yield()
                }
            } finally {
                runCatching { renderer?.close() }
                cache.refreshRanges(total)
            }
        }
    }
}
