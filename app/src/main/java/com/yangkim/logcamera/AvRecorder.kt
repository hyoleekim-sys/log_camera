package com.yangkim.logcamera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

/**
 * 영상 + 소리 녹화 (직접 인코딩).
 * - 영상: 화면 처리(보정) 결과를 H.264로
 * - 소리: 마이크 1개 또는 2개(폰 + 블루투스)를 섞어서 AAC로
 * - 모든 시간값을 같은 시계(System.nanoTime)로 맞춰 길이·싱크 오류 방지
 */
class AvRecorder(private val ctx: Context) {
    var onWarning: ((String) -> Unit)? = null

    @Volatile var isRecording = false
        private set

    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private val muxLock = Object()
    private var videoTrack = -1
    private var audioTrack = -1
    private var wantAudio = false
    @Volatile private var muxerStarted = false
    private var wroteVideo = false
    private var startUs = 0L
    private var lastVideoUs = -1L
    private var lastAudioUs = -1L

    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    private val records = ArrayList<AudioRecord>()

    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null

    /**
     * @param mics 녹음할 마이크들 (null 항목 = 기본 마이크). 비어 있으면 소리 없이.
     */
    fun start(width: Int, height: Int, fps: Int, bitrate: Int, orientationHint: Int, mics: List<AudioDeviceInfo?>): Surface {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "VID_${CameraEngine.stamp()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, CameraEngine.FOLDER)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val u = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("저장 위치를 만들 수 없어요")
        uri = u
        val fd = resolver.openFileDescriptor(u, "rw") ?: throw IOException("파일을 열 수 없어요")
        pfd = fd

        try {
            val m = MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            m.setOrientationHint(orientationHint)
            muxer = m

            // 영상 인코더
            val vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            vf.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            vf.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            vf.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            vf.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            val vc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            vc.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = vc.createInputSurface()
            vc.start()
            videoCodec = vc

            // 소리
            wantAudio = setupAudio(mics)

            videoTrack = -1
            audioTrack = -1
            muxerStarted = false
            wroteVideo = false
            lastVideoUs = -1L
            lastAudioUs = -1L
            startUs = System.nanoTime() / 1000
            isRecording = true

            videoThread = Thread({ videoLoop() }, "rec-video").apply { start() }
            if (wantAudio) audioThread = Thread({ audioLoop() }, "rec-audio").apply { start() }
            return surface
        } catch (e: Exception) {
            cleanup(false)
            throw e
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupAudio(mics: List<AudioDeviceInfo?>): Boolean {
        records.clear()
        if (mics.isEmpty()) return false
        for (dev in mics) {
            try {
                val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val src = if (dev != null && dev.type != AudioDeviceInfo.TYPE_BUILTIN_MIC)
                    MediaRecorder.AudioSource.MIC else MediaRecorder.AudioSource.CAMCORDER
                val r = AudioRecord.Builder()
                    .setAudioSource(src)
                    .setAudioFormat(
                        AudioFormat.Builder().setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
                    )
                    .setBufferSizeInBytes(max(min * 4, FRAME * 2 * 8))
                    .build()
                if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); continue }
                if (dev != null) r.setPreferredDevice(dev)
                records.add(r)
            } catch (e: Exception) {
                Log.w(TAG, "mic", e)
            }
        }
        if (records.isEmpty()) {
            onWarning?.invoke("마이크를 열 수 없어 소리 없이 녹화돼요")
            return false
        }
        val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1)
        af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        af.setInteger(MediaFormat.KEY_BIT_RATE, 160_000)
        af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME * 2 * 2)
        val ac = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        ac.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        ac.start()
        audioCodec = ac
        return true
    }

    // ───────── 먹서(파일) ─────────

    private fun addTrack(format: MediaFormat, video: Boolean) {
        synchronized(muxLock) {
            val m = muxer ?: return
            if (video) videoTrack = m.addTrack(format) else audioTrack = m.addTrack(format)
            val ready = videoTrack >= 0 && (!wantAudio || audioTrack >= 0)
            if (ready && !muxerStarted) {
                m.start()
                muxerStarted = true
                // 시작은 키프레임부터
                try {
                    videoCodec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                } catch (e: Exception) {}
            }
        }
    }

    private fun write(video: Boolean, buf: java.nio.ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(muxLock) {
            if (!muxerStarted) return
            val m = muxer ?: return
            if (info.size <= 0) return
            if (video) {
                if (!wroteVideo && (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) == 0) return
                wroteVideo = true
            }
            var pts = info.presentationTimeUs - startUs
            if (pts < 0) return
            // 시간이 거꾸로 가지 않게
            if (video) { if (pts <= lastVideoUs) pts = lastVideoUs + 1; lastVideoUs = pts }
            else { if (pts <= lastAudioUs) pts = lastAudioUs + 1; lastAudioUs = pts }
            info.presentationTimeUs = pts
            buf.position(info.offset)
            buf.limit(info.offset + info.size)
            m.writeSampleData(if (video) videoTrack else audioTrack, buf, info)
        }
    }

    // ───────── 영상 ─────────

    private fun videoLoop() {
        val vc = videoCodec ?: return
        val info = MediaCodec.BufferInfo()
        var eosSent = false
        while (true) {
            if (!isRecording && !eosSent) {
                try { vc.signalEndOfInputStream() } catch (e: Exception) {}
                eosSent = true
            }
            val idx = try { vc.dequeueOutputBuffer(info, 10_000) } catch (e: Exception) { break }
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> { if (eosSent && ++idleAfterEos > 100) break }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> addTrack(vc.outputFormat, true)
                idx >= 0 -> {
                    val buf = vc.getOutputBuffer(idx)
                    if (buf != null && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) write(true, buf, info)
                    vc.releaseOutputBuffer(idx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
        }
    }

    private var idleAfterEos = 0

    // ───────── 소리 ─────────

    private fun audioLoop() {
        val ac = audioCodec ?: return
        val bufs = records.map { ShortArray(FRAME) }
        val mix = ShortArray(FRAME)
        val bytes = java.nio.ByteBuffer.allocate(FRAME * 2).order(java.nio.ByteOrder.nativeOrder())
        for (r in records) try { r.startRecording() } catch (e: Exception) {}

        // 두 마이크가 같은 장치로 잡혔으면 하나만 사용 (동시 녹음이 막힌 경우)
        if (records.size > 1) {
            val a = records[0].routedDevice?.id
            val b = records[1].routedDevice?.id
            if (a != null && a == b) {
                onWarning?.invoke("이 폰은 두 마이크 동시 녹음을 막아둬서 한 마이크만 녹음돼요")
                val extra = records.removeAt(1)
                try { extra.stop() } catch (e: Exception) {}
                extra.release()
            }
        }

        var firstUs = -1L
        var samples = 0L
        val info = MediaCodec.BufferInfo()
        var eos = false
        while (true) {
            if (!eos) {
                val n = readMixed(bufs, mix)
                if (n > 0) {
                    if (firstUs < 0) {
                        // 읽은 순간에서 버퍼 길이만큼 앞이 실제 소리 시작
                        firstUs = System.nanoTime() / 1000 - n * 1_000_000L / RATE
                    }
                    val pts = firstUs + samples * 1_000_000L / RATE
                    samples += n
                    val inIdx = try { ac.dequeueInputBuffer(10_000) } catch (e: Exception) { -1 }
                    if (inIdx >= 0) {
                        val ib = ac.getInputBuffer(inIdx)
                        if (ib != null) {
                            ib.clear()
                            bytes.clear()
                            for (i in 0 until n) bytes.putShort(mix[i])
                            bytes.flip()
                            ib.put(bytes)
                            ac.queueInputBuffer(inIdx, 0, n * 2, pts, 0)
                        }
                    }
                }
                if (!isRecording) {
                    val inIdx = try { ac.dequeueInputBuffer(10_000) } catch (e: Exception) { -1 }
                    if (inIdx >= 0) {
                        ac.queueInputBuffer(inIdx, 0, 0, firstUs.coerceAtLeast(0) + samples * 1_000_000L / RATE,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eos = true
                    }
                }
            }
            // 인코딩된 소리 꺼내기
            var drained = false
            while (true) {
                val idx = try { ac.dequeueOutputBuffer(info, if (eos) 10_000 else 0) } catch (e: Exception) { drained = true; break }
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) break
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { addTrack(ac.outputFormat, false); continue }
                if (idx >= 0) {
                    val buf = ac.getOutputBuffer(idx)
                    if (buf != null && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) write(false, buf, info)
                    ac.releaseOutputBuffer(idx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) { drained = true; break }
                }
            }
            if (drained) break
            if (eos && ++audioIdle > 200) break
        }
        for (r in records) {
            try { r.stop() } catch (e: Exception) {}
            r.release()
        }
        records.clear()
    }

    private var audioIdle = 0

    /** 마이크들을 읽어서 섞기. 하나면 그대로. */
    private fun readMixed(bufs: List<ShortArray>, out: ShortArray): Int {
        if (records.isEmpty()) return 0
        var n = FRAME
        val counts = IntArray(records.size)
        for ((i, r) in records.withIndex()) {
            counts[i] = try { r.read(bufs[i], 0, FRAME) } catch (e: Exception) { 0 }
            if (counts[i] > 0) n = min(n, counts[i])
        }
        val valid = records.indices.filter { counts[it] > 0 }
        if (valid.isEmpty()) return 0
        if (valid.size == 1) {
            System.arraycopy(bufs[valid[0]], 0, out, 0, counts[valid[0]])
            return counts[valid[0]]
        }
        for (j in 0 until n) {
            var s = 0
            for (i in valid) s += bufs[i][j]
            s = (s * 0.75f).toInt()
            out[j] = s.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return n
    }

    // ───────── 정지 ─────────

    /** 화면 처리가 녹화 서피스에 더 그리지 않게 한 다음 호출. */
    fun stop(): Uri? {
        if (!isRecording) return null
        isRecording = false
        idleAfterEos = 0
        audioIdle = 0
        try { audioThread?.join(3000) } catch (e: Exception) {}
        try { videoThread?.join(3000) } catch (e: Exception) {}
        audioThread = null
        videoThread = null
        val ok = muxerStarted && wroteVideo
        return cleanup(ok)
    }

    private fun cleanup(ok0: Boolean): Uri? {
        var ok = ok0
        synchronized(muxLock) {
            try { if (muxerStarted) muxer?.stop() } catch (e: Exception) { ok = false }
            try { muxer?.release() } catch (e: Exception) {}
            muxer = null
            muxerStarted = false
        }
        try { videoCodec?.stop() } catch (e: Exception) {}
        try { videoCodec?.release() } catch (e: Exception) {}
        videoCodec = null
        try { audioCodec?.stop() } catch (e: Exception) {}
        try { audioCodec?.release() } catch (e: Exception) {}
        audioCodec = null
        for (r in records) { try { r.release() } catch (e: Exception) {} }
        records.clear()
        isRecording = false
        try { pfd?.close() } catch (e: Exception) {}
        pfd = null
        val u = uri ?: return null
        uri = null
        val resolver = ctx.contentResolver
        return if (ok) {
            resolver.update(u, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            u
        } else {
            try { resolver.delete(u, null, null) } catch (e: Exception) {}
            null
        }
    }

    companion object {
        private const val TAG = "AvRecorder"
        private const val RATE = 48000
        private const val FRAME = 1024
    }
}
