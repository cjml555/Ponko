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
 * Tests del catalogo del tagger y de la decision de recarga.
 *
 * La regla de recarga es la parte critica: si no se recarga cuando toca, el
 * usuario etiqueta con el modelo equivocado sin enterarse.
 */
class TaggerStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var prefs: FakePrefs
    private lateinit var store: TaggerStore

    @Before
    fun setUp() {
        filesDir = tmp.newFolder("files")
        prefs = FakePrefs()
        store = TaggerStore(filesDir, prefs)
    }

    private fun taggerFile(name: String): File {
        val d = File(filesDir, "tagger").apply { mkdirs() }
        return File(d, name).apply { writeText("x") }
    }

    // ---------- listado ----------

    @Test
    fun nothingIsListedWhenNothingIsImported() {
        assertTrue(store.listModels().isEmpty())
        assertTrue(store.listCsvs().isEmpty())
        assertNull(store.modelFile())
        assertNull(store.csvFile())
    }

    @Test
    fun onlyOnnxAndCsvAreListed() {
        taggerFile("modelo.onnx")
        taggerFile("tabla.csv")
        taggerFile("notas.txt")
        taggerFile("otro.pt")

        assertEquals(1, store.listModels().size)
        assertEquals("modelo.onnx", store.listModels()[0].name)
        assertEquals(1, store.listCsvs().size)
        assertEquals("tabla.csv", store.listCsvs()[0].name)
    }

    @Test
    fun listsAreSortedByName() {
        taggerFile("z.onnx")
        taggerFile("a.onnx")

        assertEquals(listOf("a.onnx", "z.onnx"), store.listModels().map { it.name })
    }

    // ---------- seleccion ----------

    @Test
    fun firstFileIsUsedWhenNothingIsSaved() {
        taggerFile("a.onnx")
        taggerFile("b.onnx")

        assertEquals("a.onnx", store.modelFile()?.name)
    }

    @Test
    fun savedModelWins() {
        taggerFile("a.onnx")
        taggerFile("b.onnx")
        store.selectModel(taggerFile("b.onnx"))

        assertEquals("b.onnx", store.currentModelName())
    }

    @Test
    fun savedModelThatNoLongerExistsFallsBackToTheFirstOne() {
        taggerFile("a.onnx")
        taggerFile("b.onnx")
        prefs.edit().putString(TaggerStore.KEY_SEL_MODEL, "borrado.onnx").apply()

        assertEquals("a.onnx", store.currentModelName())
    }

    @Test
    fun savedCsvWins() {
        taggerFile("a.csv")
        taggerFile("b.csv")
        store.selectCsv(taggerFile("b.csv"))

        assertEquals("b.csv", store.currentCsvName())
    }

    @Test
    fun selectionIsStoredByNameNotByPath() {
        store.selectModel(taggerFile("modelo.onnx"))

        assertEquals("modelo.onnx", prefs.getString(TaggerStore.KEY_SEL_MODEL, null))
    }
}

/** Tests de la decision de recarga del tagger. */
class TaggerSessionTest {

    private val selection = TaggerSelection("modelo.onnx", "tabla.csv", wantGpu = false)

    @Test
    fun emptySessionIsNotLoaded() {
        assertFalse(TaggerSession.EMPTY.engineLoaded)
    }

    @Test
    fun sessionWithEngineLoadedIsMarked() {
        val s = TaggerSession(true, "modelo.onnx", "tabla.csv", false)
        assertTrue(s.engineLoaded)
    }

    @Test
    fun missingFilesAreNotReady() {
        val s = TaggerSelection(null, "tabla.csv", false)
        assertTrue(s.missingFiles)
        assertFalse(s.isReadyWith(TaggerSession.EMPTY))
        assertFalse(s.isReadyWith(TaggerSession(true, "modelo.onnx", "tabla.csv", false)))
    }

    @Test
    fun nothingLoadedIsNotReady() {
        assertFalse(selection.isReadyWith(TaggerSession.EMPTY))
    }

    @Test
    fun sameModelCsvAndBackendAreReady() {
        val s = TaggerSession(true, "modelo.onnx", "tabla.csv", false)
        assertTrue(selection.isReadyWith(s))
    }

    @Test
    fun aDifferentModelIsNotReady() {
        val s = TaggerSession(true, "otro.onnx", "tabla.csv", false)
        assertFalse(selection.isReadyWith(s))
    }

    @Test
    fun aDifferentCsvIsNotReady() {
        val s = TaggerSession(true, "modelo.onnx", "otra.csv", false)
        assertFalse(selection.isReadyWith(s))
    }

    @Test
    fun switchingBackendIsNotReady() {
        val loadedOnGpu = TaggerSession(true, "modelo.onnx", "tabla.csv", true)
        assertFalse("De GPU a CPU hay que recargar", selection.isReadyWith(loadedOnGpu))

        val loadedOnCpu = TaggerSession(true, "modelo.onnx", "tabla.csv", false)
        val wantGpu = TaggerSelection("modelo.onnx", "tabla.csv", wantGpu = true)
        assertFalse("De CPU a GPU hay que recargar", wantGpu.isReadyWith(loadedOnCpu))
    }

    @Test
    fun sameBackendIsReady() {
        val loadedOnGpu = TaggerSession(true, "modelo.onnx", "tabla.csv", true)
        val wantGpu = TaggerSelection("modelo.onnx", "tabla.csv", wantGpu = true)
        assertTrue(wantGpu.isReadyWith(loadedOnGpu))
    }

    @Test
    fun engineNotLoadedIsNotReadyEvenWithMatchingNames() {
        // El engine dice "no hay sesion": aunque los nombres coincidan, no esta listo.
        val s = TaggerSession(false, "modelo.onnx", "tabla.csv", false)
        assertFalse(selection.isReadyWith(s))
    }
}
