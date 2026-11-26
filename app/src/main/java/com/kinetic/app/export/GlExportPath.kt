package com.kinetic.app.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import com.kinetic.app.engine.EffectStackRunner
import com.kinetic.app.model.CompSettings
import com.kinetic.app.model.COMP_H
import com.kinetic.app.model.COMP_W
import com.kinetic.app.model.Layer
import com.kinetic.app.model.maskClips
import com.kinetic.app.model.textIsAnimated
import com.kinetic.app.model.LayerType
import com.kinetic.app.model.MotionBlur
import com.kinetic.app.model.PropId
import com.kinetic.app.model.layerMatrix
import com.kinetic.app.ui.LayerRaster
import com.kinetic.app.model.sourceElapsedAt
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

internal object GlExportPath {
    fun eligible(layers: List<Layer>, start: Float, end: Float): Boolean {
        val visual = layers.filter {
            it.visible && it.type != LayerType.AUDIO && it.type != LayerType.NULL && it.type != LayerType.CAMERA &&
                it.start < end && it.end > start
        }
        if (visual.isEmpty()) return false
        return visual.all { l ->
            (l.type == LayerType.VIDEO || l.type == LayerType.IMAGE || l.type == LayerType.TEXT || l.type == LayerType.SHAPE) &&
                (l.uri != null || l.type == LayerType.TEXT || l.type == LayerType.SHAPE) &&
                !l.is3D && !l.motionBlur && l.effectStack.isEmpty() && l.effects.isEmpty() &&
                !(l.maskClips && l.maskPath != null) && l.blendMode == "Normal"
        }
    }
}

internal class GlSceneRenderer(private val surface: Surface, val width: Int, val height: Int) {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var oesProgram = 0
    private var texProgram = 0
    private val vertices: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val flipV = FloatArray(16).also {
        android.opengl.Matrix.setIdentityM(it, 0)
        it[5] = -1f
        it[13] = 1f
    }
    private val points = FloatArray(8)
    private var readBuffer: ByteBuffer? = null
    private var captureBuffer: ByteBuffer? = null
    private var fbo = 0
    private var fboTex = 0
    private var fboW = 0
    private var fboH = 0
    private val uploaded = HashMap<Int, Long>()

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "EGL initialize failed" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "No recordable EGL config" }
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "EGL context failed" }
        eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "EGL window surface failed" }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "EGL make current failed" }
        oesProgram = buildProgram(true)
        texProgram = buildProgram(false)
    }

    fun createOesTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    fun createBitmapTexture(bitmap: Bitmap): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        return ids[0]
    }

    fun createEmptyTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    fun uploadBitmap(textureId: Int, bitmap: Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        val size = (bitmap.width.toLong() shl 32) or bitmap.height.toLong()
        if (uploaded[textureId] == size) {
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
        } else {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            uploaded[textureId] = size
        }
    }

    private fun ensureFbo(w: Int, h: Int) {
        if (fbo != 0 && fboW == w && fboH == h) return
        if (fbo != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            GLES20.glDeleteTextures(1, intArrayOf(fboTex), 0)
        }
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        fboTex = tex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        val ids = IntArray(1)
        GLES20.glGenFramebuffers(1, ids, 0)
        fbo = ids[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex, 0)
        check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) { "FBO incomplete" }
        fboW = w
        fboH = h
    }

    fun captureVideo(textureId: Int, texMatrix: FloatArray, w: Int, h: Int): Bitmap {
        ensureFbo(w, h)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val v = vertices
        v.position(0)
        v.put(-1f); v.put(-1f); v.put(0f); v.put(1f)
        v.put(-1f); v.put(1f); v.put(0f); v.put(0f)
        v.put(1f); v.put(-1f); v.put(1f); v.put(1f)
        v.put(1f); v.put(1f); v.put(1f); v.put(0f)
        GLES20.glUseProgram(oesProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(oesProgram, "uTexture"), 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(oesProgram, "uAlpha"), 1f)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(oesProgram, "uTex"), 1, false, texMatrix, 0)
        val position = GLES20.glGetAttribLocation(oesProgram, "aPos")
        val coord = GLES20.glGetAttribLocation(oesProgram, "aUv")
        v.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, v)
        GLES20.glEnableVertexAttribArray(position)
        v.position(2)
        GLES20.glVertexAttribPointer(coord, 2, GLES20.GL_FLOAT, false, 16, v)
        GLES20.glEnableVertexAttribArray(coord)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(coord)
        val size = w * h * 4
        val buffer = captureBuffer?.takeIf { it.capacity() == size }
            ?: ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder()).also { captureBuffer = it }
        buffer.clear()
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        buffer.rewind()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        return bitmap
    }

    fun deleteTexture(id: Int) {
        uploaded.remove(id)
        if (id != 0) GLES20.glDeleteTextures(1, intArrayOf(id), 0)
    }

    fun begin(background: Int) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glClearColor(
            ((background shr 16) and 0xff) / 255f, ((background shr 8) and 0xff) / 255f,
            (background and 0xff) / 255f, 1f
        )
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    fun drawQuad(textureId: Int, oes: Boolean, texMatrix: FloatArray?, layerMatrix: Matrix, left: Float, top: Float, right: Float, bottom: Float, alpha: Float) {
        if (alpha <= 0f) return
        points[0] = left; points[1] = top
        points[2] = left; points[3] = bottom
        points[4] = right; points[5] = top
        points[6] = right; points[7] = bottom
        layerMatrix.mapPoints(points)
        val v = vertices
        v.position(0)
        v.put(ndcX(points[0])); v.put(ndcY(points[1])); v.put(0f); v.put(1f)
        v.put(ndcX(points[2])); v.put(ndcY(points[3])); v.put(0f); v.put(0f)
        v.put(ndcX(points[4])); v.put(ndcY(points[5])); v.put(1f); v.put(1f)
        v.put(ndcX(points[6])); v.put(ndcY(points[7])); v.put(1f); v.put(0f)
        val program = if (oes) oesProgram else texProgram
        GLES20.glUseProgram(program)
        val target = if (oes) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(target, textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uAlpha"), alpha)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTex"), 1, false, texMatrix ?: flipV, 0)
        val position = GLES20.glGetAttribLocation(program, "aPos")
        val coord = GLES20.glGetAttribLocation(program, "aUv")
        v.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, v)
        GLES20.glEnableVertexAttribArray(position)
        v.position(2)
        GLES20.glVertexAttribPointer(coord, 2, GLES20.GL_FLOAT, false, 16, v)
        GLES20.glEnableVertexAttribArray(coord)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(coord)
    }

    private fun ndcX(x: Float) = x / COMP_W * 2f - 1f
    private fun ndcY(y: Float) = 1f - y / COMP_H * 2f

    fun readThumbnail(maxEdge: Int): Bitmap? {
        val size = width * height * 4
        val buffer = readBuffer?.takeIf { it.capacity() == size }
            ?: ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder()).also { readBuffer = it }
        buffer.clear()
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        buffer.rewind()
        val full = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(buffer)
        val k = min(1f, maxEdge.toFloat() / max(width, height))
        val m = Matrix().apply { postScale(k, -k) }
        val out = Bitmap.createBitmap(full, 0, 0, width, height, m, true)
        if (out !== full) full.recycle()
        return out
    }

    fun present(presentationNs: Long) {
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, presentationNs)
        check(EGL14.eglSwapBuffers(display, eglSurface)) { "EGL swap failed" }
    }

    fun release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        surface.release()
    }

    private fun buildProgram(oes: Boolean): Int {
        fun shader(type: Int, source: String): Int {
            val id = GLES20.glCreateShader(type)
            GLES20.glShaderSource(id, source)
            GLES20.glCompileShader(id)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "Shader compile failed: ${GLES20.glGetShaderInfoLog(id)}" }
            return id
        }
        val vertex = shader(
            GLES20.GL_VERTEX_SHADER,
            "attribute vec2 aPos; attribute vec2 aUv; uniform mat4 uTex; varying vec2 vUv; " +
                "void main() { gl_Position = vec4(aPos, 0.0, 1.0); vUv = (uTex * vec4(aUv, 0.0, 1.0)).xy; }"
        )
        val fragment = shader(
            GLES20.GL_FRAGMENT_SHADER,
            if (oes) {
                "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 vUv; " +
                    "uniform samplerExternalOES uTexture; uniform float uAlpha; " +
                    "void main() { vec4 c = texture2D(uTexture, vUv); gl_FragColor = vec4(c.rgb * uAlpha, uAlpha); }"
            } else {
                "precision mediump float; varying vec2 vUv; uniform sampler2D uTexture; uniform float uAlpha; " +
                    "void main() { gl_FragColor = texture2D(uTexture, vUv) * uAlpha; }"
            }
        )
        val id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, vertex)
        GLES20.glAttachShader(id, fragment)
        GLES20.glLinkProgram(id)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "Program link failed: ${GLES20.glGetProgramInfoLog(id)}" }
        return id
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}

internal class SurfaceVideoDecoder(
    context: Context, uri: Uri, startUs: Long, private val renderer: GlSceneRenderer, private val signalThread: HandlerThread
) {
    var textureId = 0
        private set
    val texMatrix = FloatArray(16)
    var displayWidth = 1
        private set
    var displayHeight = 1
        private set
    var hasFrame = false
        private set

    private val extractor = MediaExtractor()
    private lateinit var codec: MediaCodec
    private lateinit var texture: SurfaceTexture
    private lateinit var outputSurface: Surface
    private val info = MediaCodec.BufferInfo()
    private val frameSignal = Semaphore(0)
    private var inputDone = false
    private var outputDone = false
    private var heldIndex = -1
    private var heldPts = 0L
    private var heldEos = false

    init {
        var created: MediaCodec? = null
        var tex = 0
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: throw IllegalStateException("No video track")
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IllegalStateException("No mime")
            val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
            val w = format.getInteger(MediaFormat.KEY_WIDTH)
            val h = format.getInteger(MediaFormat.KEY_HEIGHT)
            val swap = rotation % 180 != 0
            displayWidth = if (swap) h else w
            displayHeight = if (swap) w else h
            extractor.selectTrack(track)
            extractor.seekTo(startUs.coerceAtLeast(0L), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            tex = renderer.createOesTexture()
            textureId = tex
            texture = SurfaceTexture(tex)
            texture.setOnFrameAvailableListener({ frameSignal.release() }, Handler(signalThread.looper))
            outputSurface = Surface(texture)
            created = MediaCodec.createDecoderByType(mime)
            try {
                format.setInteger(MediaFormat.KEY_PRIORITY, 0)
                format.setInteger(MediaFormat.KEY_OPERATING_RATE, 480)
                created.configure(format, outputSurface, null, 0)
            } catch (e: Exception) {
                format.setInteger(MediaFormat.KEY_PRIORITY, 1)
                if (android.os.Build.VERSION.SDK_INT >= 29) format.removeKey(MediaFormat.KEY_OPERATING_RATE)
                created.configure(format, outputSurface, null, 0)
            }
            created.start()
            codec = created
        } catch (error: Throwable) {
            runCatching { created?.release() }
            runCatching { extractor.release() }
            renderer.deleteTexture(tex)
            throw error
        }
    }

    private fun feed() {
        var fed = 0
        while (!inputDone && fed < 4) {
            val index = codec.dequeueInputBuffer(0)
            if (index < 0) return
            val buffer = codec.getInputBuffer(index)
            val size = if (buffer != null) extractor.readSampleData(buffer, 0) else -1
            if (size < 0) {
                codec.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                inputDone = true
            } else {
                codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                extractor.advance()
            }
            fed++
        }
    }

    private fun nextOutput(): Boolean {
        var idle = 0
        while (!outputDone) {
            feed()
            val index = codec.dequeueOutputBuffer(info, 5_000L)
            if (index >= 0) {
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (info.size == 0) {
                    codec.releaseOutputBuffer(index, false)
                    if (eos) outputDone = true
                    continue
                }
                heldIndex = index
                heldPts = info.presentationTimeUs
                heldEos = eos
                return true
            }
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                idle++
                if (idle > 800) throw IllegalStateException("Video decoder stalled")
            }
        }
        return false
    }

    var lastTargetUs = -1L
        private set

    fun needsSeek(targetUs: Long): Boolean =
        lastTargetUs >= 0L && (targetUs < lastTargetUs - 100_000L || targetUs > lastTargetUs + 2_000_000L)

    fun seek(targetUs: Long) {
        if (heldIndex >= 0) {
            runCatching { codec.releaseOutputBuffer(heldIndex, false) }
            heldIndex = -1
        }
        extractor.seekTo(targetUs.coerceAtLeast(0L), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        codec.flush()
        inputDone = false
        outputDone = false
        hasFrame = false
    }

    fun advance(targetUs: Long): Boolean {
        lastTargetUs = targetUs
        val limit = targetUs + TOLERANCE_US
        var candidate = -1
        while (true) {
            if (heldIndex < 0 && !nextOutput()) break
            if (heldPts <= limit || (candidate < 0 && !hasFrame)) {
                if (candidate >= 0) codec.releaseOutputBuffer(candidate, false)
                candidate = heldIndex
                heldIndex = -1
                if (heldEos) outputDone = true
                if (outputDone) break
            } else break
        }
        if (candidate >= 0) {
            while (frameSignal.tryAcquire()) Unit
            codec.releaseOutputBuffer(candidate, true)
            if (!frameSignal.tryAcquire(3, TimeUnit.SECONDS)) throw IllegalStateException("Decoded frame never arrived")
            texture.updateTexImage()
            texture.getTransformMatrix(texMatrix)
            hasFrame = true
        }
        return hasFrame
    }

    fun release() {
        runCatching { if (heldIndex >= 0) codec.releaseOutputBuffer(heldIndex, false) }
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { extractor.release() }
        runCatching { texture.release() }
        runCatching { outputSurface.release() }
        runCatching { renderer.deleteTexture(textureId) }
    }

    private companion object {
        const val TOLERANCE_US = 8_000L
    }
}

internal class GlFrameComposer(
    private val context: Context,
    surface: Surface,
    layers: List<Layer>,
    private val comp: CompSettings,
    width: Int,
    height: Int,
    private val fps: Int
) : AutoCloseable {
    private val renderer = GlSceneRenderer(surface, width, height)
    private val ordered = layers.asReversed()
    private val byId = layers.associateBy { it.id }
    private val sx = width / COMP_W
    private val sy = height / COMP_H
    private val videos = HashMap<String, SurfaceVideoDecoder>()
    private val idle = ArrayList<Pair<String, SurfaceVideoDecoder>>()
    private val images = HashMap<String, Pair<Int, Bitmap>>()
    private val scratch = HashMap<String, Int>()
    private val signalThread = HandlerThread("KineticExportFrames").also { it.start() }
    private var lastThumbAt = 0L

    fun render(time: Float, presentationNs: Long, wantThumbnail: Boolean): Bitmap? {
        renderer.begin(comp.background.toInt())
        val finished = videos.keys.filter { id -> byId[id]?.let { time >= it.end || !it.visible } ?: true }
        finished.forEach { id ->
            val decoder = videos.remove(id) ?: return@forEach
            park(byId[id]?.uri, decoder)
        }
        for (layer in ordered) {
            if (!layer.visible || !layer.activeAt(time)) continue
            when (layer.type) {
                LayerType.VIDEO -> drawVideo(layer, time)
                LayerType.IMAGE -> drawImage(layer, time)
                LayerType.TEXT, LayerType.SHAPE -> drawVector(layer, time)
                else -> Unit
            }
        }
        var thumb: Bitmap? = null
        val now = SystemClock.elapsedRealtime()
        if (wantThumbnail && now - lastThumbAt >= 1_200L) {
            thumb = runCatching { renderer.readThumbnail(320) }.getOrNull()
            lastThumbAt = now
        }
        renderer.present(presentationNs)
        return thumb
    }

    private fun park(uri: String?, decoder: SurfaceVideoDecoder) {
        if (uri == null) {
            decoder.release()
            return
        }
        idle.add(uri to decoder)
        while (idle.size > 2) idle.removeAt(0).second.release()
    }

    private fun acquire(uri: String, targetUs: Long): SurfaceVideoDecoder {
        val index = idle.indexOfFirst { it.first == uri }
        if (index >= 0) return idle.removeAt(index).second
        return SurfaceVideoDecoder(context, Uri.parse(uri), targetUs, renderer, signalThread)
    }

    private fun sampleTimes(layer: Layer, time: Float): FloatArray =
        MotionBlur.sampleTimes(layer, time, comp.shutterAngle, fps, byId, MotionBlur.EXPORT_MAX_SAMPLES, 0.75f)

    private fun effectStack(layer: Layer) = EffectStackRunner.pictureEffects(layer.effectStack).take(12)

    private fun targetEdge(layer: Layer): Int =
        max(layer.width * sx, layer.height * sy).toInt().coerceIn(64, MAX_EDGE)

    private fun drawVideo(layer: Layer, time: Float) {
        val uri = layer.uri ?: return
        val sourceSeconds = (layer.sourceElapsedAt(time) + layer.srcOffset).coerceAtLeast(0f)
        val targetUs = (sourceSeconds * 1_000_000f).toLong()
        val decoder = videos.getOrPut(layer.id) { acquire(uri, targetUs) }
        if (decoder.needsSeek(targetUs)) decoder.seek(targetUs)
        if (!decoder.advance(targetUs)) return
        val scale = min(layer.width / decoder.displayWidth, layer.height / decoder.displayHeight)
        val w = decoder.displayWidth * scale
        val h = decoder.displayHeight * scale
        val left = (layer.width - w) / 2f
        val top = (layer.height - h) / 2f
        val stack = effectStack(layer)
        if (stack.isEmpty()) {
            drawSamples(layer, time) { matrix, alpha ->
                renderer.drawQuad(decoder.textureId, true, decoder.texMatrix, matrix, left, top, left + w, top + h, alpha)
            }
            return
        }
        val k = min(1f, targetEdge(layer).toFloat() / max(decoder.displayWidth, decoder.displayHeight))
        val captured = renderer.captureVideo(
            decoder.textureId, decoder.texMatrix,
            (decoder.displayWidth * k).toInt().coerceAtLeast(2), (decoder.displayHeight * k).toInt().coerceAtLeast(2)
        )
        drawProcessed(layer, time, captured, RectF(left, top, left + w, top + h), stack, true, cacheResult = false)
    }

    private fun drawImage(layer: Layer, time: Float) {
        val uri = layer.uri ?: return
        val entry = images[layer.id] ?: run {
            val bitmap = loadImage(Uri.parse(uri)) ?: return
            val created = renderer.createBitmapTexture(bitmap) to bitmap
            images[layer.id] = created
            created
        }
        val bitmap = entry.second
        val scale = min(layer.width / bitmap.width, layer.height / bitmap.height)
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        val left = (layer.width - w) / 2f
        val top = (layer.height - h) / 2f
        val stack = effectStack(layer)
        if (stack.isEmpty()) {
            drawSamples(layer, time) { matrix, alpha ->
                renderer.drawQuad(entry.first, false, null, matrix, left, top, left + w, top + h, alpha)
            }
            return
        }
        drawProcessed(layer, time, bitmap, RectF(left, top, left + w, top + h), stack, false)
    }

    private fun drawVector(layer: Layer, time: Float) {
        val bitmap = LayerRaster.render(layer, time, targetEdge(layer), allowUpscale = true) ?: return
        drawProcessed(layer, time, bitmap, RectF(0f, 0f, layer.width, layer.height), effectStack(layer), true, cacheResult = false)
    }

    private fun drawProcessed(
        layer: Layer, time: Float, source: Bitmap, dest: RectF,
        stack: List<com.kinetic.app.model.EffectInstance>, ownsSource: Boolean, cacheResult: Boolean = true
    ) {
        val effected = if (stack.isEmpty()) null else EffectStackRunner.processBitmapGrown(
            source, stack, time, time - layer.start, fps.toFloat(), layer.width, layer.height, MAX_EFFECT_PIXELS, cacheResult = cacheResult
        )
        val bitmap = effected?.bitmap ?: source
        val rect = if (effected != null) {
            RectF(dest.left - effected.left, dest.top - effected.top, dest.right + effected.right, dest.bottom + effected.bottom)
        } else dest
        val texture = scratch.getOrPut(layer.id) { renderer.createEmptyTexture() }
        renderer.uploadBitmap(texture, bitmap)
        drawSamples(layer, time) { matrix, alpha ->
            renderer.drawQuad(texture, false, null, matrix, rect.left, rect.top, rect.right, rect.bottom, alpha)
        }
        if (effected != null && !effected.bitmap.isRecycled) effected.bitmap.recycle()
        if (ownsSource && !source.isRecycled) source.recycle()
    }

    private inline fun drawSamples(layer: Layer, time: Float, draw: (Matrix, Float) -> Unit) {
        val opacity = (layer.p(PropId.OPACITY).valueAt(time) / 100f).coerceIn(0f, 1f)
        val times = sampleTimes(layer, time)
        for (index in times.indices) {
            val alpha = if (times.size == 1) opacity else MotionBlur.sampleAlpha(opacity, index, times.size)
            draw(layerMatrix(layer, times[index], byId), alpha)
        }
    }

    private fun loadImage(uri: Uri): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }.getOrNull()

    override fun close() {
        videos.values.forEach { runCatching { it.release() } }
        videos.clear()
        idle.forEach { runCatching { it.second.release() } }
        idle.clear()
        images.values.forEach { runCatching { renderer.deleteTexture(it.first) }; if (!it.second.isRecycled) it.second.recycle() }
        images.clear()
        scratch.values.forEach { runCatching { renderer.deleteTexture(it) } }
        scratch.clear()
        runCatching { renderer.release() }
        runCatching { signalThread.quitSafely() }
    }

    private companion object {
        const val MAX_EDGE = 4096
        const val MAX_EFFECT_PIXELS = 12_000_000
    }
}
