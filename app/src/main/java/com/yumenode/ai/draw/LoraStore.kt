// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai.draw

import android.content.SharedPreferences
import java.io.File

/**
 * Catalogo de LoRA descargadas e importadas (`files/draw/lora/`).
 *
 * Extraido de `DrawPage` para que la seleccion de LoRA —con su regla de
 * prioridad— quede en una clase testeable en JVM.
 *
 * El store no sabe nada de vistas ni de inferencia: solo lista ficheros
 * `.safetensors`, recuerda cual esta seleccionado y lo borra.
 *
 * @param filesDir  raiz de los datos privados de la app (las LoRA van en `filesDir/draw/lora`)
 * @param prefs     preferencias donde se guarda el nombre de la LoRA seleccionada
 */
class LoraStore(
    private val filesDir: File,
    private val prefs: SharedPreferences,
) {

    companion object {
        /** Extension de los ficheros de pesos LoRA. */
        const val LORA_EXT = ".safetensors"

        /** Preferencia con el nombre de la LoRA seleccionada. */
        const val KEY_SEL_LORA = "selLora"

        /** Subdirectorio de la app donde viven las LoRA. */
        const val DIR_NAME = "draw"

        /** Subcarpeta de las LoRA. */
        const val LORA_SUBDIR = "lora"
    }

    /** Directorio de las LoRA (`files/draw/lora`). */
    fun loraDir(): File = File(File(filesDir, DIR_NAME), LORA_SUBDIR).apply { mkdirs() }

    /** LoRA descargadas, ordenadas por nombre. */
    fun list(): List<File> =
        loraDir().listFiles { f -> f.isFile && f.name.endsWith(LORA_EXT, ignoreCase = true) }
            ?.sortedBy { it.name } ?: emptyList()

    /** Hay alguna LoRA disponible. */
    fun hasAny(): Boolean = list().isNotEmpty()

    /** Nombre de la LoRA seleccionada (null si no hay ninguna). */
    fun selectedName(): String? = prefs.getString(KEY_SEL_LORA, null)

    /**
     * La LoRA que esta vigente.
     *
     * La prioridad es: **la seleccionada en esta sesion** > **la que quedo
     * guardada** > **la primera de la lista**.
     *
     * - La seleccion de la sesion manda, pero solo si el fichero sigue
     *   existiendo: si el usuario la borro, se cae al siguiente criterio.
     * - La guardada se busca **por nombre**, no por ruta: el directorio de la
     *   app puede cambiar (restauracion de copia de seguridad) y el nombre es
     *   lo unico estable.
     * - Sin nada elegido se usa la primera, para que una LoRA recien
     *   importada se pueda usar sin tocar nada mas.
     */
    fun active(sessionSelection: File? = null): File? {
        val all = list()
        if (all.isEmpty()) return null
        val sel = sessionSelection
        if (sel != null && sel.isFile && all.any { it.absolutePath == sel.absolutePath }) return sel
        val saved = selectedName()
        if (saved != null) all.firstOrNull { it.name == saved }?.let { return it }
        return all.first()
    }

    /**
     * Recuerda la LoRA seleccionada.
     *
     * Se guarda el **nombre**, no la ruta: la ruta absoluta no sobrevive a una
     * restauracion de la app en otro directorio.
     */
    fun select(f: File) {
        prefs.edit().putString(KEY_SEL_LORA, f.name).apply()
    }

    /**
     * Borra una LoRA.
     *
     * @return true si el fichero se borro
     */
    fun delete(f: File): Boolean = runCatching { f.delete() }.getOrDefault(false)
}
