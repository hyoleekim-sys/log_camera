package com.yangkim.logcamera

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.util.Size
import android.view.Choreographer
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign

class MainActivity : Activity(), CameraEngine.Callback {

    // ───── 뷰 ─────
    private lateinit var root: FrameLayout
    private lateinit var preview: SurfaceView
    private lateinit var flash: View
    private lateinit var focusRing: View
    private lateinit var uiRoot: FrameLayout
    private lateinit var level: LevelView
    private lateinit var infoMain: TextView
    private lateinit var infoSub: TextView
    private lateinit var histogram: HistogramView
    private lateinit var recTime: TextView
    private lateinit var leverBox: LinearLayout
    private lateinit var focalLabel: TextView
    private lateinit var zoomLever: ZoomLever
    private lateinit var controlBar: LinearLayout
    private lateinit var focalRow: LinearLayout
    private lateinit var settingsRow: LinearLayout
    private lateinit var shutterRow: LinearLayout
    private lateinit var btnFps: TextView
    private lateinit var btnWb: TextView
    private lateinit var btnAe: TextView
    private lateinit var btnMode: TextView
    private lateinit var thumb: ImageView
    private lateinit var shutter: View
    private lateinit var btnCamera: TextView

    // ───── 상태 ─────
    private lateinit var engine: CameraEngine
    private lateinit var orientationListener: OrientationEventListener
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private var cams: List<CameraEngine.Cam> = emptyList()
    private var camIndex = 0
    private val cam: CameraEngine.Cam? get() = cams.getOrNull(camIndex)

    private var isResumedNow = false
    private var started = false
    private var surfaceW = 0
    private var surfaceH = 0
    private var videoMode = true
    private var fps = 30
    private var zoom = 1f
    private var recordStart = 0L
    private var deviceDeg = 0
    private var uiDeg = 0
    private var lastUri: Uri? = null
    private var lastMime = "image/jpeg"
    private var lastExp = 0L
    private var lastIso = 0
    private var lastK = 0

    private data class FocalOption(val mm: Int, val zoom: Float, val enabled: Boolean)
    private var focalOptions: List<FocalOption> = emptyList()
    private val focalViews = ArrayList<TextView>()

    // 줌 애니메이션
    private var zoomAnimator: ValueAnimator? = null
    private var leverValue = 0f
    private var leverLooping = false
    private var lastFrameNs = 0L

    // ───────────────────────── 생명주기 ─────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        bindViews()
        engine = CameraEngine(this, this)

        preview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                surfaceW = width
                surfaceH = height
                maybeStart()
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceW = 0
                surfaceH = 0
                stopRecordingIfNeeded()
                engine.close()
                started = false
            }
        })

        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                onDeviceOrientation(orientation)
            }
        }

        root.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (r - l != oldR - oldL || b - t != oldB - oldT) {
                root.post {
                    layoutPreview()
                    applyUiOrientation(animate = false)
                }
            }
        }

        setupControls()
        updateButtons()

        val need = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).filter { !has(it) }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), REQ_PERMS)
    }

    override fun onResume() {
        super.onResume()
        isResumedNow = true
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        initCameras()
        maybeStart()
        main.post(histogramTick)
        main.post(statusTick)
    }

    override fun onPause() {
        stopRecordingIfNeeded()
        engine.close()
        started = false
        isResumedNow = false
        orientationListener.disable()
        main.removeCallbacks(histogramTick)
        main.removeCallbacks(statusTick)
        super.onPause()
    }

    override fun onDestroy() {
        engine.release()
        worker.shutdown()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        root.post {
            layoutPreview()
            applyUiOrientation(animate = false)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!has(Manifest.permission.CAMERA)) {
            toast("카메라 권한이 있어야 촬영할 수 있어요")
            return
        }
        initCameras()
        maybeStart()
    }

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    // ───────────────────────── 카메라 준비 ─────────────────────────

    private fun initCameras() {
        if (cams.isNotEmpty() || !has(Manifest.permission.CAMERA)) return
        cams = try { engine.enumerate() } catch (e: Exception) { emptyList() }
        if (cams.isEmpty()) {
            toast("사용할 수 있는 카메라가 없어요")
            return
        }
        selectCamera(0)
    }

    private fun selectCamera(i: Int) {
        camIndex = i
        val c = cam ?: return
        btnCamera.text = c.label
        zoom = 1f.coerceIn(c.zoomMin, c.zoomMax)
        if (!c.supportsFps(fps)) fps = listOf(30, 24, 60).firstOrNull { c.supportsFps(it) } ?: 30
        engine.fps = fps
        engine.zoom = zoom
        buildFocalButtons(c)
        started = false
        preview.holder.setFixedSize(c.previewSize.width, c.previewSize.height)
        layoutPreview()
        updateButtons()
        updateZoomUi()
        maybeStart()
    }

    /** 미리보기 서피스 크기가 맞을 때만 카메라를 엽니다. */
    private fun maybeStart() {
        val c = cam ?: return
        if (started || !isResumedNow || !has(Manifest.permission.CAMERA)) return
        if (surfaceW != c.previewSize.width || surfaceH != c.previewSize.height) return
        started = true
        engine.open(c, preview.holder.surface, videoMode)
    }

    /** 미리보기를 영상 비율(세로 9:16)에 맞춰 가운데 배치. */
    private fun layoutPreview() {
        val c = cam ?: return
        val w = root.width - root.paddingLeft - root.paddingRight
        val h = root.height - root.paddingTop - root.paddingBottom
        if (w <= 0 || h <= 0) return
        val portraitContent = c.sensorOrientation % 180 != 0
        val cw = if (portraitContent) c.previewSize.height else c.previewSize.width
        val ch = if (portraitContent) c.previewSize.width else c.previewSize.height
        val r = cw.toFloat() / ch
        val pw: Int
        val ph: Int
        if (w.toFloat() / h > r) { ph = h; pw = (h * r).roundToInt() } else { pw = w; ph = (w / r).roundToInt() }
        val lp = preview.layoutParams as FrameLayout.LayoutParams
        if (lp.width != pw || lp.height != ph) {
            lp.width = pw
            lp.height = ph
            lp.gravity = Gravity.CENTER
            preview.layoutParams = lp
        }
    }

    // ───────────────────────── 화면 회전 (UI만) ─────────────────────────

    private fun onDeviceOrientation(o: Int) {
        deviceDeg = o
        val target = when {
            o in 60..120 -> 90
            o in 240..300 -> 270
            o <= 30 || o >= 330 -> 0
            else -> uiDeg
        }
        if (target != uiDeg) {
            uiDeg = target
            applyUiOrientation(animate = true)
        }
        var t = (o - uiDeg).toFloat()
        if (t > 180f) t -= 360f
        if (t < -180f) t += 360f
        level.tilt = t
    }

    private fun applyUiOrientation(animate: Boolean) {
        val w = root.width - root.paddingLeft - root.paddingRight
        val h = root.height - root.paddingTop - root.paddingBottom
        if (w <= 0 || h <= 0) return
        val land = uiDeg != 0
        val doIt = {
            val lp = uiRoot.layoutParams as FrameLayout.LayoutParams
            lp.width = if (land) h else w
            lp.height = if (land) w else h
            lp.gravity = Gravity.CENTER
            uiRoot.layoutParams = lp
            uiRoot.rotation = when (uiDeg) { 90 -> -90f; 270 -> 90f; else -> 0f }
            arrangeControls(land)
        }
        if (animate) {
            uiRoot.animate().alpha(0f).setDuration(110).withEndAction {
                doIt()
                uiRoot.animate().alpha(1f).setDuration(160).start()
            }.start()
        } else {
            doIt()
        }
    }

    /** 세로: 버튼이 아래에 가로로. 가로: 버튼이 오른쪽에 세로로. */
    private fun arrangeControls(land: Boolean) {
        controlBar.orientation = if (land) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        for (row in listOf(focalRow, settingsRow, shutterRow)) {
            row.orientation = if (land) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        }
        val cl = controlBar.layoutParams as FrameLayout.LayoutParams
        if (land) {
            cl.width = FrameLayout.LayoutParams.WRAP_CONTENT
            cl.height = FrameLayout.LayoutParams.MATCH_PARENT
            cl.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        } else {
            cl.width = FrameLayout.LayoutParams.MATCH_PARENT
            cl.height = FrameLayout.LayoutParams.WRAP_CONTENT
            cl.gravity = Gravity.BOTTOM
        }
        controlBar.layoutParams = cl
        val ll = leverBox.layoutParams as FrameLayout.LayoutParams
        ll.gravity = if (land) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.END or Gravity.CENTER_VERTICAL
        leverBox.layoutParams = ll
    }

    // ───────────────────────── 컨트롤 ─────────────────────────

    private fun bindViews() {
        root = findViewById(R.id.root)
        preview = findViewById(R.id.preview)
        flash = findViewById(R.id.flash)
        focusRing = findViewById(R.id.focusRing)
        uiRoot = findViewById(R.id.uiRoot)
        level = findViewById(R.id.level)
        infoMain = findViewById(R.id.infoMain)
        infoSub = findViewById(R.id.infoSub)
        histogram = findViewById(R.id.histogram)
        recTime = findViewById(R.id.recTime)
        leverBox = findViewById(R.id.leverBox)
        focalLabel = findViewById(R.id.focalLabel)
        zoomLever = findViewById(R.id.zoomLever)
        controlBar = findViewById(R.id.controlBar)
        focalRow = findViewById(R.id.focalRow)
        settingsRow = findViewById(R.id.settingsRow)
        shutterRow = findViewById(R.id.shutterRow)
        btnFps = findViewById(R.id.btnFps)
        btnWb = findViewById(R.id.btnWb)
        btnAe = findViewById(R.id.btnAe)
        btnMode = findViewById(R.id.btnMode)
        thumb = findViewById(R.id.thumb)
        shutter = findViewById(R.id.shutter)
        btnCamera = findViewById(R.id.btnCamera)
    }

    private fun setupControls() {
        shutter.setOnClickListener { onShutter() }

        btnFps.setOnClickListener {
            val c = cam ?: return@setOnClickListener
            if (!videoMode || engine.recording) return@setOnClickListener
            val order = listOf(24, 30, 60)
            var idx = order.indexOf(fps)
            for (step in 1..3) {
                idx = (idx + 1) % order.size
                val f = order[idx]
                if (c.supportsFps(f)) {
                    fps = f
                    break
                } else {
                    toast("${f}fps는 이 카메라에서 막혀 있어요")
                }
            }
            engine.fps = fps
            engine.requestUpdate()
            updateButtons()
        }

        btnWb.setOnClickListener {
            engine.awbLock = !engine.awbLock
            engine.requestUpdate()
            updateButtons()
        }

        btnAe.setOnClickListener {
            engine.aeLock = !engine.aeLock
            engine.requestUpdate()
            updateButtons()
        }

        btnMode.setOnClickListener {
            if (engine.recording) return@setOnClickListener
            videoMode = !videoMode
            engine.setVideoMode(videoMode)
            updateButtons()
        }

        btnCamera.setOnClickListener {
            if (engine.recording || cams.size < 2) return@setOnClickListener
            engine.close()
            started = false
            selectCamera((camIndex + 1) % cams.size)
        }

        thumb.setOnClickListener {
            val u = lastUri ?: return@setOnClickListener
            try {
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(u, lastMime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            } catch (e: Exception) {
                toast("갤러리를 열 수 없어요")
            }
        }

        zoomLever.onChange = { v ->
            leverValue = v
            if (abs(v) > 0.01f) {
                zoomAnimator?.cancel()
                if (!leverLooping) {
                    leverLooping = true
                    lastFrameNs = 0L
                    Choreographer.getInstance().postFrameCallback(leverFrame)
                }
            }
        }

        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                onTapFocus(e.x, e.y)
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                engine.clearMetering()
                focusRing.visibility = View.INVISIBLE
                toast("초점·노출 자동")
            }
        })
        preview.setOnTouchListener { _, ev -> detector.onTouchEvent(ev) }
    }

    private fun updateButtons() {
        val c = cam
        btnFps.text = "${fps}p"
        btnFps.alpha = if (videoMode) 1f else 0.35f
        setLocked(btnWb, engine.awbLock, "AWB", "WB 잠금")
        setLocked(btnAe, engine.aeLock, "AE", "AE 잠금")
        btnMode.text = if (videoMode) "영상" else "사진"
        shutter.setBackgroundResource(
            when {
                engine.recording -> R.drawable.shutter_recording
                videoMode -> R.drawable.shutter_video
                else -> R.drawable.shutter_photo
            }
        )
        recTime.visibility = if (engine.recording) View.VISIBLE else View.GONE
        val lock = if (engine.recording) 0.35f else 1f
        btnMode.alpha = lock
        btnCamera.alpha = if (engine.recording || (c != null && cams.size < 2)) 0.35f else 1f
        if (videoMode) btnFps.alpha = lock
        updateInfoSub()
    }

    private fun setLocked(v: TextView, locked: Boolean, off: String, on: String) {
        v.text = if (locked) on else off
        v.setBackgroundResource(if (locked) R.drawable.chip_selected_bg else R.drawable.chip_bg)
    }

    // ───────────────────────── 화각 / 줌 ─────────────────────────

    private fun buildFocalButtons(c: CameraEngine.Cam) {
        val targets = listOf(14, 24, 35, 50, 85)
        val minMm = c.eq1 * c.zoomMin
        val maxMm = c.eq1 * c.zoomMax
        focalOptions = targets.mapIndexed { i, mm ->
            var z = mm / c.eq1
            var label = mm
            var ok = mm >= minMm * 0.97f && mm <= maxMm * 1.01f
            // 가장 넓은 칸: 렌즈가 14mm까지 안 되면 가장 넓은 화각으로
            if (i == 0 && !ok && minMm < targets[1] * 0.97f) {
                z = c.zoomMin
                label = minMm.roundToInt()
                ok = true
            }
            FocalOption(label, z.coerceIn(c.zoomMin, c.zoomMax), ok)
        }
        focalRow.removeAllViews()
        focalViews.clear()
        for (opt in focalOptions) {
            val tv = TextView(this, null, 0, R.style.Ctl).apply {
                text = "${opt.mm}"
                alpha = if (opt.enabled) 1f else 0.3f
                setOnClickListener {
                    if (opt.enabled) animateZoomTo(opt.zoom)
                    else toast("${opt.mm}mm는 이 카메라에서 쓸 수 없어요")
                }
            }
            val lp = LinearLayout.LayoutParams(dp(54), LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(dp(3), dp(3), dp(3), dp(3))
            focalRow.addView(tv, lp)
            focalViews.add(tv)
        }
    }

    /** 화각 버튼: 줌인/줌아웃되듯 부드럽게 이동. */
    private fun animateZoomTo(target: Float) {
        zoomAnimator?.cancel()
        val from = zoom
        if (abs(from - target) < 0.001f) return
        val ratio = target / from
        zoomAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (380 + 260 * abs(ln(ratio)) / ln(4f)).toLong().coerceAtMost(900)
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                setZoom(from * ratio.pow(t))
            }
            start()
        }
    }

    /** 레버: 많이 밀수록 빠르게 (속도 = 밀린 정도의 제곱). */
    private val leverFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val v = leverValue
            if (abs(v) < 0.01f) {
                leverLooping = false
                return
            }
            if (lastFrameNs != 0L) {
                val dt = (frameTimeNanos - lastFrameNs) / 1e9f
                val rate = sign(v) * v * v * MAX_ZOOM_SPEED
                setZoom(zoom * exp(rate * dt))
            }
            lastFrameNs = frameTimeNanos
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun setZoom(z: Float) {
        val c = cam ?: return
        val nz = z.coerceIn(c.zoomMin, c.zoomMax)
        if (abs(nz - zoom) < 0.0005f && nz == zoom) return
        zoom = nz
        engine.zoom = nz
        engine.requestUpdate()
        updateZoomUi()
    }

    private fun updateZoomUi() {
        val c = cam ?: return
        val mm = (c.eq1 * zoom).roundToInt()
        focalLabel.text = String.format(Locale.US, "%dmm  %.1fx", mm, zoom)
        for ((i, tv) in focalViews.withIndex()) {
            val opt = focalOptions.getOrNull(i) ?: continue
            val on = opt.enabled && abs(opt.zoom - zoom) / opt.zoom < 0.03f
            tv.setBackgroundResource(if (on) R.drawable.chip_selected_bg else R.drawable.chip_bg)
        }
    }

    // ───────────────────────── 탭 초점 ─────────────────────────

    private fun onTapFocus(x: Float, y: Float) {
        val c = cam ?: return
        val w = preview.width.toFloat()
        val h = preview.height.toFloat()
        if (w <= 0f || h <= 0f) return
        var u = x / w
        val v = y / h
        if (c.front) u = 1f - u
        val (nx, ny) = when (c.sensorOrientation) {
            90 -> Pair(v, 1f - u)
            180 -> Pair(1f - u, 1f - v)
            270 -> Pair(1f - v, u)
            else -> Pair(u, v)
        }
        engine.setMeteringPoint(nx, ny)
        focusRing.x = preview.x + x - focusRing.width / 2f
        focusRing.y = preview.y + y - focusRing.height / 2f
        focusRing.visibility = View.VISIBLE
        focusRing.alpha = 1f
        focusRing.scaleX = 1.3f
        focusRing.scaleY = 1.3f
        focusRing.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
        focusRing.animate().alpha(0.35f).setStartDelay(1200).setDuration(600).start()
    }

    // ───────────────────────── 촬영 ─────────────────────────

    /** 저장 방향 (Camera2 공식). 폰을 든 방향 기준. */
    private fun outputRotation(c: CameraEngine.Cam): Int {
        val d = if (c.front) -uiDeg else uiDeg
        return (c.sensorOrientation + d + 360) % 360
    }

    private fun onShutter() {
        val c = cam ?: return
        if (!videoMode) {
            flash.alpha = 0.6f
            flash.animate().alpha(0f).setDuration(220).start()
            engine.takePhoto(outputRotation(c))
            return
        }
        if (engine.recording) {
            stopRecordingIfNeeded()
        } else {
            if (engine.startRecording(outputRotation(c), has(Manifest.permission.RECORD_AUDIO))) {
                recordStart = SystemClock.elapsedRealtime()
                main.post(recTick)
            }
            updateButtons()
        }
    }

    private fun stopRecordingIfNeeded() {
        if (!engine.recording) return
        val uri = engine.stopRecording()
        updateButtons()
        if (uri != null) {
            lastUri = uri
            lastMime = "video/mp4"
            loadThumb(uri)
            toast("영상 저장됨 · DCIM/ProCamera")
        } else {
            toast("영상 저장 실패 (너무 짧았을 수 있어요)")
        }
    }

    override fun onPhotoSaved(uri: Uri?) {
        runOnUiThread {
            if (uri != null) {
                lastUri = uri
                lastMime = "image/jpeg"
                loadThumb(uri)
            } else {
                toast("사진 저장 실패")
            }
        }
    }

    private fun loadThumb(uri: Uri) {
        worker.execute {
            val bmp: Bitmap? = try { contentResolver.loadThumbnail(uri, Size(160, 160), null) } catch (e: Exception) { null }
            if (bmp != null) runOnUiThread { thumb.setImageBitmap(bmp) }
        }
    }

    private val recTick = object : Runnable {
        override fun run() {
            if (!engine.recording) return
            val s = (SystemClock.elapsedRealtime() - recordStart) / 1000
            recTime.text = String.format(Locale.US, "● %02d:%02d", s / 60, s % 60)
            main.postDelayed(this, 500)
        }
    }

    // ───────────────────────── 정보 표시 ─────────────────────────

    override fun onStats(exposureNs: Long, iso: Int, kelvin: Int) {
        lastExp = exposureNs
        lastIso = iso
        lastK = kelvin
        runOnUiThread { updateInfoMain() }
    }

    override fun onCameraError(message: String) {
        runOnUiThread { toast(message) }
    }

    private fun updateInfoMain() {
        val shutterTxt = when {
            lastExp <= 0L -> "—"
            lastExp >= 500_000_000L -> String.format(Locale.US, "%.1fs", lastExp / 1e9)
            else -> "1/" + (1e9 / lastExp).roundToInt()
        }
        val k = if (lastK > 0) "≈${lastK}K" else "—"
        val locks = buildString {
            if (engine.aeLock) append("  [AE]")
            if (engine.awbLock) append("  [WB]")
        }
        infoMain.text = String.format(Locale.US, "%-8s ISO %-5d %s%s", shutterTxt, lastIso, k, locks)
    }

    private fun updateInfoSub() {
        val c = cam
        val res = if (c != null) "${minOf(c.previewSize.width, c.previewSize.height)}p" else "—"
        val bat = (getSystemService(BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val free = try {
            StatFs(Environment.getExternalStorageDirectory().path).availableBytes
        } catch (e: Exception) { 0L }
        infoSub.text = if (videoMode) {
            val bytesPerSec = engine.bitrate() / 8L + 24_000L
            val mins = free / bytesPerSec / 60
            String.format(Locale.US, "%s · %dfps · 남은 %d시간 %02d분 · 배터리 %d%%", res, fps, mins / 60, mins % 60, bat)
        } else {
            val shots = free / 4_000_000L
            String.format(Locale.US, "사진 16:9 · 약 %,d장 · 배터리 %d%%", shots, bat)
        }
    }

    private val statusTick = object : Runnable {
        override fun run() {
            updateInfoSub()
            main.postDelayed(this, 5000)
        }
    }

    private var histBmp: Bitmap? = null
    private val histogramTick = object : Runnable {
        override fun run() {
            if (!isResumedNow) return
            if (started && surfaceW > 0) {
                val bmp = histBmp ?: Bitmap.createBitmap(72, 128, Bitmap.Config.ARGB_8888).also { histBmp = it }
                try {
                    PixelCopy.request(preview, bmp, { result ->
                        if (result == PixelCopy.SUCCESS) histogram.update(bmp)
                    }, main)
                } catch (e: Exception) {
                }
            }
            main.postDelayed(this, 300)
        }
    }

    // ───────────────────────── 유틸 ─────────────────────────

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REQ_PERMS = 1
        /** 레버를 끝까지 밀었을 때 줌 속도 (초당 약 4.5배) */
        private const val MAX_ZOOM_SPEED = 1.5f
    }
}
