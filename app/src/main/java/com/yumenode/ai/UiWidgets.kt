// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Constructores de widgets con el estilo de Yumenode AI (tarjetas, títulos, botones).
 *
 * Antes vivían como métodos privados de MainActivity, lo que obligaba a que
 * cualquier pantalla nueva pasara por la Activity. La paleta se pide con un
 * provider (`{ pal }`) para que el cambio de tema se refleje sin recrear el
 * objeto.
 */
class UiWidgets(
    private val ctx: Activity,
    private val paletteProvider: () -> YumenodeTheme,
) : ThemeAliases {

    override val palette: YumenodeTheme get() = paletteProvider()

    /** Tarjeta blanca con esquinas redondeadas. */
    fun card(): LinearLayout = with(ctx) {
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(C_CARD, 16)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            elevation = dp(1).toFloat()
        }
    }

    /** Tarjeta de sección: una por bloque, con 10dp de separación inferior. */
    fun sectionCard(): LinearLayout = with(ctx) {
        card().apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
    }

    fun pageTitle(text: String): TextView = with(ctx) {
        TextView(ctx).apply {
            this.text = text
            textSize = 15f
            setTextColor(C_TEXT)
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    /** Sub-título de sección (un punto más pequeño que [pageTitle]). */
    fun subTitle(text: String): TextView = with(ctx) {
        TextView(ctx).apply {
            this.text = text
            textSize = 14f
            setTextColor(C_TEXT)
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    /** Línea divisoria fina dentro de una tarjeta. */
    fun divider(): View = with(ctx) {
        View(ctx).apply {
            setBackgroundColor(C_DIVIDER)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            )
        }
    }

    fun hintText(text: String): TextView = with(ctx) {
        TextView(ctx).apply {
            this.text = text
            textSize = 12f
            setTextColor(C_SUBTEXT)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, dp(5), 0, 0)
        }
    }

    fun actionButton(text: String, onClick: () -> Unit): TextView = with(ctx) {
        TextView(ctx).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = rounded(C_PRIMARY, 12)
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    fun smallButton(text: String, onClick: () -> Unit): TextView = with(ctx) {
        TextView(ctx).apply {
            this.text = text
            textSize = 12f
            setTextColor(C_PRIMARY)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = rounded(C_PRIMARY_SOFT, 10)
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    fun iconButton(glyph: String, onClick: () -> Unit): TextView = with(ctx) {
        TextView(ctx).apply {
            text = glyph
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = rounded(Color.parseColor("#33FFFFFF"), 12)
            isClickable = true
            setOnClickListener { onClick() }
        }
    }
}