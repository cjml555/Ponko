// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * Gestión centralizada de los parámetros de chat (context length / temperatura / top-K / top-P /
 * penalización de repetición / presupuesto de razonamiento / semilla).
 *
 * Los parámetros se guardan en SharedPreferences **por modelo** (scoped por nombre de archivo):
 * si no hay modelo seleccionado se usan los valores por defecto sin sufijo. Así cada modelo
 * recuerda su propia configuración.
 *
 * Extraído de MainActivity para reducir el God Object y facilitar pruebas unitarias
 * (no depende de UI — los EditText se siguen manejando en la Activity).
 */
class ChatParams(
    private val context: Context,
    private val modelPathProvider: () -> String?,
) {

    companion object {
        // ---- claves por modelo ----
        const val KEY_CHAT_CTX = "chatCtx"
        const val KEY_CHAT_MAXOUT = "chatMaxOut"
        const val KEY_CHAT_TEMP = "chatTemp"
        const val KEY_CHAT_TOPK = "chatTopK"
        const val KEY_CHAT_TOPP = "chatTopP"
        const val KEY_CHAT_REP = "chatRep"
        const val KEY_CHAT_BUDGET = "chatBudget"
        const val KEY_CHAT_SEED = "chatSeed"

        // ---- valores por defecto ----
        const val DEF_CHAT_THINK = true
        const val DEF_CHAT_CTX = 4096
        const val DEF_CHAT_MAXOUT = 2048
        const val DEF_CHAT_TEMP = 0.7f
        const val DEF_CHAT_TOPK = 40
        const val DEF_CHAT_TOPP = 0.9f
        const val DEF_CHAT_REP = 1.1f
        const val DEF_CHAT_BUDGET = 2048
        const val DEF_CHAT_SEED = -1
    }

    private val prefs: SharedPreferences get() = context.getSharedPreferences("ponko", Context.MODE_PRIVATE)

    /** El modelo que se cargó en memoria (no el seleccionado en UI).
     *  Sirve para decidir si los cambios de parámetro deben marcar dirty. */
    private var loadedModelPath: String? = null

    /** true = los parámetros cambiaron después de cargar el modelo actual → hay que reconstruir la sesión. */
    var dirty: Boolean = false
        private set

    /** true = los valores están cargándose desde código (no por interacción del usuario). */
    var uiLoading: Boolean = false
        private set

    // ── prefijo de clave (scoped por nombre de modelo) ──

    /** Nombre del modelo actual (sin ruta), o null si no hay ninguno seleccionado. */
    fun modelName(): String? = modelPathProvider()?.let { File(it).name }?.ifEmpty { null }

    /** Construye una clave scoped al modelo actual: "base" o "base#ModelName.gguf". */
    private fun key(base: String): String {
        val n = modelName()
        return if (n == null) base else "$base#$n"
    }

    // ── getters con rango válido ──

    fun ctx(): Int = prefs.getInt(key(KEY_CHAT_CTX), DEF_CHAT_CTX).coerceIn(512, 262144)
    fun maxOut(): Int = prefs.getInt(key(KEY_CHAT_MAXOUT), DEF_CHAT_MAXOUT).coerceIn(64, 65536)
    fun temp(): Float = prefs.getFloat(key(KEY_CHAT_TEMP), DEF_CHAT_TEMP).coerceIn(0f, 2f)
    fun topK(): Int = prefs.getInt(key(KEY_CHAT_TOPK), DEF_CHAT_TOPK).coerceIn(0, 500)
    fun topP(): Float = prefs.getFloat(key(KEY_CHAT_TOPP), DEF_CHAT_TOPP).coerceIn(0.01f, 1f)
    fun repPenalty(): Float = prefs.getFloat(key(KEY_CHAT_REP), DEF_CHAT_REP).coerceIn(0.5f, 2f)
    fun budget(): Int = prefs.getInt(key(KEY_CHAT_BUDGET), DEF_CHAT_BUDGET).coerceIn(0, 65536)
    fun seed(): Int = prefs.getInt(key(KEY_CHAT_SEED), DEF_CHAT_SEED)
    fun thinkCheck(): Boolean = prefs.getBoolean(KEY_CHAT_THINK, DEF_CHAT_THINK)

    // ── derivados ──

    /** Input token budget: deja 1/4 del contexto para output. */
    fun inputTokenBudget(): Int = ctx() - ctx() / 4

    /** Tokens máximo por archivo adjunto (escala con el contexto). */
    fun maxFileTokens(): Int = (ctx() / 4).coerceIn(600, 8000)

    /** Presupuesto de pensamiento: 0 = ilimitado (ambos backends usan -1). */
    fun thinkBudgetOrUnlimited(): Int = if (budget() > 0) budget() else -1

    // ── etiqueta del propietario de los parámetros (para la UI) ──

    /** Devuelve el nombre del modelo cuyos parámetros se están editando, o null si usa los globales. */
    fun ownerLabel(): String? = modelName()

    // ── guards de UI ──

    fun setUiLoading(v: Boolean) { uiLoading = v }

    // ── escritura ──

    /**
     * Guarda los parámetros parseados desde los EditText de la Activity.
     * Los valores ya deben estar costringidos a enteros/floats válidos.
     */
    fun save(
        ctx: Int,
        maxOut: Int,
        temp: Float,
        topK: Int,
        topP: Float,
        repPenalty: Float,
        budget: Int,
        seed: Int,
    ) {
        prefs.edit()
            .putInt(key(KEY_CHAT_CTX), ctx)
            .putInt(key(KEY_CHAT_MAXOUT), maxOut)
            .putFloat(key(KEY_CHAT_TEMP), temp)
            .putInt(key(KEY_CHAT_TOPK), topK)
            .putFloat(key(KEY_CHAT_TOPP), topP)
            .putFloat(key(KEY_CHAT_REP), repPenalty)
            .putInt(key(KEY_CHAT_BUDGET), budget)
            .putInt(key(KEY_CHAT_SEED), seed)
            .apply()
        // Marcar dirty sólo si el modelo editado es el que está cargado en memoria.
        dirty = modelPathProvider() != null && modelPathProvider() == loadedModelPath
    }

    /** Restaura los valores por defecto para el modelo actual. */
    fun resetDefaults() {
        prefs.edit()
            .putInt(key(KEY_CHAT_CTX), DEF_CHAT_CTX)
            .putInt(key(KEY_CHAT_MAXOUT), DEF_CHAT_MAXOUT)
            .putFloat(key(KEY_CHAT_TEMP), DEF_CHAT_TEMP)
            .putInt(key(KEY_CHAT_TOPK), DEF_CHAT_TOPK)
            .putFloat(key(KEY_CHAT_TOPP), DEF_CHAT_TOPP)
            .putFloat(key(KEY_CHAT_REP), DEF_CHAT_REP)
            .putInt(key(KEY_CHAT_BUDGET), DEF_CHAT_BUDGET)
            .putInt(key(KEY_CHAT_SEED), DEF_CHAT_SEED)
            .apply()
        dirty = modelPathProvider() != null && modelPathProvider() == loadedModelPath
    }

    /** El modelo fue cargado → recordar ruta para el dirty-check. */
    fun markModelLoaded(path: String?) {
        loadedModelPath = path
    }

    /** El modelo fue descargado/unload → limpiar referencia. */
    fun markModelUnloaded() {
        loadedModelPath = null
    }

    /** Marca dirty explícitamente (usado cuando se detecta que los params cambiaron). */
    fun setDirty(v: Boolean) { dirty = v }
}
