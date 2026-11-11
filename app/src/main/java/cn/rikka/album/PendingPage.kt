package cn.rikka.album

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/** 待删除：相册里删了、备份还在倒计时的照片视频。点「放回」恢复到原位置 */
class PendingPage(private val a: MainActivity) : Page {
    private val ui = a.ui
    private var items: List<Item> = emptyList()
    private lateinit var sub: TextView
    private lateinit var allBtn: TextView
    private lateinit var empty: TextView
    private val list = ListView(a)

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = items[p].id

        override fun getView(pos: Int, convert: View?, parent: ViewGroup?): View {
            val row = (convert as? LinearLayout) ?: makeRow()
            val it = items[pos]
            val h = row.tag as Holder
            Thumbs.load(a, it, h.img)
            h.video.visibility = if (it.video) View.VISIBLE else View.GONE
            h.name.text = it.name
            h.where.text = Media.userPath(it.folder) + " · " + Fmt.size(it.size)
            h.left.text = Fmt.daysLeft(it.dueAt)
            val soon = it.dueAt - System.currentTimeMillis() < 3 * 86_400_000L
            h.left.setTextColor(if (soon) ui.warn else ui.sub)
            h.back.setOnClickListener { _ -> restore(listOf(it)) }
            row.setOnLongClickListener { _ ->
                ui.confirm("立即删除备份", "「${it.name}」的备份会马上删掉，不能再放回相册。", "删除") {
                    Worker.job(a, "删除") { SyncEngine.purge(a, it.id); "已删除 ${it.name}" }
                }
                true
            }
            return row
        }
    }

    private class Holder(val img: ImageView, val video: ImageView, val name: TextView, val where: TextView, val left: TextView, val back: TextView)

    private fun makeRow(): LinearLayout {
        val img = ImageView(a).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = ui.round(ui.surface2, 10); clipToOutline = true
        }
        val video = ui.icon(R.drawable.ic_video, 0xFFFFFFFF.toInt(), 18).apply {
            background = ui.round(0x66000000, 12); setPadding(ui.dp(2), ui.dp(2), ui.dp(2), ui.dp(2))
        }
        val thumb = ui.frame {
            addView(img, android.widget.FrameLayout.LayoutParams(-1, -1))
            addView(video, android.widget.FrameLayout.LayoutParams(ui.dp(22), ui.dp(22), Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, ui.dp(4), ui.dp(4))
            })
        }
        val name = ui.tv("", 14.5f, ui.text, true).also { ui.ellipsize(it) }
        val where = ui.tv("", 12f, ui.sub).also { ui.ellipsize(it) }
        val left = ui.tv("", 12f, ui.sub, true)
        val back = ui.chip("放回") {}
        val row = ui.hbox {
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            background = ui.ripple(ui.round(ui.surface, 16), 16)
            addView(thumb, LinearLayout.LayoutParams(ui.dp(60), ui.dp(60)))
            addView(ui.vbox {
                setPadding(ui.dp(12), 0, ui.dp(8), 0)
                addView(name); addView(where, ui.lp(t = 2)); addView(left, ui.lp(t = 4))
            }, ui.lp(0, weight = 1f))
            addView(back)
        }
        row.tag = Holder(img, video, name, where, left, back)
        return row
    }

    override val view: View = ui.vbox {
        setPadding(ui.dp(16), 0, ui.dp(16), 0)
        addView(ui.hbox {
            addView(ui.title("待删除"), ui.lp(0, weight = 1f))
            allBtn = ui.chip("全部放回") {
                ui.confirm("全部放回", "把 ${items.size} 个文件放回相册原来的位置，时间和删除前一样。") { restore(items) }
            }
            addView(allBtn, ui.lp(-2, t = 14))
        })
        sub = ui.subtitle("")
        addView(sub)
        empty = ui.tv("没有待删除的文件\n\n在相册里删掉的照片视频会出现在这里，到期前都能放回。", 14f, ui.sub) {
            gravity = Gravity.CENTER; setPadding(ui.dp(24), ui.dp(80), ui.dp(24), 0)
        }
        addView(empty)
        list.apply {
            divider = null; dividerHeight = ui.dp(10)
            selector = android.graphics.drawable.ColorDrawable(0)
            clipToPadding = false; setPadding(0, 0, 0, ui.dp(16))
            adapter = this@PendingPage.adapter
        }
        addView(list, ui.lp(h = 0, weight = 1f))
    }

    private fun restore(list: List<Item>) {
        val ids = list.map { it.id }
        Worker.job(a, "放回") {
            var ok = 0; var fail = 0; var last = ""
            for ((i, id) in ids.withIndex()) {
                if (Worker.cancel) break
                Bus.update { it.copy(done = i, total = ids.size) }
                try { last = Restorer.restoreOne(a, id); ok++ } catch (t: Throwable) { fail++; last = t.message ?: ""; Lg.e("放回失败", t) }
            }
            if (ids.size == 1) last else "放回 $ok 个" + (if (fail > 0) "，失败 $fail 个（$last）" else "")
        }
    }

    override fun refresh() {
        items = Db.get(a).pending()
        val keep = Prefs(a).keepDays
        sub.text = "相册里删掉的照片视频，备份会保留 $keep 天后自动删除。点「放回」恢复到原来的位置，长按可立即删除。"
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        allBtn.visibility = if (items.size > 1) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }
}
