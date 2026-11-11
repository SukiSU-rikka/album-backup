package cn.rikka.album

import android.content.Context

/**
 * 一轮同步：
 *  1. 扫媒体库，新出现/被修改的照片视频（过了延迟期、已写完）复制进备份目录，校验通过才算成功
 *  2. 相册里已经没有的，备份进入倒计时（默认 30 天）
 *  3. 倒计时到期的，删掉备份（同内容还有别的记录在用时保留文件）
 *  4. 首次备份完成后核对媒体库数量，变了就报警并停下
 */
class SyncEngine(private val ctx: Context, private val cancelled: () -> Boolean) {
    private val db = Db.get(ctx)
    private val prefs = Prefs(ctx)

    class Result(
        val added: Int,
        val failed: Int,
        val waiting: Int,
        /** 最早一个等待中的文件可以备份的时间（毫秒），0 = 没有在等的 */
        val nextAt: Long,
        val marked: Int,
        val expired: Int,
        val spaceLow: Boolean
    )

    fun run(): Result {
        val halt = prefs.haltReason
        if (halt.isNotEmpty()) throw Halt(halt)
        Store.ensure()
        Store.recoverIndex(ctx, db)

        val now = System.currentTimeMillis()
        val firstRun = !prefs.firstDone
        val startSec = now / 1000
        val scan = Media.scan(ctx, true)
        val countBefore = if (firstRun) scan.count { it.added < startSec } else 0

        val active = db.active()
        val activeByPath = active.associateBy { it.path }
        val pendingByPath = db.pending().associateBy { it.path }
        val excluded = prefs.excluded
        val delayMs = prefs.delayMin * 60_000L
        val reserve = prefs.reserveGb * 1024L * 1024 * 1024
        val usedRels = HashSet(db.blobRels())

        // ---------- 1. 找出要备份的
        val todo = ArrayList<MediaEntry>()
        var waiting = 0
        var nextAt = 0L
        fun waitUntil(t: Long) { waiting++; nextAt = if (nextAt == 0L) t else minOf(nextAt, t) }

        for (e in scan) {
            if (e.video && !prefs.videos) continue
            if (excluded.any { e.rel.startsWith(it) }) continue
            if (!e.video && prefs.minKb > 0 && e.size < prefs.minKb * 1024L) continue
            if (!e.video && prefs.minEdge > 0 && e.width > 0 && maxOf(e.width, e.height) < prefs.minEdge) continue
            val old = activeByPath[e.path]
            if (old != null && old.size == e.size && old.mtime == e.mtime) {
                if (old.mediaId != e.id) db.update(old.copy(mediaId = e.id))
                continue
            }
            val ready = maxOf(e.added, e.mtime) * 1000 + delayMs
            if (ready > now) { waitUntil(ready); continue }
            todo.add(e)
        }

        // ---------- 2. 复制
        var added = 0; var failed = 0; var spaceLow = false; var sinceSave = 0
        todo.sortBy { it.added }
        for ((i, e) in todo.withIndex()) {
            if (cancelled()) break
            Bus.update { it.copy(busy = true, task = "同步", detail = e.name, done = i, total = todo.size, waiting = waiting) }
            if (Store.freeBytes() < reserve + e.size) {
                spaceLow = true
                Lg.w("手机剩余空间低于 ${prefs.reserveGb} GB，暂停备份（还有 ${todo.size - i} 个没备份）")
                break
            }
            try {
                val st = Root.stat(e.path) ?: continue   // 已经没了
                // 大小和媒体库对不上，或 15 秒内还在改：说明还在写（比如正在录像），下次再来
                if (st.second != e.size || now / 1000 - st.first < 15) {
                    waitUntil(now + 30_000); continue
                }
                val sha = Root.sha256(e.path)
                if (db.blob(sha) == null) {
                    val rel = Store.pickRel(e.name, if (e.taken > 0) e.taken else e.mtime * 1000, usedRels)
                    Store.copyIn(e.path, rel, sha)
                    usedRels.add(rel)
                    db.putBlob(Blob(sha, rel, st.second, true, System.currentTimeMillis(), 0))
                }
                val t = System.currentTimeMillis()
                val old = activeByPath[e.path]
                val revive = pendingByPath[e.path]?.takeIf { it.sha == sha }
                val fresh = Item(
                    mediaId = e.id, path = e.path, orig = e.path, name = e.name, mime = e.mime,
                    size = st.second, mtime = st.first, taken = e.taken, added = e.added, video = e.video,
                    sha = sha, backedAt = t
                )
                when {
                    old != null -> {
                        // 同一位置的文件被编辑过：换成新内容，旧内容没人用就清掉
                        db.update(fresh.copy(id = old.id, orig = old.orig))
                        if (old.sha != sha) gc(old.sha)
                        Lg.i("更新：${Media.userPath(e.path)}")
                    }
                    revive != null -> {
                        db.update(revive.copy(state = Item.ACTIVE, goneAt = 0, dueAt = 0, mediaId = e.id))
                        Lg.i("文件回来了，取消倒计时：${Media.userPath(e.path)}")
                    }
                    else -> {
                        db.insert(fresh)
                        Lg.i("已备份：${Media.userPath(e.path)}")
                    }
                }
                added++; sinceSave++
                if (sinceSave >= 100) { Store.saveManifest(ctx, db); sinceSave = 0 }
            } catch (t: Throwable) {
                failed++
                Lg.e("备份失败 ${Media.userPath(e.path)}", t)
            }
        }

        // ---------- 3. 相册里删掉的 → 倒计时
        var marked = 0
        if (!cancelled() && storageMounted()) {
            val present = scan.mapTo(HashSet()) { it.path }
            val keepMs = prefs.keepDays * 86_400_000L
            for (it in db.active()) {
                if (it.path in present) continue
                if (Root.exists(it.path)) continue   // 文件还在，只是媒体库暂时没收录
                db.update(it.copy(state = Item.PENDING, goneAt = now, dueAt = now + keepMs))
                marked++
                Lg.i("相册里已删除，备份保留 ${prefs.keepDays} 天：${Media.userPath(it.path)}")
            }
        }

        // ---------- 4. 到期的清掉
        var expired = 0
        for (it in db.pendingDue(System.currentTimeMillis())) {
            db.deleteItem(it.id)
            gc(it.sha)
            expired++
            Lg.i("倒计时到期，已删除备份：${it.name}")
        }

        if (added + marked + expired > 0 || sinceSave > 0) Store.saveManifest(ctx, db)

        // ---------- 5. 首次备份后的安全核对
        if (firstRun && !cancelled() && failed == 0 && !spaceLow) {
            Thread.sleep(3000)
            val countAfter = Media.scan(ctx, true).count { it.added < startSec }
            if (countAfter > countBefore) {
                val msg = "首次备份后相册里的照片视频变多了（之前 $countBefore，现在 $countAfter），已停止同步，请检查相册"
                prefs.haltReason = msg
                prefs.syncOn = false
                Lg.e(msg)
                throw Halt(msg)
            }
            if (waiting == 0) {
                prefs.firstDone = true
                Lg.i("首次备份完成并核对通过：相册数量前后一致（$countBefore）")
            }
        }

        prefs.lastSync = System.currentTimeMillis()
        return Result(added, failed, waiting, nextAt, marked, expired, spaceLow)
    }

    private fun storageMounted() =
        Root.exists(Media.PRIMARY + "Android") || Root.exists(Media.PRIMARY + "DCIM")

    /** 没有记录再引用这个内容时，删掉备份文件 */
    private fun gc(sha: String) {
        if (db.refs(sha) > 0) return
        val b = db.blob(sha) ?: return
        if (b.onPhone) Store.remove(b.rel)
        db.deleteBlob(sha)
    }

    companion object {
        /** 待删除列表里点「全部删除」：只删仍在倒计时的，期间被放回的不动 */
        fun purgeAll(ctx: Context, ids: List<Long>): String {
            val db = Db.get(ctx)
            var n = 0; var bytes = 0L
            for ((i, id) in ids.withIndex()) {
                if (Worker.cancel) break
                Bus.update { it.copy(busy = true, task = "删除", detail = "", done = i, total = ids.size) }
                val it = db.byId(id) ?: continue
                if (it.state != Item.PENDING) continue
                db.deleteItem(id)
                if (db.refs(it.sha) == 0) {
                    db.blob(it.sha)?.let { b -> if (b.onPhone) { Store.remove(b.rel); bytes += b.size }; db.deleteBlob(b.sha) }
                }
                n++
            }
            Store.saveManifest(ctx, db)
            Lg.i("手动全部删除待删除备份：$n 个，释放 ${Fmt.size(bytes)}")
            return "已删除 $n 个，释放 ${Fmt.size(bytes)}"
        }

        /** 待删除列表里点「立即删除」 */
        fun purge(ctx: Context, id: Long) {
            val db = Db.get(ctx)
            val it = db.byId(id) ?: return
            db.deleteItem(id)
            if (db.refs(it.sha) == 0) {
                db.blob(it.sha)?.let { b -> if (b.onPhone) Store.remove(b.rel); db.deleteBlob(b.sha) }
            }
            Store.saveManifest(ctx, db)
            Lg.i("手动立即删除备份：${it.name}")
        }
    }
}
