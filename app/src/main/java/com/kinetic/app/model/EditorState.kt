package com.kinetic.app.model

import android.graphics.Matrix
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kinetic.app.data.AppSettings
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** One open composition. A new instance is always empty. */
class EditorState(val settings: AppSettings, val projectId: String = UUID.randomUUID().toString(), var projectName: String = "Untitled") {
    var resolution by mutableIntStateOf(settings.resolution)
    private var baseDuration by mutableFloatStateOf(settings.compSeconds.toFloat())
    val duration: Float get() = maxOf(baseDuration, layers.maxOfOrNull { it.end } ?: 0f, 0.1f)
    var layers by mutableStateOf<List<Layer>>(emptyList())
        private set
    var time by mutableFloatStateOf(0f)
    var playing by mutableStateOf(false)
    var selectedId by mutableStateOf<String?>(null)
    var selectedProp by mutableStateOf<PropId?>(null)
    var selectedKey by mutableIntStateOf(-1)
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    private val undoStack = ArrayDeque<List<Layer>>()
    private val redoStack = ArrayDeque<List<Layer>>()

    val fps: Int get() = settings.fps
    val frame: Float get() = 1f / settings.fps
    val selected: Layer? get() = layers.firstOrNull { it.id == selectedId }
    fun restoreProject(savedDuration: Float, savedResolution: Int, savedLayers: List<Layer>, savedTime: Float = 0f) {
        baseDuration = maxOf(savedDuration, savedLayers.maxOfOrNull { it.end } ?: 0f, 0.1f)
        resolution = savedResolution.coerceIn(0, 3)
        time = savedTime.coerceIn(0f, duration)
        layers = savedLayers.map(::syncPositionKeyframes)
        selectedId = null; selectedProp = null; selectedKey = -1
        undoStack.clear(); redoStack.clear(); sync()
    }

    // ---------- history ----------
    fun beginEdit() {
        undoStack.addLast(layers)
        if (undoStack.size > 100) undoStack.removeFirst()
        redoStack.clear()
        sync()
    }

    private fun sync() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.addLast(layers)
        layers = undoStack.removeLast()
        fixSelection()
        sync()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.addLast(layers)
        layers = redoStack.removeLast()
        fixSelection()
        sync()
    }

    private fun fixSelection() {
        if (layers.none { it.id == selectedId }) clearSelection()
    }

    private fun clearSelection() {
        selectedId = null; selectedProp = null; selectedKey = -1
    }

    private fun commit(f: (List<Layer>) -> List<Layer>) { beginEdit(); layers = f(layers) }
    private fun live(f: (List<Layer>) -> List<Layer>) { layers = f(layers) }

    /** Position X and Y keep independent values, but share one set of keyframe times. */
    private fun syncPositionKeyframes(layer: Layer): Layer {
        val x = layer.p(PropId.POS_X)
        val y = layer.p(PropId.POS_Y)
        val times = (x.keys.map { it.t } + y.keys.map { it.t }).sorted()
            .fold(mutableListOf<Float>()) { acc, t ->
                if (acc.none { abs(it - t) <= frame / 2f }) acc.add(t)
                acc
            }
        if (times.isEmpty()) return layer
        fun align(prop: Prop): Prop = prop.copy(keys = times.map { t ->
            prop.keys.minByOrNull { abs(it.t - t) }?.takeIf { abs(it.t - t) <= frame / 2f }
                ?: Keyframe(t, prop.valueAt(t))
        })
        return layer.copy(props = layer.props + mapOf(
            PropId.POS_X to align(x),
            PropId.POS_Y to align(y)
        ))
    }

    fun updateLayer(id: String, push: Boolean = true, f: (Layer) -> Layer) {
        val fn: (List<Layer>) -> List<Layer> = { ls -> ls.map { if (it.id == id) f(it) else it } }
        if (push) commit(fn) else live(fn)
    }

    // ---------- selection / time ----------
    fun select(id: String?) {
        selectedId = id; selectedProp = null; selectedKey = -1
    }

    fun selectProp(id: PropId?) { selectedProp = id; selectedKey = -1 }


    fun selectKey(prop: PropId, index: Int, seekTo: Float?) {
        selectedProp = prop; selectedKey = index
        if (seekTo != null) time = seekTo.coerceIn(0f, duration)
    }

    fun seek(t: Float) { val target = t.coerceAtLeast(0f); if (target > duration) baseDuration = target; time = target }


    fun seekSnap(t: Float, windowSec: Float) {
        val raw = t.coerceAtLeast(0f)
        if (raw > duration) baseDuration = raw
        if (settings.snap) {
            val times = selected?.props?.values?.flatMap { p -> p.keys.map { it.t } } ?: emptyList()
            val near = times.minByOrNull { abs(it - raw) }
            if (near != null && abs(near - raw) <= windowSec) { time = near; return }
        }
        time = (raw * fps).roundToInt() / fps.toFloat()
    }

    // ---------- layers ----------
    private fun remaining(): Float = (duration - time).coerceAtLeast(1f)
    private fun nextName(base: String): String = "$base ${layers.count { it.name.startsWith(base) } + 1}"

    private fun addLayer(l: Layer) {
        commit { listOf(l) + it }
        select(l.id)
    }

    fun addNull() = addLayer(newLayer(LayerType.NULL, nextName("Null"), time, remaining(), 100f, 100f))
    fun addAdjustment() =
        addLayer(newLayer(LayerType.ADJUSTMENT, nextName("Adjustment Layer"), time, remaining(), COMP_W, COMP_H))
    fun addText() = addLayer(
        newLayer(LayerType.TEXT, "Text", time, remaining(), 800f, 200f, text = "Text", color = 0xFFFFFFFF.toInt())
    )
    fun addShape(
        shapeName: String = "Rectangle",
        points: List<VectorPoint> = listOf(VectorPoint(0f, 0f), VectorPoint(1f, 0f), VectorPoint(1f, 1f), VectorPoint(0f, 1f))
    ) {
        val base = newLayer(LayerType.SHAPE, nextName(shapeName), time, remaining(), 400f, 400f)
        val pointProps = mutableMapOf<PropId, Prop>()
        points.forEachIndexed { index, point ->
            PropId.vectorPoint(index, 'X')?.let { pointProps[it] = Prop(point.x) }
            PropId.vectorPoint(index, 'Y')?.let { pointProps[it] = Prop(point.y) }
        }
        val layer = base.copy(shapeKind = shapeName, vectorPoints = points, props = base.props + pointProps)
        addLayer(layer)
    }

    fun updateShapePoint(layerId: String, index: Int, x: Float? = null, y: Float? = null) {
        if (x != null) selectProp(PropId.vectorPoint(index, 'X'))
        if (y != null) selectProp(PropId.vectorPoint(index, 'Y'))
        val t = time
        val eps = frame / 2f
        updateLayer(layerId, push = false) { layer ->
            val currentPoints = layer.vectorPoints.ifEmpty {
                listOf(VectorPoint(0f, 0f), VectorPoint(1f, 0f), VectorPoint(1f, 1f), VectorPoint(0f, 1f))
            }
            if (layer.type != LayerType.SHAPE || index !in currentPoints.indices) layer
            else {
                val old = currentPoints[index]
                var nextProps = layer.props
                x?.let { value ->
                    val id = PropId.vectorPoint(index, 'X')
                    if (id != null) nextProps = nextProps + (id to (nextProps[id] ?: Prop(old.x)).withValueAt(t, value.coerceIn(0f, 1f), eps))
                }
                y?.let { value ->
                    val id = PropId.vectorPoint(index, 'Y')
                    if (id != null) nextProps = nextProps + (id to (nextProps[id] ?: Prop(old.y)).withValueAt(t, value.coerceIn(0f, 1f), eps))
                }
                val baseX = PropId.vectorPoint(index, 'X')?.let { nextProps[it]?.base } ?: old.x
                val baseY = PropId.vectorPoint(index, 'Y')?.let { nextProps[it]?.base } ?: old.y
                layer.copy(
                    props = nextProps,
                    vectorPoints = currentPoints.toMutableList().also { points ->
                        points[index] = VectorPoint(baseX, baseY)
                    }
                )
            }
        }
    }

    // ---------- masks ----------
    fun createMask(layerId: String, kind: String) {
        updateLayer(layerId) { layer ->
            layer.copy(maskEnabled = true, maskPath = LayerMask(kind = kind))
        }
    }

    fun updateMask(layerId: String, mask: LayerMask, push: Boolean = false) {
        updateLayer(layerId, push = push) { layer ->
            layer.copy(maskEnabled = true, maskPath = mask)
        }
    }

    fun addMaskPoint(layerId: String, point: VectorPoint) {
        updateLayer(layerId) { layer ->
            val mask = layer.maskPath ?: LayerMask(kind = "Pen")
            layer.copy(maskEnabled = true, maskPath = mask.copy(
                kind = "Pen",
                points = (mask.points + point).take(128),
                closed = false
            ))
        }
    }

    fun addMaskStroke(layerId: String, stroke: MaskStroke) {
        if (stroke.points.size < 2) return
        updateLayer(layerId) { layer ->
            val mask = layer.maskPath ?: LayerMask(kind = "Draw")
            layer.copy(maskEnabled = true, maskPath = mask.copy(
                kind = mask.kind,
                strokes = (mask.strokes + stroke).takeLast(120)
            ))
        }
    }

    fun clearMask(layerId: String) {
        updateLayer(layerId) { it.copy(maskEnabled = false, maskPath = null) }
    }

    fun addMedia(type: LayerType, name: String, uri: String, w: Int, h: Int, length: Float?) {
        val lw: Float
        val lh: Float
        if (type == LayerType.AUDIO) {
            lw = 0f; lh = 0f
        } else if (w <= 0 || h <= 0) {
            lw = COMP_W; lh = COMP_H
        } else {
            val s = minOf(COMP_W / w, COMP_H / h)
            lw = w * s; lh = h * s
        }
        val isTimed = type == LayerType.VIDEO || type == LayerType.AUDIO
        val dur = if (isTimed) (length ?: 5f).coerceAtLeast(0.1f) else remaining()
        val src = if (isTimed) dur else Float.POSITIVE_INFINITY
        addLayer(newLayer(type, name, time, dur, lw, lh, uri, src))
    }

    fun extractAudio(id: String) {
        val source = layers.firstOrNull { it.id == id } ?: return
        if (source.type != LayerType.VIDEO || source.uri == null) return
        val audio = newLayer(LayerType.AUDIO, nextName("Extracted Audio"), source.start,
            source.duration, 0f, 0f, source.uri, source.srcLength).let { layer ->
            layer.copy(
                srcOffset = source.srcOffset,
                props = layer.props + mapOf(
                    PropId.SPEED to source.p(PropId.SPEED),
                    PropId.VOLUME to source.p(PropId.VOLUME)
                )
            )
        }
        commit { layers ->
            listOf(audio) + layers.map { layer ->
                if (layer.id == source.id) layer.copy(
                    volume = 0f,
                    props = layer.props + (PropId.VOLUME to Prop(0f))
                ) else layer
            }
        }
        select(audio.id)
    }

    fun addMediaTrimmed(type: LayerType, name: String, uri: String, w: Int, h: Int, sourceLength: Float?, trimStart: Float, trimEnd: Float) {
        val startOffset = trimStart.coerceAtLeast(0f)
        val endOffset = trimEnd.coerceAtLeast(startOffset + 0.1f)
        val duration = (endOffset - startOffset).coerceAtLeast(0.1f)
        val layerWidth: Float
        val layerHeight: Float
        if (type == LayerType.AUDIO) {
            layerWidth = 0f; layerHeight = 0f
        } else if (w <= 0 || h <= 0) {
            layerWidth = COMP_W; layerHeight = COMP_H
        } else {
            val scale = minOf(COMP_W / w, COMP_H / h)
            layerWidth = w * scale; layerHeight = h * scale
        }
        val layer = newLayer(type, name, time, duration, layerWidth, layerHeight, uri,
            sourceLength ?: Float.POSITIVE_INFINITY).copy(srcOffset = if (type == LayerType.IMAGE) 0f else startOffset)
        addLayer(layer)
    }

    fun deleteLayer(id: String) {
        commit { ls -> ls.filter { it.id != id }.map { if (it.parentId == id) it.copy(parentId = null) else it } }
        fixSelection()
    }

    fun duplicate(id: String) {
        val l = layers.firstOrNull { it.id == id } ?: return
        val c = l.copy(id = UUID.randomUUID().toString(), name = l.name + " copy")
        commit { ls -> ls.flatMap { if (it.id == id) listOf(c, it) else listOf(it) } }
        select(c.id)
    }

    fun splitSelected() {
        val l = selected ?: return
        val t = time
        if (t <= l.start + frame || t >= l.end - frame) return
        val a = l.copy(duration = t - l.start)
        val b = l.copy(
            id = UUID.randomUUID().toString(), start = t,
            duration = l.end - t, srcOffset = l.srcOffset + (t - l.start)
        )
        commit { ls -> ls.flatMap { if (it.id == l.id) listOf(b, a) else listOf(it) } }
        select(b.id)
    }

    fun toggleVisible(id: String) = updateLayer(id) { it.copy(visible = !it.visible) }

    fun toggleScaleLink(id: String) = updateLayer(id) { layer ->
        val linked = !layer.scaleLinked
        if (linked) layer.copy(scaleLinked = true, props = layer.props + (PropId.SCALE_Y to layer.p(PropId.SCALE_X)))
        else layer.copy(scaleLinked = false)
    }

    fun renameLayer(id: String, name: String) = updateLayer(id) { it.copy(name = name) }

    fun setText(id: String, text: String) = updateLayer(id) { it.copy(text = text, name = text.ifBlank { "Text" }) }

    /** Reorder without snapshot (call beginEdit() at gesture start). dir = +1 moves down, -1 up. */
    fun moveLayer(id: String, dir: Int) {
        val i = layers.indexOfFirst { it.id == id }
        val j = i + dir
        if (i < 0 || j !in layers.indices) return
        val m = layers.toMutableList()
        val tmp = m[i]; m[i] = m[j]; m[j] = tmp
        layers = m
    }

    fun moveClip(id: String, dt: Float) =
        updateLayer(id, push = false) { it.copy(start = max(0f, it.start + dt)) }

    fun trimRight(id: String, dt: Float) = updateLayer(id, push = false) { l ->
        l.copy(duration = (l.duration + dt).coerceIn(0.1f, max(0.1f, l.srcLength - l.srcOffset)))
    }

    fun trimLeft(id: String, dt: Float, push: Boolean = true) = updateLayer(id, push) { l ->
        val lo = max(-l.srcOffset, -l.start)
        val hi = l.duration - 0.1f
        val d = dt.coerceIn(lo, max(lo, hi))
        l.copy(start = l.start + d, duration = l.duration - d, srcOffset = l.srcOffset + d)
    }

    /**
     * Move the selected clip so either its start or end aligns with the playhead.
     * When aligning the end before time zero, shorten only as much as needed to
     * keep the clip inside the composition.
     */
    fun bringLayerToPlayhead(id: String, alignEnd: Boolean) {
        val l = layers.firstOrNull { it.id == id } ?: return
        val target = time.coerceIn(0f, duration)
        if (alignEnd && target < frame) return
        val nextDuration = if (alignEnd) minOf(l.duration, target) else l.duration
        val nextStart = if (alignEnd) target - nextDuration else target
        updateLayer(id) { it.copy(start = nextStart.coerceAtLeast(0f), duration = nextDuration) }
    }

    fun moveLayerStartToPlayhead(id: String) {
        val l = layers.firstOrNull { it.id == id } ?: return
        val target = time.coerceIn(0f, duration)
        if (target >= l.end - frame) return
        val delta = target - l.start
        val maxSource = if (l.srcLength.isFinite()) (l.srcLength - l.srcOffset).coerceAtLeast(frame) else Float.POSITIVE_INFINITY
        val nextDuration = minOf(l.duration - delta, maxSource).coerceAtLeast(frame)
        updateLayer(id) { it.copy(start = target, duration = nextDuration, srcOffset = (it.srcOffset + delta).coerceAtLeast(0f)) }
    }

    fun moveLayerEndToPlayhead(id: String) {
        val l = layers.firstOrNull { it.id == id } ?: return
        val target = time.coerceAtLeast(l.start + frame)
        val requested = target - l.start
        val maxDuration = if (l.srcLength.isFinite()) (l.srcLength - l.srcOffset).coerceAtLeast(frame) else Float.POSITIVE_INFINITY
        updateLayer(id) { it.copy(duration = requested.coerceIn(frame, maxDuration)) }
    }

    fun extendLayerToPlayhead(id: String) {
        val l = layers.firstOrNull { it.id == id } ?: return
        val target = time.coerceIn(0f, duration)
        when {
            target > l.end + frame -> {
                val maxDuration = if (l.srcLength.isFinite()) {
                    (l.srcLength - l.srcOffset).coerceAtLeast(l.duration)
                } else {
                    (duration - l.start).coerceAtLeast(l.duration)
                }
                val nextDuration = (target - l.start).coerceIn(l.duration, maxDuration)
                updateLayer(id) { it.copy(duration = nextDuration) }
            }
            target < l.start - frame -> {
                // Extend left only through source media that exists before the current in-point.
                val availableBefore = if (l.srcLength.isFinite()) l.srcOffset.coerceAtLeast(0f) else l.start
                val extendBy = minOf(l.start - target, availableBefore, l.start)
                if (extendBy <= 0f) return
                updateLayer(id) {
                    it.copy(
                        start = it.start - extendBy,
                        duration = it.duration + extendBy,
                        srcOffset = if (it.srcLength.isFinite()) (it.srcOffset - extendBy).coerceAtLeast(0f) else it.srcOffset
                    )
                }
            }
        }
    }

    /** Parenting keeps the child where it is on screen (position only, if position isn't keyframed). */
    fun setParent(childId: String, parentId: String?) {
        val byId = layers.associateBy { it.id }
        commit { ls ->
            ls.map { l ->
                if (l.id != childId) l else {
                    var nl = l.copy(parentId = parentId)
                    val px = l.p(PropId.POS_X)
                    val py = l.p(PropId.POS_Y)
                    if (!px.animated && !py.animated) {
                        val a = floatArrayOf(
                            l.p(PropId.ANCHOR_X).valueAt(time), l.p(PropId.ANCHOR_Y).valueAt(time)
                        )
                        layerMatrix(l, time, byId).mapPoints(a)
                        val pl = parentId?.let { byId[it] }
                        if (pl != null) {
                            val pm = layerMatrix(pl, time, byId)
                            pm.preTranslate(pl.p(PropId.ANCHOR_X).valueAt(time), pl.p(PropId.ANCHOR_Y).valueAt(time))
                            val inv = Matrix()
                            if (pm.invert(inv)) inv.mapPoints(a)
                        }
                        nl = nl.copy(
                            props = l.props + mapOf(
                                PropId.POS_X to px.copy(base = a[0]),
                                PropId.POS_Y to py.copy(base = a[1])
                            )
                        )
                    }
                    nl
                }
            }
        }
    }

    // ---------- properties / keyframes ----------
    /** Live change (no snapshot). Call beginEdit() once at the start of a scrub. */
    /** Live change (no snapshot). Call beginEdit() once at the start of a scrub. */
    fun setPropValue(layerId: String, id: PropId, value: Float) {
        val v = when {
            id == PropId.OPACITY -> value.coerceIn(0f, 100f)
            id.isVectorPoint -> value.coerceIn(0f, 1f)
            else -> value
        }
        val t = time
        val eps = frame / 2f
        updateLayer(layerId, push = false) { l ->
            var next = l.props + (id to l.p(id).withValueAt(t, v, eps))

            if (l.scaleLinked && (id == PropId.SCALE_X || id == PropId.SCALE_Y)) {
                val other = if (id == PropId.SCALE_X) PropId.SCALE_Y else PropId.SCALE_X
                next = next + (other to l.p(other).withValueAt(t, v, eps))
            }
            val result = l.copy(props = next)
            if (id == PropId.POS_X || id == PropId.POS_Y) syncPositionKeyframes(result) else result
        }
    }

    fun toggleStopwatch(layerId: String, id: PropId) {
        val t = time
        updateLayer(layerId) { l ->
            if (id == PropId.POS_X || id == PropId.POS_Y) {
                val x = l.p(PropId.POS_X)
                val y = l.p(PropId.POS_Y)
                if (l.p(id).animated) {
                    l.copy(props = l.props + mapOf(
                        PropId.POS_X to Prop(x.valueAt(t)),
                        PropId.POS_Y to Prop(y.valueAt(t))
                    ))
                } else {
                    fun addKey(prop: Prop): Prop {
                        if (prop.keys.any { abs(it.t - t) <= frame / 2f }) return prop
                        return prop.copy(keys = (prop.keys + Keyframe(t, prop.valueAt(t))).sortedBy { it.t })
                    }
                    syncPositionKeyframes(l.copy(props = l.props + mapOf(
                        PropId.POS_X to addKey(x),
                        PropId.POS_Y to addKey(y)
                    )))
                }
            } else {
                val p = l.p(id)
                val np = if (p.animated) Prop(p.valueAt(t)) else Prop(p.base, listOf(Keyframe(t, p.base)))
                var next = l.props + (id to np)
                if (l.scaleLinked && (id == PropId.SCALE_X || id == PropId.SCALE_Y)) {
                    val other = if (id == PropId.SCALE_X) PropId.SCALE_Y else PropId.SCALE_X
                    next = next + (other to np)
                }
                l.copy(props = next)
            }
        }
        selectedKey = -1
    }

    fun toggleKeyAtTime(layerId: String, id: PropId) {
        val t = time
        val eps = frame / 2f
        updateLayer(layerId) { l ->
            fun toggleAt(prop: Prop): Prop {
                val idx = prop.keys.indexOfFirst { abs(it.t - t) <= eps }
                return if (idx >= 0) {
                    val remaining = prop.keys.filterIndexed { i, _ -> i != idx }
                    if (remaining.isEmpty()) Prop(prop.keys[idx].v) else prop.copy(keys = remaining)
                } else prop.copy(keys = (prop.keys + Keyframe(t, prop.valueAt(t))).sortedBy { it.t })
            }
            if (id == PropId.POS_X || id == PropId.POS_Y) {
                val x = l.p(PropId.POS_X)
                val y = l.p(PropId.POS_Y)
                val exists = x.keys.any { abs(it.t - t) <= eps } || y.keys.any { abs(it.t - t) <= eps }
                if (exists) {
                    fun removeAt(prop: Prop): Prop {
                        val remaining = prop.keys.filterNot { abs(it.t - t) <= eps }
                        return if (remaining.isEmpty()) Prop(prop.keys.firstOrNull()?.v ?: prop.base)
                            else prop.copy(keys = remaining)
                    }
                    l.copy(props = l.props + mapOf(
                        PropId.POS_X to removeAt(x),
                        PropId.POS_Y to removeAt(y)
                    ))
                } else {
                    syncPositionKeyframes(l.copy(props = l.props + mapOf(
                        PropId.POS_X to toggleAt(x),
                        PropId.POS_Y to toggleAt(y)
                    )))
                }
            } else {
                val np = toggleAt(l.p(id))
                var next = l.props + (id to np)
                if (l.scaleLinked && (id == PropId.SCALE_X || id == PropId.SCALE_Y)) {
                    val other = if (id == PropId.SCALE_X) PropId.SCALE_Y else PropId.SCALE_X
                    next = next + (other to np)
                }
                l.copy(props = next)
            }
        }
        selectedKey = -1
    }

    fun jumpKey(layerId: String, id: PropId, dir: Int) {
        val p = layers.firstOrNull { it.id == layerId }?.p(id) ?: return
        val eps = frame / 2f
        val target = if (dir > 0) p.keys.firstOrNull { it.t > time + eps } else p.keys.lastOrNull { it.t < time - eps }
        if (target != null) selectKey(id, p.keys.indexOf(target), target.t)
    }

    fun replaceKey(layerId: String, id: PropId, index: Int, key: Keyframe, push: Boolean) {
        updateLayer(layerId, push) { l ->
            val p = l.p(id)
            if (index !in p.keys.indices) l
            else {
                val oldKey = p.keys[index]
                val updated = p.copy(keys = p.keys.toMutableList().also { it[index] = key }.sortedBy { it.t })
                var next = l.props + (id to updated)

                if (l.scaleLinked && (id == PropId.SCALE_X || id == PropId.SCALE_Y)) {
                    val other = if (id == PropId.SCALE_X) PropId.SCALE_Y else PropId.SCALE_X
                    next = next + (other to updated)
                }
                if (id == PropId.POS_X || id == PropId.POS_Y) {
                    val other = if (id == PropId.POS_X) PropId.POS_Y else PropId.POS_X
                    val otherProp = l.p(other)
                    val pairedIndex = otherProp.keys.indexOfFirst { abs(it.t - oldKey.t) <= frame / 2f }
                    val otherKeys = otherProp.keys.toMutableList()
                    val paired = if (pairedIndex >= 0) otherKeys.removeAt(pairedIndex)
                        else Keyframe(oldKey.t, otherProp.valueAt(oldKey.t))
                    otherKeys.add(paired.copy(t = key.t, type = key.type,
                        x1 = key.x1, y1 = key.y1, x2 = key.x2, y2 = key.y2))
                    next = next + (other to otherProp.copy(keys = otherKeys.sortedBy { it.t }))
                }
                val result = l.copy(props = next)
                if (id == PropId.POS_X || id == PropId.POS_Y) syncPositionKeyframes(result) else result
            }
        }
    }

    /** Move a keyframe in time only; linked Scale dimensions move their paired key too. */
    fun moveKeyTime(layerId: String, id: PropId, index: Int, time: Float) {
        val l = layers.firstOrNull { it.id == layerId } ?: return
        val keys = l.p(id).keys
        if (index !in keys.indices) return
        val previous = if (index > 0) keys[index - 1].t + frame else 0f
        val nextTime = if (index < keys.lastIndex) keys[index + 1].t - frame else duration
        val original = keys[index]
        val moved = original.copy(t = time.coerceIn(previous, max(previous, nextTime)))
        val sorted = keys.toMutableList().also { it[index] = moved }.sortedBy { it.t }
        updateLayer(layerId, push = false) { layer ->
            val updated = layer.p(id).copy(keys = sorted)
            var next = layer.props + (id to updated)

            if (layer.scaleLinked && (id == PropId.SCALE_X || id == PropId.SCALE_Y)) {
                val other = if (id == PropId.SCALE_X) PropId.SCALE_Y else PropId.SCALE_X
                next = next + (other to updated)
            }
            if (id == PropId.POS_X || id == PropId.POS_Y) {
                val other = if (id == PropId.POS_X) PropId.POS_Y else PropId.POS_X
                val otherProp = layer.p(other)
                val pairedIndex = otherProp.keys.indexOfFirst { abs(it.t - original.t) <= frame / 2f }
                if (pairedIndex >= 0) {
                    val pairedKeys = otherProp.keys.toMutableList()
                    val paired = pairedKeys.removeAt(pairedIndex)
                    pairedKeys.add(paired.copy(t = moved.t, type = moved.type,
                        x1 = moved.x1, y1 = moved.y1, x2 = moved.x2, y2 = moved.y2))
                    next = next + (other to otherProp.copy(keys = pairedKeys.sortedBy { it.t }))
                }
            }
            val result = layer.copy(props = next)
            if (id == PropId.POS_X || id == PropId.POS_Y) syncPositionKeyframes(result) else result
        }
        selectedKey = sorted.indexOf(moved)
    }

    fun setExpression(layerId: String, id: PropId, expression: String?) {
        updateLayer(layerId) { l -> l.copy(props = l.props + (id to l.p(id).copy(expression = expression))) }
    }

    fun deleteKey(layerId: String, id: PropId, index: Int) {
        updateLayer(layerId) { l ->
            val p = l.p(id)
            if (index !in p.keys.indices) l else {
                val removed = p.keys[index]
                val remaining = p.keys.filterIndexed { i, _ -> i != index }
                val np = if (remaining.isEmpty()) Prop(removed.v) else p.copy(keys = remaining)
                var next = l.props + (id to np)

                if (l.scaleLinked && (id == PropId.SCALE_X || id == PropId.SCALE_Y)) {
                    val other = if (id == PropId.SCALE_X) PropId.SCALE_Y else PropId.SCALE_X
                    next = next + (other to np)
                }
                if (id == PropId.POS_X || id == PropId.POS_Y) {
                    val other = if (id == PropId.POS_X) PropId.POS_Y else PropId.POS_X
                    val otherProp = l.p(other)
                    val otherKeys = otherProp.keys.filterNot { abs(it.t - removed.t) <= frame / 2f }
                    val otherUpdated = if (otherKeys.isEmpty() && otherProp.keys.isNotEmpty())
                        Prop(otherProp.valueAt(removed.t)) else otherProp.copy(keys = otherKeys)
                    next = next + (other to otherUpdated)
                }
                val result = l.copy(props = next)
                if (id == PropId.POS_X || id == PropId.POS_Y) syncPositionKeyframes(result) else result
            }
        }
        selectedKey = -1
    }}
