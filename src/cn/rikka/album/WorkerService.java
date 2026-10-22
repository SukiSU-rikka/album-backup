package cn.rikka.album;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import java.util.ArrayList;
import java.util.List;

/** 前台服务：跑备份/恢复，防系统杀后台。状态放静态字段，界面轮询读取。 */
public class WorkerService extends Service {

    public static volatile boolean running = false;
    public static volatile String opType = "";
    public static volatile int done = 0;
    public static volatile int total = 0;
    public static volatile String current = "";
    public static volatile String lastSummary = "";
    public static final List<String> logs = new ArrayList<String>();
    private static Engine engine;

    private static final String CH = "album_backup";
    private long lastNotify = 0;

    public static void addLog(String line) {
        synchronized (logs) {
            logs.add(line);
            while (logs.size() > 200) logs.remove(0);
        }
    }

    public static void cancelCurrent() {
        if (engine != null) engine.cancelled = true;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            nm.createNotificationChannel(new NotificationChannel(CH, "备份任务", NotificationManager.IMPORTANCE_LOW));
        }
    }

    private Notification buildNotification(String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH)
                : new Notification.Builder(this);
        return b.setContentTitle("相册全备份")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .build();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || running) return START_NOT_STICKY;
        final String op = intent.getStringExtra("op");
        if (op == null) return START_NOT_STICKY;
        running = true;
        opType = op;
        done = 0; total = 0; current = "";
        try {
            Notification n = buildNotification("准备中…");
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(1, n);
            }
        } catch (Exception e) {
            // 前台启动失败也继续跑任务本体
            try { startForeground(1, buildNotification("运行中")); } catch (Exception ignored) { }
        }
        engine = new Engine(this, new Engine.Progress() {
            public void onProgress(int d, int t, String cur) {
                done = d; total = t; current = cur;
                long now = System.currentTimeMillis();
                if (now - lastNotify > 1500) {
                    lastNotify = now;
                    NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                    if (nm != null) nm.notify(1, buildNotification((op.equals("backup") ? "备份中 " : "恢复中 ") + d + "/" + t));
                }
            }
            public void onLog(String line) { addLog(line); }
        });
        new Thread(new Runnable() {
            public void run() {
                String verb = op.equals("backup") ? "备份" : "恢复";
                addLog("—— " + verb + "开始 " + Engine.fmtTime(System.currentTimeMillis()));
                try {
                    Engine.Result r = op.equals("backup") ? engine.runBackup() : engine.runRestore();
                    lastSummary = verb + "完成：共 " + r.total + " 项，成功 " + r.added + "，跳过 " + r.skipped + "，失败 " + r.failed
                            + (r.bytes > 0 ? "，传输 " + Engine.fmtBytes(r.bytes) : "");
                    addLog(lastSummary);
                } catch (Exception e) {
                    lastSummary = verb + "中断：" + e.getMessage();
                    addLog(lastSummary);
                } finally {
                    running = false;
                    opType = "";
                    stopForeground(true);
                    stopSelf();
                }
            }
        }, "album-worker").start();
        return START_NOT_STICKY;
    }
}
