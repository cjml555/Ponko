// Yumenode AI · (c) 2026 visitor257 · MIT License
package com.yumenode.ai

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EngineConfig
import net.ladenthin.llama.args.CacheType
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
     *
     * @param gpuLayers cuántas capas se descargan a la GPU. 0 = solo CPU;
     *   -1 = todas (lo que usa llama.cpp para "gpu layers = all"). Si el
     *   binario no trae backend Vulkan, ggml lo ignora y sigue en CPU.
     * @param cacheTypeK cuantización del cache K (F16 = sin cuantizar).
     * @param cacheTypeV cuantización del cache V.
     */
    fun llamaParams(
        path: String,
        ctxSize: Int,
        maxOut: Int,
        threads: Int,
        mmprojPath: String?,
        gpuLayers: Int = 0,
        cacheTypeK: CacheType = CacheType.Q8_0,
        cacheTypeV: CacheType = CacheType.Q8_0,
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
            .setGpuLayers(gpuLayers)
            // Cuantizar el cache K/V: en F16 ocupa el doble de RAM que en
            // Q8_0 y no se nota en la calidad. Con contexto largo (32k) la
            // diferencia es de gigas: es lo que permite que un modelo grande
            // quepa en un movil.
            .setCacheTypeK(cacheTypeK)
            .setCacheTypeV(cacheTypeV)
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
