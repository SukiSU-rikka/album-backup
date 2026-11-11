package cn.rikka.album

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.security.MessageDigest

/**
 * 还原。三种情况：
 *  1. 待删除列表里点「放回」：单个文件放回原位置
 *  2. 本机整体还原：从 /data/photo_backup 放回（全部或某一年）
 *  3. 从 U 盘/文件夹还原：换新手机、刷机后用；新手机没 root 也能用（可靠性差一点）
 * 都是先比对校验值，手机上已经有的跳过；放回后写回修改时间，刷新媒体库，最后核对数量和时间。
 */
object Restorer {

    class Report(
        var restored: Int = 0, var skipped: Int = 0, var failed: Int = 0, var missing: Int = 0,
        var timeOk: Int = 0, var timeBad: Int = 0, var notIndexed: Int = 0
    ) {
        override fun toString() = buildString {
            append("放回 $restored 个，已存在跳过 $skipped 个")
            if (failed > 0) append("，失败 $failed 个")
            if (missing > 0) append("，$missing 个不在这份备份里（已导出到 U 盘并清了手机暂存，请从 U 盘还原）")
            append("\n核对：时间一致 $timeOk 个")
            if (timeBad > 0) append("，时间不一致 $timeBad 个")
            if (notIndexed > 0) append("，媒体库暂未收录 $notIndexed 个")
        }
    }

    // ---------------------------------------------------------------- 1. 单个放回

    fun restoreOne(ctx: Context, id: Long): String {
        val db = Db.get(ctx)
        val item = db.byId(id) ?: throw java.io.IOException("记录不存在")
        val blob = db.blob(item.sha) ?: throw java.io.IOException("备份文件不存在")
        if (!blob.onPhone) throw java.io.IOException("这个文件已导出到 U 盘并清掉了手机暂存，请到「工具 → 从 U 盘还原」")
        val src = RootSource(Store.DIR, "本机备份")
        val rep = Report()
        val placed = put(ctx, src, blob.rel, item, rep, useRoot = true)
        verify(ctx, listOf(Pair(placed ?: item.path, item)), rep, useRoot = true)
        db.update(item.copy(path = placed ?: item.path, state = Item.ACTIVE, goneAt = 0, dueAt = 0))
        Store.saveManifest(ctx, db)
        Lg.i("放回相册：${Media.userPath(placed ?: item.path)}")
        return if (rep.failed > 0) "放回失败，详见日志" else "已放回：${Media.userPath(placed ?: item.path)}"
    }

    // ---------------------------------------------------------------- 2. 本机整体还原

    /** 有备份的年份（还原时选时段用） */
    fun years(ctx: Context): List<Int> {
        val cal = java.util.Calendar.getInstance()
        return Db.get(ctx).active().map { cal.timeInMillis = it.sortTime; cal.get(java.util.Calendar.YEAR) }
            .distinct().sortedDescending()
    }

    fun restoreLocal(ctx: Context, year: Int?, cancelled: () -> Boolean): Report {
        val db = Db.get(ctx)
        Store.recoverIndex(ctx, db)
        val cal = java.util.Calendar.getInstance()
        val items = db.active().filter {
            year == null || run { cal.timeInMillis = it.sortTime; cal.get(java.util.Calendar.YEAR) == year }
        }
        val blobs = db.blobs().associateBy { it.sha }
        return restoreItems(ctx, RootSource(Store.DIR, "本机备份"), items, blobs, true, cancelled)
    }

    // ---------------------------------------------------------------- 3. 从 U 盘/文件夹还原

    fun restoreExternal(ctx: Context, tree: Uri, cancelled: () -> Boolean): Report {
        val root = Root.available()
        val src: Source = if (root) {
            val real = Saf.realPath(tree, true) ?: throw java.io.IOException("识别不了这个位置，请选 U 盘或手机存储里的文件夹")
            val dir = when {
                Root.exists("$real/${Store.MANIFEST}") -> real
                Root.exists("$real/${Exporter.DIR_NAME}/${Store.MANIFEST}") -> "$real/${Exporter.DIR_NAME}"
                else -> throw java.io.IOException("这个文件夹里没有找到备份清单（${Store.MANIFEST}）")
            }
            RootSource(dir, "U 盘/文件夹")
        } else {
            Lg.w("没有 root：改用系统接口还原，修改时间写回的可靠性不如 root")
            SafSource(ctx, tree)
        }
        val (items, blobList) = src.manifest().use { Manifest.read(it) }
        val blobs = blobList.associateBy { it.sha }
        // 导出清单里待删除的也带上：在旧手机上还没到期的，用户可能还想要。默认只还原相册里还在的
        val want = items.filter { it.state == Item.ACTIVE }
        return restoreItems(ctx, src, want, blobs, root, cancelled)
    }

    // ---------------------------------------------------------------- 公共流程

    private fun restoreItems(
        ctx: Context, src: Source, items: List<Item>, blobs: Map<String, Blob>, useRoot: Boolean, cancelled: () -> Boolean
    ): Report {
        val rep = Report()
        val placed = ArrayList<Pair<String, Item>>()
        val sorted = items.sortedBy { it.sortTime }
        for ((i, it) in sorted.withIndex()) {
            if (cancelled()) break
            Bus.update { s -> s.copy(busy = true, task = "还原", detail = it.name, done = i, total = sorted.size) }
            val b = blobs[it.sha]
            if (b == null || (src is RootSource && src.real(b.rel)?.startsWith(Store.DIR) == true && !b.onPhone) || !src.exists(b.rel)) {
                rep.missing++; continue
            }
            val p = put(ctx, src, b.rel, it, rep, useRoot)
            if (p != null) placed.add(Pair(p, it))
        }
        if (useRoot) Media.rescan(ctx, placed.map { it.first })
        verify(ctx, placed, rep, useRoot)
        Lg.i("还原完成（${src.label}）：$rep")
        return rep
    }

    /** 放回一个文件，返回最终路径；已存在相同内容返回 null */
    private fun put(ctx: Context, src: Source, rel: String, it: Item, rep: Report, useRoot: Boolean): String? {
        return try {
            if (useRoot) putRoot(src, rel, it, rep) else putSystem(ctx, src, rel, it, rep)
        } catch (t: Throwable) {
            rep.failed++
            Lg.e("还原失败 ${it.name}", t)
            null
        }
    }

    private fun putRoot(src: Source, rel: String, it: Item, rep: Report): String? {
        var target = it.path
        val st = Root.stat(target)
        if (st != null) {
            if (st.second == it.size && Root.sha256(target) == it.sha) { rep.skipped++; return null }
            target = freeName(target)
        }
        Root.run("mkdir -p ${Root.q(target.substringBeforeLast('/'))}")
        val real = src.real(rel)
        if (real != null) {
            Root.run("cp ${Root.q(real)} ${Root.q(target)}")
        } else {
            src.open(rel).use { ins -> Root.openWrite(target).use { ins.copyTo(it, 1 shl 16) } }
        }
        if (Root.sha256(target) != it.sha) {
            Root.exec("rm -f ${Root.q(target)}")
            throw java.io.IOException("放回后校验不一致")
        }
        Root.setMtime(target, it.mtime)
        rep.restored++
        return target
    }

    /** 没 root：通过媒体库写入，再用文件接口写回修改时间 */
    private fun putSystem(ctx: Context, src: Source, rel: String, it: Item, rep: Report): String? {
        Media.byPath(ctx, it.path)?.let { e -> if (e.size == it.size) { rep.skipped++; return null } }
        val collection = if (it.video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        var relDir = Media.relOf(it.path)
        val allowed = if (it.video) listOf("DCIM/", "Movies/") else listOf("DCIM/", "Pictures/")
        if (allowed.none { relDir.startsWith(it) }) relDir = (if (it.video) "Movies/" else "Pictures/") + "还原/" + relDir
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, it.name)
            put(MediaStore.MediaColumns.MIME_TYPE, it.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relDir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (it.taken > 0) put(MediaStore.MediaColumns.DATE_TAKEN, it.taken)
        }
        val uri = ctx.contentResolver.insert(collection, cv) ?: throw java.io.IOException("媒体库拒绝写入")
        val md = MessageDigest.getInstance("SHA-256")
        try {
            ctx.contentResolver.openOutputStream(uri)!!.use { out ->
                src.open(rel).use { ins ->
                    val buf = ByteArray(1 shl 16)
                    while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n); out.write(buf, 0, n) }
                }
            }
            val got = md.digest().joinToString("") { b -> "%02x".format(b) }
            if (got != it.sha) throw java.io.IOException("放回后校验不一致")
        } catch (t: Throwable) {
            ctx.contentResolver.delete(uri, null, null); throw t
        }
        ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        val path = pathOf(ctx, uri) ?: throw java.io.IOException("写入后找不到文件路径")
        File(path).setLastModified(it.mtime * 1000)
        Media.rescan(ctx, listOf(path))
        rep.restored++
        return path
    }

    @Suppress("DEPRECATION")
    private fun pathOf(ctx: Context, uri: Uri): String? =
        ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    /** 核对：媒体库收录了，且修改时间和备份时一致（照片拍摄时间在文件内部，跟着文件走） */
    private fun verify(ctx: Context, placed: List<Pair<String, Item>>, rep: Report, useRoot: Boolean) {
        val fix = ArrayList<String>()
        for ((path, it) in placed) {
            val e = Media.byPath(ctx, path)
            if (e == null) { rep.notIndexed++; continue }
            if (kotlin.math.abs(e.mtime - it.mtime) <= 2) rep.timeOk++
            else if (useRoot) { Root.exec("touch -m -d @${it.mtime} ${Root.q(path)}"); fix.add(path); rep.timeOk++ }
            else rep.timeBad++
        }
        if (fix.isNotEmpty()) Media.rescan(ctx, fix)
    }

    private fun freeName(path: String): String {
        val dir = path.substringBeforeLast('/') + "/"
        val name = path.substringAfterLast('/')
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var n = 1
        var p = "$dir${base}_$n$ext"
        while (Root.exists(p)) { n++; p = "$dir${base}_$n$ext" }
        return p
    }
}
