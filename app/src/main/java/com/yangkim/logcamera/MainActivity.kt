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
    private lateinit var topControls: LinearLayout
    private lateinit var btnGrade: TextView
    private lateinit var gradeListPanel: LinearLayout
    private lateinit var gradeList: LinearLayout
    private lateinit var gradePanel: LinearLayout
    private lateinit var gradeTitle: TextView
    private lateinit var curveView: CurveView
    private lateinit var gradeScroll: View
    private lateinit var gradeSliders: LinearLayout
    private lateinit var focalRow: LinearLayout
    private lateinit var settingsRow: LinearLayout
    private lateinit var shutterRow: LinearLayout
    private lateinit var btnFps: TextView
    private lateinit var btnRes: TextView
    private lateinit var btnAspect: TextView
    private lateinit var flashSlider: FlashSlider
    private lateinit var btnSelfie: TextView
    private lateinit var rearSelfie: RearSelfie
    private var aspect43 = false
    private var res4k = false
    private lateinit var btnWb: TextView
    private lateinit var btnAe: TextView
    private lateinit var modePhoto: ImageView
    private lateinit var modeVideo: ImageView
    private lateinit var thumb: ImageView
    private lateinit var shutter: View
    private lateinit var btnCamera: TextView

    // ───── 상태 ─────
    private lateinit var engine: CameraEngine
    private lateinit var renderer: GlRenderer
    private lateinit var recorder: AvRecorder
    private lateinit var audioManager: android.media.AudioManager
    private lateinit var btnMic: TextView
    private lateinit var evLabel: TextView
    private lateinit var dialRow: LinearLayout
    private lateinit var dialAperture: ValueDial
    private lateinit var dialShutter: ValueDial
    private lateinit var dialIso: ValueDial
    /** 사진 수동 설정: 셔터(ns)·ISO 후보와 선택 */
    private var shutterChoices: List<Long> = emptyList()
    private var isoChoices: List<Int> = emptyList()
    private var shutterManual = false
    private var isoManual = false
    private var expF = 16_666_667f
    private lateinit var monitor: AudioMonitor
    private lateinit var btnMonitor: TextView
    private lateinit var levelMeter: LevelMeter
    private lateinit var volumeRow: View
    private lateinit var volLabel: TextView
    private var monitorVol = 5
    private lateinit var screenLight: ScreenLight
    /** 전면 셀피 화면 조명 단계 0~5 */
    private var lightLevel = 0
    private var lightL = 0
    private var lightT = 0
    private var lightR = 0
    private var lightB = 0
    /** 밝기 보정: 0.3EV 단위 칸 수 */
    private var evSteps = 0
    private lateinit var recBorder: View
    /** 0 = 폰 마이크, 1 = 블루투스, 2 = 둘 다 */
    private var micMode = 0
    private lateinit var gradeStore: GradeStore
    private lateinit var currentGrade: Grade
    private var editingGrade: Grade? = null
    private var editingIsNew = false
    @Volatile private var currentLut: FloatArray? = null
    private val lutExecutor = Executors.newSingleThreadExecutor()
    private val lutGen = java.util.concurrent.atomic.AtomicInteger()
    private var glReady = false
    private lateinit var orientationListener: OrientationEventListener
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private var cams: List<CameraEngine.Cam> = emptyList()

    /** 카메라 버튼으로 고르는 항목: 후면 렌즈마다 하나씩 + 전면. */
    private class CamEntry(val camIndex: Int, val zoom: Float?, val label: String)
    private var entries: List<CamEntry> = emptyList()
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
        CrashLog.install(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        bindViews()
        engine = CameraEngine(this, this)
        recorder = AvRecorder(this)
        recorder.onWarning = { m -> runOnUiThread { toast(m) } }
        audioManager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        audioManager.registerAudioDeviceCallback(object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>) { onMicDevicesChanged() }
            override fun onAudioDevicesRemoved(removed: Array<out android.media.AudioDeviceInfo>) { onMicDevicesChanged() }
        }, main)
        gradeStore = GradeStore(this)
        currentGrade = gradeStore.selected()
        renderer = GlRenderer()
        renderer.start {
            runOnUiThread {
                glReady = true
                applyGrade(currentGrade)
                maybeStart()
            }
        }

        preview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                surfaceW = width
                surfaceH = height
                renderer.setWindowSurface(holder.surface, width, height)
                maybeStart()
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceW = 0
                surfaceH = 0
                renderer.setWindowSurface(null, 0, 0)
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
                    if (lightLevel > 0) setLight(lightLevel) else layoutPreview()
                    applyUiOrientation(animate = false)
                }
            }
        }

        rearSelfie = RearSelfie(this, renderer,
            onStateChanged = { runOnUiThread { syncSelfieUi(); updateButtons() } },
            onShutter = { runOnUiThread { onShutter() } },
            onMessage = { m -> runOnUiThread { toast(m) } })
        // 후면 셀피: 끔 → 듀얼(바깥은 미리보기) → 셀피(바깥에서 전부 조작) → 끔
        // 끔 → 듀얼(바깥은 미리보기·셔터, 조작은 안쪽) → 셀피(안쪽은 꺼지고 바깥에서 전부 조작) → 끔
        btnSelfie.setOnClickListener {
            try {
                when {
                    !rearSelfie.active -> { rearSelfie.fullControl = false; openSelfie() }
                    !rearSelfie.fullControl -> rearSelfie.fullControl = true
                    else -> rearSelfie.close()
                }
                syncSelfieUi()
            } catch (e: Throwable) {
                CrashLog.save(this, e)
                toast("셀피를 켜다가 오류가 났어요: ${e.message}")
            }
        }
        setupControls()
        addHaptics(uiRoot)
        updateButtons()
        // 셀피 중 안쪽 큰 화면: 검게 + 터치 막기
        innerBlocker = View(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            isClickable = true
            visibility = View.GONE
        }
        root.addView(innerBlocker, FrameLayout.LayoutParams(-1, -1))
        CrashLog.showIfAny(this)

        val need = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).filter { !has(it) }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), REQ_PERMS)
    }

    override fun onResume() {
        super.onResume()
        isResumedNow = true
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        initCameras()
        maybeStart()
        if (cam != null) updateShutterPriority()
        updateMonitorUi()
        rearSelfie.start()
        applyMicRoute()
        main.post(histogramTick)
        main.post(statusTick)
    }

    override fun onPause() {
        stopRecordingIfNeeded()
        engine.close()
        started = false
        isResumedNow = false
        orientationListener.disable()
        rearSelfie.stop()
        try {
            audioManager.clearCommunicationDevice()
            if (commMode) { audioManager.mode = android.media.AudioManager.MODE_NORMAL; commMode = false }
        } catch (e: Exception) {}
        if (monitor.running) { monitor.stop(); updateMonitorUi() }
        setLight(0)
        main.removeCallbacks(histogramTick)
        main.removeCallbacks(statusTick)
        super.onPause()
    }

    override fun onDestroy() {
        engine.release()
        rearSelfie.release()
        renderer.release()
        lutExecutor.shutdown()
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

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            gradePanel.visibility == View.VISIBLE -> closeGradeEditor(revert = true)
            gradeListPanel.visibility == View.VISIBLE -> showGradeList(false)
            else -> super.onBackPressed()
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
        entries = cams.flatMapIndexed { i, c ->
            if (!c.front && c.lenses.size > 1) {
                // 광각을 먼저, 그다음 초광각·망원
                val sorted = c.lenses.sortedBy { if (abs(it.zoom - 1f) < 0.05f) 0 else 1 }
                sorted.map { CamEntry(i, it.zoom, it.name) }
            } else {
                listOf(CamEntry(i, null, if (c.front) c.label else c.lenses.firstOrNull()?.name ?: c.label))
            }
        }
        selectCamera(0)
    }

    /** 지금 보고 있는 렌즈 이름 (줌 배율 기준). */
    private fun currentEntryIndex(): Int {
        val list = entries.withIndex().filter { it.value.camIndex == camIndex }
        if (list.isEmpty()) return 0
        val withZoom = list.filter { it.value.zoom != null }
        if (withZoom.isEmpty()) return list.first().index
        // 지금 줌 이하에서 가장 큰 렌즈 (폰도 그 렌즈를 씀)
        val active = withZoom.filter { it.value.zoom!! <= zoom * 1.001f }.maxByOrNull { it.value.zoom!! }
        return (active ?: withZoom.minByOrNull { it.value.zoom!! }!!).index
    }

    private fun selectCamera(i: Int) {
        camIndex = i
        val c = cam ?: return
        zoom = 1f.coerceIn(c.zoomMin, c.zoomMax)
        buildDialChoices(c)
        if (!c.hasFlash) engine.torchLevel = 0
        val screenFlash = c.front && !c.hasFlash
        if (!screenFlash && lightLevel > 0) setLight(0)
        flashSlider.title = if (screenFlash) "화면조명" else "플래시"
        flashSlider.levels = if (screenFlash) 5 else c.torchMaxLevel
        flashSlider.setLevelSilently(if (screenFlash) lightLevel else engine.torchLevel.coerceAtMost(c.torchMaxLevel))
        flashSlider.enabledFlash = c.hasFlash || screenFlash
        if (!c.supportsFps(fps)) fps = listOf(30, 24, 60).firstOrNull { c.supportsFps(it) } ?: 30
        engine.fps = fps
        engine.zoom = zoom
        updateShutterPriority()
        buildFocalButtons(c)
        started = false
        layoutPreview()
        updateButtons()
        updateZoomUi()
        maybeStart()
    }

    /** 미리보기 서피스 크기가 맞을 때만 카메라를 엽니다. */
    /** 카메라에서 받는 영상 크기: 4K 선택 시 3840x2160, 아니면 1080p. */
    private fun streamSize(c: CameraEngine.Cam): Size = pickStream(c, aspect43, res4k) ?: c.previewSize

    private fun is43(s: Size) = abs(s.width * 3 - s.height * 4) <= s.width / 50

    /** 비율·해상도에 맞는 영상 크기. 없으면 null. */
    private fun pickStream(c: CameraEngine.Cam, a43: Boolean, k4: Boolean): Size? {
        if (!a43) return if (k4) c.size4k else c.previewSize
        val list = c.streamSizes.filter { is43(it) }
        return if (k4) {
            list.filter { it.width in 3000..4096 }.maxByOrNull { it.width.toLong() * it.height }
        } else {
            list.firstOrNull { it.width == 1440 && it.height == 1080 }
                ?: list.filter { it.height <= 1080 }.maxByOrNull { it.width.toLong() * it.height }
        }
    }

    /** 세로로 세워 저장할 때 인코더가 이 크기를 녹화할 수 있는지 */
    private fun canRecord(c: CameraEngine.Cam, s: Size): Boolean {
        val portrait = c.sensorOrientation % 180 != 0
        val (w, h) = if (portrait) Pair(s.height, s.width) else Pair(s.width, s.height)
        return encoderSupports(w, h, fps)
    }

    /** 비율/해상도가 바뀌면 카메라를 다시 엶 */
    private fun restartStream() {
        engine.close()
        started = false
        layoutPreview()
        maybeStart()
        updateButtons()
    }

    /** 녹화 비트레이트 */
    private fun bitrate(): Int = when {
        res4k && fps >= 60 -> 80_000_000
        res4k -> 50_000_000
        fps >= 60 -> 30_000_000
        else -> 20_000_000
    }

    /** 이 폰의 H.264 인코더가 이 크기를 녹화할 수 있는지 */
    private fun encoderSupports(w: Int, h: Int, f: Int): Boolean = try {
        android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals("video/avc", true) } &&
                info.getCapabilitiesForType("video/avc").videoCapabilities.let { vc ->
                    vc.isSizeSupported(w, h) && vc.areSizeAndRateSupported(w, h, f.toDouble())
                }
        }
    } catch (e: Exception) { false }

    private fun maybeStart() {
        val c = cam ?: return
        if (started || !glReady || !isResumedNow || !has(Manifest.permission.CAMERA)) return
        started = true
        val size = streamSize(c)
        engine.streamSize = size
        engine.aspect43 = aspect43
        renderer.configure(size.width, size.height, c.sensorOrientation)
        engine.open(c, renderer.cameraSurface, videoMode)
    }

    /** 미리보기를 영상 비율(세로 9:16)에 맞춰 가운데 배치. */
    /** 보정창이 가리는 화면 위/아래 영역 (px). 열려 있으면 미리보기를 남은 곳에 줄여서 보여줌. */
    private var insetTop = 0
    private var insetBottom = 0

    private fun gradePanelOpen() =
        gradePanel.visibility == View.VISIBLE || gradeListPanel.visibility == View.VISIBLE

    /** 보정창 크기에 맞춰 미리보기 자리 다시 계산. */
    private fun updatePreviewInsets() {
        val open = gradePanelOpen()
        insetTop = 0
        insetBottom = 0
        renderer.mirrorInsetTop = 0
        renderer.mirrorInsetBottom = 0
        if (open && uiInCover) {
            // 셀피(바깥 화면): 바깥 미리보기를 보정창 위로 줄여서
            val panel = if (gradePanel.visibility == View.VISIBLE) gradePanel else gradeListPanel
            renderer.mirrorInsetBottom = panel.height
        } else if (open) {
            val panel = if (gradePanel.visibility == View.VISIBLE) gradePanel else gradeListPanel
            when (uiDeg) {
                0 -> insetBottom = panel.height
                270 -> insetBottom = panel.width   // 판이 시계방향으로 돌아 오른쪽 → 화면 아래
                90 -> insetTop = panel.width       // 반시계방향으로 돌아 오른쪽 → 화면 위
            }
        }
        // 미리보기를 가리는 정보 표시는 보정 중엔 숨김
        val overlay = if (open) View.GONE else View.VISIBLE
        uiRoot.findViewById<View>(R.id.infoBar).visibility = overlay
        histogram.visibility = overlay
        uiRoot.findViewById<View>(R.id.monitorBox).visibility = overlay
        level.visibility = overlay
        layoutPreview()
    }

    private fun layoutPreview() {
        val c = cam ?: return
        val fullW = root.width - root.paddingLeft - root.paddingRight
        val full = root.height - root.paddingTop - root.paddingBottom
        val w = fullW - lightL - lightR
        val h = full - insetTop - insetBottom - lightT - lightB
        if (w <= 0 || h <= 0) return
        val portraitContent = c.sensorOrientation % 180 != 0
        val ss = streamSize(c)
        val cw = if (portraitContent) ss.height else ss.width
        val ch = if (portraitContent) ss.width else ss.height
        val r = cw.toFloat() / ch
        val pw: Int
        val ph: Int
        if (w.toFloat() / h > r) { ph = h; pw = (h * r).roundToInt() } else { pw = w; ph = (w / r).roundToInt() }
        val lp = preview.layoutParams as FrameLayout.LayoutParams
        val top = insetTop + lightT + (h - ph) / 2
        val left = lightL + (w - pw) / 2
        if (lp.width != pw || lp.height != ph || lp.topMargin != top || lp.leftMargin != left) {
            lp.width = pw
            lp.height = ph
            lp.gravity = Gravity.TOP or Gravity.START
            lp.topMargin = top
            lp.leftMargin = left
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
        if (uiInCover) return
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
            if (gradePanelOpen()) uiRoot.post { updatePreviewInsets() }
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

    /** 세로: 버튼이 아래에 가로로. 가로: 버튼이 오른쪽에 세로로, 레버는 그 바로 왼쪽. */
    private fun arrangeControls(land: Boolean) {
        // 설정 버튼 + 카메라 버튼: 가로에선 작게 오른쪽 위로
        (settingsRow.parent as? LinearLayout)?.removeView(settingsRow)
        (btnCamera.parent as? LinearLayout)?.removeView(btnCamera)
        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
        val small = listOf(btnAspect, btnRes, btnFps, btnMic, btnWb, btnGrade, btnSelfie, btnCamera)
        if (land) {
            settingsRow.orientation = LinearLayout.HORIZONTAL
            topControls.addView(settingsRow, LinearLayout.LayoutParams(wrap, wrap))
            topControls.addView(btnCamera, LinearLayout.LayoutParams(wrap, wrap).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            for (v in small) {
                v.textSize = 11f
                v.minWidth = dp(40)
                v.minimumWidth = dp(40)
                v.setPadding(dp(8), dp(4), dp(8), dp(4))
            }
            topControls.visibility = View.VISIBLE
        } else {
            controlBar.addView(settingsRow, controlBar.indexOfChild(dialRow) + 1, LinearLayout.LayoutParams(wrap, wrap).apply { gravity = Gravity.CENTER })
            shutterRow.addView(btnCamera, LinearLayout.LayoutParams(dp(64), dp(52)).apply { setMargins(dp(10), dp(10), dp(10), dp(10)) })
            for (v in small) {
                v.textSize = 14f
                v.minWidth = dp(58)
                v.minimumWidth = dp(58)
                v.setPadding(dp(10), dp(8), dp(10), dp(8))
            }
            topControls.visibility = View.GONE
        }
        controlBar.orientation = if (land) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        for (row in listOf(focalRow, dialRow, settingsRow, shutterRow)) {
            row.orientation = if (land && row !== settingsRow) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
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
        ll.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        ll.rightMargin = dp(6)
        leverBox.layoutParams = ll
        if (land) {
            controlBar.post {
                val l2 = leverBox.layoutParams as FrameLayout.LayoutParams
                l2.rightMargin = controlBar.width + dp(4)
                leverBox.layoutParams = l2
            }
        }
        // 보정 창: 세로는 아래, 가로는 오른쪽 절반
        for (panel in listOf(gradePanel, gradeListPanel)) {
            val pl = panel.layoutParams as FrameLayout.LayoutParams
            if (land) {
                pl.width = (uiRoot.layoutParams.width * 0.55f).toInt()
                pl.height = FrameLayout.LayoutParams.MATCH_PARENT
                pl.gravity = Gravity.END
            } else {
                pl.width = FrameLayout.LayoutParams.MATCH_PARENT
                pl.height = FrameLayout.LayoutParams.WRAP_CONTENT
                pl.gravity = Gravity.BOTTOM
            }
            panel.layoutParams = pl
        }
        val sl = gradeScroll.layoutParams as LinearLayout.LayoutParams
        if (land) { sl.height = 0; sl.weight = 1f } else { sl.height = dp(170); sl.weight = 0f }
        gradeScroll.layoutParams = sl
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
        topControls = findViewById(R.id.topControls)
        btnGrade = findViewById(R.id.btnGrade)
        gradeListPanel = findViewById(R.id.gradeListPanel)
        gradeList = findViewById(R.id.gradeList)
        gradePanel = findViewById(R.id.gradePanel)
        gradeTitle = findViewById(R.id.gradeTitle)
        curveView = findViewById(R.id.curveView)
        gradeScroll = findViewById(R.id.gradeScroll)
        gradeSliders = findViewById(R.id.gradeSliders)
        focalRow = findViewById(R.id.focalRow)
        settingsRow = findViewById(R.id.settingsRow)
        shutterRow = findViewById(R.id.shutterRow)
        btnFps = findViewById(R.id.btnFps)
        btnRes = findViewById(R.id.btnRes)
        btnAspect = findViewById(R.id.btnAspect)
        flashSlider = findViewById(R.id.flashSlider)
        btnMic = findViewById(R.id.btnMic)
        evLabel = findViewById(R.id.evLabel)
        dialRow = findViewById(R.id.dialRow)
        dialAperture = findViewById(R.id.dialAperture)
        dialShutter = findViewById(R.id.dialShutter)
        dialIso = findViewById(R.id.dialIso)
        setupDials()
        btnMonitor = findViewById(R.id.btnMonitor)
        levelMeter = findViewById(R.id.levelMeter)
        volumeRow = findViewById(R.id.volumeRow)
        volLabel = findViewById(R.id.volLabel)
        screenLight = findViewById(R.id.screenLight)
        monitor = AudioMonitor { rms, peak -> main.post { levelMeter.set(rms, peak) } }
        btnMonitor.setOnClickListener { toggleMonitor() }
        findViewById<View>(R.id.btnVolDown).setOnClickListener { changeMonitorVol(-1) }
        findViewById<View>(R.id.btnVolUp).setOnClickListener { changeMonitorVol(+1) }
        findViewById<View>(R.id.btnEvUp).setOnClickListener { changeEv(+1) }
        findViewById<View>(R.id.btnEvDown).setOnClickListener { changeEv(-1) }
        findViewById<View>(R.id.evLabel).setOnClickListener { changeEv(-evSteps) }
        recBorder = findViewById(R.id.recBorder)
        btnSelfie = findViewById(R.id.btnSelfie)
        btnWb = findViewById(R.id.btnWb)
        btnAe = findViewById(R.id.btnAe)
        modePhoto = findViewById(R.id.modePhoto)
        modeVideo = findViewById(R.id.modeVideo)
        thumb = findViewById(R.id.thumb)
        shutter = findViewById(R.id.shutter)
        btnCamera = findViewById(R.id.btnCamera)
        // 전면/후면 버튼은 없앰 (화면을 위아래로 쓸어서 전환)
        btnCamera.visibility = View.GONE
    }

    private fun setupControls() {
        shutter.setOnClickListener { onShutter() }

        btnFps.setOnClickListener {
            val c = cam ?: return@setOnClickListener
            if (!videoMode || recorder.isRecording) return@setOnClickListener
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
            updateShutterPriority()
            engine.requestUpdate()
            updateButtons()
        }

        btnRes.setOnClickListener {
            val c = cam ?: return@setOnClickListener
            if (!videoMode || recorder.isRecording) return@setOnClickListener
            if (!res4k) {
                val s = pickStream(c, aspect43, true)
                if (s == null) { toast("이 카메라는 ${if (aspect43) "4:3 " else ""}4K가 막혀 있어요"); return@setOnClickListener }
                if (!canRecord(c, s)) {
                    toast("이 폰의 인코더가 ${s.width}x${s.height} ${fps}fps 녹화를 지원하지 않아요")
                    return@setOnClickListener
                }
            }
            res4k = !res4k
            restartStream()
        }

        btnAspect.setOnClickListener {
            val c = cam ?: return@setOnClickListener
            if (recorder.isRecording) return@setOnClickListener
            val next = !aspect43
            val s = pickStream(c, next, res4k)
            if (s == null) {
                toast("이 카메라는 ${if (next) "4:3" else "16:9"} ${if (res4k) "4K" else "1080p"}를 지원하지 않아요")
                return@setOnClickListener
            }
            if (videoMode && !canRecord(c, s)) {
                toast("이 폰의 인코더가 ${s.width}x${s.height} 녹화를 지원하지 않아요")
                return@setOnClickListener
            }
            aspect43 = next
            restartStream()
        }

        flashSlider.onBlocked = { toast("이 카메라에는 플래시가 없어요") }
        var dimNoticeShown = false
        flashSlider.onChange = fun(lv: Int) {
            val cc = cam
            if (cc != null && cc.front && !cc.hasFlash) {
                setLight(lv)
                return
            }
            engine.torchLevel = lv
            engine.requestUpdate()
            val c = cam
            if (lv > 0 && c != null && c.torchMaxLevel <= 1 && !dimNoticeShown) {
                dimNoticeShown = true
                toast("이 폰은 촬영 중 플래시 밝기 조절을 막아둬서 켬/끔만 돼요")
            }
        }

        btnMic.setOnClickListener {
            if (!videoMode || recorder.isRecording) return@setOnClickListener
            if (btMic() == null) {
                micMode = 0
                toast("유선·블루투스 마이크를 연결하면 쓸 수 있어요 (지금은 폰 마이크)")
                return@setOnClickListener
            } else {
                micMode = (micMode + 1) % 3
                toast(when (micMode) {
                    1 -> "외부(유선·블루투스) 마이크만 녹음"
                    2 -> "폰 + 외부 마이크 같이 녹음"
                    else -> "폰 마이크로 녹음"
                })
            }
            applyMicRoute()
            updateButtons()
        }

        btnWb.setOnClickListener {
            val fixed = engine.setWbLock(!engine.awbLock)
            if (engine.awbLock && !fixed) toast("이 카메라는 색 숫자 고정을 막아둬서, 렌즈가 바뀌면 색이 조금 달라질 수 있어요")
            updateButtons()
        }

        // AE 잠금: 누르고 있는 동안만 잠금, 손을 떼면 풀림

        btnGrade.setOnClickListener { showGradeList(true) }
        findViewById<View>(R.id.btnGrClose).setOnClickListener { showGradeList(false) }
        findViewById<View>(R.id.btnGrNew).setOnClickListener {
            showGradeList(false)
            openGradeEditor(Grade.none(), isNew = true)
        }
        findViewById<View>(R.id.btnGrEdit).setOnClickListener {
            showGradeList(false)
            openGradeEditor(currentGrade, isNew = currentGrade.id == Grade.NONE_ID)
        }
        findViewById<View>(R.id.btnGrCancel).setOnClickListener { closeGradeEditor(revert = true) }
        findViewById<View>(R.id.btnGrReset).setOnClickListener {
            val e = editingGrade ?: return@setOnClickListener
            e.setFrom(Grade.none())
            buildGradeSliders()
            selectCurveTab(curveTab)
            applyGrade(e)
        }
        findViewById<View>(R.id.btnGrSave).setOnClickListener { saveGrade(asNew = false) }
        findViewById<View>(R.id.btnGrSaveAs).setOnClickListener { saveGrade(asNew = true) }
        curveView.onChange = { editingGrade?.let { applyGrade(it) } }
        val tabs = listOf(R.id.tabMaster, R.id.tabR, R.id.tabG, R.id.tabB)
        for ((i, id) in tabs.withIndex()) {
            findViewById<View>(id).setOnClickListener { selectCurveTab(i) }
        }

        // AE 잠금: 누르고 있는 동안만 잠금 / 오른쪽으로 밀면 고정, 왼쪽으로 밀면 해제
        btnAe.setOnTouchListener { v, ev -> onAeTouch(v, ev) }

        modePhoto.setOnClickListener { setMode(video = false) }
        modeVideo.setOnClickListener { setMode(video = true) }

        // 카메라 버튼: 전면 ↔ 후면만
        btnCamera.setOnClickListener { flipFacing() }

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
                preview.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                onTapFocus(e.x, e.y)
                return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val start = e1 ?: return false
                val dx = e2.x - start.x
                val dy = e2.y - start.y
                // 폰을 든 방향 기준의 위/아래 = 세로일 땐 화면 세로, 가로일 땐 화면 가로
                val along = if (uiDeg == 0) dy else dx
                val across = if (uiDeg == 0) dx else dy
                if (abs(along) > dp(70) && abs(along) > abs(across) * 1.5f) {
                    flipFacing()
                    return true
                }
                return false
            }

            override fun onLongPress(e: MotionEvent) {
                engine.clearMetering()
                focusRing.visibility = View.INVISIBLE
                toast("초점 자동")
            }
        })
        preview.setOnTouchListener { _, ev -> detector.onTouchEvent(ev) }
    }

    /**
     * 누를 때 진동. 줌(레버·화각 버튼)은 촬영 중 쓰는 거라 제외.
     * 기존 클릭 동작은 그대로 두고 손가락이 닿는 순간에만 진동.
     */
    private fun addHaptics(v: View) {
        if (v === zoomLever || v === focalRow || v === btnAe || v is FlashSlider || v is CurveView) return
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) addHaptics(v.getChildAt(i))
        }
        if (v.hasOnClickListeners()) {
            v.setOnTouchListener { vv, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    vv.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                }
                false
            }
        }
    }

    // ───── 밝기 보정 (0.3EV씩, 최대 ±2.1) ─────

    private fun evValue() = evSteps * EV_STEP

    private fun changeEv(delta: Int) {
        val n = (evSteps + delta).coerceIn(-EV_MAX_STEPS, EV_MAX_STEPS)
        if (n == evSteps) return
        evSteps = n
        engine.evComp = evValue()
        engine.requestUpdate()
        updateEvUi()
    }

    private fun updateEvUi() {
        val v = evValue()
        evLabel.text = if (evSteps == 0) "±0.0" else String.format(Locale.US, "%+.1f", v)
        updateInfoMain()
    }

    // ───── 소리 모니터 ─────

    private fun headphonesConnected(): Boolean {
        val types = setOf(
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
            android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
        )
        return audioManager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any { it.type in types }
    }

    private fun toggleMonitor() {
        if (monitor.running) {
            monitor.stop()
        } else {
            if (!headphonesConnected()) {
                toast("이어폰(유선·무선)을 연결해야 들을 수 있어요")
                return
            }
            if (!has(Manifest.permission.RECORD_AUDIO)) {
                toast("마이크 권한이 필요해요")
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_PERMS)
                return
            }
            monitor.gain = inputGain()
            monitor.setExternal(recorder.isRecording, null)
            monitor.start(micsForRecording().firstOrNull())
        }
        updateMonitorUi()
    }

    /** 수음 크기: 5 = 0dB, 한 칸 2dB (−10 ~ +10dB) */
    private fun inputGainDb() = (monitorVol - 5) * 2

    private fun inputGain() = 10f.pow(inputGainDb() / 20f)

    private fun changeMonitorVol(d: Int) {
        monitorVol = (monitorVol + d).coerceIn(0, 10)
        monitor.gain = inputGain()
        recorder.gain = inputGain()
        volLabel.text = gainLabel()
    }

    private fun gainLabel() = "수음 " + (if (inputGainDb() > 0) "+" else "") + "${inputGainDb()}dB"

    private fun updateMonitorUi() {
        val on = monitor.running
        btnMonitor.setBackgroundResource(if (on) R.drawable.chip_selected_bg else R.drawable.chip_bg)
        btnMonitor.alpha = if (on || headphonesConnected()) 1f else 0.4f
        levelMeter.visibility = if (on) View.VISIBLE else View.GONE
        volumeRow.visibility = if (on) View.VISIBLE else View.GONE
        volLabel.text = gainLabel()
    }

    // ───── 전면 셀피 화면 조명 (5단계) ─────

    private fun setLight(level: Int) {
        lightLevel = level.coerceIn(0, 5)
        val w = root.width - root.paddingLeft - root.paddingRight
        val h = root.height - root.paddingTop - root.paddingBottom
        lightL = 0; lightT = 0; lightR = 0; lightB = 0
        if (lightLevel > 0 && w > 0 && h > 0) {
            val unfolded = w.toFloat() / h > 0.75f && h.toFloat() / w > 0.75f
            if (unfolded) {
                // 펼침: 양쪽 끝에서 가운데로 흰 띠 (5단계면 거의 가운데까지)
                val side = (w * 0.42f * lightLevel / 5f).roundToInt()
                lightL = side; lightR = side
            } else {
                // 접힘: 사방 테두리가 가장자리부터 두꺼워짐
                val t = (minOf(w, h) * 0.30f * lightLevel / 5f).roundToInt()
                lightL = t; lightR = t; lightT = t; lightB = t
            }
        }
        screenLight.setInsets(lightL, lightT, lightR, lightB)
        // 조명이 켜지면 화면을 가장 밝게
        val lp = window.attributes
        lp.screenBrightness = if (lightLevel > 0) 1f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
        layoutPreview()
    }

    // ───── 마이크 ─────

    /** 유선·USB·블루투스 마이크 */
    private fun isExternalMic(d: android.media.AudioDeviceInfo) = isBt(d) ||
        d.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
        d.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET ||
        d.type == android.media.AudioDeviceInfo.TYPE_USB_DEVICE

    private fun micName(d: android.media.AudioDeviceInfo?): String = when {
        d == null -> "기본 마이크"
        d.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC -> "폰 마이크"
        isBt(d) -> "블루투스(${d.productName})"
        else -> "유선(${d.productName})"
    }

    /** 실제로 잡힌 마이크를 알려주고, 고른 것과 다르면 경고 */
    private fun reportRouted(wanted: Int, devs: List<android.media.AudioDeviceInfo?>) {
        val names = devs.map { micName(it) }.distinct()
        val hasPhone = devs.any { it?.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC }
        val hasExt = devs.any { it != null && isExternalMic(it) }
        val msg = "녹음 마이크: " + names.joinToString(" + ")
        val wrong = when (wanted) {
            0 -> hasExt
            1 -> hasPhone || !hasExt
            else -> !(hasPhone && hasExt)
        }
        toast(if (wrong) "$msg  (고른 설정과 달라요 — 폰이 마이크를 바꿔 잡았어요)" else msg)
        lastRouted = names.joinToString("+")
        updateInfoSub()
    }

    private var lastRouted = ""
    private var commMode = false

    private fun isBt(d: android.media.AudioDeviceInfo) =
        d.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO || d.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET

    private fun btMic(): android.media.AudioDeviceInfo? =
        audioManager.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS).let { list ->
            list.firstOrNull { isBt(it) } ?: list.firstOrNull { isExternalMic(it) }
        }

    private fun phoneMic(): android.media.AudioDeviceInfo? =
        audioManager.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC }

    /** 블루투스가 끊기면 폰 마이크로 */
    private fun effectiveMicMode(): Int = if (micMode != 0 && btMic() == null) 0 else micMode

    private fun micsForRecording(): List<android.media.AudioDeviceInfo?> {
        if (!has(Manifest.permission.RECORD_AUDIO)) return emptyList()
        val bt = btMic()
        return when (effectiveMicMode()) {
            1 -> listOf(bt)
            2 -> listOf(phoneMic(), bt)
            else -> listOf(phoneMic())
        }
    }

    /** 블루투스 마이크를 쓸 땐 통화용 경로를 켜야 마이크가 열림 */
    private fun applyMicRoute() {
        try {
            // 블루투스 마이크만 통화 경로가 필요 (유선은 그냥 열림)
            if (effectiveMicMode() != 0 && isResumedNow && btMic()?.let { isBt(it) } == true) {
                val dev = audioManager.availableCommunicationDevices.firstOrNull { isBt(it) }
                if (dev != null) {
                    audioManager.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
                    audioManager.setCommunicationDevice(dev)
                    commMode = true
                }
            } else {
                audioManager.clearCommunicationDevice()
                if (commMode) {
                    audioManager.mode = android.media.AudioManager.MODE_NORMAL
                    commMode = false
                }
            }
        } catch (e: Exception) {}
    }

    private fun onMicDevicesChanged() {
        // 외부 마이크가 빠지면 기본(폰 마이크)으로
        if (btMic() == null && !recorder.isRecording) micMode = 0
        if (!recorder.isRecording) applyMicRoute()
        // 이어폰이 빠지면 모니터 끔 (스피커로 소리가 되울리지 않게)
        if (monitor.running && !headphonesConnected()) {
            monitor.stop()
            toast("이어폰이 빠져서 모니터를 껐어요")
        }
        updateMonitorUi()
        updateButtons()
    }

    // ───── 후면 셀피: 듀얼 / 셀피 ─────

    private var uiInCover = false
    private lateinit var innerBlocker: View

    private fun openSelfie() {
        val c = cam
        if (c != null && c.front && !recorder.isRecording) {
            val target = entries.firstOrNull { !cams[it.camIndex].front }
            if (target != null) {
                engine.close()
                started = false
                selectCamera(target.camIndex)
                target.zoom?.let { setZoom(it) }
            }
        }
        rearSelfie.open()
    }

    /** 셀피 모드면 조작판(uiRoot)을 바깥 화면으로 옮기고, 아니면 안쪽으로 되돌림 */
    private fun syncSelfieUi() {
        val host = rearSelfie.container
        val wantCover = rearSelfie.active && rearSelfie.fullControl && host != null
        innerBlocker.visibility = if (wantCover) View.VISIBLE else View.GONE
        if (wantCover && !uiInCover) {
            (uiRoot.parent as? android.view.ViewGroup)?.removeView(uiRoot)
            uiRoot.rotation = 0f
            uiRoot.alpha = 1f
            host!!.addView(uiRoot, FrameLayout.LayoutParams(-1, -1))
            uiInCover = true
            arrangeControls(false)
        } else if (!wantCover && uiInCover) {
            (uiRoot.parent as? android.view.ViewGroup)?.removeView(uiRoot)
            root.addView(uiRoot, root.indexOfChild(innerBlocker).coerceAtLeast(0), FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
            uiInCover = false
            root.post { applyUiOrientation(animate = false) }
        }
    }

    private fun setMode(video: Boolean) {
        if (recorder.isRecording || video == videoMode) return
        videoMode = video
        engine.setVideoMode(videoMode)
        updateShutterPriority()
        updateButtons()
    }

    /** 후면 ↔ 전면 (화면 쓸기) */
    private fun flipFacing() {
        val c = cam ?: return
        if (recorder.isRecording) {
            toast("녹화 중에는 앞뒤 카메라를 바꿀 수 없어요")
            return
        }
        val target = entries.firstOrNull { cams[it.camIndex].front != c.front } ?: return
        preview.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        flash.alpha = 1f
        flash.animate().alpha(0f).setStartDelay(250).setDuration(300).start()
        engine.close()
        started = false
        selectCamera(target.camIndex)
        target.zoom?.let { setZoom(it) }
    }

    private fun updateButtons() {
        val c = cam
        btnFps.text = "${fps}fps"
        btnFps.alpha = if (videoMode) 1f else 0.35f
        btnRes.text = if (res4k && c?.size4k != null) "4K" else "1080p"
        btnAspect.text = if (aspect43) "4:3" else "16:9"
        btnSelfie.visibility = if (rearSelfie.available || rearSelfie.active) View.VISIBLE else View.GONE
        btnSelfie.text = when {
            !rearSelfie.active -> "셀피 끔"
            rearSelfie.fullControl -> "셀피"
            else -> "듀얼"
        }
        btnSelfie.setBackgroundResource(if (rearSelfie.active) R.drawable.chip_selected_bg else R.drawable.chip_bg)
        btnAspect.alpha = if (recorder.isRecording) 0.35f else 1f

        btnRes.alpha = if (videoMode && !recorder.isRecording) 1f else 0.35f
        setLocked(btnWb, engine.awbLock, "AWB", "WB 잠금")
        setLocked(btnAe, engine.aeLock, "AE-L ›", if (aeLatched) "AE 고정 ›" else "AE 잠금")
        btnGrade.text = if (currentGrade.id == Grade.NONE_ID) "보정" else "보정 ●"
        modePhoto.setBackgroundResource(if (!videoMode) R.drawable.chip_selected_bg else 0)
        modeVideo.setBackgroundResource(if (videoMode) R.drawable.chip_selected_bg else 0)
        modePhoto.alpha = if (!videoMode) 1f else 0.55f
        modeVideo.alpha = if (videoMode) 1f else 0.55f
        shutter.setBackgroundResource(
            when {
                recorder.isRecording -> R.drawable.shutter_recording
                videoMode -> R.drawable.shutter_video
                else -> R.drawable.shutter_photo
            }
        )
        recTime.visibility = if (recorder.isRecording) View.VISIBLE else View.GONE
        recBorder.visibility = if (recorder.isRecording) View.VISIBLE else View.GONE
        rearSelfie.setRecording(recorder.isRecording)
        btnMic.text = when (effectiveMicMode()) { 1 -> "외부 마이크"; 2 -> "폰+외부"; else -> "폰 마이크" }
        btnMic.alpha = if (videoMode && !recorder.isRecording && btMic() != null) 1f else 0.35f
        val lock = if (recorder.isRecording) 0.35f else 1f
        uiRoot.findViewById<View>(R.id.modeToggle)?.alpha = lock
        btnCamera.alpha = if (c != null && entries.size < 2) 0.35f else 1f
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
        btnCamera.text = if (c.front) "전면" else "후면"
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

    /** 영상 저장 방향: 화면 처리 후 영상은 이미 세워져 있어 폰을 돌린 만큼만. */
    private fun videoRotation(c: CameraEngine.Cam): Int {
        if (!renderer.transformRotates) return outputRotation(c)
        return if (c.front) (360 - uiDeg) % 360 else uiDeg
    }

    /** 사진 저장 방향 (Camera2 공식). 폰을 든 방향 기준. */
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
        if (recorder.isRecording) {
            stopRecordingIfNeeded()
        } else {
            try {
                val (w, h) = renderer.outputSize()
                val mics = micsForRecording()
                if (!has(Manifest.permission.RECORD_AUDIO)) toast("마이크 권한이 없어 소리 없이 녹화돼요")
                monitor.setExternal(true, null)
                recorder.onPcm = { b, n -> monitor.feed(b, n) }
                recorder.gain = inputGain()
                val wanted = effectiveMicMode()
                recorder.onRouted = { devs -> runOnUiThread { reportRouted(wanted, devs) } }
                val surface = recorder.start(w, h, fps, bitrate(), videoRotation(c), mics)
                renderer.setEncoderSurface(surface)
                recordStart = SystemClock.elapsedRealtime()
                main.post(recTick)
            } catch (e: Exception) {
                toast("녹화를 시작할 수 없어요: ${e.message}")
            }
            updateButtons()
        }
    }

    private fun stopRecordingIfNeeded() {
        if (!recorder.isRecording) return
        renderer.setEncoderSurface(null)
        val uri = recorder.stop()
        recorder.onPcm = null
        monitor.setExternal(false, micsForRecording().firstOrNull())
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

    override fun onJpeg(bytes: ByteArray) {
        val lut = if (currentGrade.isNeutral) null else currentLut
        worker.execute {
            val uri = try { PhotoSaver.save(this, bytes, lut) } catch (e: Throwable) { null }
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
    }

    // ───────────────────────── 보정 ─────────────────────────

    private class SliderDef(val label: String, val get: (Grade) -> Float, val set: (Grade, Float) -> Unit)

    private val sliderDefs = listOf(
        SliderDef("색온도  (− 차갑게 / + 따뜻하게)", { it.temp }, { g, v -> g.temp = v }),
        SliderDef("틴트  (− 초록 / + 마젠타)", { it.tint }, { g, v -> g.tint = v }),
        SliderDef("노출", { it.exposure }, { g, v -> g.exposure = v }),
        SliderDef("대비", { it.contrast }, { g, v -> g.contrast = v }),
        SliderDef("하이라이트", { it.highlights }, { g, v -> g.highlights = v }),
        SliderDef("섀도", { it.shadows }, { g, v -> g.shadows = v }),
        SliderDef("화이트", { it.whites }, { g, v -> g.whites = v }),
        SliderDef("블랙", { it.blacks }, { g, v -> g.blacks = v }),
        SliderDef("채도", { it.saturation }, { g, v -> g.saturation = v }),
        SliderDef("얼굴 밝기", { it.skinBright }, { g, v -> g.skinBright = v }),
        SliderDef("얼굴 톤  (− 노랗게 / + 붉게)", { it.skinHue }, { g, v -> g.skinHue = v }),
        SliderDef("얼굴 채도", { it.skinSat }, { g, v -> g.skinSat = v })
    )

    /** 보정 → LUT 계산 → 화면에 반영 (가장 최근 것만). */
    private fun applyGrade(g: Grade) {
        val gen = lutGen.incrementAndGet()
        val copy = g.copyAs()
        lutExecutor.execute {
            if (gen != lutGen.get()) return@execute
            val data = GradeEngine.build(copy)
            if (gen == lutGen.get()) {
                currentLut = data
                renderer.uploadLut(data, GradeEngine.N)
            }
        }
    }

    private fun showGradeList(show: Boolean) {
        if (show) {
            populateGrades()
            gradeListPanel.visibility = View.VISIBLE
            controlBar.visibility = View.GONE
            leverBox.visibility = View.GONE
        } else {
            gradeListPanel.visibility = View.GONE
            controlBar.visibility = View.VISIBLE
            leverBox.visibility = View.VISIBLE
        }
        gradeListPanel.post { updatePreviewInsets() }
    }

    private fun populateGrades() {
        gradeList.removeAllViews()
        for (g in gradeStore.all()) {
            val selected = g.id == currentGrade.id
            val tv = TextView(this).apply {
                text = (if (selected) "●  " else "○  ") + g.name
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 15f
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setBackgroundResource(if (selected) R.drawable.chip_selected_bg else R.drawable.row_bg)
                setOnClickListener {
                    // 바로 적용만 하고 창은 그대로 (닫기로 나감)
                    selectGrade(g)
                    populateGrades()
                }
                setOnLongClickListener {
                    if (g.id == Grade.NONE_ID) {
                        toast("'보정 없음'은 지울 수 없어요")
                    } else {
                        android.app.AlertDialog.Builder(this@MainActivity)
                            .setTitle("'${g.name}' 삭제할까요?")
                            .setPositiveButton("삭제") { _, _ ->
                                gradeStore.delete(g)
                                if (g.id == currentGrade.id) selectGrade(Grade.none())
                                populateGrades()
                            }
                            .setNegativeButton("취소", null)
                            .show()
                    }
                    true
                }
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(6)
            gradeList.addView(tv, lp)
            addHaptics(tv)
        }
    }

    private var curveTab = 0

    private fun selectCurveTab(i: Int) {
        val e = editingGrade ?: return
        curveTab = i
        curveView.points = when (i) { 1 -> e.curveR; 2 -> e.curveG; 3 -> e.curveB; else -> e.curve }
        curveView.lineColor = when (i) {
            1 -> 0xFFFF7B7B.toInt(); 2 -> 0xFF7BE38B.toInt(); 3 -> 0xFF7BB4FF.toInt(); else -> 0xFFF5F0EA.toInt()
        }
        val tabs = listOf(R.id.tabMaster, R.id.tabR, R.id.tabG, R.id.tabB)
        for ((k, id) in tabs.withIndex()) {
            uiRoot.findViewById<View>(id)?.setBackgroundResource(if (k == i) R.drawable.chip_selected_bg else R.drawable.chip_bg)
        }
    }

    // ───── AE 잠금 버튼: 누르는 동안 잠금 / 오른쪽으로 밀면 고정, 한 번 더 밀면 해제 ─────
    private var aeLatched = false
    private var aeDownX = 0f
    private var aeToggled = false

    private fun setAe(locked: Boolean) {
        if (engine.aeLock != locked) {
            engine.aeLock = locked
            engine.requestUpdate()
        }
        updateButtons()
        updateInfoMain()
    }

    private fun onAeTouch(v: View, ev: MotionEvent): Boolean {
        val slide = dp(36).toFloat()
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.parent?.requestDisallowInterceptTouchEvent(true)
                aeDownX = ev.rawXInView(v)
                aeToggled = false
                if (!aeLatched) {
                    setAe(true)
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawXInView(v) - aeDownX
                v.translationX = dx.coerceIn(-slide, slide) * 0.6f
                // 오른쪽으로 밀 때마다 고정 ↔ 해제 (한 번 밀 때 한 번만)
                if (!aeToggled && dx > slide) {
                    aeToggled = true
                    aeLatched = !aeLatched
                    setAe(true) // 해제해도 손을 뗄 때까지는 잠금 유지
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.animate().translationX(0f).setDuration(150).start()
                if (!aeLatched) setAe(false)
            }
        }
        return true
    }

    /** 버튼이 움직여도 흔들리지 않는 터치 좌표 (버튼 이동량 보정). */
    private fun MotionEvent.rawXInView(v: View): Float = x + v.translationX

    private fun selectGrade(g: Grade) {
        currentGrade = g
        gradeStore.setSelected(g.id)
        applyGrade(g)
        updateButtons()
    }

    private fun openGradeEditor(base: Grade, isNew: Boolean) {
        editingIsNew = isNew
        val e = if (isNew) base.copyAs(gradeStore.newId(), "내 보정 ${gradeStore.all().size}") else base.copyAs()
        editingGrade = e
        gradeTitle.text = if (isNew) "새 보정 만들기" else "보정 편집 · ${base.name}"
        selectCurveTab(0)
        buildGradeSliders()
        gradePanel.visibility = View.VISIBLE
        controlBar.visibility = View.GONE
        leverBox.visibility = View.GONE
        gradePanel.post { updatePreviewInsets() }
        applyGrade(e)
    }

    private fun closeGradeEditor(revert: Boolean) {
        editingGrade = null
        gradePanel.visibility = View.GONE
        controlBar.visibility = View.VISIBLE
        leverBox.visibility = View.VISIBLE
        updatePreviewInsets()
        if (revert) applyGrade(currentGrade)
    }

    private fun buildGradeSliders() {
        val e = editingGrade ?: return
        gradeSliders.removeAllViews()
        for (def in sliderDefs) {
            val label = TextView(this).apply {
                setTextColor(0xDDFFFFFF.toInt())
                textSize = 13f
                setPadding(dp(4), dp(8), dp(4), 0)
            }
            val refresh = {
                val v = def.get(e)
                label.text = String.format(Locale.US, "%s   %+d", def.label, (v * 100).roundToInt())
            }
            refresh()
            val bar = android.widget.SeekBar(this)
            bar.max = 200
            bar.progress = ((def.get(e) + 1f) * 100f).roundToInt().coerceIn(0, 200)
            bar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                var lastTick = 0
                override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    // 5칸 움직일 때마다 약한 진동, 0 지점은 조금 더 또렷하게
                    if (abs(p - lastTick) >= 5 || (p == 100 && lastTick != 100)) {
                        lastTick = p
                        sb.performHapticFeedback(
                            if (p == 100) android.view.HapticFeedbackConstants.CLOCK_TICK
                            else android.view.HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
                    }
                    def.set(e, p / 100f - 1f)
                    refresh()
                    applyGrade(e)
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar) {
                    lastTick = sb.progress
                    sb.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                }
                override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
            })
            // 이름을 누르면 0으로
            label.setOnClickListener {
                def.set(e, 0f)
                bar.progress = 100
                refresh()
                applyGrade(e)
            }
            gradeSliders.addView(label)
            gradeSliders.addView(bar)
        }
    }

    private fun saveGrade(asNew: Boolean) {
        val e = editingGrade ?: return
        if (asNew || editingIsNew) {
            val input = android.widget.EditText(this)
            input.setText(if (editingIsNew) e.name else "${e.name} 2")
            input.setSelection(input.text.length)
            android.app.AlertDialog.Builder(this)
                .setTitle("보정 이름")
                .setView(input)
                .setPositiveButton("저장") { _, _ ->
                    val name = input.text.toString().trim().ifBlank { e.name }
                    val g = e.copyAs(gradeStore.newId(), name)
                    gradeStore.save(g)
                    selectGrade(g)
                    closeGradeEditor(revert = false)
                    toast("'$name' 저장됨")
                }
                .setNegativeButton("취소", null)
                .show()
        } else {
            gradeStore.save(e)
            selectGrade(e)
            closeGradeEditor(revert = false)
            toast("'${e.name}' 저장됨")
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
            if (!recorder.isRecording) return
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
        runOnUiThread {
            updateInfoMain()
            if (exposureNs > 0) dialShutter.autoText = fmtShutter(exposureNs)
            if (iso > 0) dialIso.autoText = "$iso"
            if (!videoMode && shutterManual && !isoManual) dialIso.autoText = "${engine.manualIso}"
            if (!videoMode && isoManual && !shutterManual) dialShutter.autoText = fmtShutter(engine.manualExposureNs)
        }
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
            if (evSteps != 0) append(String.format(Locale.US, "  EV%+.1f", evValue()))
        }
        infoMain.text = String.format(Locale.US, "%-8s ISO %-5d %s%s", shutterTxt, lastIso, k, locks)
    }

    private fun updateInfoSub() {
        val c = cam
        val res = if (c == null) "—" else {
            val ss = streamSize(c)
            (if (res4k) "4K" else "${minOf(ss.width, ss.height)}p") + (if (aspect43) " 4:3" else "")
        }
        val bat = (getSystemService(BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val free = try {
            StatFs(Environment.getExternalStorageDirectory().path).availableBytes
        } catch (e: Exception) { 0L }
        infoSub.text = if (videoMode) {
            val bytesPerSec = bitrate() / 8L + 24_000L
            val mins = free / bytesPerSec / 60
            String.format(Locale.US, "%s · %dfps · 남은 %d시간 %02d분 · 배터리 %d%%", res, fps, mins / 60, mins % 60, bat)
        } else {
            val shots = free / 4_000_000L
            String.format(Locale.US, "사진 %s · 약 %,d장 · 배터리 %d%%", if (aspect43) "4:3" else "16:9", shots, bat)
        }
    }

    // ───── 셔터 우선 (영상): 셔터 = 1/(프레임×2), ISO 자동 ─────

    private fun shutterNsFor(f: Int): Long = when (f) {
        24, 25 -> 1_000_000_000L / 50
        30 -> 1_000_000_000L / 60
        60 -> 1_000_000_000L / 120
        else -> 1_000_000_000L / (f * 2)
    }

    private fun updateShutterPriority() {
        val c = cam
        dialRow.visibility = if (videoMode) View.GONE else View.VISIBLE
        if (!videoMode) { updatePhotoExposure(); return }
        val on = videoMode && c != null && c.manualSensor
        val ns = if (on) shutterNsFor(fps) else 0L
        if (ns > 0 && engine.manualExposureNs == 0L) {
            // 자동 노출이 찾아둔 밝기에서 시작
            val start = if (lastExp > 0 && lastIso > 0) (lastExp.toDouble() * lastIso / ns).toInt() else 400
            isoF = start.coerceIn(c!!.isoMin, c.isoMax).toFloat()
            engine.manualIso = isoF.roundToInt()
        }
        engine.manualExposureNs = ns
        engine.requestUpdate()
        main.removeCallbacks(isoTick)
        if (ns > 0) main.postDelayed(isoTick, 200)
    }

    private var isoF = 400f

    // ───── 사진: 조리개 / 셔터 / ISO 다이얼 ─────

    private fun fmtShutter(ns: Long): String =
        if (ns >= 300_000_000L) String.format(Locale.US, "%.1f\"", ns / 1e9) else "1/" + (1e9 / ns).roundToInt()

    private fun setupDials() {
        dialAperture.title = "조리개"
        dialShutter.title = "셔터"
        dialIso.title = "ISO"
        val blocked: (String) -> Unit = { what ->
            when (what) {
                "auto" -> toast("꾹 누르면 수동으로 바뀌어요")
                "조리개" -> toast("이 렌즈는 조리개가 하나로 고정이에요")
                else -> toast("이 카메라는 수동 노출을 막아뒀어요")
            }
        }
        dialAperture.onBlocked = blocked
        dialShutter.onBlocked = blocked
        dialIso.onBlocked = blocked
        dialAperture.onAuto = { a ->
            engine.aperture = if (a) 0f else (cam?.apertures?.getOrNull(dialAperture.index) ?: 0f)
            engine.requestUpdate()
        }
        dialAperture.onIndex = { i -> engine.aperture = cam?.apertures?.getOrNull(i) ?: 0f; engine.requestUpdate() }
        dialShutter.onAuto = { a ->
            shutterManual = !a
            if (!a) dialShutter.index = nearestShutter(lastExp)
            updatePhotoExposure()
        }
        dialShutter.onIndex = { updatePhotoExposure() }
        dialIso.onAuto = { a ->
            isoManual = !a
            if (!a) dialIso.index = nearestIso(lastIso)
            updatePhotoExposure()
        }
        dialIso.onIndex = { updatePhotoExposure() }
    }

    private fun nearestShutter(ns: Long): Int {
        if (shutterChoices.isEmpty() || ns <= 0) return (shutterChoices.size / 2)
        return shutterChoices.indices.minByOrNull { abs(ln(shutterChoices[it].toFloat() / ns)) } ?: 0
    }

    private fun nearestIso(iso: Int): Int {
        if (isoChoices.isEmpty() || iso <= 0) return 0
        return isoChoices.indices.minByOrNull { abs(ln(isoChoices[it].toFloat() / iso)) } ?: 0
    }

    /** 카메라가 바뀔 때 다이얼 값 목록 다시 만들기 */
    private fun buildDialChoices(c: CameraEngine.Cam) {
        val ap = c.apertures
        dialAperture.values = if (ap.isEmpty()) listOf("—") else ap.map { String.format(Locale.US, "f/%.1f", it) }
        dialAperture.canManual = ap.size > 1
        dialAperture.auto = true
        dialAperture.autoText = dialAperture.values.firstOrNull() ?: "—"
        engine.aperture = 0f
        // 셔터: 빠른 것 → 느린 것 (위로 밀면 느려짐 = 밝아짐)
        val denoms = listOf(8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600, 1250, 1000, 800, 640, 500, 400,
            320, 250, 200, 160, 125, 100, 80, 60, 50, 40, 30, 25, 20, 15, 13, 10, 8, 6, 5, 4)
        val maxNs = minOf(c.expMaxNs, 250_000_000L)
        shutterChoices = denoms.map { 1_000_000_000L / it }.filter { it in c.expMinNs..maxNs }
        dialShutter.values = shutterChoices.map { fmtShutter(it) }
        // ISO: 낮은 것 → 높은 것
        val isos = listOf(50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600,
            2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800)
        isoChoices = isos.filter { it in c.isoMin..c.isoMax }.ifEmpty { listOf(c.isoMin) }
        dialIso.values = isoChoices.map { "$it" }
        dialShutter.canManual = c.manualSensor && shutterChoices.isNotEmpty()
        dialIso.canManual = c.manualSensor
        if (!c.manualSensor) { shutterManual = false; isoManual = false }
        dialShutter.auto = !shutterManual
        dialIso.auto = !isoManual
        dialShutter.index = dialShutter.index.coerceIn(0, (shutterChoices.size - 1).coerceAtLeast(0))
        dialIso.index = dialIso.index.coerceIn(0, (isoChoices.size - 1).coerceAtLeast(0))
    }

    /** 사진 모드 노출: 둘 다 자동이면 카메라 자동, 하나라도 수동이면 나머지를 앱이 맞춤 */
    private fun updatePhotoExposure() {
        val c = cam ?: return
        main.removeCallbacks(isoTick)
        if (!c.manualSensor || (!shutterManual && !isoManual)) {
            engine.manualExposureNs = 0L
            engine.requestUpdate()
            return
        }
        val ns = if (shutterManual) shutterChoices.getOrElse(dialShutter.index) { 16_666_667L }
            else (if (lastExp > 0) lastExp else 16_666_667L)
        val iso = if (isoManual) isoChoices.getOrElse(dialIso.index) { 100 }
            else (if (lastExp > 0 && lastIso > 0) (lastExp.toDouble() * lastIso / ns).toInt() else 400)
                .coerceIn(c.isoMin, c.isoMax)
        expF = ns.toFloat()
        isoF = iso.toFloat()
        engine.manualExposureNs = ns
        engine.manualIso = iso
        engine.requestUpdate()
        if (!(shutterManual && isoManual)) main.postDelayed(isoTick, 200)
    }

    /** 보정 전 화면 밝기를 보고 ISO를 천천히 맞춤. AE 잠금 중엔 그대로. */
    private val isoTick = object : Runnable {
        override fun run() {
            val c = cam
            if (engine.manualExposureNs <= 0L || c == null || !isResumedNow) return
            val mean = renderer.meanLuma
            if (!engine.aeLock && mean > 0.01f) {
                // EV 보정만큼 목표 밝기 이동 (화면 감마 기준)
                val target = (TARGET_LUMA * 2f.pow(evValue() / 2.2f)).coerceIn(0.05f, 0.9f)
                val err = (ln(target / mean) / ln(2f)).coerceIn(-2f, 2f)
                if (abs(err) > 0.06f) {
                    val photoIsoManual = !videoMode && isoManual
                    if (!videoMode && shutterManual && isoManual) {
                        // 둘 다 수동: 그대로
                    } else if (photoIsoManual) {
                        // ISO 고정 → 셔터가 따라감
                        val maxNs = minOf(c.expMaxNs, 250_000_000L).toFloat()
                        expF = (expF * 2f.pow(err * 0.35f)).coerceIn(c.expMinNs.toFloat(), maxNs)
                        engine.manualExposureNs = expF.toLong()
                        engine.requestUpdate()
                    } else {
                        isoF = (isoF * 2f.pow(err * 0.35f)).coerceIn(c.isoMin.toFloat(), c.isoMax.toFloat())
                        val iso = isoF.roundToInt()
                        if (iso != engine.manualIso) {
                            engine.manualIso = iso
                            engine.requestUpdate()
                        }
                    }
                }
            }
            main.postDelayed(this, 150)
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
        /** 셔터 우선일 때 맞추는 평균 밝기 */
        private const val TARGET_LUMA = 0.42f
        private const val EV_STEP = 0.3f
        private const val EV_MAX_STEPS = 7
        /** 레버를 끝까지 밀었을 때 줌 속도 (초당 약 4.5배) */
        private const val MAX_ZOOM_SPEED = 1.5f
    }
}
