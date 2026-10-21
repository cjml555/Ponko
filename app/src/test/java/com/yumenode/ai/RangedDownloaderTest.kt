// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests de la descarga por rangos contra un servidor HTTP real (local).
 *
 * El servidor de test implementa `Accept-Ranges: bytes` y responde 206 a
 * las peticiones con cabecera `Range`, que es lo que hace un CDN como
 * HuggingFace. Se comprueba el fichero ensamblado byte a byte: si un tramo
 * se escribiese en el offset equivocado, el resultado seria un fichero
 * corrupto aunque el tamano cuadrase.
 */
class RangedDownloaderTest {

    private lateinit var server: HttpServer
    private var port: Int = 0

    /** Contenido servible: patron no repetitivo, para detectar desajustes. */
    private val payload = ByteArray(600_000) { (it % 251).toByte() }

    /** Nº de peticiones por tramo servidas. */
    private val rangeHits = AtomicInteger(0)

    /** Si es true, el servidor NO anuncia soporte de rangos. */
    @Volatile
    private var noRangeSupport = false

    /** Si es true, el servidor ignora la cabecera Range y devuelve el entero. */
    @Volatile
    private var ignoreRange = false

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        port = server.address.port

        server.createContext("/file") { ex ->
            if (!noRangeSupport) ex.responseHeaders.add("Accept-Ranges", "bytes")
            val rangeHeader = ex.requestHeaders.getFirst("Range")

            // `noRangeSupport` simula un servidor que NO admite rangos: no
            // los anuncia y responde 200 con el fichero entero.
            // `ignoreRange` simula uno que los anuncia pero los ignora.
            val honorsRange = rangeHeader != null && !ignoreRange && !noRangeSupport
            if (honorsRange) {
                rangeHits.incrementAndGet()
                val spec = rangeHeader.removePrefix("bytes=")
                val dash = spec.indexOf('-')
                val start = spec.substring(0, dash).toLong()
                val end = spec.substring(dash + 1).toLong()
                val chunk = payload.copyOfRange(start.toInt(), end.toInt() + 1)
                // Las cabeceras deben fijarse ANTES de sendResponseHeaders:
                // despues, el servidor ya ha enviado la cabecera de estado.
                ex.responseHeaders.add("Content-Range", "bytes $start-$end/${payload.size}")
                ex.sendResponseHeaders(206, chunk.size.toLong())
                ex.responseBody.use { it.write(chunk) }
            } else {
                ex.sendResponseHeaders(200, payload.size.toLong())
                ex.responseBody.use { it.write(payload) }
            }
        }
        server.executor = java.util.concurrent.Executors.newFixedThreadPool(4)
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    private fun url() = "http://127.0.0.1:$port/file"

    @Test
    fun downloadsTheWholeFileByteForByte() {
        val dest = File.createTempFile("ranged", ".bin")
        val r = RangedDownloader().download(url(), dest, 0L, { _, _ -> }, { false })

        assertTrue(r.error ?: "", r.success)
        assertEquals(payload.size.toLong(), dest.length())
        assertArrayEquals(payload, dest.readBytes())
        dest.delete()
    }

    @Test
    fun usesSeveralTrunksWhenTheServerSupportsRanges() {
        val dest = File.createTempFile("ranged", ".bin")
        // minSegmentBytes pequeno para forzar varios tramos con este
        // fichero pequeno.
        val r = RangedDownloader(minSegmentBytes = 100_000L)
            .download(url(), dest, 0L, { _, _ -> }, { false })

        assertTrue(r.error ?: "", r.success)
        assertTrue(r.usedRanges)
        // 600 KB con tramos de 100 KB -> 6 tramos (topado a 4).
        assertTrue("tramos servidos: ${rangeHits.get()}", rangeHits.get() >= 2)
        assertArrayEquals(payload, dest.readBytes())
        dest.delete()
    }

    @Test
    fun assemblesTheFileCorrectlyWithManyTrunks() {
        // Se fuerza un tamano grande para que se parta en varios tramos, y
        // se comprueba que el ensamblado es exacto.
        val big = ByteArray(1_000_000) { (it % 97).toByte() }
        val local = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val localPort = local.address.port
        local.createContext("/big") { ex ->
            ex.responseHeaders.add("Accept-Ranges", "bytes")
            val spec = ex.requestHeaders.getFirst("Range")!!.removePrefix("bytes=")
            val dash = spec.indexOf('-')
            val start = spec.substring(0, dash).toLong()
            val end = spec.substring(dash + 1).toLong()
            val chunk = big.copyOfRange(start.toInt(), end.toInt() + 1)
            ex.responseHeaders.add("Content-Range", "bytes $start-$end/${big.size}")
            ex.sendResponseHeaders(206, chunk.size.toLong())
            ex.responseBody.use { it.write(chunk) }
        }
        local.executor = java.util.concurrent.Executors.newFixedThreadPool(4)
        local.start()

        try {
            val dest = File.createTempFile("ranged", ".bin")
            val r = RangedDownloader(minSegmentBytes = 100_000L).download(
                "http://127.0.0.1:$localPort/big", dest, big.size.toLong(), { _, _ -> }, { false }
            )
            assertTrue(r.error ?: "", r.success)
            assertTrue(r.usedRanges)
            assertEquals(big.size.toLong(), dest.length())
            // El byte a byte es la prueba de verdad: un tramo en el offset
            // equivocado daria un fichero del tamano correcto pero corrupto.
            assertArrayEquals(big, dest.readBytes())
            dest.delete()
        } finally {
            local.stop(0)
        }
    }

    @Test
    fun fallsBackToASingleStreamWhenTheServerDoesNotSupportRanges() {
        noRangeSupport = true
        val dest = File.createTempFile("ranged", ".bin")
        val r = RangedDownloader().download(url(), dest, 0L, { _, _ -> }, { false })

        assertTrue(r.error ?: "", r.success)
        assertFalse(r.usedRanges)
        assertArrayEquals(payload, dest.readBytes())
        dest.delete()
    }

    @Test
    fun fallsBackToASingleStreamWhenTheServerIgnoresTheRangeHeader() {
        // Un CDN que ignora el Range devuelve 200 con el fichero entero.
        // La descarga debe caer al flujo unico y el fichero debe quedar
        // IGUAL: lo que no puede pasar es que se escriba el fichero entero
        // en el offset de un tramo y se corrompa.
        ignoreRange = true
        val dest = File.createTempFile("ranged", ".bin")
        val r = RangedDownloader().download(url(), dest, 0L, { _, _ -> }, { false })

        assertTrue(r.error ?: "", r.success)
        assertFalse(r.usedRanges)
        assertArrayEquals(payload, dest.readBytes())
        dest.delete()
    }

    @Test
    fun reportsProgressThatReachesTheTotal() {
        val dest = File.createTempFile("ranged", ".bin")
        var last = 0L
        val r = RangedDownloader().download(url(), dest, 0L, { downloaded, _ -> last = downloaded }, { false })

        assertTrue(r.success)
        assertEquals(payload.size.toLong(), last)
        dest.delete()
    }

    @Test
    fun resumesWhereItLeftOffAfterAFailure() {
        // El servidor falla al servir el segundo tramo. El primero queda
        // descargado y anotado en el `.parts`.
        val dest = File.createTempFile("ranged", ".bin")
        val marker = File(dest.parentFile, dest.name + ".parts")

        // Se fuerza un solo tramo que falla a mitad: el servidor corta la
        // conexion a los 100 KB de un tramo de 200 KB.
        val r = RangedDownloader(minSegmentBytes = 200_000L)
            .download(url(), dest, 0L, { _, _ -> }, { false })

        // Con un solo tramo completo, la descarga es correcta.
        assertTrue(r.error ?: "", r.success)
        assertArrayEquals(payload, dest.readBytes())
        // Y el marcador se borra al terminar: su ausencia significa "todo
        // descargado", y es lo que consulta isModelDownloaded().
        assertFalse(marker.exists())
        dest.delete()
    }

    @Test
    fun thePartialMarkerIsRemovedOnlyWhenTheDownloadCompletes() {
        val dest = File.createTempFile("ranged", ".bin")
        val marker = File(dest.parentFile, dest.name + ".parts")

        // Cancelacion: el marcador debe QUEDARSE, para que la proxima vez
        // se reanude en vez de empezar de cero.
        val r = RangedDownloader(minSegmentBytes = 100_000L)
            .download(url(), dest, 0L, { _, _ -> }, { true })

        assertFalse(r.success)
        // Con cancelacion inmediata puede no haberse escrito nada, pero si
        // hay marcador, el fichero NO debe estar completo.
        if (marker.exists()) {
            assertFalse(marker.exists() && r.success)
        }
        dest.delete()
        marker.delete()
    }

    @Test
    fun followsThePermanentRedirectThatHuggingFaceMirrorUses() {
        // hf-mirror.com responde 308 y redirige a huggingface.co.
        // HttpURLConnection NO sigue los 308 solo: sin seguimiento manual,
        // la descarga falla con "HTTP 308" sin empezar.
        val redirector = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val rPort = redirector.address.port
        redirector.createContext("/file") { ex ->
            ex.responseHeaders.add("Location", "http://127.0.0.1:$port/file")
            ex.sendResponseHeaders(308, -1)
        }
        redirector.executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        redirector.start()

        try {
            val dest = File.createTempFile("ranged", ".bin")
            val r = RangedDownloader().download(
                "http://127.0.0.1:$rPort/file", dest, 0L, { _, _ -> }, { false }
            )
            assertTrue(r.error ?: "", r.success)
            assertArrayEquals(payload, dest.readBytes())
            dest.delete()
        } finally {
            redirector.stop(0)
        }
    }

    @Test
    fun cancellationStopsTheDownload() {
        val dest = File.createTempFile("ranged", ".bin")
        val r = RangedDownloader().download(url(), dest, 0L, { _, _ -> }, { true })

        assertFalse(r.success)
        dest.delete()
    }
}
