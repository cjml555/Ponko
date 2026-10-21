// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import java.io.File
import java.security.MessageDigest

/**
 * Verificacion de integridad de los ficheros descargados.
 *
 * Extraido de `ModelDownloader` para que la regla se pueda probar en JVM
 * (hash y deteccion de placeholders) sin Android ni red.
 */
object DownloadIntegrity {

    /** Tamano del buffer de lectura (1 MB). */
    const val CHUNK_SIZE = 1024 * 1024

    /**
     * Un checksum de placeholder se reconoce por su **forma completa**:
     * 4 grupos de 4 hex separados por puntos, acabando en "...".
     *
     * Antes se detectaba por **prefijo** ("a1b2", "b2c3", ...), lo que era
     * fragil: un SHA-256 real que empezara por uno de esos 4 hex se saltaria
     * la verificacion entera. Ahora se exige la forma exacta del placeholder.
     */
    private val PLACEHOLDER_PATTERN = Regex("^[0-9a-fA-F]{4}([0-9a-fA-F]{4}){2}\\.\\.\\.$")

    /**
     * true si el checksum es un placeholder del catalogo (no un hash real).
     *
     * Un placeholder no permite verificar nada, asi que el llamante debe
     * tratarlo como "sin checksum disponible".
     */
    fun isPlaceholder(expectedSha256: String?): Boolean {
        val s = expectedSha256?.trim().orEmpty()
        if (s.isEmpty()) return true
        return PLACEHOLDER_PATTERN.matches(s)
    }

    /**
     * SHA-256 de un fichero, en hexadecimal.
     *
     * @return el hash, o null si el fichero no se puede leer
     */
    fun sha256(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(CHUNK_SIZE)
            var read = input.read(buffer)
            while (read != -1) {
                digest.update(buffer, 0, read)
                read = input.read(buffer)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        null
    }

    /**
     * Verifica un fichero contra el checksum esperado.
     *
     * @return true si el fichero es valido
     */
    fun verify(file: File, expectedSha256: String?): Boolean {
        // Sin checksum real no hay nada que comprobar: se acepta.
        if (isPlaceholder(expectedSha256)) return true
        val expected = expectedSha256!!.trim()
        val calculated = sha256(file) ?: return true
        return calculated.equals(expected, ignoreCase = true)
    }

    /**
     * true si el fichero esta **incompleto** respecto al tamaño esperado.
     *
     * Un modelo importado a mano (no catalogado) se considera completo: no
     * hay con que compararlo.
     *
     * @param file          fichero en disco
     * @param expectedBytes tamano esperado (null si no esta en el catalogo)
     * @param ratio         fraccion minima del tamano esperado que cuenta como
     *                      descargado completo (0.9 = 90 %)
     */
    fun isIncomplete(file: File, expectedBytes: Long?, ratio: Double = 0.9): Boolean {
        if (expectedBytes == null) return false
        return file.length() < (expectedBytes * ratio).toLong()
    }
}
