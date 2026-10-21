// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * Formato de la API local compatible con OpenAI.
 *
 * Extraido de `ApiServer` para que el **contrato** con los clientes (agentes,
 * OpenWebUI, Continue...) quede en un sitio comprobable: el formato de las
 * respuestas, el de los chunks SSE, el contenido multimodal y los codigos de
 * error son la superficie publica de la app.
 *
 * No toca sockets: solo construye y lee JSON.
 */
object OpenAiApi {

    // ==================== Peticiones ====================

    /**
     * Un mensaje de la peticion.
     *
     * @param role "system" / "user" / "assistant"
     * @param text contenido ya aplanado (el multimodal se resuelve antes)
     */
    data class Message(val role: String, val text: String)

    /**
     * Lee los mensajes de una peticion `/v1/chat/completions`.
     *
     * El campo `content` puede ser un string o un array de partes
     * (`{type:"text",text:...}`, `{type:"image_url",...}`); las partes de
     * imagen se Reduce a la marca `[image]`, que es lo que ve el modelo.
     */
    fun parseMessages(messages: JSONArray?): List<Message> {
        val out = ArrayList<Message>()
        messages?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(Message(o.optString("role", "user"), contentToText(o.opt("content"))))
            }
        }
        return out
    }

    /**
     * Aplana el contenido de un mensaje a texto.
     *
     * - string → el string tal cual
     * - array de partes → se concatenan las de texto; las imagenes se
     *   sustituyen por `[image]`
     * - null → string vacio
     */
    fun contentToText(content: Any?): String = when (content) {
        null -> ""
        is String -> content
        is JSONArray -> {
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val part = content.optJSONObject(i) ?: continue
                val type = part.optString("type", "text")
                if (type == "text" || type == "input_text") sb.append(part.optString("text"))
                else if (type == "image_url") sb.append("[image]")
            }
            sb.toString()
        }
        else -> content.toString()
    }

    /**
     * Parametros de sampling de la peticion.
     *
     * @param temperature temperatura (null si no se envió)
     * @param maxTokens   maximo de tokens (null si no se envió)
     */
    data class Sampling(val temperature: Float?, val maxTokens: Int?)

    /** Lee temperature / max_tokens / max_completion_tokens de la peticion. */
    fun parseSampling(req: JSONObject): Sampling {
        val temperature = if (req.has("temperature")) req.optDouble("temperature").toFloat() else null
        val maxTokens = if (req.has("max_tokens")) req.optInt("max_tokens")
        else if (req.has("max_completion_tokens")) req.optInt("max_completion_tokens")
        else null
        return Sampling(temperature, maxTokens)
    }

    /**
     * true si la peticion lleva al menos un mensaje de usuario con contenido.
     *
     * Un `system` no cuenta: una peticion solo de sistema no se puede servir.
     */
    fun hasUserMessage(messages: List<Message>): Boolean =
        messages.any { it.role != "system" && it.text.isNotEmpty() }

    // ==================== Respuestas ====================

    /**
     * Cuerpo de una respuesta no-streaming (`chat.completion`).
     *
     * @param id      id de la completacion
     * @param model   nombre del modelo
     * @param created timestamp unix en segundos
     * @param answer  texto generado
     * @param usage   conteo de tokens (prompt / completion / total)
     */
    fun chatCompletion(
        id: String,
        model: String,
        created: Long,
        answer: String,
        usage: JSONObject,
    ): JSONObject = JSONObject()
        .put("id", id).put("object", "chat.completion").put("created", created).put("model", model)
        .put("choices", JSONArray().put(JSONObject()
            .put("index", 0)
            .put("message", JSONObject().put("role", "assistant").put("content", answer))
            .put("finish_reason", "stop")))
        .put("usage", usage)

    /**
     * Cuerpo de un chunk de streaming (`chat.completion.chunk`).
     *
     * @param content contenido del delta
     * @param role    rol del delta (solo en el primer chunk)
     */
    fun chatChunk(
        id: String,
        model: String,
        created: Long,
        content: String,
        role: String? = null,
    ): JSONObject {
        val delta = JSONObject()
        if (role != null) delta.put("role", role)
        delta.put("content", content)
        return JSONObject()
            .put("id", id).put("object", "chat.completion.chunk").put("created", created).put("model", model)
            .put("choices", JSONArray().put(JSONObject()
                .put("index", 0).put("delta", delta)
                .put("finish_reason", JSONObject.NULL)))
    }

    /**
     * Ultimo chunk del streaming: delta vacio y `finish_reason` "stop".
     */
    fun chatChunkFinish(id: String, model: String, created: Long): JSONObject = JSONObject()
        .put("id", id).put("object", "chat.completion.chunk").put("created", created).put("model", model)
        .put("choices", JSONArray().put(JSONObject()
            .put("index", 0).put("delta", JSONObject()).put("finish_reason", "stop")))

    /**
     * Envoltorio de un chunk en el formato de Server-Sent Events.
     *
     * El formato es `data: <json>\n\n`; el cierre del stream es
     * `data: [DONE]\n\n`.
     */
    fun sseEvent(json: JSONObject): String = "data: $json\n\n"

    /** Evento SSE de fin de stream. */
    const val SSE_DONE = "data: [DONE]\n\n"

    // ==================== Errores ====================

    /**
     * Cuerpo de error con el formato de OpenAI:
     * `{"error": {"message": ..., "type": ...}}`.
     */
    fun error(message: String, type: String): JSONObject = JSONObject()
        .put("error", JSONObject().put("message", message).put("type", type))

    /** Tipos de error que usa la API. */
    object ErrorType {
        const val INVALID_REQUEST = "invalid_request_error"
        const val RATE_LIMIT = "rate_limit_error"
        const val SERVER = "server_error"
        const val SERVICE_UNAVAILABLE = "service_unavailable"
    }

    // ==================== Modelos ====================

    /**
     * Lista de modelos en formato OpenAI (`/v1/models`).
     *
     * @param id    id del modelo
     * @param ownedBy propietario declarado
     * @param created timestamp unix en segundos
     */
    fun modelList(id: String, ownedBy: String, created: Long): JSONObject = JSONObject()
        .put("object", "list")
        .put("data", JSONArray().put(JSONObject()
            .put("id", id).put("object", "model").put("created", created).put("owned_by", ownedBy)))
}
