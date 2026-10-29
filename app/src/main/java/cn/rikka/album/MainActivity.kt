package cn.rikka.album

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val reqTree = 11
    private val reqPerm = 12

    private lateinit var prefs: SharedPreferences
    private lateinit var folderText: TextView
    private lateinit var layoutGroup: RadioGroup
    private lateinit var origCheck: CheckBox
    private lateinit var btnBackup: Button
    private lateinit var btnRestore: Button
    private lateinit var btnStop: Button
    private lateinit var bar: ProgressBar
    private lateinit var status: TextView

    private var treeUri: Uri? = null
    private var pending: (() -> Unit)? = null

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        treeUri = prefs.getString("tree", null)?.let { Uri.parse(it) }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(36), dp(20), dp(24))
        }

        fun label(text: String, size: Float, bold: Boolean = false, topMargin: Int = 0) =
            TextView(this).apply {
                this.text = text
                textSize = size
                if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
                col.addView(this, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, dp(topMargin), 0, 0) })
            }

        fun button(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
            col.addView(this, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(8), 0, 0) })
        }

        label("相册备份", 28f, true)
        label("备份照片和视频到你选的文件夹，并记录时间信息，恢复后相册排序和原来一致。", 14f, topMargin = 6)

        label("备份文件夹", 16f, true, 24)
        folderText = label("", 14f, topMargin = 4)
        button("选择文件夹") { pickFolder() }

        label("备份后的整理方式", 16f, true, 20)
        layoutGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            listOf(1 to "按日期（年/月）", 2 to "按来源（相机、截图、微信…）", 3 to "全部平铺在一个文件夹").forEach { (id, t) ->
                addView(RadioButton(context).apply { this.id = id; text = t })
            }
            check(prefs.getInt("layout", 1))
            setOnCheckedChangeListener { _, checked -> prefs.edit().putInt("layout", checked).apply() }
        }
        col.addView(layoutGroup)

        btnBackup = button("开始备份") { start("backup") }
        btnRestore = button("恢复到手机") { confirmRestore() }
        origCheck = CheckBox(this).apply {
            text = "恢复时按原来的文件夹放回（会重新变乱，一般不用勾）"
            textSize = 13f
        }
        col.addView(origCheck)
        btnStop = button("停止") { Bus.cancel = true }

        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            col.addView(this, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(16), 0, 0) })
        }
        status = label("", 14f, topMargin = 8)

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        Bus.listener = { runOnUiThread { render() } }
        render()
    }

    override fun onPause() {
        Bus.listener = null
        super.onPause()
    }

    private fun render() {
        val s = Bus.snapshot
        val run = Bus.running
        folderText.text = treeUri?.let { Uri.decode(it.lastPathSegment ?: it.toString()) } ?: "还没选择"
        btnBackup.isEnabled = !run
        btnRestore.isEnabled = !run
        btnStop.visibility = if (run) View.VISIBLE else View.GONE
        bar.visibility = if (run) View.VISIBLE else View.GONE
        if (run) {
            bar.progress = if (s.bytesTotal > 0) (s.bytesDone * 1000 / s.bytesTotal).toInt().coerceIn(0, 1000) else 0
            status.text = "${s.mode}中  ${s.done}/${s.total}\n新增 ${s.copied} · 跳过 ${s.skipped} · 失败 ${s.failed}\n${s.current}"
        } else {
            status.text = s.message.ifEmpty { "选好文件夹后，点“开始备份”。" }
        }
    }

    private fun pickFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        )
        @Suppress("DEPRECATION")
        startActivityForResult(i, reqTree)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqTree && resultCode == RESULT_OK) {
            val u = data?.data ?: return
            contentResolver.takePersistableUriPermission(
                u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            treeUri = u
            prefs.edit().putString("tree", u.toString()).apply()
            render()
        }
    }

    private fun hasRead(): Boolean =
        if (Build.VERSION.SDK_INT >= 33)
            checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        else
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun ensurePerms(then: () -> Unit) {
        val need = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            need.add(Manifest.permission.READ_MEDIA_IMAGES)
            need.add(Manifest.permission.READ_MEDIA_VIDEO)
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            need.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        need.add(Manifest.permission.ACCESS_MEDIA_LOCATION)
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            then()
        } else {
            pending = then
            requestPermissions(missing.toTypedArray(), reqPerm)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != reqPerm) return
        val action = pending
        pending = null
        if (!hasRead()) {
            Toast.makeText(this, "需要允许访问“全部照片和视频”才能备份", Toast.LENGTH_LONG).show()
            return
        }
        action?.invoke()
    }

    private fun start(mode: String) {
        val t = treeUri
        if (t == null) {
            Toast.makeText(this, "请先选择文件夹", Toast.LENGTH_SHORT).show()
            return
        }
        if (Bus.running) return
        ensurePerms { launch(mode, t) }
    }

    private fun confirmRestore() {
        if (treeUri == null) {
            Toast.makeText(this, "请先选择备份文件夹", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("恢复到手机")
            .setMessage("会把备份里手机上没有的照片和视频放回系统相册，并还原拍摄时间。手机上已有的会自动跳过。继续吗？")
            .setPositiveButton("开始恢复") { _, _ -> start("restore") }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun launch(mode: String, tree: Uri) {
        val layout = when (layoutGroup.checkedRadioButtonId) {
            2 -> "SOURCE"
            3 -> "FLAT"
            else -> "DATE"
        }
        val i = Intent(this, WorkService::class.java)
            .putExtra("mode", mode)
            .putExtra("tree", tree.toString())
            .putExtra("layout", layout)
            .putExtra("orig", origCheck.isChecked)
        startForegroundService(i)
    }
}
