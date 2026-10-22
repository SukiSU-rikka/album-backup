package cn.rikka.album;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 备份存储层：两种模式——
 * 1) 默认：App 专属目录 Android/data/cn.rikka.album/files/AlbumBackup
 * 2) 用户自选：SAF 文件夹（ACTION_OPEN_DOCUMENT_TREE 授权，持久化保存）
 * 对上层提供按相对路径的 open/create/exists/length/listAll。
 */
public class BackupStore {

    public static final String PREFS = "album_backup_prefs";
    public static final String KEY_TREE = "tree_uri";

    private final Context ctx;
    private final boolean saf;
    private final File rootFile;     // 非 SAF 模式
    private final Uri treeUri;       // SAF 模式
    private final String rootDocId;  // SAF 根目录 docId

    // SAF 目录列表缓存：relDir -> (name -> [docId, mime, size])
    private final Map<String, Map<String, String[]>> listingCache = new HashMap<String, Map<String, String[]>>();
    private final Map<String, String> dirIdCache = new HashMap<String, String>();

    private BackupStore(Context ctx, File rootFile) {
        this.ctx = ctx; this.saf = false; this.rootFile = rootFile;
        this.treeUri = null; this.rootDocId = null;
    }

    private BackupStore(Context ctx, Uri treeUri) {
        this.ctx = ctx; this.saf = true; this.rootFile = null;
        this.treeUri = treeUri;
        this.rootDocId = DocumentsContract.getTreeDocumentId(treeUri);
        dirIdCache.put("", rootDocId);
    }

    public static BackupStore open(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String s = sp.getString(KEY_TREE, null);
        if (s != null && s.length() > 0) {
            try { return new BackupStore(ctx, Uri.parse(s)); }
            catch (Exception ignored) { }
        }
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        return new BackupStore(ctx, new File(base, "AlbumBackup"));
    }

    public boolean isSaf() { return saf; }

    public String describe() {
        if (!saf) return rootFile.getAbsolutePath();
        String id = rootDocId == null ? "" : rootDocId;
        int colon = id.indexOf(':');
        String vol = colon >= 0 ? id.substring(0, colon) : "";
        String path = colon >= 0 ? id.substring(colon + 1) : id;
        String volName = "primary".equals(vol) ? "内部存储" : ("存储卡(" + vol + ")");
        return path.length() == 0 ? volName : volName + "/" + path;
    }

    // ---------------- 对外接口（相对路径，'/' 分隔） ----------------

    public InputStream open(String rel) throws Exception {
        if (!saf) {
            File f = new File(rootFile, rel);
            if (!f.isFile()) return null;
            return new FileInputStream(f);
        }
        String[] e = findEntry(rel);
        if (e == null) return null;
        return ctx.getContentResolver().openInputStream(docUri(e[0]));
    }

    public OutputStream create(String rel) throws Exception {
        if (!saf) {
            File f = new File(rootFile, rel);
            File p = f.getParentFile();
            if (p != null && !p.exists()) p.mkdirs();
            return new FileOutputStream(f);
        }
        String parentRel = parentOf(rel);
        String name = nameOf(rel);
        String parentId = ensureDir(parentRel);
        Map<String, String[]> listing = listing(parentRel);
        String[] exist = listing.get(name);
        ContentResolver cr = ctx.getContentResolver();
        Uri doc;
        if (exist != null) {
            doc = docUri(exist[0]);
            OutputStream out = cr.openOutputStream(doc, "wt");
            if (out != null) return out;
            // 写不了就删掉重建
            try { DocumentsContract.deleteDocument(cr, doc); } catch (Exception ignored) { }
            listing.remove(name);
        }
        Uri created = DocumentsContract.createDocument(cr, docUri(parentId), "application/octet-stream", name);
        if (created == null) throw new Exception("无法在所选文件夹创建文件：" + rel);
        String newId = DocumentsContract.getDocumentId(created);
        listing.put(name, new String[]{newId, "application/octet-stream", "0"});
        OutputStream out = cr.openOutputStream(created, "wt");
        if (out == null) throw new Exception("无法写入文件：" + rel);
        return out;
    }

    public boolean exists(String rel) {
        if (!saf) return new File(rootFile, rel).isFile();
        try { return findEntry(rel) != null; } catch (Exception e) { return false; }
    }

    public long length(String rel) {
        if (!saf) {
            File f = new File(rootFile, rel);
            return f.isFile() ? f.length() : -1;
        }
        try {
            String[] e = findEntry(rel);
            return e == null ? -1 : Long.parseLong(e[2]);
        } catch (Exception ex) { return -1; }
    }

    public List<String> listAll() {
        List<String> out = new ArrayList<String>();
        if (!saf) {
            listFiles(rootFile, "", out);
            return out;
        }
        listSaf("", out);
        return out;
    }

    // ---------------- SAF 内部 ----------------

    private Uri docUri(String docId) {
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
    }

    private String parentOf(String rel) {
        int i = rel.lastIndexOf('/');
        return i < 0 ? "" : rel.substring(0, i);
    }

    private String nameOf(String rel) {
        int i = rel.lastIndexOf('/');
        return i < 0 ? rel : rel.substring(i + 1);
    }

    /** 找到 rel 对应条目：[docId, mime, size]，不存在返回 null */
    private String[] findEntry(String rel) throws Exception {
        Map<String, String[]> listing = listing(parentOf(rel));
        return listing.get(nameOf(rel));
    }

    private Map<String, String[]> listing(String relDir) throws Exception {
        Map<String, String[]> cached = listingCache.get(relDir);
        if (cached != null) return cached;
        String dirId = dirId(relDir);
        Map<String, String[]> map = new HashMap<String, String[]>();
        if (dirId != null) {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirId);
            Cursor c = null;
            try {
                c = ctx.getContentResolver().query(children, new String[]{
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE}, null, null, null);
                if (c != null) {
                    while (c.moveToNext()) {
                        String name = c.getString(1);
                        if (name == null) continue;
                        long size = c.isNull(3) ? 0 : c.getLong(3);
                        map.put(name, new String[]{c.getString(0), c.getString(2), String.valueOf(size)});
                    }
                }
            } finally {
                if (c != null) c.close();
            }
        }
        listingCache.put(relDir, map);
        return map;
    }

    private String dirId(String relDir) {
        String id = dirIdCache.get(relDir);
        if (id != null) return id;
        if (relDir.length() == 0) { dirIdCache.put("", rootDocId); return rootDocId; }
        try {
            String parentRel = parentOf(relDir);
            String parentId = dirId(parentRel);
            if (parentId == null) return null;
            Map<String, String[]> listing = listing(parentRel);
            String[] e = listing.get(nameOf(relDir));
            if (e != null && DocumentsContract.Document.MIME_TYPE_DIR.equals(e[1])) {
                dirIdCache.put(relDir, e[0]);
                return e[0];
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** 确保目录存在（逐级创建），返回 docId */
    private String ensureDir(String relDir) throws Exception {
        if (relDir.length() == 0) return rootDocId;
        String id = dirId(relDir);
        if (id != null) return id;
        String parentRel = parentOf(relDir);
        String parentId = ensureDir(parentRel);
        Map<String, String[]> listing = listing(parentRel);
        Uri created = DocumentsContract.createDocument(ctx.getContentResolver(), docUri(parentId),
                DocumentsContract.Document.MIME_TYPE_DIR, nameOf(relDir));
        if (created == null) throw new Exception("无法创建文件夹：" + relDir);
        String newId = DocumentsContract.getDocumentId(created);
        listing.put(nameOf(relDir), new String[]{newId, DocumentsContract.Document.MIME_TYPE_DIR, "0"});
        dirIdCache.put(relDir, newId);
        return newId;
    }

    private void listSaf(String relDir, List<String> out) {
        try {
            Map<String, String[]> listing = listing(relDir);
            for (Map.Entry<String, String[]> e : listing.entrySet()) {
                String childRel = relDir.length() == 0 ? e.getKey() : relDir + "/" + e.getKey();
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(e.getValue()[1])) listSaf(childRel, out);
                else out.add(childRel);
            }
        } catch (Exception ignored) { }
    }

    private void listFiles(File dir, String prefix, List<String> out) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            String rel = prefix.length() == 0 ? f.getName() : prefix + "/" + f.getName();
            if (f.isDirectory()) listFiles(f, rel, out);
            else out.add(rel);
        }
    }
}
