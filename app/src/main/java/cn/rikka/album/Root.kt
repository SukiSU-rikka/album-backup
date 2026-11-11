package cn.rikka.album

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

/**
 * 常驻 root shell。所有对 /data/photo_backup 和相册文件的读写都走这里。
 * 优先用 `su --mount-master`（全局挂载命名空间，能看到 /storage/emulated/0），不支持时退回普通 su。
 */
object Root {
    class Res(val code: Int, val out: String) {
        val ok get() = code == 0
        val lines: List<String> get() = out.lines().filter { it.isNotEmpty() }
    }

    private var proc: Process? = null
    private var w: BufferedWriter? = null
    private var r: BufferedReader? = null
    private var seq = 0
    private var suCmd: Array<String>? = null

    /** 单引号转义，任何路径都能安全放进命令行 */
    fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    @Synchronized
    fun available(): Boolean = try {
        val res = exec("id -u")
        res.ok && res.out.trim() == "0"
    } catch (_: Throwable) {
        false
    }

    private fun start() {
        val candidates = listOf(arrayOf("su", "--mount-master"), arrayOf("su", "-mm"), arrayOf("su"))
        val list = suCmd?.let { listOf(it) } ?: candidates
        var last: Throwable? = null
        for (cmd in list) {
            try {
                val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
                val pw = BufferedWriter(OutputStreamWriter(p.outputStream, Charsets.UTF_8))
                val pr = BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8))
                pw.write("echo __hello__\n"); pw.flush()
                // 等最多 20 秒（用户可能正在点授权弹窗）
                val deadline = System.currentTimeMillis() + 20_000
                var line: String? = null
                while (System.currentTimeMillis() < deadline) {
                    if (pr.ready()) { line = pr.readLine(); break }
                    if (!p.isAlive) break
                    Thread.sleep(30)
                }
                if (line?.trim() == "__hello__") {
                    proc = p; w = pw; r = pr; suCmd = cmd
                    return
                }
                p.destroy()
            } catch (t: Throwable) {
                last = t
            }
        }
        throw IllegalStateException("拿不到 root：" + (last?.message ?: "su 被拒绝或不存在"))
    }

    @Synchronized
    fun exec(cmd: String): Res {
        for (attempt in 0..1) {
            try {
                if (proc?.isAlive != true) start()
                val tag = "__end_${++seq}__"
                val pw = w!!; val pr = r!!
                pw.write("{ $cmd\n} </dev/null 2>&1\necho \"$tag \$?\"\n")
                pw.flush()
                val sb = StringBuilder()
                while (true) {
                    val line = pr.readLine() ?: throw IllegalStateException("root shell 意外退出")
                    if (line.startsWith(tag)) {
                        val code = line.substring(tag.length).trim().toIntOrNull() ?: -1
                        return Res(code, sb.toString().trimEnd('\n'))
                    }
                    sb.append(line).append('\n')
                }
            } catch (t: Throwable) {
                try { proc?.destroy() } catch (_: Throwable) { }
                proc = null
                if (attempt == 1) throw t
            }
        }
        throw IllegalStateException("unreachable")
    }

    /** 失败就抛异常，带上命令输出 */
    fun run(cmd: String): String {
        val res = exec(cmd)
        if (!res.ok) throw java.io.IOException(res.out.ifBlank { "命令失败($cmd)" }.lines().last())
        return res.out
    }

    private fun suArgs(script: String): Array<String> =
        (suCmd ?: arrayOf("su")) + arrayOf("-c", script)

    /** 以 root 读一个文件（独立进程，适合大文件流式读取） */
    fun openRead(path: String): InputStream {
        val p = ProcessBuilder(*suArgs("cat " + q(path))).start()
        return object : java.io.FilterInputStream(p.inputStream) {
            override fun close() {
                try { super.close() } finally {
                    try { p.waitFor(5, TimeUnit.SECONDS) } catch (_: Throwable) { }
                    p.destroy()
                }
            }
        }
    }

    /** 以 root 写一个文件；close() 时等写完并检查退出码 */
    fun openWrite(path: String): OutputStream {
        val p = ProcessBuilder(*suArgs("cat > " + q(path))).redirectErrorStream(true).start()
        return object : java.io.FilterOutputStream(p.outputStream) {
            override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len) }
            override fun close() {
                super.close()
                p.waitFor(60, TimeUnit.SECONDS)
                if (p.exitValue() != 0) throw java.io.IOException("写入失败：$path")
            }
        }
    }

    fun exists(path: String) = exec("[ -e ${q(path)} ]").ok

    /** 返回 (修改时间秒, 大小字节)，文件不存在返回 null */
    fun stat(path: String): Pair<Long, Long>? {
        val res = exec("stat -c '%Y %s' ${q(path)}")
        if (!res.ok) return null
        val p = res.out.trim().split(" ")
        return if (p.size == 2) Pair(p[0].toLongOrNull() ?: 0, p[1].toLongOrNull() ?: 0) else null
    }

    fun sha256(path: String): String {
        val out = run("sha256sum ${q(path)}")
        val h = out.trim().split(Regex("\\s+")).firstOrNull() ?: ""
        if (h.length != 64) throw java.io.IOException("校验值读取失败：$path")
        return h
    }

    fun setMtime(path: String, sec: Long) {
        run("touch -m -d @$sec ${q(path)}")
    }
}
