package com.yangkim.logcamera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaRecorder
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.Surface
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

/** 1080p H.264 녹화. LUT가 입혀진 화면을 그대로 저장. */
class VideoRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null

    fun start(width: Int, height: Int, orientationHint: Int, withAudio: Boolean): Surface {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "LOGCAM_${stamp()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/LogCamera")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val u = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("저장 위치를 만들 수 없어요")
        uri = u
        val fd = resolver.openFileDescriptor(u, "rw") ?: throw IOException("파일을 열 수 없어요")
        pfd = fd
        val r = MediaRecorder(context)
        try {
            if (withAudio) r.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setOutputFile(fd.fileDescriptor)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            r.setVideoSize(width, height)
            r.setVideoFrameRate(30)
            r.setVideoEncodingBitRate(16_000_000)
            if (withAudio) {
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioSamplingRate(48000)
                r.setAudioEncodingBitRate(192_000)
                r.setAudioChannels(2)
            }
            r.setOrientationHint(orientationHint)
            r.prepare()
            val surface = r.surface
            r.start()
            recorder = r
            return surface
        } catch (e: Exception) {
            r.release()
            cleanup(false)
            throw e
        }
    }

    /** 녹화 종료. 성공하면 저장된 Uri. */
    fun stop(): Uri? {
        val r = recorder ?: return null
        recorder = null
        var ok = true
        try { r.stop() } catch (e: RuntimeException) { ok = false }
        r.release()
        return cleanup(ok)
    }

    private fun cleanup(ok: Boolean): Uri? {
        try { pfd?.close() } catch (e: Exception) {}
        pfd = null
        val u = uri ?: return null
        uri = null
        val resolver = context.contentResolver
        return if (ok) {
            val v = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            resolver.update(u, v, null, null)
            u
        } else {
            try { resolver.delete(u, null, null) } catch (e: Exception) {}
            null
        }
    }
}

object PhotoSaver {
    fun save(context: Context, bitmap: Bitmap): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "LOGCAM_${stamp()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LogCamera")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            val v = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(uri, v, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }
}
