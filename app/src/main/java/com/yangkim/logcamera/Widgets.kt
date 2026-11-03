package com.yangkim.logcamera

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 텐션 줌 레버.
 * 위로 밀면 +, 아래로 당기면 −. 손을 떼면 스프링처럼 가운데로 돌아감.
 * onChange 값(-1..1)은 "줌 속도"로 쓰임: 많이 밀수록 빠름.
 */
class ZoomLever(ctx: Context, attrs: AttributeSet?) : View(ctx, attrs) {
    var onChange: ((Float) -> Unit)? = null

    private var value = 0f
    private var startY = 0f
    private var startValue = 0f
    private var lastTick = 0f
    private var spring: ValueAnimator? = null
    private val d = resources.displayMetrics.density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }
    private val trackStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55FFFFFF; style = Paint.Style.STROKE; strokeWidth = 1f * d
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88FFFFFF.toInt(); strokeWidth = 1.5f * d }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF5F0EA.toInt() }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFC58A.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt(); textSize = 18f * d; textAlign = Paint.Align.CENTER
    }

    private fun thumbR() = width * 0.32f
    private fun travel() = height / 2f - thumbR() - 26f * d

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val tw = width * 0.42f
        val rect = RectF(cx - tw / 2, 22f * d, cx + tw / 2, height - 22f * d)
        canvas.drawRoundRect(rect, tw / 2, tw / 2, trackPaint)
        canvas.drawRoundRect(rect, tw / 2, tw / 2, trackStroke)
        canvas.drawLine(cx - tw * 0.8f, cy, cx + tw * 0.8f, cy, tickPaint)
        canvas.drawText("+", cx, 16f * d, textPaint)
        canvas.drawText("−", cx, height - 2f * d, textPaint)
        val ty = cy - value * travel()
        canvas.drawCircle(cx, ty, thumbR(), if (abs(value) > 0.02f) activePaint else thumbPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                spring?.cancel()
                parent?.requestDisallowInterceptTouchEvent(true)
                startY = e.y
                startValue = value
                lastTick = value
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val t = travel()
                if (t > 0f) {
                    value = (startValue + (startY - e.y) / t).coerceIn(-1f, 1f)
                    // 조금씩 밀 때마다 약한 진동 (끝에 닿으면 한 번 더)
                    if (abs(value - lastTick) >= 0.12f || (abs(value) >= 1f && abs(lastTick) < 1f)) {
                        lastTick = value
                        performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
                    }
                    onChange?.invoke(value)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                onChange?.invoke(0f)
                spring = ValueAnimator.ofFloat(value, 0f).apply {
                    duration = 260
                    interpolator = OvershootInterpolator(1.6f)
                    addUpdateListener { value = it.animatedValue as Float; invalidate() }
                    start()
                }
                return true
            }
        }
        return super.onTouchEvent(e)
    }
}

/** 밝기 분포 그래프. */
class HistogramView(ctx: Context, attrs: AttributeSet?) : View(ctx, attrs) {
    private val bins = FloatArray(64)
    private var pixels = IntArray(0)
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCF5F0EA.toInt() }
    private val d = resources.displayMetrics.density

    fun update(bmp: Bitmap) {
        val n = bmp.width * bmp.height
        if (pixels.size != n) pixels = IntArray(n)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val counts = IntArray(64)
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val y = (r * 54 + g * 183 + b * 19) shr 8
            counts[(y shr 2).coerceIn(0, 63)]++
        }
        var mx = 1
        for (c in counts) if (c > mx) mx = c
        for (i in 0 until 64) bins[i] = sqrt(counts[i].toFloat() / mx)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val r = 6f * d
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, bg)
        val pad = 4f * d
        val w = (width - pad * 2) / 64f
        val h = height - pad * 2
        for (i in 0 until 64) {
            val bh = bins[i] * h
            canvas.drawRect(pad + i * w, height - pad - bh, pad + (i + 1) * w, height - pad, fill)
        }
    }
}

/** 수평계: 기울기만큼 선이 돌아가고, 수평이면 초록색. */
class LevelView(ctx: Context, attrs: AttributeSet?) : View(ctx, attrs) {
    var tilt = 0f
        set(v) { field = v; invalidate() }
    private val d = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f * d; strokeCap = Paint.Cap.ROUND }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val len = 70f * d
        val gap = 14f * d
        val level = abs(tilt) < 1f
        line.color = if (level) 0xFF7CE38B.toInt() else 0xAAFFFFFF.toInt()
        // 고정 기준 눈금
        canvas.drawLine(cx - len - 14f * d, cy, cx - len - 4f * d, cy, line)
        canvas.drawLine(cx + len + 4f * d, cy, cx + len + 14f * d, cy, line)
        canvas.save()
        canvas.rotate(-tilt, cx, cy)
        canvas.drawLine(cx - len, cy, cx - gap, cy, line)
        canvas.drawLine(cx + gap, cy, cx + len, cy, line)
        canvas.restore()
    }
}

/** 커브 편집: 점 5개를 위아래로 끌어서 조정. */
class CurveView(ctx: Context, attrs: AttributeSet?) : View(ctx, attrs) {
    var points: FloatArray = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        set(v) { field = v; invalidate() }
    var onChange: (() -> Unit)? = null
    /** 선·점 색 (전체=흰색, R/G/B) */
    var lineColor: Int = 0xFFF5F0EA.toInt()
        set(v) { field = v; line.color = v; dot.color = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val pad = 14f * d
    private var active = -1
    private var lastTick = 0f
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88000000.toInt() }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF; strokeWidth = 1f * d }
    private val diag = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x44FFFFFF; strokeWidth = 1f * d }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF5F0EA.toInt(); strokeWidth = 2.2f * d; style = Paint.Style.STROKE
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFC58A.toInt() }
    private val path = android.graphics.Path()

    private fun px(x: Float) = pad + x * (width - 2 * pad)
    private fun py(y: Float) = height - pad - y * (height - 2 * pad)

    override fun onDraw(canvas: Canvas) {
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 10f * d, 10f * d, bg)
        for (i in 1..3) {
            val t = i / 4f
            canvas.drawLine(px(t), py(0f), px(t), py(1f), grid)
            canvas.drawLine(px(0f), py(t), px(1f), py(t), grid)
        }
        canvas.drawLine(px(0f), py(0f), px(1f), py(1f), diag)
        path.reset()
        for (i in 0..64) {
            val x = i / 64f
            val y = GradeEngine.curveAt(points, x).coerceIn(0f, 1f)
            if (i == 0) path.moveTo(px(x), py(y)) else path.lineTo(px(x), py(y))
        }
        canvas.drawPath(path, line)
        for (i in 0..4) {
            canvas.drawCircle(px(i / 4f), py(points[i]), if (i == active) 9f * d else 7f * d, dot)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val x = ((e.x - pad) / (width - 2 * pad)).coerceIn(0f, 1f)
                active = (x * 4f + 0.5f).toInt().coerceIn(0, 4)
                lastTick = points[active]
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                move(e.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> { move(e.y); return true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun move(y: Float) {
        if (active < 0) return
        points[active] = ((height - pad - y) / (height - 2 * pad)).coerceIn(0f, 1f)
        if (abs(points[active] - lastTick) >= 0.04f) {
            lastTick = points[active]
            performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
        }
        invalidate()
        onChange?.invoke()
    }
}


/**
 * 플래시 밝기 슬라이더. 밀어서 조절, 왼쪽 끝 = 끔.
 * levels 가 1이면 켬/끔 두 칸.
 */
class FlashSlider(ctx: Context, attrs: AttributeSet?) : View(ctx, attrs) {
    var levels = 1
        set(v) { field = v.coerceAtLeast(1); level = level.coerceIn(0, field); invalidate() }
    var level = 0
        private set
    var enabledFlash = true
        set(v) { field = v; alpha = if (v) 1f else 0.35f; invalidate() }
    var onChange: ((Int) -> Unit)? = null
    var onBlocked: (() -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55FFFFFF; style = Paint.Style.STROKE; strokeWidth = 1f * d
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC8A5A2B.toInt() }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFC58A.toInt() }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = 12f * d; textAlign = Paint.Align.CENTER
    }

    fun setLevelSilently(v: Int) { level = v.coerceIn(0, levels); invalidate() }

    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        val rect = RectF(1f * d, 1f * d, width - 1f * d, height - 1f * d)
        canvas.drawRoundRect(rect, r, r, bg)
        val frac = level.toFloat() / levels
        if (level > 0) {
            val fr = RectF(rect.left, rect.top, rect.left + (rect.width() - 2 * r) * frac + 2 * r, rect.bottom)
            canvas.drawRoundRect(fr, r, r, fill)
        }
        canvas.drawRoundRect(rect, r, r, stroke)
        val kx = rect.left + r + (rect.width() - 2 * r) * frac
        canvas.drawCircle(kx, height / 2f, r - 4f * d, knob)
        val label = when {
            level == 0 -> "플래시 끔"
            levels > 1 -> "플래시 $level/$levels"
            else -> "플래시 켬"
        }
        val ty = height / 2f - (text.descent() + text.ascent()) / 2f
        canvas.drawText(label, width / 2f, ty, text)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!enabledFlash) {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) onBlocked?.invoke()
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val r = height / 2f
                val frac = ((e.x - r) / (width - 2 * r)).coerceIn(0f, 1f)
                val nl = Math.round(frac * levels)
                if (e.actionMasked == MotionEvent.ACTION_DOWN) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                if (nl != level) {
                    level = nl
                    if (e.actionMasked == MotionEvent.ACTION_MOVE) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onChange?.invoke(level)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
        }
        return super.onTouchEvent(e)
    }
}
