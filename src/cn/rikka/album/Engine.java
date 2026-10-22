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

/** 备份 / 恢复引擎。备份池 = App 专属目录下的 AlbumBackup 文件夹。 */
public class Engine {

    public interface Progress {
        void onProgress(int done, int total, String current);
        void onLog(String line);
    }

    public volatile boolean cancelled = false;

    private final Context ctx;
    private final Progress progress;

    public Engine(Context ctx, Progress progress) {
        this.ctx = ctx.getApplicationContext();
        this.progress = progress;
    }

    public static File backupRoot(Context ctx) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        return new File(base, "AlbumBackup");
    }

    public static File manifestFile(Context ctx) { return new File(backupRoot(ctx), "manifest.json"); }
    public static File historyFile(Context ctx) { return new File(backupRoot(ctx), "history.json"); }

    // ---------------- 备份 ----------------

    public static class Result {
        public int total, added, skipped, failed;
        public long bytes;
    }

    public Result runBackup() throws Exception {
        cancelled = false;
        File root = backupRoot(ctx);
        if (!root.exists()) root.mkdirs();
        Map<String, JSONObject> old = loadManifestMap(root);
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
                    File f = new File(root, prev.optString("stored", key));
                    if (f.exists() && f.length() == it.size) {
                        arr.put(prev);
                        r.skipped++;
                        continue;
                    }
                }
                String stored = copyOne(it, root, key);
                JSONObject o = new JSONObject();
                o.put("stored", stored);
                o.put("name", it.name);
                o.put("rel", it.relDir);
                o.put("size", it.size);
                o.put("taken", it.dateTaken);
                o.put("modified", it.dateModified);
                o.put("mime", it.mime == null ? "" : it.mime);
                o.put("video", it.video);
                // md5 在 copyOne 里算好放 ThreadLocal 不方便，改为存 size 校验，md5 存 copy 时计算
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
        writeText(new File(root, "manifest.json"), man.toString());
        appendHistory(root, "backup", r);
        return r;
    }

    private String lastMd5 = "";

    private String copyOne(MediaLib.Item it, File root, String key) throws Exception {
        File dest = new File(root, key);
        if (dest.exists() && dest.length() > 0 && dest.length() != it.size) {
            // 同路径同名但大小不同：换名存放，避免覆盖另一份
            int dot = key.lastIndexOf('.');
            String alt = (dot > 0 ? key.substring(0, dot) : key) + "_" + it.dateModified + (dot > 0 ? key.substring(dot) : "");
            dest = new File(root, alt);
            key = alt;
        }
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File tmp = new File(parent, dest.getName() + ".part");
        InputStream in = null;
        OutputStream out = null;
        MessageDigest md = MessageDigest.getInstance("MD5");
        try {
            in = ctx.getContentResolver().openInputStream(it.uri);
            if (in == null) throw new Exception("无法读取源文件");
            out = new FileOutputStream(tmp);
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                md.update(buf, 0, n);
                if (cancelled) throw new Exception("已取消");
            }
        } finally {
            if (in != null) in.close();
            if (out != null) out.close();
        }
        if (dest.exists()) dest.delete();
        if (!tmp.renameTo(dest)) throw new Exception("写入备份文件失败");
        if (it.dateModified > 0) dest.setLastModified(it.dateModified);
        lastMd5 = hex(md.digest());
        return key;
    }

    // ---------------- 恢复 ----------------

    public Result runRestore() throws Exception {
        cancelled = false;
        File root = backupRoot(ctx);
        Map<String, JSONObject> map = loadManifestMap(root);
        if (map.isEmpty()) throw new Exception("没有找到备份清单（manifest.json），请先备份，或把备份文件夹拷到本机后重试");
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
                if (MediaLib.existsInStore(ctx, rel, name, size, video)) {
                    r.skipped++;
                    continue;
                }
                File src = new File(root, stored);
                if (!src.exists()) { r.failed++; progress.onLog("备份文件缺失：" + stored); continue; }
                restoreOne(src, o);
                r.added++;
                r.bytes += size;
            } catch (Exception e) {
                r.failed++;
                progress.onLog("恢复失败：" + name + "（" + e.getMessage() + "）");
            }
        }
        appendHistory(root, "restore", r);
        return r;
    }

    private void restoreOne(File src, JSONObject o) throws Exception {
        String name = o.optString("name");
        String rel = o.optString("rel", "");
        String mime = o.optString("mime", "");
        long taken = o.optLong("taken");
        long modified = o.optLong("modified");
        boolean video = o.optBoolean("video");
        if (Build.VERSION.SDK_INT >= 29) {
            Uri base = video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            if (mime.length() > 0) v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, rel.length() == 0 ? Environment.DIRECTORY_PICTURES : rel);
            if (taken > 0) v.put(MediaStore.MediaColumns.DATE_TAKEN, taken);
            if (modified > 0) v.put(MediaStore.MediaColumns.DATE_MODIFIED, modified / 1000L);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            ContentResolver cr = ctx.getContentResolver();
            Uri uri = cr.insert(base, v);
            if (uri == null) throw new Exception("MediaStore 拒绝写入");
            OutputStream out = null;
            InputStream in = null;
            try {
                out = cr.openOutputStream(uri);
                in = new FileInputStream(src);
                byte[] buf = new byte[128 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                if (in != null) in.close();
                if (out != null) out.close();
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, done, null, null);
        } else {
            File dest = new File(Environment.getExternalStorageDirectory(), rel.length() == 0 ? name : rel + "/" + name);
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            InputStream in = null;
            OutputStream out = null;
            try {
                in = new FileInputStream(src);
                out = new FileOutputStream(dest);
                byte[] buf = new byte[128 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                if (in != null) in.close();
                if (out != null) out.close();
            }
            if (modified > 0) dest.setLastModified(modified);
            android.media.MediaScannerConnection.scanFile(ctx, new String[]{dest.getAbsolutePath()}, null, null);
        }
    }

    // ---------------- 清单/历史 ----------------

    public static Map<String, JSONObject> loadManifestMap(File root) {
        Map<String, JSONObject> map = new HashMap<String, JSONObject>();
        try {
            File f = new File(root, "manifest.json");
            if (!f.exists()) return map;
            JSONObject man = new JSONObject(readText(f));
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

    private void appendHistory(File root, String type, Result r) {
        try {
            JSONArray recs = new JSONArray();
            File f = new File(root, "history.json");
            if (f.exists()) {
                try { recs = new JSONArray(readText(f)); } catch (Exception ignored) { }
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
            writeText(f, recs.toString());
        } catch (Exception ignored) { }
    }

    public static String readText(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[(int) f.length()];
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            return new String(buf, 0, off, "UTF-8");
        } finally { in.close(); }
    }

    public static void writeText(File f, String s) throws Exception {
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        FileOutputStream out = new FileOutputStream(f);
        try { out.write(s.getBytes("UTF-8")); } finally { out.close(); }
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
