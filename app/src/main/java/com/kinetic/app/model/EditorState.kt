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

data class TimelineTab(val path: List<String>, val name: String)

private data class TimelineFrame(val precompId: String, val parentLayers: List<Layer>)

/** One open composition. A new instance is always empty. */
class EditorState(val settings: AppSettings, val projectId: String = UUID.randomUUID().toString(), var projectName: String = "Untitled") {
    var compSettings by mutableStateOf(settings.lastComp)
        private set
    var resolution by mutableIntStateOf(1)
    private var baseDuration by mutableFloatStateOf(settings.lastComp.duration)
    init {
        COMP_W = compSettings.width.toFloat()
        COMP_H = compSettings.height.toFloat()
    }
    /** Duration is always measured against the root composition, including while editing a nested pre-comp. */
    val duration: Float get() = maxOf(baseDuration, projectLayers.maxOfOrNull { it.end } ?: 0f, 0.1f)
    var layers by mutableStateOf<List<Layer>>(emptyList())
        private set
    private val timelineFrames = mutableListOf<TimelineFrame>()
    var timelinePath by mutableStateOf<List<String>>(emptyList())
        private set
    /** Root project layers, including edits made while nested pre-comps are open. */
    val projectLayers: List<Layer>
        get() {
            var current = layers
            timelineFrames.asReversed().forEach { frame ->
                current = frame.parentLayers.map { layer ->
                    if (layer.id == frame.precompId) layer.copy(precompLayers = current) else layer
                }
            }
            return current
        }
    /** Flattened visual stack: pre-comp children render in the parent stack's position. */
    val previewLayers: List<Layer>
        get() = flattenPrecomps(layers)
    val projectPreviewLayers: List<Layer>
        get() = flattenPrecomps(projectLayers)
    val timelineTabs: List<TimelineTab>
        get() {
            val result = mutableListOf(TimelineTab(emptyList(), projectName))
            fun collect(items: List<Layer>, parent: List<String>) {
                items.filter { it.type == LayerType.PRECOMP }.forEach { layer ->
                    val path = parent + layer.id
                    result.add(TimelineTab(path, layer.name))
                    collect(layer.precompLayers, path)
                }
            }
            collect(projectLayers, emptyList())
            return result
        }
    var time by mutableFloatStateOf(0f)
    var playing by mutableStateOf(false)
    var beatMarkers by mutableStateOf<List<Float>>(emptyList())
        private set
    var selectedId by mutableStateOf<String?>(null)
    var selectedProp by mutableStateOf<PropId?>(null)
    var selectedKey by mutableIntStateOf(-1)
    var selectedEffectId by mutableStateOf<String?>(null)
        private set
    var selectedEffectParameter by mutableStateOf<String?>(null)
        private set
    var selectedEffectKey by mutableIntStateOf(-1)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    private val undoStack = ArrayDeque<List<Layer>>()
    private val redoStack = ArrayDeque<List<Layer>>()

    val fps: Int get() = compSettings.fps
    val frame: Float get() = 1f / compSettings.fps
    val selected: Layer? get() = layers.firstOrNull { it.id == selectedId }
    fun applyComp(cs: CompSettings) {
        val c = cs.sanitized()
        compSettings = c
        projectName = c.name
        baseDuration = c.duration
        COMP_W = c.width.toFloat()
        COMP_H = c.height.toFloat()
        time = time.coerceIn(0f, duration)
    }

    fun restoreProject(
        savedDuration: Float,
        savedResolution: Int,
        savedLayers: List<Layer>,
        savedTime: Float = 0f,
        savedComp: CompSettings? = null,
        savedBeatMarkers: List<Float> = emptyList()
    ) {
        if (savedComp != null) {
            val c = savedComp.sanitized()
            compSettings = c
            COMP_W = c.width.toFloat()
            COMP_H = c.height.toFloat()
        }
        baseDuration = maxOf(savedDuration, savedLayers.maxOfOrNull { it.end } ?: 0f, 0.1f)
        resolution = savedResolution.coerceIn(0, 3)
        time = savedTime.coerceIn(0f, duration)
        timelineFrames.clear()
        timelinePath = emptyList()
        layers = savedLayers.map(::syncPositionKeyframes)
        beatMarkers = savedBeatMarkers.filter { it.isFinite() && it >= 0f }.distinct().sorted()
        selectedId = null; selectedProp = null; selectedKey = -1
        selectedEffectId = null; selectedEffectParameter = null; selectedEffectKey = -1
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
        val effectStillExists = selectedEffectId?.let { effectId ->
            layers.any { layer -> layer.effectStack.any { it.id == effectId } }
        } ?: true
        if (!effectStillExists) {
            selectedEffectId = null
            selectedEffectParameter = null
            selectedEffectKey = -1
        }
    }

    private fun clearSelection() {
        selectedId = null; selectedProp = null; selectedKey = -1
        selectedEffectId = null; selectedEffectParameter = null; selectedEffectKey = -1
    }

    private fun commit(f: (List<Layer>) -> List<Layer>) { beginEdit(); layers = f(layers) }
    private fun live(f: (List<Layer>) -> List<Layer>) { layers = f(layers) }

    /** Position X/Y/Z retain independent values and easing but share keyframe times. */
    private val positionAxes = listOf(PropId.POS_X, PropId.POS_Y, PropId.POS_Z)
    private fun isPositionAxis(id: PropId): Boolean = id in positionAxes

    private fun syncPositionKeyframes(layer: Layer, template: Keyframe? = null): Layer {
        val axes = positionAxes.associateWith { layer.p(it) }
        val times = axes.values.flatMap { it.keys }.map { it.t }.sorted()
            .fold(mutableListOf<Float>()) { acc, t ->
                if (acc.none { abs(it - t) <= frame / 2f }) acc.add(t)
                acc
            }
        if (times.isEmpty()) return layer
        val aligned = axes.mapValues { (_, prop) ->
            prop.copy(keys = times.map { t ->
                prop.keys.minByOrNull { abs(it.t - t) }?.takeIf { abs(it.t - t) <= frame / 2f }
                    ?: template?.copy(t = t, v = prop.valueAt(t))
                    ?: Keyframe(t, prop.valueAt(t))
            })
        }
        return layer.copy(props = layer.props + aligned)
    }

    private fun updatePositionKeyGroup(
        layer: Layer,
        oldTime: Float,
        replacement: Keyframe? = null,
        activeAxis: PropId = PropId.POS_X
    ): Layer {
        val eps = frame / 2f
        val next = positionAxes.associate { id ->
            val prop = layer.p(id)
            val index = prop.keys.indexOfFirst { abs(it.t - oldTime) <= eps }
            val keys = prop.keys.toMutableList()
            if (replacement == null) {
                if (index >= 0) keys.removeAt(index)
            } else {
                val current = if (index >= 0) keys.removeAt(index) else null
                val value = current?.v ?: prop.valueAt(replacement.t)
                keys.add(replacement.copy(v = if (id == activeAxis) replacement.v else value))
            }
            id to prop.copy(keys = keys.sortedBy { it.t })
        }
        return syncPositionKeyframes(layer.copy(props = layer.props + next), replacement)
    }


    fun updateLayer(id: String, push: Boolean = true, f: (Layer) -> Layer) {
        val fn: (List<Layer>) -> List<Layer> = { ls -> ls.map { if (it.id == id) f(it) else it } }
        if (push) commit(fn) else live(fn)
    }

    fun toggleLayer3D(id: String) {
        updateLayer(id) { it.copy(is3D = !it.is3D) }
    }

    fun toggleMotionBlur(id: String) {
        updateLayer(id) { it.copy(motionBlur = !it.motionBlur) }
    }

    /** Wrap the selected layer in a new pre-comp container without changing its visual timing. */
    fun precomposeSelected(): Boolean {
        val child = selected ?: return false
        if (child.type == LayerType.PRECOMP) return false
        // The pre-comp occupies exactly the selected clip's timeline range.
        // Its child retains its original source in/out points and playback speed.
        val precomp = newLayer(
            LayerType.PRECOMP, child.name + " Pre-comp", child.start, child.duration,
            COMP_W, COMP_H
        ).copy(precompLayers = listOf(child))
        commit { current -> current.map { if (it.id == child.id) precomp else it } }
        select(precomp.id)
        return true
    }

    fun enterPrecomp(id: String): Boolean {
        val target = layers.firstOrNull { it.id == id && it.type == LayerType.PRECOMP } ?: return false
        timelineFrames.add(TimelineFrame(id, layers))
        layers = target.precompLayers
        timelinePath = timelinePath + id
        undoStack.clear(); redoStack.clear(); sync()
        clearSelection()
        time = time.coerceIn(0f, duration)
        return true
    }

    fun exitPrecomp(): Boolean {
        val frame = timelineFrames.removeLastOrNull() ?: return false
        layers = frame.parentLayers.map { layer ->
            if (layer.id == frame.precompId) layer.copy(precompLayers = layers) else layer
        }
        timelinePath = timelinePath.dropLast(1)
        undoStack.clear(); redoStack.clear(); sync()
        clearSelection()
        time = time.coerceIn(0f, duration)
        return true
    }

    fun navigateTimeline(path: List<String>) {
        while (timelineFrames.isNotEmpty()) exitPrecomp()
        path.forEach { id -> if (!enterPrecomp(id)) return }
    }

    // ---------- selection / time ----------
    fun select(id: String?) {
        selectedId = id; selectedProp = null; selectedKey = -1
        selectedEffectId = null; selectedEffectParameter = null; selectedEffectKey = -1
    }

    fun selectProp(id: PropId?) {
        selectedProp = id; selectedKey = -1
        selectedEffectId = null; selectedEffectParameter = null; selectedEffectKey = -1
    }

    fun selectEffectParameter(layerId: String, effectId: String, parameter: String) {
        if (layers.none { layer -> layer.id == layerId && layer.effectStack.any { it.id == effectId } }) return
        selectedId = layerId
        selectedProp = null
        selectedKey = -1
        selectedEffectId = effectId
        selectedEffectParameter = parameter
        selectedEffectKey = -1
    }

    fun clearEffectParameterSelection() {
        selectedEffectId = null
        selectedEffectParameter = null
        selectedEffectKey = -1
    }

    fun selectEffectKey(index: Int, seekTo: Boolean = true) {
        val effectId = selectedEffectId ?: return
        val parameter = selectedEffectParameter ?: return
        val keys = layers.firstOrNull { it.id == selectedId }
            ?.effectStack?.firstOrNull { it.id == effectId }
            ?.parameterKeys?.get(parameter).orEmpty()
        if (index < 0) {
            selectedEffectKey = -1
            return
        }
        if (index !in keys.indices) return
        selectedEffectKey = index
        if (seekTo) time = keys[index].t.coerceIn(0f, duration)
    }


    fun selectKey(prop: PropId, index: Int, seekTo: Float?) {
        selectedProp = prop; selectedKey = index
        selectedEffectId = null; selectedEffectParameter = null; selectedEffectKey = -1
        if (seekTo != null) time = seekTo.coerceIn(0f, duration)
    }

    fun seek(t: Float) { val target = t.coerceAtLeast(0f); if (target > duration) baseDuration = target; time = target }

    /** Add or remove a beat marker at the current frame-quantized playhead position. */
    fun toggleBeatMarkerAtPlayhead(): Float {
        val marker = (time / frame).roundToInt() * frame
        val existing = beatMarkers.indexOfFirst { abs(it - marker) <= frame / 2f }
        beatMarkers = if (existing >= 0) beatMarkers.filterIndexed { index, _ -> index != existing }
            else (beatMarkers + marker).distinct().sorted()
        return marker
    }

    fun previousBeatMarker(): Float? = beatMarkers.lastOrNull { it < time - frame / 2f }
    fun nextBeatMarker(): Float? = beatMarkers.firstOrNull { it > time + frame / 2f }



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

    fun addVectorLayer(): String {
        val layer = newLayer(LayerType.SHAPE, nextName("Shape"), time, remaining(), COMP_W, COMP_H, color = 0xFFFFFFFF.toInt())
            .copy(shapeKind = "Vector", vectorClosed = false)
        addLayer(layer)
        return layer.id
    }

    fun addVectorPoint(layerId: String, x: Float, y: Float, smooth: Boolean) {
        updateLayer(layerId, push = true) { layer ->
            val index = layer.vectorPoints.size
            val cx = x.coerceIn(0f, 1f)
            val cy = y.coerceIn(0f, 1f)
            var props = layer.props
            PropId.vectorPoint(index, 'X')?.let { props = props + (it to Prop(cx)) }
            PropId.vectorPoint(index, 'Y')?.let { props = props + (it to Prop(cy)) }
            val flags = layer.vectorSmooth.toMutableList()
            while (flags.size < index) flags.add(false)
            flags.add(smooth)
            layer.copy(vectorPoints = layer.vectorPoints + VectorPoint(cx, cy), vectorSmooth = flags, props = props)
        }
    }

    fun moveVectorPoint(layerId: String, index: Int, x: Float, y: Float, push: Boolean) {
        val t = time
        val eps = frame / 2f
        updateLayer(layerId, push = push) { layer ->
            if (index !in layer.vectorPoints.indices) layer else {
                val old = layer.vectorPoints[index]
                val nx = x.coerceIn(0f, 1f)
                val ny = y.coerceIn(0f, 1f)
                var props = layer.props
                val xi = PropId.vectorPoint(index, 'X')
                val yi = PropId.vectorPoint(index, 'Y')
                if (xi != null) props = props + (xi to (props[xi] ?: Prop(old.x)).withValueAt(t, nx, eps))
                if (yi != null) props = props + (yi to (props[yi] ?: Prop(old.y)).withValueAt(t, ny, eps))
                val bx = xi?.let { props[it]?.base } ?: nx
                val by = yi?.let { props[it]?.base } ?: ny
                layer.copy(props = props, vectorPoints = layer.vectorPoints.toMutableList().also { it[index] = VectorPoint(bx, by) })
            }
        }
    }

    fun removeVectorPoint(layerId: String, index: Int) {
        updateLayer(layerId, push = true) { layer ->
            if (index !in layer.vectorPoints.indices) layer else {
                val shifted = LinkedHashMap<PropId, Prop>()
                layer.props.forEach { (id, prop) ->
                    if (!id.isVectorPoint) shifted[id] = prop
                    else if (id.vectorPointIndex < index) shifted[id] = prop
                    else if (id.vectorPointIndex > index) PropId.vectorPoint(id.vectorPointIndex - 1, id.vectorPointAxis)?.let { shifted[it] = prop }
                }
                val flags = layer.vectorSmooth.toMutableList()
                if (index < flags.size) flags.removeAt(index)
                layer.copy(
                    vectorPoints = layer.vectorPoints.toMutableList().also { it.removeAt(index) },
                    vectorSmooth = flags, props = shifted
                )
            }
        }
    }

    fun setVectorSmooth(layerId: String, index: Int, smooth: Boolean) {
        updateLayer(layerId, push = true) { layer ->
            if (index !in layer.vectorPoints.indices) layer else {
                val flags = layer.vectorSmooth.toMutableList()
                while (flags.size < layer.vectorPoints.size) flags.add(false)
                flags[index] = smooth
                layer.copy(vectorSmooth = flags)
            }
        }
    }

    fun setVectorClosed(layerId: String, closed: Boolean) {
        updateLayer(layerId, push = true) { it.copy(vectorClosed = closed) }
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

    /**
     * Move a clip and its animation as one timeline object. Property and effect
     * keyframe times are composition-time values, so they must follow the clip
     * when its start position changes; otherwise the animation is left behind.
     */
    fun moveClip(id: String, dt: Float) {
        val original = layers.firstOrNull { it.id == id } ?: return
        val nextStart = max(0f, original.start + dt)
        val actualDelta = nextStart - original.start
        if (abs(actualDelta) < 0.0001f) return

        updateLayer(id, push = false) { layer ->
            val shiftedProps = layer.props.mapValues { (_, prop) ->
                if (prop.keys.isEmpty()) prop else prop.copy(
                    keys = prop.keys.map { key -> key.copy(t = (key.t + actualDelta).coerceAtLeast(0f)) }
                        .sortedBy { it.t }
                )
            }
            val shiftedEffects = layer.effectStack.map { effect ->
                if (effect.parameterKeys.isEmpty()) effect else effect.copy(
                    parameterKeys = effect.parameterKeys.mapValues { (_, keys) ->
                        keys.map { key -> key.copy(t = (key.t + actualDelta).coerceAtLeast(0f)) }
                            .sortedBy { it.t }
                    }
                )
            }
            layer.copy(start = nextStart, props = shiftedProps, effectStack = shiftedEffects)
        }
    }

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

    /** Reparent without changing the child's current world-space position or keyed motion. */
    fun setParent(childId: String, parentId: String?) {
        val byId = layers.associateBy { it.id }
        if (parentId == childId || parentId?.let { byId[it]?.let { p -> isDescendantOf(p, childId, byId) } } == true) return
        commit { ls ->
            ls.map { layer ->
                if (layer.id != childId) layer else {
                    val px = layer.p(PropId.POS_X)
                    val py = layer.p(PropId.POS_Y)
                    val sampleTimes = mutableListOf<Float>()
                    sampleTimes += px.keys.map { it.t }
                    sampleTimes += py.keys.map { it.t }

                    fun collectAncestorTimes(firstId: String?) {
                        var currentId = firstId
                        var depth = 0
                        while (currentId != null && depth++ < 16) {
                            val ancestor = byId[currentId] ?: break
                            val transformIds = setOf(
                                PropId.POS_X, PropId.POS_Y, PropId.POS_Z,
                                PropId.ANCHOR_X, PropId.ANCHOR_Y,
                                PropId.SCALE_X, PropId.SCALE_Y,
                                PropId.ROTATION, PropId.ROTATION_X, PropId.ROTATION_Y
                            )
                            ancestor.props.filterKeys { it in transformIds }.values
                                .forEach { prop -> sampleTimes += prop.keys.map { it.t } }
                            currentId = ancestor.parentId
                        }
                    }
                    collectAncestorTimes(layer.parentId)
                    collectAncestorTimes(parentId)
                    val times = sampleTimes.filter { it.isFinite() && it >= 0f }.distinct().sorted()

                    fun mappedPositionAt(sampleTime: Float): FloatArray {
                        val point = floatArrayOf(
                            layer.p(PropId.ANCHOR_X).valueAt(sampleTime),
                            layer.p(PropId.ANCHOR_Y).valueAt(sampleTime)
                        )
                        layerMatrix(layer, sampleTime, byId).mapPoints(point)
                        val targetParent = parentId?.let { byId[it] }
                        if (targetParent != null) {
                            val parentMatrix = layerMatrix(targetParent, sampleTime, byId)
                            parentMatrix.preTranslate(
                                targetParent.p(PropId.ANCHOR_X).valueAt(sampleTime),
                                targetParent.p(PropId.ANCHOR_Y).valueAt(sampleTime)
                            )
                            val inverse = Matrix()
                            if (parentMatrix.invert(inverse)) inverse.mapPoints(point)
                        }
                        return point
                    }

                    val basePoint = mappedPositionAt(if (times.isEmpty()) time else 0f)
                    val xKeys = times.map { sampleTime ->
                        val template = px.keys.minByOrNull { abs(it.t - sampleTime) }
                            ?.takeIf { abs(it.t - sampleTime) <= frame / 2f }
                        (template?.copy(t = sampleTime) ?: Keyframe(sampleTime, 0f))
                            .copy(v = mappedPositionAt(sampleTime)[0])
                    }
                    val yKeys = times.map { sampleTime ->
                        val template = py.keys.minByOrNull { abs(it.t - sampleTime) }
                            ?.takeIf { abs(it.t - sampleTime) <= frame / 2f }
                        (template?.copy(t = sampleTime) ?: Keyframe(sampleTime, 0f))
                            .copy(v = mappedPositionAt(sampleTime)[1])
                    }
                    val props = layer.props + mapOf(
                        PropId.POS_X to px.copy(base = basePoint[0], keys = xKeys),
                        PropId.POS_Y to py.copy(base = basePoint[1], keys = yKeys)
                    )
                    val updated = layer.copy(parentId = parentId, props = props)
                    syncPositionKeyframes(updated)
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
            if (isPositionAxis(id)) syncPositionKeyframes(result) else result
        }
    }

    fun toggleStopwatch(layerId: String, id: PropId) {
        val t = time
        updateLayer(layerId) { l ->
            if (isPositionAxis(id)) {
                val axes = positionAxes.associateWith { l.p(it) }
                val disable = axes.values.any { it.animated }
                val next = axes.mapValues { (_, prop) ->
                    if (disable) Prop(prop.valueAt(t))
                    else if (prop.keys.any { abs(it.t - t) <= frame / 2f }) prop
                    else prop.copy(keys = (prop.keys + Keyframe(t, prop.valueAt(t))).sortedBy { it.t })
                }
                syncPositionKeyframes(l.copy(props = l.props + next))
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
            if (isPositionAxis(id)) {
                val axes = positionAxes.associateWith { l.p(it) }
                val exists = axes.values.any { prop -> prop.keys.any { abs(it.t - t) <= eps } }
                val next = axes.mapValues { (_, prop) ->
                    if (exists) {
                        val remaining = prop.keys.filterNot { abs(it.t - t) <= eps }
                        if (remaining.isEmpty()) Prop(prop.keys.firstOrNull()?.v ?: prop.base)
                        else prop.copy(keys = remaining)
                    } else toggleAt(prop)
                }
                syncPositionKeyframes(l.copy(props = l.props + next))
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
                if (isPositionAxis(id)) {
                    val grouped = updatePositionKeyGroup(l.copy(props = next), oldKey.t, key, id)
                    return@updateLayer grouped
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
            if (isPositionAxis(id)) {
                return@updateLayer updatePositionKeyGroup(layer, original.t, moved, id)
            }
            val result = layer.copy(props = next)
            if (id == PropId.POS_X || id == PropId.POS_Y) syncPositionKeyframes(result) else result
        }
        selectedKey = sorted.indexOf(moved)
    }

    fun addEffect(layerId: String, effectId: String): Boolean {
        if (layers.none { it.id == layerId }) return false
        val effect = createEffect(effectId) ?: return false
        updateLayer(layerId) { layer -> layer.copy(effectStack = layer.effectStack + effect) }
        return layers.firstOrNull { it.id == layerId }?.effectStack?.any { it.id == effect.id } == true
    }

    fun removeEffect(layerId: String, effectId: String) {
        updateLayer(layerId) { layer -> layer.copy(effectStack = layer.effectStack.filterNot { it.id == effectId }) }
        if (selectedEffectId == effectId) {
            selectedEffectId = null
            selectedEffectParameter = null
            selectedEffectKey = -1
        }
    }

    fun setEffectEnabled(layerId: String, effectId: String, enabled: Boolean) {
        updateLayer(layerId) { layer ->
            layer.copy(effectStack = layer.effectStack.map { if (it.id == effectId) it.copy(enabled = enabled) else it })
        }
    }

    fun updateEffectParameter(layerId: String, effectId: String, parameter: String, value: Float) {
        val t = time
        updateLayer(layerId, push = false) { layer ->
            layer.copy(effectStack = layer.effectStack.map { effect ->
                if (effect.id != effectId) effect else {
                    val base = effect.parameters[parameter] ?: 0f
                    val prop = Prop(base, effect.parameterKeys[parameter].orEmpty())
                    val updated = prop.withValueAt(t, value, frame / 2f)
                    effect.copy(
                        parameters = effect.parameters + (parameter to updated.base),
                        parameterKeys = if (updated.keys.isEmpty()) effect.parameterKeys - parameter
                            else effect.parameterKeys + (parameter to updated.keys)
                    )
                }
            })
        }
    }

    fun toggleEffectParameterKey(layerId: String, effectId: String, parameter: String) {
        val t = time
        updateLayer(layerId) { layer ->
            layer.copy(effectStack = layer.effectStack.map { effect ->
                if (effect.id != effectId) effect else {
                    val base = effect.parameters[parameter] ?: 0f
                    val keys = effect.parameterKeys[parameter].orEmpty()
                    val index = keys.indexOfFirst { abs(it.t - t) <= frame / 2f }
                    if (index >= 0) {
                        val removed = keys[index]
                        val remaining = keys.filterIndexed { i, _ -> i != index }
                        effect.copy(
                            parameters = if (remaining.isEmpty()) effect.parameters + (parameter to removed.v) else effect.parameters,
                            parameterKeys = if (remaining.isEmpty()) effect.parameterKeys - parameter
                                else effect.parameterKeys + (parameter to remaining)
                        )
                    } else {
                        val current = Prop(base, keys).valueAt(t)
                        effect.copy(parameterKeys = effect.parameterKeys +
                            (parameter to (keys + Keyframe(t, current)).sortedBy { it.t }))
                    }
                }
            })
        }
    }

    fun replaceEffectParameterKey(
        layerId: String, effectId: String, parameter: String, index: Int, key: Keyframe
    ) {
        updateLayer(layerId, push = false) { layer ->
            layer.copy(effectStack = layer.effectStack.map { effect ->
                if (effect.id != effectId) effect else {
                    val keys = effect.parameterKeys[parameter].orEmpty()
                    if (index !in keys.indices) effect else effect.copy(
                        parameterKeys = effect.parameterKeys + (parameter to keys.toMutableList().also { it[index] = key }
                            .sortedBy { it.t })
                    )
                }
            })
        }
    }

    /** Move an effect-parameter keyframe in time using the same bounded drag rules as layer properties. */
    fun moveEffectKeyTime(layerId: String, effectId: String, parameter: String, index: Int, time: Float) {
        val layer = layers.firstOrNull { it.id == layerId } ?: return
        val effect = layer.effectStack.firstOrNull { it.id == effectId } ?: return
        val keys = effect.parameterKeys[parameter].orEmpty()
        if (index !in keys.indices) return
        val previous = if (index > 0) keys[index - 1].t + frame else 0f
        val nextTime = if (index < keys.lastIndex) keys[index + 1].t - frame else duration
        val moved = keys[index].copy(t = time.coerceIn(previous, max(previous, nextTime)))
        val sorted = keys.toMutableList().also { it[index] = moved }.sortedBy { it.t }
        updateLayer(layerId, push = false) { current ->
            current.copy(effectStack = current.effectStack.map { item ->
                if (item.id == effectId) item.copy(
                    parameterKeys = item.parameterKeys + (parameter to sorted)
                ) else item
            })
        }
        selectedEffectKey = sorted.indexOf(moved)
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
                if (isPositionAxis(id)) {
                    return@updateLayer updatePositionKeyGroup(l.copy(props = next), removed.t, null)
                }
                val result = l.copy(props = next)
                if (id == PropId.POS_X || id == PropId.POS_Y) syncPositionKeyframes(result) else result
            }
        }
        selectedKey = -1
    }
}

private fun multiplyOpacity(parent: Prop, child: Prop): Prop {
    val base = (parent.base * child.base / 100f).coerceIn(0f, 100f)
    val times = (parent.keys.map { it.t } + child.keys.map { it.t }).distinct().sorted()
    if (times.isEmpty()) return Prop(base)
    return Prop(base, times.map { t ->
        Keyframe(t, (parent.valueAt(t) * child.valueAt(t) / 100f).coerceIn(0f, 100f))
    })
}

private fun flattenPrecomps(
    items: List<Layer>,
    parentVisible: Boolean = true,
    parentStart: Float = 0f,
    parentEnd: Float = Float.POSITIVE_INFINITY,
    parentId: String? = null,
    inheritedOpacity: Prop = Prop(100f),
    inheritedMotionBlur: Boolean = false
): List<Layer> = items.flatMap { layer ->
    val start = maxOf(layer.start, parentStart)
    val end = minOf(layer.end, parentEnd)
    if (end <= start) emptyList()
    else if (layer.type == LayerType.PRECOMP) {
        // Keep a non-rendering transform node in the flattened stack. Children
        // reference it as their parent so container transforms affect the entire pre-comp.
        val container = layer.copy(
            type = LayerType.NULL,
            start = start,
            duration = end - start,
            visible = layer.visible && parentVisible,
            parentId = layer.parentId ?: parentId,
            precompLayers = emptyList()
        )
        val opacity = multiplyOpacity(inheritedOpacity, layer.p(PropId.OPACITY))
        listOf(container) + flattenPrecomps(
            layer.precompLayers,
            parentVisible && layer.visible,
            start,
            end,
            layer.id,
            opacity,
            inheritedMotionBlur || layer.motionBlur
        )
    } else {
        val clippedElapsed = layer.sourceElapsedAt(start)
        val opacity = multiplyOpacity(inheritedOpacity, layer.p(PropId.OPACITY))
        listOf(layer.copy(
            start = start,
            duration = end - start,
            srcOffset = layer.srcOffset + clippedElapsed,
            visible = layer.visible && parentVisible,
            parentId = layer.parentId ?: parentId,
            motionBlur = layer.motionBlur || inheritedMotionBlur,
            props = layer.props + (PropId.OPACITY to opacity)
        ))
    }
}
