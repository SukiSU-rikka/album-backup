package cn.rikka.album

import android.os.Handler
import android.os.Looper

/** 后台任务的实时状态，界面订阅它刷新 */
data class Status(
    val busy: Boolean = false,
    val task: String = "",       // 当前在做什么：同步 / 整理 / 还原 / 导出 / 校验
    val detail: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val waiting: Int = 0,        // 还在等待延迟期的新文件数
    val note: String = "",       // 最近一次结果
    val root: Boolean? = null    // null = 还没检测
)

object Bus {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<(Status) -> Unit>()
    @Volatile var status = Status(); private set

    fun set(s: Status) {
        status = s
        main.post { listeners.toList().forEach { it(s) } }
    }

    fun update(f: (Status) -> Status) = synchronized(this) { set(f(status)) }

    fun listen(l: (Status) -> Unit) { listeners.add(l); l(status) }
    fun unlisten(l: (Status) -> Unit) { listeners.remove(l) }
}

class Halt(msg: String) : Exception(msg)
