// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Helper para construir los paneles y pestañas de la página de modelos.
 * Se extiende de UiHelpers para no depender de MainActivity.
 */
object ModelsHelpers {

    /** Envuelve una vista en un ScrollView con padding estándar. */
    fun modelsPane(context: Context, inner: View): ScrollView {
        return ScrollView(context).apply {
            clipToPadding = false
            setPadding(
                UiHelpers.dp(12, context),
                UiHelpers.dp(12, context),
                UiHelpers.dp(12, context),
                UiHelpers.dp(12, context)
            )
            addView(inner, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    /** Sub-pestaña de una sección de modelos (chat/draw). */
    fun modelsSubTab(context: Context, t: String): TextView {
        return TextView(context).apply {
            text = t
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, UiHelpers.dp(9, context), 0, UiHelpers.dp(9, context))
        }
    }

    /** Pestaña de nivel superior de la página de modelos. */
    fun tabItem(context: Context, t: String): TextView {
        return TextView(context).apply {
            text = t
            textSize = 13.5f
            gravity = Gravity.CENTER
            setPadding(0, UiHelpers.dp(11, context), 0, UiHelpers.dp(11, context))
        }
    }

    /**
     * Construye una barra de sub-pestañas con dos pestañas (0=modelo 1=API).
     * El listener se asigna desde afuera (necesita acceso al estado de MainActivity).
     */
    fun subTabBar(context: Context, tab0: TextView, tab1: TextView): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(tab0, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(tab1, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    /**
     * Muestra un panel y oculta el otro, marcando la pestaña correspondiente.
     *
     * @param pane0 panel del modelo (visible cuando [selected] == 0)
     * @param pane1 panel de la API (visible cuando [selected] == 1)
     * @param tab0 pestaña del modelo
     * @param tab1 pestaña de la API
     */
    fun switchSubPage(
        pane0: View,
        pane1: View,
        tab0: TextView,
        tab1: TextView,
        selected: Int,
        pal: YumenodeTheme,
    ) {
        pane0.visibility = if (selected == 0) View.VISIBLE else View.GONE
        pane1.visibility = if (selected == 1) View.VISIBLE else View.GONE
        for ((tv, sel) in listOf(tab0 to (selected == 0), tab1 to (selected == 1))) {
            tv.setTextColor(if (sel) pal.primary else pal.subText)
            tv.typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            tv.setBackgroundColor(if (sel) pal.primarySoft else pal.surface)
        }
    }

    /**
     * Sincroniza el resaltado de las pestañas principales de la página de modelos.
     * También se llama al deslizar el ViewPager.
     */
    fun highlightTabs(tabs: List<TextView>, selected: Int, pal: YumenodeTheme) {
        for ((i, tv) in tabs.withIndex()) {
            val sel = i == selected
            tv.setTextColor(if (sel) pal.primary else pal.subText)
            tv.typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            tv.setBackgroundColor(if (sel) pal.primarySoft else pal.surface)
        }
    }
}
