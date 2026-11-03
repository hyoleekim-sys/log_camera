package com.yangkim.logcamera

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 마이크 소리를 이어폰으로 바로 들려주고 음량 레벨을 알려줌.
 * - 평소: 직접 마이크를 열어서 들려줌
 * - 녹화 중: 녹음되는 소리를 그대로 받아서 들려줌 (feed)
 */
class AudioMonitor(private val onLevel: (rmsDb: Float, peakDb: Float) -> Unit) {
    @Volatile var running = false
        private set
    /** 수음 크기 (녹화 중이 아닐 때 레벨·들리는 소리에 적용. 녹화 중엔 이미 적용된 소리가 들어옴) */
    @Volatile var gain = 1f

    @Volatile private var external = false
    private var track: AudioTrack? = null
    private var rec: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var capturing = false
    private var out = ShortArray(FRAME)
    private var lastLevelMs = 0L
    private var peakHold = -90f

    fun start(mic: AudioDeviceInfo?) {
        if (running) return
        running = true
        track = buildTrack()
        try { track?.play() } catch (e: Exception) {}
        if (!external) startCapture(mic)
    }

    fun stop() {
        running = false
        stopCapture()
        try { track?.pause(); track?.flush() } catch (e: Exception) {}
        try { track?.release() } catch (e: Exception) {}
        track = null
        onLevel(-90f, -90f)
    }

    /** 녹화가 시작되면 true: 직접 녹음을 멈추고 녹화 소리를 받아 씀 */
    fun setExternal(on: Boolean, mic: AudioDeviceInfo?) {
        external = on
        if (!running) return
        if (on) stopCapture() else startCapture(mic)
    }

    /** 녹화 쪽에서 넘겨주는 소리 */
    fun feed(buf: ShortArray, n: Int) {
        if (running && external) process(buf, n)
    }

    private fun buildTrack(): AudioTrack? = try {
        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(max(min, FRAME * 2 * 2))
            .build()
    } catch (e: Exception) { null }

    private fun startCapture(mic: AudioDeviceInfo?) {
        if (capturing) return
        val (r, k) = MicOpener.open(mic) ?: return
        rec = r
        capturing = true
        thread = Thread({
            val raw = ShortArray(FRAME)
            val buf = ShortArray(FRAME)
            try { r.startRecording() } catch (e: Exception) {}
            while (capturing) {
                val c = try { r.read(raw, 0, FRAME / k) } catch (e: Exception) { -1 }
                if (c > 0) process(buf, MicOpener.upsample(raw, c, k, buf))
            }
            try { r.stop() } catch (e: Exception) {}
            r.release()
        }, "monitor").apply { start() }
    }

    private fun stopCapture() {
        capturing = false
        try { thread?.join(500) } catch (e: Exception) {}
        thread = null
        rec = null
    }

    private fun process(buf: ShortArray, n: Int) {
        if (out.size < n) out = ShortArray(n)
        val g = if (external) 1f else gain
        var sum = 0.0
        var peak = 0
        for (i in 0 until n) {
            val s = (buf[i] * g).toInt().coerceIn(-32768, 32767)
            val a = abs(s)
            if (a > peak) peak = a
            sum += s.toDouble() * s
            out[i] = s.toShort()
        }
        try { track?.write(out, 0, n, AudioTrack.WRITE_NON_BLOCKING) } catch (e: Exception) {}
        // 레벨 (녹음되는 원래 크기 기준)
        val rms = sqrt(sum / max(n, 1))
        val rmsDb = if (rms > 0) (20 * log10(rms / 32768.0)).toFloat() else -90f
        val pDb = if (peak > 0) (20 * log10(peak / 32768.0)).toFloat() else -90f
        peakHold = if (pDb > peakHold) pDb else peakHold - 0.6f
        val now = SystemClock.elapsedRealtime()
        if (now - lastLevelMs >= 50) {
            lastLevelMs = now
            onLevel(rmsDb, peakHold)
        }
    }

    companion object {
        private const val RATE = 48000
        private const val FRAME = 480
    }
}
