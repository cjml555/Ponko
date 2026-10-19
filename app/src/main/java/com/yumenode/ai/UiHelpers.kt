// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.content.Context
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Helper UI functions for building Views in a consistent style
 */
class UiHelpers {
    companion object {
        fun fmtSize(bytes: Long): String = when {
            bytes >= 1L shl 30 -> String.format("%.2f GB", bytes.toDouble() / (1L shl 30))
            bytes >= 1L shl 20 -> String.format("%.1f MB", bytes.toDouble() / (1L shl 20))
            bytes >= 1L shl 10 -> String.format("%.0f KB", bytes.toDouble() / (1L shl 10))
            else -> "$bytes B"
        }

        fun fmtTimeMs(ms: Long): String = when {
            ms >= 3600000 -> String.format("%.1f小时", ms.toDouble() / 3600000)
            ms >= 60000 -> String.format("%.1f分钟", ms.toDouble() / 60000)
            ms >= 1000 -> String.format("%.0f秒", ms.toDouble() / 1000)
            else -> "${ms}毫秒"
        }

        /**
         * DP to pixel conversion helper
         */
        fun dp(value: Int, context: Context): Int {
            return (value * context.resources.displayMetrics.density).toInt()
        }

        /** Helper for LayoutParams: MATCH_PARENT, WRAP_CONTENT with margins */
        fun matchWrap(context: Context, topMargin: Int = 0, bottomMargin: Int = 0, leftMargin: Int = 0, rightMargin: Int = 0): LinearLayout.LayoutParams {
            val lparams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lparams.setMargins(
                dp(leftMargin, context),
                dp(topMargin, context),
                dp(rightMargin, context),
                dp(bottomMargin, context)
            )
            return lparams
        }

        /** Helper for LayoutParams: WRAP_CONTENT, WRAP_CONTENT with margins */
        fun wrapWrap(context: Context, topMargin: Int = 0, bottomMargin: Int = 0, leftMargin: Int = 0, rightMargin: Int = 0): LinearLayout.LayoutParams {
            val lparams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lparams.setMargins(
                dp(leftMargin, context),
                dp(topMargin, context),
                dp(rightMargin, context),
                dp(bottomMargin, context)
            )
            return lparams
        }

        /** Number input field helper */
        fun numField(
            context: Context,
            label: String,
            value: String,
            hint: String,
            keep: (EditText) -> Unit,
            onChanged: () -> Unit,
            palette: () -> YumenodeTheme,
            ui: UiWidgets
        ): LinearLayout {
            val pal = palette()
            val layout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val labelTv = TextView(context).apply {
                    text = label
                    textSize = 11.5f
                    setTextColor(pal.subText)
                }
                addView(labelTv)
            }
            val edit = EditText(context).apply {
                setText(value)
                textSize = 13f
                setTextColor(pal.text)
                setInputType(InputType.TYPE_CLASS_NUMBER or
                    InputType.TYPE_NUMBER_FLAG_DECIMAL or
                    InputType.TYPE_NUMBER_FLAG_SIGNED)
                setBackgroundColor(pal.fieldBg)
                setPadding(
                    dp(10, context),
                    dp(8, context),
                    dp(10, context),
                    dp(8, context)
                )
                isSingleLine = true
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) {
                        onChanged()
                    }
                })
            }
            keep(edit)
            layout.addView(edit, matchWrap(context).apply { topMargin = dp(4, context) })
            layout.addView(hintTextView(context, hint, palette), matchWrap(context).apply { topMargin = dp(3, context) })
            return layout
        }

        /** Hint text helper */
        fun hintTextView(context: Context, text: String, palette: () -> YumenodeTheme): TextView = TextView(context).apply {
            this.text = text
            textSize = 10.5f
            setTextColor(palette().subText)
            setPadding(0, dp(3, context), 0, 0)
        }

        /** Two-column layout helper (matches MainActivity's twoCols with 5dp gap) */
        fun twoCols(
            context: Context,
            first: View,
            second: View
        ): LinearLayout {
            return LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(first, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = dp(5, context)
                })
                addView(second, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = dp(5, context)
                })
            }
        }
    }
}