package cn.rikka.album

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView

/** 纯代码写界面用的小工具（不依赖 AndroidX） */
class Ui(val ctx: Context) {
    fun dp(v: Number): Int = (v.toFloat() * ctx.resources.displayMetrics.density + 0.5f).toInt()
    fun c(id: Int): Int = ctx.getColor(id)

    val bg get() = c(R.color.bg)
    val surface get() = c(R.color.surface)
    val surface2 get() = c(R.color.surface2)
    val line get() = c(R.color.line)
    val text get() = c(R.color.text)
    val sub get() = c(R.color.sub)
    val accent get() = c(R.color.accent)
    val accentSoft get() = c(R.color.accent_soft)
    val onAccent get() = c(R.color.on_accent)
    val ok get() = c(R.color.ok)
    val warn get() = c(R.color.warn)
    val warnSoft get() = c(R.color.warn_soft)

    fun round(color: Int, radius: Number, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        if (stroke > 0) setStroke(dp(stroke), strokeColor)
    }

    fun ripple(base: Drawable?, radius: Number): Drawable {
        val mask = round(0xFFFFFFFF.toInt(), radius)
        return RippleDrawable(ColorStateList.valueOf((accent and 0x00FFFFFF) or 0x33000000), base, mask)
    }

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f,
           l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }

    fun vbox(pad: Int = 0, f: LinearLayout.() -> Unit = {}) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(pad), dp(pad), dp(pad), dp(pad)); f()
    }

    fun hbox(pad: Int = 0, f: LinearLayout.() -> Unit = {}) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad)); f()
    }

    fun tv(s: CharSequence = "", size: Float = 14f, color: Int = text, bold: Boolean = false, f: TextView.() -> Unit = {}) =
        TextView(ctx).apply {
            this.text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setLineSpacing(0f, 1.15f); f()
        }

    fun title(s: String) = tv(s, 26f, text, true) { setPadding(dp(4), dp(18), dp(4), dp(2)) }
    fun subtitle(s: String) = tv(s, 13.5f, sub) { setPadding(dp(4), 0, dp(4), dp(12)) }
    fun section(s: String) = tv(s, 13f, sub, true) { setPadding(dp(8), dp(18), dp(8), dp(8)); letterSpacing = 0.04f }

    fun card(pad: Int = 16, f: LinearLayout.() -> Unit = {}) = vbox(pad) {
        background = round(surface, 18)
        f()
    }

    /** 主按钮（琥珀色实心） */
    fun primary(s: String, onClick: () -> Unit) = tv(s, 15f, onAccent, true) {
        gravity = Gravity.CENTER; minHeight = dp(46); setPadding(dp(18), dp(10), dp(18), dp(10))
        background = ripple(round(accent, 14), 14)
        setOnClickListener { onClick() }
    }

    /** 次按钮（浅琥珀底） */
    fun secondary(s: String, onClick: () -> Unit) = tv(s, 15f, accent, true) {
        gravity = Gravity.CENTER; minHeight = dp(46); setPadding(dp(18), dp(10), dp(18), dp(10))
        background = ripple(round(accentSoft, 14), 14)
        setOnClickListener { onClick() }
    }

    /** 小号文字按钮 */
    fun chip(s: String, color: Int = accent, fill: Int = accentSoft, onClick: () -> Unit) = tv(s, 13f, color, true) {
        gravity = Gravity.CENTER; setPadding(dp(12), dp(6), dp(12), dp(6))
        background = ripple(round(fill, 20), 20)
        setOnClickListener { onClick() }
    }

    fun icon(res: Int, tint: Int, size: Int = 24) = ImageView(ctx).apply {
        setImageResource(res); imageTintList = ColorStateList.valueOf(tint)
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    fun divider() = View(ctx).apply {
        setBackgroundColor(line)
        layoutParams = lp(h = 1, l = 16, r = 16)
    }

    fun progress() = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
        progressTintList = ColorStateList.valueOf(accent)
        progressBackgroundTintList = ColorStateList.valueOf(surface2)
        max = 1000
    }

    /** 设置行：标题 + 说明 + 右侧数值，整行可点 */
    fun row(title: String, desc: String = "", value: () -> String, onClick: () -> Unit): View {
        lateinit var v: TextView
        val r = hbox {
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(vbox {
                addView(tv(title, 15.5f, text))
                if (desc.isNotEmpty()) addView(tv(desc, 12.5f, sub) { setPadding(0, dp(2), 0, 0) })
            }, lp(0, weight = 1f))
            v = tv(value(), 14f, accent, true) { setPadding(dp(12), 0, 0, 0) }
            addView(v)
            background = ripple(null, 0)
        }
        r.setOnClickListener { onClick(); v.text = value() }
        r.tag = Runnable { v.text = value() }
        return r
    }

    /** 开关行 */
    fun switchRow(title: String, desc: String, get: () -> Boolean, set: (Boolean) -> Unit): View {
        val sw = Switch(ctx).apply {
            isChecked = get()
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(accent, c(R.color.surface2)))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf((accent and 0x00FFFFFF) or 0x66000000, line))
            isClickable = false
        }
        return hbox {
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(vbox {
                addView(tv(title, 15.5f, text))
                if (desc.isNotEmpty()) addView(tv(desc, 12.5f, sub) { setPadding(0, dp(2), dp(8), 0) })
            }, lp(0, weight = 1f))
            addView(sw)
            background = ripple(null, 0)
            setOnClickListener { sw.isChecked = !sw.isChecked; set(sw.isChecked) }
        }
    }

    fun ellipsize(t: TextView, lines: Int = 1) { t.maxLines = lines; t.ellipsize = TextUtils.TruncateAt.MIDDLE }

    fun frame(f: FrameLayout.() -> Unit = {}) = FrameLayout(ctx).apply(f)

    // ---------------------------------------------------------------- 对话框

    private fun dialog() = AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_DayNight)

    fun choose(title: String, labels: List<String>, checked: Int, onPick: (Int) -> Unit) {
        dialog().setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { d, which -> onPick(which); d.dismiss() }
            .setNegativeButton("取消", null).show().tint()
    }

    fun multi(title: String, labels: List<String>, checked: BooleanArray, onDone: (BooleanArray) -> Unit) {
        dialog().setTitle(title)
            .setMultiChoiceItems(labels.toTypedArray(), checked) { _, w, v -> checked[w] = v }
            .setPositiveButton("确定") { _, _ -> onDone(checked) }
            .setNegativeButton("取消", null).show().tint()
    }

    fun confirm(title: String, msg: String, okText: String = "确定", onOk: () -> Unit) {
        dialog().setTitle(title).setMessage(msg)
            .setPositiveButton(okText) { _, _ -> onOk() }
            .setNegativeButton("取消", null).show().tint()
    }

    fun info(title: String, msg: String) {
        dialog().setTitle(title).setMessage(msg).setPositiveButton("知道了", null).show().tint()
    }

    private fun AlertDialog.tint(): AlertDialog {
        window?.setBackgroundDrawable(round(surface, 22))
        getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(accent)
        getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(sub)
        return this
    }

    fun clear(v: View) { v.background = ColorDrawable(0) }
}
