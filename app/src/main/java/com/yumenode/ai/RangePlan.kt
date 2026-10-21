// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

/**
 * Logica pura de la descarga por rangos (HTTP Range).
 *
 * La idea: en vez de un unico flujo de bytes de principio a fin, el
 * fichero se parte en N tramos y cada uno se pide por separado con
 * `Range: bytes=inicio-fin`. El servidor entrega los bytes en paralelo y
 * el resultado se ensambla en un unico fichero.
 *
 * Ventajas en un movil:
 *  - Varios tramos en paralelo saturan mejor la conexion que uno solo
 *    (que suele quedarse por debajo del ancho de banda disponible).
 *  - Si la descarga se corta, solo se repiten los tramos que faltaban, no
 *    el fichero entero.
 *
 * Este objeto NO hace red ni disco: solo decide como partir el fichero y
 * como strenuous. Es la parte que se puede comprobar en un test de JVM.
 */
object RangePlan {

    /** Tamano minimo de un tramo. Por debajo, el overhead HTTP se come el Beneficio. */
    const val MIN_SEGMENT_BYTES = 8L * 1024 * 1024

    /** Numero maximo de tramos simultaneos. */
    const val MAX_SEGMENTS = 4

    /**
     * Cuantos tramos conviene usar para un fichero de `totalBytes`.
     *
     * Un tramo por cada [minSegmentBytes], con topes en [MAX_SEGMENTS] y
     * un minimo de 1 (un fichero pequeño se descarga entero de una vez: los
     * tramos solo tienen sentido cuando hay volumen).
     *
     * @param totalBytes      tamano total del fichero
     * @param parallel        cuantos tramos se quieren en paralelo (topado)
     * @param minSegmentBytes tamano minimo de un tramo
     */
    fun segmentCount(
        totalBytes: Long,
        parallel: Int = MAX_SEGMENTS,
        minSegmentBytes: Long = MIN_SEGMENT_BYTES,
    ): Int {
        if (totalBytes <= 0L) return 1
        val cap = parallel.coerceIn(1, MAX_SEGMENTS)
        val floor = minSegmentBytes.coerceAtLeast(1L)
        val bySize = (totalBytes / floor).toInt().coerceAtLeast(1)
        return bySize.coerceAtMost(cap)
    }

    /**
     * Parte `[0, totalBytes)` en `count` tramos contiguos.
     *
     * Los tramos se reparten de forma uniforme: el primero se queda con el
     * sobrante de la division, para que la diferencia entre el tramo mayor
     * y el menor sea de 1 byte.
     *
     * @param totalBytes tamano total del fichero
     * @param count      numero de tramos
     * @return lista de tramos, en orden, que cubren todo el fichero
     */
    fun segments(totalBytes: Long, count: Int): List<Segment> {
        if (totalBytes <= 0L) return emptyList()
        val n = count.coerceAtLeast(1)
        val base = totalBytes / n
        val extra = totalBytes % n
        val out = ArrayList<Segment>(n)
        var start = 0L
        for (i in 0 until n) {
            // El primer tramo absorbe el resto de la division.
            val size = base + if (i == 0) extra else 0L
            val end = start + size - 1
            out.add(Segment(index = i, start = start, end = end))
            start = end + 1
        }
        return out
    }

    /**
     * Los tramos que faltan por descargar, dado lo ya descargado.
     *
     * @param segments  todos los tramos del fichero
     * @param done      tramos ya completos
     * @return solo los que aun faltan
     */
    fun pending(segments: List<Segment>, done: Set<Int>): List<Segment> =
        segments.filter { it.index !in done }

    /**
     * Parsea una cabecera `Content-Range: bytes 100-199/1000`.
     *
     * @return el tramo, o null si la cabecera no tiene ese formato
     */
    fun parseContentRange(header: String?): Segment? {
        if (header.isNullOrBlank()) return null
        // bytes 100-199/1000
        // La cabecera puede venir en cualquier caja ("bytes", "Bytes"...),
        // asi que se normaliza antes de quitar el prefijo.
        val body = header.trim()
            .replaceFirst(Regex("(?i)^bytes"), "")
            .trim()
            .substringBefore('/')
            .trim()
        val dash = body.indexOf('-')
        if (dash <= 0) return null
        val start = body.substring(0, dash).trim().toLongOrNull() ?: return null
        val end = body.substring(dash + 1).trim().toLongOrNull() ?: return null
        if (end < start) return null
        return Segment(index = 0, start = start, end = end)
    }

    /**
     * ¿La respuesta del servidor indica que acepta rangos?
     *
     * Con `Accept-Ranges: bytes` el servidor puede servir tramos sueltos.
     * Sin ese dato, no se puede fiar uno: algunos CDNs lo ignoran y
     * devuelven el fichero entero con un 200, lo que corromperia el
     * ensamblado si se escribiese en su sitio.
     */
    fun acceptsRanges(acceptRangesHeader: String?): Boolean =
        acceptRangesHeader?.trim()?.equals("bytes", ignoreCase = true) == true

    /**
     * Un tramo de bytes del fichero.
     *
     * @param index indice del tramo (0..n-1)
     * @param start byte inicial (inclusivo)
     * @param end   byte final (inclusivo)
     */
    data class Segment(
        val index: Int,
        val start: Long,
        val end: Long,
    ) {
        /** Numero de bytes del tramo. */
        val size: Long get() = end - start + 1

        /** Cabecera `Range` para pedir este tramo. */
        fun rangeHeader(): String = "bytes=$start-$end"

        /** ¿Este tramo es el que devuelve el servidor? */
        fun matches(start: Long, end: Long): Boolean = this.start == start && this.end == end
    }
}
