package cn.rikka.album

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import kotlin.concurrent.thread

/** 前台服务：切到后台、息屏也能继续备份/恢复 */
class WorkService : Service() {

    companion object {
        const val ACTION_STOP = "cn.rikka.album.STOP"
        private const val CHANNEL = "work"
        private const val ID_PROGRESS = 1
        private const val ID_DONE = 2
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Bus.cancel = true
            return START_NOT_STICKY
        }
        createChannel()
        val mode = intent?.getStringExtra("mode") ?: "backup"
        val title = if (mode == "backup") "正在备份…" else "正在恢复…"
        // 被 startForegroundService 拉起后必须马上调用 startForeground
        startForeground(
            ID_PROGRESS, buildNotif(title, 0, true), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        val treeStr = intent?.getStringExtra("tree")
        if (intent == null || treeStr == null || Bus.running) {
            if (!Bus.running) stopSelf()
            return START_NOT_STICKY
        }
        val tree = Uri.parse(treeStr)
        val layout = Layout.valueOf(intent.getStringExtra("layout") ?: "DATE")
        val orig = intent.getBooleanExtra("orig", false)

        Bus.running = true
        Bus.cancel = false
        Bus.publish(Stats(mode = if (mode == "backup") "备份" else "恢复"))

        thread(name = "media-work") {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "album:work")
            wl.acquire(6 * 60 * 60 * 1000L)
            var last = 0L
            val engine = Engine(
                applicationContext,
                cancelled = { Bus.cancel },
                report = { s ->
                    Bus.publish(s)
                    val now = SystemClock.elapsedRealtime()
                    if (now - last > 1000) {
                        last = now
                        notifyProgress(title, s)
                    }
                }
            )
            val result: Stats = try {
                if (mode == "backup") engine.backup(tree, layout) else engine.restore(tree, orig)
            } catch (t: Throwable) {
                Bus.snapshot.copy(
                    finished = true, current = "",
                    message = "出错了：${t.message ?: t.javaClass.simpleName}"
                )
            } finally {
                if (wl.isHeld) wl.release()
            }
            Bus.running = false
            Bus.publish(result)
            stopForeground(STOP_FOREGROUND_REMOVE)
            notifyDone(result.message.lineSequence().firstOrNull() ?: "完成")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "备份进度", NotificationManager.IMPORTANCE_LOW))
    }

    private fun buildNotif(text: String, pct: Int, indeterminate: Boolean): Notification {
        val stop = PendingIntent.getService(
            this, 0, Intent(this, WorkService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "停止", stop
        ).build()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("相册备份")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct, indeterminate)
            .addAction(action)
            .build()
    }

    private fun notifyProgress(title: String, s: Stats) {
        val pct = if (s.bytesTotal > 0) (s.bytesDone * 100 / s.bytesTotal).toInt().coerceIn(0, 100) else 0
        val text = "${s.done}/${s.total}  $pct%"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(ID_PROGRESS, buildNotif("$title $text", pct, s.total == 0))
    }

    private fun notifyDone(text: String) {
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("相册备份")
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ID_DONE, n)
    }
}
