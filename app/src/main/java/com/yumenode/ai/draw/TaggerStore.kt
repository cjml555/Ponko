// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai.draw

import android.content.SharedPreferences
import java.io.File

/**
 * Catalogo del tagger (modelo ONNX + tabla de tags CSV) en `files/tagger/`.
 *
 * Extraido de `DrawPage` para que la seleccion de ficheros quede en una clase
 * testeable en JVM.
 *
 * @param filesDir  raiz de los datos privados de la app (el tagger va en `filesDir/tagger`)
 * @param prefs     preferencias donde se guardan los nombres seleccionados
 */
class TaggerStore(
    private val filesDir: File,
    private val prefs: SharedPreferences,
) {

    companion object {
        /** Extension del modelo de tagger. */
        const val MODEL_EXT = ".onnx"

        /** Extension de la tabla de tags. */
        const val CSV_EXT = ".csv"

        /** Subdirectorio de la app donde viven los ficheros del tagger. */
        const val TAGGER_DIR = "tagger"

        /** Preferencia con el nombre del modelo seleccionado. */
        const val KEY_SEL_MODEL = "selTaggerModel"

        /** Preferencia con el nombre de la tabla de tags seleccionada. */
        const val KEY_SEL_CSV = "selTaggerCsv"
    }

    /** Directorio del tagger (`files/tagger`). */
    fun dir(): File = File(filesDir, TAGGER_DIR).apply { mkdirs() }

    /** Modelos ONNX importados, ordenados por nombre. */
    fun listModels(): List<File> =
        dir().listFiles { f -> f.isFile && f.name.endsWith(MODEL_EXT, true) }
            ?.sortedBy { it.name } ?: emptyList()

    /** Tablas de tags importadas, ordenadas por nombre. */
    fun listCsvs(): List<File> =
        dir().listFiles { f -> f.isFile && f.name.endsWith(CSV_EXT, true) }
            ?.sortedBy { it.name } ?: emptyList()

    /**
     * Elige el fichero de la lista segun la preferencia.
     *
     * La preferencia guarda el **nombre**: la ruta absoluta no sobrevive a una
     * restauracion de la app. Si el nombre guardado ya no existe (el usuario
     * lo borro), se cae al primero de la lista.
     */
    private fun pickSelected(all: List<File>, key: String): File? {
        if (all.isEmpty()) return null
        val saved = prefs.getString(key, null)
        return all.firstOrNull { it.name == saved } ?: all.first()
    }

    /** Modelo ONNX seleccionado (null si no hay ninguno importado). */
    fun modelFile(): File? = pickSelected(listModels(), KEY_SEL_MODEL)

    /** Tabla de tags seleccionada (null si no hay ninguna importada). */
    fun csvFile(): File? = pickSelected(listCsvs(), KEY_SEL_CSV)

    /** Nombre del modelo seleccionado (null si no hay). */
    fun currentModelName(): String? = modelFile()?.name

    /** Nombre de la tabla seleccionada (null si no hay). */
    fun currentCsvName(): String? = csvFile()?.name

    /** Recuerda el modelo seleccionado. */
    fun selectModel(f: File) {
        prefs.edit().putString(KEY_SEL_MODEL, f.name).apply()
    }

    /** Recuerda la tabla de tags seleccionada. */
    fun selectCsv(f: File) {
        prefs.edit().putString(KEY_SEL_CSV, f.name).apply()
    }
}

/**
 * Estado del tagger en memoria.
 *
 * Lo que hay cargado ahora mismo: si el engine tiene una sesion, con que
 * modelo, con que tabla de tags y con que backend.
 *
 * @param engineLoaded     el engine tiene una sesion cargada
 * @param loadedModelName  nombre del modelo cargado (null si no hay)
 * @param loadedCsvName    nombre de la tabla cargada (null si no hay)
 * @param loadedWithGpu    si el tagger cargado usa el backend GPU
 */
data class TaggerSession(
    val engineLoaded: Boolean,
    val loadedModelName: String?,
    val loadedCsvName: String?,
    val loadedWithGpu: Boolean,
) {
    companion object {
        /** Sesion vacia: no hay ningun tagger cargado. */
        val EMPTY = TaggerSession(false, null, null, false)
    }
}

/**
 * Lo que el usuario quiere usar ahora con el tagger.
 *
 * @param modelName  nombre del modelo elegido (null si no hay ninguno importado)
 * @param csvName    nombre de la tabla elegida (null si no hay ninguna importada)
 * @param wantGpu    si se quiere el backend GPU
 */
data class TaggerSelection(
    val modelName: String?,
    val csvName: String?,
    val wantGpu: Boolean,
) {
    /** El usuario no ha importado modelo o tabla: no hay nada que cargar. */
    val missingFiles: Boolean get() = modelName == null || csvName == null

    /**
     * El tagger ya cargado sirve para la seleccion actual.
     *
     * Es true solo si: hay modelo y tabla, el engine tiene una sesion, el
     * modelo y la tabla son los que quiere el usuario, y el backend coincide.
     *
     * Si devuelve false hay que **cargar** (o el usuario debe ir a cargar el
     * modelo a mano). Es la forma positiva a proposito: la version negativa
     * (`needsReload`) se invierte con facilidad al conectar los dos call
     * sites, y la inversion produce un bug silencioso: cargar cuando ya
     * estaba listo.
     */
    fun isReadyWith(session: TaggerSession): Boolean {
        if (missingFiles) return false
        if (!session.engineLoaded) return false
        if (session.loadedModelName != modelName) return false
        if (session.loadedCsvName != csvName) return false
        if (session.loadedWithGpu != wantGpu) return false
        return true
    }
}
