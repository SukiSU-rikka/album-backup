package cn.rikka.album

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Layout { DATE, SOURCE, FLAT }

class CancelledException : Exception("已停止")

/** 手机媒体库里的一个照片/视频 */
class Item(
    val uri: Uri,
    val name: String,
    val mime: String,
    val size: Long,
    val taken: Long,      // 毫秒，0 = 没有
    val modified: Long,   // 秒
    val added: Long,      // 秒
    val rel: String,      // 原目录，如 DCIM/Camera/
    val video: Boolean
) {
    val key: String get() = "$name|$size"
}

class Engine(
    private val ctx: Context,
    private val cancelled: () -> Boolean,
    private val report: (Stats) -> Unit
) {
    private var st = Stats()
    private var lastPush = 0L
    private val errors = ArrayList<String>()
    private val yf = SimpleDateFormat("yyyy", Locale.US)
    private val mf = SimpleDateFormat("MM", Locale.US)

    private fun push(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (force || now - lastPush > 300) {
            lastPush = now
            report(st)
        }
    }

    private fun addError(name: String, t: Throwable) {
        if (errors.size < 20) errors.add("$name：${t.message ?: t.javaClass.simpleName}")
    }

    // ---------------------------------------------------------------- 扫描

    private fun scan(): List<Item> {
        val list = ArrayList<Item>()
        query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, list)
        query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, list)
        list.sortWith(compareBy<Item> { it.added }.thenBy { it.name })
        return list
    }

    private fun query(base: Uri, video: Boolean, out: MutableList<Item>) {
        val proj = arrayOf(
            MediaColumns._ID, MediaColumns.DISPLAY_NAME, MediaColumns.MIME_TYPE, MediaColumns.SIZE,
            MediaColumns.DATE_TAKEN, MediaColumns.DATE_MODIFIED, MediaColumns.DATE_ADDED,
            MediaColumns.RELATIVE_PATH
        )
        ctx.contentResolver.query(base, proj, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (name.startsWith(".")) continue
                val rel = c.getString(7) ?: ""
                // 跳过 .thumbnails、.trash 这类隐藏目录
                if (rel.split("/").any { it.startsWith(".") }) continue
                val mime = c.getString(2) ?: ""
                out.add(
                    Item(
                        uri = ContentUris.withAppendedId(base, c.getLong(0)),
                        name = name,
                        mime = mime,
                        size = c.getLong(3),
                        taken = if (c.isNull(4)) 0L else c.getLong(4),
                        modified = if (c.isNull(5)) 0L else c.getLong(5),
                        added = if (c.isNull(6)) 0L else c.getLong(6),
                        rel = rel,
                        video = video
                    )
                )
            }
        }
    }

    // ---------------------------------------------------------------- 备份

    private fun clean(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "其他" }

    private fun segs(item: Item, layout: Layout): List<String> = when (layout) {
        Layout.DATE -> {
            val t = if (item.taken > 0) item.taken else item.modified * 1000
            val d = Date(t)
            listOf(yf.format(d), mf.format(d))
        }
        Layout.SOURCE -> {
            val last = item.rel.split("/").filter { it.isNotBlank() }.lastOrNull() ?: "其他"
            listOf(clean(last))
        }
        Layout.FLAT -> emptyList()
    }

    private fun entryOf(item: Item, path: String) = Entry(
        path, item.name, item.mime, item.size, item.taken, item.modified, item.added, item.rel, item.video
    )

    fun backup(tree: Uri, layout: Layout): Stats {
        val saf = Saf(ctx, tree)
        val entries = ArrayList<Entry>()
        val keys = HashSet<String>()
        val paths = HashSet<String>()

        val old = saf.rootChildren()[Index.FILE]
        if (old != null && !old.isDir) {
            try {
                saf.openIn(old.docId).use { Index.read(it) }.forEach {
                    entries.add(it); keys.add(it.key); paths.add(it.path)
                }
            } catch (t: Throwable) {
                throw IOException("读取已有的 ${Index.FILE} 失败，为避免重复备份已停止（${t.message}）")
            }
        }
        var indexId: String? = old?.docId

        fun flush() {
            val id = indexId ?: saf.createFile(saf.rootId, Index.FILE, -1L).also { indexId = it }
            saf.openOut(id).use { Index.write(it, entries) }
        }

        val items = scan()
        st = Stats(mode = "备份", total = items.size, bytesTotal = items.sumOf { it.size })
        push(true)

        val useOrig = ctx.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        var sinceFlush = 0

        for (item in items) {
            if (cancelled()) break
            st = st.copy(current = item.name)
            push()
            if (item.key in keys) {
                st = st.copy(done = st.done + 1, skipped = st.skipped + 1, bytesDone = st.bytesDone + item.size)
                continue
            }
            try {
                val (entry, copied) = backupOne(saf, item, layout, paths, useOrig)
                entries.add(entry); keys.add(entry.key); paths.add(entry.path)
                st = if (copied) st.copy(done = st.done + 1, copied = st.copied + 1)
                else st.copy(done = st.done + 1, skipped = st.skipped + 1, bytesDone = st.bytesDone + item.size)
                sinceFlush++
                if (sinceFlush >= 300) {
                    sinceFlush = 0
                    try { flush() } catch (_: Throwable) { }
                }
            } catch (c: CancelledException) {
                break
            } catch (t: Throwable) {
                addError(item.name, t)
                st = st.copy(done = st.done + 1, failed = st.failed + 1)
            }
        }

        var flushErr: String? = null
        try { flush() } catch (t: Throwable) { flushErr = t.message ?: t.javaClass.simpleName }

        val head = if (cancelled()) "已停止（下次点备份会接着来，已备份的会自动跳过）" else "备份完成"
        var msg = "$head\n新增 ${st.copied}，跳过 ${st.skipped}，失败 ${st.failed}（共 ${st.total}）"
        if (errors.isNotEmpty()) msg += "\n失败原因：" + errors.take(3).joinToString("；")
        if (flushErr != null) msg += "\n⚠ 索引文件保存失败：$flushErr，请重新备份一次"
        if (!useOrig) msg += "\n提示：没有授权“媒体位置”，照片里的拍摄地点可能没备份到"
        st = st.copy(finished = true, message = msg, current = "")
        push(true)
        return st
    }

    /** 返回 (索引记录, 是否真的复制了)。同名同大小且没被索引占用的文件视为上次中断时已复制好的，直接收编 */
    private fun backupOne(
        saf: Saf, item: Item, layout: Layout, paths: Set<String>, useOrig: Boolean
    ): Pair<Entry, Boolean> {
        val segs = segs(item, layout)
        val dirId = saf.ensureDir(segs)
        val ch = saf.children(dirId)
        val dot = item.name.lastIndexOf('.')
        val stem = if (dot > 0) item.name.substring(0, dot) else item.name
        val ext = if (dot > 0) item.name.substring(dot) else ""
        var n = 0
        var cand = item.name
        while (true) {
            val path = (segs + cand).joinToString("/")
            val ex = ch[cand]
            if (ex == null) {
                val docId = saf.createFile(dirId, cand, item.size)
                try {
                    copyFromPhone(item, saf.openOut(docId), useOrig)
                } catch (t: Throwable) {
                    saf.delete(dirId, cand, docId)
                    throw t
                }
                return Pair(entryOf(item, path), true)
            }
            if (!ex.isDir && ex.size == item.size && path !in paths) {
                return Pair(entryOf(item, path), false)
            }
            n++
            cand = "${stem}_$n$ext"
        }
    }

    private fun copyFromPhone(item: Item, out: OutputStream, useOrig: Boolean) {
        var src = item.uri
        if (useOrig) {
            try { src = MediaStore.setRequireOriginal(item.uri) } catch (_: Throwable) { }
        }
        val ins = ctx.contentResolver.openInputStream(src) ?: throw IOException("无法读取")
        val n = out.use { o -> ins.use { i -> pump(i, o) } }
        if (item.size > 0 && n < item.size) throw IOException("复制不完整")
    }

    private fun pump(ins: InputStream, out: OutputStream): Long {
        val buf = ByteArray(256 * 1024)
        var total = 0L
        while (true) {
            if (cancelled()) throw CancelledException()
            val r = ins.read(buf)
            if (r < 0) break
            out.write(buf, 0, r)
            total += r
            st = st.copy(bytesDone = st.bytesDone + r)
            push()
        }
        out.flush()
        return total
    }

    // ---------------------------------------------------------------- 恢复

    fun restore(tree: Uri, originalPaths: Boolean): Stats {
        val saf = Saf(ctx, tree)
        val idx = saf.rootChildren()[Index.FILE]
        if (idx == null || idx.isDir) {
            throw IOException("所选文件夹里没有 ${Index.FILE}，请选备份时用的那个文件夹")
        }
        val entries = saf.openIn(idx.docId).use { Index.read(it) }.sortedBy { it.dateAdded }

        // 手机上已有的（按 文件名+大小）不重复恢复
        val have = HashSet<String>()
        for (base in listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)) {
            ctx.contentResolver.query(base, arrayOf(MediaColumns.DISPLAY_NAME, MediaColumns.SIZE), null, null, null)
                ?.use { c ->
                    while (c.moveToNext()) have.add("${c.getString(0)}|${c.getLong(1)}")
                }
        }

        st = Stats(mode = "恢复", total = entries.size, bytesTotal = entries.sumOf { it.size })
        push(true)

        for (e in entries) {
            if (cancelled()) break
            st = st.copy(current = e.name)
            push()
            if (e.key in have) {
                st = st.copy(done = st.done + 1, skipped = st.skipped + 1, bytesDone = st.bytesDone + e.size)
                continue
            }
            try {
                restoreOne(saf, e, originalPaths)
                have.add(e.key)
                st = st.copy(done = st.done + 1, copied = st.copied + 1)
            } catch (c: CancelledException) {
                break
            } catch (t: Throwable) {
                addError(e.name, t)
                st = st.copy(done = st.done + 1, failed = st.failed + 1)
            }
        }

        val head = if (cancelled()) "已停止（再点一次恢复会接着来，已恢复的会自动跳过）" else "恢复完成"
        var msg = "$head\n恢复 ${st.copied}，跳过 ${st.skipped}，失败 ${st.failed}（共 ${st.total}）"
        if (errors.isNotEmpty()) msg += "\n失败原因：" + errors.take(3).joinToString("；")
        if (st.copied > 0) msg += "\n恢复的文件在 DCIM/Restored（或原路径），相册里按时间排序即可看到"
        st = st.copy(finished = true, message = msg, current = "")
        push(true)
        return st
    }

    private fun guessMime(e: Entry): String {
        val prefix = if (e.video) "video/" else "image/"
        if (e.mime.startsWith(prefix)) return e.mime
        val ext = e.name.substringAfterLast('.', "").lowercase(Locale.US)
        val g = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        if (g != null && g.startsWith(prefix)) return g
        return if (e.video) "video/mp4" else "image/jpeg"
    }

    @Suppress("DEPRECATION")
    private fun restoreOne(saf: Saf, e: Entry, originalPaths: Boolean) {
        val child = saf.resolve(e.path) ?: throw IOException("备份里找不到 ${e.path}")
        val cr = ctx.contentResolver
        val coll = if (e.video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val mime = guessMime(e)

        fun values(relPath: String) = ContentValues().apply {
            put(MediaColumns.DISPLAY_NAME, e.name)
            put(MediaColumns.MIME_TYPE, mime)
            put(MediaColumns.RELATIVE_PATH, relPath)
            put(MediaColumns.DATE_MODIFIED, e.dateModified)
            if (e.dateTaken > 0) put(MediaColumns.DATE_TAKEN, e.dateTaken)
            put(MediaColumns.IS_PENDING, 1)
        }

        var dst: Uri? = null
        if (originalPaths && e.album.isNotBlank()) {
            // 原路径不一定被系统允许（比如 Tencent/...），不行就退回到 DCIM/Restored
            dst = try { cr.insert(coll, values(e.album)) } catch (_: Exception) { null }
        }
        if (dst == null) dst = cr.insert(coll, values("DCIM/Restored/"))
        val target = dst ?: throw IOException("无法写入系统相册")

        try {
            val out = cr.openOutputStream(target) ?: throw IOException("无法写入系统相册")
            val n = out.use { o -> saf.openIn(child.docId).use { i -> pump(i, o) } }
            if (e.size > 0 && n < e.size) throw IOException("复制不完整")
        } catch (t: Throwable) {
            try { cr.delete(target, null, null) } catch (_: Throwable) { }
            throw t
        }

        // 发布，然后尽力把时间改回原样（各步独立，失败不影响文件本身）
        cr.update(target, ContentValues().apply { put(MediaColumns.IS_PENDING, 0) }, null, null)
        try {
            cr.query(target, arrayOf(MediaColumns.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val p = c.getString(0)
                    if (p != null) File(p).setLastModified(e.dateModified * 1000)
                }
            }
        } catch (_: Throwable) { }
        try {
            cr.update(target, ContentValues().apply {
                put(MediaColumns.DATE_MODIFIED, e.dateModified)
                if (e.dateTaken > 0) put(MediaColumns.DATE_TAKEN, e.dateTaken)
            }, null, null)
        } catch (_: Throwable) { }
        try {
            cr.update(target, ContentValues().apply { put(MediaColumns.DATE_ADDED, e.dateAdded) }, null, null)
        } catch (_: Throwable) { }
    }
}
