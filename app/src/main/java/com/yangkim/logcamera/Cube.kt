package com.yangkim.logcamera

import java.util.Locale
import kotlin.math.min

/** .cube 3D LUT. 데이터 순서: R이 가장 빠르게 변함 (표준 .cube 순서). */
class Cube(val size: Int, val data: FloatArray) {

    private fun idx(r: Int, g: Int, b: Int) = ((b * size + g) * size + r) * 3

    /** 삼선형 보간 샘플링. 입력 0..1 */
    fun sample(r: Float, g: Float, b: Float, out: FloatArray) {
        val m = size - 1
        val fr = r.coerceIn(0f, 1f) * m
        val fg = g.coerceIn(0f, 1f) * m
        val fb = b.coerceIn(0f, 1f) * m
        val r0 = min(fr.toInt(), m - 1)
        val g0 = min(fg.toInt(), m - 1)
        val b0 = min(fb.toInt(), m - 1)
        val tr = fr - r0
        val tg = fg - g0
        val tb = fb - b0
        for (c in 0 until 3) {
            val c000 = data[idx(r0, g0, b0) + c]
            val c100 = data[idx(r0 + 1, g0, b0) + c]
            val c010 = data[idx(r0, g0 + 1, b0) + c]
            val c110 = data[idx(r0 + 1, g0 + 1, b0) + c]
            val c001 = data[idx(r0, g0, b0 + 1) + c]
            val c101 = data[idx(r0 + 1, g0, b0 + 1) + c]
            val c011 = data[idx(r0, g0 + 1, b0 + 1) + c]
            val c111 = data[idx(r0 + 1, g0 + 1, b0 + 1) + c]
            val x00 = c000 + (c100 - c000) * tr
            val x10 = c010 + (c110 - c010) * tr
            val x01 = c001 + (c101 - c001) * tr
            val x11 = c011 + (c111 - c011) * tr
            val y0 = x00 + (x10 - x00) * tg
            val y1 = x01 + (x11 - x01) * tg
            out[c] = y0 + (y1 - y0) * tb
        }
    }

    companion object {
        fun parse(text: String): Cube {
            var size = 0
            val values = ArrayList<Float>()
            val ws = Regex("\\s+")
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val up = line.uppercase(Locale.US)
                when {
                    up.startsWith("LUT_3D_SIZE") -> size = line.split(ws)[1].toInt()
                    up.startsWith("LUT_1D") -> throw IllegalArgumentException("1D LUT는 지원하지 않아요")
                    up.startsWith("TITLE") || up.startsWith("DOMAIN") ||
                        up.startsWith("LUT_3D_INPUT_RANGE") -> Unit
                    else -> {
                        val parts = line.split(ws)
                        if (parts.size >= 3) {
                            val a = parts[0].toFloatOrNull()
                            val b = parts[1].toFloatOrNull()
                            val c = parts[2].toFloatOrNull()
                            if (a != null && b != null && c != null) {
                                values.add(a); values.add(b); values.add(c)
                            }
                        }
                    }
                }
            }
            require(size in 2..128) { "LUT_3D_SIZE 가 없어요" }
            val need = size * size * size * 3
            require(values.size >= need) { "LUT 데이터가 부족해요" }
            val arr = FloatArray(need)
            for (i in 0 until need) arr[i] = values[i]
            return Cube(size, arr)
        }

        fun toText(title: String, n: Int, data: FloatArray): String {
            val sb = StringBuilder(n * n * n * 28 + 200)
            sb.append("TITLE \"").append(title.replace("\"", "")).append("\"\n")
            sb.append("# Made with LogCamera. Input: LogCamera Log (K=20), Output: Rec.709 display\n")
            sb.append("LUT_3D_SIZE ").append(n).append('\n')
            sb.append("DOMAIN_MIN 0.0 0.0 0.0\nDOMAIN_MAX 1.0 1.0 1.0\n")
            for (i in 0 until n * n * n) {
                sb.append(String.format(Locale.US, "%.6f %.6f %.6f\n", data[i * 3], data[i * 3 + 1], data[i * 3 + 2]))
            }
            return sb.toString()
        }
    }
}
