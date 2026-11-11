package cn.rikka.album

import android.content.Context
import android.os.StatFs
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份目录 /data/photo_backup。
 * 这里只有 root 能读，系统相册和媒体库扫不到，所以相册里永远不会出现两份。
 *
 * 结构：
 *   /data/photo_backup/files/2024/05/IMG_xxx.jpg   备份文件（按年/月，不压缩，修改时间原样保留）
 *   /data/photo_backup/index.tsv                   清单（每个文件的拍摄时间、修改时间、原路径、校验值）
 */
object Store {
    const val DIR = "/data/photo_backup"
    const val MANIFEST = "index.tsv"

    fun abs(rel: String) = "$DIR/$rel"

    fun ensure() {
        Root.run("mkdir -p ${Root.q("$DIR/files")} && chmod 700 ${Root.q(DIR)} && touch ${Root.q("$DIR/.nomedia")}")
    }

    /** /data 剩余空间（字节） */
    fun freeBytes(): Long = try { StatFs("/data").availableBytes } catch (_: Throwable) { Long.MAX_VALUE }

    /** 任意目录所在分区的剩余空间（root 下用 df），读不到返回 -1 */
    fun freeBytesOf(dir: String): Long {
        val line = Root.exec("df -k ${Root.q(dir)} | tail -n 1").out.trim()
        val f = line.split(Regex("\\s+"))
        return if (f.size >= 4) (f[3].toLongOrNull() ?: -1) * 1024 else -1
    }

    private val ym = SimpleDateFormat("yyyy/MM", Locale.US)

    /** 给新文件挑一个不冲突的相对路径：files/年/月/原文件名（重名加 _1、_2） */
    fun pickRel(name: String, sortTime: Long, taken: Set<String>): String {
        val t = if (sortTime > 0) sortTime else System.currentTimeMillis()
        val dir = "files/" + ym.format(Date(t))
        val safe = name.replace(Regex("[\\\\/:*?\"<>|\\t\\n]"), "_").ifBlank { "unnamed" }
        val base = safe.substringBeforeLast('.', safe)
        val ext = if (safe.contains('.')) "." + safe.substringAfterLast('.') else ""
        var rel = "$dir/$safe"
        var n = 1
        while (rel in taken || Root.exists(abs(rel))) {
            rel = "$dir/${base}_$n$ext"; n++
        }
        return rel
    }

    /**
     * 复制一个文件进备份目录：先写 .part，校验值对上才改成正式名。
     * cp -p 会原样保留修改时间。返回实际校验值。
     */
    fun copyIn(src: String, rel: String, expectSha: String) {
        val dst = abs(rel)
        val part = "$dst.part"
        Root.run("mkdir -p ${Root.q(dst.substringBeforeLast('/'))} && cp -p ${Root.q(src)} ${Root.q(part)}")
        val got = Root.sha256(part)
        if (got != expectSha) {
            Root.exec("rm -f ${Root.q(part)}")
            throw java.io.IOException("复制后校验不一致（文件可能正在被修改），稍后重试")
        }
        Root.run("mv -f ${Root.q(part)} ${Root.q(dst)}")
    }

    /** 删掉一个备份文件，并顺手删掉变空的 年/月 目录 */
    fun remove(rel: String) {
        val p = abs(rel)
        Root.exec("rm -f ${Root.q(p)}; rmdir ${Root.q(p.substringBeforeLast('/'))} 2>/dev/null; " +
            "rmdir ${Root.q(p.substringBeforeLast('/').substringBeforeLast('/'))} 2>/dev/null; true")
    }

    /** 把清单写进备份目录（先写 app 缓存，再由 root 覆盖过去） */
    fun saveManifest(ctx: Context, db: Db) {
        val tmp = File(ctx.cacheDir, MANIFEST)
        tmp.outputStream().use { Manifest.write(it, db.all(), db.blobs()) }
        val dst = abs(MANIFEST)
        Root.run("cp ${Root.q(tmp.absolutePath)} ${Root.q("$dst.tmp")} && mv -f ${Root.q("$dst.tmp")} ${Root.q(dst)} && chmod 600 ${Root.q(dst)}")
        tmp.delete()
    }

    /** 本机数据库丢了（清数据、重装）但备份目录还在时，从清单把索引找回来 */
    fun recoverIndex(ctx: Context, db: Db): Int {
        if (db.countBlobs() > 0 || !Root.exists(abs(MANIFEST))) return 0
        val (items, blobs) = Root.openRead(abs(MANIFEST)).use { Manifest.read(it) }
        db.tx {
            blobs.forEach { db.putBlob(it) }
            items.forEach { db.insert(it.copy(id = 0)) }
        }
        Lg.i("从备份目录的清单恢复了索引：${items.size} 条记录，${blobs.size} 个文件")
        return items.size
    }
}

/** 清单格式（制表符分隔的文本），备份目录和导出目录共用 */
object Manifest {
    private const val HEAD = "#album-backup-manifest\t2"

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")
    private fun unesc(s: String): String {
        val sb = StringBuilder(); var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) { 't' -> sb.append('\t'); 'n' -> sb.append('\n'); else -> sb.append(s[i + 1]) }
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    fun write(out: OutputStream, items: List<Item>, blobs: List<Blob>) {
        val w = out.bufferedWriter(Charsets.UTF_8)
        w.write(HEAD); w.write("\n")
        for (b in blobs) {
            w.write(listOf("B", b.sha, esc(b.rel), b.size, if (b.onPhone) 1 else 0, b.verifiedAt, b.exportedAt).joinToString("\t"))
            w.write("\n")
        }
        for (it in items) {
            w.write(listOf(
                "I", it.mediaId, esc(it.path), esc(it.orig), esc(it.name), esc(it.mime), it.size, it.mtime,
                it.taken, it.added, if (it.video) 1 else 0, it.sha, it.state, it.goneAt, it.dueAt, it.backedAt
            ).joinToString("\t"))
            w.write("\n")
        }
        w.flush()
    }

    fun read(ins: InputStream): Pair<List<Item>, List<Blob>> {
        val r = BufferedReader(InputStreamReader(ins, Charsets.UTF_8))
        val head = r.readLine() ?: throw java.io.IOException("清单是空的")
        if (!head.startsWith("#album-backup-manifest")) throw java.io.IOException("不是本 App 的备份清单")
        val items = ArrayList<Item>(); val blobs = ArrayList<Blob>()
        while (true) {
            val line = r.readLine() ?: break
            val f = line.split('\t')
            try {
                when (f[0]) {
                    "B" -> blobs.add(Blob(f[1], unesc(f[2]), f[3].toLong(), f[4] == "1", f[5].toLong(), f[6].toLong()))
                    "I" -> items.add(Item(
                        mediaId = f[1].toLong(), path = unesc(f[2]), orig = unesc(f[3]), name = unesc(f[4]),
                        mime = unesc(f[5]), size = f[6].toLong(), mtime = f[7].toLong(), taken = f[8].toLong(),
                        added = f[9].toLong(), video = f[10] == "1", sha = f[11], state = f[12].toInt(),
                        goneAt = f[13].toLong(), dueAt = f[14].toLong(), backedAt = f[15].toLong()
                    ))
                }
            } catch (_: Throwable) { /* 坏行跳过 */ }
        }
        return Pair(items, blobs)
    }
}
