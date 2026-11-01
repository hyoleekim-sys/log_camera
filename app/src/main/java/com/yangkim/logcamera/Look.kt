package com.yangkim.logcamera

import org.json.JSONObject

/** LUT를 만드는 슬라이더 값들. */
data class LookParams(
    var exposure: Float = 0f,     // -1..1 (스톱)
    var warmth: Float = 0f,       // -1..1  색온도 (+ 따뜻하게)
    var tint: Float = 0f,         // -1..1  (+ 마젠타 / - 초록)
    var contrast: Float = 1f,     // 0.6..1.6
    var rolloff: Float = 0.5f,    // 0..1   하이라이트 부드럽게
    var fade: Float = 0f,         // 0..1   블랙 띄우기
    var shadowWarm: Float = 0f,   // -1..1  암부 따뜻하게 (- 차갑게)
    var saturation: Float = 1f,   // 0..1.6
    var skin: Float = 0.5f,       // 0..1   피부톤 보호
    var skinHue: Float = 0f,      // -1..1  피부 (+ 붉게·혈색 / - 노랗게)
    var redSat: Float = 1f,       // 0..1.6 빨강 채도
    var greenHue: Float = 0f,     // -1..1  초록 (+ 노란 쪽 / - 청록 쪽)
    var greenSat: Float = 1f,     // 0..1.6 초록 채도
    var blueHue: Float = 0f,      // -1..1  파랑 (+ 보라 쪽 / - 청록 쪽)
    var blueSat: Float = 1f       // 0..1.6 파랑 채도
) {
    fun setFrom(o: LookParams) {
        exposure = o.exposure; warmth = o.warmth; tint = o.tint; contrast = o.contrast
        rolloff = o.rolloff; fade = o.fade; shadowWarm = o.shadowWarm
        saturation = o.saturation; skin = o.skin
        skinHue = o.skinHue; redSat = o.redSat; greenHue = o.greenHue
        greenSat = o.greenSat; blueHue = o.blueHue; blueSat = o.blueSat
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("exposure", exposure.toDouble())
        put("warmth", warmth.toDouble())
        put("tint", tint.toDouble())
        put("contrast", contrast.toDouble())
        put("rolloff", rolloff.toDouble())
        put("fade", fade.toDouble())
        put("shadowWarm", shadowWarm.toDouble())
        put("saturation", saturation.toDouble())
        put("skin", skin.toDouble())
        put("skinHue", skinHue.toDouble())
        put("redSat", redSat.toDouble())
        put("greenHue", greenHue.toDouble())
        put("greenSat", greenSat.toDouble())
        put("blueHue", blueHue.toDouble())
        put("blueSat", blueSat.toDouble())
    }

    companion object {
        fun neutral() = LookParams(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f)

        fun fromJson(o: JSONObject): LookParams {
            val d = LookParams()
            return LookParams(
                exposure = o.optDouble("exposure", d.exposure.toDouble()).toFloat(),
                warmth = o.optDouble("warmth", d.warmth.toDouble()).toFloat(),
                tint = o.optDouble("tint", d.tint.toDouble()).toFloat(),
                contrast = o.optDouble("contrast", d.contrast.toDouble()).toFloat(),
                rolloff = o.optDouble("rolloff", d.rolloff.toDouble()).toFloat(),
                fade = o.optDouble("fade", d.fade.toDouble()).toFloat(),
                shadowWarm = o.optDouble("shadowWarm", d.shadowWarm.toDouble()).toFloat(),
                saturation = o.optDouble("saturation", d.saturation.toDouble()).toFloat(),
                skin = o.optDouble("skin", d.skin.toDouble()).toFloat(),
                skinHue = o.optDouble("skinHue", 0.0).toFloat(),
                redSat = o.optDouble("redSat", 1.0).toFloat(),
                greenHue = o.optDouble("greenHue", 0.0).toFloat(),
                greenSat = o.optDouble("greenSat", 1.0).toFloat(),
                blueHue = o.optDouble("blueHue", 0.0).toFloat(),
                blueSat = o.optDouble("blueSat", 1.0).toFloat()
            )
        }
    }
}

/**
 * 하나의 LUT(룩).
 * baseCube 가 있으면 가져온 .cube 위에 슬라이더 조정이 더해집니다.
 */
data class Look(
    val id: String,
    val name: String,
    val builtin: Boolean,
    val baseCube: String?,
    val params: LookParams,
    val createdAt: Long = 0L
) {
    fun deepCopy(): Look = copy(params = params.copy())

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("baseCube", baseCube ?: JSONObject.NULL)
        put("params", params.toJson())
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(o: JSONObject): Look = Look(
            id = o.getString("id"),
            name = o.getString("name"),
            builtin = false,
            baseCube = if (o.isNull("baseCube")) null else o.optString("baseCube", ""),
            params = LookParams.fromJson(o.optJSONObject("params") ?: JSONObject()),
            createdAt = o.optLong("createdAt", 0L)
        )

        /** 기본 제공 룩. 첫 번째가 기본값. */
        fun builtins(): List<Look> = listOf(
            Look(
                "builtin_warm_portrait", "따뜻한 인물", true, null,
                LookParams(
                    exposure = 0f, warmth = 0.35f, tint = 0.1f, contrast = 1.05f,
                    rolloff = 0.6f, fade = 0.15f, shadowWarm = 0.6f,
                    saturation = 0.95f, skin = 0.7f
                )
            ),
            Look(
                "builtin_canon_like", "캐논 느낌", true, null,
                LookParams(
                    exposure = 0f, warmth = 0.3f, tint = 0.15f, contrast = 1.08f,
                    rolloff = 0.7f, fade = 0.08f, shadowWarm = 0.5f,
                    saturation = 1.05f, skin = 0.7f, skinHue = 0.45f,
                    redSat = 1.12f, greenHue = 0.5f, greenSat = 0.9f,
                    blueHue = -0.2f, blueSat = 1.05f
                )
            ),
            Look(
                "builtin_iphone_like", "아이폰 느낌", true, null,
                LookParams(
                    exposure = 0.15f, warmth = 0.1f, tint = 0f, contrast = 0.95f,
                    rolloff = 0.9f, fade = 0.02f, shadowWarm = 0.1f,
                    saturation = 1.12f, skin = 0.5f, skinHue = -0.1f,
                    redSat = 1.05f, greenHue = 0.1f, greenSat = 1.1f,
                    blueHue = -0.15f, blueSat = 1.15f
                )
            ),
            Look(
                "builtin_natural", "자연스럽게", true, null,
                LookParams(
                    exposure = 0f, warmth = 0.1f, tint = 0f, contrast = 1.0f,
                    rolloff = 0.5f, fade = 0f, shadowWarm = 0.25f,
                    saturation = 1.0f, skin = 0.4f
                )
            ),
            Look(
                "builtin_film_warm", "필름 웜", true, null,
                LookParams(
                    exposure = 0f, warmth = 0.5f, tint = 0.15f, contrast = 1.15f,
                    rolloff = 0.8f, fade = 0.35f, shadowWarm = 0.8f,
                    saturation = 0.85f, skin = 0.6f
                )
            ),
            Look(
                "builtin_soft_muted", "부드러운 저채도", true, null,
                LookParams(
                    exposure = 0.05f, warmth = 0.25f, tint = 0.05f, contrast = 0.9f,
                    rolloff = 0.7f, fade = 0.25f, shadowWarm = 0.5f,
                    saturation = 0.75f, skin = 0.6f
                )
            )
        )
    }
}
