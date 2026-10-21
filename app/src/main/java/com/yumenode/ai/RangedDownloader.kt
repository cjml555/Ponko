// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Descarga por rangos: pide el fichero en varios tramos en paralelo y los
 * ensambla en un unico fichero.
 *
 * Por que:
 *  - Un unico flujo HTTP suele quedarse por debajo del ancho de banda
 *    disponible (el cuello de botella es la latencia, no el ancho de banda).
 *    Varios tramos a la vez lo aprovechan.
 *  - Si se interrumpe, se reanuda desde el ultimo tramo completo en vez de
 *    repetir el fichero entero.
 *
 * Como funciona:
 *  1. Se pide el tamano total y si el servidor acepta rangos
 *     (`Accept-Ranges: bytes`).
 *  2. Se parte el fichero en tramos con [RangePlan] y se piden en paralelo.
 *  3. Cada tramo se escribe en su offset dentro del fichero destino, que
 *     se preasigna al tamano total.
 *  4. Un fichero `.parts` guarda que tramos ya se completaron, para poder
 *     reanudar tras un corte.
 *
 * Si el servidor NO acepta rangos, [download] cae a la descarga normal de
 * un solo flujo: el resultado es identico, solo que sin paralelismo.
 */
class RangedDownloader(
    private val bufferSize: Int = 1024 * 1024,
    private val connectTimeoutMs: Int = 30000,
    private val readTimeoutMs: Int = 60000,
    /**
     * Tamano minimo de un tramo. Por defecto 8 MB (un tramo mas pequeno no
     * compensa el overhead de la peticion HTTP). Se expone para poder
     * probar la division con ficheros pequenos.
     */
    private val minSegmentBytes: Long = RangePlan.MIN_SEGMENT_BYTES,
) {

    companion object {
        /** Codigos de redireccion que hay que seguir a mano. */
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        /** Maximo de saltos antes de rendirse. */
        const val MAX_REDIRECTS = 5
    }

    /**
     * Resultado de la descarga por rangos.
     *
     * @param success  si se completo
     * @param error    mensaje de error (null si tuvo exito)
     * @param usedRanges si se_download_ en varios tramos o en uno solo
     */
    data class Result(
        val success: Boolean,
        val error: String? = null,
        val usedRanges: Boolean = false,
    )

    /**
     * Descarga `url` a `destFile`.
     *
     * @param url        URL del fichero
     * @param destFile   fichero destino (se sobrescribe)
     * @param totalBytes tamano esperado; si es <= 0 se pregunta al servidor
     * @param onProgress bytes descargados, total, y si se ha ensamblado todo
     * @param isCancelled se consulta para poder abortar
     */
    fun download(
        url: String,
        destFile: File,
        totalBytes: Long,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        isCancelled: () -> Boolean,
    ): Result {
        destFile.parentFile?.mkdirs()
        val partsFile = File(destFile.parentFile, destFile.name + ".parts")

        // 1) Ver si el servidor admite rangos y cual es el tamano real.
        val probe = probe(url)
        if (probe.error != null) return Result(false, probe.error)

        val total = if (totalBytes > 0L) totalBytes else probe.totalBytes
        if (total <= 0L) return Result(false, "No se pudo conocer el tamaño del fichero")

        if (!probe.acceptsRanges) {
            // Sin soporte de rangos: descarga normal, de un solo flujo.
            return downloadSingleStream(url, destFile, total, onProgress, isCancelled)
        }

        // 2) Repartir en tramos y ver cuales ya estaban hechos (reanudacion).
        val count = RangePlan.segmentCount(total, minSegmentBytes = minSegmentBytes)
        val segments = RangePlan.segments(total, count)
        val done = readCompletedParts(partsFile)
        val pending = RangePlan.pending(segments, done)
        if (pending.isEmpty()) {
            onProgress(total, total)
            return Result(true, usedRanges = true)
        }

        // 3) Preasignar el fichero al tamano total y escribir cada tramo en
        //    su offset. RandomAccessFile permite escribir en cualquier punto.
        val downloaded = AtomicLong(0L)
        RandomAccessFile(destFile, "rw").use { raf ->
            raf.setLength(total)
            val channel = raf.channel

            val workers = pending.map { seg ->
                Thread {
                    try {
                        val got = fetchSegment(url, seg, channel, downloaded, total, onProgress, isCancelled)
                        if (got > 0) markPartDone(partsFile, seg.index)
                    } catch (_: Exception) {
                        // Un tramo fallido se reintentara en la siguiente
                        // invocacion (queda sin marcar en el .parts).
                    }
                }
            }
            workers.forEach { it.start() }
            workers.forEach { it.join() }
        }

        if (isCancelled()) {
            return Result(false, "Cancelado", usedRanges = true)
        }

        // 4) ¿Se descargaron todos los bytes? Si no, algo fallo.
        val stillPending = RangePlan.pending(segments, readCompletedParts(partsFile))
        if (stillPending.isNotEmpty()) {
            return Result(
                false,
                "Faltan ${stillPending.size} tramo(s) por descargar",
                usedRanges = true,
            )
        }
        if (destFile.length() != total) {
            return Result(false, "Tamaño inesperado: ${destFile.length()} de $total", usedRanges = true)
        }

        partsFile.delete()
        onProgress(total, total)
        return Result(true, usedRanges = true)
    }

    /**
     * Abre una conexion y sigue las redirecciones a mano.
     *
     * `HttpURLConnection` NO sigue los 308 (Permanent Redirect) y el mirror
     * de HuggingFace los usa: `hf-mirror.com` responde 308 y redirige a
     * `huggingface.co`. Sin esto, la descarga falla con "HTTP 308" nada
     * mas empezar.
     *
     * @param url      URL inicial
     * @param range    cabecera Range a enviar (null para el sondeo)
     * @return la conexion ya situada en la respuesta final
     */
    private fun openFollowingRedirects(
        url: String,
        range: String?,
    ): HttpURLConnection {
        var current = url
        var conn: HttpURLConnection? = null
        for (hop in 0..MAX_REDIRECTS) {
            conn?.disconnect()
            conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                requestMethod = "GET"
                instanceFollowRedirects = true
                if (range != null) setRequestProperty("Range", range)
            }
            val code = conn.responseCode
            if (code !in REDIRECT_CODES) return conn
            val location = conn.getHeaderField("Location")
            if (location.isNullOrBlank()) return conn
            // Redireccion relativa: se resuelve contra la URL actual.
            current = URL(URL(current), location).toString()
        }
        return conn!!
    }

    /** Datos de la respuesta inicial del servidor. */
    private data class Probe(
        val acceptsRanges: Boolean,
        val totalBytes: Long,
        val error: String? = null,
    )

    /**
     * Consulta al servidor: ¿acepta rangos? y ¿de que tamano es el fichero?
     *
     * Se hace con un `GET` de UN solo byte (`Range: bytes=0-0`) en vez de un
     * `HEAD`, por dos razones:
     *  - Muchos servidores no devuelven `Content-Length` en un `HEAD` (o no
     *    lo soportan en absoluto), asi que el tamano se quedaria desconocido.
     *  - El `Content-Range` de la respuesta dice el tamano TOTAL del
     *    fichero, y el codigo 206 vs 200 dice si el servidor honra los
     *    rangos: un 206 significa "si", un 200 significa "te mando el
     *    fichero entero".
     */
    private fun probe(url: String): Probe {
        var conn: HttpURLConnection? = null
        return try {
            conn = openFollowingRedirects(url, "bytes=0-0")
            val code = conn.responseCode
            when {
                code == 206 -> {
                    // Content-Range: bytes 0-0/TOTAL -> total del fichero.
                    val served = RangePlan.parseContentRange(conn.getHeaderField("Content-Range"))
                    val total = parseTotalFromContentRange(conn.getHeaderField("Content-Range"))
                        ?: served?.let { seg ->
                            // Si no hay total, al menos hay un tramo: se pide
                            // el Content-Length de la respuesta.
                            conn.contentLengthLong.takeIf { it > 0 }
                        }
                        ?: 0L
                    Probe(
                        acceptsRanges = true,
                        totalBytes = total,
                    )
                }
                code == 200 -> {
                    // El servidor ignoro el Range: devolvio el fichero entero.
                    // No sirve para rangos, pero si da el tamano.
                    Probe(
                        acceptsRanges = false,
                        totalBytes = conn.contentLengthLong,
                    )
                }
                else -> Probe(false, 0L, "HTTP $code: ${conn.responseMessage}")
            }
        } catch (e: Exception) {
            Probe(false, 0L, e.message ?: "Error de red")
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    /**
     * Extrae el tamano total de `Content-Range: bytes 0-0/TOTAL`.
     */
    private fun parseTotalFromContentRange(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        val after = header.substringAfter('/', "").trim()
        return after.toLongOrNull()?.takeIf { it > 0L }
    }

    /**
     * Descarga de un solo flujo, para servidores sin soporte de rangos.
     */
    private fun downloadSingleStream(
        url: String,
        destFile: File,
        total: Long,
        onProgress: (Long, Long) -> Unit,
        isCancelled: () -> Boolean,
    ): Result {
        var conn: HttpURLConnection? = null
        return try {
            conn = openFollowingRedirects(url, null)
            if (conn.responseCode != 200) {
                return Result(false, "HTTP ${conn.responseCode}: ${conn.responseMessage}")
            }
            conn.getInputStream().use { input ->
                destFile.outputStream().use { output ->
                    val buffer = ByteArray(bufferSize)
                    var downloaded = 0L
                    while (!isCancelled()) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                    if (isCancelled()) return Result(false, "Cancelado")
                }
            }
            onProgress(total, total)
            Result(true)
        } catch (e: Exception) {
            Result(false, e.message ?: "Error de red")
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    /**
     * Descarga un tramo y lo escribe en su offset.
     *
     * @return bytes escritos en este tramo
     */
    private fun fetchSegment(
        url: String,
        seg: RangePlan.Segment,
        channel: java.nio.channels.FileChannel,
        downloaded: AtomicLong,
        total: Long,
        onProgress: (Long, Long) -> Unit,
        isCancelled: () -> Boolean,
    ): Long {
        var conn: HttpURLConnection? = null
        var written = 0L
        try {
            conn = openFollowingRedirects(url, seg.rangeHeader())
            val code = conn.responseCode
            // 206 = parcial (correcto). 200 = el servidor ignoro el Range y
            // va a mandar el fichero entero: no se escribe, o se corromperia.
            if (code != 206) return 0L

            // El servidor puede devolver un tramo distinto al pedido (por
            // ejemplo, si acota el rango). Se respeta el que dice la cabecera.
            val served = RangePlan.parseContentRange(conn.getHeaderField("Content-Range"))
            val start = served?.start ?: seg.start
            val end = served?.end ?: seg.end

            var remaining = end - start + 1
            var position = start
            conn.getInputStream().use { input ->
                val buffer = ByteArray(bufferSize)
                while (remaining > 0 && !isCancelled()) {
                    val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                    val read = input.read(buffer, 0, toRead)
                    if (read == -1) break
                    // Escritura POSICIONAL: cada hilo escribe en SU offset sin
                    // tocar el puntero del canal. Con un `seek()` + `write()`
                    // normal, el puntero es compartido y un hilo pisaba la
                    // posicion del otro: el fichero salia corrupto.
                    channel.write(java.nio.ByteBuffer.wrap(buffer, 0, read), position)
                    position += read
                    written += read
                    remaining -= read
                    downloaded.addAndGet(read.toLong())
                    onProgress(downloaded.get(), total)
                }
            }
            // Solo se da el tramo por bueno si llego entero.
            if (remaining > 0) return 0L
            return written
        } catch (_: Exception) {
            return 0L
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    /** Tramos ya completados, leidos del fichero `.parts`. */
    private fun readCompletedParts(partsFile: File): Set<Int> {
        if (!partsFile.exists()) return emptySet()
        return try {
            partsFile.readLines()
                .mapNotNull { it.trim().toIntOrNull() }
                .toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    /** Marca un tramo como completado (para poder reanudar). */
    private fun markPartDone(partsFile: File, index: Int) {
        try {
            synchronized(partsFile) {
                val current = readCompletedParts(partsFile)
                if (index in current) return
                partsFile.appendText("$index\n")
            }
        } catch (_: Exception) {
            // Si no se puede guardar, la descarga sigue: solo se pierde la
            // reanudacion.
        }
    }
}
