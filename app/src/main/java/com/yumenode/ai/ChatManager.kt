// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.content.Context
import android.graphics.Bitmap
import com.yumenode.ai.QaTurn
import com.yumenode.ai.ChatSession

/**
 * ChatManager — gestiona la preparación de mensajes antes del envío.
 * 
 * Mantiene la lógica de construcción de texto, manejo de archivos y estimación de tokens
 * separada de la UI de MainActivity.
 */
class ChatManager(
    private val context: Context,
    private val attachmentHandler: AttachmentHandler
) {

    /** Resultado preparado para enviar un mensaje */
    data class SendRequest(
        val text: String,
        val attachBmps: List<Bitmap>,
        val attachJpegs: List<ByteArray>,
        val fileBlock: String,
        val fileNames: List<String>
    )

    /**
     * Prepara los datos para enviar un mensaje con texto e adjuntos.
     * Devuelve null si no hay nada que enviar (texto vacío y sin adjuntos).
     */
    fun prepareSend(text: String): SendRequest? {
        val attachBmps = attachmentHandler.getPendingImages().map { it.bmp }
        val attachJpegs = attachmentHandler.getPendingImages().map { it.jpeg }
        
        if (text.isEmpty() && attachJpegs.isEmpty() && attachmentHandler.getPendingFiles().isEmpty()) {
            return null
        }
        
        val fileBlock = buildFileBlock(attachmentHandler.getPendingFiles())
        val fileNames = attachmentHandler.getPendingFiles().map { it.name }
        
        val sendText = when {
            fileBlock.isEmpty() -> text
            text.isEmpty() -> fileBlock
            else -> text + "\n\n" + fileBlock
        }
        
        return SendRequest(sendText, attachBmps, attachJpegs, fileBlock, fileNames)
    }

    // ==================== File Block Building ====================

    /** Construye el bloque de texto a partir de archivos adjuntos. */
    fun buildFileBlock(files: List<AttachmentHandler.PendingFile>): String {
        if (files.isEmpty()) return ""
        val sb = StringBuilder()
        for (f in files) {
            sb.append("【文件：").append(f.name)
            if (f.truncated) sb.append("（已截断，以下为前 ").append(f.text.length).append(" 字）")
            sb.append("】\n")
            sb.append(f.text.trim())
            sb.append("\n【文件结束】\n")
        }
        sb.append(context.getString(R.string.s_300))
        return sb.toString().trim()
    }

    /** Codifica archivos adjuntos a JSON para almacenamiento temporal. */
    fun encodeFiles(files: List<AttachmentHandler.PendingFile>): String? {
        if (files.isEmpty()) return null
        return try {
            val arr = org.json.JSONArray()
            for (f in files) {
                arr.put(
                    org.json.JSONObject()
                        .put("n", f.name)
                        .put("b", f.bytes)
                        .put("t", f.text)
                        .put("tr", f.truncated)
                        .put("d", f.detail ?: "")
                )
            }
            arr.toString()
        } catch (_: Throwable) {
            null
        }
    }

    // ==================== Token Estimation ====================

    /** Estima tokens en un texto (aproximado). Delegado en [TokenEstimator]. */
    fun estTokens(s: String): Float = TokenEstimator.estimate(s)

    /** Recorta texto para no exceder maxTokens */
    fun fitTokens(text: String, maxTokens: Int): String {
        if (estTokens(text) <= maxTokens) return text
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
     * Estima tokens de la conversación completa.
     * Incluye prompt de sistema, historial de turnos y contenido del mensaje actual.
     */
    fun estimateConversationTokens(
        session: ChatSession, 
        extra: String, 
        dropLast: Boolean,
        canDrawFromChat: Boolean,
        drawToolPrompt: String
    ): Float {
        var t = 0f
        if (canDrawFromChat) t += estTokens(drawToolPrompt) + 12f
        val upto = if (dropLast) session.turns.size - 1 else session.turns.size
        for (i in 0 until maxOf(upto, 0)) {
            val tt = session.turns[i]
            t += estTokens(tt.user) + estTokens(tt.answer) + 12f
            tt.fileText?.let { t += estTokens(it) }
        }
        t += estTokens(extra) + 12f
        return t
    }

    /** Borra los adjuntos después del envío */
    fun clearPendingAttachments() {
        attachmentHandler.clearPendingImages()
        attachmentHandler.clearPendingFiles()
    }
}
