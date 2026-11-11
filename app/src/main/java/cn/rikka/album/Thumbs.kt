package cn.rikka.album

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.view.View
import android.widget.ImageView
import java.io.File
import java.util.concurrent.Executors

/** 缩略图：相册里还在的直接问系统要；已删除的从备份文件解码并缓存到 files/thumbs */
object Thumbs {
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val mem = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun load(ctx: Context, item: Item, view: ImageView) {
        val key = item.sha
        view.tag = key
        mem.get(key)?.let { view.setImageBitmap(it); return }
        view.setImageDrawable(null)
        val app = ctx.applicationContext
        pool.execute {
            val bmp = try { make(app, item) } catch (_: Throwable) { null }
            if (bmp != null) mem.put(key, bmp)
            main.post { if (view.tag == key) view.setImageBitmap(bmp) }
        }
    }

    /** 给胶片条用：回调里拿到图 */
    fun get(ctx: Context, item: Item, cb: (Bitmap?) -> Unit) {
        mem.get(item.sha)?.let { cb(it); return }
        val app = ctx.applicationContext
        pool.execute {
            val bmp = try { make(app, item) } catch (_: Throwable) { null }
            if (bmp != null) mem.put(item.sha, bmp)
            main.post { cb(bmp) }
        }
    }

    private fun make(ctx: Context, item: Item): Bitmap? {
        // 1. 相册里还在：系统缩略图最快
        if (item.state == Item.ACTIVE && item.mediaId > 0) {
            val uri = android.content.ContentUris.withAppendedId(
                if (item.video) android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, item.mediaId
            )
            try { return ctx.contentResolver.loadThumbnail(uri, Size(320, 320), null) } catch (_: Throwable) { }
        }
        if (item.video) return null   // 视频从备份里取帧太费，直接显示占位
        // 2. 磁盘缓存
        val dir = File(ctx.filesDir, "thumbs").apply { mkdirs() }
        val f = File(dir, item.sha + ".jpg")
        if (f.exists()) BitmapFactory.decodeFile(f.absolutePath)?.let { return it }
        // 3. 从备份文件解码（root 读），两遍：先量尺寸再按比例缩小
        val blob = Db.get(ctx).blob(item.sha) ?: return null
        if (!blob.onPhone) return null
        val path = Store.abs(blob.rel)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        Root.openRead(path).use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth <= 0) return null
        var s = 1
        while (o.outWidth / (s * 2) >= 320 && o.outHeight / (s * 2) >= 320) s *= 2
        val bmp = Root.openRead(path).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s })
        } ?: return null
        try { f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) } } catch (_: Throwable) { }
        return bmp
    }
}

/**
 * 首页的胶片条：深色底片 + 上下两排齿孔，每一格是一张最近备份的缩略图。
 */
class FilmStripView(ctx: Context) : View(ctx) {
    private val ui = Ui(ctx)
    private val film = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ui.c(R.color.film) }
    private val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ui.c(R.color.film_hole) }
    private val empty = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(40, 255, 255, 255) }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ui.c(R.color.film_text); textSize = ui.dp(8.5f).toFloat()
        typeface = android.graphics.Typeface.MONOSPACE; letterSpacing = 0.12f
    }
    private val play = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val playBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 0, 0, 0) }
    private val img = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private var frames: List<Pair<Bitmap?, Boolean>> = emptyList()
    var label = "ALBUM BACKUP"

    fun setFrames(list: List<Pair<Bitmap?, Boolean>>) { frames = list; invalidate() }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        setMeasuredDimension(width, ui.dp(136))
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val r = ui.dp(14).toFloat()
        c.drawRoundRect(RectF(0f, 0f, w, h), r, r, film)

        // 齿孔
        // 上下各一条边：外侧是齿孔，内侧是胶片编号
        val band = ui.dp(24).toFloat()
        val hw = ui.dp(7).toFloat(); val hh = ui.dp(5).toFloat(); val gap = ui.dp(13).toFloat()
        val hy = ui.dp(8).toFloat()
        var x = ui.dp(10).toFloat()
        while (x + hw < w - ui.dp(6)) {
            c.drawRoundRect(RectF(x, hy - hh / 2, x + hw, hy + hh / 2), 2f, 2f, hole)
            c.drawRoundRect(RectF(x, h - hy - hh / 2, x + hw, h - hy + hh / 2), 2f, 2f, hole)
            x += gap
        }

        // 画格
        val top = band; val bottom = h - band
        val fh = bottom - top
        val fw = fh * 1.18f
        val pad = ui.dp(6).toFloat()
        val n = maxOf(1, ((w - pad) / (fw + pad)).toInt())
        val startX = (w - (n * fw + (n - 1) * pad)) / 2
        val fr = ui.dp(3).toFloat()
        for (i in 0 until n) {
            val left = startX + i * (fw + pad)
            val rect = RectF(left, top, left + fw, bottom)
            val f = frames.getOrNull(i)
            val bmp = f?.first
            if (bmp != null) {
                c.save()
                val p = Path().apply { addRoundRect(rect, fr, fr, Path.Direction.CW) }
                c.clipPath(p)
                // centerCrop
                val sc = maxOf(rect.width() / bmp.width, rect.height() / bmp.height)
                val dw = bmp.width * sc; val dh = bmp.height * sc
                val dst = RectF(rect.centerX() - dw / 2, rect.centerY() - dh / 2, rect.centerX() + dw / 2, rect.centerY() + dh / 2)
                c.drawBitmap(bmp, null, dst, img)
                c.restore()
            } else {
                c.drawRoundRect(rect, fr, fr, empty)
            }
            if (f?.second == true) {
                val cx = rect.right - ui.dp(12); val cy = rect.bottom - ui.dp(12)
                c.drawCircle(cx, cy, ui.dp(9).toFloat(), playBg)
                val s = ui.dp(4).toFloat()
                c.drawPath(Path().apply { moveTo(cx - s * 0.7f, cy - s); lineTo(cx + s, cy); lineTo(cx - s * 0.7f, cy + s); close() }, play)
            }
            // 底边的胶片编号
            c.drawText("${i + 1}A", left + ui.dp(2), h - ui.dp(14.5f), mark)
        }
        c.drawText(label, w - mark.measureText(label) - ui.dp(14), ui.dp(21).toFloat(), mark)
    }
}
