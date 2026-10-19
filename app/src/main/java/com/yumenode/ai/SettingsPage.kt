// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator

/**
 * Dependencias que la página de ajustes necesita de MainActivity.
 *
 * Se declara como interfaz (en vez de pasar la Activity entera) para que la
 * pantalla no pueda tocar estado de chat, motores ni sesiones.
 */
interface SettingsHost {
    val activity: Activity
    val accentIndex: Int
    fun applyAccent(index: Int)

    val palette: YumenodeTheme
    val ui: UiWidgets
    val themeMode: Int
    val drawBusy: Boolean
    fun applyThemeMode(mode: Int)
    fun toast(msg: String)
    fun appVersion(): String
    fun installedAt(): String
    fun prefBool(key: String, def: Boolean): Boolean
    fun prefPutBool(key: String, value: Boolean)
    fun prefStr(key: String, def: String): String
    fun prefPutStr(key: String, value: String)
    fun shareBenchmark(endpoint: String, onResult: (String) -> Unit)
    fun runBenchmark(onUpdate: (String) -> Unit)
    fun setAppLanguage(tag: String?)
    fun currentAppLanguage(): String?
}

/**
 * Pestaña «Acerca de / Ajustes»: tema, hilos de inferencia y créditos.
 * Extraída de MainActivity.
 */
class SettingsPage(private val host: SettingsHost) : ThemeAliases {

    private val ctx: Activity get() = host.activity

    override val palette: YumenodeTheme get() = host.palette

    fun build(): View = with(ctx) {

        val sv = ScrollView(ctx).apply {
            clipToPadding = false
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val card = host.ui.card()

        // ---- 设置（可折叠，默认展开；展开状态记在偏好里，重启后保持）----
        val settingsBody = collapsibleSection(
            card, getString(R.string.s_381), expanded = true, prefKey = KEY_UI_SETTINGS_OPEN)
        settingsBody.addView(host.ui.subTitle(getString(R.string.s_376)))
        val themeSwitch = SegmentedSwitch(
            ctx,
            arrayOf(
                getString(R.string.s_378), getString(R.string.s_377), getString(R.string.s_379)
            ),
            host.palette,
            // 档位顺序：0=亮 1=跟随系统 2=暗；themeMode 的取值是 0=跟随系统 1=浅色 2=深色
            when (host.themeMode) {
                1 -> 0
                2 -> 2
                else -> 1
            },
        )
        themeSwitch.onChanged = { i ->
            val mode = when (i) {
                0 -> 1
                2 -> 2
                else -> 0
            }
            if (mode != host.themeMode) {
                if (host.drawBusy) {
                    // 出图途中重建视图会把结果丢掉，先拒绝并弹回原位
                    host.toast(getString(R.string.s_382))
                    themeSwitch.setIndexSilently(
                        when (host.themeMode) {
                            1 -> 0
                            2 -> 2
                            else -> 1
                        }
                    )
                } else {
                    host.applyThemeMode(mode)
                }
            }
        }
        settingsBody.addView(themeSwitch, matchWrap().apply { topMargin = dp(12) })

        // ---- Color de acento ----
        settingsBody.addView(host.ui.subTitle(getString(R.string.s_492)), matchWrap().apply { topMargin = dp(14) })
        val accentRow = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val accentNames = intArrayOf(R.string.s_493, R.string.s_494, R.string.s_495, R.string.s_496, R.string.s_497)
        for (i in 0 until AccentPalette.size) {
            val dot = android.view.View(ctx)
            val sizePx = dp(30)
            dot.layoutParams = android.widget.LinearLayout.LayoutParams(sizePx, sizePx).apply { marginEnd = dp(14) }
            val selected = i == host.accentIndex
            dot.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(if (host.palette.isDark) AccentPalette.dark[i] else AccentPalette.light[i])
                setStroke(dp(if (selected) 3 else 1), if (selected) host.palette.text else host.palette.border)
            }
            dot.contentDescription = getString(accentNames[i])
            dot.setOnClickListener {
                if (i != host.accentIndex) {
                    if (host.drawBusy) host.toast(getString(R.string.s_382))
                    else host.applyAccent(i)
                }
            }
            accentRow.addView(dot)
        }
        settingsBody.addView(accentRow, matchWrap().apply { topMargin = dp(10) })



        // ---- 推理线程数（全局：绘图 / 对话 gguf / 打标 共用；下次加载模型生效）----
        // 放在「设置」分区而不是绘图页：它管的是所有 CPU 推理，不是绘图专属。
        settingsBody.addView(host.ui.subTitle(getString(R.string.s_439)), matchWrap().apply { topMargin = dp(14) })
        val maxThreads = InferConfig.maxThreads()
        // 直接输入而不是下拉选择（用户 2026-10-13 指定）。越界当场纠正：
        //   0（或负数）→ 1；超过本机可用核数 → 上限。
        var fixing = false
        val threadsEdit = EditText(ctx).apply {
            setText(InferConfig.threads(ctx).toString())
            textSize = 13f
            setTextColor(C_TEXT)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setBackgroundColor(C_FIELD_BG)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            isSingleLine = true
        }
        fun applyThreads(raw: String) {
            // 空串按默认值处理（用户清空后直接离开输入框）
            val v = raw.trim().toIntOrNull() ?: InferConfig.DEFAULT
            val fixed = when {
                v <= 0 -> 1
                v > maxThreads -> maxThreads
                else -> v
            }
            if (threadsEdit.text.toString() != fixed.toString()) {
                fixing = true
                threadsEdit.setText(fixed.toString())
                threadsEdit.setSelection(threadsEdit.text.length)
                fixing = false
            }
            InferConfig.setThreads(this, fixed)
        }
        threadsEdit.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                if (fixing) return
                // 中途清空是正常输入过程（准备重打），留到失焦时兜底，别当场塞个值回去
                if (s == null || s.toString().trim().isEmpty()) return
                applyThreads(s.toString())
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        threadsEdit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) applyThreads(threadsEdit.text.toString())
        }
        // 一行显示成「[输入] / 上限」：输入框后跟一个静态的「/ 8」，
        // 用户一眼能看出合法范围，不用去猜最大值是多少（用户 2026-10-13 指定）。
        val threadsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        threadsRow.addView(
            threadsEdit,
            LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        threadsRow.addView(TextView(ctx).apply {
            text = getString(R.string.s_447, maxThreads)
            textSize = 13f
            setTextColor(C_SUBTEXT)
        }, wrapWrap().apply { leftMargin = dp(6) })
        settingsBody.addView(threadsRow, matchWrap().apply { topMargin = dp(8) })
        // 唯一一条保留的说明：.litertlm 走 Google LiteRT-LM，该库没有线程数参数，
        // 不受这项控制。不写清的话，用户会以为是 bug（这条 2026-10-13 由用户要求加回）。
        settingsBody.addView(host.ui.hintText(getString(R.string.s_446)), matchWrap().apply { topMargin = dp(4) })

        // ---- Language ----
        settingsBody.addView(host.ui.subTitle(getString(R.string.s_488)), matchWrap().apply { topMargin = dp(14) })
        val curLang = host.currentAppLanguage()
        val langSwitch = SegmentedSwitch(
            ctx,
            arrayOf(getString(R.string.s_489), getString(R.string.s_490), getString(R.string.s_491)),
            host.palette,
            when (curLang) { "en" -> 1; "es" -> 2; else -> 0 },
        )
        var langBusy = false
        langSwitch.onChanged = { i ->
            if (!langBusy) {
                langBusy = true
                host.setAppLanguage(when (i) { 1 -> "en"; 2 -> "es"; else -> null })
            }
        }
        settingsBody.addView(langSwitch, matchWrap().apply { topMargin = dp(8) })

        // ---- Benchmark (local only) ----
        settingsBody.addView(host.ui.subTitle(getString(R.string.s_474)), matchWrap().apply { topMargin = dp(14) })
        val benchOut = host.ui.hintText("").apply { setTextColor(C_TEXT) }
        var benchRunning = false
        val benchBtn = host.ui.actionButton(getString(R.string.s_475)) {}
        benchBtn.setOnClickListener {
            if (benchRunning) return@setOnClickListener
            benchRunning = true
            benchBtn.text = getString(R.string.s_476)
            benchBtn.isEnabled = false
            host.runBenchmark { text ->
                benchOut.text = text
                benchRunning = false
                benchBtn.text = getString(R.string.s_475)
                benchBtn.isEnabled = true
            }
        }
        settingsBody.addView(benchBtn, matchWrap().apply { topMargin = dp(8) })
        settingsBody.addView(benchOut, matchWrap().apply { topMargin = dp(6) })
        settingsBody.addView(host.ui.hintText(getString(R.string.s_478)), matchWrap().apply { topMargin = dp(4) })

        // optional: share anonymously to the endpoint from bench/worker
        val benchEndpoint = EditText(ctx).apply {
            setText(host.prefStr(KEY_BENCH_ENDPOINT, DEFAULT_BENCH_ENDPOINT))
            hint = "https://…/api/report"
            textSize = 12f
            setTextColor(C_TEXT)
            setHintTextColor(C_SUBTEXT)
            setBackgroundColor(C_FIELD_BG)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            isSingleLine = true
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    host.prefPutStr(KEY_BENCH_ENDPOINT, s?.toString() ?: "")
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        settingsBody.addView(benchEndpoint, matchWrap().apply { topMargin = dp(8) })
        val shareBtn = host.ui.smallButton(getString(R.string.s_477)) {}
        shareBtn.setOnClickListener {
            shareBtn.isEnabled = false
            host.shareBenchmark(benchEndpoint.text.toString()) { msg ->
                benchOut.text = ((benchOut.text ?: "").toString().trimEnd() + "\n\n" + msg).trim()
                shareBtn.isEnabled = true
            }
        }
        settingsBody.addView(shareBtn, matchWrap().apply { topMargin = dp(6) })
        settingsBody.addView(host.ui.hintText(getString(R.string.s_479)), matchWrap().apply { topMargin = dp(4) })

        // ---- 关于（可折叠，默认展开；展开状态同样记在偏好里）----
        val aboutBody = collapsibleSection(
            card, getString(R.string.s_048), expanded = true, prefKey = KEY_UI_ABOUT_OPEN)
        aboutBody.addView(host.ui.hintText(getString(R.string.s_142, host.appVersion())))
        aboutBody.addView(host.ui.hintText(getString(R.string.s_322, host.installedAt())))
        val portrait = ImageView(ctx).apply {
            setImageResource(R.drawable.about_portrait)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(0, dp(10), 0, 0)
        }
        aboutBody.addView(portrait, matchWrap())
        aboutBody.addView(host.ui.hintText(getString(R.string.s_025)))

        aboutBody.addView(host.ui.pageTitle(getString(R.string.s_104)), matchWrap().apply { topMargin = dp(14) })
        aboutBody.addView(host.ui.hintText(getString(R.string.s_097)))

        aboutBody.addView(host.ui.pageTitle(getString(R.string.s_171)), matchWrap().apply { topMargin = dp(14) })
        aboutBody.addView(host.ui.hintText(getString(R.string.s_140)))

        aboutBody.addView(host.ui.pageTitle(getString(R.string.s_043)), matchWrap().apply { topMargin = dp(14) })
        aboutBody.addView(host.ui.hintText("visitor257"))
        // Crédito original + nota de fork: rebautizado y refactorizado
        aboutBody.addView(host.ui.hintText("Original: Ponko © visitor257 (MIT). Derivado como fork, rebautizado y parcialmente refactorizado como Yumenode AI."))

        aboutBody.addView(host.ui.pageTitle(getString(R.string.s_194)), matchWrap().apply { topMargin = dp(14) })
        aboutBody.addView(linkText(getString(R.string.s_107), "https://github.com/cjml555/Yumenode-AI"))

        // ---- 声明（「关于」最底：AI 产出免责 + 开源版权 + 指向 README / NOTICE）----
        // 单独成段而不是并进上面的「许可」：许可是"我用了谁的"，声明是"产出归谁负责"，
        // 两件事性质不同（前者是义务披露，后者是责任边界）。
        aboutBody.addView(host.ui.divider(), matchWrap().apply { topMargin = dp(14) })
        aboutBody.addView(host.ui.pageTitle(getString(R.string.s_448)), matchWrap().apply { topMargin = dp(12) })
        aboutBody.addView(host.ui.hintText(getString(R.string.s_449)))
        aboutBody.addView(host.ui.hintText(getString(R.string.s_450)))
        aboutBody.addView(host.ui.hintText(getString(R.string.s_451)))
        aboutBody.addView(
            linkText(getString(R.string.s_452), "https://github.com/cjml555/Yumenode-AI/blob/main/README.md")
        )
        aboutBody.addView(
            linkText(getString(R.string.s_453), "https://github.com/cjml555/Yumenode-AI/blob/main/NOTICE")
        )

        sv.addView(card, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        return sv
        }

    /** Sección plegable con estado persistido (clave de preferencias). */
    private fun collapsibleSection(
        parentBox: LinearLayout,
        title: String,
        expanded: Boolean,
        prefKey: String? = null,
    ): LinearLayout = with(ctx) {
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            isClickable = true
        }
        val arrow = TextView(ctx).apply {
            textSize = 12f
            setTextColor(C_SUBTEXT)
            setPadding(dp(8), 0, dp(2), 0)
        }
        head.addView(host.ui.pageTitle(title), LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(arrow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        // 关键：View 在加入父容器之前 layoutParams 是 null，而下面 render(false) 会立刻用它
        // （settle）—— 必须先给一份，否则 buildUi 阶段直接 NPE，表现就是「打开立刻闪退」。
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = matchWrap()
        }
        // 展开状态从偏好里读；没存过才用 expanded 的默认值
        var open = prefKey?.let { host.prefBool(it, expanded) } ?: expanded
        var anim: ValueAnimator? = null

        /** 收尾：取消动画并把高度还原成 WRAP_CONTENT（否则动画值会被钉死） */
        fun settle() {
            anim?.cancel()
            anim = null
            body.layoutParams = (body.layoutParams ?: matchWrap()).apply {
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }

        /** 展开 / 收起。animate=false 用于初始状态（直接摆好，不播动画） */
        fun render(animate: Boolean) {
            arrow.text = if (open) "\u25be" else "\u25b8"
            if (!animate) {
                settle()
                body.visibility = if (open) View.VISIBLE else View.GONE
                return
            }
            val target = if (open) {
                // 先按父容器宽度量一遍，拿到内容的真实高度（收起时高度是 0，量不出来）
                val w = (parentBox.width - parentBox.paddingLeft - parentBox.paddingRight)
                    .takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
                body.measure(
                    View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                body.measuredHeight
            } else 0
            if (open) body.visibility = View.VISIBLE
            val from = body.height.coerceAtLeast(0)
            anim?.cancel()
            if (from == target) {
                settle()
                if (!open) body.visibility = View.GONE
                return
            }
            anim = ValueAnimator.ofInt(from, target).apply {
                duration = 180L
                interpolator = DecelerateInterpolator()
                addUpdateListener { va ->
                    body.layoutParams = (body.layoutParams ?: matchWrap()).apply {
                        height = va.animatedValue as Int
                    }
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: Animator) {
                        settle()
                        if (!open) body.visibility = View.GONE
                    }
                })
                start()
            }
        }

        render(animate = false)
        head.setOnClickListener {
            open = !open
            // 落盘：不然合上之后切走/重启又会弹回来
            prefKey?.let { host.prefPutBool(it, open) }
            render(animate = true)
        }
        parentBox.addView(head, matchWrap())
        parentBox.addView(body, matchWrap())
        return body
    }

    /** Enlace que abre el navegador del sistema. */
    private fun linkText(label: String, url: String): TextView = with(ctx) {
        host.ui.hintText(label).apply {
            isClickable = true
            setTextColor(C_PRIMARY)
            setOnClickListener {
                runCatching {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                }.onFailure { host.toast(getString(R.string.s_134)) }
            }
        }
    }
}
