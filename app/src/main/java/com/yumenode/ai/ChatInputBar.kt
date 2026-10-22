// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.content.res.ColorStateList
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Dependencias que la barra de entrada necesita de MainActivity.
 *
 * Se declara como interfaz (en vez de pasar la Activity entera) para que la
 * barra no pueda tocar estado de chat, motores ni sesiones: solo lee la
 * paleta, persiste el interruptor de "pensar" y avisa de los dos clicks.
 */
interface InputBarHost {
    val activity: android.app.Activity
    val palette: YumenodeTheme
    /** Adjuntos pendientes: la barra le pasa sus dos vistas para que las pinte. */
    fun attachPreviewViews(strip: HorizontalScrollView, box: LinearLayout)
    fun onAttachClick()
    fun onSendOrStop()
    /** Prefs del chat, para persistir el interruptor "pensar". */
    fun chatPrefs(): android.content.SharedPreferences
}

/**
 * Barra de entrada del chat: interruptor "pensar", tira de vista previa de
 * adjuntos, campo de texto, botón ＋ (adjuntar) y botón ↑ (enviar / detener).
 *
 * Extraída de MainActivity (buildInputBar) para que ese archivo siga
 * reduciéndose. La barra es dueña de sus seis vistas; MainActivity las
 * expone con getters delegados, igual que ModelsPage.
 */
class ChatInputBar(private val host: InputBarHost) {

    private val ctx: android.app.Activity get() = host.activity
    private val pal: YumenodeTheme get() = host.palette

    private val C_TEXT get() = pal.text
    private val C_HINT get() = pal.hint
    private val C_CARD get() = pal.surface
    private val C_PRIMARY get() = pal.primary
    private val C_PRIMARY_SOFT get() = pal.primarySoft
    private val C_SOFT_BTN get() = pal.softBtn

    /** Interruptor "pensar antes de responder". */
    lateinit var thinkCheck: CheckBox
        private set
    /** Tira horizontal con las miniaturas de los adjuntos pendientes. */
    lateinit var attachPreviewStrip: HorizontalScrollView
        private set
    /** Contenedor horizontal dentro de la tira de vista previa. */
    lateinit var attachPreviewBox: LinearLayout
        private set
    /** Campo de texto del mensaje. */
    lateinit var inputEdit: EditText
        private set
    /** Botón ＋ (adjuntar). */
    lateinit var attachButton: TextView
        private set
    /** Botón ↑ (enviar) / ■ (detener). */
    lateinit var sendButton: TextView
        private set

    /** Construye la barra completa. */
    fun build(): View {
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C_CARD)
            setPadding(ctx.dp(10), ctx.dp(4), ctx.dp(10), ctx.dp(8))
            elevation = ctx.dp(6).toFloat()
        }

        thinkCheck = CheckBox(ctx).apply {
            text = ctx.getString(R.string.s_105)
            textSize = 12.5f
            setTextColor(C_TEXT)
            isChecked = host.chatPrefs().getBoolean(KEY_CHAT_THINK, true)
            buttonTintList = ColorStateList.valueOf(C_PRIMARY)
            setOnCheckedChangeListener { _, v ->
                host.chatPrefs().edit().putBoolean(KEY_CHAT_THINK, v).apply()
            }
        }
        bar.addView(thinkCheck, ctx.matchWrap())

        // 待发送图片预览条（横向可滚动，每张一个小缩略图 + 右上角 ×）
        attachPreviewStrip = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            setPadding(0, ctx.dp(2), 0, ctx.dp(6))
        }
        attachPreviewBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        attachPreviewStrip.addView(attachPreviewBox)
        bar.addView(attachPreviewStrip, ctx.matchWrap())

        // 预览条的重建逻辑在 AttachmentHandler（增删附件后自动刷新）
        host.attachPreviewViews(attachPreviewStrip, attachPreviewBox)

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }

        inputEdit = EditText(ctx).apply {
            hint = ctx.getString(R.string.s_180)
            textSize = 15f
            setTextColor(C_TEXT)
            setHintTextColor(C_HINT)
            background = ctx.rounded(C_SOFT_BTN, 20)
            setPadding(ctx.dp(16), ctx.dp(10), ctx.dp(16), ctx.dp(10))
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 1
            maxLines = 5
            gravity = Gravity.TOP or Gravity.START
        }
        row.addView(inputEdit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        attachButton = TextView(ctx).apply {
            text = "＋"
            textSize = 20f
            setTextColor(C_PRIMARY)
            gravity = Gravity.CENTER
            background = ctx.rounded(C_PRIMARY_SOFT, 22)
            isClickable = true
            setOnClickListener { host.onAttachClick() }
        }
        row.addView(attachButton, LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(44)).apply { leftMargin = ctx.dp(8) })

        sendButton = TextView(ctx).apply {
            text = "↑"
            textSize = 19f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = ctx.rounded(C_PRIMARY, 22)
            isClickable = true
            setOnClickListener { host.onSendOrStop() }
        }
        row.addView(sendButton, LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(44)).apply { leftMargin = ctx.dp(8) })
        bar.addView(row, ctx.matchWrap())
        return bar
    }
}
