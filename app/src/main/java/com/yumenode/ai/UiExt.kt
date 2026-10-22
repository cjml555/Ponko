// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import android.widget.Toast

/**
 * UI 小工具：dp 换算、Toast、圆角背景、通用 LayoutParams。
 *
 * 都做成 Context 的扩展函数，这样在 Activity / 持有 Context 的类里可以直接调用，
 * 不用每个类各写一份（此前 MainActivity、DrawPage、AttachmentHandler 各有一份 dp/toast/rounded）。
 */

/** dp → px (truncando; ver [UiScale]) */
fun Context.dp(v: Int): Int = UiScale.dpToPx(v, resources.displayMetrics.density)

/** 显示一条 Toast；short = true 用短时长 */
fun Context.toast(msg: String, short: Boolean = false) {
    Toast.makeText(this, msg, if (short) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
}

/** 圆角背景（可选描边）。radiusDp 按 dp 计，不用手换算。 */
fun Context.rounded(
    color: Int,
    radiusDp: Int,
    strokeDp: Int = 0,
    strokeColor: Int = 0
): GradientDrawable {
    val d = resources.displayMetrics.density
    return GradientDrawable().apply {
        cornerRadius = radiusDp * d
        setColor(color)
        if (strokeDp > 0) setStroke((strokeDp * d).toInt(), strokeColor)
    }
}

/** MATCH_PARENT × WRAP_CONTENT，可带顶部外边距 */
fun Context.matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
    LinearLayout.LayoutParams.MATCH_PARENT,
    LinearLayout.LayoutParams.WRAP_CONTENT
).apply { topMargin = dp(top) }

/** WRAP_CONTENT × WRAP_CONTENT, con margen izquierdo opcional (dp) */
fun Context.wrapWrap(leftMargin: Int = 0) = LinearLayout.LayoutParams(
    LinearLayout.LayoutParams.WRAP_CONTENT,
    LinearLayout.LayoutParams.WRAP_CONTENT
).apply { this.leftMargin = dp(leftMargin) }
