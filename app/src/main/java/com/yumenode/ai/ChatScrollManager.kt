// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.content.Context
import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView

/**
 * Gestión del scroll de la página de chat: auto-follow, botón «volver al
 * fondo» y preservación del foco del input durante scrolls programáticos.
 *
 * Extraído de MainActivity para mantener cohesionado el estado de scroll y
 * facilitar pruebas de la lógica de follow / jump thresholds.
 */
class ChatScrollManager(
    private val ctx: Context,
    private val scrollView: ScrollView,
    private val jumpButton: TextView,
    // Provider en vez de referencia directa: el input se construye después de
    // esta clase en buildUi(), así que no existe todavía al construirla.
    private val inputEditProvider: () -> EditText,
) {

    private val inputEdit: EditText get() = inputEditProvider()

    // ── estado ──

    private var scrollPending = false
    private var programmaticScroll = false
    private var autoFollow = true

    private val followThresholdPx: Int by lazy { ctx.dp(12) }

    /** 「回到底部」按钮的显示阈值：离底部超过 15 行左右才出现。 */
    private val jumpThresholdPx: Int by lazy {
        val line = (16f * ctx.resources.displayMetrics.scaledDensity * 1.4f).toInt() + ctx.dp(3)
        line * 15
    }

    init {
        // El botón «volver al fondo» hace scroll forzado y borra el foco visual
        jumpButton.setOnClickListener { jumpToBottom() }
        // El listener se encarga de pausar auto-follow cuando el usuario hace scroll manual
        scrollView.setOnScrollChangeListener { _, _, _, _, _ ->
            if (!programmaticScroll) refreshFollowState()
        }
    }

    // ── API pública ──

    /**
     * Hace scroll hasta el fondo.
     * - force = true: siempre (usado por jumpToBottom y por send)
     * - force = false: respeta autoFollow (solo se mueve si el usuario aún sigue pegado al fondo)
     */
    fun scrollToBottom(force: Boolean = false) {
        if (!force && !autoFollow) {
            refreshFollowState()
            return
        }
        if (scrollPending) return
        scrollPending = true
        scrollView.post {
            scrollPending = false
            // 排队期间用户可能已经往上划了：再确认一次，别把他拽回去
            if (!force && !autoFollow) {
                refreshFollowState()
                return@post
            }
            // 滚动到底不应影响输入框焦点（用户可能正在打字）
            val hadFocus = inputEdit.hasFocus()
            programmaticScroll = true
            scrollView.getChildAt(0)?.let { scrollView.scrollTo(0, it.height) }
            programmaticScroll = false
            autoFollow = true
            refreshJumpButton()
            if (hadFocus && !inputEdit.hasFocus()) inputEdit.requestFocus()
        }
    }

    /** Usuario acción explícita (enviar mensaje): restaura follow y salta al fondo. */
    fun jumpToBottom() {
        autoFollow = true
        jumpButton.visibility = View.GONE
        scrollToBottom(force = true)
    }

    /**
     * Reinicia el estado de scroll después de un rebuild completo.
     * Sólo restablece autoFollow y oculta el botón, sin hacer scroll.
     */
    fun resetForRebuild() {
        autoFollow = true
        jumpButton.visibility = View.GONE
    }

    // ── helpers privados ──

    /** Distancia en píxeles al fondo del contenido (negativo → 0). */
    private fun distanceToBottom(): Int {
        val child = scrollView.getChildAt(0) ?: return 0
        return (child.height - (scrollView.scrollY + scrollView.height)).coerceAtLeast(0)
    }

    /** Actualiza autoFollow y la visibilidad del botón según la posición actual. */
    private fun refreshFollowState() {
        // 用户往上划走后就停止自动跟随（读者优先），不再把他拽回底部；
        // 只有他自己划回到贴底附近，才恢复跟随。
        autoFollow = distanceToBottom() <= followThresholdPx
        refreshJumpButton()
    }

    private fun refreshJumpButton() {
        val child = scrollView.getChildAt(0) ?: return
        val scrollable = child.height > scrollView.height + ctx.dp(16)
        jumpButton.visibility =
            if (scrollable && distanceToBottom() > jumpThresholdPx) View.VISIBLE else View.GONE
    }
}
