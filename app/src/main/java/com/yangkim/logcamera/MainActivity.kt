package com.yangkim.logcamera

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.InputType
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : Activity(), CameraController.Listener {

    private lateinit var preview: SurfaceView
    private lateinit var flash: View
    private lateinit var focusRing: View
    private lateinit var btnLook: TextView
    private lateinit var btnLutToggle: TextView
    private lateinit var btnFlip: TextView
    private lateinit var recTime: TextView
    private lateinit var bottomBar: View
    private lateinit var evBar: SeekBar
    private lateinit var evValue: TextView
    private lateinit var btnMode: TextView
    private lateinit var shutter: View
    private lateinit var btnEdit: TextView
    private lateinit var editorPanel: View
    private lateinit var editorTitle: TextView
    private lateinit var sliderContainer: LinearLayout
    private lateinit var lookPanel: View
    private lateinit var lookContainer: LinearLayout

    private lateinit var renderer: GlRenderer
    private lateinit var camera: CameraController
    private lateinit var store: LookStore
    private lateinit var recorder: VideoRecorder
    private lateinit var orientationListener: OrientationEventListener

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val lutExecutor = Executors.newSingleThreadExecutor()
    private val lutGen = AtomicInteger()

    private var surfaceTextureReady = false
    private var isResumedNow = false
    private var useFront = false
    private var videoMode = true
    private var recording = false
    private var recordStartMs = 0L
    private var deviceOrientation = 0
    private var cameraInfo: CameraController.CameraInfo? = null
    private var evLower = 0
    private var evStep = 0f
    private var tonemapNoticeShown = false

    private lateinit var currentLook: Look
    private var editingLook: Look? = null

    private class SliderDef(
        val label: String,
        val min: Float,
        val max: Float,
        val get: (LookParams) -> Float,
        val set: (LookParams, Float) -> Unit
    )

    private val sliderDefs = listOf(
        SliderDef("노출", -1f, 1f, { it.exposure }, { p, v -> p.exposure = v }),
        SliderDef("색온도 (따뜻하게 +)", -1f, 1f, { it.warmth }, { p, v -> p.warmth = v }),
        SliderDef("틴트 (초록 − / 마젠타 +)", -1f, 1f, { it.tint }, { p, v -> p.tint = v }),
        SliderDef("대비", 0.6f, 1.6f, { it.contrast }, { p, v -> p.contrast = v }),
        SliderDef("하이라이트 부드럽게", 0f, 1f, { it.rolloff }, { p, v -> p.rolloff = v }),
        SliderDef("암부 따뜻하게 (− 차갑게)", -1f, 1f, { it.shadowWarm }, { p, v -> p.shadowWarm = v }),
        SliderDef("페이드 (블랙 띄우기)", 0f, 1f, { it.fade }, { p, v -> p.fade = v }),
        SliderDef("채도", 0f, 1.6f, { it.saturation }, { p, v -> p.saturation = v }),
        SliderDef("피부톤 보호", 0f, 1f, { it.skin }, { p, v -> p.skin = v }),
        SliderDef("피부 혈색 (+ 붉게 / − 노랗게)", -1f, 1f, { it.skinHue }, { p, v -> p.skinHue = v }),
        SliderDef("빨강 채도", 0f, 1.6f, { it.redSat }, { p, v -> p.redSat = v }),
        SliderDef("초록 색상 (+ 노랗게 / − 청록)", -1f, 1f, { it.greenHue }, { p, v -> p.greenHue = v }),
        SliderDef("초록 채도", 0f, 1.6f, { it.greenSat }, { p, v -> p.greenSat = v }),
        SliderDef("파랑 색상 (+ 보라 / − 청록)", -1f, 1f, { it.blueHue }, { p, v -> p.blueHue = v }),
        SliderDef("파랑 채도", 0f, 1.6f, { it.blueSat }, { p, v -> p.blueSat = v })
    )

    // ───────────────────────── 생명주기 ─────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        bindViews()

        store = LookStore(this)
        recorder = VideoRecorder(this)
        currentLook = store.selected()

        renderer = GlRenderer()
        renderer.onCurveIgnored = {
            runOnUiThread { toast("이 카메라는 로그 커브를 무시해서 화면 변환 방식으로 바꿨어요") }
        }
        renderer.start {
            runOnUiThread {
                surfaceTextureReady = true
                maybeOpenCamera()
            }
        }
        camera = CameraController(this, this)

        preview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                updateDisplayRotation()
                renderer.setWindowSurface(holder.surface, width, height)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                renderer.setWindowSurface(null, 0, 0)
            }
        })

        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                deviceOrientation = ((orientation + 45) / 90 * 90) % 360
            }
        }

        setupControls()
        applyLook(currentLook)
        updateLookButton()
        updateShutter()

        val need = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { !has(it) }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), REQ_PERMS)
    }

    override fun onResume() {
        super.onResume()
        isResumedNow = true
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        maybeOpenCamera()
    }

    override fun onPause() {
        stopRecording()
        camera.close()
        isResumedNow = false
        orientationListener.disable()
        super.onPause()
    }

    override fun onDestroy() {
        camera.release()
        renderer.release()
        worker.shutdown()
        lutExecutor.shutdown()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateDisplayRotation()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            editorPanel.visibility == View.VISIBLE -> closeEditor(revert = true)
            lookPanel.visibility == View.VISIBLE -> showLookPanel(false)
            else -> super.onBackPressed()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!has(Manifest.permission.CAMERA)) {
            toast("카메라 권한이 있어야 촬영할 수 있어요")
            return
        }
        maybeOpenCamera()
    }

    // ───────────────────────── 카메라 ─────────────────────────

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun updateDisplayRotation() {
        val rot = display?.rotation ?: 0
        renderer.displayRotation = rot * 90
    }

    private fun maybeOpenCamera() {
        if (!surfaceTextureReady || !isResumedNow || !has(Manifest.permission.CAMERA)) return
        val info = try { camera.select(useFront) } catch (e: Exception) { null }
        if (info == null) {
            toast("카메라를 찾을 수 없어요")
            return
        }
        cameraInfo = info
        updateDisplayRotation()
        renderer.configure(info.size.width, info.size.height, info.sensorOrientation, info.isFront)
        camera.open(info, renderer.cameraSurface)
        setupEvBar(info)
    }

    private fun setupEvBar(info: CameraController.CameraInfo) {
        val (range, step) = try { camera.evRange(info.id) } catch (e: Exception) { return }
        evLower = range.lower
        evStep = step
        evBar.max = range.upper - range.lower
        evBar.progress = -range.lower
        evBar.isEnabled = evBar.max > 0
        updateEvLabel(0)
    }

    private fun updateEvLabel(index: Int) {
        evValue.text = String.format(Locale.US, "%+.1f", index * evStep)
    }

    override fun onTonemapStatus(curveApplied: Boolean) {
        runOnUiThread {
            renderer.inputMode = if (curveApplied) 0 else 1
            if (!tonemapNoticeShown) {
                tonemapNoticeShown = true
                toast(
                    if (curveApplied) "로그 커브 적용됨 — 암부·명부를 넓게 담아요"
                    else "이 렌즈는 로그 커브를 막아둬서 화면 변환으로 대신해요"
                )
            }
        }
    }

    override fun onCameraError(message: String) {
        runOnUiThread { toast(message) }
    }

    /** 사진/영상 저장 회전값 (Camera2 공식). */
    private fun outputRotation(info: CameraController.CameraInfo): Int {
        val d = if (info.isFront) -deviceOrientation else deviceOrientation
        // 카메라가 이미 세워서 준 영상이면 폰을 돌린 만큼만 보정
        val base = if (renderer.transformRotates) 0 else info.sensorOrientation
        return (base + d + 720) % 360
    }

    // ───────────────────────── 컨트롤 ─────────────────────────

    private fun bindViews() {
        preview = findViewById(R.id.preview)
        flash = findViewById(R.id.flash)
        focusRing = findViewById(R.id.focusRing)
        btnLook = findViewById(R.id.btnLook)
        btnLutToggle = findViewById(R.id.btnLutToggle)
        btnFlip = findViewById(R.id.btnFlip)
        recTime = findViewById(R.id.recTime)
        bottomBar = findViewById(R.id.bottomBar)
        evBar = findViewById(R.id.evBar)
        evValue = findViewById(R.id.evValue)
        btnMode = findViewById(R.id.btnMode)
        shutter = findViewById(R.id.shutter)
        btnEdit = findViewById(R.id.btnEdit)
        editorPanel = findViewById(R.id.editorPanel)
        editorTitle = findViewById(R.id.editorTitle)
        sliderContainer = findViewById(R.id.sliderContainer)
        lookPanel = findViewById(R.id.lookPanel)
        lookContainer = findViewById(R.id.lookContainer)
    }

    private fun setupControls() {
        shutter.setOnClickListener { onShutter() }

        btnMode.setOnClickListener {
            if (recording) return@setOnClickListener
            videoMode = !videoMode
            updateShutter()
        }

        btnFlip.setOnClickListener {
            if (recording) return@setOnClickListener
            useFront = !useFront
            tonemapNoticeShown = false
            camera.close()
            maybeOpenCamera()
        }

        btnLutToggle.setOnClickListener {
            renderer.lutOn = !renderer.lutOn
            btnLutToggle.text = if (renderer.lutOn) "LUT 켬" else "LUT 끔"
            if (!renderer.lutOn) toast("LUT 꺼짐 — 지금 찍으면 로그 원본(밋밋한 화면)으로 저장돼요")
        }

        btnLook.setOnClickListener { showLookPanel(true) }
        btnEdit.setOnClickListener { openEditor() }

        evBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val idx = evLower + progress
                updateEvLabel(idx)
                if (fromUser) camera.setEv(idx)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                onTapFocus(e.x, e.y)
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                camera.clearMetering()
                focusRing.visibility = View.INVISIBLE
                toast("초점·노출 자동으로 돌아감")
            }
        })
        preview.setOnTouchListener { _, ev -> detector.onTouchEvent(ev) }

        findViewById<View>(R.id.btnEdCancel).setOnClickListener { closeEditor(revert = true) }
        findViewById<View>(R.id.btnEdReset).setOnClickListener {
            val e = editingLook ?: return@setOnClickListener
            e.params.setFrom(currentLook.params)
            buildSliders()
            applyLook(e)
        }
        findViewById<View>(R.id.btnEdExport).setOnClickListener { exportEditing() }
        findViewById<View>(R.id.btnEdSaveAs).setOnClickListener { saveEditing(asNew = true) }
        findViewById<View>(R.id.btnEdSave).setOnClickListener { saveEditing(asNew = false) }

        findViewById<View>(R.id.btnImport).setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_IMPORT)
        }
        findViewById<View>(R.id.btnLookClose).setOnClickListener { showLookPanel(false) }
    }

    private fun onTapFocus(x: Float, y: Float) {
        val info = cameraInfo ?: return
        val w = preview.width.toFloat()
        val h = preview.height.toFloat()
        if (w <= 0f || h <= 0f) return
        val deg = (info.sensorOrientation - renderer.displayRotation + 360) % 360
        val rotated = deg % 180 != 0
        val cw = (if (rotated) info.size.height else info.size.width).toFloat()
        val ch = (if (rotated) info.size.width else info.size.height).toFloat()
        val r = cw / ch
        val vr = w / h
        val sx = if (r > vr) 1f else r / vr
        val sy = if (r > vr) vr / r else 1f
        var u = ((x / w * 2f - 1f) / sx + 1f) / 2f
        val v = ((y / h * 2f - 1f) / sy + 1f) / 2f
        if (u < 0f || u > 1f || v < 0f || v > 1f) return
        if (info.isFront) u = 1f - u
        val (nx, ny) = when (deg) {
            90 -> Pair(v, 1f - u)
            180 -> Pair(1f - u, 1f - v)
            270 -> Pair(1f - v, u)
            else -> Pair(u, v)
        }
        camera.setMeteringPoint(nx, ny)

        focusRing.x = preview.x + x - focusRing.width / 2f
        focusRing.y = preview.y + y - focusRing.height / 2f
        focusRing.visibility = View.VISIBLE
        focusRing.alpha = 1f
        focusRing.scaleX = 1.3f
        focusRing.scaleY = 1.3f
        focusRing.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
        focusRing.animate().alpha(0.4f).setStartDelay(1200).setDuration(600).start()
    }

    private fun updateShutter() {
        btnMode.text = if (videoMode) "영상" else "사진"
        shutter.setBackgroundResource(
            when {
                recording -> R.drawable.shutter_recording
                videoMode -> R.drawable.shutter_video
                else -> R.drawable.shutter_photo
            }
        )
        recTime.visibility = if (recording) View.VISIBLE else View.GONE
        btnFlip.alpha = if (recording) 0.4f else 1f
        btnMode.alpha = if (recording) 0.4f else 1f
    }

    private fun onShutter() {
        val info = cameraInfo ?: return
        if (!videoMode) {
            takePhoto(info)
            return
        }
        if (recording) stopRecording() else startRecording(info)
    }

    private fun takePhoto(info: CameraController.CameraInfo) {
        val rot = outputRotation(info)
        flash.alpha = 0.7f
        flash.animate().alpha(0f).setDuration(250).start()
        renderer.capturePhoto { bmp ->
            worker.execute {
                val m = android.graphics.Matrix()
                m.postScale(1f, -1f)
                m.postRotate(rot.toFloat())
                val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (out !== bmp) bmp.recycle()
                val uri = PhotoSaver.save(this, out)
                out.recycle()
                runOnUiThread { toast(if (uri != null) "사진 저장됨 · 사진/LogCamera" else "사진 저장 실패") }
            }
        }
    }

    private fun startRecording(info: CameraController.CameraInfo) {
        try {
            val (outW, outH) = renderer.outputSize()
            val surface = recorder.start(
                outW, outH, outputRotation(info),
                has(Manifest.permission.RECORD_AUDIO)
            )
            renderer.setEncoderSurface(surface)
            recording = true
            recordStartMs = SystemClock.elapsedRealtime()
            updateShutter()
            tickTimer()
        } catch (e: Exception) {
            toast("녹화를 시작할 수 없어요: ${e.message}")
        }
    }

    private fun stopRecording() {
        if (!recording) return
        recording = false
        renderer.setEncoderSurface(null)
        val uri = recorder.stop()
        updateShutter()
        toast(if (uri != null) "영상 저장됨 · 동영상/LogCamera" else "영상 저장 실패 (너무 짧았을 수 있어요)")
    }

    private fun tickTimer() {
        if (!recording) return
        val sec = (SystemClock.elapsedRealtime() - recordStartMs) / 1000
        recTime.text = String.format(Locale.US, "● %02d:%02d", sec / 60, sec % 60)
        mainHandler.postDelayed({ tickTimer() }, 500)
    }

    // ───────────────────────── LUT ─────────────────────────

    /** 룩 → 33³ LUT 계산 → GPU 업로드 (최신 요청만 반영). */
    private fun applyLook(look: Look) {
        val gen = lutGen.incrementAndGet()
        val params = look.params.copy()
        val base = look.baseCube
        lutExecutor.execute {
            if (gen != lutGen.get()) return@execute
            val cube = base?.let { store.loadCube(it) }
            val data = LookEngine.build(params, cube, LookEngine.LUT_SIZE)
            if (gen == lutGen.get()) renderer.uploadLut(data, LookEngine.LUT_SIZE)
        }
    }

    private fun updateLookButton() {
        btnLook.text = "LUT · ${currentLook.name}"
    }

    private fun selectLook(look: Look) {
        currentLook = look
        store.setSelected(look.id)
        applyLook(look)
        updateLookButton()
    }

    private fun showLookPanel(show: Boolean) {
        if (show) {
            populateLooks()
            lookPanel.visibility = View.VISIBLE
            bottomBar.visibility = View.GONE
        } else {
            lookPanel.visibility = View.GONE
            bottomBar.visibility = View.VISIBLE
        }
    }

    private fun populateLooks() {
        lookContainer.removeAllViews()
        for (look in store.all()) {
            val selected = look.id == currentLook.id
            val tv = TextView(this).apply {
                text = buildString {
                    append(if (selected) "●  " else "○  ")
                    append(look.name)
                    if (look.builtin) append("   · 기본")
                    if (look.baseCube != null) append("   · .cube")
                }
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setBackgroundResource(if (selected) R.drawable.chip_selected_bg else R.drawable.row_bg)
                setOnClickListener {
                    selectLook(look)
                    showLookPanel(false)
                }
                setOnLongClickListener {
                    if (look.builtin) {
                        toast("기본 LUT는 지울 수 없어요")
                    } else {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("'${look.name}' 삭제할까요?")
                            .setPositiveButton("삭제") { _, _ ->
                                store.delete(look)
                                if (look.id == currentLook.id) selectLook(Look.builtins().first())
                                populateLooks()
                            }
                            .setNegativeButton("취소", null)
                            .show()
                    }
                    true
                }
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(6)
            lookContainer.addView(tv, lp)
        }
    }

    private fun openEditor() {
        editingLook = currentLook.deepCopy()
        editorTitle.text = "LUT 만들기 · ${currentLook.name} 기준"
        buildSliders()
        editorPanel.visibility = View.VISIBLE
        bottomBar.visibility = View.GONE
        lookPanel.visibility = View.GONE
    }

    private fun closeEditor(revert: Boolean) {
        editingLook = null
        editorPanel.visibility = View.GONE
        bottomBar.visibility = View.VISIBLE
        if (revert) applyLook(currentLook)
    }

    private fun buildSliders() {
        val e = editingLook ?: return
        sliderContainer.removeAllViews()
        for (def in sliderDefs) {
            val label = TextView(this).apply {
                setTextColor(0xDDFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(4), dp(8), dp(4), 0)
            }
            fun refresh() {
                label.text = String.format(Locale.US, "%s   %.2f", def.label, def.get(e.params))
            }
            refresh()
            val bar = SeekBar(this).apply {
                max = 1000
                progress = (((def.get(e.params) - def.min) / (def.max - def.min)) * 1000f).toInt().coerceIn(0, 1000)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        def.set(e.params, def.min + (def.max - def.min) * p / 1000f)
                        refresh()
                        applyLook(e)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar) {}
                    override fun onStopTrackingTouch(sb: SeekBar) {}
                })
            }
            sliderContainer.addView(label)
            sliderContainer.addView(bar)
        }
    }

    private fun saveEditing(asNew: Boolean) {
        val e = editingLook ?: return
        if (asNew || currentLook.builtin) {
            askName(if (currentLook.builtin) "${currentLook.name} 수정" else "${currentLook.name} 2") { name ->
                val nl = store.newLook(name, e)
                selectLook(nl)
                closeEditor(revert = false)
                toast("'$name' 저장됨")
            }
        } else {
            val updated = currentLook.copy(params = e.params.copy())
            store.save(updated)
            selectLook(updated)
            closeEditor(revert = false)
            toast("'${updated.name}' 저장됨")
        }
    }

    private fun exportEditing() {
        val e = editingLook ?: return
        val look = e.copy(name = currentLook.name, params = e.params.copy())
        worker.execute {
            val uri = try { store.exportCube(look) } catch (ex: Exception) { null }
            runOnUiThread {
                toast(if (uri != null) "다운로드/LogCamera 에 .cube 저장됨" else "내보내기 실패")
            }
        }
    }

    private fun askName(initial: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle("LUT 이름")
            .setView(input)
            .setPositiveButton("저장") { _, _ ->
                val name = input.text.toString().trim().ifBlank { initial }
                onOk(name)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        worker.execute {
            val result = try {
                val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: throw IllegalArgumentException("파일을 읽을 수 없어요")
                store.importCube(displayName(uri), text)
            } catch (e: Exception) {
                runOnUiThread { toast("가져오기 실패: ${e.message}") }
                null
            }
            if (result != null) runOnUiThread {
                selectLook(result)
                populateLooks()
                toast("'${result.name}' 가져옴 — 로그 기준 LUT라 색이 다를 수 있어요")
            }
        }
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0) ?: "가져온 LUT"
        }
        return "가져온 LUT"
    }

    // ───────────────────────── 유틸 ─────────────────────────

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REQ_PERMS = 1
        private const val REQ_IMPORT = 2
    }
}
