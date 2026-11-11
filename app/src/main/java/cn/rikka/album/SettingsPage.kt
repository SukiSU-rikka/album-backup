package cn.rikka.album

import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

/** 设置 */
class SettingsPage(private val a: MainActivity) : Page {
    private val ui = a.ui
    private val p = Prefs(a)
    private val rows = ArrayList<View>()

    private fun group(title: String, vararg items: View) = ui.vbox {
        addView(ui.section(title))
        addView(ui.card(0) {
            items.forEachIndexed { i, v -> if (i > 0) addView(ui.divider()); addView(v) }
        })
    }

    private fun pick(title: String, values: List<Int>, label: (Int) -> String, get: () -> Int, set: (Int) -> Unit, desc: String = "") =
        ui.row(title, desc, { label(get()) }) {
            ui.choose(title, values.map(label), values.indexOf(get())) { i -> set(values[i]); refresh() }
        }.also { rows.add(it) }

    override val view: View = ScrollView(a).apply {
        addView(ui.vbox(16) {
            addView(ui.title("设置"))

            addView(group("同步",
                ui.switchRow("实时同步", "新拍的照片、新存的图几分钟内自动备份", { p.syncOn }) {
                    p.syncOn = it; if (it) SyncService.start(a) else SyncService.stop(a)
                },
                ui.switchRow("开机自启", "开机后自动开始同步", { p.bootStart }) { p.bootStart = it },
                ui.switchRow("备份视频", "关闭后只备份照片", { p.videos }) { p.videos = it },
                pick("延迟备份", listOf(0, 1, 3, 5, 10, 30), { if (it == 0) "立即" else "$it 分钟" }, { p.delayMin }, { p.delayMin = it },
                    "新图出现后等一会儿再备份，截完图马上删的不会进备份")
            ))

            addView(group("删除",
                pick("删除后保留", listOf(7, 15, 30, 60, 90), { "$it 天" }, { p.keepDays }, { p.keepDays = it },
                    "相册里删掉后，备份倒计时这么久再自动删（只影响之后删除的）")
            ))

            addView(group("排除",
                ui.row("排除的文件夹", "这些文件夹里的图不备份（表情包、App 缓存图等）", { if (p.excluded.isEmpty()) "无" else "${p.excluded.size} 个" }) {
                    chooseExcluded()
                }.also { rows.add(it) },
                pick("最小文件大小", listOf(0, 10, 30, 50, 100), { if (it == 0) "不限" else "$it KB" }, { p.minKb }, { p.minKb = it },
                    "小于这个大小的图片不备份"),
                pick("最小图片尺寸", listOf(0, 200, 400, 600), { if (it == 0) "不限" else "$it 像素" }, { p.minEdge }, { p.minEdge = it },
                    "长边小于这个像素的图片不备份，用来过滤小图标")
            ))

            addView(group("空间与导出",
                pick("空间保护", listOf(1, 2, 3, 5, 10), { "剩 $it GB 时暂停" }, { p.reserveGb }, { p.reserveGb = it },
                    "手机剩余空间低于这个值时暂停备份并提醒，不会把手机塞满"),
                pick("导出提醒", listOf(0, 7, 14, 30, 60), { if (it == 0) "不提醒" else "$it 天" }, { p.exportRemindDays }, { p.exportRemindDays = it },
                    "这么久没导出到 U 盘/电脑就提醒（刷机会清空手机上的备份）")
            ))

            addView(group("整理",
                ui.switchRow("自动整理", "微信、QQ 新建的乱文件夹里的图定期自动归位，不用手动点", { p.autoTidy }) { p.autoTidy = it }
            ))

            addView(group("关于",
                ui.row("备份位置", "只有 root 能访问，系统相册和媒体库读不到", { Store.DIR }) {
                    ui.info("备份位置", "${Store.DIR}\n\n文件按 年/月 存放，不压缩，修改时间原样保留。清单 ${Store.MANIFEST} 记录了每个文件的拍摄时间、修改时间、原路径和校验值。\n\n注意：刷机或恢复出厂会清空 /data，请定期导出。")
                },
                ui.row("版本", "", { a.packageManager.getPackageInfo(a.packageName, 0).versionName ?: "" }) {}
            ))
        })
    }

    private fun chooseExcluded() {
        Thread {
            val scan = Media.scan(a, true)
            val rels = scan.groupingBy { it.rel }.eachCount().entries.sortedByDescending { it.value }
            val cur = p.excluded
            // 已排除但现在没有文件的也保留在列表里，方便取消
            val all = rels.map { it.key to it.value } + cur.filter { c -> rels.none { it.key == c } }.map { it to 0 }
            a.runOnUiThread {
                val checked = BooleanArray(all.size) { all[it].first in cur }
                ui.multi("排除的文件夹", all.map { "${it.first.trimEnd('/')}  (${it.second})" }, checked) { res ->
                    p.excluded = all.filterIndexed { i, _ -> res[i] }.map { it.first }.toSet()
                    refresh()
                }
            }
        }.start()
    }

    override fun refresh() {
        rows.forEach { (it.tag as? Runnable)?.run() }
    }
}
