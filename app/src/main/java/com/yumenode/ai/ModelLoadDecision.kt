// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

/**
 * Decisiones puras de la carga de un modelo de lenguaje.
 *
 * `toggleLoad()` en MainActivity mezcla tres cosas: decidir (qué backend, qué
 * archivo, cuántas capas), construir los specs (ya en [ModelLoadSpecs]) y
 * orquestar el engine/native en una corrutina. Este objeto extrae solo la
 * capa de *decisión*, que no toca estado y por lo tanto se puede cubrir con
 * tests JVM sin device ni engine.
 */
internal object ModelLoadDecision {

    /** Nombres de backend que el spinner de la página Modelos ofrece. */
    const val BACKEND_GPU = "GPU"
    const val BACKEND_CPU = "CPU"

    /** ¿El archivo es un GGUF (llama.cpp) o un LiteRT-LM (.litertlm)? */
    fun isGguf(path: String): Boolean = path.endsWith(".gguf", ignoreCase = true)

    /** ¿El usuario pidió el backend GPU? */
    fun wantsGpu(backendName: String): Boolean = backendName == BACKEND_GPU

    /**
     * Capas a enviarle a llama.cpp.
     * -1 (GpuSupport.GPU_LAYERS_ALL) = todas las capas; 0 = solo CPU.
     *
     * Pedir GPU solo cuenta si la via experimental esta habilitada: un
     * SIGSEGV del driver no se puede catchar, asi que pedir capas en la GPU
     * sin estar verificado mataria la app.
     */
    fun gpuLayers(backendName: String, gpuAllowed: Boolean = GpuSupport.GGUF_GPU_EXPERIMENTAL): Int =
        if (wantsGpu(backendName) && gpuAllowed) GpuSupport.GPU_LAYERS_ALL else 0

    /**
     * ¿El modelo realmente quedó corriendo en GPU?
     * Pedir GPU no basta: si el binario no trae el backend Vulkan, ggml se
     * queda en CPU en silencio. Solo se afirma GPU si se pidió, el build
     * incluye Vulkan Y la via experimental esta habilitada.
     */
    fun actuallyOnGpu(
        backendName: String,
        vulkanBuiltIn: Boolean,
        gpuAllowed: Boolean = GpuSupport.GGUF_GPU_EXPERIMENTAL,
    ): Boolean = wantsGpu(backendName) && vulkanBuiltIn && gpuAllowed

    /**
     * ¿Hay que avisar al usuario de que pidió GPU pero obtuvo CPU?
     *
     * Tres casos, todos con aviso:
     *  - el build no trae Vulkan;
     *  - la via experimental esta deshabilitada (el driver aun no es fiable);
     *  - no se pidio GPU (entonces no hay nada que avisar).
     */
    fun shouldWarnGpuFallback(
        backendName: String,
        vulkanBuiltIn: Boolean,
        gpuAllowed: Boolean = GpuSupport.GGUF_GPU_EXPERIMENTAL,
    ): Boolean = wantsGpu(backendName) && (!vulkanBuiltIn || !gpuAllowed)

    /**
     * Nombre del modelo para registros/logs: el archivo sin la ruta.
     * (ChatCore.attachGguf / attachLiteRt lo usan como etiqueta.)
     */
    fun modelName(path: String): String = path.substringAfterLast('/')
}
