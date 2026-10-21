// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Importacion de ficheros de dibujo (LoRA, modelo ONNX del tagger, tabla CSV).
 *
 * Los tres casos eran **el mismo handler copiado tres veces** en
 * `MainActivity`: identical salvo por la funcion de importacion, el texto de
 * estado, el texto del toast y el refresco de la UI. Este handler lo
 * unifica; el `MainActivity` conserva la UI (barras de progreso, textos) y
 * solo aporta el trabajo pesado.
 *
 * @param scope  scope de corrutinas del host
 * @param host   contrato minimo: la UI que hay que actualizar
 */
class DrawFileImporter<T>(
    private val scope: CoroutineScope,
    private val host: DrawFileImportHost,
) {

    /**
     * Importa un fichero de dibujo y actualiza la UI.
     *
     * El flujo es siempre el mismo: marcar la app como ocupada, mostrar la
     * barra de progreso, importar, y despues marcar el exito o el error y
     * refrescar la seccion correspondiente.
     *
     * @param uri        fichero elegido por el usuario
     * @param importFile la operacion de importacion (devuelve null si fue bien)
     * @param onStage    actualiza el texto de estado de la seccion
     * @param onProgress actualiza la barra de progreso
     * @param onOk       texto a mostrar cuando la importacion fue bien
     * @param onToast    texto del toast de exito
     * @param refresh    refresca la seccion afectada
     */
    fun import(
        uri: T,
        importFile: suspend (T, (String) -> Unit, (Long, Long) -> Unit) -> String?,
        onStage: (String) -> Unit,
        onProgress: (Long, Long) -> Unit,
        onOk: String,
        onToast: String,
        refresh: () -> Unit,
    ) {
        host.setBusy(true)
        host.setDrawBusy(true)
        host.showDrawProgress(true)
        host.setStatus(host.busyMessage(), host.warnColor())

        scope.launch {
            val err = importFile(uri, onStage, onProgress)
            host.setBusy(false)
            host.setDrawBusy(false)
            host.showDrawProgress(false)
            if (err == null) {
                host.setStatus(onOk, host.okColor())
                host.toast(onToast)
            } else {
                host.setStatus(host.errorMessage(), host.errColor())
                host.toast(err)
            }
            refresh()
        }
    }
}

/**
 * Contrato minimo que necesita [DrawFileImporter] de su host.
 *
 * Deliberadamente **no** expone el motor, la sesion ni el estado de los
 * modelos: el handler solo puede poner textos y mover barras de progreso.
 */
interface DrawFileImportHost {
    /** Marcar la app como ocupada (deshabilita la entrada). */
    fun setBusy(busy: Boolean)

    /** Marcar specifically la seccion de dibujo como ocupada. */
    fun setDrawBusy(busy: Boolean)

    /** Mostrar u ocultar la barra de progreso de dibujo. */
    fun showDrawProgress(show: Boolean)

    /** Texto de la barra de estado inferior. */
    fun setStatus(text: String, color: Int)

    /** Mostrar un aviso. */
    fun toast(msg: String)

    /** Texto de "importando..." para la barra de estado. */
    fun busyMessage(): String

    /** Texto de error generico de importacion. */
    fun errorMessage(): String

    /** Color de exito. */
    fun okColor(): Int

    /** Color de error. */
    fun errColor(): Int

    /** Color de aviso. */
    fun warnColor(): Int
}
