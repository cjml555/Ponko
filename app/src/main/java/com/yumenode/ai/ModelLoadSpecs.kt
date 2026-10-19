// Yumenode AI · (c) 2026 visitor257 · MIT License
package com.yumenode.ai

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EngineConfig
import net.ladenthin.llama.parameters.ModelParameters
import java.io.File

/**
 * Construcción de los parámetros de carga de modelos, separada de la UI.
 *
 * Antes vivía dentro de `MainActivity.toggleLoad` mezclada con el estado de la
 * pantalla; aquí son funciones puras: mismas entradas → misma configuración.
 */
internal object ModelLoadSpecs {

    /**
     * Parámetros de llama.cpp para un `.gguf`.
     *
     * Un solo slot con `cache_prompt`: el prefijo KV de la conversación se
     * reutiliza y los turnos largos solo calculan los tokens nuevos.
     */
    fun llamaParams(
        path: String,
        ctxSize: Int,
        maxOut: Int,
        threads: Int,
        mmprojPath: String?,
    ): ModelParameters {
        val params = ModelParameters()
            .setModel(path)
            .setCtxSize(ctxSize)
            .setPredict(maxOut)
            .setThreads(threads)
            .setThreadsBatch(threads)
            .setBatchSize(512)
            .setParallel(1)
            .setKeep(64)
            .setGpuLayers(0)
        // Multimodal: sin el proyector de visión el gguf no acepta imágenes.
        mmprojPath?.let { if (File(it).isFile) params.setMmproj(it) }
        return params
    }

    /**
     * Configuración de LiteRT-LM.
     *
     * `visionBackend` debe pasarse explícitamente cuando el modelo es
     * multimodal: el default (null) deja el executor de visión sin crear y el
     * primer envío de imagen falla.
     */
    fun engineConfig(
        path: String,
        maxTokens: Int,
        gpuWanted: Boolean,
        visionOk: Boolean,
        maxImages: Int,
        cacheDir: String,
    ): EngineConfig = EngineConfig(
        modelPath = path,
        maxNumTokens = maxTokens,
        backend = backendFor(gpuWanted),
        visionBackend = if (visionOk) backendFor(gpuWanted) else null,
        maxNumImages = if (visionOk) maxImages else null,
        cacheDir = cacheDir,
    )

    /** Configuración equivalente forzada a CPU (fallback cuando la GPU falla). */
    fun cpuEngineConfig(
        path: String,
        maxTokens: Int,
        visionOk: Boolean,
        maxImages: Int,
        cacheDir: String,
    ): EngineConfig = engineConfig(
        path = path,
        maxTokens = maxTokens,
        gpuWanted = false,
        visionOk = visionOk,
        maxImages = maxImages,
        cacheDir = cacheDir,
    )

    private fun backendFor(gpu: Boolean) = if (gpu) Backend.GPU() else Backend.CPU()
}
