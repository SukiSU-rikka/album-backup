package cn.rikka.album

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast

/** 一个页面：视图 + 刷新 */
interface Page {
    val view: View
    fun refresh() {}
    fun onStatus(s: Status) {}
}

class MainActivity : Activity() {
    lateinit var ui: Ui
    private lateinit var content: FrameLayout
    private lateinit var tabs: List<Page>
    private val tabViews = ArrayList<LinearLayout>()
    private var current = 0
    /** 叠在上面的子页面（日志、整理文件夹） */
    private val stack = ArrayList<Page>()
    private var pickCb: ((Uri) -> Unit)? = null

    private val statusListener: (Status) -> Unit = { s ->
        tabs.forEach { it.onStatus(s) }
        stack.forEach { it.onStatus(s) }
        s.note.takeIf { it.isNotEmpty() && it != lastNote && !s.busy }?.let {
            lastNote = it
            if (resumed) Toast.makeText(this, it.lines().first(), Toast.LENGTH_SHORT).show()
            tabs.forEach { p -> p.refresh() }
        }
    }
    private var lastNote = ""
    private var resumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lg.init(this)
        ui = Ui(this)
        lastNote = Bus.status.note

        tabs = listOf(HomePage(this), PendingPage(this), ToolsPage(this), SettingsPage(this))
        content = FrameLayout(this)
        val nav = ui.hbox {
            setBackgroundColor(ui.surface)
            setPadding(0, ui.dp(6), 0, ui.dp(6))
            elevation = ui.dp(8).toFloat()
        }
        val names = listOf("首页" to R.drawable.ic_tab_home, "待删除" to R.drawable.ic_tab_pending,
            "工具" to R.drawable.ic_tab_tools, "设置" to R.drawable.ic_tab_settings)
        names.forEachIndexed { i, (label, icon) ->
            val t = ui.vbox {
                gravity = Gravity.CENTER
                setPadding(0, ui.dp(6), 0, ui.dp(4))
                val pill = ui.frame {
                    setPadding(ui.dp(18), ui.dp(4), ui.dp(18), ui.dp(4))
                    addView(ui.icon(icon, ui.sub, 22))
                }
                addView(pill, LinearLayout.LayoutParams(-2, -2))
                addView(ui.tv(label, 12f, ui.sub) { gravity = Gravity.CENTER; setPadding(0, ui.dp(3), 0, 0) })
                background = ui.ripple(null, 16)
                setOnClickListener { show(i) }
            }
            tabViews.add(t)
            nav.addView(t, ui.lp(0, weight = 1f))
        }
        setContentView(ui.vbox {
            setBackgroundColor(ui.bg)
            addView(content, ui.lp(h = 0, weight = 1f))
            addView(nav)
        })
        show(0)

        // 申请权限（有 root 时同步服务会自己授权，这里是没 root 时的后备）
        val need = if (Build.VERSION.SDK_INT >= 33)
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.POST_NOTIFICATIONS)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)

        if (Prefs(this).syncOn) SyncService.start(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        tabs.forEach { it.refresh() }
        if (Prefs(this).syncOn) SyncService.start(this)
    }

    fun show(i: Int) {
        while (stack.isNotEmpty()) pop()
        current = i
        content.removeAllViews()
        content.addView(tabs[i].view)
        tabs[i].refresh()
        tabViews.forEachIndexed { idx, t ->
            val on = idx == i
            val pill = t.getChildAt(0) as FrameLayout
            pill.background = if (on) ui.round(ui.accentSoft, 16) else null
            (pill.getChildAt(0) as android.widget.ImageView).imageTintList = ColorStateList.valueOf(if (on) ui.accent else ui.sub)
            (t.getChildAt(1) as android.widget.TextView).setTextColor(if (on) ui.text else ui.sub)
        }
    }

    fun push(p: Page) {
        stack.add(p)
        content.addView(p.view)
        p.refresh()
        p.onStatus(Bus.status)
    }

    private fun pop() {
        val p = stack.removeAt(stack.size - 1)
        content.removeView(p.view)
    }

    fun back() {
        if (stack.isNotEmpty()) { pop(); tabs[current].refresh() }
    }

    @Deprecated("framework Activity")
    override fun onBackPressed() {
        when {
            stack.isNotEmpty() -> back()
            current != 0 -> show(0)
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    /** 打开系统文件夹选择器（选 U 盘或文件夹） */
    fun pickFolder(cb: (Uri) -> Unit) {
        pickCb = cb
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 7)
        } catch (t: Throwable) {
            Toast.makeText(this, "打不开文件夹选择器", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("framework Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == 7 && resultCode == RESULT_OK && uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (_: Throwable) { }
            pickCb?.invoke(uri)
        }
        pickCb = null
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        Bus.listen(statusListener)
        tabs[current].refresh()
    }

    override fun onPause() {
        resumed = false
        Bus.unlisten(statusListener)
        super.onPause()
    }
}
