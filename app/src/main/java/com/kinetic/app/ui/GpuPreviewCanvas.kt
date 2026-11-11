package com.kinetic.app.ui

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.kinetic.app.data.MediaLoader
import com.kinetic.app.model.COMP_H
import com.kinetic.app.model.COMP_W
import com.kinetic.app.model.EditorState
import com.kinetic.app.model.LayerType
import com.kinetic.app.model.PropId
import com.kinetic.app.model.layerMatrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import android.net.Uri

private data class GpuLayer(
    val id: String,
    val bitmap: Bitmap?,
    val matrix: Matrix,
    val width: Float,
    val height: Float,
    val opacity: Float,
    val mask: Boolean,
    val grayscale: Boolean,
    val blend: String
)

private data class GpuScene(
    val layers: List<GpuLayer>,
    val selected: GpuLayer?,
    val background: Int = 0xFF000000.toInt()
)

private fun supportsGles2(context: Context): Boolean = runCatching {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    manager.deviceConfigurationInfo.reqGlEsVersion >= 0x20000
}.getOrDefault(false)

@Composable
fun PreviewCanvas(editor: EditorState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val frames = remember { mutableStateMapOf<String, Bitmap>() }
    val textTextures = remember { mutableMapOf<String, Pair<Int, Bitmap>>() }
    LaunchedEffect(editor.layers.map { it.id }) {
        val currentIds = editor.layers.mapTo(HashSet<String>()) { it.id }
        val stale = textTextures.keys.filter { it !in currentIds }
        stale.forEach { id -> textTextures.remove(id) }
    }

    for (layer in editor.layers) {
        val uri = layer.uri
        if (uri != null && (layer.type == LayerType.IMAGE || layer.type == LayerType.VIDEO)) {
            val active = layer.activeAt(editor.time)
            val sourceTime = ((editor.time - layer.start).coerceAtLeast(0f) * layer.speed + layer.srcOffset).coerceAtLeast(0f)
            val bucket = if (!active) -1 else if (layer.type == LayerType.VIDEO) (sourceTime * 12f).toInt() else 0
            key(layer.id) {
                DisposableEffect(layer.id) {
                    onDispose { frames.remove(layer.id) }
                }
                LaunchedEffect(uri, bucket) {
                    if (bucket >= 0) {
                        MediaLoader.bitmapFor(context, Uri.parse(uri), layer.type, sourceTime, 12, 640)?.let { frames[layer.id] = it }
                    } else frames.remove(layer.id)
                }
            }
        }
    }

    val byId = editor.layers.associateBy { it.id }
    val renderLayers = ArrayList<GpuLayer>()
    for (layer in editor.layers.asReversed()) {
        if (!layer.visible || !layer.activeAt(editor.time) || layer.type == LayerType.AUDIO ||
            layer.type == LayerType.NULL || layer.type == LayerType.ADJUSTMENT) continue
        val bitmap = when (layer.type) {
            LayerType.IMAGE, LayerType.VIDEO -> frames[layer.id]
            LayerType.TEXT, LayerType.SHAPE -> {
                val signature = if (layer.type == LayerType.TEXT)
                    layer.text.hashCode() * 31 + layer.color
                else layer.color * 31 + layer.width.toInt() * 7 + layer.height.toInt()
                val cached = textTextures[layer.id]
                if (cached == null || cached.first != signature || cached.second.isRecycled) {
                    val w = layer.width.toInt().coerceIn(1, 4096)
                    val h = layer.height.toInt().coerceIn(1, 4096)
                    val generated = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = AndroidCanvas(generated)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = layer.color }
                    if (layer.type == LayerType.TEXT) {
                        paint.textAlign = Paint.Align.CENTER
                        paint.textSize = 120f
                        paint.typeface = Typeface.DEFAULT_BOLD
                        canvas.drawText(layer.text, w / 2f, h / 2f + 40f, paint)
                    } else {
                        canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 24f, 24f, paint)
                    }
                    textTextures[layer.id] = signature to generated
                    generated
                } else cached.second
            }
            else -> null
        } ?: continue
        val opacity = (layer.p(PropId.OPACITY).valueAt(editor.time) / 100f).coerceIn(0f, 1f)
        renderLayers += GpuLayer(
            layer.id, bitmap, Matrix(layerMatrix(layer, editor.time, byId)),
            layer.width, layer.height, opacity, layer.maskEnabled,
            "Grayscale" in layer.effects, layer.blendMode
        )
    }
    val selectedLayer = editor.layers.firstOrNull {
        it.id == editor.selectedId && it.visible && it.activeAt(editor.time) && it.type != LayerType.AUDIO
    }?.let { layer ->
        GpuLayer(
            layer.id, null,
            Matrix(layerMatrix(layer, editor.time, byId)), layer.width, layer.height, 1f,
            false, false, "Normal"
        )
    }
    val scene = GpuScene(renderLayers, selectedLayer)
    val gpuFailed = remember { androidx.compose.runtime.mutableStateOf(false) }

    if (gpuFailed.value || !supportsGles2(context)) {
        CpuPreviewCanvas(editor, modifier)
    } else {
        val view = remember(context) { GpuPreviewSurfaceView(context) { gpuFailed.value = true } }
        AndroidView(
            factory = { view },
            modifier = modifier,
            update = { it.submit(scene) }
        )
        DisposableEffect(view) {
            onDispose { view.releaseRenderer() }
        }
    }
}

private class GpuPreviewSurfaceView(context: Context, onGpuFailure: () -> Unit) : GLSurfaceView(context) {
    private val gpuRenderer = CompositionGlRenderer(onGpuFailure)
    init {
        setEGLContextClientVersion(2)
        setPreserveEGLContextOnPause(true)
        setRenderer(gpuRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        setBackgroundColor(android.graphics.Color.BLACK)
    }

    fun submit(scene: GpuScene) {
        gpuRenderer.submit(scene)
        requestRender()
    }

    fun releaseRenderer() {
        queueEvent { gpuRenderer.release() }
    }
}

private class CompositionGlRenderer(private val onGpuFailure: () -> Unit) : GLSurfaceView.Renderer {
    @Volatile private var scene = GpuScene(emptyList(), null)
    private var program = 0
    private var solidProgram = 0
    private var positionHandle = 0
    private var uvHandle = 0
    private var samplerHandle = 0
    private var alphaHandle = 0
    private var grayHandle = 0
    private var solidPositionHandle = 0
    private var solidColorHandle = 0
    private val textures = HashMap<String, TextureEntry>()
    private var viewportWidth = 1
    private var viewportHeight = 1
    private val vertices = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val solidVertices = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private data class TextureEntry(val id: Int, val bitmap: Bitmap)

    fun submit(next: GpuScene) { scene = next }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.02f, 0.02f, 0.04f, 1f)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        // A recreated EGL context invalidates every texture/program from the old context.
        textures.clear()
        program = 0
        solidProgram = 0
        try {
            program = linkProgram(VERTEX_SHADER, TEXTURE_SHADER)
            solidProgram = linkProgram(VERTEX_SHADER, SOLID_SHADER)
            positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            uvHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
            samplerHandle = GLES20.glGetUniformLocation(program, "uTexture")
            alphaHandle = GLES20.glGetUniformLocation(program, "uAlpha")
            grayHandle = GLES20.glGetUniformLocation(program, "uGray")
            solidPositionHandle = GLES20.glGetAttribLocation(solidProgram, "aPosition")
            solidColorHandle = GLES20.glGetUniformLocation(solidProgram, "uColor")
        } catch (_: Throwable) {
            program = 0
            solidProgram = 0
            Handler(Looper.getMainLooper()).post { onGpuFailure() }
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (program == 0) return
        val current = scene
        val activeTextureIds = current.layers.mapTo(HashSet<String>()) { it.id }
        val staleIds = textures.keys.filter { it !in activeTextureIds }
        staleIds.forEach { id -> textures.remove(id)?.let { GLES20.glDeleteTextures(1, intArrayOf(it.id), 0) } }
        val scale = minOf(viewportWidth / COMP_W, viewportHeight / COMP_H)
        val offsetX = (viewportWidth - COMP_W * scale) / 2f
        val offsetY = (viewportHeight - COMP_H * scale) / 2f
        for (layer in current.layers) drawLayer(layer, scale, offsetX, offsetY)
        current.selected?.let { drawSelection(it, scale, offsetX, offsetY) }
    }

    private fun drawLayer(layer: GpuLayer, scale: Float, ox: Float, oy: Float) {
        val bitmap = layer.bitmap ?: return
        if (bitmap.isRecycled || layer.opacity <= 0f) return
        val texture = textureFor(layer.id, bitmap) ?: return
        val right = if (layer.mask) layer.width * 0.8f else layer.width
        val bottom = if (layer.mask) layer.height * 0.8f else layer.height
        val uvMax = if (layer.mask) 0.8f else 1f
        val points = floatArrayOf(0f, 0f, 0f, bottom, right, 0f, right, bottom)
        layer.matrix.mapPoints(points)
        val data = floatArrayOf(
            ndcX(points[0], scale, ox), ndcY(points[1], scale, oy), 0f, 0f,
            ndcX(points[2], scale, ox), ndcY(points[3], scale, oy), 0f, uvMax,
            ndcX(points[4], scale, ox), ndcY(points[5], scale, oy), uvMax, 0f,
            ndcX(points[6], scale, ox), ndcY(points[7], scale, oy), uvMax, uvMax
        )
        vertices.clear(); vertices.put(data); vertices.position(0)
        GLES20.glUseProgram(program)
        vertices.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 16, vertices)
        vertices.position(2)
        GLES20.glEnableVertexAttribArray(uvHandle)
        GLES20.glVertexAttribPointer(uvHandle, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(samplerHandle, 0)
        GLES20.glUniform1f(alphaHandle, layer.opacity)
        GLES20.glUniform1f(grayHandle, if (layer.grayscale) 1f else 0f)
        when (layer.blend) {
            "Multiply" -> GLES20.glBlendFunc(GLES20.GL_DST_COLOR, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            "Screen" -> GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_COLOR)
            "Add" -> GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
            else -> GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        }
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(uvHandle)
    }

    private fun textureFor(layerId: String, bitmap: Bitmap): Int? {
        val old = textures[layerId]
        if (old != null && old.bitmap === bitmap && old.id != 0) return old.id
        if (old != null) GLES20.glDeleteTextures(1, intArrayOf(old.id), 0)
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        if (ids[0] == 0) return null
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        textures[layerId] = TextureEntry(ids[0], bitmap)
        return ids[0]
    }

    private fun drawSelection(layer: GpuLayer, scale: Float, ox: Float, oy: Float) {
        val p = floatArrayOf(0f, 0f, layer.width, 0f, layer.width, layer.height, 0f, layer.height)
        layer.matrix.mapPoints(p)
        val data = floatArrayOf(
            ndcX(p[0], scale, ox), ndcY(p[1], scale, oy),
            ndcX(p[2], scale, ox), ndcY(p[3], scale, oy),
            ndcX(p[4], scale, ox), ndcY(p[5], scale, oy),
            ndcX(p[6], scale, ox), ndcY(p[7], scale, oy)
        )
        solidVertices.clear(); solidVertices.put(data); solidVertices.position(0)
        GLES20.glUseProgram(solidProgram)
        GLES20.glEnableVertexAttribArray(solidPositionHandle)
        GLES20.glVertexAttribPointer(solidPositionHandle, 2, GLES20.GL_FLOAT, false, 0, solidVertices)
        GLES20.glUniform4f(solidColorHandle, 0.52f, 0.40f, 1f, 1f)
        GLES20.glLineWidth(2f)
        GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, 4)
        GLES20.glDisableVertexAttribArray(solidPositionHandle)
    }

    private fun ndcX(x: Float, scale: Float, ox: Float) = ((ox + x * scale) / viewportWidth) * 2f - 1f
    private fun ndcY(y: Float, scale: Float, oy: Float) = 1f - ((oy + y * scale) / viewportHeight) * 2f

    fun release() {
        if (textures.isNotEmpty()) {
            GLES20.glDeleteTextures(textures.size, textures.values.map { it.id }.toIntArray(), 0)
            textures.clear()
        }
        if (program != 0) GLES20.glDeleteProgram(program)
        if (solidProgram != 0) GLES20.glDeleteProgram(solidProgram)
        program = 0; solidProgram = 0
    }

    private fun linkProgram(vertex: String, fragment: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertex)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragment)
        val result = GLES20.glCreateProgram()
        GLES20.glAttachShader(result, vs); GLES20.glAttachShader(result, fs)
        GLES20.glLinkProgram(result)
        val status = IntArray(1)
        GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vs); GLES20.glDeleteShader(fs)
        check(status[0] == GLES20.GL_TRUE) { GLES20.glGetProgramInfoLog(result) }
        return result
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source); GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { GLES20.glGetShaderInfoLog(shader) }
        return shader
    }

    companion object {
        private const val VERTEX_SHADER = "attribute vec2 aPosition; attribute vec2 aTexCoord; varying vec2 vTexCoord; void main(){ gl_Position=vec4(aPosition,0.0,1.0); vTexCoord=aTexCoord; }"
        private const val TEXTURE_SHADER = "precision mediump float; uniform sampler2D uTexture; uniform float uAlpha; uniform float uGray; varying vec2 vTexCoord; void main(){ vec4 c=texture2D(uTexture,vTexCoord); if(uGray>0.5){ float y=dot(c.rgb,vec3(0.299,0.587,0.114)); c.rgb=vec3(y); } gl_FragColor=vec4(c.rgb*uAlpha,c.a*uAlpha); }"
        private const val SOLID_SHADER = "precision mediump float; uniform vec4 uColor; void main(){ gl_FragColor=uColor; }"
    }
}
