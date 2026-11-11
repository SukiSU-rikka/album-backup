package cn.rikka.album

import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/** 首页：胶片条（最近备份）+ 状态 + 统计 + 立即同步 */
class HomePage(private val a: MainActivity) : Page {
    private val ui = a.ui
    private val film = FilmStripView(a)
    private lateinit var pill: TextView
    private lateinit var haltBox: LinearLayout
    private lateinit var haltText: TextView
    private lateinit var remindBox: LinearLayout
    private lateinit var remindText: TextView
    private lateinit var busyBox: LinearLayout
    private lateinit var busyTitle: TextView
    private lateinit var busyDetail: TextView
    private lateinit var bar: ProgressBar
    private lateinit var note: TextView
    private lateinit var toggle: TextView
    private val stats = HashMap<String, Pair<TextView, TextView>>()
    private var filmKey = ""

    override val view: View = ScrollView(a).apply {
        isFillViewport = true
        addView(ui.vbox(16) {
            addView(ui.hbox {
                setPadding(ui.dp(4), ui.dp(18), ui.dp(4), ui.dp(14))
                addView(ui.tv("相册备份", 26f, ui.text, true), ui.lp(0, weight = 1f))
                pill = ui.tv("", 12.5f, ui.ok, true) { setPadding(ui.dp(12), ui.dp(5), ui.dp(12), ui.dp(5)) }
                addView(pill)
            })
            addView(film)

            // 安全停止报警
            haltBox = ui.vbox(16) {
                background = ui.round(ui.warnSoft, 18)
                addView(ui.tv("同步已停止", 15.5f, ui.warn, true))
                haltText = ui.tv("", 13.5f, ui.text) { setPadding(0, ui.dp(6), 0, ui.dp(12)) }
                addView(haltText)
                addView(ui.hbox {
                    addView(ui.chip("我检查过了，继续同步", ui.warn, ui.surface) {
                        ui.confirm("继续同步", "确认相册里没有出现重复的照片视频后再继续。") {
                            Prefs(a).apply { haltReason = ""; syncOn = true; firstDone = true }
                            Notify.cancel(a, Notify.ID_HALT)
                            SyncService.start(a)
                            refresh()
                        }
                    })
                    addView(ui.chip("看日志", ui.warn, ui.surface) { a.push(LogPage(a)) }, ui.lp(-2, l = 8))
                })
            }
            addView(haltBox, ui.lp(t = 12))

            // 导出提醒
            remindBox = ui.vbox(16) {
                background = ui.round(ui.accentSoft, 18)
                remindText = ui.tv("", 13.5f, ui.text) { setPadding(0, 0, 0, ui.dp(10)) }
                addView(remindText)
                addView(ui.hbox { addView(ui.chip("去导出", ui.accent, ui.surface) { a.show(2) }) })
            }
            addView(remindBox, ui.lp(t = 12))

            // 进行中
            busyBox = ui.card {
                busyTitle = ui.tv("", 15f, ui.text, true)
                addView(ui.hbox {
                    addView(busyTitle, ui.lp(0, weight = 1f))
                    addView(ui.chip("停止", ui.sub, ui.surface2) { Worker.cancel = true })
                })
                bar = ui.progress()
                addView(bar, ui.lp(t = 10))
                busyDetail = ui.tv("", 12.5f, ui.sub) { setPadding(0, ui.dp(6), 0, 0) }
                ui.ellipsize(busyDetail)
                addView(busyDetail)
            }
            addView(busyBox, ui.lp(t = 12))

            // 统计 2×2
            fun stat(key: String, label: String) = ui.card(14) {
                addView(ui.tv(label, 12.5f, ui.sub))
                val v = ui.tv("–", 22f, ui.text, true) { setPadding(0, ui.dp(4), 0, 0) }
                val s = ui.tv("", 12f, ui.sub) { setPadding(0, ui.dp(2), 0, 0) }
                addView(v); addView(s)
                stats[key] = Pair(v, s)
            }
            addView(ui.hbox {
                addView(stat("count", "已备份"), ui.lp(0, weight = 1f, r = 6))
                addView(stat("size", "占用空间"), ui.lp(0, weight = 1f, l = 6))
            }, ui.lp(t = 12))
            addView(ui.hbox {
                addView(stat("pending", "待删除").apply { setOnClickListener { a.show(1) } }, ui.lp(0, weight = 1f, r = 6))
                addView(stat("last", "最近同步"), ui.lp(0, weight = 1f, l = 6))
            }, ui.lp(t = 12))

            note = ui.tv("", 13f, ui.sub) { setPadding(ui.dp(6), ui.dp(14), ui.dp(6), 0) }
            addView(note)

            addView(ui.hbox {
                addView(ui.primary("立即同步") {
                    val p = Prefs(a)
                    if (p.haltReason.isNotEmpty()) { ui.info("同步已停止", p.haltReason); return@primary }
                    if (!p.syncOn) Worker.job(a, "同步") { SyncEngine(a) { Worker.cancel }.run().let { "新备份 ${it.added} 个" } }
                    else { SyncService.start(a); Worker.sync(a) }
                }, ui.lp(0, weight = 1f, r = 6))
                toggle = ui.secondary("") {
                    val p = Prefs(a)
                    p.syncOn = !p.syncOn
                    if (p.syncOn) SyncService.start(a) else SyncService.stop(a)
                    refresh()
                }
                addView(toggle, ui.lp(0, weight = 1f, l = 6))
            }, ui.lp(t = 16))

            addView(ui.tv("备份存放在 ${Store.DIR}，只有 root 能访问，系统相册看不到，不会出现两份。", 12f, ui.sub) {
                setPadding(ui.dp(6), ui.dp(18), ui.dp(6), ui.dp(8)); gravity = Gravity.CENTER
            })
        })
    }

    override fun refresh() {
        val p = Prefs(a)
        val db = Db.get(a)
        val n = db.countActive(); val v = db.countActiveVideo()
        stats["count"]!!.let { it.first.text = "$n"; it.second.text = "照片 ${n - v} · 视频 $v" }
        val off = db.countOffloaded()
        stats["size"]!!.let { it.first.text = Fmt.size(db.phoneBytes()); it.second.text = if (off > 0) "另有 $off 个只在 U 盘上" else "手机剩余 ${Fmt.size(Store.freeBytes())}" }
        val pend = db.countPending()
        stats["pending"]!!.let { it.first.text = "$pend"; it.second.text = "删除后保留 ${p.keepDays} 天" }
        stats["last"]!!.let { it.first.text = Fmt.ago(p.lastSync); it.second.text = "延迟 ${if (p.delayMin == 0) "关闭" else "${p.delayMin} 分钟"}" }

        haltBox.visibility = if (p.haltReason.isNotEmpty()) View.VISIBLE else View.GONE
        haltText.text = p.haltReason
        val remind = Exporter.shouldRemind(a)
        remindBox.visibility = if (remind) View.VISIBLE else View.GONE
        if (remind) remindText.text = "${db.countUnexported()} 个备份还没导出过。刷机或恢复出厂会清空 /data，手机上的备份也会一起没，建议定期导出到 U 盘或电脑。"
        toggle.text = if (p.syncOn) "暂停同步" else "开启同步"
        onStatus(Bus.status)
        loadFilm()
    }

    private fun loadFilm() {
        val items = Db.get(a).recent(8)
        val key = items.joinToString { it.sha }
        if (key == filmKey) return
        filmKey = key
        val out = arrayOfNulls<Bitmap>(items.size)
        film.setFrames(items.map { Pair(null, it.video) })
        items.forEachIndexed { i, it ->
            Thumbs.get(a, it) { b ->
                out[i] = b
                film.setFrames(items.mapIndexed { j, x -> Pair(out[j], x.video) })
            }
        }
    }

    override fun onStatus(s: Status) {
        val p = Prefs(a)
        when {
            s.root == false -> { pill.text = "没有 root"; pill.setTextColor(ui.warn); pill.background = ui.round(ui.warnSoft, 20) }
            p.haltReason.isNotEmpty() -> { pill.text = "已停止"; pill.setTextColor(ui.warn); pill.background = ui.round(ui.warnSoft, 20) }
            !p.syncOn -> { pill.text = "已暂停"; pill.setTextColor(ui.sub); pill.background = ui.round(ui.surface2, 20) }
            else -> { pill.text = "● 实时同步中"; pill.setTextColor(ui.ok); pill.background = ui.round(ui.surface, 20) }
        }
        busyBox.visibility = if (s.busy) View.VISIBLE else View.GONE
        if (s.busy) {
            busyTitle.text = if (s.total > 0) "正在${s.task}  ${s.done}/${s.total}" else "正在${s.task}"
            busyDetail.text = s.detail
            bar.isIndeterminate = s.total == 0
            if (s.total > 0) bar.progress = (s.done * 1000L / s.total).toInt()
        }
        val parts = ArrayList<String>()
        if (s.note.isNotEmpty()) parts.add(s.note)
        if (s.waiting > 0 && !s.busy) parts.add("${s.waiting} 个新文件在等延迟期，过后自动备份")
        if (s.root == false) parts.add("请在 SukiSU / KernelSU 管理器里给「相册备份」授予 root")
        note.text = parts.joinToString("\n")
        note.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
    }
}
