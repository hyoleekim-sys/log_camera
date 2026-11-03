package com.yangkim.logcamera

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.max

/** 마이크 열기: 폰·유선은 48kHz, 블루투스는 통화용(16kHz)으로 열어야 실제로 잡힘. */
object MicOpener {
    fun isBt(d: AudioDeviceInfo?) = d != null &&
        (d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || d.type == AudioDeviceInfo.TYPE_BLE_HEADSET)

    /** (녹음기, 배율) — 배율 3이면 16kHz를 48kHz로 늘려 써야 함 */
    @SuppressLint("MissingPermission")
    fun open(dev: AudioDeviceInfo?): Pair<AudioRecord, Int>? {
        val bt = isBt(dev)
        val rate = if (bt) 16000 else 48000
        val src = when {
            bt -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            dev != null && dev.type != AudioDeviceInfo.TYPE_BUILTIN_MIC -> MediaRecorder.AudioSource.MIC
            else -> MediaRecorder.AudioSource.CAMCORDER
        }
        return try {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val r = AudioRecord.Builder()
                .setAudioSource(src)
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
                )
                .setBufferSizeInBytes(max(min * 4, 960 * 2 * 8))
                .build()
            if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return null }
            if (dev != null) r.setPreferredDevice(dev)
            Pair(r, if (bt) 3 else 1)
        } catch (e: Exception) {
            null
        }
    }

    /** k배로 늘리기 (직선 보간). 결과 길이 반환. */
    fun upsample(src: ShortArray, n: Int, k: Int, out: ShortArray): Int {
        if (k == 1) { System.arraycopy(src, 0, out, 0, n); return n }
        var o = 0
        for (j in 0 until n) {
            val a = src[j].toInt()
            val b = if (j + 1 < n) src[j + 1].toInt() else a
            for (t in 0 until k) {
                if (o < out.size) out[o++] = (a + (b - a) * t / k).toShort()
            }
        }
        return o
    }
}
