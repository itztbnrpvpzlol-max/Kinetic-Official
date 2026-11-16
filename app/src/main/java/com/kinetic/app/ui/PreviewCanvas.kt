package com.kinetic.app.ui

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
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
import com.kinetic.app.model.COMP_H
import com.kinetic.app.model.COMP_W
import com.kinetic.app.model.EditorState
import com.kinetic.app.model.LayerType
import com.kinetic.app.model.PropId
import com.kinetic.app.model.VectorPoint
import com.kinetic.app.model.sourceElapsedAt
import com.kinetic.app.model.layerMatrixInto
import com.kinetic.app.engine.NativeEffectsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.graphics.Path as AndroidPath

private class CpuPreviewClock {
    @Volatile var timeSec: Float = 0f
    @Volatile var playing: Boolean = false
}

@Composable
fun CpuPreviewCanvas(
    editor: EditorState,
    modifier: Modifier = Modifier,
    directVideoLayerId: String? = null
) {
    val ctx = LocalContext.current
    val bitmaps = remember { mutableStateMapOf<String, Bitmap>() }
    val effectedBitmaps = remember { mutableStateMapOf<String, Bitmap>() }
    val motionTileBitmaps = remember { mutableStateMapOf<String, Bitmap>() }
    val previewClock = remember(editor) { CpuPreviewClock() }
    val mediaLayers = editor.layers.filter {
        it.id != directVideoLayerId && it.uri != null && (it.type == LayerType.IMAGE || it.type == LayerType.VIDEO)
    }
    val mediaLayerSignature = mediaLayers.map {
        "${it.id}|${it.uri}|${it.type}|${it.start}|${it.duration}|${it.srcOffset}|${it.p(PropId.SPEED)}|${it.visible}|${it.effectStack.hashCode()}"
    }

    // Observe the master clock outside composition. Updating this volatile
    // holder does not invalidate the editor UI on every playback tick.
    LaunchedEffect(editor, previewClock) {
        snapshotFlow { editor.time to editor.playing }.collect { (time, playing) ->
            previewClock.timeSec = time
            previewClock.playing = playing
        }
    }

    // One latest-only decode loop serves all media layers. A single worker is
    // the safe limit on this device class; per-layer polling coroutines used
    // to queue obsolete frame requests behind blocking native decodes.
    LaunchedEffect(mediaLayerSignature) {
        val validIds = mediaLayers.map { it.id }.toSet()
        effectedBitmaps.keys.toList().filterNot { it in validIds }.forEach { id ->
            val source = bitmaps[id]
            val old = effectedBitmaps.remove(id)
            if (old != null && old !== source && !old.isRecycled) old.recycle()
        }
        bitmaps.keys.toList().filterNot { it in validIds }.forEach { bitmaps.remove(it) }
        motionTileBitmaps.keys.toList().filterNot { it in validIds }.forEach { id ->
            motionTileBitmaps.remove(id)?.let { if (!it.isRecycled) it.recycle() }
        }
        val lastRequests = mutableMapOf<String, Pair<Int, Int>>()
        val retryAfterNs = mutableMapOf<String, Long>()

        while (true) {
            val timelineTime = previewClock.timeSec
            val playing = previewClock.playing
            var hadActiveMedia = false

            for (layer in mediaLayers) {
                val rawUri = layer.uri ?: continue
                if (!layer.visible || !layer.activeAt(timelineTime)) {
                    // Keep the most recently decoded frame warm while this clip
                    // is outside the playhead. On re-entry it can cover decoder
                    // startup latency instead of flashing the missing-frame fill.
                    lastRequests.remove(layer.id)
                    continue
                }
                hadActiveMedia = true

                val sourceTime = if (layer.type == LayerType.VIDEO) {
                    (layer.sourceElapsedAt(timelineTime) + layer.srcOffset).coerceAtLeast(0f)
                } else 0f
                // CPU effects are expensive on low-memory phones. Limit effect-bearing
                // preview frames to 12 fps instead of queuing 20 fps of native work.
                val sampleFps = if (MediaWorkers.isConstrainedDevice && layer.effectStack.any { it.enabled }) {
                    12
                } else MediaLoader.CPU_PREVIEW_SAMPLE_FPS
                val bucket = if (layer.type == LayerType.VIDEO) {
                    (sourceTime * sampleFps.toFloat()).toInt()
                } else 0
                val frameEdge = if (
                    layer.effectStack.any { it.enabled } ||
                    (layer.type == LayerType.VIDEO && (playing || MediaWorkers.isConstrainedDevice))
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
                    sampleFps = sampleFps,
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
                    (layer.sourceElapsedAt(currentTime) + layer.srcOffset).coerceAtLeast(0f)
                } else 0f
                val currentBucket = if (layer.type == LayerType.VIDEO) {
                    (currentSourceTime * sampleFps.toFloat()).toInt()
                } else 0
                val currentEdge = if (
                    layer.effectStack.any { it.enabled } ||
                    (layer.type == LayerType.VIDEO &&
                        (previewClock.playing || MediaWorkers.isConstrainedDevice))
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

    // Effect pixels are processed by the independent native CPU engine. This
    // worker is latest-only and samples animated effects at the preview rate;
    // no effect shader or Kotlin pixel-processing fallback is involved.
    LaunchedEffect(mediaLayerSignature) {
        if (mediaLayers.none { layer -> layer.effectStack.any { it.enabled } }) return@LaunchedEffect
        val lastProcessed = mutableMapOf<String, Long>()
        while (true) {
            val timelineTime = previewClock.timeSec
            val playing = previewClock.playing
            for (layer in mediaLayers) {
                if (!layer.visible || !layer.activeAt(timelineTime)) {
                    motionTileBitmaps.remove(layer.id)?.let { if (!it.isRecycled) it.recycle() }
                    continue
                }
                val source = bitmaps[layer.id] ?: continue
                val activeEffects = layer.effectStack.filter { it.enabled }.take(8)
                if (activeEffects.isEmpty()) {
                    val previous = effectedBitmaps.remove(layer.id)
                    if (previous != null && previous !== source && !previous.isRecycled) previous.recycle()
                    motionTileBitmaps.remove(layer.id)?.let { if (!it.isRecycled) it.recycle() }
                    lastProcessed.remove(layer.id)
                    continue
                }
                if (!layer.visible || !layer.activeAt(timelineTime)) continue
                val timeDependent = playing || activeEffects.any { effect ->
                    effect.name == "Wave Warp" || effect.name == "Warp Shake" ||
                        effect.name == "Oscillate" || effect.parameterKeys.values.any { it.isNotEmpty() }
                }
                val effectSampleFps = if (MediaWorkers.isConstrainedDevice) 12 else MediaLoader.CPU_PREVIEW_SAMPLE_FPS
                val timeBucket = if (timeDependent) (timelineTime * effectSampleFps).toInt() else 0
                var key = System.identityHashCode(source).toLong()
                key = (key * 31L) + activeEffects.hashCode().toLong()
                key = (key * 31L) + timeBucket.toLong()
                if (lastProcessed[layer.id] == key) continue
                val motionTile = activeEffects.lastOrNull { it.name == "Motion Tile" }
                val rendered = withContext(Dispatchers.Default) {
                    NativeEffectsEngine.processBitmap(source, activeEffects.filterNot { it.name == "Motion Tile" }, timelineTime)
                }
                val tileSource = rendered ?: source
                val tileRendered = if (motionTile != null) {
                    withContext(Dispatchers.Default) {
                        NativeEffectsEngine.createMotionTileBackground(tileSource, motionTile, timelineTime)
                    }
                } else null
                if (bitmaps[layer.id] === source && previewClock.timeSec.let { !timeDependent || (it * effectSampleFps).toInt() == timeBucket }) {
                    if (motionTile == null) {
                        motionTileBitmaps.remove(layer.id)?.let { if (!it.isRecycled) it.recycle() }
                    } else if (tileRendered != null) {
                        val oldTile = motionTileBitmaps.put(layer.id, tileRendered)
                        if (oldTile != null && oldTile !== tileRendered && !oldTile.isRecycled) oldTile.recycle()
                    }
                    val previous = effectedBitmaps.put(layer.id, rendered ?: source)
                    if (previous != null && previous !== source && previous !== rendered && !previous.isRecycled) previous.recycle()
                    lastProcessed[layer.id] = key
                } else {
                    if (rendered != null && rendered !== source && !rendered.isRecycled) rendered.recycle()
                    if (tileRendered != null && !tileRendered.isRecycled) tileRendered.recycle()
                }
            }
            delay(if (playing) 40L else 80L)
        }
    }

    val shapePaths = remember { mutableMapOf<String, android.graphics.Path>() }
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

    Canvas(
        if (directVideoLayerId == null) modifier.background(Color(0xFF0A0A0F)) else modifier
    ) {
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
            if (directVideoLayerId == null) nc.drawColor(android.graphics.Color.BLACK)

            for (l in layers.asReversed()) {
                if (!l.visible || !l.activeAt(time) || l.type == LayerType.AUDIO || l.id == directVideoLayerId) continue
                val alpha = (l.p(PropId.OPACITY).valueAt(time) * 2.55f).toInt().coerceIn(0, 255)
                nc.save()
                val layerMatrix = layerMatrices.getOrPut(l.id) { Matrix() }
                layerMatrixInto(l, time, byId, layerMatrix, matrixScratch)
                nc.concat(layerMatrix)
                val motionTile = motionTileBitmaps[l.id]
                val sourceForTile = bitmaps[l.id]
                if (motionTile != null && sourceForTile != null && !motionTile.isRecycled && !sourceForTile.isRecycled) {
                    val tileEffect = l.effectStack.lastOrNull { it.enabled && it.name == "Motion Tile" }
                    val expandedWidth = l.width * ((tileEffect?.parameterAt("Output Width", time, 200f) ?: 200f).coerceIn(100f, 500f) / 100f)
                    val expandedHeight = l.height * ((tileEffect?.parameterAt("Output Height", time, 200f) ?: 200f).coerceIn(100f, 500f) / 100f)
                    val marginX = (expandedWidth - l.width) * 0.5f
                    val marginY = (expandedHeight - l.height) * 0.5f
                    paint.alpha = alpha
                    paint.color = android.graphics.Color.WHITE
                    paint.xfermode = blendModes[l.blendMode]
                    paint.colorFilter = if ("Grayscale" in l.effects) grayscaleFilter else null
                    nc.drawBitmap(
                        motionTile, null,
                        RectF(-marginX, -marginY, l.width + marginX, l.height + marginY),
                        paint
                    )
                }
                val activeMask = l.maskAt(time).takeIf { l.maskEnabled }
                if (activeMask != null) nc.saveLayer(RectF(0f, 0f, l.width, l.height), null)
                paint.xfermode = blendModes[l.blendMode]
                paint.colorFilter = if ("Grayscale" in l.effects) grayscaleFilter else null
                when (l.type) {
                    LayerType.IMAGE, LayerType.VIDEO -> {
                        val bmp = effectedBitmaps[l.id] ?: bitmaps[l.id]
                        paint.alpha = alpha
                        if (bmp != null) {
                            paint.color = android.graphics.Color.WHITE
                            nc.drawBitmap(bmp, null, destinationRects[l.id] ?: RectF(0f, 0f, l.width, l.height), paint)
                        }
                        // A missing decode is transparent to the composition.
                        // Never paint a gray placeholder over the clip: it can
                        // persist through animated transforms and transitions.
                    }
                    LayerType.SHAPE -> {
                        paint.color = l.color; paint.alpha = alpha
                        val points = if (l.vectorPoints.isEmpty()) listOf(VectorPoint(0f, 0f), VectorPoint(1f, 0f), VectorPoint(1f, 1f), VectorPoint(0f, 1f)) else l.vectorPointsAt(time)
                        val path = shapePaths.getOrPut(l.id) { android.graphics.Path() }
                        path.reset()
                        points.forEachIndexed { index, point ->
                            val px = point.x * l.width; val py = point.y * l.height
                            if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
                        }
                        path.close(); nc.drawPath(path, paint)
                    }
                    LayerType.TEXT -> {
                        textPaint.color = l.color; textPaint.alpha = alpha
                        textPaint.xfermode = blendModes[l.blendMode]
                        textPaint.colorFilter = if ("Grayscale" in l.effects) grayscaleFilter else null
                        nc.drawText(l.text, l.width / 2f, l.height / 2f + 40f, textPaint)
                    }
                    else -> Unit
                }
                if (activeMask != null) {
                    val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.WHITE
                        setAlpha((activeMask.opacity * 2.55f).toInt().coerceIn(0, 255))
                        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                        if (activeMask.feather > 0f) {
                            maskFilter = BlurMaskFilter(activeMask.feather.coerceIn(0f, 100f), BlurMaskFilter.Blur.NORMAL)
                        }
                    }
                    val maskShape = buildMaskContour(activeMask.points, l.width, l.height, activeMask.closed)
                    if (activeMask.inverted || activeMask.mode == "Subtract") {
                        maskShape.fillType = AndroidPath.FillType.INVERSE_WINDING
                    }
                    if ((activeMask.kind == "Pen" || activeMask.kind == "Ellipse") && activeMask.points.size >= 3 && activeMask.closed) {
                        if (activeMask.expansion != 0f) {
                            maskPaint.style = Paint.Style.FILL_AND_STROKE
                            maskPaint.strokeWidth = activeMask.expansion.coerceIn(-100f, 100f) * 2f
                            maskPaint.strokeJoin = Paint.Join.ROUND
                        }
                        nc.drawPath(maskShape, maskPaint)
                    } else if ((activeMask.kind == "Draw" || activeMask.kind == "Roto Brush") && activeMask.strokes.isNotEmpty()) {
                        val foreground = AndroidPath()
                        val background = AndroidPath()
                        activeMask.strokes.forEach { stroke ->
                            if (stroke.points.size > 1) {
                                val target = if (stroke.foreground) foreground else background
                                stroke.points.forEachIndexed { index, point ->
                                    val x = point.x * l.width
                                    val y = point.y * l.height
                                    if (index == 0) target.moveTo(x, y) else target.lineTo(x, y)
                                }
                            }
                        }
                        val brushPaint = Paint(maskPaint).apply {
                            style = Paint.Style.STROKE
                            strokeCap = Paint.Cap.ROUND
                            strokeJoin = Paint.Join.ROUND
                            strokeWidth = (activeMask.brushSize * minOf(l.width, l.height)).coerceAtLeast(2f)
                        }
                        if (!foreground.isEmpty) nc.drawPath(foreground, brushPaint)
                        if (!background.isEmpty) {
                            brushPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                            nc.drawPath(background, brushPaint)
                        }
                    }
                    nc.restore()
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
