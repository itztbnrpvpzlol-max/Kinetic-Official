package com.kinetic.app.ui

import android.graphics.Bitmap
import android.os.Process
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
import java.io.ByteArrayOutputStream
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
            cache.setSignatures(sigs)
            // Immediately drop the ranges this edit invalidated (the green bar updates at once).
            cache.refreshRanges(total)
            // Let a drag / typing settle before spending CPU re-rendering.
            delay(400)
            val maxMem = Runtime.getRuntime().maxMemory()
            val constrained = com.kinetic.app.data.MediaWorkers.isConstrainedDevice
            cache.budgetBytes = if (constrained) {
                (maxMem / 16L).coerceIn(8L * 1024L * 1024L, 24L * 1024L * 1024L)
            } else {
                (maxMem / 8L).coerceIn(16L * 1024L * 1024L, 96L * 1024L * 1024L)
            }
            val w = (if (constrained) 320 else 480)
                .coerceAtMost(COMP_W.toInt().coerceAtLeast(2)).let { it - it % 2 }
            val h = (w * COMP_H / COMP_W).toInt().coerceAtLeast(2).let { it - it % 2 }
            var renderer: ExportFrameRenderer? = null
            val failed = HashSet<Int>()
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
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
                    cache.prefetchDecode(head, 6, total)
                    if (editor.playing) {
                        // Never run the expensive composition renderer during live playback.
                        delay(100L)
                        continue
                    } else if (tick - lastMoveAt < 300L) {
                        // Scrubbing / seeking: do not compete with the preview while the playhead is moving.
                        delay(80L); continue
                    }
                    // Sweep the full composition, starting at the playhead and wrapping
                    // around. The compressed-frame budget decides how much can remain
                    // resident; this avoids permanently limiting the cache to ~2 seconds.
                    var next = -1
                    for (offset in 0 until total) {
                        val frame = (head + offset) % total
                        if (frame !in failed && !cache.hasValid(frame)) {
                            next = frame
                            break
                        }
                    }
                    val now = System.currentTimeMillis()
                    if (next < 0) {
                        if (now - lastRefresh > 1500) { cache.refreshRanges(total); lastRefresh = now }
                        delay(250L)
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
                    // Stay out of the way of live playback and scrubbing: yield a little between frames.
                    delay(40L)
                }
            } finally {
                runCatching { renderer?.close() }
                Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
            }
        }
    }
}
