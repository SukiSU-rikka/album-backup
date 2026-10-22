package cn.rikka.album;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 备份 / 恢复引擎。存储出口统一走 BackupStore（默认目录或用户自选 SAF 文件夹）。 */
public class Engine {

    public interface Progress {
        void onProgress(int done, int total, String current);
        void onLog(String line);
    }

    public volatile boolean cancelled = false;

    /** 恢复统一落点：不按原路径还原，全部进这个相册，靠拍摄时间排序 */
    public static final String RESTORE_DIR = "Pictures/相册备份恢复";
    public static final String MANIFEST = "manifest.json";
    public static final String HISTORY = "history.json";

    private final Context ctx;
    private final Progress progress;
    private final BackupStore store;

    public Engine(Context ctx, Progress progress) {
        this.ctx = ctx.getApplicationContext();
        this.progress = progress;
        this.store = BackupStore.open(this.ctx);
    }

    // ---------------- 备份 ----------------

    public static class Result {
        public int total, added, skipped, failed;
        public long bytes;
    }

    public Result runBackup() throws Exception {
        cancelled = false;
        Map<String, JSONObject> old = loadManifestMap(store);
        List<MediaLib.Item> items = MediaLib.queryAll(ctx);
        Result r = new Result();
        r.total = items.size();
        JSONArray arr = new JSONArray();
        int done = 0;
        for (MediaLib.Item it : items) {
            if (cancelled) break;
            done++;
            progress.onProgress(done, r.total, it.name);
            String key = (it.relDir.length() == 0 ? it.name : it.relDir + "/" + it.name);
            JSONObject prev = old.get(key);
            try {
                if (prev != null
                        && prev.optLong("size") == it.size
                        && prev.optLong("modified") == it.dateModified) {
                    String storedPath = prev.optString("stored", key);
                    if (store.exists(storedPath) && store.length(storedPath) == it.size) {
                        arr.put(prev);
                        r.skipped++;
                        continue;
                    }
                }
                String stored = copyOne(it, key, prev != null);
                JSONObject o = new JSONObject();
                o.put("stored", stored);
                o.put("name", it.name);
                o.put("rel", it.relDir);
                o.put("size", it.size);
                o.put("taken", it.dateTaken);
                o.put("modified", it.dateModified);
                o.put("mime", it.mime == null ? "" : it.mime);
                o.put("video", it.video);
                o.put("md5", lastMd5);
                arr.put(o);
                r.added++;
                r.bytes += it.size;
            } catch (Exception e) {
                r.failed++;
                progress.onLog("备份失败：" + key + "（" + e.getMessage() + "）");
            }
        }
        JSONObject man = new JSONObject();
        man.put("version", 1);
        man.put("items", arr);
        writeStoreText(MANIFEST, man.toString());
        appendHistory("backup", r);
        return r;
    }

    private String lastMd5 = "";

    private String copyOne(MediaLib.Item it, String key, boolean hadPrev) throws Exception {
        String target = key;
        long existLen = store.length(key);
        if (hadPrev && existLen >= 0 && existLen != it.size) {
            // 同路径同名但内容已变：旧的那份保留，新文件换名存，避免覆盖
            int dot = key.lastIndexOf('.');
            target = (dot > 0 ? key.substring(0, dot) : key) + "_" + it.dateModified + (dot > 0 ? key.substring(dot) : "");
        }
        InputStream in = null;
        OutputStream out = null;
        MessageDigest md = MessageDigest.getInstance("MD5");
        try {
            in = ctx.getContentResolver().openInputStream(it.uri);
            if (in == null) throw new Exception("无法读取源文件");
            out = store.create(target);
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                md.update(buf, 0, n);
                if (cancelled) throw new Exception("已取消");
            }
            out.flush();
        } finally {
            if (in != null) in.close();
            if (out != null) out.close();
        }
        lastMd5 = hex(md.digest());
        return target;
    }

    // ---------------- 恢复 ----------------

    public Result runRestore() throws Exception {
        cancelled = false;
        Map<String, JSONObject> map = loadManifestMap(store);
        if (map.isEmpty()) throw new Exception("在备份位置没找到清单（manifest.json），请先备份，或把备份拷到当前备份文件夹后重试");
        Result r = new Result();
        r.total = map.size();
        int done = 0;
        for (JSONObject o : map.values()) {
            if (cancelled) break;
            done++;
            String name = o.optString("name");
            String rel = o.optString("rel", "");
            String stored = o.optString("stored", (rel.length() == 0 ? name : rel + "/" + name));
            long size = o.optLong("size");
            boolean video = o.optBoolean("video");
            progress.onProgress(done, r.total, name);
            try {
                if (MediaLib.existsInStore(ctx, RESTORE_DIR, name, size, video)) {
                    r.skipped++;
                    continue;
                }
                InputStream in = store.open(stored);
                if (in == null) { r.failed++; progress.onLog("备份文件缺失：" + stored); continue; }
                try { restoreOne(in, o); } finally { in.close(); }
                r.added++;
                r.bytes += size;
            } catch (Exception e) {
                r.failed++;
                progress.onLog("恢复失败：" + name + "（" + e.getMessage() + "）");
            }
        }
        appendHistory("restore", r);
        return r;
    }

    private void restoreOne(InputStream in, JSONObject o) throws Exception {
        String name = o.optString("name");
        String mime = o.optString("mime", "");
        long taken = o.optLong("taken");
        long modified = o.optLong("modified");
        long size = o.optLong("size");
        boolean video = o.optBoolean("video");
        if (Build.VERSION.SDK_INT >= 29) {
            Uri base = video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            if (mime.length() > 0) v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, RESTORE_DIR);
            if (taken > 0) v.put(MediaStore.MediaColumns.DATE_TAKEN, taken);
            if (modified > 0) v.put(MediaStore.MediaColumns.DATE_MODIFIED, modified / 1000L);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            ContentResolver cr = ctx.getContentResolver();
            Uri uri = cr.insert(base, v);
            if (uri == null) throw new Exception("MediaStore 拒绝写入");
            OutputStream out = null;
            try {
                out = cr.openOutputStream(uri);
                byte[] buf = new byte[128 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                if (out != null) out.close();
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, done, null, null);
        } else {
            File dest = new File(Environment.getExternalStorageDirectory(), RESTORE_DIR + "/" + name);
            if (dest.exists() && dest.length() != size) {
                int dot = name.lastIndexOf('.');
                String alt = (dot > 0 ? name.substring(0, dot) : name) + "_" + modified + (dot > 0 ? name.substring(dot) : "");
                dest = new File(Environment.getExternalStorageDirectory(), RESTORE_DIR + "/" + alt);
            }
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            OutputStream out = null;
            try {
                out = new FileOutputStream(dest);
                byte[] buf = new byte[128 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                if (out != null) out.close();
            }
            if (modified > 0) dest.setLastModified(modified);
            android.media.MediaScannerConnection.scanFile(ctx, new String[]{dest.getAbsolutePath()}, null, null);
        }
    }

    // ---------------- 清单/历史 ----------------

    public static Map<String, JSONObject> loadManifestMap(BackupStore store) {
        Map<String, JSONObject> map = new HashMap<String, JSONObject>();
        try {
            InputStream in = store.open(MANIFEST);
            if (in == null) return map;
            String text;
            try { text = readText(in); } finally { in.close(); }
            JSONObject man = new JSONObject(text);
            JSONArray arr = man.optJSONArray("items");
            if (arr == null) return map;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String rel = o.optString("rel", "");
                String name = o.optString("name", "");
                map.put(rel.length() == 0 ? name : rel + "/" + name, o);
            }
        } catch (Exception ignored) { }
        return map;
    }

    public static Map<String, JSONObject> loadManifestMap(Context ctx) {
        return loadManifestMap(BackupStore.open(ctx));
    }

    public static JSONArray readHistory(Context ctx) {
        try {
            BackupStore store = BackupStore.open(ctx);
            InputStream in = store.open(HISTORY);
            if (in == null) return new JSONArray();
            try { return new JSONArray(readText(in)); } finally { in.close(); }
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private void appendHistory(String type, Result r) {
        try {
            JSONArray recs = new JSONArray();
            InputStream in = store.open(HISTORY);
            if (in != null) {
                try { recs = new JSONArray(readText(in)); } catch (Exception ignored) { }
                in.close();
            }
            JSONObject o = new JSONObject();
            o.put("time", System.currentTimeMillis());
            o.put("type", type);
            o.put("total", r.total);
            o.put("added", r.added);
            o.put("skipped", r.skipped);
            o.put("failed", r.failed);
            o.put("bytes", r.bytes);
            recs.put(o);
            while (recs.length() > 50) recs.remove(0);
            writeStoreText(HISTORY, recs.toString());
        } catch (Exception ignored) { }
    }

    private void writeStoreText(String rel, String s) throws Exception {
        OutputStream out = store.create(rel);
        try { out.write(s.getBytes("UTF-8")); } finally { out.close(); }
    }

    public static String readText(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), "UTF-8");
    }

    static String hex(byte[] bs) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bs) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    public static String fmtBytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.CHINA, "%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format(Locale.CHINA, "%.1f MB", b / 1048576.0);
        return String.format(Locale.CHINA, "%.2f GB", b / 1073741824.0);
    }

    public static String fmtTime(long ms) {
        if (ms <= 0) return "—";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new Date(ms));
    }
}
