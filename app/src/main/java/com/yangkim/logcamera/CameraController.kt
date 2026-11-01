package com.yangkim.logcamera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.TonemapCurve
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Camera2 제어: 1080p, 30fps, 로그 톤 커브, 얼굴 우선 노출, 탭 초점. */
class CameraController(context: Context, private val listener: Listener) {

    interface Listener {
        fun onTonemapStatus(curveApplied: Boolean)
        fun onCameraError(message: String)
    }

    data class CameraInfo(
        val id: String,
        val size: Size,
        val sensorOrientation: Int,
        val isFront: Boolean
    )

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var target: Surface? = null
    private var chars: CameraCharacteristics? = null
    private var info: CameraInfo? = null

    @Volatile private var evIndex = 0
    @Volatile private var regions: Array<MeteringRectangle>? = null
    @Volatile private var openToken = 0
    private var statusReported = false
    private var frameCount = 0

    fun select(front: Boolean): CameraInfo? {
        val want = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        for (id in manager.cameraIdList) {
            val c = manager.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.LENS_FACING) != want) continue
            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
            val sizes = map.getOutputSizes(SurfaceTexture::class.java) ?: continue
            if (sizes.isEmpty()) continue
            val so = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            return CameraInfo(id, pickSize(sizes), so, front)
        }
        return null
    }

    private fun pickSize(sizes: Array<Size>): Size {
        sizes.firstOrNull { it.width == 1920 && it.height == 1080 }?.let { return it }
        val wide = sizes.filter { it.width <= 1920 && abs(it.width * 9 - it.height * 16) < 32 }
        wide.maxByOrNull { it.width * it.height }?.let { return it }
        return sizes.filter { it.width <= 1920 }.maxByOrNull { it.width * it.height } ?: sizes[0]
    }

    /** 노출 보정 범위와 한 칸당 EV. */
    fun evRange(id: String): Pair<Range<Int>, Float> {
        val c = manager.getCameraCharacteristics(id)
        val range = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
        val step = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f
        return Pair(range, step)
    }

    @SuppressLint("MissingPermission")
    fun open(newInfo: CameraInfo, surface: Surface) {
        close()
        val token = ++openToken
        info = newInfo
        target = surface
        evIndex = 0
        regions = null
        try {
            chars = manager.getCameraCharacteristics(newInfo.id)
            manager.openCamera(newInfo.id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    if (token != openToken) { d.close(); return }
                    device = d
                    createSession(d, surface, token)
                }

                override fun onDisconnected(d: CameraDevice) {
                    d.close()
                    if (device === d) device = null
                }

                override fun onError(d: CameraDevice, error: Int) {
                    d.close()
                    if (device === d) device = null
                    listener.onCameraError("카메라 오류 ($error)")
                }
            }, handler)
        } catch (e: Exception) {
            listener.onCameraError("카메라를 열 수 없어요: ${e.message}")
        }
    }

    private fun createSession(d: CameraDevice, surface: Surface, token: Int) {
        try {
            val cfg = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(OutputConfiguration(surface)),
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (token != openToken) { s.close(); return }
                        session = s
                        statusReported = false
                        frameCount = 0
                        submitRequest()
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        listener.onCameraError("카메라 설정에 실패했어요")
                    }
                })
            d.createCaptureSession(cfg)
        } catch (e: Exception) {
            listener.onCameraError("카메라 세션 오류: ${e.message}")
        }
    }

    fun setEv(index: Int) {
        evIndex = index
        handler.post { submitRequest() }
    }

    /** 정규화된 센서 좌표(0..1)로 초점/노출 지점 지정. */
    fun setMeteringPoint(nx: Float, ny: Float) {
        val c = chars ?: return
        val inf = info ?: return
        val arr: Rect = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        // 16:9 프레임은 센서(4:3)의 가운데를 잘라 쓰므로 그에 맞춰 환산
        val fw = inf.size.width.toFloat()
        val fh = inf.size.height.toFloat()
        var cropW = arr.width().toFloat()
        var cropH = cropW * fh / fw
        if (cropH > arr.height()) {
            cropH = arr.height().toFloat()
            cropW = cropH * fw / fh
        }
        val ox = arr.left + (arr.width() - cropW) / 2f
        val oy = arr.top + (arr.height() - cropH) / 2f
        val px = ox + nx.coerceIn(0f, 1f) * cropW
        val py = oy + ny.coerceIn(0f, 1f) * cropH
        val half = (arr.width() / 14).coerceAtLeast(20)
        val left = max(arr.left, (px - half).toInt())
        val top = max(arr.top, (py - half).toInt())
        val right = min(arr.right - 1, (px + half).toInt())
        val bottom = min(arr.bottom - 1, (py + half).toInt())
        if (right <= left || bottom <= top) return
        regions = arrayOf(MeteringRectangle(left, top, right - left, bottom - top, MeteringRectangle.METERING_WEIGHT_MAX))
        handler.post { submitRequest() }
    }

    fun clearMetering() {
        regions = null
        handler.post { submitRequest() }
    }

    private fun submitRequest() {
        val d = device ?: return
        val s = session ?: return
        val c = chars ?: return
        val surf = target ?: return
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            b.addTarget(surf)

            // 얼굴 우선 노출/초점
            val scenes = c.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES) ?: IntArray(0)
            if (scenes.contains(CameraMetadata.CONTROL_SCENE_MODE_FACE_PRIORITY)) {
                b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_USE_SCENE_MODE)
                b.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_FACE_PRIORITY)
            } else {
                b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            }
            val faces = c.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES) ?: IntArray(0)
            if (faces.contains(CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE)) {
                b.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE)
            }

            val afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
            if (afModes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }

            // 30fps 고정
            val ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            if (ranges != null && ranges.isNotEmpty()) {
                val fixed = ranges.firstOrNull { it.lower == 30 && it.upper == 30 }
                val best = fixed ?: ranges.filter { it.upper == 30 }.maxByOrNull { it.lower }
                if (best != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, best)
            }

            val stab = c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: IntArray(0)
            if (stab.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)) {
                b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
            }

            // 로그(평평한) 톤 커브 — 지원하는 경우에만
            val toneModes = c.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES) ?: IntArray(0)
            if (toneModes.contains(CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)) {
                val maxPts = c.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: 32
                b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
                b.set(CaptureRequest.TONEMAP_CURVE, logCurve(maxPts))
            }

            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evIndex)

            val r = regions
            if (r != null) {
                if ((c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0) b.set(CaptureRequest.CONTROL_AF_REGIONS, r)
                if ((c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0) b.set(CaptureRequest.CONTROL_AE_REGIONS, r)
            }

            s.setRepeatingRequest(b.build(), captureCallback, handler)
        } catch (e: Exception) {
            // 세션이 닫히는 중일 수 있음
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            if (statusReported) return
            frameCount++
            if (frameCount < 8) return
            statusReported = true
            val requested = request.get(CaptureRequest.TONEMAP_MODE) == CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE
            val applied = requested && result.get(CaptureResult.TONEMAP_MODE) == CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE
            listener.onTonemapStatus(applied)
        }
    }

    private fun logCurve(maxPoints: Int): TonemapCurve {
        val n = maxPoints.coerceIn(2, 64)
        val pts = FloatArray(n * 2)
        for (i in 0 until n) {
            val t = i.toFloat() / (n - 1)
            val x = t * t // 어두운 영역에 점을 촘촘하게
            pts[i * 2] = x
            pts[i * 2 + 1] = LookEngine.logEncode(x).coerceIn(0f, 1f)
        }
        return TonemapCurve(pts, pts.copyOf(), pts.copyOf())
    }

    fun close() {
        openToken++
        try { session?.close() } catch (e: Exception) {}
        try { device?.close() } catch (e: Exception) {}
        session = null
        device = null
    }

    fun release() {
        close()
        thread.quitSafely()
    }
}
