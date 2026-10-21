// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.app.Activity
import android.content.SharedPreferences
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Un bloque de configuración de API: estado / dirección / puerto / key / interruptor /
 * estadísticas / aviso de seguridad.
 *
 * **El chat y el dibujo llaman a la misma función** — así los dos lados tienen
 * necesariamente los mismos elementos, en el mismo orden, con el mismo estilo.
 * La primera versión estaba escrita a mano dos veces y la del dibujo se había
 * quedado sin puerto, sin key y sin estadísticas, además de compartir la
 * configuración del chat: parecía un apéndice medio inválido.
 *
 * @param extraAfterAddress contenido extra entre «dirección» y «puerto»
 *        (el lado de dibujo lo usa para «modelo de dibujo: xxx»)
 * @param extraNotes notas extra bajo el interruptor y antes de las estadísticas
 *        (el lado de dibujo lo usa para el aviso de consumo de CPU)
 */
data class ApiBlockView(
    val card: LinearLayout,
    val statusTv: TextView,
    val addrTv: TextView,
    val portEdit: EditText,
    val keyTv: TextView,
    val toggleBtn: TextView,
    val statsTv: TextView,
)

/**
 * Constructor de [ApiBlockView].
 *
 * Se pasa por constructor lo que necesita en vez de recibir la Activity entera:
 * así la clase no puede tocar el motor, la sesión de chat ni el estado del
 * modelo por accidente.
 */
class ApiBlockBuilder(
    private val activity: Activity,
    private val ui: UiWidgets,
    private val pal: YumenodeTheme,
    private val chatPrefs: SharedPreferences,
    private val onCopyAddress: (ApiServer.Role) -> Unit,
    private val onNewKey: (ApiServer.Role) -> Unit,
    private val onClearKey: (ApiServer.Role) -> Unit,
    private val onToggle: (ApiServer.Role) -> Unit,
    private val onRefreshSurfaces: () -> Unit,
) {

    fun build(
        role: ApiServer.Role,
        extraAfterAddress: List<View> = emptyList(),
        extraNotes: List<View> = emptyList(),
    ): ApiBlockView {
        val isChat = role == ApiServer.Role.CHAT
        val card = ui.sectionCard()
        // El título es idéntico en ambos lados: lo que los distingue es la página
        // donde están y la línea de texto de abajo
        card.addView(ui.pageTitle(activity.getString(R.string.s_403)))
        card.addView(
            ui.hintText(activity.getString(if (isChat) R.string.s_404 else R.string.s_422)),
            matchWrap().apply { topMargin = dp(4) },
        )

        val statusTv = TextView(activity).apply {
            textSize = 12.5f
            setTextColor(pal.subText)
            setPadding(0, dp(6), 0, 0)
        }
        card.addView(statusTv, matchWrap())

        val addrTv = TextView(activity).apply {
            textSize = 12f
            setTextColor(pal.primary)
            isClickable = true
            setPadding(0, dp(2), 0, 0)
            setOnClickListener { onCopyAddress(role) }
        }
        card.addView(addrTv, matchWrap())

        extraAfterAddress.forEach { card.addView(it, matchWrap()) }

        val portRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        portRow.addView(
            TextView(activity).apply {
                text = activity.getString(R.string.s_400)
                textSize = 12f
                setTextColor(pal.text)
            },
            wrapWrap(),
        )
        val portEdit = EditText(activity).apply {
            setText(apiPort(role).toString())
            textSize = 13f
            setTextColor(pal.text)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setBackgroundColor(pal.fieldBg)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            isSingleLine = true
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    val v = s?.toString()?.trim()?.toIntOrNull() ?: return
                    if (v in 1024..65535) {
                        chatPrefs.edit().putInt(ApiService.keyPort(role), v).apply()
                    }
                }

                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
            // El servicio solo se reinicia al perder el foco: reiniciarlo mientras
            // se escribe lo corta una y otra vez
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus && ApiService.isRunning(role)) {
                    ApiService.sync(activity)
                    android.os.Handler(activity.mainLooper)
                        .postDelayed({ onRefreshSurfaces() }, 500)
                }
            }
        }
        portRow.addView(
            portEdit,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(8) },
        )
        card.addView(portRow, matchWrap())
        card.addView(
            ui.hintText(activity.getString(R.string.s_401)),
            matchWrap().apply { topMargin = dp(4) },
        )

        val keyTv = TextView(activity).apply {
            textSize = 12f
            setTextColor(pal.subText)
            setPadding(0, dp(8), 0, 0)
        }
        card.addView(keyTv, matchWrap())
        val keyRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        // Los dos botones van separados: antes iban pegados y era fácil pulsar
        // «borrar» sin querer
        keyRow.addView(
            ui.smallButton(activity.getString(R.string.s_411)) { onNewKey(role) },
            wrapWrap().apply { rightMargin = dp(12) },
        )
        keyRow.addView(
            ui.smallButton(activity.getString(R.string.s_412)) { onClearKey(role) },
            wrapWrap(),
        )
        card.addView(keyRow, matchWrap().apply { topMargin = dp(6) })

        val toggleBtn = ui.actionButton(activity.getString(R.string.s_407)) { onToggle(role) }
        card.addView(toggleBtn, matchWrap().apply { topMargin = dp(8) })

        extraNotes.forEach { card.addView(it, matchWrap().apply { topMargin = dp(6) }) }

        val statsTv = TextView(activity).apply {
            textSize = 11.5f
            setTextColor(pal.subText)
            setPadding(0, dp(6), 0, 0)
        }
        card.addView(statsTv, matchWrap())
        card.addView(
            ui.hintText(activity.getString(R.string.s_415)),
            matchWrap().apply { topMargin = dp(6) },
        )

        return ApiBlockView(card, statusTv, addrTv, portEdit, keyTv, toggleBtn, statsTv)
    }

    private fun apiPort(role: ApiServer.Role): Int =
        chatPrefs.getInt(ApiService.keyPort(role), ApiService.defaultPort(role))
            .coerceIn(1024, 65535)

    private fun dp(v: Int): Int =
        UiScale.dpToPx(v, activity.resources.displayMetrics.density)

    private fun matchWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )

    private fun wrapWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
}
