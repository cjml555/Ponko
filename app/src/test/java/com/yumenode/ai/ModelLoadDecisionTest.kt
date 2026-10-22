package com.yumenode.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterization tests for ModelLoadDecision — pin the decision layer of
 * toggleLoad() exactly as it behaved before the extraction, so a refactor that
 * changes which backend wins, how many layers llama.cpp gets, or when the
 * "GPU unavailable" toast fires fails here.
 */
class ModelLoadDecisionTest {

    // ── isGguf: file extension decides the backend path ─────────────────────
    @Test fun gguf_lowercase_is_gguf() =
        assertTrue(ModelLoadDecision.isGguf("/models/llama-3.2-3b.gguf"))

    @Test fun gguf_uppercase_is_gguf() =
        assertTrue(ModelLoadDecision.isGguf("/models/MODEL.GGUF"))

    @Test fun litertlm_is_not_gguf() =
        assertFalse(ModelLoadDecision.isGguf("/models/gemma-3n.litertlm"))

    @Test fun extension_must_be_at_the_end() =
        assertFalse(ModelLoadDecision.isGguf("/models/model.gguf.tmp"))

    // ── wantsGpu / gpuLayers ────────────────────────────────────────────────
    @Test fun gpu_backend_is_wanted() =
        assertTrue(ModelLoadDecision.wantsGpu("GPU"))

    @Test fun cpu_backend_is_not_wanted() =
        assertFalse(ModelLoadDecision.wantsGpu("CPU"))

    @Test fun unknown_backend_is_not_gpu() =
        assertFalse(ModelLoadDecision.wantsGpu("NNAPI"))

    @Test fun gpu_backend_means_all_layers() =
        assertEquals(GpuSupport.GPU_LAYERS_ALL, ModelLoadDecision.gpuLayers("GPU", gpuAllowed = true))

    @Test fun cpu_backend_means_zero_layers() =
        assertEquals(0, ModelLoadDecision.gpuLayers("CPU"))

    // ── La via experimental: sin ella, pedir GPU no mete capas en la GPU ───
    // Un SIGSEGV del driver no se puede catchar: pedir capas en la GPU sin
    // estar verificado mataria la app. Por eso el flag va implicito.
    @Test fun without_the_experimental_flag_gpu_means_zero_layers() =
        assertEquals(0, ModelLoadDecision.gpuLayers("GPU", gpuAllowed = false))

    @Test fun the_experimental_flag_is_off_by_default() =
        assertFalse(GpuSupport.GGUF_GPU_EXPERIMENTAL)

    @Test fun by_default_gpu_layers_is_zero() =
        assertEquals(0, ModelLoadDecision.gpuLayers("GPU"))

    // ── actuallyOnGpu: pedir GPU no basta, el build debe traer Vulkan ──────
    @Test fun on_gpu_only_when_requested_and_vulkan_present() =
        assertTrue(ModelLoadDecision.actuallyOnGpu("GPU", vulkanBuiltIn = true, gpuAllowed = true))

    @Test fun requested_gpu_but_no_vulkan_is_cpu_at_runtime() =
        assertFalse(ModelLoadDecision.actuallyOnGpu("GPU", vulkanBuiltIn = false, gpuAllowed = true))

    @Test fun vulkan_present_but_cpu_requested_is_not_on_gpu() =
        assertFalse(ModelLoadDecision.actuallyOnGpu("CPU", vulkanBuiltIn = true, gpuAllowed = true))

    @Test fun vulkan_present_but_experimental_off_is_not_on_gpu() =
        assertFalse(ModelLoadDecision.actuallyOnGpu("GPU", vulkanBuiltIn = true, gpuAllowed = false))

    // ── shouldWarnGpuFallback: solo avisa si pidió GPU y no lo consiguió ────
    @Test fun warns_when_gpu_requested_but_vulkan_missing() =
        assertTrue(ModelLoadDecision.shouldWarnGpuFallback("GPU", vulkanBuiltIn = false, gpuAllowed = true))

    @Test fun does_not_warn_when_gpu_worked() =
        assertFalse(ModelLoadDecision.shouldWarnGpuFallback("GPU", vulkanBuiltIn = true, gpuAllowed = true))

    @Test fun does_not_warn_on_cpu_backend() =
        assertFalse(ModelLoadDecision.shouldWarnGpuFallback("CPU", vulkanBuiltIn = false, gpuAllowed = true))

    @Test fun warns_when_gpu_requested_but_experimental_is_off() =
        assertTrue(ModelLoadDecision.shouldWarnGpuFallback("GPU", vulkanBuiltIn = true, gpuAllowed = false))

    // ── modelName: etiqueta sin ruta ───────────────────────────────────────
    @Test fun model_name_drops_directory() =
        assertEquals("model.gguf", ModelLoadDecision.modelName("/home/user/models/model.gguf"))

    @Test fun model_name_of_bare_file() =
        assertEquals("model.gguf", ModelLoadDecision.modelName("model.gguf"))
}
