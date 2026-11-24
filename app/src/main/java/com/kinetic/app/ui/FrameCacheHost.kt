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
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Starts the moment the editor opens: renders every frame of the root composition in the background
 * (nearest the playhead first) into the RAM frame cache. When a layer changes, only the frames that layer
 * appears in become invalid; the filler re-renders just those, after the edit settles.
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
            cache.budgetBytes = minOf(192L * 1024L * 1024L, maxMem / 5L).coerceAtLeast(48L * 1024L * 1024L)
            val w = (if (com.kinetic.app.data.MediaWorkers.isConstrainedDevice) 480 else 640)
                .coerceAtMost(COMP_W.toInt().coerceAtLeast(2)).let { it - it % 2 }
            val h = (w * COMP_H / COMP_W).toInt().coerceAtLeast(2).let { it - it % 2 }
            var renderer: ExportFrameRenderer? = null
            val failed = HashSet<Int>()
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            try {
                var lastRefresh = System.currentTimeMillis()
                val out = ByteArrayOutputStream(64 * 1024)
                while (isActive) {
                    val head = (editor.time * fps).toInt().coerceIn(0, total - 1)
                    cache.playheadFrame = head
                    if (editor.playing) cache.prefetchDecode(head + 1, 8, total)
                    var next = -1
                    for (i in 0 until total) {
                        val f = (head + i) % total
                        if (f !in failed && !cache.hasValid(f)) { next = f; break }
                    }
                    val now = System.currentTimeMillis()
                    if (next < 0 || (cache.isFull() && abs(next - head) >= cache.farthestDistance())) {
                        if (now - lastRefresh > 400) { cache.refreshRanges(total); lastRefresh = now }
                        delay(if (editor.playing) 60L else 250L)
                        continue
                    }
                    try {
                        val r = renderer ?: ExportFrameRenderer(context, layers, comp, w, h, fps).also { renderer = it }
                        val bmp: Bitmap = r.render(next / fps.toFloat())
                        out.reset()
                        bmp.compress(Bitmap.CompressFormat.JPEG, 84, out)
                        cache.put(next, sigs.sigAt(next), out.toByteArray())
                    } catch (e: Throwable) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        failed += next
                        runCatching { renderer?.close() }
                        renderer = null
                    }
                    if (now - lastRefresh > 400) { cache.refreshRanges(total); lastRefresh = now }
                    // Stay out of the way of live playback and scrubbing: yield a little between frames.
                    delay(if (editor.playing) 4L else 1L)
                }
            } finally {
                runCatching { renderer?.close() }
                Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
            }
        }
    }
}
