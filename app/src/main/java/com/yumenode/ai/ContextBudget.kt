// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

/**
 * Recorte de texto por presupuesto de tokens y estimacion del contexto.
 *
 * Antes `fitTokens` estaba **duplicado byte a byte** en `ChatManager` y en
 * `AttachmentHandler`, y el calculo del presupuesto de conversacion vivia
 * repartido entre `ChatManager` y `MainActivity`. Aqui vive una sola vez, en
 * forma pura (sin Android), y se puede probar en JVM.
 */
object ContextBudget {

    /**
     * Tokens por turno de conversacion.
     *
     * Cada turno gasta ademas de su texto un sobre de unos 12 tokens (marcas
     * de rol, separadores) que el modelo cuenta pero el texto no refleja.
     */
    const val TOKENS_PER_TURN = 12f

    /**
     * Recorta un texto para que no exceda el presupuesto de tokens.
     *
     * Corta por el ultimo salto de linea si esta en la mitad final del
     * fragmento: partir por una linea entera deja el texto legible en lugar
     * de cortarlo a media frase.
     *
     * @param text      texto a recortar
     * @param maxTokens presupuesto maximo
     * @return el texto recortado (o el original si ya cabe)
     */
    fun fitTokens(text: String, maxTokens: Int): String {
        if (TokenEstimator.estimate(text) <= maxTokens) return text
        var acc = 0f
        var cut = text.length
        for (i in text.indices) {
            acc += if (text[i].code in 0x1100..0xFFFF) 1f else 0.3f
            if (acc > maxTokens) {
                cut = i
                break
            }
        }
        var body = text.substring(0, cut)
        val nl = body.lastIndexOf('\n')
        if (nl > cut / 2) body = body.substring(0, nl)
        return body
    }

    /**
     * Tokens que ocupa una conversacion completa.
     *
     * Incluye el prompt de la herramienta de dibujo (si esta disponible), el
     * historial de turnos (con el sobre por turno) y el contenido del mensaje
     * actual.
     *
     * @param session        conversacion con su historial
     * @param extra          contenido del mensaje que se va a enviar
     * @param dropLast       si se excluye el ultimo turno (se va a repetir)
     * @param canDrawFromChat si la herramienta de dibujo esta disponible
     * @param drawToolPrompt prompt de la herramienta de dibujo
     */
    fun estimateConversationTokens(
        session: ChatSession,
        extra: String,
        dropLast: Boolean,
        canDrawFromChat: Boolean,
        drawToolPrompt: String,
    ): Float {
        var t = 0f
        if (canDrawFromChat) t += TokenEstimator.estimate(drawToolPrompt) + TOKENS_PER_TURN
        val upto = if (dropLast) session.turns.size - 1 else session.turns.size
        for (i in 0 until maxOf(upto, 0)) {
            val tt = session.turns[i]
            t += TokenEstimator.estimate(tt.user) + TokenEstimator.estimate(tt.answer) + TOKENS_PER_TURN
            tt.fileText?.let { t += TokenEstimator.estimate(it) }
        }
        t += TokenEstimator.estimate(extra) + TOKENS_PER_TURN
        return t
    }
}
