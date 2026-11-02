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
        fun onPhotoSaved(uri: Uri?)
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
        val stabModes: IntArray
    ) {
        fun fpsRange(fps: Int): Range<Int>? = fpsRanges.filter { it.upper == fps }.maxByOrNull { it.lower }
        fun supportsFps(fps: Int) = fpsRange(fps) != null
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
    private var recSurface: Surface? = null
    private var recorder: MediaRecorder? = null
    private var recUri: Uri? = null
    private var recPfd: ParcelFileDescriptor? = null
    @Volatile private var token = 0
    private var lastStats = 0L
    private var updatePending = false

    @Volatile var videoMode = true
        private set
    @Volatile var fps = 30
    @Volatile var zoom = 1f
    @Volatile var aeLock = false
    @Volatile var awbLock = false
    @Volatile var recording = false
        private set
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
                    jpegSize = pick16x9(jpegSizes, 100000),
                    activeArray = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, 4000, 3000),
                    maxAf = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0,
                    maxAe = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0,
                    afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0),
                    stabModes = c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: IntArray(0)
                )
            )
        }
        // 후면 → 전면 → 외부 순서
        return out.sortedBy { if (!it.front && !it.label.startsWith("외부")) 0 else if (it.front) 1 else 2 }
    }

    private fun pick16x9(sizes: Array<Size>, maxW: Int): Size {
        sizes.firstOrNull { it.width == 1920 && it.height == 1080 && maxW >= 1920 && maxW < 100000 }?.let { return it }
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
        if (recording || video == videoMode) return
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
            if (videoMode) {
                val s = recSurface ?: MediaCodec.createPersistentInputSurface().also { recSurface = it }
                primeRecorderSurface(s, c)
                outs.add(s)
            } else {
                jpegReader?.close()
                val r = ImageReader.newInstance(c.jpegSize.width, c.jpegSize.height, ImageFormat.JPEG, 2)
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

    /** 녹화용 서피스를 미리 준비 (크기 지정). */
    private fun primeRecorderSurface(s: Surface, c: Cam) {
        val r = MediaRecorder(ctx)
        try {
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setOutputFile(File(ctx.cacheDir, "prime.mp4").absolutePath)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            r.setVideoSize(c.previewSize.width, c.previewSize.height)
            r.setVideoFrameRate(30)
            r.setInputSurface(s)
            r.prepare()
        } catch (e: Exception) {
        } finally {
            r.release()
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
        b.set(CaptureRequest.CONTROL_AWB_LOCK, awbLock)
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
            if (recording) recSurface?.let { b.addTarget(it) }
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
        val fw = c.previewSize.width.toFloat()
        val fh = c.previewSize.height.toFloat()
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
                b.set(CaptureRequest.JPEG_QUALITY, 95.toByte())
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
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_${stamp()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, FOLDER)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        if (uri == null) { cb.onPhotoSaved(null); return }
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            cb.onPhotoSaved(uri)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            cb.onPhotoSaved(null)
        }
    }

    // ───────────────────────── 녹화 ─────────────────────────

    fun startRecording(orientation: Int, withAudio: Boolean): Boolean {
        val c = cam ?: return false
        val s = recSurface ?: return false
        if (!videoMode || session == null || recording) return false
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "VID_${stamp()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, FOLDER)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: return false
        val pfd = try { resolver.openFileDescriptor(uri, "rw") } catch (e: Exception) { null }
        if (pfd == null) { resolver.delete(uri, null, null); return false }
        val r = MediaRecorder(ctx)
        try {
            if (withAudio) r.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setOutputFile(pfd.fileDescriptor)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            r.setVideoSize(c.previewSize.width, c.previewSize.height)
            r.setVideoFrameRate(fps)
            r.setVideoEncodingBitRate(bitrate())
            if (withAudio) {
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioSamplingRate(48000)
                r.setAudioEncodingBitRate(192_000)
                r.setAudioChannels(2)
            }
            r.setInputSurface(s)
            r.setOrientationHint(orientation)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            try { pfd.close() } catch (x: Exception) {}
            resolver.delete(uri, null, null)
            cb.onCameraError("녹화를 시작할 수 없어요: ${e.message}")
            return false
        }
        recorder = r
        recUri = uri
        recPfd = pfd
        recording = true
        requestUpdate()
        return true
    }

    fun stopRecording(): Uri? {
        if (!recording) return null
        recording = false
        runSync { submit() }
        val r = recorder
        recorder = null
        var ok = true
        if (r != null) {
            try { r.stop() } catch (e: RuntimeException) { ok = false }
            r.release()
        }
        try { recPfd?.close() } catch (e: Exception) {}
        recPfd = null
        val u = recUri ?: return null
        recUri = null
        val resolver = ctx.contentResolver
        return if (ok) {
            resolver.update(u, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            u
        } else {
            try { resolver.delete(u, null, null) } catch (e: Exception) {}
            null
        }
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
        if (recording) stopRecording()
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
        recSurface?.release()
        recSurface = null
        thread.quitSafely()
    }

    companion object {
        const val FOLDER = "DCIM/ProCamera"
        private fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    }
}
