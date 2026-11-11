package cn.rikka.album

import android.content.Context

/**
 * 整理：把散在乱七八糟文件夹里的照片视频「移动」到干净的文件夹。
 * - 是移动不是复制，相册里不会多出一份
 * - 只动已经备份并校验过的文件（没备份的先备份）
 * - 移动后核对修改时间，不一致就写回去，相册排序不变
 * - 文件夹里一个文件都不剩了才删掉旧文件夹
 */
object Organizer {

    enum class Cat(val label: String) {
        CAMERA("相机"), SCREENSHOT("截图"), SCREENREC("录屏"), WECHAT("微信"), QQ("QQ"), OTHER("其他"), KEEP("不动")
    }

    class Folder(val rel: String, val count: Int, val bytes: Long, var cat: Cat, val clean: Boolean)

    /** 不会被删除的上级目录 */
    private val protectedDirs = setOf("", "DCIM/", "Pictures/", "Movies/", "Download/", "Documents/", "Music/", "Android/", "Tencent/")

    private fun guess(rel: String): Cat {
        val l = rel.lowercase()
        return when {
            Regex("screen.?rec|录屏|录像屏幕").containsMatchIn(l) -> Cat.SCREENREC
            Regex("screenshot|截屏|截图|screencap").containsMatchIn(l) -> Cat.SCREENSHOT
            Regex("weixin|wechat|micromsg|tencent/mm").containsMatchIn(l) -> Cat.WECHAT
            Regex("(^|/)(qq|qq_images|qqfile_recv|mobileqq|qqbrowser)(/|$)|tencent/qq").containsMatchIn(l) -> Cat.QQ
            Regex("^dcim/(camera|100andro|100media|\\d{3}[a-z]{5})/").containsMatchIn(l) -> Cat.CAMERA
            else -> Cat.KEEP
        }
    }

    /** 每一类归到哪：截图/录屏沿用手机上已经在用的那个目录 */
    fun targets(scan: List<MediaEntry>): Map<Cat, String> {
        val byRel = scan.groupingBy { it.rel }.eachCount()
        fun pick(vararg c: String) = c.maxByOrNull { byRel[it] ?: 0 }!!
        return mapOf(
            Cat.CAMERA to "DCIM/Camera/",
            Cat.SCREENSHOT to pick("DCIM/Screenshots/", "Pictures/Screenshots/"),
            Cat.SCREENREC to pick("DCIM/Screenrecorder/", "Movies/Screenrecorder/", "DCIM/ScreenRecorder/", "Movies/ScreenRecords/"),
            Cat.WECHAT to "Pictures/WeiXin/",
            Cat.QQ to "Pictures/QQ/",
            Cat.OTHER to "Pictures/其他/"
        )
    }

    fun folders(ctx: Context): List<Folder> {
        val scan = Media.scan(ctx, true)
        val tg = targets(scan).values.toSet()
        return scan.groupBy { it.rel }.map { (rel, list) ->
            val clean = rel in tg
            Folder(rel, list.size, list.sumOf { it.size }, if (clean) Cat.KEEP else guess(rel), clean)
        }.sortedWith(compareBy<Folder> { it.clean }.thenByDescending { it.count })
    }

    /** 自动整理只处理微信、QQ 这两类 */
    fun autoPlan(ctx: Context): Map<String, Cat> =
        folders(ctx).filter { !it.clean && (it.cat == Cat.WECHAT || it.cat == Cat.QQ) }.associate { it.rel to it.cat }

    class Report(var moved: Int = 0, var skipped: Int = 0, var failed: Int = 0, var timeFixed: Int = 0, var dirsRemoved: Int = 0) {
        override fun toString() = "移动 $moved 个，跳过 $skipped 个（还没备份或正在变化），失败 $failed 个" +
            (if (timeFixed > 0) "，修正时间 $timeFixed 个" else "") + "，清理空文件夹 $dirsRemoved 个"
    }

    fun tidy(ctx: Context, plan: Map<String, Cat>, cancelled: () -> Boolean): Report {
        val rep = Report()
        val db = Db.get(ctx)
        val scan = Media.scan(ctx, true)
        val tg = targets(scan)
        val byPath = db.active().associateBy { it.path }
        val work = scan.filter { e -> plan[e.rel]?.let { it != Cat.KEEP } == true }
        val rescan = ArrayList<String>()
        val touchedDirs = HashSet<String>()

        for ((i, e) in work.withIndex()) {
            if (cancelled()) break
            Bus.update { it.copy(busy = true, task = "整理", detail = e.name, done = i, total = work.size) }
            val cat = plan[e.rel]!!
            val target = Media.PRIMARY + tg[cat]!!
            if (e.path.substringBeforeLast('/') + "/" == target) continue
            val item = byPath[e.path]
            // 只有已备份、内容没变过的才动
            if (item == null || item.size != e.size || item.mtime != e.mtime || db.blob(item.sha) == null) {
                rep.skipped++; continue
            }
            try {
                val dst = freeName(target, e.name)
                Root.run("mkdir -p ${Root.q(target)} && mv -n ${Root.q(e.path)} ${Root.q(dst)}")
                val st = Root.stat(dst) ?: throw java.io.IOException("移动后找不到文件")
                if (st.second != item.size) throw java.io.IOException("移动后大小不一致")
                if (st.first != item.mtime) { Root.setMtime(dst, item.mtime); rep.timeFixed++ }
                db.update(item.copy(path = dst))
                rescan.add(e.path); rescan.add(dst)
                touchedDirs.add(e.path.substringBeforeLast('/'))
                rep.moved++
                Lg.i("整理：${Media.userPath(e.path)} → ${Media.userPath(dst)}")
            } catch (t: Throwable) {
                rep.failed++
                Lg.e("整理失败 ${Media.userPath(e.path)}", t)
            }
        }
        Media.rescan(ctx, rescan)
        for (d in touchedDirs) rep.dirsRemoved += removeIfEmpty(d, tg.values.toSet())
        if (rep.moved > 0) Store.saveManifest(ctx, db)
        Lg.i("整理完成：$rep")
        return rep
    }

    private fun freeName(dir: String, name: String): String {
        var p = dir + name
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var n = 1
        while (Root.exists(p)) { p = "$dir${base}_$n$ext"; n++ }
        return p
    }

    /** 文件夹（含子文件夹）里一个文件都没有了才删，再往上逐级检查 */
    private fun removeIfEmpty(abs: String, keep: Set<String>): Int {
        var removed = 0
        var dir = abs
        while (dir.startsWith(Media.PRIMARY)) {
            val rel = dir.removePrefix(Media.PRIMARY).trimEnd('/') + "/"
            if (rel in protectedDirs || rel in keep || rel == "/") break
            val left = Root.exec("find ${Root.q(dir)} -type f | head -n 1").out.trim()
            if (left.isNotEmpty()) break
            if (!Root.exec("rm -r ${Root.q(dir)}").ok) break
            Lg.i("已删除空文件夹：$rel")
            removed++
            dir = dir.substringBeforeLast('/')
        }
        return removed
    }
}
