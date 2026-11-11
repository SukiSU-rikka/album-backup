package cn.rikka.album

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore

/**
 * 常驻前台服务：监听媒体库变化 → 几秒防抖后触发同步；
 * 等延迟期的文件到点再触发；每 30 分钟兜底跑一次（处理倒计时到期、自动整理、导出提醒）。
 */
class SyncService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private val trigger = Runnable { Worker.sync(this) }
    private val delayed = Runnable { Worker.sync(this) }
    private val tick = object : Runnable {
        override fun run() { Worker.sync(this@SyncService); h.postDelayed(this, 30 * 60_000L) }
    }
    private var lastNotify = 0L

    private val observer = object : ContentObserver(h) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            h.removeCallbacks(trigger)
            h.postDelayed(trigger, 5_000)
        }
    }

    private val listener: (Status) -> Unit = { s ->
        val now = System.currentTimeMillis()
        if (!s.busy || now - lastNotify > 1000) {
            lastNotify = now
            getSystemService(NotificationManager::class.java).notify(Notify.ID_FG, build(s))
        }
        if (!s.busy && !Prefs(this).syncOn) stopSelf()
    }

    override fun onCreate() {
        super.onCreate()
        Lg.init(this)
        Notify.channels(this)
        startForeground(Notify.ID_FG, build(Bus.status), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
        Worker.onNext = { t ->
            h.post {
                h.removeCallbacks(delayed)
                if (t > 0) h.postDelayed(delayed, (t - System.currentTimeMillis()).coerceAtLeast(5_000) + 2_000)
            }
        }
        Bus.listen(listener)
        h.post(tick)
        MediaJob.schedule(this)
        running = true
        Lg.i("同步服务已启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SYNC) Worker.sync(this)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        Bus.unlisten(listener)
        contentResolver.unregisterContentObserver(observer)
        h.removeCallbacksAndMessages(null)
        Worker.onNext = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun build(s: Status): Notification {
        val db = Db.get(this)
        val p = Prefs(this)
        val (title, text) = when {
            s.busy -> Pair("正在${s.task}", if (s.total > 0) "${s.done}/${s.total}  ${s.detail}" else s.detail)
            p.haltReason.isNotEmpty() -> Pair("同步已停止", p.haltReason)
            !p.syncOn -> Pair("实时同步已关闭", "")
            else -> Pair("实时同步中", "已备份 ${db.countActive()} 个" +
                (if (s.waiting > 0) " · ${s.waiting} 个等待中" else "") + " · 上次 ${Fmt.ago(p.lastSync)}")
        }
        val b = Notification.Builder(this, Notify.CH_SYNC)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(Notify.openApp(this))
        if (s.busy && s.total > 0) b.setProgress(s.total, s.done, false)
        return b.build()
    }

    companion object {
        const val ACTION_SYNC = "cn.rikka.album.SYNC"
        @Volatile var running = false

        fun start(ctx: Context, sync: Boolean = true) {
            val i = Intent(ctx, SyncService::class.java)
            if (sync) i.action = ACTION_SYNC
            try { ctx.startForegroundService(i) } catch (t: Throwable) {
                Lg.e("启动同步服务失败", t)
                if (sync) Worker.sync(ctx)
            }
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, SyncService::class.java)) }
    }
}

/** 通知 */
object Notify {
    const val CH_SYNC = "sync"
    const val CH_ALERT = "alert"
    const val ID_FG = 1
    const val ID_HALT = 2
    const val ID_SPACE = 3
    const val ID_EXPORT = 4

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SYNC, "同步状态", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "提醒与报警", NotificationManager.IMPORTANCE_HIGH))
    }

    fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun alert(ctx: Context, id: Int, title: String, text: String) {
        channels(ctx)
        val n = Notification.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        try { ctx.getSystemService(NotificationManager::class.java).notify(id, n) } catch (_: Throwable) { }
    }

    fun cancel(ctx: Context, id: Int) { ctx.getSystemService(NotificationManager::class.java).cancel(id) }
}

/** 开机自启 / App 更新后重新拉起 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Lg.init(ctx)
        val p = Prefs(ctx)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && !p.bootStart) return
        if (p.syncOn) {
            Lg.i("收到 ${intent.action}，启动同步服务")
            SyncService.start(ctx)
        }
    }
}

/** 兜底：服务万一被杀，媒体库一变化系统就会叫醒这个任务，再把服务拉起来 */
class MediaJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        Lg.init(this)
        if (Prefs(this).syncOn) {
            if (!SyncService.running) SyncService.start(this) else Worker.sync(this)
        }
        schedule(this)
        return false
    }

    override fun onStopJob(params: JobParameters?) = false

    companion object {
        private const val ID = 1001
        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java)
            val info = JobInfo.Builder(ID, ComponentName(ctx, MediaJob::class.java))
                .addTriggerContentUri(JobInfo.TriggerContentUri(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .addTriggerContentUri(JobInfo.TriggerContentUri(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .setTriggerContentUpdateDelay(3_000)
                .setTriggerContentMaxDelay(30_000)
                .build()
            try { js.schedule(info) } catch (t: Throwable) { Lg.e("注册媒体变化任务失败", t) }
        }
    }
}
