package com.yumenode.ai.draw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Equivalencia entre la logica ORIGINAL del tagger (la que estaba dentro de
 * `DrawPage` antes del refactor) y la nueva (`TaggerSelection.isReadyWith`).
 *
 * La logica original se reimplementa aqui tal cual estaba, y se compara con
 * la nueva en una matriz de estados. Si alguien cambia cualquiera de las dos
 * sin la otra, este test falla.
 *
 * Es la unica forma de cubrir el bug que casi se cuela: al extraer la
 * comparacion, invertirla produce un fallo silencioso que compila, pasa los
 * tests unitarios de la funcion aislada y aun asi rompe la app.
 */
class TaggerReadyEquivalenceTest {

    /** Como estaba antes: ¿el tagger cargado sirve para la seleccion actual? */
    private fun originalIsReady(
        engineLoaded: Boolean,
        loadedModelName: String?,
        loadedCsvName: String?,
        loadedWithGpu: Boolean,
        wantModelName: String,
        wantCsvName: String,
        wantGpu: Boolean,
    ): Boolean {
        // ensureTaggerLoaded(): "si esta cargado y coincide -> return null (listo)"
        val readyForLoad = engineLoaded &&
            loadedModelName == wantModelName &&
            loadedCsvName == wantCsvName &&
            loadedWithGpu == wantGpu
        // taggerReadyError(): "si no esta cargado -> error; si no coincide -> error"
        val errorIfNotReady = !engineLoaded ||
            loadedModelName != wantModelName ||
            loadedCsvName != wantCsvName ||
            loadedWithGpu != wantGpu
        // Ambas funciones deben coincidir: listo == !error
        check(readyForLoad != errorIfNotReady) { "La logica original es contradictoria" }
        return readyForLoad
    }

    private fun newIsReady(
        engineLoaded: Boolean,
        loadedModelName: String?,
        loadedCsvName: String?,
        loadedWithGpu: Boolean,
        wantModelName: String,
        wantCsvName: String,
        wantGpu: Boolean,
    ): Boolean {
        val session = TaggerSession(engineLoaded, loadedModelName, loadedCsvName, loadedWithGpu)
        val selection = TaggerSelection(wantModelName, wantCsvName, wantGpu)
        return selection.isReadyWith(session)
    }

    @Test
    fun newLogicMatchesTheOriginalInEveryState() {
        val modelNames = listOf("modelo.onnx", "otro.onnx")
        val csvNames = listOf("tabla.csv", "otra.csv")

        var casos = 0
        for (loaded in booleanArrayOf(true, false)) {
            for (loadedModel in modelNames + listOf<String?>(null)) {
                for (loadedCsv in csvNames + listOf<String?>(null)) {
                    for (loadedGpu in booleanArrayOf(true, false)) {
                        for (wantModel in modelNames) {
                            for (wantCsv in csvNames) {
                                for (wantGpu in booleanArrayOf(true, false)) {
                                    val old = originalIsReady(
                                        loaded, loadedModel, loadedCsv, loadedGpu,
                                        wantModel, wantCsv, wantGpu,
                                    )
                                    val new = newIsReady(
                                        loaded, loadedModel, loadedCsv, loadedGpu,
                                        wantModel, wantCsv, wantGpu,
                                    )
                                    assertEquals(
                                        "Divergen con loaded=$loaded model=$loadedModel " +
                                            "csv=$loadedCsv gpu=$loadedGpu -> " +
                                            "want=$wantModel/$wantCsv gpu=$wantGpu",
                                        old, new,
                                    )
                                    casos++
                                }
                            }
                        }
                    }
                }
            }
        }
        assertTrue("Se esperaban muchos casos", casos > 100)
    }

    @Test
    fun theOldInvertedFormWouldHaveBrokenTheApp() {
        // Esta es la forma en la que primeramente se escribio la funcion.
        // Con la inversion, un tagger ya cargado y correcto se recargaba.
        fun invertedIsReady(
            engineLoaded: Boolean,
            loadedModelName: String?,
            loadedCsvName: String?,
            loadedWithGpu: Boolean,
            wantModelName: String,
            wantCsvName: String,
            wantGpu: Boolean,
        ): Boolean {
            val session = TaggerSession(engineLoaded, loadedModelName, loadedCsvName, loadedWithGpu)
            val selection = TaggerSelection(wantModelName, wantCsvName, wantGpu)
            return !selection.isReadyWith(session)
        }

        val yaListo = invertedIsReady(
            true, "modelo.onnx", "tabla.csv", false, "modelo.onnx", "tabla.csv", false,
        )
        assertFalse(
            "Con la forma invertida, un tagger ya listo pareceria que hay que recargarlo",
            yaListo,
        )
    }
}
