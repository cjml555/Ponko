// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

/**
 * Calculo del progreso de una descarga: velocidad, ETA y politica de
 * reintentos.
 *
 * Extraido de `ModelDownloader` para que estas formulas —que antes vivian
 * entre el bucle de descarga y no se podian comprobar— queden en un objeto
 * puro y testeable en JVM.
 */
object DownloadProgress {

    /** Primer retardo entre reintentos (1 s). */
    const val INITIAL_RETRY_DELAY_MS = 1000L

    /** Numero maximo de intentos por descarga. */
    const val MAX_RETRIES = 3

    /**
     * Retardo antes del reintento `attempt` (1 based), con backoff exponencial.
     *
     * El primer reintento espera 1 s, el segundo 2 s y el tercero 4 s.
     *
     * @param attempt numero de intento que ya ha fallado (1 = el primero)
     */
    fun retryDelayMs(attempt: Int): Long =
        INITIAL_RETRY_DELAY_MS * (1L shl (attempt - 1))

    /**
     * ¿Toca reintentar?
     *
     * @param attempt   numero de intento actual (1 based)
     * @param maxRetries maximo de intentos permitidos
     * @param cancelled si el usuario cancelo
     */
    fun shouldRetry(attempt: Int, maxRetries: Int = MAX_RETRIES, cancelled: Boolean = false): Boolean =
        attempt < maxRetries && !cancelled

    /**
     * Progreso de una descarga en curso.
     *
     * @param downloadedBytes bytes ya descargados
     * @param totalBytes      tamano total conocido (-1 si el servidor no lo dice)
     * @param elapsedMs       tiempo transcurrido desde el inicio
     * @return velocidad, ETA y fraccion de progreso; null donde no aplique
     */
    fun compute(
        downloadedBytes: Long,
        totalBytes: Long,
        elapsedMs: Long,
    ): Snapshot {
        if (totalBytes <= 0L) {
            // El servidor no dice el tamano: no hay progreso que mostrar.
            return Snapshot(
                progress = 0.0,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                speedBytesPerSec = 0.0,
                etaSeconds = null,
            )
        }
        val elapsedSec = elapsedMs / 1000.0
        val speed = if (elapsedSec > 0) downloadedBytes / elapsedSec else 0.0
        val remaining = totalBytes - downloadedBytes
        val eta = if (speed > 0) (remaining / speed).toLong() else null
        return Snapshot(
            progress = downloadedBytes.toDouble() / totalBytes,
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            speedBytesPerSec = speed,
            etaSeconds = eta,
        )
    }

    /**
     * ¿El fichero descargado tiene un tamano aceptable?
     *
     * @param actualBytes tamano del fichero en disco
     * @param totalBytes  tamano declarado por el servidor
     * @param minRatio    fraccion minima aceptable (0.98 = 98 %)
     */
    fun isSizeAcceptable(actualBytes: Long, totalBytes: Long, minRatio: Double = 0.98): Boolean {
        if (totalBytes <= 0L) return true
        return actualBytes >= (totalBytes * minRatio).toLong()
    }

    /**
     * Instantanea del progreso de una descarga.
     *
     * @param progress         fraccion descargada (0.0 a 1.0)
     * @param downloadedBytes  bytes descargados
     * @param totalBytes       tamano total (-1 si se desconoce)
     * @param speedBytesPerSec velocidad media
     * @param etaSeconds       segundos restantes (null si no se puede estimar)
     */
    data class Snapshot(
        val progress: Double,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val speedBytesPerSec: Double,
        val etaSeconds: Long?,
    )
}
