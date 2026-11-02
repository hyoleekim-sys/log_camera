package com.yangkim.logcamera

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Half
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * OpenGL ES 3 렌더러.
 * 카메라 프레임 → 보정 3D LUT → 미리보기 화면 + 녹화 서피스.
 */
class GlRenderer {
    private val thread = HandlerThread("gl").apply { start() }
    private val handler = Handler(thread.looper)

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var windowTarget: Surface? = null
    private var windowW = 0
    private var windowH = 0
    private var encoder: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var oesTex = 0
    private var lutTex = 0
    private var aPos = 0
    private var aTex = 0
    private var uTexMatrix = 0
    private var uMvp = 0
    private var uCam = 0
    private var uLut = 0
    private var uLutSize = 0

    private lateinit var surfaceTexture: SurfaceTexture
    lateinit var cameraSurface: Surface
        private set

    private val texMatrix = FloatArray(16)
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private var frameW = 1920
    private var frameH = 1080
    private var sensorOrientation = 90
    private var lutSize = 2
    private var released = false

    /** 카메라가 이미 세워서 준 영상인지 (갤럭시는 대부분 그럼). 매 프레임 확인. */
    @Volatile var transformRotates = true
        private set

    private val quad: FloatBuffer = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val texCoords: FloatBuffer = floatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

    fun start(onReady: () -> Unit) {
        handler.post {
            initEgl()
            initGl()
            onReady()
        }
    }

    private fun runSync(block: () -> Unit) {
        if (Looper.myLooper() == thread.looper) { block(); return }
        val latch = CountDownLatch(1)
        handler.post { try { block() } finally { latch.countDown() } }
        latch.await(3, TimeUnit.SECONDS)
    }

    fun configure(w: Int, h: Int, sensor: Int) = runSync {
        frameW = w; frameH = h; sensorOrientation = sensor
        if (!released) surfaceTexture.setDefaultBufferSize(w, h)
    }

    fun setWindowSurface(surface: Surface?, w: Int, h: Int) = runSync {
        if (released) return@runSync
        if (surface == null || surface !== windowTarget) {
            if (window != EGL14.EGL_NO_SURFACE) {
                makeCurrent(pbuffer)
                EGL14.eglDestroySurface(display, window)
                window = EGL14.EGL_NO_SURFACE
            }
            windowTarget = null
        }
        if (surface != null && window == EGL14.EGL_NO_SURFACE) {
            window = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
            windowTarget = surface
        }
        windowW = w; windowH = h
    }

    fun setEncoderSurface(surface: Surface?) = runSync {
        if (released) return@runSync
        if (encoder != EGL14.EGL_NO_SURFACE) {
            makeCurrent(pbuffer)
            EGL14.eglDestroySurface(display, encoder)
            encoder = EGL14.EGL_NO_SURFACE
        }
        if (surface != null) {
            encoder = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        }
    }

    fun uploadLut(data: FloatArray, n: Int) {
        handler.post {
            if (released) return@post
            makeCurrent(pbuffer)
            val sb = ByteBuffer.allocateDirect(data.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
            for (f in data) sb.put(Half.toHalf(f))
            sb.position(0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F, n, n, n, 0,
                GLES30.GL_RGB, GLES30.GL_HALF_FLOAT, sb)
            lutSize = n
        }
    }

    /** 저장(녹화) 크기: 세워서 들어오면 세로 1080x1920. */
    fun outputSize(): Pair<Int, Int> =
        if (transformRotates && sensorOrientation % 180 != 0) Pair(frameH, frameW) else Pair(frameW, frameH)

    fun release() {
        runSync {
            released = true
            makeCurrent(pbuffer)
            if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
            if (encoder != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, encoder)
            window = EGL14.EGL_NO_SURFACE
            encoder = EGL14.EGL_NO_SURFACE
            try { surfaceTexture.release() } catch (e: Exception) {}
            try { cameraSurface.release() } catch (e: Exception) {}
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, pbuffer)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
        thread.quitSafely()
    }

    // ───────────────────────── 내부 ─────────────────────────

    private fun initEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2)
        EGL14.eglInitialize(display, v, 0, v, 1)
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0)
        config = configs[0] ?: throw RuntimeException("EGL 설정을 찾을 수 없어요")
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        pbuffer = EGL14.eglCreatePbufferSurface(display, config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        makeCurrent(pbuffer)
    }

    private fun initGl() {
        program = buildProgram(VERTEX, FRAGMENT)
        aPos = GLES30.glGetAttribLocation(program, "aPos")
        aTex = GLES30.glGetAttribLocation(program, "aTex")
        uTexMatrix = GLES30.glGetUniformLocation(program, "uTexMatrix")
        uMvp = GLES30.glGetUniformLocation(program, "uMvp")
        uCam = GLES30.glGetUniformLocation(program, "uCam")
        uLut = GLES30.glGetUniformLocation(program, "uLut")
        uLutSize = GLES30.glGetUniformLocation(program, "uLutSize")

        val t = IntArray(2)
        GLES30.glGenTextures(2, t, 0)
        oesTex = t[0]
        lutTex = t[1]

        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        val id = FloatArray(2 * 2 * 2 * 3)
        var i = 0
        for (b in 0..1) for (g in 0..1) for (r in 0..1) {
            id[i++] = r.toFloat(); id[i++] = g.toFloat(); id[i++] = b.toFloat()
        }
        uploadLut(id, 2)

        surfaceTexture = SurfaceTexture(oesTex)
        surfaceTexture.setDefaultBufferSize(frameW, frameH)
        surfaceTexture.setOnFrameAvailableListener({ onFrame() }, handler)
        cameraSurface = Surface(surfaceTexture)
    }

    private fun makeCurrent(s: EGLSurface) {
        EGL14.eglMakeCurrent(display, s, s, context)
    }

    private fun onFrame() {
        if (released) return
        try {
            makeCurrent(if (window != EGL14.EGL_NO_SURFACE) window else pbuffer)
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(texMatrix)
            if (sensorOrientation % 180 != 0) {
                transformRotates = abs(texMatrix[1]) > abs(texMatrix[0])
            }

            if (window != EGL14.EGL_NO_SURFACE && windowW > 0 && windowH > 0) {
                GLES30.glViewport(0, 0, windowW, windowH)
                GLES30.glClearColor(0f, 0f, 0f, 1f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                draw(previewMvp())
                EGL14.eglSwapBuffers(display, window)
            }

            if (encoder != EGL14.EGL_NO_SURFACE) {
                val (outW, outH) = outputSize()
                makeCurrent(encoder)
                GLES30.glViewport(0, 0, outW, outH)
                draw(if (transformRotates) identity else legacyEncoderMvp())
                EGLExt.eglPresentationTimeANDROID(display, encoder, surfaceTexture.timestamp)
                EGL14.eglSwapBuffers(display, encoder)
            }
        } catch (e: Exception) {
            Log.w(TAG, "frame error", e)
        }
    }

    private fun legacyEncoderMvp(): FloatArray = identity

    /** 미리보기: 화면 비율에 맞추기 (회전이 안 들어온 기기만 직접 돌림). */
    private fun previewMvp(): FloatArray {
        val m = FloatArray(16)
        Matrix.setIdentityM(m, 0)
        if (transformRotates) {
            val (cw, ch) = outputSize()
            applyAspect(m, cw, ch)
        } else {
            val deg = sensorOrientation % 360
            val rotated = deg % 180 != 0
            applyAspect(m, if (rotated) frameH else frameW, if (rotated) frameW else frameH)
            Matrix.rotateM(m, 0, -deg.toFloat(), 0f, 0f, 1f)
        }
        return m
    }

    private fun applyAspect(m: FloatArray, cw: Int, ch: Int) {
        val r = cw.toFloat() / ch
        val vr = windowW.toFloat() / windowH
        if (r > vr) Matrix.scaleM(m, 0, 1f, vr / r, 1f) else Matrix.scaleM(m, 0, r / vr, 1f, 1f)
    }

    private fun draw(mvp: FloatArray) {
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES30.glUniform1i(uCam, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
        GLES30.glUniform1i(uLut, 1)
        GLES30.glUniform1f(uLutSize, lutSize.toFloat())
        GLES30.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES30.glEnableVertexAttribArray(aPos)
        GLES30.glVertexAttribPointer(aPos, 2, GLES30.GL_FLOAT, false, 0, quad)
        GLES30.glEnableVertexAttribArray(aTex)
        GLES30.glVertexAttribPointer(aTex, 2, GLES30.GL_FLOAT, false, 0, texCoords)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES30.GL_VERTEX_SHADER, vs)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v)
        GLES30.glAttachShader(p, f)
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("link: " + GLES30.glGetProgramInfoLog(p))
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("shader: " + GLES30.glGetShaderInfoLog(s))
        return s
    }

    companion object {
        private const val TAG = "ProCamera"
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private fun floatBuffer(a: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(a); position(0)
            }

        private const val VERTEX = """#version 300 es
in vec4 aPos;
in vec4 aTex;
uniform mat4 uTexMatrix;
uniform mat4 uMvp;
out vec2 vTex;
void main() {
    gl_Position = uMvp * aPos;
    vTex = (uTexMatrix * aTex).xy;
}
"""

        private const val FRAGMENT = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uCam;
uniform highp sampler3D uLut;
uniform float uLutSize;
in vec2 vTex;
out vec4 outColor;
void main() {
    vec3 c = clamp(texture(uCam, vTex).rgb, 0.0, 1.0);
    vec3 p = c * ((uLutSize - 1.0) / uLutSize) + 0.5 / uLutSize;
    outColor = vec4(texture(uLut, p).rgb, 1.0);
}
"""
    }
}
