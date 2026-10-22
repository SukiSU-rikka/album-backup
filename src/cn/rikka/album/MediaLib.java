package cn.rikka.album;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** 查 MediaStore —— 系统相册能显示的，这里全能查到。 */
public class MediaLib {

    public static class Item {
        public Uri uri;
        public String name;        // DISPLAY_NAME
        public String relDir;      // 相对外部存储根目录的目录，如 DCIM/Camera（无首尾斜杠）
        public long size;
        public long dateTaken;     // ms
        public long dateModified;  // ms
        public String mime;
        public boolean video;
    }

    public static List<Item> queryAll(android.content.Context ctx) {
        List<Item> out = new ArrayList<Item>();
        queryOne(ctx, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, out);
        queryOne(ctx, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, out);
        Collections.sort(out, new Comparator<Item>() {
            public int compare(Item a, Item b) {
                long x = a.dateTaken, y = b.dateTaken;
                return x < y ? -1 : (x == y ? 0 : 1);
            }
        });
        return out;
    }

    private static void queryOne(android.content.Context ctx, Uri base, boolean video, List<Item> out) {
        ContentResolver cr = ctx.getContentResolver();
        boolean modern = Build.VERSION.SDK_INT >= 29;
        List<String> cols = new ArrayList<String>();
        cols.add(MediaStore.MediaColumns._ID);
        cols.add(MediaStore.MediaColumns.DISPLAY_NAME);
        cols.add(MediaStore.MediaColumns.SIZE);
        cols.add(MediaStore.MediaColumns.DATE_TAKEN);
        cols.add(MediaStore.MediaColumns.DATE_MODIFIED);
        cols.add(MediaStore.MediaColumns.MIME_TYPE);
        if (modern) cols.add(MediaStore.MediaColumns.RELATIVE_PATH);
        else cols.add(MediaStore.MediaColumns.DATA);
        Cursor c = null;
        try {
            c = cr.query(base, cols.toArray(new String[0]), null, null, null);
            if (c == null) return;
            int iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
            int iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE);
            int iTaken = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN);
            int iMod = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED);
            int iMime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE);
            int iRel = modern ? c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH) : -1;
            int iData = modern ? -1 : c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA);
            String extRoot = Environment.getExternalStorageDirectory().getAbsolutePath();
            while (c.moveToNext()) {
                Item it = new Item();
                long id = c.getLong(iId);
                it.uri = ContentUris.withAppendedId(base, id);
                it.name = c.getString(iName);
                if (it.name == null || it.name.length() == 0) continue;
                it.size = c.isNull(iSize) ? 0 : c.getLong(iSize);
                it.dateTaken = c.isNull(iTaken) ? 0 : c.getLong(iTaken);
                it.dateModified = (c.isNull(iMod) ? 0 : c.getLong(iMod)) * 1000L;
                if (it.dateTaken <= 0) it.dateTaken = it.dateModified;
                it.mime = c.getString(iMime);
                it.video = video;
                if (modern) {
                    String rel = c.getString(iRel);
                    it.relDir = trimSlashes(rel == null ? "" : rel);
                } else {
                    String data = c.getString(iData);
                    it.relDir = "";
                    if (data != null && data.startsWith(extRoot)) {
                        String rest = data.substring(extRoot.length());
                        int slash = rest.lastIndexOf('/');
                        if (slash > 0) it.relDir = trimSlashes(rest.substring(0, slash));
                    }
                }
                out.add(it);
            }
        } catch (Exception e) {
            // 单个集合查询失败不拖垮整体
        } finally {
            if (c != null) c.close();
        }
    }

    static String trimSlashes(String s) {
        String r = s;
        while (r.startsWith("/")) r = r.substring(1);
        while (r.endsWith("/")) r = r.substring(0, r.length() - 1);
        return r;
    }

    /** 恢复时检查：MediaStore 里是否已有同路径同名同大小的文件 */
    public static boolean existsInStore(android.content.Context ctx, String relDir, String name, long size, boolean video) {
        Uri base = video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String sel;
        String[] args;
        if (Build.VERSION.SDK_INT >= 29) {
            sel = MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.SIZE + "=?";
            String rel = relDir.length() == 0 ? "" : relDir + "/";
            args = new String[]{rel, name, String.valueOf(size)};
        } else {
            String path = new File(Environment.getExternalStorageDirectory(), (relDir.length() == 0 ? name : relDir + "/" + name)).getAbsolutePath();
            sel = MediaStore.MediaColumns.DATA + "=?";
            args = new String[]{path};
        }
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(base, new String[]{MediaStore.MediaColumns._ID}, sel, args, null);
            return c != null && c.moveToFirst();
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.close();
        }
    }
}
