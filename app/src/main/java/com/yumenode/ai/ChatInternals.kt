// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

/** Resultado de una ronda de generación: respuesta y pensamiento. */
data class RoundOut(
    val answer: String,
    val thought: String
)

/**
 * Objeto de retorno cuando se usa una herramienta (ej. tagger) y se devuelve al modelo.
 * - modelText: lo que el modelo generó en esta ronda (para rellenar como mensaje assistant)
 * - text: resultado de la herramienta (se devuelve como system/tool/user según plantilla)
 */
data class ToolReturn(
    val modelText: String,
    val text: String
)

/** Roles candidatos para mensajes de herramienta en GGUF (se prueba en orden). */
val tagRolesGguf = listOf("system", "tool", "user")