// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import net.ladenthin.llama.parameters.InferenceParameters
import net.ladenthin.llama.value.ChatMessage
import net.ladenthin.llama.value.ContentPart
import net.ladenthin.llama.value.Pair as LamaPair

/**
 * Construcción de parámetros de inferencia (InferenceParameters / ConversationConfig).
 *
 * Extraído de MainActivity: encapsula la lógica de construcción de parámetros
 * para ambos backends (gguf / litertlm), incluyendo la inyección de prompts
 * de herramientas (draw/tag), el modo de pensamiento y la semilla aleatoria.
 *
 * Las lambdas de dependencia permiten que el builder controle la lógica mientras
 * MainActivity sigue dueño de las vistas y el estado de UI.
 */
class InferenceParamsBuilder(
    private val chatParams: ChatParams,
    private val drawToolPrompt: String,
    private val canDrawFromChat: () -> Boolean,
    private val canTagTurn: (QaTurn?) -> Boolean,
    private val tagHint: (QaTurn?) -> String,
    private val thinkChecked: () -> Boolean,
) {

    // ── gguf (llama.cpp) ──

    /**
     * Construye parámetros de inferencia para gguf (texto puro).
     * @param s sesión de chat
     * @param extraMsgs mensajes extra al final (p.ej. retorno de herramientas)
     * @param maxTurns máximo de turnos de historia a incluir
     */
    fun buildInferenceParams(
        s: ChatSession,
        extraMsgs: List<LamaPair<String, String>> = emptyList(),
        maxTurns: Int = 24,
    ): InferenceParameters {
        val msgs = mutableListOf<LamaPair<String, String>>()
        val turns = s.turns.takeLast(maxTurns)
        for ((ti, t) in turns.withIndex()) {
            var userText = t.user
            if (ti == turns.size - 1) {
                t.fileText?.let { if (it.isNotEmpty()) userText += "\n\n" + it }
                if (canTagTurn(t)) userText += "\n\n" + tagHint(t)
                if (canDrawFromChat()) userText += "\n\n" + drawToolPrompt
            }
            msgs += LamaPair("user", userText)
            val isLast = ti == turns.size - 1
            if (t.answer.isNotEmpty() && !(isLast && extraMsgs.isNotEmpty())) msgs += LamaPair("assistant", t.answer)
        }
        extraMsgs.forEach { msgs += LamaPair(it.key, it.value) }
        if (canDrawFromChat() && msgs.isEmpty()) msgs += LamaPair("user", drawToolPrompt)
        var p = InferenceParameters.empty()
            .withMessages(null, msgs)
            .withCachePrompt(true)
            .withSlotId(0)
            .withNPredict(chatParams.maxOut())
            .withTemperature(chatParams.temp())
            .withTopK(chatParams.topK())
            .withTopP(chatParams.topP())
            .withRepeatPenalty(chatParams.repPenalty())
            .withSeed(if (chatParams.seed() < 0) (System.nanoTime() and 0x7FFFFFFF).toInt() else chatParams.seed())
        p = if (thinkChecked()) {
            p.withReasoningBudgetTokens(chatParams.thinkBudgetOrUnlimited())
        } else {
            p.withReasoningBudgetTokens(0)
                .withChatTemplateKwargs(mapOf("enable_thinking" to "false"))
        }
        return p
    }

    /**
     * Construye parámetros de inferencia multimodal para gguf (texto + imágenes).
     */
    fun buildMultimodalParams(
        s: ChatSession,
        images: List<ByteArray>,
        extraMsgs: List<LamaPair<String, String>> = emptyList(),
        maxTurns: Int = 24,
    ): InferenceParameters {
        val turns = s.turns.takeLast(maxTurns)
        val msgs = mutableListOf<ChatMessage>()
        for ((ti, t) in turns.withIndex()) {
            if (ti == turns.size - 1) {
                val parts = ArrayList<ContentPart>()
                var body = t.user
                t.fileText?.let { if (it.isNotEmpty()) body += "\n\n" + it }
                if (canTagTurn(t)) body += "\n\n" + tagHint(t)
                if (canDrawFromChat()) body += "\n\n" + drawToolPrompt
                if (body.isNotEmpty()) parts.add(ContentPart.text(body))
                images.forEach { parts.add(ContentPart.imageBytes(it, "image/jpeg")) }
                msgs += ChatMessage.userMultimodal(*parts.toTypedArray())
            } else {
                msgs += ChatMessage("user", t.user)
            }
            val isLast = ti == turns.size - 1
            if (t.answer.isNotEmpty() && !(isLast && extraMsgs.isNotEmpty())) msgs += ChatMessage("assistant", t.answer)
        }
        extraMsgs.forEach { msgs += ChatMessage(it.key, it.value) }
        var p = InferenceParameters.empty()
            .withMessages(msgs)
            .withCachePrompt(true)
            .withSlotId(0)
            .withNPredict(chatParams.maxOut())
            .withTemperature(chatParams.temp())
            .withTopK(chatParams.topK())
            .withTopP(chatParams.topP())
            .withRepeatPenalty(chatParams.repPenalty())
            .withSeed(if (chatParams.seed() < 0) (System.nanoTime() and 0x7FFFFFFF).toInt() else chatParams.seed())
        p = if (thinkChecked()) {
            p.withReasoningBudgetTokens(chatParams.thinkBudgetOrUnlimited())
        } else {
            p.withReasoningBudgetTokens(0)
                .withChatTemplateKwargs(mapOf("enable_thinking" to "false"))
        }
        return p
    }

    // ── litertlm ──

    /**
     * Construye configuración de sesión LiteRT a partir del historial de chat.
     * @param s sesión de chat
     * @param thinking modo de pensamiento activado
     * @param dropLast descartar el último turno (para presupuesto de tokens)
     * @param reserveTokens tokens a reservar para la respuesta
     */
    fun configFor(
        s: ChatSession,
        thinking: Boolean,
        dropLast: Boolean = false,
        reserveTokens: Int = 900,
    ): ConversationConfig {
        val msgs = mutableListOf<Message>()
        if (canDrawFromChat()) msgs += Message.system(drawToolPrompt)
        var budget = chatParams.inputTokenBudget() -
            (if (canDrawFromChat()) estimateTokens(drawToolPrompt) else 0) - reserveTokens
        val upto = if (dropLast) s.turns.size - 1 else s.turns.size
        val keep = ArrayList<QaTurn>()
        for (i in upto - 1 downTo 0) {
            val t = s.turns[i]
            val cost = estimateTokens(t.user) + estimateTokens(t.answer) + 12
            if (cost > budget && keep.isNotEmpty()) break
            budget -= cost
            keep.add(0, t)
        }
        for (t in keep) {
            msgs += Message.user(t.user)
            if (t.answer.isNotEmpty()) msgs += Message.model(t.answer)
        }
        return ConversationConfig(
            initialMessages = msgs,
            samplerConfig = SamplerConfig(
                seed = if (chatParams.seed() < 0) (System.nanoTime() and 0x7FFFFFFF).toInt() else chatParams.seed(),
                topP = chatParams.topP().toDouble(),
                temperature = chatParams.temp().toDouble(),
                topK = chatParams.topK(),
            ),
            thinkingConfig = ThinkingConfig(enableThinking = thinking, thinkingTokenBudget = chatParams.thinkBudgetOrUnlimited()),
        )
    }

    /** Estimación de tokens (delegada a ChatManager en MainActivity). */
    var estimateTokens: (String) -> Int = { TokenEstimator.fromChars(it.length) }
}
