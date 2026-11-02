package com.yangkim.logcamera

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** 다빈치 리졸브식 간단 보정 값. 0 = 변화 없음. */
class Grade(
    val id: String,
    var name: String,
    var temp: Float = 0f,        // 색온도  -1(차갑게)..1(따뜻하게)
    var tint: Float = 0f,        // 틴트    -1(초록)..1(마젠타)
    var exposure: Float = 0f,    // 노출    -1..1 스톱
    var contrast: Float = 0f,    // 대비    -1..1
    var highlights: Float = 0f,  // 하이라이트 -1..1
    var shadows: Float = 0f,     // 섀도     -1..1
    var whites: Float = 0f,      // 화이트   -1..1
    var blacks: Float = 0f,      // 블랙     -1..1
    var saturation: Float = 0f,  // 채도     -1..1
    var skinBright: Float = 0f,  // 얼굴 밝기 -1..1
    var skinHue: Float = 0f,     // 얼굴 톤  -1(노랗게)..1(붉게)
    var skinSat: Float = 0f,     // 얼굴 채도 -1..1
    /** 커브: x = 0, .25, .5, .75, 1 에서의 출력값 */
    val curve: FloatArray = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f),
    val createdAt: Long = System.currentTimeMillis()
) {
    val isNeutral: Boolean
        get() = listOf(temp, tint, exposure, contrast, highlights, shadows, whites, blacks,
            saturation, skinBright, skinHue, skinSat).all { abs(it) < 0.001f } &&
            curve.withIndex().all { (i, v) -> abs(v - i * 0.25f) < 0.001f }

    fun copyAs(newId: String = id, newName: String = name): Grade = Grade(
        newId, newName, temp, tint, exposure, contrast, highlights, shadows, whites, blacks,
        saturation, skinBright, skinHue, skinSat, curve.copyOf(),
        if (newId == id) createdAt else System.currentTimeMillis()
    )

    fun setFrom(o: Grade) {
        temp = o.temp; tint = o.tint; exposure = o.exposure; contrast = o.contrast
        highlights = o.highlights; shadows = o.shadows; whites = o.whites; blacks = o.blacks
        saturation = o.saturation; skinBright = o.skinBright; skinHue = o.skinHue; skinSat = o.skinSat
        for (i in curve.indices) curve[i] = o.curve[i]
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("createdAt", createdAt)
        put("temp", temp.toDouble()); put("tint", tint.toDouble()); put("exposure", exposure.toDouble())
        put("contrast", contrast.toDouble()); put("highlights", highlights.toDouble())
        put("shadows", shadows.toDouble()); put("whites", whites.toDouble()); put("blacks", blacks.toDouble())
        put("saturation", saturation.toDouble()); put("skinBright", skinBright.toDouble())
        put("skinHue", skinHue.toDouble()); put("skinSat", skinSat.toDouble())
        put("curve", JSONArray().apply { for (v in curve) put(v.toDouble()) })
    }

    companion object {
        const val NONE_ID = "none"
        fun none() = Grade(NONE_ID, "보정 없음", createdAt = 0L)

        fun fromJson(o: JSONObject): Grade {
            fun f(k: String) = o.optDouble(k, 0.0).toFloat()
            val c = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
            o.optJSONArray("curve")?.let { a -> for (i in 0 until min(5, a.length())) c[i] = a.optDouble(i, c[i].toDouble()).toFloat() }
            return Grade(
                o.getString("id"), o.optString("name", "내 보정"),
                f("temp"), f("tint"), f("exposure"), f("contrast"), f("highlights"), f("shadows"),
                f("whites"), f("blacks"), f("saturation"), f("skinBright"), f("skinHue"), f("skinSat"),
                c, o.optLong("createdAt", 0L)
            )
        }
    }
}

/** 보정값 → 3D LUT 계산, 사진에 적용. */
object GradeEngine {
    const val N = 33

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun bell(x: Float, c: Float, w: Float): Float {
        val d = (x - c) / w
        return exp(-d * d)
    }

    /** 단조 3차 보간 커브 (점 5개, x 간격 0.25). */
    private class Curve(ys: FloatArray) {
        private val y = ys.copyOf()
        private val m = FloatArray(5)
        init {
            val h = 0.25f
            val d = FloatArray(4) { (y[it + 1] - y[it]) / h }
            m[0] = d[0]; m[4] = d[3]
            for (i in 1..3) m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
            for (i in 0..3) {
                if (abs(d[i]) < 1e-6f) { m[i] = 0f; m[i + 1] = 0f; continue }
                val a = m[i] / d[i]
                val b = m[i + 1] / d[i]
                val s = a * a + b * b
                if (s > 9f) {
                    val t = 3f / kotlin.math.sqrt(s)
                    m[i] = t * a * d[i]
                    m[i + 1] = t * b * d[i]
                }
            }
        }
        fun eval(x0: Float): Float {
            val x = x0.coerceIn(0f, 1f)
            val i = min((x / 0.25f).toInt(), 3)
            val h = 0.25f
            val t = (x - i * h) / h
            val t2 = t * t
            val t3 = t2 * t
            return (2 * t3 - 3 * t2 + 1) * y[i] + (t3 - 2 * t2 + t) * h * m[i] +
                (-2 * t3 + 3 * t2) * y[i + 1] + (t3 - t2) * h * m[i + 1]
        }
    }

    /** 커브 화면에 그릴 때 쓰는 값. */
    fun curveAt(ys: FloatArray, x: Float): Float = Curve(ys).eval(x)

    /** 톤(밝기) 조정 한 채널. 입력·출력 0..1 감마 값. */
    private fun tone(v0: Float, g: Grade): Float {
        var v = v0
        // 블랙: 가장 어두운 곳을 띄우거나 누름
        v += g.blacks * 0.10f * (1f - v).pow(4)
        // 섀도
        v += g.shadows * 0.14f * bell(v, 0.22f, 0.2f)
        // 하이라이트
        v += g.highlights * 0.14f * bell(v, 0.75f, 0.2f)
        // 화이트: 가장 밝은 곳
        v += g.whites * 0.10f * v.coerceAtLeast(0f).pow(4)
        // 대비 (0.45 기준 S커브)
        val c = 1f + g.contrast * 0.6f
        val p = 0.45f
        val x = v.coerceIn(0f, 1f)
        v = if (x <= p) p * (x / p).pow(c) else 1f - (1f - p) * ((1f - x) / (1f - p)).pow(c)
        return v
    }

    fun build(g: Grade, n: Int = N): FloatArray {
        val out = FloatArray(n * n * n * 3)
        val curve = Curve(g.curve)
        val gain = 2f.pow(g.exposure)
        val wr = 1f + 0.10f * g.temp + 0.03f * g.tint
        val wg = 1f - 0.07f * g.tint
        val wb = 1f - 0.12f * g.temp + 0.03f * g.tint
        val hsv = FloatArray(3)
        val rgb = FloatArray(3)
        val inv = 1f / (n - 1)
        var o = 0
        for (bi in 0 until n) for (gi in 0 until n) for (ri in 0 until n) {
            grade(ri * inv, gi * inv, bi * inv, g, curve, gain, wr, wg, wb, hsv, rgb)
            out[o++] = rgb[0]; out[o++] = rgb[1]; out[o++] = rgb[2]
        }
        return out
    }

    private fun grade(
        r0: Float, g0: Float, b0: Float, g: Grade, curve: Curve,
        gain: Float, wr: Float, wg: Float, wb: Float, hsv: FloatArray, out: FloatArray
    ) {
        // 선형으로 → 노출·화이트밸런스 → 다시 감마
        var r = (r0.pow(2.2f) * gain * wr).coerceAtLeast(0f).pow(1f / 2.2f)
        var gg = (g0.pow(2.2f) * gain * wg).coerceAtLeast(0f).pow(1f / 2.2f)
        var b = (b0.pow(2.2f) * gain * wb).coerceAtLeast(0f).pow(1f / 2.2f)
        // 톤
        r = curve.eval(tone(r, g)); gg = curve.eval(tone(gg, g)); b = curve.eval(tone(b, g))
        r = r.coerceIn(0f, 1f); gg = gg.coerceIn(0f, 1f); b = b.coerceIn(0f, 1f)
        // 채도 + 얼굴
        rgbToHsv(r, gg, b, hsv)
        var h = hsv[0]
        var s = hsv[1]
        var v = hsv[2]
        var dh = abs(h - 0.07f)
        dh = min(dh, 1f - dh)
        val skin = exp(-(dh / 0.055f) * (dh / 0.055f)) *
            smooth(0.06f, 0.18f, s) * (1f - smooth(0.75f, 0.95f, s)) * smooth(0.08f, 0.25f, v)
        s *= 1f + g.saturation * 0.8f * (1f - 0.5f * skin)
        s *= 1f + g.skinSat * 0.45f * skin
        h -= g.skinHue * 0.022f * skin
        if (h < 0f) h += 1f
        if (h >= 1f) h -= 1f
        v *= 1f + g.skinBright * 0.22f * skin
        hsvToRgb(h, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f), out)
    }

    /** 사진(비트맵)에 LUT 적용. 픽셀 단위 삼선형 보간. */
    fun apply(bmp: Bitmap, lut: FloatArray, n: Int = N) {
        val w = bmp.width
        val h = bmp.height
        val row = IntArray(w)
        val m = n - 1
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val p = row[x]
                val fr = ((p shr 16) and 0xFF) / 255f * m
                val fg = ((p shr 8) and 0xFF) / 255f * m
                val fb = (p and 0xFF) / 255f * m
                val r0 = min(fr.toInt(), m - 1); val g0 = min(fg.toInt(), m - 1); val b0 = min(fb.toInt(), m - 1)
                val tr = fr - r0; val tg = fg - g0; val tb = fb - b0
                var res = 0xFF000000.toInt()
                for (c in 0 until 3) {
                    fun at(ri: Int, gi: Int, bi: Int) = lut[((bi * n + gi) * n + ri) * 3 + c]
                    val x00 = at(r0, g0, b0) + (at(r0 + 1, g0, b0) - at(r0, g0, b0)) * tr
                    val x10 = at(r0, g0 + 1, b0) + (at(r0 + 1, g0 + 1, b0) - at(r0, g0 + 1, b0)) * tr
                    val x01 = at(r0, g0, b0 + 1) + (at(r0 + 1, g0, b0 + 1) - at(r0, g0, b0 + 1)) * tr
                    val x11 = at(r0, g0 + 1, b0 + 1) + (at(r0 + 1, g0 + 1, b0 + 1) - at(r0, g0 + 1, b0 + 1)) * tr
                    val y0 = x00 + (x10 - x00) * tg
                    val y1 = x01 + (x11 - x01) * tg
                    val v = ((y0 + (y1 - y0) * tb) * 255f + 0.5f).toInt().coerceIn(0, 255)
                    res = res or (v shl (16 - 8 * c))
                }
                row[x] = res
            }
            bmp.setPixels(row, 0, w, 0, y, w, 1)
        }
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

/** 내 보정 저장/불러오기. */
class GradeStore(ctx: Context) {
    private val dir = File(ctx.filesDir, "grades").apply { mkdirs() }
    private val prefs = ctx.getSharedPreferences("procam", Context.MODE_PRIVATE)

    fun all(): List<Grade> = listOf(Grade.none()) + userGrades()

    private fun userGrades(): List<Grade> {
        val files = dir.listFiles { f -> f.extension == "json" } ?: return emptyList()
        return files.mapNotNull { try { Grade.fromJson(JSONObject(it.readText())) } catch (e: Exception) { null } }
            .sortedBy { it.createdAt }
    }

    fun save(g: Grade) {
        if (g.id == Grade.NONE_ID) return
        File(dir, g.id + ".json").writeText(g.toJson().toString())
    }

    fun delete(g: Grade) {
        File(dir, g.id + ".json").delete()
    }

    fun newId(): String = UUID.randomUUID().toString()

    fun selected(): Grade {
        val id = prefs.getString("selectedGrade", null)
        return all().firstOrNull { it.id == id } ?: Grade.none()
    }

    fun setSelected(id: String) {
        prefs.edit().putString("selectedGrade", id).apply()
    }
}
