// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import net.ladenthin.llama.LlamaModel

/**
 * Detección del backend GPU disponible para el motor GGUF (llama.cpp).
 *
 * Hay dos preguntas distintas y conviene no mezclarlas:
 *
 *  1. ¿El binario trae el backend Vulkan? Eso lo dice la cadena de build
 *     que devuelve `LlamaModel.getLlamaCppBuildInfo()`: si el runtime se
 *     compiló con `GGML_VULKAN=ON`, aparece "VULKAN" en la lista.
 *  2. ¿El dispositivo tiene un driver Vulkan usable? Eso solo se sabe
 *     intentándolo: ggml lo registra en el log al cargar el modelo.
 *
 * Aqui se responde (1), que es lo que permite avisar al usuario de que
 * la opcion "GPU" no va a hacer nada, en vez de dejarle esperando una
 * aceleracion que no llega.
 */
internal object GpuSupport {

    /** llama.cpp usa -1 para "todas las capas a la GPU". */
    const val GPU_LAYERS_ALL = -1

    /**
     * ¿Se permite pedirle a llama.cpp que meta capas en la GPU?
     *
     * **De momento NO.** El backend Vulkan sobre Android todavia no es
     * fiable en este dispositivo: al cargar un modelo con capas en la GPU
     * el proceso muere con SIGSEGV (el driver de Adreno no resuelve bien
     * las funciones de dispositivo de Vulkan y el dispatcher acaba
     * llamando a un puntero nulo).
     *
     * Un SIGSEGV no se puede catchar: si el usuario elige "GPU" y el
     * motor peta, la app se cierra. Eso es peor que ir en CPU. Por eso la
     * opcion se pide pero no se activa hasta verificarlo en el dispositivo.
     *
     * El camino esta todomontado (AAR con Vulkan en local-maven/, script
     * reproducible en tools/build-llama-vulkan.sh, y los parches del
     * dispatcher y del buffer device address). Cuando se verifique en un
     * movil, se cambia este flag a true y ya funciona.
     */
    const val GGUF_GPU_EXPERIMENTAL = false

    /** Cacheado: la cadena de build no cambia durante la vida del proceso. */
    @Volatile
    private var vulkanBuiltIn: Boolean? = null

    /**
     * ¿El runtime de llama.cpp se compiló con el backend Vulkan?
     *
     * `getLlamaCppBuildInfo()` devuelve algo como
     * `AVX=0 AVX2=0 ... VULKAN=1 OPENMP=1` cuando el backend está dentro.
     * Si la llamada falla (binario raro, error de carga), se asume que no
     * hay GPU: es el comportamiento seguro.
     */
    fun isVulkanBuiltIn(): Boolean {
        vulkanBuiltIn?.let { return it }
        val info = try {
            LlamaModel.getLlamaCppBuildInfo()
        } catch (_: Throwable) {
            null
        }
        val has = info != null && info.contains("VULKAN", ignoreCase = true)
        vulkanBuiltIn = has
        return has
    }

    /**
     * La cadena de build completa (para diagnostics).
     *
     * @return la informacion de compilacion, o null si no se pudo leer
     */
    fun buildInfo(): String? = try {
        LlamaModel.getLlamaCppBuildInfo()
    } catch (_: Throwable) {
        null
    }
}
