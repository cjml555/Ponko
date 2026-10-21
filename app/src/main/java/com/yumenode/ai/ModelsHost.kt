// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.app.Activity
import android.view.View
import kotlinx.coroutines.CoroutineScope
import java.io.File

/**
 * Contrato mínimo que [ModelsPage] necesita de su host (MainActivity).
 *
 * Solo lo imprescindible: contexto, paleta, scope y callbacks de acción.
 * ModelsPage nunca accede al motor, al estado de chat ni a la sesión.
 */
interface ModelsHost {
    /** Fábrica de widgets de UI. */
    val ui: UiWidgets

    /** Paleta de colores actual. */
    val palette: YumenodeTheme

    /** Activity para crear vistas y leer recursos. */
    val activity: Activity

    /** CoroutineScope para operations asíncronas (VisionManager, etc.). */
    val scope: CoroutineScope

    /** Directorio de archivos para logs y datos. */
    val filesDir: File

    /** Parámetros de chat (contexto, temperatura, topK, etc.). */
    val chatParams: ChatParams

    /** Estado: ¿hubo un crash de dibujo pendiente? */
    val pendingDrawCrash: Boolean

    /** Etapa del crash de dibujo pendiente (o null). */
    val pendingDrawCrashStage: String?

    /** Último log de crash (o null si no hay). */
    fun takeCrashLog(): String?

    /** Mostrar estado en la barra inferior. */
    fun setStatus(text: String, color: Int)

    /** Mostrar toast. */
    fun toast(msg: String)

    /** Callback: usuario pulsó "seleccionar archivo de modelo". */
    fun onPickModel()

    /** Callback: usuario pulsó "cargar / descargar modelo". */
    fun onToggleLoad()

    /** Callback: restablecer parámetros de chat a sus valores por defecto. */
    fun onResetChatParams()

    /** Callback: guardar parámetros de chat editados en UI. */
    fun onSaveChatParams()

    /** Callback: mostrar menú de tipo de dibujo (GGUF / LiteRT). */
    fun onShowDrawTypeMenu()

    /** Callback: alternar lista de slots de modelo multi-archivo. */
    fun onToggleDrawSlots()

    /** Callback: botón de toggle de carga de dibujo. */
    fun onDrawToggleClickRequested()

    /** Callback: seleccionar origen de LoRA. */
    fun onPickLoraSource()

    /** Descargar TAESD (mini autoencoder para decodificar mas rapido en CPU). */
    fun onPickTaesdSource()

    /** Callback: seleccionar archivo LoRA. */
    fun onPickLoraFile()

    /** Callback: confirmar borrado de LoRA. */
    fun onConfirmDeleteLora()

    /** Callback: seleccionar ONNX de tagger. */
    fun onPickTaggerOnnx()

    /** Callback: seleccionar CSV de tagger. */
    fun onPickTaggerCsv()

    /** Callback: botón de carga/descarga de tagger. */
    fun onTaggerToggleClickRequested()

    /** Callback: cambiar sub-página de chat (0=modelo, 1=API). */
    fun onSwitchChatSubPage(p: Int)

    /** Callback: cambiar sub-página de dibujo (0=modelo, 1=API). */
    fun onSwitchDrawSubPage(p: Int)

    /** Callback: cambiar pestaña principal (0=chat, 1=draw, 2=tagger). */
    fun onSwitchModelsTab(p: Int)

    /** Callback: refrescar UI de API de chat. */
    fun onRefreshApiUi()

    /** Callback: refrescar UI de API de dibujo. */
    fun onRefreshDrawApiUi()

    /** Callback: refrescar lista de LoRA. */
    fun onRefreshLoraUi()

    /** Callback: refrescar lista de modelos de dibujo. */
    fun onRefreshDrawModels()

    /** Callback: refrescar lista de tagger. */
    fun onRefreshTaggerUi()

    /** Callback: refrescar lista de modelos guardados. */
    fun onRefreshSavedModels()

    /** Callback: cargar parámetros de chat desde prefs a los campos. */
    fun onLoadChatParamsIntoUi()

    /** Callback: construir un bloque de configuración de API. */
    fun onBuildApiBlock(
        role: ApiServer.Role,
        extraAfterAddress: List<View> = emptyList(),
        extraNotes: List<View> = emptyList(),
    ): ApiBlockView

    /** Callback: actualizar uso de GPU en la página de dibujo. */
    fun onSetDrawUseGpu(value: Boolean)

    /** Callback: seleccionar archivo mmproj. */
    fun onPickMmprojFile()

    /** Callback: refrescar UI de mmproj. */
    fun onRefreshMmprojUi()

    /** Callback: progreso de carga de mmproj (0-100 o null para ocultar). */
    fun onProgress(pct: Int?)

    /** Callback: actualizar estado de VisionManager. */
    fun onVisionStatus(text: String, color: Int)

    /** Callback: error de VisionManager. */
    fun onVisionError(msg: String)

    /** Callback: el modelo cambiado afecta a la visión. */
    fun onModelChanged()
}
