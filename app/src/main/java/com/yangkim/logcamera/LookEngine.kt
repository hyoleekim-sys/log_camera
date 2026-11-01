package com.yangkim.logcamera

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 색 처리 엔진.
 *
 * 카메라 → "CamLog"(평평한 로그 곡선) → 3D LUT → 화면/저장.
 * LUT 입력은 항상 CamLog 이고, 출력은 일반 화면용(Rec.709 감마) 색입니다.
 */
object LookEngine {
    const val K = 20f
    private val LOG_NORM = ln(1f + K)
    const val LUT_SIZE = 33

    fun logEncode(x: Float): Float = ln(1f + K * max(x, 0f)) / LOG_NORM
    fun logDecode(y: Float): Float = (exp(y * LOG_NORM) - 1f) / K

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun rolloff(x: Float, knee: Float): Float {
        if (x <= knee) return x
        val range = 1f - knee
        return knee + range * (1f - exp(-(x - knee) / range))
    }

    private fun contrast(d0: Float, c: Float): Float {
        val d = d0.coerceIn(0f, 1f)
        val p = 0.45f
        return if (d <= p) p * (d / p).pow(c) else 1f - (1f - p) * ((1f - d) / (1f - p)).pow(c)
    }

    /** LUT 데이터(n^3 * 3, R이 가장 빠르게)를 만듭니다. */
    fun build(p: LookParams, base: Cube?, n: Int = LUT_SIZE): FloatArray {
        val out = FloatArray(n * n * n * 3)
        val tmp = FloatArray(3)
        val hsv = FloatArray(3)
        val gain = 2f.pow(p.exposure)
        val wr = 1f + 0.12f * p.warmth + 0.03f * p.tint
        val wg = 1f - 0.07f * p.tint
        val wb = 1f - 0.14f * p.warmth + 0.03f * p.tint
        val useRolloff = p.rolloff > 0.001f
        val knee = 1f - 0.6f * p.rolloff
        val fadeAmt = p.fade * 0.12f
        val inv = 1f / (n - 1)
        var o = 0
        for (bi in 0 until n) for (gi in 0 until n) for (ri in 0 until n) {
            // 1) 로그 → 선형 + 노출 + 화이트밸런스
            var lr = logDecode(ri * inv) * gain * wr
            var lg = logDecode(gi * inv) * gain * wg
            var lb = logDecode(bi * inv) * gain * wb
            // 2) 하이라이트 부드럽게
            if (useRolloff) {
                lr = rolloff(lr, knee); lg = rolloff(lg, knee); lb = rolloff(lb, knee)
            }
            // 3) 베이스: 기본 감마 또는 가져온 .cube
            var r: Float
            var g: Float
            var b: Float
            if (base == null) {
                r = lr.coerceIn(0f, 1f).pow(1f / 2.2f)
                g = lg.coerceIn(0f, 1f).pow(1f / 2.2f)
                b = lb.coerceIn(0f, 1f).pow(1f / 2.2f)
            } else {
                base.sample(
                    logEncode(lr).coerceIn(0f, 1f),
                    logEncode(lg).coerceIn(0f, 1f),
                    logEncode(lb).coerceIn(0f, 1f), tmp
                )
                r = tmp[0]; g = tmp[1]; b = tmp[2]
            }
            // 4) 대비
            r = contrast(r, p.contrast); g = contrast(g, p.contrast); b = contrast(b, p.contrast)
            // 5) 페이드 (블랙 띄우기)
            r = fadeAmt + r * (1f - fadeAmt)
            g = fadeAmt + g * (1f - fadeAmt)
            b = fadeAmt + b * (1f - fadeAmt)
            // 6) 암부 따뜻하게 — 어두운 곳이 푸르게 뜨지 않도록
            val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val ws = (1f - smooth(0f, 0.55f, y)) * (0.4f + 0.6f * smooth(0f, 0.1f, y))
            r += p.shadowWarm * 0.04f * ws
            g += p.shadowWarm * 0.012f * ws
            b -= p.shadowWarm * 0.04f * ws
            r = r.coerceIn(0f, 1f); g = g.coerceIn(0f, 1f); b = b.coerceIn(0f, 1f)
            // 7) 채도 + 피부톤 보호
            rgbToHsv(r, g, b, hsv)
            var h = hsv[0]
            var s = hsv[1]
            var v = hsv[2]
            var dh = abs(h - 0.07f)
            dh = min(dh, 1f - dh)
            val skinW = exp(-(dh / 0.05f) * (dh / 0.05f)) *
                smooth(0.06f, 0.2f, s) * (1f - smooth(0.7f, 0.95f, s)) *
                smooth(0.08f, 0.25f, v) * p.skin
            val skinSat = 1f + (p.saturation - 1f) * 0.35f
            s *= p.saturation + (skinSat - p.saturation) * skinW
            s *= 1f - 0.12f * skinW
            var diff = 0.07f - h
            if (diff > 0.5f) diff -= 1f
            if (diff < -0.5f) diff += 1f
            h += diff * 0.4f * skinW
            if (h < 0f) h += 1f
            if (h >= 1f) h -= 1f
            v *= 1f + 0.04f * skinW
            hsvToRgb(h, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f), tmp)
            out[o++] = tmp[0]
            out[o++] = tmp[1]
            out[o++] = tmp[2]
        }
        return out
    }

    private fun rgbToHsv(r: Float, g: Float, b: Float, out: FloatArray) {
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        var h = 0f
        if (d > 1e-6f) {
            h = when (mx) {
                r -> ((g - b) / d) / 6f
                g -> ((b - r) / d + 2f) / 6f
                else -> ((r - g) / d + 4f) / 6f
            }
            if (h < 0f) h += 1f
        }
        out[0] = h
        out[1] = if (mx > 1e-6f) d / mx else 0f
        out[2] = mx
    }

    private fun hsvToRgb(h: Float, s: Float, v: Float, out: FloatArray) {
        val hh = (h * 6f) % 6f
        val i = hh.toInt()
        val f = hh - i
        val p = v * (1f - s)
        val q = v * (1f - s * f)
        val t = v * (1f - s * (1f - f))
        when (i) {
            0 -> { out[0] = v; out[1] = t; out[2] = p }
            1 -> { out[0] = q; out[1] = v; out[2] = p }
            2 -> { out[0] = p; out[1] = v; out[2] = t }
            3 -> { out[0] = p; out[1] = q; out[2] = v }
            4 -> { out[0] = t; out[1] = p; out[2] = v }
            else -> { out[0] = v; out[1] = p; out[2] = q }
        }
    }
}
