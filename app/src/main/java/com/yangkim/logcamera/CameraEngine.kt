package com.yangkim.logcamera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
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
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaRecorder
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Camera2 엔진: 카메라 찾기, 미리보기, 사진, 녹화, 줌, 잠금. */
class CameraEngine(private val ctx: Context, private val cb: Callback) {

    interface Callback {
        fun onStats(exposureNs: Long, iso: Int, kelvin: Int)
        fun onCameraError(message: String)
        /** 찍힌 JPEG (보정·저장은 화면 쪽에서) */
        fun onJpeg(bytes: ByteArray)
    }

    class Cam(
        val id: String,
        val label: String,
        val front: Boolean,
        val sensorOrientation: Int,
        /** 줌 1.0배일 때 35mm 환산 화각(mm) */
        val eq1: Float,
        val zoomMin: Float,
        val zoomMax: Float,
        val zoomRatioKey: Boolean,
        val fpsRanges: List<Range<Int>>,
        val previewSize: Size,
        val jpegSize: Size,
        val activeArray: Rect,
        val maxAf: Int,
        val maxAe: Int,
        val afModes: IntArray,
        val stabModes: IntArray,
        /** 노이즈 제거·선명도·색번짐 보정 모드 */
        val nrModes: IntArray = IntArray(0),
        val edgeModes: IntArray = IntArray(0),
        val aberrModes: IntArray = IntArray(0),
        /** 이 카메라 안의 실제 렌즈들 (후면 광각/초광각 등). 줌 배율로 전환. */
        val lenses: List<Lens>,
        /** 4K(3840x2160) 스트림이 되면 그 크기, 안 되면 null */
        val size4k: Size? = null,
        /** 카메라가 내보낼 수 있는 영상 크기 / 사진 크기 전체 */
        val streamSizes: List<Size> = emptyList(),
        val jpegSizes: List<Size> = emptyList(),
        val hasFlash: Boolean = false,
        /** 노출 보정 한 칸 크기(EV)와 범위(칸 수) */
        val evStep: Float = 0f,
        val evMinIdx: Int = 0,
        val evMaxIdx: Int = 0,
        /** 촬영 중 플래시 밝기 단계 수 (1이면 켬/끔만) */
        val torchMaxLevel: Int = 1,
        val torchDefaultLevel: Int = 1,
        /** 셔터·ISO를 직접 정할 수 있는지 */
        val manualSensor: Boolean = false,
        /** 색 보정값을 직접 정할 수 있는지 (화이트밸런스 숫자 고정) */
        val manualPost: Boolean = false,
        /** 렌즈가 지원하는 조리개 값들 (대부분 하나) */
        val apertures: List<Float> = emptyList(),
        val expMinNs: Long = 100_000L,
        val isoMin: Int = 50,
        val isoMax: Int = 3200,
        val expMaxNs: Long = 100_000_000L
    ) {
        fun fpsRange(fps: Int): Range<Int>? = fpsRanges.filter { it.upper == fps }.maxByOrNull { it.lower }
        fun supportsFps(fps: Int) = fpsRange(fps) != null
    }

    class Lens(val name: String, val zoom: Float, val mm: Int)

    private fun lensName(mm: Float): String = when {
        mm < 18f -> "초광각"
        mm <= 35f -> "광각"
        else -> "망원"
    }

    private fun equivalent(c: CameraCharacteristics): Float? {
        val focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val phys = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        if (focals == null || focals.isEmpty() || phys == null) return null
        val diag = sqrt(phys.width * phys.width + phys.height * phys.height)
        return if (diag > 0f) focals[0] * 43.27f / diag else null
    }

    private val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var cam: Cam? = null
    private var preview: Surface? = null
    private var jpegReader: ImageReader? = null
    @Volatile private var token = 0
    private var lastStats = 0L
    private var updatePending = false

    @Volatile var videoMode = true
        private set
    @Volatile var fps = 30
    @Volatile var zoom = 1f
    @Volatile var aeLock = false
    @Volatile var awbLock = false
    /** 조리개 (0 = 렌즈 기본) */
    @Volatile var aperture = 0f
    @Volatile private var lastGains: android.hardware.camera2.params.RggbChannelVector? = null
    @Volatile private var lastTransform: android.hardware.camera2.params.ColorSpaceTransform? = null
    @Volatile private var lockedGains: android.hardware.camera2.params.RggbChannelVector? = null
    @Volatile private var lockedTransform: android.hardware.camera2.params.ColorSpaceTransform? = null

    /** 노이즈 제거·선명도·색번짐 보정을 최고 품질로 */
    private fun setHighQuality(b: CaptureRequest.Builder, c: Cam) {
        if (c.nrModes.contains(CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY))
            b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)
        if (c.edgeModes.contains(CameraMetadata.EDGE_MODE_HIGH_QUALITY))
            b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY)
        if (c.aberrModes.contains(CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY))
            b.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)
    }

    /** 수동 색온도 (0 = 자동) */
    @Volatile private var manualKelvin = 0
    @Volatile private var refBase = 0f
    @Volatile private var refGreen = 1f
    @Volatile private var refTransform: android.hardware.camera2.params.ColorSpaceTransform? = null

    /** 수동 색온도 켜기/바꾸기. 0이면 자동으로. 수동이 가능하면 true */
    fun setManualKelvin(k: Int): Boolean {
        if (k <= 0) {
            manualKelvin = 0
            requestUpdate()
            return false
        }
        if (manualKelvin == 0) {
            // 자동이 잡아둔 값을 기준으로 삼음
            val g = lastGains ?: lockedGains
            val t = lastTransform ?: lockedTransform
            if (g == null || t == null || cam?.manualPost != true) return false
            refBase = kotlin.math.sqrt(g.red * g.blue)
            refGreen = (g.greenEven + g.greenOdd) / 2f
            refTransform = t
        }
        manualKelvin = k
        requestUpdate()
        return true
    }

    /** 지금 자동 화이트밸런스가 추정한 색온도 */
    fun currentKelvin(): Int {
        val g = lastGains ?: return 5000
        return if (g.blue > 0f) estimateKelvin(g.red / g.blue) else 5000
    }

    /** 화이트밸런스 잠금/해제. 잠글 땐 지금 색 보정값을 저장. 숫자 고정이 되면 true */
    fun setWbLock(on: Boolean): Boolean {
        if (on) {
            lockedGains = lastGains
            lockedTransform = lastTransform
        } else {
            lockedGains = null
            lockedTransform = null
        }
        awbLock = on
        requestUpdate()
        return on && cam?.manualPost == true && lockedGains != null && lockedTransform != null
    }
    /** 노출 보정 (EV). 자동 노출일 때 카메라에 전달 */
    @Volatile var evComp = 0f
    /** 플래시(계속 켜짐) */
    /** 플래시 밝기 단계: 0 = 끔 */
    @Volatile var torchLevel = 0
    /** 사진 비율 4:3 여부 */
    @Volatile var aspect43 = false
    /** 지금 받는 영상 크기 (초점 위치 계산용) */
    @Volatile var streamSize: Size? = null
    /** 0이면 자동 노출. 0보다 크면 셔터를 이 값(ns)으로 고정하고 ISO는 manualIso. */
    @Volatile var manualExposureNs = 0L
    @Volatile var manualIso = 400
    @Volatile private var regions: Array<MeteringRectangle>? = null

    // ───────────────────────── 카메라 찾기 ─────────────────────────

    fun enumerate(): List<Cam> {
        val ids = manager.cameraIdList
        val chars = ids.associateWith { manager.getCameraCharacteristics(it) }
        val hidden = HashSet<String>()
        for (c in chars.values) {
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            if (caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)) {
                hidden.addAll(c.physicalCameraIds)
            }
        }
        val out = ArrayList<Cam>()
        var back = 0
        var front = 0
        var ext = 0
        for (id in ids) {
            if (id in hidden) continue
            val c = chars[id] ?: continue
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            if (!caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) continue
            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
            val facing = c.get(CameraCharacteristics.LENS_FACING)
            val label = when (facing) {
                CameraCharacteristics.LENS_FACING_BACK -> { back++; if (back == 1) "후면" else "후면 $back" }
                CameraCharacteristics.LENS_FACING_FRONT -> { front++; if (front == 1) "전면" else "전면 $front" }
                else -> { ext++; "외부 $ext" }
            }
            val focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val phys = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val eq = if (focals != null && focals.isNotEmpty() && phys != null) {
                val diag = sqrt(phys.width * phys.width + phys.height * phys.height)
                if (diag > 0f) focals[0] * 43.27f / diag else 24f
            } else 24f
            val zr = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            val zMin: Float
            val zMax: Float
            val zKey: Boolean
            if (zr != null) {
                zMin = zr.lower; zMax = zr.upper; zKey = true
            } else {
                zMin = 1f; zMax = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f; zKey = false
            }
            // 실제 렌즈 찾기 (논리 카메라 안의 물리 카메라들)
            val lensList = ArrayList<Lens>()
            if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                for (pid in c.physicalCameraIds) {
                    val pe = try { equivalent(manager.getCameraCharacteristics(pid)) } catch (e: Exception) { null } ?: continue
                    var z = pe / eq
                    if (z < zMin) z = zMin
                    if (z > zMax * 1.01f) continue
                    if (lensList.any { abs(it.zoom - z) / z < 0.08f }) continue
                    lensList.add(Lens(lensName(pe), z, pe.roundToInt()))
                }
            }
            // 줌 범위가 1배 아래로 내려가면 초광각이 있다는 뜻
            if (facing == CameraCharacteristics.LENS_FACING_BACK && lensList.isEmpty() && zMin < 0.9f) {
                lensList.add(Lens("초광각", zMin, (eq * zMin).roundToInt()))
                lensList.add(Lens("광각", 1f, eq.roundToInt()))
            }
            lensList.sortBy { it.zoom }
            if (lensList.isEmpty()) lensList.add(Lens(label, 1f.coerceIn(zMin, max(zMax, zMin)), eq.roundToInt()))
            val previewSizes = map.getOutputSizes(SurfaceHolder::class.java) ?: continue
            if (previewSizes.isEmpty()) continue
            val jpegSizes = map.getOutputSizes(ImageFormat.JPEG) ?: previewSizes
            out.add(
                Cam(
                    id = id,
                    label = label,
                    front = facing == CameraCharacteristics.LENS_FACING_FRONT,
                    sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
                    eq1 = eq,
                    zoomMin = zMin,
                    zoomMax = max(zMax, zMin),
                    zoomRatioKey = zKey,
                    fpsRanges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList() ?: emptyList(),
                    previewSize = pick16x9(previewSizes, 1920),
                    size4k = (map.getOutputSizes(android.graphics.SurfaceTexture::class.java) ?: previewSizes)
                        .firstOrNull { it.width == 3840 && it.height == 2160 },
                    jpegSize = pick16x9(jpegSizes, 4096),
                    streamSizes = (map.getOutputSizes(android.graphics.SurfaceTexture::class.java) ?: previewSizes).toList(),
                    jpegSizes = jpegSizes.toList(),
                    hasFlash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
                    evStep = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f,
                    evMinIdx = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.lower ?: 0,
                    evMaxIdx = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.upper ?: 0,
                    torchMaxLevel = if (android.os.Build.VERSION.SDK_INT >= 35)
                        (c.get(CameraCharacteristics.FLASH_TORCH_STRENGTH_MAX_LEVEL) ?: 1).coerceAtLeast(1) else 1,
                    torchDefaultLevel = if (android.os.Build.VERSION.SDK_INT >= 35)
                        (c.get(CameraCharacteristics.FLASH_TORCH_STRENGTH_DEFAULT_LEVEL) ?: 1).coerceAtLeast(1) else 1,
                    activeArray = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, 4000, 3000),
                    maxAf = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0,
                    maxAe = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0,
                    afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0),
                    stabModes = c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: IntArray(0),
                    nrModes = c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) ?: IntArray(0),
                    edgeModes = c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES) ?: IntArray(0),
                    aberrModes = c.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES) ?: IntArray(0),
                    manualSensor = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR),
                    manualPost = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING),
                    apertures = (c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES) ?: FloatArray(0)).toList().sorted(),
                    expMinNs = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.lower ?: 100_000L,
                    isoMin = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.lower ?: 50,
                    isoMax = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.upper ?: 3200,
                    expMaxNs = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.upper ?: 100_000_000L,
                    lenses = lensList
                )
            )
        }
        // 후면 → 전면 → 외부 순서
        return out.sortedBy { if (!it.front && !it.label.startsWith("외부")) 0 else if (it.front) 1 else 2 }
    }

    /** 사진 크기: 비율에 맞는 가장 큰 크기 (가로 4096 이하). */
    fun jpegSizeFor(c: Cam): Size {
        if (!aspect43) return c.jpegSize
        return c.jpegSizes.filter { it.width <= 4096 && abs(it.width * 3 - it.height * 4) <= it.width / 50 }
            .maxByOrNull { it.width.toLong() * it.height } ?: c.jpegSize
    }

    private fun pick16x9(sizes: Array<Size>, maxW: Int): Size {
        if (maxW == 1920) sizes.firstOrNull { it.width == 1920 && it.height == 1080 }?.let { return it }
        val wide = sizes.filter { it.width <= maxW && abs(it.width * 9 - it.height * 16) <= it.width / 50 }
        wide.maxByOrNull { it.width.toLong() * it.height }?.let { return it }
        return sizes.filter { it.width <= maxW }.maxByOrNull { it.width.toLong() * it.height } ?: sizes[0]
    }

    // ───────────────────────── 열기 / 세션 ─────────────────────────

    @SuppressLint("MissingPermission")
    fun open(c: Cam, previewSurface: Surface, video: Boolean) {
        close()
        val t = ++token
        cam = c
        preview = previewSurface
        videoMode = video
        regions = null
        aeLock = false
        awbLock = false
        try {
            manager.openCamera(c.id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    if (t != token) { d.close(); return }
                    device = d
                    createSession(t)
                }
                override fun onDisconnected(d: CameraDevice) {
                    d.close()
                    if (device === d) device = null
                }
                override fun onError(d: CameraDevice, error: Int) {
                    d.close()
                    if (device === d) device = null
                    cb.onCameraError("카메라 오류 ($error)")
                }
            }, handler)
        } catch (e: Exception) {
            cb.onCameraError("카메라를 열 수 없어요: ${e.message}")
        }
    }

    /** 사진 ↔ 영상 전환: 세션만 다시 만듦. */
    fun setVideoMode(video: Boolean) {
        if (video == videoMode) return
        videoMode = video
        handler.post {
            try { session?.close() } catch (e: Exception) {}
            session = null
            createSession(token)
        }
    }

    private fun createSession(t: Int) {
        val d = device ?: return
        val c = cam ?: return
        val p = preview ?: return
        try {
            val outs = ArrayList<Surface>()
            outs.add(p)
            if (!videoMode) {
                jpegReader?.close()
                val js = jpegSizeFor(c)
                val r = ImageReader.newInstance(js.width, js.height, ImageFormat.JPEG, 2)
                r.setOnImageAvailableListener({ saveJpeg(it) }, handler)
                jpegReader = r
                outs.add(r.surface)
            }
            val cfg = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outs.map { OutputConfiguration(it) },
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (t != token) { s.close(); return }
                        session = s
                        submit()
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        cb.onCameraError("카메라 설정에 실패했어요")
                    }
                })
            d.createCaptureSession(cfg)
        } catch (e: Exception) {
            cb.onCameraError("카메라 세션 오류: ${e.message}")
        }
    }

    // ───────────────────────── 요청 ─────────────────────────

    /** 설정이 바뀌었을 때 호출 (여러 번 불려도 한 번만 반영). */
    fun requestUpdate() {
        synchronized(this) {
            if (updatePending) return
            updatePending = true
        }
        handler.post {
            synchronized(this) { updatePending = false }
            submit()
        }
    }

    private fun applyCommon(b: CaptureRequest.Builder, c: Cam) {
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        val af = if (videoMode) CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO else CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        if (c.afModes.contains(af)) b.set(CaptureRequest.CONTROL_AF_MODE, af)
        if (videoMode) {
            c.fpsRange(fps)?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            if (c.stabModes.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)) {
                b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
            }
        }
        val z = zoom.coerceIn(c.zoomMin, c.zoomMax)
        if (c.zoomRatioKey) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, z)
        } else {
            val a = c.activeArray
            val w = (a.width() / z).toInt()
            val h = (a.height() / z).toInt()
            val l = a.left + (a.width() - w) / 2
            val tp = a.top + (a.height() - h) / 2
            b.set(CaptureRequest.SCALER_CROP_REGION, Rect(l, tp, l + w, tp + h))
        }
        b.set(CaptureRequest.CONTROL_AE_LOCK, aeLock)
        if (aperture > 0f && c.apertures.size > 1) b.set(CaptureRequest.LENS_APERTURE, aperture)
        if (c.evStep > 0f) {
            val idx = Math.round(evComp / c.evStep).coerceIn(c.evMinIdx, c.evMaxIdx)
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, idx)
        }
        if (c.hasFlash) {
            b.set(CaptureRequest.FLASH_MODE,
                if (torchLevel > 0) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF)
            if (torchLevel > 0 && c.torchMaxLevel > 1 && android.os.Build.VERSION.SDK_INT >= 35) {
                b.set(CaptureRequest.FLASH_STRENGTH_LEVEL, torchLevel.coerceIn(1, c.torchMaxLevel))
            }
        }
        if (manualExposureNs > 0 && c.manualSensor) {
            // 셔터 우선: 셔터 고정, ISO는 화면 밝기에 맞춰 앱이 조절
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, min(manualExposureNs, c.expMaxNs))
            b.set(CaptureRequest.SENSOR_SENSITIVITY, manualIso.coerceIn(c.isoMin, c.isoMax))
            val fd = if (videoMode) 1_000_000_000L / fps.coerceAtLeast(1) else 33_333_333L
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, max(fd, manualExposureNs))
        }
        val lg = lockedGains
        val lt = lockedTransform
        val mk = manualKelvin
        val rb = refBase
        val rt = refTransform
        if (mk > 0 && c.manualPost && rb > 0f && rt != null) {
            // 수동 색온도: 켈빈 값으로 빨강/파랑 세기를 계산해서 강제
            val ratio = 0.5f * (mk / 3000f).pow(1f / 0.66f)
            val sq = kotlin.math.sqrt(ratio)
            val g = android.hardware.camera2.params.RggbChannelVector(rb * sq, refGreen, refGreen, rb / sq)
            b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            b.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            b.set(CaptureRequest.COLOR_CORRECTION_GAINS, g)
            b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, rt)
        } else if (awbLock && c.manualPost && lg != null && lt != null) {
            // 잠글 때의 색 보정값을 그대로 강제 → 렌즈가 바뀌어도 같은 색
            b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            b.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            b.set(CaptureRequest.COLOR_CORRECTION_GAINS, lg)
            b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, lt)
        } else {
            b.set(CaptureRequest.CONTROL_AWB_LOCK, awbLock)
        }
        val r = regions
        if (r != null) {
            if (c.maxAf > 0) b.set(CaptureRequest.CONTROL_AF_REGIONS, r)
        }
    }

    private fun submit() {
        val d = device ?: return
        val s = session ?: return
        val c = cam ?: return
        val p = preview ?: return
        try {
            val b = d.createCaptureRequest(if (videoMode) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(p)
            applyCommon(b, c)
            s.setRepeatingRequest(b.build(), statsCallback, handler)
        } catch (e: Exception) {
            // 세션이 닫히는 중
        }
    }

    private val statsCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastStats < 200) return
            lastStats = now
            val exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
            val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
            if (!awbLock && manualKelvin == 0) {
                lastGains = gains
                lastTransform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
            }
            val k = if (gains != null && gains.blue > 0f) estimateKelvin(gains.red / gains.blue) else 0
            cb.onStats(exp, iso, k)
        }
    }

    /** 화이트밸런스 보정값으로 대략적인 색온도 추정 (센서마다 오차 있음). */
    private fun estimateKelvin(ratio: Float): Int {
        if (ratio <= 0f) return 0
        val k = 3000f * (ratio / 0.5f).pow(0.66f)
        return ((k.coerceIn(2000f, 10000f) / 100f).roundToInt() * 100)
    }

    /** 화면 좌표(센서 기준 0..1)로 초점 지점 (노출은 건드리지 않음). */
    fun setMeteringPoint(nx: Float, ny: Float) {
        val c = cam ?: return
        val a = c.activeArray
        val ss = streamSize ?: c.previewSize
        val fw = ss.width.toFloat()
        val fh = ss.height.toFloat()
        var cropW = a.width().toFloat()
        var cropH = cropW * fh / fw
        if (cropH > a.height()) { cropH = a.height().toFloat(); cropW = cropH * fw / fh }
        val ox = a.left + (a.width() - cropW) / 2f
        val oy = a.top + (a.height() - cropH) / 2f
        val px = ox + nx.coerceIn(0f, 1f) * cropW
        val py = oy + ny.coerceIn(0f, 1f) * cropH
        val half = (a.width() / 14).coerceAtLeast(20)
        val left = max(a.left, (px - half).toInt())
        val top = max(a.top, (py - half).toInt())
        val right = min(a.right - 1, (px + half).toInt())
        val bottom = min(a.bottom - 1, (py + half).toInt())
        if (right <= left || bottom <= top) return
        regions = arrayOf(MeteringRectangle(left, top, right - left, bottom - top, MeteringRectangle.METERING_WEIGHT_MAX))
        requestUpdate()
    }

    fun clearMetering() {
        regions = null
        requestUpdate()
    }

    // ───────────────────────── 사진 ─────────────────────────

    fun takePhoto(orientation: Int) {
        handler.post {
            val d = device ?: return@post
            val s = session ?: return@post
            val c = cam ?: return@post
            val r = jpegReader ?: return@post
            val p = preview ?: return@post
            try {
                val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                b.addTarget(p)
                b.addTarget(r.surface)
                applyCommon(b, c)
                b.set(CaptureRequest.JPEG_ORIENTATION, orientation)
                b.set(CaptureRequest.JPEG_QUALITY, 100.toByte())
                setHighQuality(b, c)
                s.capture(b.build(), null, handler)
            } catch (e: Exception) {
                cb.onCameraError("사진 촬영 실패: ${e.message}")
            }
        }
    }

    private fun saveJpeg(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        val bytes = try {
            val buf = image.planes[0].buffer
            ByteArray(buf.remaining()).also { buf.get(it) }
        } finally {
            image.close()
        }
        cb.onJpeg(bytes)
    }

    fun bitrate(): Int = if (fps >= 60) 30_000_000 else 20_000_000

    // ───────────────────────── 정리 ─────────────────────────

    private fun runSync(block: () -> Unit) {
        if (Looper.myLooper() == thread.looper) { block(); return }
        val latch = CountDownLatch(1)
        handler.post { try { block() } finally { latch.countDown() } }
        latch.await(2, TimeUnit.SECONDS)
    }

    fun close() {
        token++
        runSync {
            try { session?.close() } catch (e: Exception) {}
            try { device?.close() } catch (e: Exception) {}
            session = null
            device = null
            jpegReader?.close()
            jpegReader = null
        }
    }

    fun release() {
        close()
        thread.quitSafely()
    }

    companion object {
        const val FOLDER = "DCIM/ProCamera"
        fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    }
}
