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
import com.kinetic.app.model.EditorState
import com.kinetic.app.model.fmt
import com.kinetic.app.model.Layer
import com.kinetic.app.model.LayerType
import com.kinetic.app.model.PropId
import com.kinetic.app.model.isDescendantOf
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

private val LEFT_W = 176.dp
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

@Composable
fun TimelinePane(editor: EditorState, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val dens = density.density
    var pps by remember { mutableFloatStateOf(56f) } // dp per second
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    var paneW by remember { mutableIntStateOf(0) }
    var expandedLayers by remember { mutableStateOf(setOf<String>()) }
    var graphSelection by remember { mutableStateOf<Pair<String, PropId>?>(null) }
    val leftPx = with(density) { LEFT_W.toPx() }
    val contentSec = max(editor.duration, editor.layers.maxOfOrNull { it.end } ?: 0f)
    // Half-viewport gutters allow time zero and the end to align with the fixed playhead.
    val centerGutter = ((paneW - leftPx).coerceAtLeast(0f) / dens / 2f).dp
    val contentW = centerGutter + (contentSec * pps).dp + centerGutter + 48.dp

    // Center the playhead under the preview transport's play control.
    LaunchedEffect(editor.time, pps, paneW) {
        if (paneW > leftPx) {
            val target = centerGutter.value * dens + editor.time * pps * dens
            hScroll.scrollTo(target.coerceAtLeast(0f).toInt())
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .onSizeChanged { paneW = it.width }
            .pointerInput(Unit) { detectPinch { z -> pps = (pps * z).coerceIn(18f, 400f) } }
            .pointerInput(pps, paneW) {
                detectDragGestures { change, drag ->
                    if (change.position.x > leftPx) {
                        change.consume()
                        hScroll.dispatchRawDelta(-drag.x)
                        val viewW = (paneW - leftPx).coerceAtLeast(1f)
                        val centeredTime = (hScroll.value / dens) / pps
                        editor.seek(centeredTime.coerceAtLeast(0f))
                    }
                }
            }
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(30.dp)) {
                Spacer(Modifier.width(LEFT_W))
                Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(hScroll)) {
                    Ruler(editor, pps, contentW, centerGutter)
                }
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
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
                        editor, layer, pps, hScroll, contentW, centerGutter,
                        expanded = layer.id in expandedLayers,
                        onToggleExpanded = { expandedLayers = if (layer.id in expandedLayers) expandedLayers - layer.id else expandedLayers + layer.id },
                        graphSelection = graphSelection,
                        onGraphSelection = { graphSelection = if (graphSelection == (layer.id to it)) null else layer.id to it }
                    )
                }
            }
        }
        // playhead
        Canvas(Modifier.fillMaxSize()) {
            val x = leftPx + (paneW - leftPx) / 2f
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
private fun Ruler(editor: EditorState, pps: Float, contentW: Dp, centerGutter: Dp) {
    val dens = LocalDensity.current.density
    val textPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8D8BA3.toInt() }
    }
    Canvas(
        Modifier
            .width(contentW)
            .fillMaxHeight()
            .pointerInput(pps, centerGutter) { detectTapGestures { o -> editor.seekSnap(((o.x / dens - centerGutter.value) / pps).coerceAtLeast(0f), 8f / pps) } }
            .pointerInput(pps, centerGutter) {
                detectDragGestures { change, _ ->
                    change.consume()
                    editor.seekSnap(((change.position.x / dens - centerGutter.value) / pps).coerceAtLeast(0f), 8f / pps)
                }
            }
    ) {
        textPaint.textSize = 11.sp.toPx()
        val step = if (pps >= 40f) 1 else if (pps >= 20f) 2 else 5
        val secs = (size.width / dens / pps).toInt() + 1
        val tick = Color(0xFF34344A)
        val originX = centerGutter.toPx()
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
    editor: EditorState, layer: Layer, pps: Float, hScroll: ScrollState, contentW: Dp, centerGutter: Dp,
    expanded: Boolean, onToggleExpanded: () -> Unit,
    graphSelection: Pair<String, PropId>?, onGraphSelection: (PropId) -> Unit
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
            Row(Modifier.width(LEFT_W).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
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
                Box(Modifier.size(32.dp).clickable(onClick = onToggleExpanded), contentAlignment = Alignment.Center) {
                    KIcon(if (expanded) IconKind.CHEVRON_DOWN else IconKind.CHEVRON_UP, if (expanded) KColors.Accent else KColors.Dim, Modifier.size(17.dp))
                }
                Box(Modifier.size(32.dp).clickable {
                    val prop = editor.selectedProp?.takeIf { layer.p(it).animated }
                        ?: PropId.values().firstOrNull { layer.p(it).animated }
                        ?: PropId.POS_X
                    editor.selectProp(prop)
                    if (!expanded) onToggleExpanded()
                    onGraphSelection(prop)
                }, contentAlignment = Alignment.Center) {
                    Text("∿", color = if (graphSelection?.first == layer.id) KColors.Accent else KColors.Dim, fontSize = 20.sp)
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(hScroll)) {
                Box(Modifier.width(contentW).fillMaxHeight()) { Clip(editor, layer, pps, selected, centerGutter) }
            }
        }
        val parentName = layer.parentId?.let { id -> editor.layers.firstOrNull { it.id == id }?.name }
        if (parentName != null) {
            Row(Modifier.fillMaxWidth().height(20.dp).background(KColors.Row), verticalAlignment = Alignment.CenterVertically) {
                Text("╰── Parent: $parentName", color = KColors.Accent, fontSize = 10.sp, modifier = Modifier.padding(start = 14.dp))
            }
        }
        if (expanded) TransformTimelineGroup(
            editor, layer, pps, hScroll, contentW, centerGutter,
            graphOpen = graphSelection?.first == layer.id,
            graphProp = graphSelection?.takeIf { it.first == layer.id }?.second,
            onGraphSelection = onGraphSelection
        )
    }
}

@Composable
private fun Clip(editor: EditorState, layer: Layer, pps: Float, selected: Boolean, centerGutter: Dp) {
    val ctx = LocalContext.current
    val dens = LocalDensity.current.density
    val shape = RoundedCornerShape(8.dp)
    val thumb by produceState<Bitmap?>(null, layer.uri) {
        val u = layer.uri
        value = if (u != null && (layer.type == LayerType.IMAGE || layer.type == LayerType.VIDEO)) {
            MediaLoader.bitmapFor(ctx, Uri.parse(u), layer.type, 0f)
        } else null
    }
    val img = remember(thumb) { thumb?.asImageBitmap() }
    val dashed = layer.type == LayerType.ADJUSTMENT || layer.type == LayerType.NULL

    Box(
        Modifier
            .offset(x = centerGutter + (layer.start * pps).dp)
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
            .pointerInput(layer.id) { detectTapGestures { editor.select(layer.id) } }
            .pointerInput(layer.id, pps) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { editor.select(layer.id); editor.beginEdit() },
                    onDrag = { change, drag -> change.consume(); editor.moveClip(layer.id, drag.x / dens / pps) }
                )
            }
    ) {
        if (img != null) {
            Image(
                bitmap = img, contentDescription = null, contentScale = ContentScale.Crop,
                alpha = 0.45f, modifier = Modifier.fillMaxSize()
            )
        }
        if (layer.type == LayerType.AUDIO) Waveform(layer.id)
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
private fun Waveform(seed: String) {
    Canvas(Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
        val rnd = Random(seed.hashCode())
        val gap = 5.dp.toPx()
        var x = 0f
        while (x < size.width) {
            val hh = size.height * (0.2f + rnd.nextFloat() * 0.6f)
            drawLine(
                Color(0xFF6D4DFF), Offset(x, (size.height - hh) / 2f), Offset(x, (size.height + hh) / 2f),
                strokeWidth = 2.dp.toPx()
            )
            x += gap
        }
    }
}

@Composable
private fun TransformTimelineGroup(
    editor: EditorState, layer: Layer, pps: Float, hScroll: ScrollState, contentW: Dp, centerGutter: Dp,
    graphOpen: Boolean, graphProp: PropId?, onGraphSelection: (PropId) -> Unit
) {
    Row(Modifier.fillMaxWidth().height(28.dp).background(KColors.Surface2), verticalAlignment = Alignment.CenterVertically) {
        Text("TRANSFORM", color = KColors.Accent, fontSize = 10.sp, modifier = Modifier.padding(start = 14.dp).weight(1f))
        if (layer.type != LayerType.AUDIO) {
            Text("SCALE", color = KColors.Muted, fontSize = 9.sp, modifier = Modifier.padding(end = 8.dp))
            Box(Modifier.padding(end = 10.dp).clip(RoundedCornerShape(6.dp)).background(if (layer.scaleLinked) KColors.Accent.copy(alpha = .22f) else KColors.Row).clickable { editor.toggleScaleLink(layer.id) }.padding(horizontal = 8.dp, vertical = 4.dp)) {
                Text(if (layer.scaleLinked) "X = Y" else "X / Y", color = if (layer.scaleLinked) KColors.Accent else KColors.Muted, fontSize = 9.sp)
            }
        }
    }
    if (layer.type != LayerType.AUDIO) {
        for (id in PropId.values()) {
            PropertyTimelineRow(editor, layer, id, pps, hScroll, contentW, centerGutter, graphProp == id, onGraphSelection)
            if (graphOpen && graphProp == id) {
                GraphPanel(editor, layer, Modifier.fillMaxWidth().height(208.dp).background(KColors.Bg))
            }
        }
    }
}

@Composable
private fun PropertyTimelineRow(
    editor: EditorState, layer: Layer, id: PropId, pps: Float, hScroll: ScrollState, contentW: Dp, centerGutter: Dp,
    graphSelected: Boolean, onGraphSelection: (PropId) -> Unit
) {
    val dens = LocalDensity.current.density
    val prop = layer.p(id)
    val latestKeys by rememberUpdatedState(prop.keys)
    Row(Modifier.fillMaxWidth().height(34.dp).background(if (editor.selectedProp == id) KColors.RowSel else KColors.Row)) {
        Row(Modifier.width(LEFT_W).fillMaxHeight().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(id.label, color = if (editor.selectedProp == id) KColors.Text else KColors.Muted, fontSize = 10.sp, modifier = Modifier.weight(1f).clickable { editor.selectProp(id) }, maxLines = 1)
            Box(
                Modifier.width(68.dp).height(28.dp).then(
                    if (editor.settings.afterEffectsScrubbing) Modifier.pointerInput(layer.id, id) {
                        detectHorizontalDragGestures(onDragStart = { editor.beginEdit() }) { change, dx ->
                            change.consume()
                            val current = editor.layers.firstOrNull { it.id == layer.id }?.p(id)?.valueAt(editor.time) ?: prop.valueAt(editor.time)
                            editor.setPropValue(layer.id, id, current + dx / dens * id.scrubStep * editor.settings.scrubMultiplier)
                        }
                    } else Modifier
                ).clickable(enabled = !editor.settings.afterEffectsScrubbing) { editor.selectProp(id) },
                contentAlignment = Alignment.Center
            ) { Text(id.format(prop.valueAt(editor.time)), color = KColors.Accent, fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 1) }
            Box(Modifier.size(24.dp).clickable { editor.toggleStopwatch(layer.id, id) }, contentAlignment = Alignment.Center) {
                KIcon(IconKind.STOPWATCH, if (prop.animated) KColors.Accent else KColors.Dim, Modifier.size(14.dp))
            }
            val hasKey = prop.keys.any { kotlin.math.abs(it.t - editor.time) <= editor.frame / 2f }
            Box(Modifier.size(24.dp).clickable { editor.toggleKeyAtTime(layer.id, id) }, contentAlignment = Alignment.Center) {
                KIcon(if (hasKey) IconKind.DIAMOND_FILLED else IconKind.DIAMOND, if (hasKey) KColors.Accent else KColors.Dim, Modifier.size(14.dp))
            }
            Box(Modifier.size(24.dp).clickable { editor.selectProp(id); onGraphSelection(id) }, contentAlignment = Alignment.Center) {
                Text("∿", color = if (graphSelected) KColors.Accent else KColors.Dim, fontSize = 18.sp)
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(hScroll)) {
            Canvas(Modifier.width(contentW).fillMaxHeight()
                .pointerInput(layer.id, id, pps) {
                    detectTapGestures { o ->
                        val keysNow = latestKeys
                        val nearest = keysNow.indices.minByOrNull { i -> abs(centerGutter.toPx() + keysNow[i].t * pps * dens - o.x) }
                        if (nearest != null && abs(centerGutter.toPx() + keysNow[nearest].t * pps * dens - o.x) < 22.dp.toPx()) editor.selectKey(id, nearest, keysNow[nearest].t)
                        else editor.selectProp(id)
                    }
                }
                .pointerInput(layer.id, id, pps) {
                    var dragIndex = -1
                    detectDragGestures(
                        onDragStart = { o ->
                            val keysNow = latestKeys
                            dragIndex = keysNow.indices.minByOrNull { i -> abs(keysNow[i].t * pps * dens - o.x) } ?: -1
                            if (dragIndex >= 0 && abs(keysNow[dragIndex].t * pps * dens - o.x) < 26.dp.toPx()) {
                                editor.selectKey(id, dragIndex, null); editor.beginEdit()
                            } else dragIndex = -1
                        },
                        onDragEnd = { dragIndex = -1 }, onDragCancel = { dragIndex = -1 }
                    ) { change, _ ->
                        change.consume()
                        if (dragIndex in latestKeys.indices) editor.moveKeyTime(layer.id, id, dragIndex, ((change.position.x / dens - centerGutter.value) / pps).coerceIn(0f, editor.duration))
                    }
                }) {
                val cy = size.height / 2f
                val r = 5.dp.toPx()
                prop.keys.forEachIndexed { i, key ->
                    val x = centerGutter.toPx() + key.t * pps * dens
                    val diamond = Path().apply { moveTo(x, cy-r); lineTo(x+r, cy); lineTo(x, cy+r); lineTo(x-r, cy); close() }
                    drawPath(diamond, if (editor.selectedProp == id && editor.selectedKey == i) Color.White else KColors.Accent)
                }
            }
        }
    }
}
