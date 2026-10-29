package cn.rikka.album

data class Stats(
    val mode: String = "",
    val total: Int = 0,
    val done: Int = 0,
    val copied: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val bytesTotal: Long = 0,
    val bytesDone: Long = 0,
    val current: String = "",
    val finished: Boolean = false,
    val message: String = ""
)

/** 服务和界面之间共享的进度状态 */
object Bus {
    @Volatile var running = false
    @Volatile var cancel = false
    @Volatile var snapshot = Stats()
    @Volatile var listener: (() -> Unit)? = null

    fun publish(s: Stats) {
        snapshot = s
        listener?.invoke()
    }
}
