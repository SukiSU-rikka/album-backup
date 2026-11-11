package cn.rikka.album

import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** 工具：整理、导出、还原、校验、日志 */
class ToolsPage(private val a: MainActivity) : Page {
    private val ui = a.ui
    private lateinit var exportInfo: TextView
    private lateinit var restoreInfo: TextView
    private lateinit var clearBox: CheckBox

    private fun tool(title: String, desc: String, f: LinearLayout.() -> Unit) = ui.card {
        addView(ui.tv(title, 16f, ui.text, true))
        addView(ui.tv(desc, 13f, ui.sub) { setPadding(0, ui.dp(4), 0, ui.dp(12)) })
        f()
    }

    override val view: View = ScrollView(a).apply {
        addView(ui.vbox(16) {
            addView(ui.title("工具"))
            addView(ui.subtitle("所有操作都在后台排队执行，首页能看到进度，可以随时停止。"))

            addView(tool("整理相册文件夹",
                "把散在乱七八糟文件夹里的照片视频移动到干净的文件夹（相机、截图、微信、QQ…）。是移动不是复制，只动已备份并校验过的文件，修改时间原样保留，相册排序不变。旧文件夹里一个文件都不剩了才会删掉。") {
                addView(ui.primary("选择要整理的文件夹") { a.push(OrganizePage(a)) })
            })

            addView(tool("导出到 U 盘 / 电脑",
                "把备份复制到 U 盘，或手机存储里的文件夹（再用数据线拷到电脑）。会建一个「${Exporter.DIR_NAME}」目录，里面带 .nomedia，插着 U 盘时相册也不会显示。已有的自动跳过。") {
                exportInfo = ui.tv("", 12.5f, ui.sub) { setPadding(0, 0, 0, ui.dp(8)) }
                addView(exportInfo)
                clearBox = CheckBox(a).apply {
                    text = "导出后清空手机上的暂存（只清校验通过的）"
                    setTextColor(ui.text); textSize = 13.5f
                    buttonTintList = android.content.res.ColorStateList.valueOf(ui.accent)
                }
                addView(clearBox)
                addView(ui.primary("选择 U 盘或文件夹") {
                    val clear = clearBox.isChecked
                    a.pickFolder { uri ->
                        val go = { Worker.job(a, "导出") { Exporter.export(a, uri, clear) { Worker.cancel }.toString() } }
                        if (clear) ui.confirm("导出后清空暂存", "导出并校验通过的文件会从手机上的备份目录删掉，只留在 U 盘上。以后要还原这些文件需要插上这个 U 盘。", "继续") { go() }
                        else go()
                    }
                }, ui.lp(t = 8))
            }, ui.lp(t = 12))

            addView(tool("还原到本机",
                "从手机上的备份放回相册（比如误删了一批、清了相册数据）。手机上已有的会比对校验值后跳过，放回后写回修改时间并核对数量和时间。") {
                restoreInfo = ui.tv("", 12.5f, ui.sub) { setPadding(0, 0, 0, ui.dp(8)) }
                addView(restoreInfo)
                addView(ui.primary("选择范围并还原") { restoreLocal() })
            }, ui.lp(t = 12))

            addView(tool("从 U 盘 / 文件夹还原",
                "换新手机、刷机后用。选 U 盘或之前导出的「${Exporter.DIR_NAME}」文件夹。新手机没 root 也能还原，只是写回时间要走系统接口，可靠性不如 root，建议先 root 再还原。") {
                addView(ui.primary("选择 U 盘或文件夹") {
                    a.pickFolder { uri -> Worker.job(a, "还原") { Restorer.restoreExternal(a, uri) { Worker.cancel }.toString() } }
                })
            }, ui.lp(t = 12))

            addView(tool("校验全部备份",
                "重新计算每个备份文件的校验值。发现损坏时，如果原图还在会自动重新复制。") {
                addView(ui.secondary("开始校验") { Worker.job(a, "校验") { Worker.verifyAll(a) } })
            }, ui.lp(t = 12))

            addView(tool("日志", "出问题时看看是哪个文件、为什么失败。") {
                addView(ui.secondary("查看日志") { a.push(LogPage(a)) })
            }, ui.lp(t = 12))
        })
    }

    private fun restoreLocal() {
        val years = Restorer.years(a)
        if (years.isEmpty()) { ui.info("还原到本机", "还没有备份。"); return }
        val labels = listOf("全部") + years.map { "$it 年" }
        ui.choose("还原哪些", labels, -1) { i ->
            val y = if (i == 0) null else years[i - 1]
            ui.confirm("还原到本机", "把${if (y == null) "全部" else "$y 年的"}照片视频放回相册原位置，手机上已有的跳过。", "开始还原") {
                Worker.job(a, "还原") { Restorer.restoreLocal(a, y) { Worker.cancel }.toString() }
            }
        }
    }

    override fun refresh() {
        val p = Prefs(a); val db = Db.get(a)
        exportInfo.text = "上次导出：${Fmt.ago(p.lastExport)} · 未导出 ${db.countUnexported()} 个"
        restoreInfo.text = "手机上有 ${db.countActive()} 个备份可还原" +
            (db.countOffloaded().let { if (it > 0) "，$it 个只在 U 盘上" else "" })
    }
}

/** 整理相册文件夹：列出所有放照片视频的文件夹，逐个选归到哪一类 */
class OrganizePage(private val a: MainActivity) : Page {
    private val ui = a.ui
    private val listBox = ui.vbox()
    private lateinit var go: TextView
    private var folders: List<Organizer.Folder> = emptyList()
    private var targets: Map<Organizer.Cat, String> = emptyMap()

    override val view: View = ui.vbox {
        setBackgroundColor(ui.bg)
        isClickable = true
        addView(ui.hbox {
            setPadding(ui.dp(8), ui.dp(12), ui.dp(16), 0)
            addView(ui.icon(R.drawable.ic_back, ui.text).apply {
                setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
                layoutParams = LinearLayout.LayoutParams(ui.dp(40), ui.dp(40))
                background = ui.ripple(null, 20); setOnClickListener { a.back() }
            })
            addView(ui.tv("整理相册文件夹", 20f, ui.text, true) { setPadding(ui.dp(8), 0, 0, 0) })
        })
        addView(ui.tv("点右边的类别可以修改归到哪，选「不动」的文件夹不会被碰；嫌麻烦就点「一键全部整理」。没备份的文件会先跳过，等备份完再整理。", 13f, ui.sub) {
            setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8))
        })
        addView(ScrollView(a).apply {
            addView(listBox.apply { setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(16)) })
        }, ui.lp(h = 0, weight = 1f))
        go = ui.primary("整理选中的") { start() }
        addView(ui.hbox {
            addView(ui.secondary("一键全部整理") { startAll() }, ui.lp(0, weight = 1f, r = 6))
            addView(go, ui.lp(0, weight = 1f, l = 6))
        }, ui.lp(l = 16, r = 16, t = 4, b = 16))
    }

    override fun refresh() {
        listBox.removeAllViews()
        listBox.addView(ui.tv("正在读取相册…", 14f, ui.sub) { gravity = Gravity.CENTER; setPadding(0, ui.dp(40), 0, 0) })
        Thread {
            val f = Organizer.folders(a)
            val t = Organizer.targets(Media.scan(a, true))
            a.runOnUiThread { folders = f; targets = t; render() }
        }.start()
    }

    private fun render() {
        listBox.removeAllViews()
        val messy = folders.filter { !it.clean }
        val clean = folders.filter { it.clean }
        if (messy.isNotEmpty()) listBox.addView(ui.section("需要整理的文件夹（${messy.size}）"))
        val card = ui.card(0) {}
        messy.forEachIndexed { i, f ->
            if (i > 0) card.addView(ui.divider())
            lateinit var chip: TextView
            card.addView(ui.hbox {
                setPadding(ui.dp(16), ui.dp(12), ui.dp(12), ui.dp(12))
                addView(ui.vbox {
                    addView(ui.tv(f.rel.trimEnd('/'), 14.5f, ui.text).also { ui.ellipsize(it, 2) })
                    addView(ui.tv("${f.count} 个 · ${Fmt.size(f.bytes)}", 12f, ui.sub) { setPadding(0, ui.dp(2), 0, 0) })
                }, ui.lp(0, weight = 1f))
                chip = ui.chip(label(f.cat), if (f.cat == Organizer.Cat.KEEP) ui.sub else ui.accent,
                    if (f.cat == Organizer.Cat.KEEP) ui.surface2 else ui.accentSoft) {
                    val cats = Organizer.Cat.values().toList()
                    ui.choose(f.rel.trimEnd('/'), cats.map { c -> if (c == Organizer.Cat.KEEP) "不动" else "${c.label} → ${targets[c]?.trimEnd('/')}" },
                        cats.indexOf(f.cat)) { w ->
                        f.cat = cats[w]
                        chip.text = label(f.cat)
                        chip.setTextColor(if (f.cat == Organizer.Cat.KEEP) ui.sub else ui.accent)
                        chip.background = ui.ripple(ui.round(if (f.cat == Organizer.Cat.KEEP) ui.surface2 else ui.accentSoft, 20), 20)
                        updateGo()
                    }
                }
                addView(chip)
            })
        }
        if (messy.isNotEmpty()) listBox.addView(card)
        else listBox.addView(ui.tv("相册文件夹都很干净，没有需要整理的。", 14f, ui.sub) { gravity = Gravity.CENTER; setPadding(0, ui.dp(40), 0, 0) })

        if (clean.isNotEmpty()) {
            listBox.addView(ui.section("已经是整理好的文件夹"))
            listBox.addView(ui.card {
                clean.forEach { f -> addView(ui.tv("${f.rel.trimEnd('/')}  ·  ${f.count} 个", 13f, ui.sub) { setPadding(0, ui.dp(3), 0, ui.dp(3)) }) }
            })
        }
        updateGo()
    }

    private fun label(c: Organizer.Cat) = if (c == Organizer.Cat.KEEP) "不动" else "→ ${c.label}"

    private fun updateGo() {
        val n = folders.count { !it.clean && it.cat != Organizer.Cat.KEEP }
        go.text = if (n > 0) "整理选中的（$n）" else "整理选中的"
        go.alpha = if (n > 0) 1f else 0.4f
    }

    /** 一键全部整理：所有需要整理的文件夹都归类，认不出来源的归到「其他」 */
    private fun startAll() {
        val messy = folders.filter { !it.clean }
        if (messy.isEmpty()) { ui.info("一键全部整理", "相册文件夹都很干净，没有需要整理的。"); return }
        val unknown = messy.count { it.cat == Organizer.Cat.KEEP }
        val files = messy.sumOf { it.count }
        val msg = "把 ${messy.size} 个文件夹里的 $files 个照片视频全部归位。" +
            (if (unknown > 0) "其中 $unknown 个认不出来源的文件夹会归到「${targets[Organizer.Cat.OTHER]?.trimEnd('/')}」。" else "") +
            "\n\n会先跑一次同步，确保都备份好了；没备份成功的文件不会被移动。"
        ui.confirm("一键全部整理", msg, "全部整理") {
            val plan = messy.associate { it.rel to (if (it.cat == Organizer.Cat.KEEP) Organizer.Cat.OTHER else it.cat) }
            run(plan)
        }
    }

    private fun run(plan: Map<String, Organizer.Cat>) {
        Worker.job(a, "整理") {
            try { SyncEngine(a) { Worker.cancel }.run() } catch (h: Halt) { throw h } catch (t: Throwable) { Lg.e("整理前同步出错", t) }
            Organizer.tidy(a, plan) { Worker.cancel }.toString()
        }
        a.back()
    }

    private fun start() {
        val plan = folders.filter { !it.clean && it.cat != Organizer.Cat.KEEP }.associate { it.rel to it.cat }
        if (plan.isEmpty()) return
        val files = folders.filter { it.rel in plan }.sumOf { it.count }
        ui.confirm("开始整理", "移动 ${plan.size} 个文件夹里的 $files 个照片视频。会先跑一次同步，确保都备份好了。", "开始") { run(plan) }
    }
}

/** 日志页 */
class LogPage(private val a: MainActivity) : Page {
    private val ui = a.ui
    private val box = ui.vbox()

    override val view: View = ui.vbox {
        setBackgroundColor(ui.bg)
        isClickable = true
        addView(ui.hbox {
            setPadding(ui.dp(8), ui.dp(12), ui.dp(16), ui.dp(8))
            addView(ui.icon(R.drawable.ic_back, ui.text).apply {
                setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
                layoutParams = LinearLayout.LayoutParams(ui.dp(40), ui.dp(40))
                background = ui.ripple(null, 20); setOnClickListener { a.back() }
            })
            addView(ui.tv("日志", 20f, ui.text, true) { setPadding(ui.dp(8), 0, 0, 0) }, ui.lp(0, weight = 1f))
            addView(ui.chip("只看错误") { render(true) })
            addView(ui.chip("清空", ui.sub, ui.surface2) {
                ui.confirm("清空日志", "确定清空全部日志？") { Lg.clear(); render(false) }
            }, ui.lp(-2, l = 8))
        })
        addView(ScrollView(a).apply {
            addView(box.apply { setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(16)) })
        }, ui.lp(h = 0, weight = 1f))
    }

    override fun refresh() = render(false)

    private fun render(errorsOnly: Boolean) {
        box.removeAllViews()
        val lines = Lg.tail().filter { !errorsOnly || it.contains(" E ") || it.contains(" W ") }
        if (lines.isEmpty()) {
            box.addView(ui.tv("没有记录", 14f, ui.sub) { gravity = Gravity.CENTER; setPadding(0, ui.dp(40), 0, 0) })
            return
        }
        val card = ui.card(12) {}
        for (l in lines.take(500)) {
            val color = when { l.contains(" E ") -> ui.warn; l.contains(" W ") -> ui.accent; else -> ui.text }
            card.addView(ui.tv(l, 12f, color) {
                typeface = android.graphics.Typeface.MONOSPACE; setPadding(0, ui.dp(3), 0, ui.dp(3)); setTextIsSelectable(true)
            })
        }
        box.addView(card)
    }
}
