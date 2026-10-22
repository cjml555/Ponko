package com.yumenode.ai.draw

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
 * Tests del store de model sets.
 *
 * Cubre las ramas que antes vivian dentro de `DrawPage` sin ninguna cobertura:
 * ids seguros, colision de nombres, round-trip del JSON (relativo vs absoluto),
 * slots que desaparecen del disco, borrado y TAESD.
 */
class ModelSetStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var prefs: FakePrefs
    private lateinit var store: ModelSetStore

    @Before
    fun setUp() {
        filesDir = tmp.newFolder("files")
        prefs = FakePrefs()
        store = ModelSetStore(filesDir, prefs)
    }

    // ---------- safeId ----------

    @Test
    fun safeIdKeepsSafeCharacters() {
        assertEquals("sd15_model-v2.0", ModelSetStore.safeId("sd15_model-v2.0"))
    }

    @Test
    fun safeIdReplacesUnsafeCharacters() {
        assertEquals("my_model_2024", ModelSetStore.safeId("my model/2024"))
    }

    @Test
    fun safeIdTrimsUnderscoresAndFallsBackToSet() {
        assertEquals("abc", ModelSetStore.safeId("__abc__"))
        assertEquals("set", ModelSetStore.safeId("///"))
    }

    @Test
    fun safeIdCapsLengthAt48() {
        val long = "a".repeat(120)
        assertEquals(48, ModelSetStore.safeId(long).length)
    }

    // ---------- listSets: sueltos legacy ----------

    @Test
    fun looseGgufBecomesLegacySingleSet() {
        val f = File(filesDir, "draw").apply { mkdirs() }
        File(f, "pony_v3.gguf").writeText("x")

        val sets = store.listSets()

        assertEquals(1, sets.size)
        val s = sets[0]
        assertEquals("legacy:pony_v3.gguf", s.id)
        assertEquals("pony_v3", s.name)
        assertEquals(ModelSetStore.KIND_SINGLE, s.kind)
        assertNull(s.dir)
        assertNotNull(s.files["model"])
    }

    @Test
    fun looseVaeIsAttachedNotListedSeparately() {
        val d = File(filesDir, "draw").apply { mkdirs() }
        File(d, "pony_v3.gguf").writeText("x")
        File(d, "pony_vae.gguf").writeText("x")

        val sets = store.listSets()

        // El VAE no es un modelo: se cuelga del set, no se lista aparte.
        assertEquals(1, sets.size)
        assertEquals(2, sets[0].slotCount())
        assertNotNull(sets[0].files["vae"])
    }

    @Test
    fun hiddenFilesAreIgnored() {
        val d = File(filesDir, "draw").apply { mkdirs() }
        File(d, ".partial.gguf").writeText("x")

        assertTrue(store.listSets().isEmpty())
    }

    // ---------- round-trip JSON ----------

    @Test
    fun setSurvivesJsonRoundTrip() {
        val dir = store.createSetDir("flux")
        File(dir, "flux.gguf").writeText("model")
        File(dir, "ae.safetensors").writeText("vae")
        val set = ModelSet(
            "flux", "Mi Flux", ModelSetStore.KIND_MULTI, "flux", dir,
            linkedMapOf("model" to File(dir, "flux.gguf"), "vae" to File(dir, "ae.safetensors"))
        )
        store.writeSetJson(set)

        val reloaded = store.listSets().first { it.id == "flux" }

        assertEquals("Mi Flux", reloaded.name)
        assertEquals(ModelSetStore.KIND_MULTI, reloaded.kind)
        assertEquals("flux", reloaded.family)
        assertEquals(2, reloaded.slotCount())
        assertNotNull(reloaded.files["model"])
        assertNotNull(reloaded.files["vae"])
    }

    @Test
    fun filesOutsideTheSetKeepTheirAbsolutePath() {
        val outside = File(filesDir, "loose_model.gguf").apply { writeText("x") }
        val dir = store.createSetDir("promoted")
        val set = ModelSet(
            "promoted", "Promovido", ModelSetStore.KIND_MULTI, "sd15", dir,
            linkedMapOf("model" to outside)
        )
        store.writeSetJson(set)

        val reloaded = store.listSets().first { it.id == "promoted" }

        assertEquals(outside.absolutePath, reloaded.files["model"]?.absolutePath)
    }

    @Test
    fun missingSlotFilesAreDroppedOnRead() {
        val dir = store.createSetDir("half")
        File(dir, "present.gguf").writeText("x")
        val set = ModelSet(
            "half", "A medias", ModelSetStore.KIND_MULTI, "sd15", dir,
            linkedMapOf(
                "model" to File(dir, "present.gguf"),
                "vae" to File(dir, "gone.safetensors")
            )
        )
        store.writeSetJson(set)
        File(dir, "gone.safetensors").delete()

        val reloaded = store.listSets().first { it.id == "half" }

        assertEquals(1, reloaded.slotCount())
        assertNotNull(reloaded.files["model"])
        assertNull(reloaded.files["vae"])
    }

    @Test
    fun corruptJsonIsIgnored() {
        val dir = store.createSetDir("roto")
        File(dir, ModelSetStore.SET_JSON_NAME).writeText("{esto no es json")

        assertTrue(store.listSets().isEmpty())
    }

    // ---------- seleccion ----------

    @Test
    fun selectedSetIdIsRemembered() {
        val set = ModelSet("x", "X", ModelSetStore.KIND_SINGLE, "sd15", null)
        store.selectSet(set)

        assertEquals("x", store.selectedSetId())
    }

    // ---------- borrado ----------

    @Test
    fun deletingSetRemovesItsDirectory() {
        val dir = store.createSetDir("borrable")
        File(dir, "m.gguf").writeText("x")
        val set = ModelSet("borrable", "Borrable", ModelSetStore.KIND_MULTI, "sd15", dir)

        assertTrue(store.deleteSet(set))
        assertFalse(dir.exists())
    }

    @Test
    fun deletingSelectedSetClearsTheSelection() {
        val dir = store.createSetDir("sel")
        val set = ModelSet("sel", "Sel", ModelSetStore.KIND_MULTI, "sd15", dir)
        store.selectSet(set)

        store.deleteSet(set)

        assertNull(store.selectedSetId())
    }

    @Test
    fun deletingLegacySetRemovesItsFiles() {
        val d = File(filesDir, "draw").apply { mkdirs() }
        val f = File(d, "old.gguf").apply { writeText("x") }
        val set = ModelSet("legacy:old.gguf", "old", ModelSetStore.KIND_SINGLE, "other", null, linkedMapOf("model" to f))

        assertTrue(store.deleteSet(set))
        assertFalse(f.exists())
    }

    // ---------- clearSlot ----------

    @Test
    fun clearSlotRemovesSlotButKeepsFile() {
        val dir = store.createSetDir("convae")
        val vae = File(dir, "ae.safetensors").apply { writeText("x") }
        val set = ModelSet(
            "convae", "Con VAE", ModelSetStore.KIND_MULTI, "sd15", dir,
            linkedMapOf("model" to File(dir, "m.gguf").apply { writeText("x") }, "vae" to vae)
        )
        store.writeSetJson(set)

        store.clearSlot(set, "vae")

        assertNull(set.files["vae"])
        assertTrue("El fichero no debe borrarse al quitar el slot", vae.exists())
    }

    // ---------- TAESD ----------

    @Test
    fun taesdPathRoundTrips() {
        store.setTaesdPath("/data/draw/taesd/taesd.safetensors")

        assertEquals("/data/draw/taesd/taesd.safetensors", store.taesdPath())
    }

    @Test
    fun taesdFileIsNullWhenMissing() {
        assertNull(store.taesdFile())
    }

    @Test
    fun taesdFileIsNullWhenTooSmall() {
        val d = File(filesDir, "draw/taesd").apply { mkdirs() }
        File(d, "taesd.safetensors").writeText("x")

        assertNull("Un fichero truncado no vale como TAESD", store.taesdFile())
    }

    @Test
    fun taesdFileIsFoundWhenBigEnough() {
        val d = File(filesDir, "draw/taesd").apply { mkdirs() }
        val f = File(d, "taesd.safetensors")
        f.writeBytes(ByteArray((ModelSetStore.TAESD_MIN_BYTES + 1).toInt()))

        assertNotNull(store.taesdFile())
        assertEquals(f.absolutePath, store.taesdFile()?.absolutePath)
    }

    // ---------- ids de set ----------

    @Test
    fun createSetDirAvoidsCollisions() {
        val a = store.createSetDir("mismo")
        val b = store.createSetDir("mismo")

        assertEquals("mismo", a.name)
        assertEquals("mismo-2", b.name)
        assertTrue(a.exists() && b.exists())
    }
}
