package cn.rikka.album

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 唯一的后台工作线程：同步、整理、还原、导出、校验全部排队在这里一个一个做，
 * 互相不会撞车（比如整理移动文件时不会被同步误判成删除）。
 */
object Worker {
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "album-worker").apply { priority = Thread.MIN_PRIORITY + 1 } }
    private val syncQueued = AtomicBoolean(false)
    @Volatile var cancel = false
    @Volatile private var lastTidy = 0L
    @Volatile private var setupDone = false

    /** 同步完成后告诉服务下次什么时候再来（等延迟期的文件） */
    @Volatile var onNext: ((Long) -> Unit)? = null

    /** 后台干活时持有唤醒锁，息屏也不会停在半路（最长 2 小时自动释放） */
    private fun <T> awake(ctx: Context, f: () -> T): T {
        val pm = ctx.getSystemService(android.os.PowerManager::class.java)
        val wl = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "AlbumBackup:work")
        wl.acquire(2 * 3600_000L)
        try { return f() } finally { if (wl.isHeld) wl.release() }
    }

    fun sync(ctx: Context) {
        if (!syncQueued.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        exec.execute {
            syncQueued.set(false)
            if (!Prefs(app).syncOn) return@execute
            cancel = false
            awake(app) { doSync(app) }
        }
    }

    /** 用户在界面上点的任务；返回的字符串显示为结果 */
    fun job(ctx: Context, task: String, block: () -> String) {
        val app = ctx.applicationContext
        SyncService.start(app)
        exec.execute {
            cancel = false
            Bus.update { it.copy(busy = true, task = task, detail = "准备中…", done = 0, total = 0) }
            val note = awake(app) { try {
                if (!Root.available()) throw Halt("没有 root 权限，请在 SukiSU / KernelSU 管理器里给「相册备份」授权")
                setup(app)
                block()
            } catch (h: Halt) {
                h.message ?: ""
            } catch (t: Throwable) {
                Lg.e("$task 出错", t)
                "$task 出错：${t.message ?: t.javaClass.simpleName}"
            } }
            Bus.update { it.copy(busy = false, task = "", detail = "", note = "$task：$note", root = Root.available()) }
        }
    }

    private fun doSync(ctx: Context) {
        val root = Root.available()
        if (!root) {
            Bus.update { it.copy(root = false, note = "没有 root 权限，同步未运行") }
            return
        }
        setup(ctx)
        Bus.update { it.copy(busy = true, task = "同步", detail = "扫描相册…", done = 0, total = 0, root = true) }
        var note: String
        try {
            val r = SyncEngine(ctx) { cancel }.run()
            note = buildString {
                append(if (r.added > 0) "新备份 ${r.added} 个" else "没有新文件")
                if (r.waiting > 0) append("，${r.waiting} 个等延迟期过后备份")
                if (r.marked > 0) append("，${r.marked} 个进入删除倒计时")
                if (r.expired > 0) append("，${r.expired} 个到期已删")
                if (r.failed > 0) append("，失败 ${r.failed} 个（见日志）")
            }
            if (r.spaceLow) {
                note = "手机空间不足，备份已暂停"
                Notify.alert(ctx, Notify.ID_SPACE, "相册备份已暂停", "手机剩余空间低于 ${Prefs(ctx).reserveGb} GB，清理空间或导出到 U 盘后会自动继续")
            } else Notify.cancel(ctx, Notify.ID_SPACE)
            onNext?.invoke(r.nextAt)
            Bus.update { it.copy(waiting = r.waiting) }
        } catch (h: Halt) {
            note = h.message ?: "已停止"
            Notify.alert(ctx, Notify.ID_HALT, "相册备份已停止", note)
        } catch (t: Throwable) {
            Lg.e("同步出错", t)
            note = "同步出错：${t.message ?: t.javaClass.simpleName}"
        }
        Bus.update { it.copy(busy = false, task = "", detail = "", note = note) }

        afterSync(ctx)
    }

    private fun afterSync(ctx: Context) {
        val p = Prefs(ctx)
        // 自动整理微信/QQ 的乱文件夹，每小时最多一次
        if (p.autoTidy && p.haltReason.isEmpty() && System.currentTimeMillis() - lastTidy > 3_600_000) {
            lastTidy = System.currentTimeMillis()
            try {
                val plan = Organizer.autoPlan(ctx)
                if (plan.isNotEmpty()) {
                    val rep = Organizer.tidy(ctx, plan) { cancel }
                    Bus.update { it.copy(busy = false, task = "", detail = "", note = "自动整理：$rep") }
                }
            } catch (t: Throwable) { Lg.e("自动整理出错", t) }
        }
        // 长期没导出提醒，每天最多一次
        if (Exporter.shouldRemind(ctx) && System.currentTimeMillis() - p.lastRemind > 86_400_000) {
            p.lastRemind = System.currentTimeMillis()
            Notify.alert(ctx, Notify.ID_EXPORT, "该导出备份了",
                "已经 ${p.exportRemindDays} 天以上没导出到 U 盘或电脑。刷机或恢复出厂会清空手机上的备份。")
        }
    }

    /** root 下给自己授权、加电池白名单，避免被 ColorOS 杀后台 */
    private fun setup(ctx: Context) {
        if (setupDone) return
        setupDone = true
        val pkg = ctx.packageName
        val perms = mutableListOf("android.permission.ACCESS_MEDIA_LOCATION")
        if (android.os.Build.VERSION.SDK_INT >= 33) perms += listOf(
            "android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO", "android.permission.POST_NOTIFICATIONS"
        ) else perms += "android.permission.READ_EXTERNAL_STORAGE"
        val cmds = perms.map { "pm grant $pkg $it" } + listOf(
            "dumpsys deviceidle whitelist +$pkg",
            "cmd appops set $pkg RUN_IN_BACKGROUND allow",
            "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow",
            "cmd appops set $pkg START_FOREGROUND allow"
        )
        Root.exec(cmds.joinToString("; ") { "$it >/dev/null 2>&1" } + "; true")
        Lg.i("已通过 root 授予媒体读取权限并加入电池白名单")
    }

    // ---------------------------------------------------------------- 校验全部备份

    fun verifyAll(ctx: Context): String {
        val db = Db.get(ctx)
        val list = db.blobs().filter { it.onPhone }
        var ok = 0; var repaired = 0; var bad = 0
        for ((i, b) in list.withIndex()) {
            if (cancel) break
            Bus.update { it.copy(busy = true, task = "校验", detail = b.rel.substringAfterLast('/'), done = i, total = list.size) }
            val got = try { Root.sha256(Store.abs(b.rel)) } catch (_: Throwable) { "" }
            if (got == b.sha) { ok++; db.putBlob(b.copy(verifiedAt = System.currentTimeMillis())); continue }
            // 坏了：如果相册里的原文件还在且没变，就重新复制一份
            val src = db.bySha(b.sha).firstOrNull { it.state == Item.ACTIVE && Root.exists(it.path) }
            val fixed = try {
                if (src != null && Root.sha256(src.path) == b.sha) { Store.copyIn(src.path, b.rel, b.sha); true } else false
            } catch (_: Throwable) { false }
            if (fixed) { repaired++; Lg.w("备份文件损坏，已从原图重新复制：${b.rel}") }
            else { bad++; Lg.e("备份文件损坏且原图已不在：${b.rel}") }
        }
        return "检查 ${list.size} 个：正常 $ok" + (if (repaired > 0) "，已修复 $repaired" else "") +
            (if (bad > 0) "，无法修复 $bad（见日志）" else "")
    }
}

object Fmt {
    fun size(b: Long): String = when {
        b >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", b / (1L shl 20).toDouble())
        b >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", b / 1024.0)
        else -> "$b B"
    }

    fun ago(t: Long): String {
        if (t <= 0) return "从未"
        val d = (System.currentTimeMillis() - t) / 1000
        return when {
            d < 60 -> "刚刚"
            d < 3600 -> "${d / 60} 分钟前"
            d < 86400 -> "${d / 3600} 小时前"
            d < 86400 * 30 -> "${d / 86400} 天前"
            else -> date(t)
        }
    }

    fun date(t: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(t))
    fun dateTime(t: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(t))

    fun daysLeft(due: Long): String {
        val ms = due - System.currentTimeMillis()
        if (ms <= 0) return "即将删除"
        val d = ms / 86_400_000
        return if (d >= 1) "还剩 $d 天" else "还剩 ${ms / 3_600_000 + 1} 小时"
    }
}
