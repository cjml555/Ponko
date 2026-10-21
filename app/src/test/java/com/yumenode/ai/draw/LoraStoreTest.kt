package com.yumenode.ai.draw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests del catalogo de LoRA.
 *
 * Fijan la regla de prioridad de la seleccion (sesion > guardada > primera)
 * y el borrado, que antes vivian dentro de `DrawPage` sin cobertura.
 */
class LoraStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var prefs: FakePrefs
    private lateinit var store: LoraStore

    @Before
    fun setUp() {
        filesDir = tmp.newFolder("files")
        prefs = FakePrefs()
        store = LoraStore(filesDir, prefs)
    }

    private fun lora(name: String): File {
        val d = File(filesDir, "draw/lora").apply { mkdirs() }
        return File(d, name).apply { writeText("pesos") }
    }

    // ---------- listado ----------

    @Test
    fun listIsEmptyWhenNothingIsImported() {
        assertTrue(store.list().isEmpty())
        assertFalse(store.hasAny())
    }

    @Test
    fun onlySafetensorsAreListed() {
        lora("buena.safetensors")
        lora("mala.pt")
        lora("notas.txt")

        val names = store.list().map { it.name }

        assertEquals(1, names.size)
        assertEquals("buena.safetensors", names[0])
    }

    @Test
    fun listIsSortedByName() {
        lora("zeta.safetensors")
        lora("alfa.safetensors")
        lora("media.safetensors")

        assertEquals(
            listOf("alfa.safetensors", "media.safetensors", "zeta.safetensors"),
            store.list().map { it.name }
        )
    }

    @Test
    fun extensionIsCaseInsensitive() {
        lora("MiLoRA.SAFETENSORS")

        assertEquals(1, store.list().size)
    }

    // ---------- prioridad de la seleccion ----------

    @Test
    fun withoutSelectionTheFirstOneIsUsed() {
        lora("alfa.safetensors")
        lora("zeta.safetensors")

        assertEquals("alfa.safetensors", store.active()?.name)
    }

    @Test
    fun savedSelectionWinsOverTheFirstOne() {
        lora("alfa.safetensors")
        lora("zeta.safetensors")
        store.select(lora("zeta.safetensors"))

        assertEquals("zeta.safetensors", store.active()?.name)
    }

    @Test
    fun sessionSelectionWinsOverTheSavedOne() {
        lora("alfa.safetensors")
        lora("zeta.safetensors")
        store.select(lora("zeta.safetensors"))

        val session = lora("media.safetensors")

        assertEquals("media.safetensors", store.active(session)?.name)
    }

    @Test
    fun sessionSelectionOfADeletedFileFallsBack() {
        lora("alfa.safetensors")
        lora("zeta.safetensors")
        store.select(lora("zeta.safetensors"))

        // La seleccion de la sesion apunta a un fichero que ya no esta.
        val ghost = File(store.loraDir(), "borrada.safetensors")

        assertEquals("zeta.safetensors", store.active(ghost)?.name)
    }

    @Test
    fun savedSelectionOfAMissingFileFallsBackToTheFirstOne() {
        lora("alfa.safetensors")
        lora("zeta.safetensors")
        // Se guardo una LoRA que ya no esta en disco.
        prefs.edit().putString(LoraStore.KEY_SEL_LORA, "desaparecida.safetensors").apply()

        assertEquals("alfa.safetensors", store.active()?.name)
    }

    @Test
    fun savedSelectionIsFoundByNameNotByPath() {
        val f = lora("alfa.safetensors")
        store.select(f)

        // La preferencia guarda el nombre, no la ruta.
        assertEquals("alfa.safetensors", store.selectedName())
        assertNull(prefs.getString(LoraStore.KEY_SEL_LORA, null)?.let { if (it.contains("/")) it else null })
    }

    @Test
    fun activeIsNullWhenThereAreNoLoras() {
        assertNull(store.active())
        assertNull(store.active(File(store.loraDir(), "fantasma.safetensors")))
    }

    // ---------- borrado ----------

    @Test
    fun deleteRemovesTheFile() {
        val f = lora("alfa.safetensors")

        assertTrue(store.delete(f))
        assertFalse(f.exists())
    }

    @Test
    fun deleteOfAMissingFileReturnsFalse() {
        assertFalse(store.delete(File(store.loraDir(), "nunca-existio.safetensors")))
    }
}
