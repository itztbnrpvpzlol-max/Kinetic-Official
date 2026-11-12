package com.kinetic.app.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.DashPathEffect
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import com.kinetic.app.data.MediaLoader
import com.kinetic.app.data.MediaWorkers
import com.kinetic.app.data.PreviewDiagnostics
import com.kinetic.app.data.TimelineRamCache
import com.kinetic.app.model.COMP_H
import com.kinetic.app.model.COMP_W
import com.kinetic.app.model.EditorState
import com.kinetic.app.model.LayerType
import com.kinetic.app.model.PropId
import com.kinetic.app.model.layerMatrixInto
import android.graphics.Path as AndroidPath

private class CpuPreviewClock {
    @Volatile var timeSec: Float = 0f
    @Volatile var playing: Boolean = false
}

@Composable
fun CpuPreviewCanvas(editor: EditorState, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val bitmaps = remember { mutableStateMapOf<String, Bitmap>() }
    val previewClock = remember(editor) { CpuPreviewClock() }
    val mediaLayers = editor.layers.filter {
        it.uri != null && (it.type == LayerType.IMAGE || it.type == LayerType.VIDEO)
    }
    val mediaLayerSignature = mediaLayers.map {
        "${it.id}|${it.uri}|${it.type}|${it.start}|${it.duration}|${it.srcOffset}|${it.speed}|${it.visible}"
    }

    // Observe the master clock outside composition. Updating this volatile
    // holder does not invalidate the editor UI on every playback tick.
    LaunchedEffect(editor, previewClock) {
        snapshotFlow { editor.time to editor.playing }.collect { (time, playing) ->
            previewClock.timeSec = time
            previewClock.playing = playing
        }
    }

    // Keep a small RAM-only frame window centered on the paused playhead.
    // Wait briefly for scrubbing to settle, then cancel obsolete cache work if
    // the user moves again. The center sample is always decoded first.
    LaunchedEffect(mediaLayerSignature, editor.playing) {
        if (editor.playing) return@LaunchedEffect
        snapshotFlow { editor.time }.collectLatest { centerTimelineTime ->
            delay(60L)
            val activeVideoLayers = mediaLayers.filter {
                it.type == LayerType.VIDEO && it.visible && it.activeAt(centerTimelineTime)
            }
            TimelineRamCache.retain(mediaLayers.map { it.id }.toSet())

            for (layer in activeVideoLayers) {
                val rawUri = layer.uri ?: continue
                val uri = Uri.parse(rawUri)
                val sourceTime = (
                    (centerTimelineTime - layer.start).coerceAtLeast(0f) *
                        layer.speed + layer.srcOffset
                    ).coerceAtLeast(0f)
                val fps = MediaLoader.CPU_PREVIEW_SAMPLE_FPS
                val edge = if (MediaWorkers.isConstrainedDevice) 320 else 640
                val frameBudget = MediaLoader
                    .recommendedPrefetchFrameCount(activeVideoLayers.size, edge)
                    .coerceIn(1, 5)
                // On this device, sequential forward decode is much cheaper
                // than seeking backward and rebuilding the codec session.
                val beforeFrames = 0
                val afterFrames = (frameBudget - 1).coerceAtMost(4)
                val minimumTime = layer.srcOffset.coerceAtLeast(0f)
                val maximumTime = if (layer.srcLength.isFinite()) {
                    (layer.srcLength - 0.05f).coerceAtLeast(minimumTime)
                } else {
                    Float.POSITIVE_INFINITY
                }
                val windowStart = sourceTime.coerceAtLeast(minimumTime)
                val windowEnd = (sourceTime + afterFrames / fps.toFloat())
                    .coerceAtMost(maximumTime)
                val signature = rawUri + "|" + layer.srcOffset + "|" + layer.srcLength + "|" + fps + "|" + edge

                val readyTimes = MediaLoader.prefetchVideoFrameWindow(
                    ctx = ctx,
                    uri = uri,
                    centerTimeSec = sourceTime,
                    beforeFrames = beforeFrames,
                    afterFrames = afterFrames,
                    sampleFps = fps,
                    maxEdge = edge,
                    minimumTimeSec = minimumTime,
                    maximumTimeSec = maximumTime
                )
                TimelineRamCache.setWindow(
                    layerId = layer.id,
                    signature = signature,
                    startSec = windowStart,
                    targetEndSec = windowEnd,
                    readyTimesSec = readyTimes,
                    totalFrames = beforeFrames + afterFrames + 1
                )
            }

            val activeIds = activeVideoLayers.map { it.id }.toSet()
            mediaLayers.filter { it.type == LayerType.VIDEO && it.id !in activeIds }
                .forEach { TimelineRamCache.clearWindow(it.id) }
        }
    }

    // One latest-only decode loop serves all media layers. A single worker is
    // the safe limit on this device class; per-layer polling coroutines used
    // to queue obsolete frame requests behind blocking native decodes.
    LaunchedEffect(mediaLayerSignature) {
        val validIds = mediaLayers.map { it.id }.toSet()
        bitmaps.keys.toList().filterNot { it in validIds }.forEach { bitmaps.remove(it) }
        val lastRequests = mutableMapOf<String, Pair<Int, Int>>()
        val retryAfterNs = mutableMapOf<String, Long>()

        while (true) {
            val timelineTime = previewClock.timeSec
            val playing = previewClock.playing
            var hadActiveMedia = false

            for (layer in mediaLayers) {
                val rawUri = layer.uri ?: continue
                if (!layer.visible || !layer.activeAt(timelineTime)) {
                    lastRequests.remove(layer.id)
                    bitmaps.remove(layer.id)
                    continue
                }
                hadActiveMedia = true

                val sourceTime = if (layer.type == LayerType.VIDEO) {
                    ((timelineTime - layer.start).coerceAtLeast(0f) *
                        layer.speed + layer.srcOffset).coerceAtLeast(0f)
                } else 0f
                val bucket = if (layer.type == LayerType.VIDEO) {
                    (sourceTime * MediaLoader.CPU_PREVIEW_SAMPLE_FPS.toFloat()).toInt()
                } else 0
                val frameEdge = if (
                    layer.type == LayerType.VIDEO &&
                    (playing || MediaWorkers.isConstrainedDevice)
                ) 320 else 640
                val requestKey = bucket to frameEdge
                if (lastRequests[layer.id] == requestKey) {
                    val retryAt = retryAfterNs[layer.id]
                    if (retryAt == null || System.nanoTime() < retryAt) continue
                } else {
                    retryAfterNs.remove(layer.id)
                }
                lastRequests[layer.id] = requestKey

                val frame = MediaLoader.bitmapFor(
                    ctx,
                    Uri.parse(rawUri),
                    layer.type,
                    sourceTime,
                    sampleFps = MediaLoader.CPU_PREVIEW_SAMPLE_FPS,
                    maxEdge = frameEdge,
                    // Playback frames stay RAM-only: synchronous JPEG compression and
                    // app-private file writes must never sit on the frame delivery path.
                    // Paused frames remain persistent so seeking back is still fast.
                    persistToDisk = !playing
                )

                // The decode may finish after the playhead moved. Drop stale
                // output rather than publishing it over a newer frame.
                val currentTime = previewClock.timeSec
                val currentSourceTime = if (layer.type == LayerType.VIDEO) {
                    ((currentTime - layer.start).coerceAtLeast(0f) *
                        layer.speed + layer.srcOffset).coerceAtLeast(0f)
                } else 0f
                val currentBucket = if (layer.type == LayerType.VIDEO) {
                    (currentSourceTime * MediaLoader.CPU_PREVIEW_SAMPLE_FPS.toFloat()).toInt()
                } else 0
                val currentEdge = if (
                    layer.type == LayerType.VIDEO &&
                    (previewClock.playing || MediaWorkers.isConstrainedDevice)
                ) 320 else 640
                // Decode on constrained devices can take several hundred milliseconds.
                // Do not discard a successfully decoded frame just because the playhead
                // advanced while it was decoding: that creates a permanent stale-frame
                // loop (decode -> reject -> decode -> reject) and looks like a frozen
                // preview. Keep the latest available frame until the next one arrives.
                if (frame == null) {
                    // A transient retriever miss must not permanently suppress this bucket.
                    retryAfterNs[layer.id] = System.nanoTime() + 150_000_000L
                } else {
                    retryAfterNs.remove(layer.id)
                }
                if (frame != null && currentEdge == frameEdge &&
                    currentBucket >= bucket
                ) {
                    bitmaps[layer.id] = frame
                }
            }

            delay(if (hadActiveMedia) 16L else 40L)
        }
    }

    // No runtime warmup: imported video frames are read from the persistent disk cache.

    val layerSnapshot = editor.layers
    val layerLookup = remember(layerSnapshot) { layerSnapshot.associateBy { it.id } }
    val destinationRects = remember(layerSnapshot) {
        layerSnapshot.associate { it.id to RectF(0f, 0f, it.width, it.height) }
    }
    val layerMatrices = remember(layerSnapshot) { mutableMapOf<String, Matrix>() }
    val matrixScratch = remember(layerSnapshot) { mutableMapOf<Int, Matrix>() }
    val selectionPath = remember { AndroidPath() }
    val selectionPoints = remember { FloatArray(8) }
    val selectionAnchor = remember { FloatArray(2) }
    val grayscaleFilter = remember {
        ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
    }
    val blendModes = remember {
        mapOf(
            "Multiply" to PorterDuffXfermode(PorterDuff.Mode.MULTIPLY),
            "Screen" to PorterDuffXfermode(PorterDuff.Mode.SCREEN),
            "Add" to PorterDuffXfermode(PorterDuff.Mode.ADD)
        )
    }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true } }
    val textPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER; textSize = 120f; typeface = Typeface.DEFAULT_BOLD
        }
    }
    val outline = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF8466FF.toInt() }
    }

    Canvas(modifier.background(Color(0xFF0A0A0F))) {
        val drawStartedNs = System.nanoTime()
        val s = minOf(size.width / COMP_W, size.height / COMP_H)
        val ox = (size.width - COMP_W * s) / 2f
        val oy = (size.height - COMP_H * s) / 2f
        val time = editor.time
        val layers = layerSnapshot
        val byId = layerLookup
        val selId = editor.selectedId

        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            nc.save()
            nc.translate(ox, oy)
            nc.scale(s, s)
            nc.clipRect(0f, 0f, COMP_W, COMP_H)
            nc.drawColor(android.graphics.Color.BLACK)

            for (l in layers.asReversed()) {
                if (!l.visible || !l.activeAt(time) || l.type == LayerType.AUDIO) continue
                val alpha = (l.p(PropId.OPACITY).valueAt(time) * 2.55f).toInt().coerceIn(0, 255)
                nc.save()
                val layerMatrix = layerMatrices.getOrPut(l.id) { Matrix() }
                layerMatrixInto(l, time, byId, layerMatrix, matrixScratch)
                nc.concat(layerMatrix)
                if (l.maskEnabled) nc.clipRect(0f, 0f, l.width * 0.8f, l.height * 0.8f)
                paint.xfermode = blendModes[l.blendMode]
                paint.colorFilter = if ("Grayscale" in l.effects) grayscaleFilter else null
                when (l.type) {
                    LayerType.IMAGE, LayerType.VIDEO -> {
                        val bmp = bitmaps[l.id]
                        paint.alpha = alpha
                        if (bmp != null) {
                            paint.color = android.graphics.Color.WHITE
                            nc.drawBitmap(bmp, null, destinationRects[l.id] ?: RectF(0f, 0f, l.width, l.height), paint)
                        } else {
                            paint.color = 0xFF24242F.toInt(); paint.alpha = alpha
                            nc.drawRect(0f, 0f, l.width, l.height, paint)
                        }
                    }
                    LayerType.SHAPE -> {
                        paint.color = l.color; paint.alpha = alpha
                        nc.drawRoundRect(0f, 0f, l.width, l.height, 24f, 24f, paint)
                    }
                    LayerType.TEXT -> {
                        textPaint.color = l.color; textPaint.alpha = alpha
                        textPaint.xfermode = blendModes[l.blendMode]
                        textPaint.colorFilter = if ("Grayscale" in l.effects) grayscaleFilter else null
                        nc.drawText(l.text, l.width / 2f, l.height / 2f + 40f, textPaint)
                    }
                    else -> Unit
                }
                paint.xfermode = null
                paint.colorFilter = null
                textPaint.xfermode = null
                textPaint.colorFilter = null
                nc.restore()
            }
            nc.restore()

            // selection overlay (not clipped to the composition)
            val sel = selId?.let { byId[it] }
            if (sel != null && sel.visible && sel.activeAt(time) && sel.type != LayerType.AUDIO) {
                nc.save()
                nc.translate(ox, oy)
                nc.scale(s, s)
                val m = layerMatrices[sel.id] ?: Matrix().also { matrix ->
                    layerMatrixInto(sel, time, byId, matrix, matrixScratch)
                    layerMatrices[sel.id] = matrix
                }
                val pts = selectionPoints
                pts[0] = 0f; pts[1] = 0f
                pts[2] = sel.width; pts[3] = 0f
                pts[4] = sel.width; pts[5] = sel.height
                pts[6] = 0f; pts[7] = sel.height
                m.mapPoints(pts)
                outline.strokeWidth = 2f / s
                outline.pathEffect =
                    if (sel.type == LayerType.ADJUSTMENT) DashPathEffect(floatArrayOf(14f / s, 10f / s), 0f) else null
                selectionPath.reset()
                selectionPath.moveTo(pts[0], pts[1]); selectionPath.lineTo(pts[2], pts[3])
                selectionPath.lineTo(pts[4], pts[5]); selectionPath.lineTo(pts[6], pts[7]); selectionPath.close()
                nc.drawPath(selectionPath, outline)
                val a = selectionAnchor
                a[0] = sel.p(PropId.ANCHOR_X).valueAt(time)
                a[1] = sel.p(PropId.ANCHOR_Y).valueAt(time)
                m.mapPoints(a)
                outline.pathEffect = null
                nc.drawCircle(a[0], a[1], 7f / s, outline)
                nc.restore()
            }
        }
        PreviewDiagnostics.recordCpuDrawFrame(System.nanoTime() - drawStartedNs)
    }
}
