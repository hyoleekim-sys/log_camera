package com.yangkim.logcamera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaRecorder
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.Surface
import java.io.ByteArrayInputStream
import java.io.IOException

/** 1080p H.264 녹화. 보정이 입혀진 화면을 그대로 저장. */
class VideoRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null

    val isRecording: Boolean get() = recorder != null

    fun start(width: Int, height: Int, fps: Int, bitrate: Int, orientationHint: Int, withAudio: Boolean): Surface {
        val resolver = context.contentResolver
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
        val r = MediaRecorder(context)
        try {
            if (withAudio) r.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setOutputFile(fd.fileDescriptor)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            r.setVideoSize(width, height)
            r.setVideoFrameRate(fps)
            r.setVideoEncodingBitRate(bitrate)
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
            finish(false)
            throw e
        }
    }

    fun stop(): Uri? {
        val r = recorder ?: return null
        recorder = null
        var ok = true
        try { r.stop() } catch (e: RuntimeException) { ok = false }
        r.release()
        return finish(ok)
    }

    private fun finish(ok: Boolean): Uri? {
        try { pfd?.close() } catch (e: Exception) {}
        pfd = null
        val u = uri ?: return null
        uri = null
        val resolver = context.contentResolver
        return if (ok) {
            resolver.update(u, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            u
        } else {
            try { resolver.delete(u, null, null) } catch (e: Exception) {}
            null
        }
    }
}

object PhotoSaver {
    /** JPEG에 보정을 입혀 저장. 보정이 없으면 원본 그대로. */
    /** 원본 카메라 JPEG에서 옮겨 담을 촬영 정보 */
    private val EXIF_TAGS = listOf(
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_SHUTTER_SPEED_VALUE, ExifInterface.TAG_ISO_SPEED_RATINGS,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_EXPOSURE_PROGRAM, ExifInterface.TAG_EXPOSURE_MODE,
        ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_METERING_MODE, ExifInterface.TAG_FLASH,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, "LensMake", "LensModel",
        ExifInterface.TAG_BRIGHTNESS_VALUE, ExifInterface.TAG_DIGITAL_ZOOM_RATIO, ExifInterface.TAG_SCENE_CAPTURE_TYPE
    )

    /** 촬영 당시 앱 설정 (사진 정보에 기록) */
    class ShotInfo(val description: String, val focal35: Int)

    fun save(context: Context, jpeg: ByteArray, lut: FloatArray?, info: ShotInfo? = null): Uri? {
        val original = try { ExifInterface(ByteArrayInputStream(jpeg)) } catch (e: Exception) { null }
        val data: ByteArray = if (lut == null) jpeg else {
            val rot = try {
                when (ExifInterface(ByteArrayInputStream(jpeg)).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } catch (e: Exception) { 0 }
            val opts = BitmapFactory.Options().apply { inMutable = true }
            var bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) ?: return null
            GradeEngine.apply(bmp, lut)
            if (rot != 0) {
                val m = Matrix().apply { postRotate(rot.toFloat()) }
                val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (r !== bmp) bmp.recycle()
                bmp = r
            }
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 98, out)
            bmp.recycle()
            out.toByteArray()
        }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_${CameraEngine.stamp()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, CameraEngine.FOLDER)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { it.write(data) }
            // 촬영 정보 기록
            try {
                resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                    val ex = ExifInterface(pfd.fileDescriptor)
                    if (lut != null && original != null) {
                        for (t in EXIF_TAGS) original.getAttribute(t)?.let { ex.setAttribute(t, it) }
                        ex.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                    }
                    ex.setAttribute(ExifInterface.TAG_SOFTWARE, "ProCamera")
                    if (info != null) {
                        ex.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, info.description)
                        if (info.focal35 > 0 && ex.getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM) == null) {
                            ex.setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, info.focal35.toString())
                        }
                    }
                    ex.saveAttributes()
                }
            } catch (e: Exception) {}
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }
}
