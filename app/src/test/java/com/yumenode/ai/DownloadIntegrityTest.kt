package com.yumenode.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests de la verificacion de integridad de las descargas.
 *
 * El caso clave es el de los placeholders: el catalogo usa checksums de
 * ejemplo ("a1b2c3d4e5f6...") y hay que distinguir esos de un SHA-256 real
 * **sin** desactivarsela verificacion por accidente.
 */
class DownloadIntegrityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var file: File

    @Before
    fun setUp() {
        file = tmp.newFile("modelo.gguf")
        file.writeBytes("contenido del modelo".toByteArray())
    }

    // ---------- deteccion de placeholders ----------

    @Test
    fun theCatalogPlaceholdersAreDetected() {
        assertTrue(DownloadIntegrity.isPlaceholder("a1b2c3d4e5f6..."))
        assertTrue(DownloadIntegrity.isPlaceholder("b2c3d4e5f6a7..."))
        assertTrue(DownloadIntegrity.isPlaceholder("c3d4e5f6a7b8..."))
        assertTrue(DownloadIntegrity.isPlaceholder("d4e5f6a7b8c9..."))
        assertTrue(DownloadIntegrity.isPlaceholder("e5f6a7b8c9d0..."))
        assertTrue(DownloadIntegrity.isPlaceholder("f6a7b8c9d0e1..."))
        assertTrue(DownloadIntegrity.isPlaceholder("a7b8c9d0e1f2..."))
        assertTrue(DownloadIntegrity.isPlaceholder("b8c9d0e1f2a3..."))
        assertTrue(DownloadIntegrity.isPlaceholder("c9d0e1f2a3b4..."))
    }

    @Test
    fun aRealHashIsNotAPlaceholder() {
        val real = DownloadIntegrity.sha256(file)!!
        assertFalse(DownloadIntegrity.isPlaceholder(real))
    }

    @Test
    fun aRealHashStartingWithAPlaceholderPrefixIsStillVerified() {
        // Este es el bug que se corrigio: la deteccion por prefijo ("a1b2")
        // hacia que un SHA-256 real que empezara asi se aceptara sin
        // comprobar. Ahora la forma exacta del placeholder es la unica que
        // se salta la verificacion.
        val fake = "a1b2" + "0".repeat(60)
        assertFalse(
            "Un hash real no puede confundirse con un placeholder",
            DownloadIntegrity.isPlaceholder(fake),
        )
    }

    @Test
    fun anEmptyOrNullChecksumIsTreatedAsAbsent() {
        assertTrue(DownloadIntegrity.isPlaceholder(null))
        assertTrue(DownloadIntegrity.isPlaceholder(""))
        assertTrue(DownloadIntegrity.isPlaceholder("   "))
    }

    @Test
    fun aTruncatedPlaceholderIsNotAPlaceholder() {
        // "a1b2c3d4e5f6" sin los puntos finales NO es la forma del
        // placeholder: es un hash incompleto y debe intentar verificarse.
        assertFalse(DownloadIntegrity.isPlaceholder("a1b2c3d4e5f6"))
    }

    // ---------- sha256 ----------

    @Test
    fun sha256OfAKnownContentIsCorrect() {
        // "abc" -> ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        val f = tmp.newFile("abc.txt")
        f.writeText("abc")
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            DownloadIntegrity.sha256(f),
        )
    }

    @Test
    fun sha256OfAMissingFileIsNull() {
        assertNull(DownloadIntegrity.sha256(File(tmp.root, "no-existe.bin")))
    }

    @Test
    fun sha256IsLowercaseHexOf64Chars() {
        val h = DownloadIntegrity.sha256(file)!!
        assertEquals(64, h.length)
        assertEquals(h.lowercase(), h)
    }

    // ---------- verify ----------

    @Test
    fun aCorrectHashPasses() {
        val h = DownloadIntegrity.sha256(file)!!
        assertTrue(DownloadIntegrity.verify(file, h))
    }

    @Test
    fun aWrongHashFails() {
        assertFalse(DownloadIntegrity.verify(file, "0".repeat(64)))
    }

    @Test
    fun theComparisonIgnoresCase() {
        val h = DownloadIntegrity.sha256(file)!!
        assertTrue(DownloadIntegrity.verify(file, h.uppercase()))
    }

    @Test
    fun aPlaceholderAcceptsAnything() {
        // Sin checksum real no se puede comprobar nada: se acepta.
        assertTrue(DownloadIntegrity.verify(file, "a1b2c3d4e5f6..."))
        assertTrue(DownloadIntegrity.verify(file, null))
    }

    @Test
    fun anUnreadableFileDoesNotBlockTheDownload() {
        // Si el hash no se puede calcular (fichero corrupto o sin permisos),
        // se acepta para no dejar al usuario sin modelo: el error se
        // comprueba al cargar.
        assertTrue(DownloadIntegrity.verify(tmp.root, "a".repeat(64)))
    }

    // ---------- isIncomplete ----------

    @Test
    fun aFullSizedFileIsComplete() {
        // El fichero de test pesa ~20 bytes: con 20 esperados esta completo.
        assertFalse(DownloadIntegrity.isIncomplete(file, file.length(), 0.9))
    }

    @Test
    fun aTruncatedFileIsIncomplete() {
        assertTrue(DownloadIntegrity.isIncomplete(file, 10000L, 0.9))
    }

    @Test
    fun theRatioIsRespected() {
        // El fichero pesa "contenido del modelo".length = 19 bytes.
        val size = file.length()
        assertFalse(DownloadIntegrity.isIncomplete(file, size, 0.9))
        assertTrue(DownloadIntegrity.isIncomplete(file, (size * 2).toLong(), 0.9))
    }

    @Test
    fun anUncatalogedFileIsConsideredComplete() {
        assertFalse(DownloadIntegrity.isIncomplete(file, null, 0.9))
    }

    @Test
    fun aZeroExpectedSizeDoesNotFlagTheFile() {
        // Con tamano esperado 0 el umbral es 0 y nada es "incompleto": un
        // tamano esperado de 0 no significa nada, asi que no se marca el
        // fichero como descargado a medias.
        assertFalse(DownloadIntegrity.isIncomplete(file, 0L, 0.9))
    }

    @Test
    fun sha256OfAFileIsStable() {
        val a = DownloadIntegrity.sha256(file)
        val b = DownloadIntegrity.sha256(file)
        assertEquals(a, b)
        assertNotNull(a)
    }
}
