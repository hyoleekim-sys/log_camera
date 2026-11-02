package com.yangkim.logcamera

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.window.area.WindowAreaCapability
import androidx.window.area.WindowAreaController
import androidx.window.area.WindowAreaInfo
import androidx.window.area.WindowAreaPresentationSessionCallback
import androidx.window.area.WindowAreaSessionPresenter
import androidx.window.core.ExperimentalWindowApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 후면 셀피: 펼친 상태에서 바깥(커버) 화면에 미리보기를 띄움.
 * 삼성이 막아두면 available 이 계속 false.
 */
@OptIn(ExperimentalWindowApi::class)
class RearSelfie(
    private val activity: Activity,
    private val renderer: GlRenderer,
    private val onStateChanged: () -> Unit,
    private val onShutter: () -> Unit,
    private val onMessage: (String) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var area: WindowAreaInfo? = null
    private var session: WindowAreaSessionPresenter? = null

    /** 지금 바깥 화면을 쓸 수 있는지 (펼친 상태 등) */
    var available = false
        private set
    /** 바깥 화면에 미리보기가 떠 있는지 */
    val active: Boolean get() = session != null

    fun start() {
        if (job != null) return
        job = scope.launch {
            try {
                WindowAreaController.getOrCreate().windowAreaInfos.collect { infos ->
                    val rear = infos.firstOrNull { it.type == WindowAreaInfo.Type.TYPE_REAR_FACING }
                    area = rear
                    val status = rear?.getCapability(WindowAreaCapability.Operation.OPERATION_PRESENT_ON_AREA)?.status
                    val ok = status == WindowAreaCapability.Status.WINDOW_AREA_STATUS_AVAILABLE ||
                        status == WindowAreaCapability.Status.WINDOW_AREA_STATUS_ACTIVE
                    if (ok != available) {
                        available = ok
                        onStateChanged()
                    }
                }
            } catch (e: Throwable) {
                available = false
                onStateChanged()
            }
        }
    }

    fun stop() {
        close()
        job?.cancel()
        job = null
    }

    fun release() {
        stop()
        scope.cancel()
    }

    fun toggle() {
        if (active) { close(); return }
        val a = area
        if (a == null || !available) {
            onMessage("이 폰은 바깥 화면 사용을 막아뒀거나, 지금 접혀 있어요")
            return
        }
        try {
            WindowAreaController.getOrCreate().presentContentOnWindowArea(
                a.token, activity, { activity.runOnUiThread(it) }, callback
            )
        } catch (e: Throwable) {
            onMessage("바깥 화면을 열 수 없어요: ${e.message}")
        }
    }

    fun close() {
        try { session?.close() } catch (e: Throwable) {}
        session = null
        renderer.setMirrorSurface(null, 0, 0)
        onStateChanged()
    }

    private val callback = object : WindowAreaPresentationSessionCallback {
        override fun onSessionStarted(session: WindowAreaSessionPresenter) {
            this@RearSelfie.session = session
            session.setContentView(buildView(session))
            onStateChanged()
        }

        override fun onSessionEnded(t: Throwable?) {
            session = null
            renderer.setMirrorSurface(null, 0, 0)
            onStateChanged()
            if (t != null) onMessage("바깥 화면이 닫혔어요")
        }

        override fun onContainerVisibilityChanged(isVisible: Boolean) {}
    }

    /** 바깥 화면 내용: 미리보기 + 아래쪽 셔터 */
    private fun buildView(s: WindowAreaSessionPresenter): View {
        val ctx = s.context
        val d = ctx.resources.displayMetrics.density
        val root = FrameLayout(ctx)
        root.setBackgroundColor(Color.BLACK)
        val sv = SurfaceView(ctx)
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                renderer.setMirrorSurface(holder.surface, w, h)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                renderer.setMirrorSurface(null, 0, 0)
            }
        })
        root.addView(sv, FrameLayout.LayoutParams(-1, -1))
        val shutter = View(ctx)
        shutter.setBackgroundResource(R.drawable.shutter_photo)
        shutter.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            onShutter()
        }
        val lp = FrameLayout.LayoutParams((64 * d).toInt(), (64 * d).toInt(), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        lp.bottomMargin = (24 * d).toInt()
        root.addView(shutter, lp)
        val hint = TextView(ctx)
        hint.text = "후면 셀피"
        hint.setTextColor(0xCCFFFFFF.toInt())
        hint.textSize = 12f
        val hl = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        hl.topMargin = (16 * d).toInt()
        root.addView(hint, hl)
        return root
    }
}
