package cn.rikka.album

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * 导出到 U 盘 / 电脑（手机存储里的文件夹，再用数据线拷走）。
 * 目录：<所选位置>/相册备份导出/ ，里面放 .nomedia，插着 U 盘时相册也不会显示。
 * 增量导出：U 盘上已有且校验一致的跳过。可选导出后清空手机暂存（只清校验通过的）。
 */
object Exporter {
    const val DIR_NAME = "相册备份导出"

    class Report(var copied: Int = 0, var skipped: Int = 0, var failed: Int = 0, var cleared: Int = 0, var clearedBytes: Long = 0) {
        override fun toString() = "导出 $copied 个，已有跳过 $skipped 个" +
            (if (failed > 0) "，失败 $failed 个" else "") +
            (if (cleared > 0) "，已清空手机暂存 $cleared 个（${Fmt.size(clearedBytes)}）" else "")
    }

    fun export(ctx: Context, tree: Uri, clearAfter: Boolean, cancelled: () -> Boolean): Report {
        val db = Db.get(ctx)
        val prefs = Prefs(ctx)
        val real = Saf.realPath(tree, true) ?: throw java.io.IOException("识别不了这个位置，请选 U 盘或手机存储里的文件夹")
        val dir = if (real.trimEnd('/').endsWith("/$DIR_NAME")) real.trimEnd('/') else "$real/$DIR_NAME"
        Root.run("mkdir -p ${Root.q("$dir/files")} && touch ${Root.q("$dir/.nomedia")}")
        if (!Root.exec("touch ${Root.q("$dir/.write_test")} && rm ${Root.q("$dir/.write_test")}").ok)
            throw java.io.IOException("这个位置写不进去（U 盘只读或没插好）")

        // 合并 U 盘上已有的清单：手机上已经到期删掉的记录，在 U 盘上继续保留
        var oldItems = emptyList<Item>(); var oldBlobs = emptyList<Blob>()
        if (Root.exists("$dir/${Store.MANIFEST}")) {
            try {
                val p = Root.openRead("$dir/${Store.MANIFEST}").use { Manifest.read(it) }
                oldItems = p.first; oldBlobs = p.second
            } catch (t: Throwable) { Lg.e("读取 U 盘上的旧清单失败，将覆盖", t) }
        }

        val rep = Report()
        val now = System.currentTimeMillis()
        val list = db.blobs().filter { it.onPhone }
        val ok = HashSet<String>()
        for ((i, b) in list.withIndex()) {
            if (cancelled()) break
            Bus.update { it.copy(busy = true, task = "导出", detail = b.rel.substringAfterLast('/'), done = i, total = list.size) }
            val dst = "$dir/${b.rel}"
            try {
                val st = Root.stat(dst)
                val needHash = clearAfter || st == null || st.second != b.size
                if (st != null && st.second == b.size && (!needHash || Root.sha256(dst) == b.sha)) {
                    rep.skipped++; ok.add(b.sha)
                } else {
                    if (Store.freeBytesOf(dir) in 0 until b.size) throw java.io.IOException("U 盘空间不够")
                    Root.run("mkdir -p ${Root.q(dst.substringBeforeLast('/'))} && cp -p ${Root.q(Store.abs(b.rel))} ${Root.q("$dst.part")}")
                    if (Root.sha256("$dst.part") != b.sha) {
                        Root.exec("rm -f ${Root.q("$dst.part")}")
                        throw java.io.IOException("导出后校验不一致")
                    }
                    Root.run("mv -f ${Root.q("$dst.part")} ${Root.q(dst)}")
                    rep.copied++; ok.add(b.sha)
                }
            } catch (t: Throwable) {
                rep.failed++
                Lg.e("导出失败 ${b.rel}", t)
                if (t.message == "U 盘空间不够") break
            }
        }

        // 已经清过暂存（只在 U 盘上）的文件也算导出过
        db.tx {
            for (b in list) if (b.sha in ok) db.putBlob(b.copy(exportedAt = now))
        }

        if (clearAfter && !cancelled()) {
            for (b in list) {
                if (b.sha !in ok) continue
                Store.remove(b.rel)
                db.putBlob(b.copy(onPhone = false, exportedAt = now))
                rep.cleared++; rep.clearedBytes += b.size
            }
            Lg.i("导出后清空手机暂存：${rep.cleared} 个")
        }

        // 写 U 盘清单 = 当前记录 + U 盘上原有但手机上已不存在的记录
        val curItems = db.all()
        val curKeys = curItems.mapTo(HashSet()) { it.sha + "|" + it.path }
        val items = curItems + oldItems.filter { (it.sha + "|" + it.path) !in curKeys }
        val curBlobs = db.blobs().associateBy { it.sha }
        val oldShas = oldBlobs.mapTo(HashSet()) { it.sha }
        val blobs = (oldBlobs.associateBy { it.sha } + curBlobs).values
            .filter { it.sha in ok || it.sha in oldShas }
            .map { it.copy(onPhone = true) }   // 在导出目录里 = 文件在这里
        val tmp = File(ctx.cacheDir, "export_manifest.tsv")
        tmp.outputStream().use { Manifest.write(it, items, blobs) }
        Root.run("cp ${Root.q(tmp.absolutePath)} ${Root.q("$dir/${Store.MANIFEST}")}")
        tmp.delete()
        Store.saveManifest(ctx, db)

        if (rep.failed == 0 && !cancelled()) prefs.lastExport = now
        Lg.i("导出到 ${Media.userPath(dir)}：$rep")
        return rep
    }

    /** 是否该提醒导出：有没导出的文件，且距上次导出超过设定天数 */
    fun shouldRemind(ctx: Context): Boolean {
        val p = Prefs(ctx)
        if (p.exportRemindDays <= 0) return false
        val db = Db.get(ctx)
        if (db.countUnexported() == 0) return false
        val since = if (p.lastExport > 0) p.lastExport else db.firstBackedAt().takeIf { it > 0 } ?: return false
        return System.currentTimeMillis() - since > p.exportRemindDays * 86_400_000L
    }
}
