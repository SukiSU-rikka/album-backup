package cn.rikka.album;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 局域网传输服务：电脑浏览器打开手机给的地址，即可下载备份（单个文件或整包 ZIP）。 */
public class HttpTransferServer {

    public static final int PORT = 18080;

    private final Context ctx;
    private ServerSocket server;
    private Thread thread;
    public volatile boolean running = false;
    public volatile String error = "";

    public HttpTransferServer(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    public void start() {
        if (running) return;
        try {
            server = new ServerSocket(PORT);
            running = true;
            error = "";
            thread = new Thread(new Runnable() {
                public void run() {
                    while (running) {
                        try {
                            final Socket s = server.accept();
                            new Thread(new Runnable() {
                                public void run() { handle(s); }
                            }).start();
                        } catch (Exception e) {
                            if (running) error = "传输服务异常：" + e.getMessage();
                        }
                    }
                }
            }, "album-http");
            thread.start();
        } catch (Exception e) {
            error = "传输服务启动失败：" + e.getMessage();
            running = false;
        }
    }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        server = null;
    }

    private void handle(Socket s) {
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), "ISO-8859-1"));
            String line = br.readLine();
            if (line == null) { s.close(); return; }
            String[] parts = line.split(" ");
            String path = parts.length >= 2 ? parts[1] : "/";
            while ((line = br.readLine()) != null && line.length() > 0) { /* 读完请求头 */ }
            OutputStream out = s.getOutputStream();
            File root = Engine.backupRoot(ctx);
            if (path.equals("/") || path.equals("/index.html")) {
                byte[] html = indexPage(root).getBytes("UTF-8");
                writeHead(out, 200, "text/html; charset=utf-8", html.length);
                out.write(html);
            } else if (path.equals("/zip")) {
                writeHead(out, 200, "application/zip", -1);
                ZipOutputStream zos = new ZipOutputStream(out);
                zos.setLevel(0);
                addToZip(zos, root, root);
                zos.finish();
                zos.flush();
            } else if (path.startsWith("/f/")) {
                String rel = URLDecoder.decode(path.substring(3), "UTF-8");
                File f = new File(root, rel);
                String canonRoot = root.getCanonicalPath();
                if (!f.getCanonicalPath().startsWith(canonRoot) || !f.isFile()) {
                    byte[] msg = "404".getBytes("UTF-8");
                    writeHead(out, 404, "text/plain", msg.length);
                    out.write(msg);
                } else {
                    writeHead(out, 200, "application/octet-stream", f.length());
                    FileInputStream in = new FileInputStream(f);
                    copy(in, out);
                    in.close();
                }
            } else {
                byte[] msg = "404".getBytes("UTF-8");
                writeHead(out, 404, "text/plain", msg.length);
                out.write(msg);
            }
            out.flush();
            s.close();
        } catch (Exception e) {
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    private void addToZip(ZipOutputStream zos, File root, File dir) throws Exception {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) { addToZip(zos, root, f); continue; }
            String rel = root.toURI().relativize(f.toURI()).getPath();
            ZipEntry e = new ZipEntry(rel);
            e.setTime(f.lastModified());
            zos.putNextEntry(e);
            FileInputStream in = new FileInputStream(f);
            copy(in, zos);
            in.close();
            zos.closeEntry();
        }
    }

    private void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[128 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private void writeHead(OutputStream out, int code, String type, long len) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(code == 200 ? " OK" : " Not Found").append("\r\n");
        sb.append("Content-Type: ").append(type).append("\r\n");
        if (len >= 0) sb.append("Content-Length: ").append(len).append("\r\n");
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes("ISO-8859-1"));
        out.flush();
    }

    private String indexPage(File root) {
        Map<String, JSONObject> map = Engine.loadManifestMap(root);
        long totalBytes = 0;
        StringBuilder rows = new StringBuilder();
        for (JSONObject o : map.values()) {
            String stored = o.optString("stored");
            long size = o.optLong("size");
            totalBytes += size;
            rows.append("<tr><td><a href=\"/f/").append(urlEncodePath(stored)).append("\">")
                .append(esc(stored)).append("</a></td><td>").append(Engine.fmtBytes(size)).append("</td><td>")
                .append(Engine.fmtTime(o.optLong("taken"))).append("</td></tr>");
        }
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>相册全备份</title>"
            + "<style>body{font-family:sans-serif;margin:24px}table{border-collapse:collapse}td{padding:4px 10px;border-bottom:1px solid #ddd}"
            + ".big{display:inline-block;background:#0e7c86;color:#fff;padding:12px 20px;border-radius:8px;text-decoration:none;font-size:18px}</style></head><body>"
            + "<h2>相册全备份</h2><p>共 " + map.size() + " 个文件，合计 " + Engine.fmtBytes(totalBytes) + "</p>"
            + "<p><a class=\"big\" href=\"/zip\">⬇ 下载全部（ZIP）</a></p>"
            + "<table>" + rows + "</table>"
            + "<p style=\"color:#888\">提示：把 ZIP 解压后，将 AlbumBackup 文件夹拷回手机（或在 App 里执行恢复），即可按原路径和拍摄时间恢复到相册。</p>"
            + "</body></html>";
    }

    private String urlEncodePath(String p) {
        try {
            String[] seg = p.split("/");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < seg.length; i++) {
                if (i > 0) sb.append('/');
                sb.append(URLEncoder.encode(seg[i], "UTF-8").replace("+", "%20"));
            }
            return sb.toString();
        } catch (Exception e) { return p; }
    }

    private String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
