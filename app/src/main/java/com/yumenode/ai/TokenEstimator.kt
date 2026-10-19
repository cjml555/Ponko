// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

/**
 * Estimación de tokens para decidir recortes y presupuestos de contexto.
 *
 * Antes había cuatro copias: `ChatManager.estTokens`, `AttachmentHandler.estTokens`
 * (idénticas), `ApiServer.estimateTokens` y el default de
 * `InferenceParamsBuilder` (ambas `length / 4`). Las dos heurísticas se mantienen
 * porque miden cosas distintas:
 *
 *  - [estimate] cuenta caracteres anchos (CJK) como 1 token y el resto como 0.3;
 *    es la que se usa para decidir si algo cabe en el contexto.
 *  - [fromChars] es la división rápida `chars / 4`, que es la que se reporta en
 *    las respuestas de la API compatible con OpenAI.
 */
object TokenEstimator {

    /** Tokens aproximados de un texto (heurística CJK-aware). */
    fun estimate(text: String): Float {
        var t = 0f
        for (c in text) t += if (c.code in 0x1100..0xFFFF) 1f else 0.3f
        return t
    }

    /** División rápida por longitud; mínimo 1. */
    fun fromChars(chars: Int): Int = (chars / 4).coerceAtLeast(1)
}
