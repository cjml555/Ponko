// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai.draw

import android.content.SharedPreferences
import java.io.File

/**
 * Un modelo de dibujo: de un solo archivo (un `.gguf` suelto) o multiarchivo
 * (modelo principal + archivos de acompanamiento: vae, clip_l, ...).
 *
 * Antes vivia como clase anidada dentro de `DrawPage`; ahora es una clase de
 * nivel de archivo para que el store ([ModelSetStore]) y sus tests puedan
 * usarla sin arrastrar la Activity.
 *
 * @param id      identificador unico del set ("legacy:archivo.gguf" para los sueltos)
 * @param name    nombre visible
 * @param kind    "single" o "multi" ([ModelSetStore.KIND_SINGLE] / [ModelSetStore.KIND_MULTI])
 * @param family  familia del modelo ("sd15", "sdxl", "flux", ...)
 * @param dir     directorio del set (null para los archivos sueltos legacy)
 * @param files   slots -> fichero. "model" = principal, "vae"/"clip_l"/... = acompanamiento.
 */
class ModelSet(
    val id: String,
    var name: String,
    var kind: String,
    var family: String,
    val dir: File?,
    val files: MutableMap<String, File> = LinkedHashMap(),
) {
    /**
     * Fichero principal del set. El ultimo fallback tiene que saltarse `taesd`:
     * es un accesorio opcional y un set que solo tenga taesd no debe tomar su
     * autoencoder por el modelo principal.
     */
    fun mainFile(): File? = files["model"] ?: files["diffusion"]
        ?: files.entries.firstOrNull { it.key != "taesd" }?.value

    /** Tamano total de los ficheros del set. */
    fun sizeBytes(): Long = files.values.sumOf { it.length() }

    /** Numero de slots ocupados. */
    fun slotCount(): Int = files.size
}

/**
 * Persistencia de los model sets de dibujo (`files/draw/sets/` + un JSON por set).
 *
 * Extraido de `DrawPage` para que la logica de disco y JSON -que antes no
 * tenia ninguna cobertura- quede en una clase testeable en JVM.
 *
 * El store no sabe nada de vistas ni de inferencia: solo lista, lee, escribe
 * y borra sets, y recuerda cual esta seleccionado.
 *
 * @param filesDir  raiz de los datos privados de la app (los sets van en `filesDir/draw`)
 * @param prefs     preferencias donde se guarda el id del set seleccionado
 */
class ModelSetStore(
    private val filesDir: File,
    private val prefs: SharedPreferences,
) {

    companion object {
        /** Un solo fichero `.gguf`. */
        const val KIND_SINGLE = "single"

        /** Modelo principal + ficheros de acompanamiento (vae, clip_l, ...). */
        const val KIND_MULTI = "multi"

        /** Nombre del fichero JSON con la definicion de un set. */
        const val SET_JSON_NAME = "ponko-set.json"

        /** Preferencia con el id del set seleccionado. */
        const val KEY_SEL_SET = "selSet"

        /** Preferencia con la ruta absoluta del TAESD global. */
        const val KEY_TAESD_PATH = "taesdPath"

        /** Tamano minimo razonable del fichero TAESD (4.9 MB en la practica). */
        const val TAESD_MIN_BYTES = 1_000_000L

        /** Subdirectorio de la app donde viven los sets. */
        const val DIR_NAME = "draw"

        /**
         * Normaliza un nombre a un id de directorio seguro.
         *
         * Sustituye todo lo que no sea alfanumerico, punto, guion o subrayado
         * por `_`, quita los guiones bajos sobrantes del principio y del final
         * y corta a 48 caracteres (limite de longitud de nombre de fichero).
         */
        fun safeId(s: String): String {
            val t = s.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_').take(48)
            return t.ifEmpty { "set" }
        }
    }

    /** Directorio que contiene los sets (`files/draw/sets`). */
    private fun setsDir(): File = File(File(filesDir, DIR_NAME), "sets").apply { mkdirs() }

    /**
     * Genera un id de set libre: si `base` ya existe como directorio, se anade
     * un sufijo numerico (-2, -3, ...) hasta encontrar uno libre.
     */
    private fun newSetId(base: String): String {
        val b = safeId(base)
        var id = b
        var i = 2
        while (File(setsDir(), id).exists()) { id = "$b-$i"; i++ }
        return id
    }

    /**
     * Escribe la definicion del set en `<dir>/ponko-set.json`.
     *
     * Los ficheros que viven dentro del set se guardan por **nombre**; los de
     * fuera (un modelo legacy promocionado a set) se guardan por **ruta
     * absoluta** - al leer se distinguen con `File.isAbsolute`.
     */
    private fun writeSetJsonInternal(s: ModelSet) {
        val d = s.dir ?: return
        runCatching {
            val o = org.json.JSONObject()
            o.put("name", s.name)
            o.put("kind", s.kind)
            o.put("family", s.family)
            val a = org.json.JSONObject()
            s.files.forEach { (k, f) ->
                val inSet = f.parentFile?.absoluteFile == d.absoluteFile
                a.put(k, if (inSet) f.name else f.absolutePath)
            }
            o.put("slots", a)
            File(d, SET_JSON_NAME).writeText(o.toString())
        }
    }

    /**
     * Lee la definicion de un set desde su directorio.
     *
     * Devuelve null si el JSON no existe o esta corrupto. Los slots cuyo
     * fichero ya no esta en disco se descartan (el set se listara sin ellos).
     */
    private fun readSetJson(dir: File): ModelSet? {
        val o = runCatching {
            org.json.JSONObject(File(dir, SET_JSON_NAME).readText())
        }.getOrNull() ?: return null
        val files = LinkedHashMap<String, File>()
        o.optJSONObject("slots")?.let { a ->
            val keys = a.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val fn = a.optString(k, "")
                val stored = File(fn)
                val f = if (stored.isAbsolute) stored else File(dir, fn)
                if (fn.isNotEmpty() && f.isFile) files[k] = f
            }
        }
        return ModelSet(
            dir.name, o.optString("name", dir.name),
            o.optString("kind", KIND_MULTI), o.optString("family", "custom"), dir, files
        )
    }

    /**
     * Modelos de dibujo disponibles: los sets nuevos (subdirectorios de
     * `files/draw/sets`) mas los ficheros sueltos legacy (`.gguf` en `files/draw`).
     */
    fun listSets(): List<ModelSet> {
        val out = ArrayList<ModelSet>()
        setsDir().listFiles { f -> f.isDirectory }?.sortedByDescending { it.lastModified() }?.forEach { d ->
            readSetJson(d)?.let { out.add(it) }
        }
        val loose = File(filesDir, DIR_NAME).listFiles { f ->
            f.isFile && f.name.endsWith(".gguf", true) && !f.name.startsWith(".")
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
        // Un .gguf cuyo nombre contiene "vae" es el VAE suelto que acompana al
        // modelo, no un modelo por si mismo: se adjunta al set y no se lista aparte.
        val looseVae = loose.firstOrNull { it.name.contains("vae", true) }
        for (f in loose) {
            if (f === looseVae) continue
            val files = LinkedHashMap<String, File>()
            files["model"] = f
            if (looseVae != null) files["vae"] = looseVae
            out.add(ModelSet("legacy:${f.name}", f.nameWithoutExtension, KIND_SINGLE, "other", null, files))
        }
        return out
    }

    /**
     * Escribe la definicion de un set en su JSON.
     *
     * Publica porque la importacion de ficheros crea el set y luego anade
     * slots, y porque el store es el unico que sabe escribir este formato.
     */
    fun writeSetJson(s: ModelSet) = writeSetJsonInternal(s)

    /** Id del set seleccionado (null si no hay ninguno). */
    fun selectedSetId(): String? = prefs.getString(KEY_SEL_SET, null)

    /** Recuerda que este set es el seleccionado. */
    fun selectSet(s: ModelSet) {
        prefs.edit().putString(KEY_SEL_SET, s.id).apply()
    }

    /**
     * Borra un set: su directorio entero (set nuevo) o sus ficheros sueltos
     * (legacy). Si era el seleccionado, se olvida la seleccion.
     */
    fun deleteSet(s: ModelSet): Boolean {
        val ok = if (s.dir != null) runCatching { s.dir.deleteRecursively() }.getOrDefault(false)
        else s.files.values.all { runCatching { it.delete() }.getOrDefault(false) }
        if (selectedSetId() == s.id) prefs.edit().remove(KEY_SEL_SET).apply()
        return ok
    }

    /**
     * Quita un slot del set **sin borrar el fichero**: el mismo fichero puede
     * estar en varios slots y en varios sets.
     */
    fun clearSlot(s: ModelSet, key: String) {
        s.files.remove(key)
        writeSetJson(s)
    }

    /** Ruta del TAESD global (preferencia) o null si no hay. */
    fun taesdPath(): String? = prefs.getString(KEY_TAESD_PATH, null)

    /** Guarda la ruta del TAESD global. */
    fun setTaesdPath(path: String) {
        prefs.edit().putString(KEY_TAESD_PATH, path).apply()
    }

    /**
     * Fichero TAESD descargado, o null si no existe o es demasiado pequeno
     * (una descarga a medias no sirve).
     */
    fun taesdFile(): File? {
        val f = File(File(File(filesDir, DIR_NAME), "taesd"), "taesd.safetensors")
        return if (f.exists() && f.length() > TAESD_MIN_BYTES) f else null
    }

    /**
     * Crea el directorio de un set nuevo con id libre y lo devuelve.
     * Lo usa la importacion de ficheros para abrir un set nuevo.
     */
    fun createSetDir(base: String): File {
        val id = newSetId(base)
        return File(setsDir(), id).apply { mkdirs() }
    }
}
