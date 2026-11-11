package cn.rikka.album

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 日志：写到 files/log.txt，超过 1 MB 自动滚动。日志页读这里 */
object Lg {
    private var file: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun init(ctx: Context) {
        if (file == null) file = File(ctx.filesDir, "log.txt")
    }

    @Synchronized
    private fun write(level: Char, msg: String) {
        android.util.Log.println(if (level == 'E') android.util.Log.ERROR else android.util.Log.INFO, "AlbumBackup", msg)
        val f = file ?: return
        try {
            if (f.length() > 1_000_000) {
                val old = File(f.parentFile, "log.old.txt")
                old.delete(); f.renameTo(old)
            }
            f.appendText("${fmt.format(Date())} $level $msg\n")
        } catch (_: Throwable) { }
    }

    fun i(msg: String) = write('I', msg)
    fun w(msg: String) = write('W', msg)
    fun e(msg: String, t: Throwable? = null) =
        write('E', if (t == null) msg else "$msg：${t.message ?: t.javaClass.simpleName}")

    /** 最近 n 行，新的在前 */
    @Synchronized
    fun tail(n: Int = 800): List<String> {
        val f = file ?: return emptyList()
        val lines = ArrayList<String>()
        val old = File(f.parentFile, "log.old.txt")
        try { if (old.exists()) lines.addAll(old.readLines()) } catch (_: Throwable) { }
        try { if (f.exists()) lines.addAll(f.readLines()) } catch (_: Throwable) { }
        return lines.takeLast(n).reversed()
    }

    @Synchronized
    fun clear() {
        val f = file ?: return
        f.delete(); File(f.parentFile, "log.old.txt").delete()
    }
}
