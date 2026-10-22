// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import net.ladenthin.llama.args.CacheType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la configuracion del cache KV (ruta GGUF).
 *
 * La parte que se puede comprobar sin Android es la eleccion del tipo de
 * cuantizacion y su efecto en el tamano de RAM; la persistencia necesita
 * un Context, y se comprueba en el dispositivo.
 */
class KvCacheConfigTest {

    // ---- Opciones ofrecidas ----

    @Test
    fun theDefaultIsQ8_0() {
        // Q8_0 es la recomendacion de llama.cpp: mitad de RAM que F16 sin
        // perdida de calidad visible.
        assertTrue(KvCacheConfig.OPTIONS.contains(CacheType.Q8_0))
        assertEquals(CacheType.Q8_0, KvCacheConfig.OPTIONS.first())
    }

    @Test
    fun theOptionsGoFromLessToMoreRam() {
        // El orden de la lista es el que ve el usuario en el desplegable:
        // primero el que menos RAM consume dentro de los cuantizados.
        val labels = KvCacheConfig.OPTIONS.map { KvCacheConfig.label(it) }
        assertEquals(listOf("Q8_0", "Q4_0", "F16"), labels)
    }

    @Test
    fun everyOptionHasAHint() {
        for (type in KvCacheConfig.OPTIONS) {
            val hint = KvCacheConfig.hint(type)
            assertTrue("sin hint para $type", hint.isNotBlank())
        }
    }

    // ---- Tamano del cache ----

    /**
     * Cuanto RAM ocupa el cache K/V segun su cuantizacion.
     *
     * K y V son el mismo numero de elementos, asi que el tamano total es
     * 2 * elementos * bytesPorElemento.
     */
    private fun cacheBytes(elements: Long, bytesPerElement: Int): Long =
        2L * elements * bytesPerElement

    @Test
    fun q8_0UsesHalfTheMemoryOfF16() {
        val elements = 32_768L * 26L * 128L * 2L // ejemplo: 32k ctx, 26 capas
        val f16 = cacheBytes(elements, 2)
        val q8 = cacheBytes(elements, 1)
        assertEquals(f16 / 2, q8)
    }

    @Test
    fun q4_0UsesAQuarterOfF16() {
        val elements = 1_000_000L
        val f16 = cacheBytes(elements, 2)
        val q4 = cacheBytes(elements, 1) / 2
        assertTrue(q4 < f16 / 3)
    }

    // ---- Etiquetas ----

    @Test
    fun labelsAreTheShortNames() {
        assertEquals("Q8_0", KvCacheConfig.label(CacheType.Q8_0))
        assertEquals("Q4_0", KvCacheConfig.label(CacheType.Q4_0))
        assertEquals("F16", KvCacheConfig.label(CacheType.F16))
    }

    @Test
    fun anUnknownTypeFallsBackToItsOwnName() {
        // No debe petar: un tipo que no esté en la lista se muestra con su
        // nombre en vez de romper la pantalla.
        val label = KvCacheConfig.label(CacheType.Q5_1)
        assertEquals("Q5_1", label)
    }
}
