package cn.rikka.album

import android.content.Context
import android.content.SharedPreferences

/** 所有设置项。默认值就是和用户商量定下来的方案 */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun b(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun i(k: String, d: Int) = sp.getInt(k, d)
    private fun l(k: String, d: Long) = sp.getLong(k, d)
    private fun put(f: SharedPreferences.Editor.() -> Unit) = sp.edit().apply(f).apply()

    /** 实时同步总开关 */
    var syncOn: Boolean get() = b("sync_on", true); set(v) = put { putBoolean("sync_on", v) }
    /** 被安全检查拦下后的暂停（需要用户手动解除） */
    var haltReason: String get() = sp.getString("halt", "") ?: ""; set(v) = put { putString("halt", v) }
    /** 新文件出现后等多少分钟再备份 */
    var delayMin: Int get() = i("delay_min", 3); set(v) = put { putInt("delay_min", v) }
    /** 相册里删除后，备份保留多少天 */
    var keepDays: Int get() = i("keep_days", 30); set(v) = put { putInt("keep_days", v) }
    /** 小于多少 KB 的图片不备份（0 = 不限） */
    var minKb: Int get() = i("min_kb", 0); set(v) = put { putInt("min_kb", v) }
    /** 长边小于多少像素的图片不备份（0 = 不限），用来过滤表情包、小图标 */
    var minEdge: Int get() = i("min_edge", 0); set(v) = put { putInt("min_edge", v) }
    /** 是否备份视频 */
    var videos: Boolean get() = b("videos", true); set(v) = put { putBoolean("videos", v) }
    /** 手机剩余空间低于多少 GB 时暂停备份 */
    var reserveGb: Int get() = i("reserve_gb", 3); set(v) = put { putInt("reserve_gb", v) }
    /** 多少天没导出就提醒（0 = 不提醒） */
    var exportRemindDays: Int get() = i("export_remind", 30); set(v) = put { putInt("export_remind", v) }
    /** 自动整理微信/QQ 的乱文件夹 */
    var autoTidy: Boolean get() = b("auto_tidy", false); set(v) = put { putBoolean("auto_tidy", v) }
    /** 开机自启 */
    var bootStart: Boolean get() = b("boot", true); set(v) = put { putBoolean("boot", v) }
    /** 排除的来源文件夹（相对路径，如 "Pictures/表情包/"） */
    var excluded: Set<String>
        get() = sp.getStringSet("excluded", emptySet())!!.toSet()
        set(v) = put { putStringSet("excluded", v) }

    // ------- 运行状态（不是设置，但也存在这里）
    var firstDone: Boolean get() = b("first_done", false); set(v) = put { putBoolean("first_done", v) }
    var lastSync: Long get() = l("last_sync", 0); set(v) = put { putLong("last_sync", v) }
    var lastExport: Long get() = l("last_export", 0); set(v) = put { putLong("last_export", v) }
    var lastRemind: Long get() = l("last_remind", 0); set(v) = put { putLong("last_remind", v) }
}
