package com.kinetic.app.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinetic.app.data.MediaLoader
import com.kinetic.app.data.MediaWorkers
import com.kinetic.app.model.EditorState
import com.kinetic.app.model.fmt
import com.kinetic.app.model.Layer
import com.kinetic.app.model.LayerType
import com.kinetic.app.model.PropId
import com.kinetic.app.model.isDescendantOf
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.sample
import kotlin.random.Random

private val ROW_H = 40.dp

fun clipColor(t: LayerType): Color = when (t) {
    LayerType.TEXT -> Color(0xFF3A3590)
    LayerType.IMAGE -> Color(0xFF2A2650)
    LayerType.VIDEO -> Color(0xFF312C6B)
    LayerType.AUDIO -> Color(0xFF241F52)
    LayerType.SHAPE -> Color(0xFF33307A)
    LayerType.ADJUSTMENT -> Color(0xFF2A2A3A)
    LayerType.NULL -> Color(0xFF22222E)
}

fun typeIcon(t: LayerType): IconKind = when (t) {
    LayerType.TEXT -> IconKind.TEXT
    LayerType.IMAGE -> IconKind.IMAGE
    LayerType.VIDEO -> IconKind.VIDEO
    LayerType.AUDIO -> IconKind.MUSIC
    LayerType.SHAPE -> IconKind.SHAPES
    LayerType.ADJUSTMENT -> IconKind.ADJ
    LayerType.NULL -> IconKind.NULL
}

@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
fun TimelinePane(editor: EditorState, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val dens = density.density
    var pps by remember { mutableFloatStateOf(56f) } // dp per second
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    var paneW by remember { mutableIntStateOf(0) }
    var leftWidth by remember { mutableFloatStateOf(176f) }
    var expandedKeyframeLayers by remember { mutableStateOf<Set<String>>(emptySet()) }
    val leftW = leftWidth.dp
    val leftPx = with(density) { leftW.toPx() }
    val contentSec = max(editor.duration, editor.layers.maxOfOrNull { it.end } ?: 0f)
    // The track viewport starts immediately after the resizable layer-controls column.
    // Time zero is anchored at that edge; the playhead follows the actual timeline time.
    val viewportW = ((paneW - leftPx).coerceAtLeast(0f) / dens).dp
    val contentW = viewportW + (contentSec * pps).dp + 48.dp

    // Center the playhead under the preview transport's play control.
    // During playback, sample auto-scroll at the composition frame cadence.
    // The native clock remains authoritative; the scroll is only a visual follow.
    val autoScrollIntervalMs = if (MediaWorkers.isConstrainedDevice) 100L else 50L
    LaunchedEffect(editor.playing, pps, paneW, leftPx) {
        snapshotFlow { editor.time }
            .let { times -> if (editor.playing) times.sample(autoScrollIntervalMs) else times }
            .collect { time ->
                if (paneW > leftPx) {
                    val playheadX = leftPx + time * pps * dens
                    val target = (playheadX - paneW / 2f).coerceAtLeast(0f)
                    val delta = target - hScroll.value.toFloat()
                    if (kotlin.math.abs(delta) > 0.5f) hScroll.dispatchRawDelta(delta)
                }
            }
    }

    Box(
        modifier
            .fillMaxWidth()
            .onSizeChanged { paneW = it.width }
            .pointerInput(Unit) { detectPinch { z -> pps = (pps * z).coerceIn(18f, 400f) } }
            .pointerInput(pps, paneW, leftPx) {
                var timelineDrag = false
                detectDragGestures(
                    onDragStart = { origin -> timelineDrag = origin.x > leftPx + 12f * dens },
                    onDragEnd = { timelineDrag = false },
                    onDragCancel = { timelineDrag = false }
                ) { change, drag ->
                    if (timelineDrag) {
                        change.consume()
                        hScroll.dispatchRawDelta(-drag.x)
                        val centeredTime = (hScroll.value + paneW / 2f - leftPx) / dens / pps
                        editor.seek(centeredTime.coerceAtLeast(0f))
                    }
                }
            }
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(30.dp)) {
                Spacer(Modifier.width(leftW))
                Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(hScroll)) {
                    Ruler(editor, pps, contentW)
                }
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth()
                    // A tap on unused timeline space clears the active layer selection.
                    .clickable { editor.select(null) },
                state = rememberLazyListState(),
                // Stable item keys let Compose retain only the visible layer rows.
                // Large projects no longer compose every layer and every property row.
            ) {
                if (editor.layers.isEmpty()) {
                    item(key = "empty-timeline") {
                        Box(Modifier.fillMaxWidth().padding(top = 28.dp, start = 24.dp, end = 24.dp), contentAlignment = Alignment.Center) {
                            Text(
                                "Empty composition. Tap + to import media or add a layer.",
                                color = KColors.Dim, fontSize = 13.sp, textAlign = TextAlign.Center
                            )
                        }
                    }
                }
                items(editor.layers, key = { it.id }) { layer ->
                    LayerRow(
                        editor, layer, pps, hScroll, contentW, leftW,
                        keyframesExpanded = layer.id in expandedKeyframeLayers,
                        onToggleKeyframes = {
                            expandedKeyframeLayers = if (layer.id in expandedKeyframeLayers) {
                                expandedKeyframeLayers - layer.id
                            } else {
                                expandedKeyframeLayers + layer.id
                            }
                        }
                    )
                }
            }
        }
        // Drag the divider to resize the layer-controls column independently of timeline zoom.
        Box(
            Modifier.offset(x = (leftW - 4.dp)).width(8.dp).fillMaxHeight()
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, drag ->
                        change.consume()
                        leftWidth = (leftWidth + drag / dens).coerceIn(124f, 320f)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.fillMaxHeight().width(2.dp).background(KColors.Line))
            Box(Modifier.width(4.dp).height(34.dp).clip(RoundedCornerShape(4.dp)).background(KColors.Muted))
        }
        // playhead
        Canvas(Modifier.fillMaxSize()) {
            // Keep the playhead aligned with the center Preview play/pause control,
            // not the center of the timeline viewport (which excludes the layer column).
            val x = leftPx + editor.time * pps * dens - hScroll.value
            if (x >= leftPx - 1f && x <= size.width) {
                drawLine(Color.White, Offset(x, 14.dp.toPx()), Offset(x, size.height), 2.dp.toPx())
                val tri = Path().apply {
                    moveTo(x - 7.dp.toPx(), 4.dp.toPx())
                    lineTo(x + 7.dp.toPx(), 4.dp.toPx())
                    lineTo(x, 17.dp.toPx())
                    close()
                }
                drawPath(tri, Color.White)
            }
        }
    }
}

@Composable
private fun Ruler(editor: EditorState, pps: Float, contentW: Dp) {
    val dens = LocalDensity.current.density
    val textPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8D8BA3.toInt() }
    }
    Canvas(
        Modifier
            .width(contentW)
            .fillMaxHeight()
            .pointerInput(pps) { detectTapGestures { o -> editor.seekSnap((o.x / dens / pps).coerceAtLeast(0f), 8f / pps) } }
            .pointerInput(pps) {
                detectDragGestures { change, _ ->
                    change.consume()
                    editor.seekSnap((change.position.x / dens / pps).coerceAtLeast(0f), 8f / pps)
                }
            }
    ) {
        textPaint.textSize = 11.sp.toPx()
        val step = if (pps >= 40f) 1 else if (pps >= 20f) 2 else 5
        val secs = (size.width / dens / pps).toInt() + 1
        val tick = Color(0xFF34344A)
        val originX = 0f
        val endX = originX + editor.duration * pps * dens
        if (endX < size.width) drawRect(Color(0x55000000), Offset(endX, 0f), Size(size.width - endX, size.height))
        for (s in 0..secs) {
            val x = originX + s * pps * dens
            if (pps >= 80f) {
                drawLine(tick, Offset(x + pps * dens / 2f, size.height - 5.dp.toPx()), Offset(x + pps * dens / 2f, size.height), 1.dp.toPx())
            }
            if (s % step != 0) continue
            drawLine(tick, Offset(x, size.height - 10.dp.toPx()), Offset(x, size.height), 1.dp.toPx())
            val label = fmt("%02d:%02d", s / 60, s % 60)
            textPaint.textAlign = if (s == 0) Paint.Align.LEFT else Paint.Align.CENTER
            drawIntoCanvas { it.nativeCanvas.drawText(label, if (s == 0) x + 4.dp.toPx() else x, 16.dp.toPx(), textPaint) }
        }
    }
}

@Composable
private fun LayerRow(
    editor: EditorState, layer: Layer, pps: Float, hScroll: ScrollState, contentW: Dp, leftW: Dp,
    keyframesExpanded: Boolean, onToggleKeyframes: () -> Unit
) {
    val selected = editor.selectedId == layer.id
    val dens = LocalDensity.current.density
    var acc by remember { mutableFloatStateOf(0f) }
    var parentDrag by remember { mutableFloatStateOf(0f) }
    var isParentDragging by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(ROW_H)
                .padding(vertical = 1.dp)
                .background(if (selected) KColors.RowSel else KColors.Row)
        ) {
            Row(Modifier.width(leftW).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(36.dp).clickable { editor.toggleVisible(layer.id) },
                    contentAlignment = Alignment.Center
                ) {
                    KIcon(
                        if (layer.visible) IconKind.EYE else IconKind.EYE_OFF,
                        if (layer.visible) KColors.ClipText else KColors.Dim
                    )
                }
                Box(
                    Modifier.size(36.dp).pointerInput(layer.id) {
                        detectDragGestures(onDragStart = { acc = 0f; editor.beginEdit() }) { change, d ->
                            change.consume()
                            acc += d.y
                            val step = ROW_H.toPx()
                            if (acc > step) { editor.moveLayer(layer.id, 1); acc -= step }
                            else if (acc < -step) { editor.moveLayer(layer.id, -1); acc += step }
                        }
                    },
                    contentAlignment = Alignment.Center
                ) { KIcon(IconKind.HANDLE, KColors.Dim) }
                Box(
                    Modifier.size(36.dp).pointerInput(layer.id, editor.layers) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { parentDrag = 0f; isParentDragging = true },
                            onDragEnd = {
                                val from = editor.layers.indexOfFirst { it.id == layer.id }
                                val step = 40f * dens
                                val targetIndex = (from + (parentDrag / step).roundToInt()).coerceIn(editor.layers.indices)
                                val target = editor.layers.getOrNull(targetIndex)
                                val byId = editor.layers.associateBy { it.id }
                                if (target != null && target.id != layer.id && !isDescendantOf(target, layer.id, byId)) {
                                    editor.setParent(layer.id, target.id)
                                }
                                isParentDragging = false; parentDrag = 0f
                            },
                            onDragCancel = { isParentDragging = false; parentDrag = 0f }
                        ) { change, drag -> change.consume(); parentDrag += drag.y }
                    },
                    contentAlignment = Alignment.Center
                ) {
                    KIcon(IconKind.PARENT, if (layer.parentId != null) KColors.Accent else KColors.Dim)
                    if (isParentDragging) Canvas(Modifier.fillMaxSize()) {
                        val x = size.width / 2f
                        drawLine(KColors.Accent, Offset(x, size.height / 2f), Offset(x, size.height / 2f + parentDrag), 2.dp.toPx())
                        drawCircle(KColors.Accent, 4.dp.toPx(), Offset(x, size.height / 2f + parentDrag))
                    }
                }
                Box(
                    Modifier.size(32.dp).clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onToggleKeyframes),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (keyframesExpanded) "⌄" else "›",
                        color = if (layer.props.values.any { it.keys.isNotEmpty() }) KColors.Accent else KColors.Dim,
                        fontSize = 20.sp
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(hScroll)) {
                Box(Modifier.width(contentW).fillMaxHeight()) { Clip(editor, layer, pps, selected) }
            }
        }
        if (keyframesExpanded) {
            layer.props.entries
                .filter { it.value.keys.isNotEmpty() }
                .sortedBy { it.key.ordinal }
                .forEach { (propId, prop) ->
                    KeyframeTrackRow(editor, layer, propId, prop.keys, pps, hScroll, contentW, leftW)
                }
        }
        val parentName = layer.parentId?.let { id -> editor.layers.firstOrNull { it.id == id }?.name }
        if (parentName != null) {
            Row(Modifier.fillMaxWidth().height(20.dp).background(KColors.Row), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(leftW))
                Text("╰── Parent: $parentName", color = KColors.Accent, fontSize = 10.sp, modifier = Modifier.padding(start = 14.dp))
            }
        }
    }
}

@Composable
private fun KeyframeTrackRow(
    editor: EditorState,
    layer: Layer,
    propId: PropId,
    keys: List<com.kinetic.app.model.Keyframe>,
    pps: Float,
    hScroll: ScrollState,
    contentW: Dp,
    leftW: Dp
) {
    val dens = LocalDensity.current.density
    Row(
        Modifier.fillMaxWidth().height(22.dp).background(KColors.Bg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.width(leftW).fillMaxHeight().padding(start = 12.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                propId.label,
                color = KColors.Muted,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(hScroll)) {
            Canvas(
                Modifier.width(contentW).fillMaxHeight()
                    .pointerInput(layer.id, propId, keys, pps) {
                        detectTapGestures { tap ->
                            val time = (tap.x / dens / pps).coerceAtLeast(0f)
                            val nearest = keys.minByOrNull { abs(it.t - time) }
                            if (nearest != null) {
                                editor.select(layer.id)
                                editor.selectProp(propId)
                                editor.seek(nearest.t)
                            }
                        }
                    }
            ) {
                val centerY = size.height / 2f
                keys.forEach { key ->
                    val x = key.t * pps * dens
                    val r = 4.dp.toPx()
                    val diamond = Path().apply {
                        moveTo(x, centerY - r)
                        lineTo(x + r, centerY)
                        lineTo(x, centerY + r)
                        lineTo(x - r, centerY)
                        close()
                    }
                    drawPath(diamond, if (kotlin.math.abs(editor.time - key.t) <= editor.frame / 2f) KColors.Accent else KColors.ClipText)
                }
            }
        }
    }
}

@Composable
private fun Clip(editor: EditorState, layer: Layer, pps: Float, selected: Boolean) {
    val ctx = LocalContext.current
    val dens = LocalDensity.current.density
    val shape = RoundedCornerShape(8.dp)
    var thumbnails by remember(
        layer.uri, layer.type, layer.duration, layer.srcOffset, layer.speed
    ) { mutableStateOf<List<Bitmap>>(emptyList()) }
    LaunchedEffect(
        layer.uri, layer.type, layer.duration, layer.srcOffset, layer.speed, editor.playing
    ) {
        // Keep the last strip visible while playing; defer only new thumbnail
        // work until pause instead of clearing the timeline artwork.
        if (editor.playing) return@LaunchedEffect
        val u = layer.uri
        thumbnails = if (u == null || (layer.type != LayerType.IMAGE && layer.type != LayerType.VIDEO)) {
            emptyList()
        } else if (layer.type == LayerType.IMAGE) {
            listOfNotNull(MediaLoader.bitmapFor(ctx, Uri.parse(u), LayerType.IMAGE, 0f, 1, 320, lowPriorityRequest = true, persistToDisk = false))
        } else {
            val fractions = floatArrayOf(0f, 0.25f, 0.5f, 0.75f)
            buildList {
                for (fraction in fractions) {
                    var sourceTime = layer.srcOffset + layer.duration * fraction * layer.speed
                    if (layer.srcLength.isFinite()) {
                        sourceTime = sourceTime.coerceAtMost((layer.srcLength - 0.05f).coerceAtLeast(0f))
                    }
                    MediaLoader.bitmapFor(ctx, Uri.parse(u), LayerType.VIDEO, sourceTime, 2, 240, lowPriorityRequest = true, persistToDisk = false)?.let(::add)
                }
            }
        }
    }
    val dashed = layer.type == LayerType.ADJUSTMENT || layer.type == LayerType.NULL
    Box(
        Modifier
            .offset(x = (layer.start * pps).dp)
            .width(max(layer.duration * pps, 6f).dp)
            .fillMaxHeight()
            .padding(vertical = 3.dp)
            .alpha(if (layer.visible) 1f else 0.4f)
            .clip(shape)
            .background(clipColor(layer.type))
            .then(
                if (dashed) Modifier.drawBehind {
                    drawRoundRect(
                        KColors.Muted, cornerRadius = CornerRadius(8.dp.toPx()),
                        style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
                    )
                } else Modifier
            )
            .then(if (selected) Modifier.border(1.5.dp, KColors.Accent, shape) else Modifier)
            .pointerInput(layer.id, editor.selectedId) {
                detectTapGestures {
                    // Tapping the already-selected clip exits layer selection.
                    editor.select(if (editor.selectedId == layer.id) null else layer.id)
                }
            }
            .pointerInput(layer.id, pps) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { editor.select(layer.id); editor.beginEdit() },
                    onDrag = { change, drag -> change.consume(); editor.moveClip(layer.id, drag.x / dens / pps) }
                )
            }
    ) {
        if (thumbnails.isNotEmpty()) {
            Row(Modifier.fillMaxSize()) {
                thumbnails.forEach { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alpha = 0.45f,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
            }
        }
        if (layer.type == LayerType.AUDIO) Waveform(layer.id, layer.uri, editor.playing)
        Row(
            Modifier.align(Alignment.CenterStart).padding(start = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KIcon(typeIcon(layer.type), KColors.ClipText, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(layer.name, color = KColors.ClipText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Clip)
        }
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.CenterStart).width(16.dp).fillMaxHeight()
                    .background(KColors.Accent.copy(alpha = 0.35f))
                    .pointerInput(layer.id, pps) {
                        detectHorizontalDragGestures(onDragStart = { editor.beginEdit() }) { change, dx ->
                            change.consume(); editor.trimLeft(layer.id, dx / dens / pps)
                        }
                    }
            )
            Box(
                Modifier
                    .align(Alignment.CenterEnd).width(16.dp).fillMaxHeight()
                    .background(KColors.Accent.copy(alpha = 0.35f))
                    .pointerInput(layer.id, pps) {
                        detectHorizontalDragGestures(onDragStart = { editor.beginEdit() }) { change, dx ->
                            change.consume(); editor.trimRight(layer.id, dx / dens / pps)
                        }
                    }
            )
        }

    }
}

@Composable
private fun Waveform(seed: String, uri: String?, playing: Boolean) {
    val context = LocalContext.current
    var decodedPeaks by remember(seed, uri) { mutableStateOf<FloatArray?>(null) }
    LaunchedEffect(uri, playing) {
        // Audio waveform extraction is non-urgent and CPU intensive on long
        // tracks; defer first-time extraction until transport is paused.
        if (playing) return@LaunchedEffect
        decodedPeaks = if (uri == null) null else MediaLoader.waveform(context, Uri.parse(uri), 240)
    }
    // Keep drawing bounded while showing actual decoded PCM peaks when available.
    val peaks = decodedPeaks ?: FloatArray(240) { 0.06f }
    Canvas(Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
        if (size.width <= 0f) return@Canvas
        val desiredGap = 5.dp.toPx()
        val count = kotlin.math.ceil(size.width / desiredGap).toInt().coerceIn(1, peaks.size)
        val step = size.width / count
        val stroke = 2.dp.toPx()
        repeat(count) { index ->
            val x = (index + 0.5f) * step
            val hh = size.height * peaks[index]
            drawLine(
                Color(0xFF6D4DFF),
                Offset(x, (size.height - hh) / 2f),
                Offset(x, (size.height + hh) / 2f),
                strokeWidth = stroke
            )
        }
    }
}
