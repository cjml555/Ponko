package com.yumenode.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests del importador de ficheros de dibujo.
 *
 * Fijan el **orden** de los pasos (ocupar -> progreso -> importar -> liberar ->
 * resultado), que es lo que un refactor puede alterar en silencio: si el
 * `setBusy(false)` se ejecuta antes del final, la app se queda bloqueada; si
 * el toast de exito se dispara en el error, el usuario ve un mensaje falso.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DrawFileImporterTest {

    /** Host de mentira que registra el orden de las llamadas. */
    private class RecordingHost : DrawFileImportHost {
        val calls = mutableListOf<String>()
        var lastStatus = ""
        var lastToast = ""

        override fun setBusy(busy: Boolean) { calls += "setBusy($busy)" }
        override fun setDrawBusy(busy: Boolean) { calls += "setDrawBusy($busy)" }
        override fun showDrawProgress(show: Boolean) { calls += "showProgress($show)" }
        override fun setStatus(text: String, color: Int) {
            calls += "status"
            lastStatus = text
        }
        override fun toast(msg: String) { calls += "toast"; lastToast = msg }
        override fun busyMessage(): String = "importando"
        override fun errorMessage(): String = "error"
        override fun okColor(): Int = 1
        override fun errColor(): Int = 2
        override fun warnColor(): Int = 3
    }

    private fun importer(host: DrawFileImportHost, scope: CoroutineScope) =
        DrawFileImporter<String>(scope, host)

    /**
     * El importer es generico en el tipo del fichero, asi que los tests usan
     * un String: `android.net.Uri` es un stub en android.jar y no se puede
     * ni construir ni parsear en un test JVM.
     */
    private fun f(s: String): String = s

    @Test
    fun successfulImportFollowsTheExpectedOrder() = runTest {
        val host = RecordingHost()
        val imp = importer(host, this)

        imp.import(
            uri = "content://x/1",
            importFile = { _, _, _ -> null },
            onStage = {},
            onProgress = { _, _ -> },
            onOk = "OK",
            onToast = "hecho",
            refresh = { host.calls += "refresh" },
        )
        advanceUntilIdle()

        assertEquals(
            listOf(
                "setBusy(true)",
                "setDrawBusy(true)",
                "showProgress(true)",
                "status",
                "setBusy(false)",
                "setDrawBusy(false)",
                "showProgress(false)",
                "status",
                "toast",
                "refresh",
            ),
            host.calls,
        )
        assertEquals("OK", host.lastStatus)
        assertEquals("hecho", host.lastToast)
    }

    @Test
    fun failedImportShowsTheErrorAndSkipsTheSuccessToast() = runTest {
        val host = RecordingHost()
        val imp = importer(host, this)

        imp.import(
            uri = "content://x/1",
            importFile = { _, _, _ -> "no se pudo" },
            onStage = {},
            onProgress = { _, _ -> },
            onOk = "OK",
            onToast = "hecho",
            refresh = { host.calls += "refresh" },
        )
        advanceUntilIdle()

        // La UI se libera igual: si no, la app queda bloqueada.
        assertTrue(host.calls.contains("setBusy(false)"))
        assertTrue(host.calls.contains("showProgress(false)"))
        // El estado final es el de error, no el de exito.
        assertEquals("error", host.lastStatus)
        assertEquals("no se pudo", host.lastToast)
    }

    @Test
    fun theRefreshAlwaysRuns() = runTest {
        val host = RecordingHost()
        val imp = importer(host, this)

        imp.import(
            uri = "content://x/1",
            importFile = { _, _, _ -> "fallo" },
            onStage = {},
            onProgress = { _, _ -> },
            onOk = "OK",
            onToast = "hecho",
            refresh = { host.calls += "refresh" },
        )
        advanceUntilIdle()

        assertEquals("refresh", host.calls.last())
    }

    @Test
    fun theUriIsPassedToTheImportFunction() = runTest {
        val host = RecordingHost()
        val imp = importer(host, this)
        val uri = "content://modelo/42"
        var received: String? = null

        imp.import(
            uri = uri,
            importFile = { u, _, _ -> received = u; null },
            onStage = {},
            onProgress = { _, _ -> },
            onOk = "OK",
            onToast = "hecho",
            refresh = {},
        )
        advanceUntilIdle()

        assertEquals(uri, received)
    }

    @Test
    fun stageAndProgressReachTheCaller() = runTest {
        val host = RecordingHost()
        val imp = importer(host, this)
        var stageSeen = ""
        var progressSeen = Pair(0L, 0L)

        imp.import(
            uri = "content://x/1",
            importFile = { _, onStage, onProgress ->
                onStage("descargando")
                onProgress(10L, 100L)
                null
            },
            onStage = { stageSeen = it },
            onProgress = { done, total -> progressSeen = done to total },
            onOk = "OK",
            onToast = "hecho",
            refresh = {},
        )
        advanceUntilIdle()

        assertEquals("descargando", stageSeen)
        assertEquals(Pair(10L, 100L), progressSeen)
    }
}
