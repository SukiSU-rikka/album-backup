package cn.rikka.album;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {

    private TextView statsView, statusView, urlView, logView;
    private ProgressBar progressBar;
    private Button backupBtn, restoreBtn, transferBtn, cancelBtn;
    private HttpTransferServer server;
    private final Handler handler = new Handler();
    private int lastLogCount = -1;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        server = new HttpTransferServer(this);
        buildUi();
        ensurePermissions();
        refreshStats();
        handler.post(poll);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(poll);
        if (server != null) server.stop();
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 48, 40, 32);

        TextView title = new TextView(this);
        title.setText("相册全备份");
        title.setTextSize(26);
        title.setTextColor(Color.rgb(14, 124, 134));
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("按系统相册（MediaStore）全量备份，藏在任何文件夹的照片视频都不会漏");
        sub.setTextSize(13);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, 8, 0, 16);
        root.addView(sub);

        statsView = new TextView(this);
        statsView.setTextSize(15);
        statsView.setPadding(0, 8, 0, 8);
        root.addView(statsView);

        backupBtn = bigButton("立即备份到本地");
        backupBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startOp("backup"); }
        });
        root.addView(backupBtn);

        transferBtn = bigButton("传到电脑（Wi-Fi）");
        transferBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleServer(); }
        });
        root.addView(transferBtn);

        urlView = new TextView(this);
        urlView.setTextSize(15);
        urlView.setTextColor(Color.rgb(14, 124, 134));
        urlView.setPadding(0, 4, 0, 8);
        root.addView(urlView);

        restoreBtn = bigButton("从备份恢复到相册");
        restoreBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startOp("restore"); }
        });
        root.addView(restoreBtn);

        cancelBtn = bigButton("取消当前任务");
        cancelBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { WorkerService.cancelCurrent(); }
        });
        cancelBtn.setVisibility(View.GONE);
        root.addView(cancelBtn);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setVisibility(View.GONE);
        progressBar.setPadding(0, 16, 0, 4);
        root.addView(progressBar);

        statusView = new TextView(this);
        statusView.setTextSize(14);
        root.addView(statusView);

        TextView logTitle = new TextView(this);
        logTitle.setText("记录");
        logTitle.setTextSize(16);
        logTitle.setPadding(0, 20, 0, 6);
        root.addView(logTitle);

        logView = new TextView(this);
        logView.setTextSize(13);
        ScrollView sv = new ScrollView(this);
        sv.addView(logView);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);
    }

    private Button bigButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(17);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 8, 0, 8);
        b.setLayoutParams(lp);
        return b;
    }

    // ---------------- 权限 ----------------

    private boolean hasMediaPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            return checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void ensurePermissions() {
        List<String> need = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.READ_MEDIA_IMAGES);
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.READ_MEDIA_VIDEO);
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            if (Build.VERSION.SDK_INT <= 28 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 7);
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] grants) {
        refreshStats();
    }

    // ---------------- 操作 ----------------

    private void startOp(String op) {
        if (!hasMediaPermission()) {
            ensurePermissions();
            statusView.setText("请先授予「照片和视频」权限，再点一次");
            return;
        }
        if (WorkerService.running) return;
        Intent i = new Intent(this, WorkerService.class);
        i.putExtra("op", op);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        lastLogCount = -1;
    }

    private void toggleServer() {
        if (server.running) {
            server.stop();
            urlView.setText("");
            transferBtn.setText("传到电脑（Wi-Fi）");
        } else {
            server.start();
            if (server.running) {
                String ip = localIp();
                urlView.setText("电脑浏览器打开：http://" + ip + ":" + HttpTransferServer.PORT + "\n（手机和电脑连同一个 Wi-Fi；用完再点一次关闭）");
                transferBtn.setText("关闭传输服务");
            } else {
                urlView.setText(server.error);
            }
        }
    }

    private String localIp() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            String fallback = null;
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    String host = a.getHostAddress();
                    if (host != null && host.indexOf(':') < 0) {
                        if (ni.getName().startsWith("wlan")) return host;
                        if (fallback == null) fallback = host;
                    }
                }
            }
            return fallback != null ? fallback : "127.0.0.1";
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    // ---------------- 状态轮询 ----------------

    private final Runnable poll = new Runnable() {
        public void run() {
            boolean running = WorkerService.running;
            progressBar.setVisibility(running ? View.VISIBLE : View.GONE);
            cancelBtn.setVisibility(running ? View.VISIBLE : View.GONE);
            backupBtn.setEnabled(!running);
            restoreBtn.setEnabled(!running);
            if (running) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                progressBar.setMax(Math.max(WorkerService.total, 1));
                progressBar.setProgress(WorkerService.done);
                statusView.setText((WorkerService.opType.equals("backup") ? "备份中 " : "恢复中 ")
                        + WorkerService.done + "/" + WorkerService.total + "  " + WorkerService.current);
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                if (WorkerService.lastSummary.length() > 0) statusView.setText(WorkerService.lastSummary);
            }
            synchronized (WorkerService.logs) {
                if (WorkerService.logs.size() != lastLogCount) {
                    lastLogCount = WorkerService.logs.size();
                    StringBuilder sb = new StringBuilder();
                    for (String l : WorkerService.logs) sb.append(l).append('\n');
                    logView.setText(sb.toString());
                }
            }
            if (!running && lastSummaryShown != WorkerService.lastSummary) {
                lastSummaryShown = WorkerService.lastSummary;
                refreshStats();
            }
            handler.postDelayed(this, 600);
        }
    };
    private String lastSummaryShown = "";

    private void refreshStats() {
        if (!hasMediaPermission()) {
            statsView.setText("尚未授予照片权限，点任意按钮会弹出授权");
            return;
        }
        final Map<String, JSONObject> map = Engine.loadManifestMap(Engine.backupRoot(this));
        long backedBytes = 0;
        for (JSONObject o : map.values()) backedBytes += o.optLong("size");
        final int backed = map.size();
        final long bb = backedBytes;
        statsView.setText("正在统计相册…");
        new Thread(new Runnable() {
            public void run() {
                int total = 0;
                try { total = MediaLib.queryAll(MainActivity.this).size(); } catch (Exception ignored) { }
                final int t = total;
                runOnUiThread(new Runnable() {
                    public void run() {
                        String hist = "";
                        try {
                            File hf = Engine.historyFile(MainActivity.this);
                            if (hf.exists()) {
                                JSONArray recs = new JSONArray(Engine.readText(hf));
                                if (recs.length() > 0) {
                                    JSONObject last = recs.getJSONObject(recs.length() - 1);
                                    hist = "\n最近一次" + (last.optString("type").equals("backup") ? "备份" : "恢复")
                                            + "：" + Engine.fmtTime(last.optLong("time"))
                                            + "（成功 " + last.optInt("added") + "，跳过 " + last.optInt("skipped") + "，失败 " + last.optInt("failed") + "）";
                                }
                            }
                        } catch (Exception ignored) { }
                        statsView.setText("相册共 " + t + " 项 · 已备份 " + backed + " 项（" + Engine.fmtBytes(bb) + "）" + hist);
                    }
                });
            }
        }).start();
    }
}
