package com.yangkim.logcamera

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 앱이 꺼질 때 오류 내용을 저장했다가 다음에 켤 때 보여줌 (복사해서 전달용). */
object CrashLog {
    private fun file(ctx: Context) = File(ctx.filesDir, "crash.txt")

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            save(app, e, t.name)
            prev?.uncaughtException(t, e)
        }
    }

    fun save(ctx: Context, e: Throwable, thread: String = Thread.currentThread().name) {
        try {
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            val head = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) +
                " · ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE} · thread=$thread\n"
            file(ctx).appendText(head + sw.toString().take(6000) + "\n\n")
        } catch (x: Throwable) {}
    }

    /** 저장된 오류가 있으면 보여주고 복사할 수 있게 */
    fun showIfAny(a: Activity) {
        val f = file(a)
        if (!f.exists()) return
        val text = try { f.readText().takeLast(8000) } catch (e: Exception) { "" }
        f.delete()
        if (text.isBlank()) return
        val tv = TextView(a).apply {
            this.text = text
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(32, 16, 32, 16)
        }
        AlertDialog.Builder(a)
            .setTitle("지난번에 앱이 꺼졌어요")
            .setMessage(null)
            .setView(ScrollView(a).apply { addView(tv) })
            .setPositiveButton("오류 내용 복사") { _, _ ->
                val cm = a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("crash", text))
            }
            .setNegativeButton("닫기", null)
            .show()
    }
}
