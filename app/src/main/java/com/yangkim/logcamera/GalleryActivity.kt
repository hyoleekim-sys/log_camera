package com.yangkim.logcamera

import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * 앱 안 앨범: DCIM/ProCamera 에 찍은 사진·영상을 모두 보여줌.
 * 썸네일 목록 + 크게 보기(옆으로 넘기기, 영상 재생).
 */
class GalleryActivity : Activity() {

    private class Item(val uri: Uri, val video: Boolean, val durationMs: Long)

    private val items = ArrayList<Item>()
    private val worker = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val thumbs = LruCache<String, Bitmap>(120)

    private lateinit var root: FrameLayout
    private lateinit var grid: GridView
    private lateinit var viewer: FrameLayout
    private lateinit var pager: androidx.viewpager2.widget.ViewPager2
    private lateinit var videoView: VideoView
    private lateinit var counter: TextView
    private var current = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        loadItems()
        val start = intent.getStringExtra(EXTRA_URI)
        if (items.isEmpty()) {
            counter.text = "아직 찍은 사진·영상이 없어요"
        } else {
            val idx = items.indexOfFirst { it.uri.toString() == start }
            show(if (idx >= 0) idx else 0)
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (viewer.visibility == View.VISIBLE) closeViewer() else super.onBackPressed()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ───────── 목록 읽기 ─────────

    private fun loadItems() {
        items.clear()
        val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val proj = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.DURATION
        )
        val sel = "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=?) AND " +
            "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?"
        val args = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
            CameraEngine.FOLDER + "%"
        )
        try {
            contentResolver.query(uri, proj, sel, args, "${MediaStore.Files.FileColumns.DATE_ADDED} DESC")?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val typeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                val durCol = c.getColumnIndex(MediaStore.Files.FileColumns.DURATION)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val video = c.getInt(typeCol) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    val dur = if (durCol >= 0) c.getLong(durCol) else 0L
                    items.add(Item(ContentUris.withAppendedId(base, id), video, dur))
                }
            }
        } catch (e: Exception) {}
        (grid.adapter as BaseAdapter).notifyDataSetChanged()
        if (::pager.isInitialized) pager.adapter?.notifyDataSetChanged()
    }

    // ───────── 화면 ─────────

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); fitsSystemWindows = true }

        // 썸네일 목록
        val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        header.addView(TextView(this).apply {
            text = "앨범 · ProCamera"
            setTextColor(0xFFFFE2C4.toInt()); textSize = 17f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(chip("닫기") { finish() })
        listBox.addView(header)
        grid = GridView(this).apply {
            numColumns = 3
            verticalSpacing = dp(2)
            horizontalSpacing = dp(2)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            adapter = ThumbAdapter()
            setOnItemClickListener { _, _, pos, _ -> show(pos) }
        }
        listBox.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(listBox, FrameLayout.LayoutParams(-1, -1))

        // 크게 보기
        viewer = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE; isClickable = true }
        // 손가락 따라 넘어가는 크게 보기
        pager = androidx.viewpager2.widget.ViewPager2(this).apply {
            adapter = PageAdapter()
            offscreenPageLimit = 1
            val t = androidx.viewpager2.widget.CompositePageTransformer()
            t.addTransformer(androidx.viewpager2.widget.MarginPageTransformer(dp(16)))
            t.addTransformer { page, pos ->
                // 넘어가는 장은 살짝 작아지고 흐려짐
                val f = 1f - 0.08f * abs(pos).coerceAtMost(1f)
                page.scaleX = f
                page.scaleY = f
                page.alpha = 1f - 0.35f * abs(pos).coerceAtMost(1f)
            }
            setPageTransformer(t)
            registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    current = position
                    stopVideo()
                    updateCounter()
                }
            })
        }
        viewer.addView(pager, FrameLayout.LayoutParams(-1, -1))
        videoView = VideoView(this).apply { visibility = View.GONE }
        viewer.addView(videoView, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x88000000.toInt())
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        top.addView(chip("목록") { closeViewer() })
        counter = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER
        }
        top.addView(counter, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(chip("공유") { shareCurrent() })
        top.addView(chip("갤러리") { openExternal() })
        viewer.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        root.addView(viewer, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    private fun chip(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE); textSize = 14f
        setBackgroundResource(R.drawable.chip_bg)
        setPadding(dp(14), dp(7), dp(14), dp(7))
        setOnClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); onClick() }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) }
    }

    // ───────── 크게 보기 ─────────

    private fun show(index: Int) {
        if (index < 0 || index >= items.size) return
        current = index
        if (viewer.visibility != View.VISIBLE) {
            // 목록에서 열 때: 살짝 커지며 나타남
            viewer.visibility = View.VISIBLE
            viewer.alpha = 0f
            viewer.scaleX = 0.94f
            viewer.scaleY = 0.94f
            viewer.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start()
        }
        stopVideo()
        pager.setCurrentItem(index, false)
        updateCounter()
    }

    private fun updateCounter() {
        val it = items.getOrNull(current) ?: return
        counter.text = "${current + 1} / ${items.size}" + if (it.video) "  ·  영상 ${fmtDur(it.durationMs)}" else ""
    }

    private fun closeViewer() {
        stopVideo()
        viewer.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setDuration(180).withEndAction {
            viewer.visibility = View.GONE
            viewer.alpha = 1f; viewer.scaleX = 1f; viewer.scaleY = 1f
        }.start()
        current = -1
    }

    private fun playCurrent() {
        val it = items.getOrNull(current) ?: return
        if (!it.video) return
        videoView.visibility = View.VISIBLE
        videoView.alpha = 0f
        videoView.animate().alpha(1f).setDuration(200).start()
        videoView.setMediaController(MediaController(this).apply { setAnchorView(videoView) })
        videoView.setVideoURI(it.uri)
        videoView.setOnCompletionListener { stopVideo() }
        videoView.start()
    }

    private fun stopVideo() {
        try { videoView.stopPlayback() } catch (e: Exception) {}
        videoView.visibility = View.GONE
    }

    private fun fmtDur(ms: Long): String {
        val s = ms / 1000
        return String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60)
    }

    private fun shareCurrent() {
        val it = items.getOrNull(current) ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = if (it.video) "video/mp4" else "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, it.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(Intent.createChooser(send, "공유")) } catch (e: Exception) {}
    }

    private fun openExternal() {
        val it = items.getOrNull(current) ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(it.uri, if (it.video) "video/mp4" else "image/jpeg")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Exception) {}
    }

    // ───────── 크게 보기 한 장 ─────────

    private class PageHolder(val box: FrameLayout, val img: ImageView, val badge: TextView) :
        androidx.recyclerview.widget.RecyclerView.ViewHolder(box) {
        var key = ""
    }

    private inner class PageAdapter : androidx.recyclerview.widget.RecyclerView.Adapter<PageHolder>() {
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val box = FrameLayout(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(-1, -1)
                setBackgroundColor(Color.BLACK)
            }
            val img = ImageView(parent.context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            box.addView(img, FrameLayout.LayoutParams(-1, -1))
            val badge = TextView(parent.context).apply {
                text = "▶  눌러서 재생"
                setTextColor(Color.WHITE); textSize = 16f
                setBackgroundResource(R.drawable.chip_bg)
                setPadding(dp(16), dp(10), dp(16), dp(10))
                setOnClickListener { playCurrent() }
            }
            box.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            return PageHolder(box, img, badge)
        }

        override fun onBindViewHolder(h: PageHolder, position: Int) {
            val item = items[position]
            h.badge.visibility = if (item.video) View.VISIBLE else View.GONE
            h.box.setOnClickListener { if (item.video) playCurrent() }
            val key = item.uri.toString()
            h.key = key
            h.img.setImageBitmap(null)
            val w = resources.displayMetrics.widthPixels
            val hh = resources.displayMetrics.heightPixels
            worker.execute {
                val bmp: Bitmap? = try {
                    if (item.video) {
                        contentResolver.loadThumbnail(item.uri, Size(w, hh), null)
                    } else {
                        ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, item.uri)) { dec, info, _ ->
                            val s = info.size
                            val scale = maxOf(1, maxOf(s.width, s.height) / maxOf(w, hh))
                            dec.setTargetSize(s.width / scale, s.height / scale)
                        }
                    }
                } catch (e: Exception) { null }
                main.post {
                    if (h.key == key) {
                        h.img.setImageBitmap(bmp)
                        h.img.alpha = 0f
                        h.img.animate().alpha(1f).setDuration(150).start()
                    }
                }
            }
        }
    }

    // ───────── 썸네일 칸 ─────────

    private inner class ThumbAdapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val box = (convertView as? FrameLayout) ?: FrameLayout(this@GalleryActivity).apply {
                val size = (parent.width / 3).coerceAtLeast(dp(100))
                layoutParams = android.widget.AbsListView.LayoutParams(-1, size)
                addView(ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP },
                    FrameLayout.LayoutParams(-1, -1))
                addView(TextView(context).apply {
                    setTextColor(Color.WHITE); textSize = 11f
                    setShadowLayer(4f, 0f, 0f, Color.BLACK)
                    setPadding(dp(4), dp(2), dp(4), dp(2))
                }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END))
            }
            val img = box.getChildAt(0) as ImageView
            val dur = box.getChildAt(1) as TextView
            val item = items[position]
            dur.text = if (item.video) "▶ ${fmtDur(item.durationMs)}" else ""
            val key = item.uri.toString()
            img.tag = key
            val cached = thumbs.get(key)
            if (cached != null) {
                img.setImageBitmap(cached)
            } else {
                img.setImageBitmap(null)
                worker.execute {
                    val b = try { contentResolver.loadThumbnail(item.uri, Size(240, 240), null) } catch (e: Exception) { null }
                    if (b != null) {
                        thumbs.put(key, b)
                        main.post { if (img.tag == key) img.setImageBitmap(b) }
                    }
                }
            }
            return box
        }
    }

    companion object {
        const val EXTRA_URI = "uri"
    }
}
