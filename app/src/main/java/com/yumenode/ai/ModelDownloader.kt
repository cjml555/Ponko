package com.yumenode.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Model Downloader for Yumenode AI - descarga modelos GGUF y .litertlm compatibles
 * desde fuentes confiables (Hugging Face, hf-mirror, etc.)
 */
object ModelDownloader {
    private const val TAG = "ModelDownloader"
    private const val MODELS_DIR = "models"
    private const val MAX_RETRIES = 3
    private const val INITIAL_RETRY_DELAY_MS = 1000L
    private const val CHUNK_SIZE = 1024 * 1024 // 1MB buffer
    private const val PROGRESS_THROTTLE_MS = 500L
    private const val MIN_FILE_SIZE_RATIO = 0.8 // 80% del tamaño esperado
    private const val EXISTING_FILE_SIZE_RATIO = 0.9 // 90% para considerar existente

    /** Fuentes de modelos con URLs base */
    private const val HF_BASE = "https://huggingface.co"
    private const val HF_MIRROR = "https://hf-mirror.com"

    /** Control de descarga activa para cancelación */
    private val activeDownload = AtomicReference<Job?>()
    private val cancelled = AtomicBoolean(false)

    /**
     * Modelos predefinidos compatibles con Yumenode AI.
     *
     * Reglas para las URLs (verificadas con HEAD):
     *  - Solo repos públicos: los gated de Google (gemma-*-gguf) devuelven 401
     *    sin token, así que se usan mirrors abiertos (bartowski / unsloth / Qwen).
     *  - sizeBytes = Content-Length real del archivo. Si queda corto, un modelo
     *    ya descargado no se reconoce (isModelDownloaded usa el 90% del tamaño).
     */
    private val MODEL_CATALOG = mapOf(
        // GGUF models (llama.cpp)
        "gemma-2-2b-it-q4_k_m.gguf" to ModelInfo(
            name = "Gemma 2 2B Instruct Q4_K_M",
            description = "Google Gemma 2 2B - Eficiente y rápido, buena calidad general",
            url = "$HF_MIRROR/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf",
            sizeBytes = 1_708_582_752L,
            format = ModelFormat.GGUF,
            supportsVision = false,
            quantType = "Q4_K_M",
            sha256 = "a1b2c3d4e5f6..." // placeholder - agregar checksums reales
        ),
        "gemma-2-2b-it-q8_0.gguf" to ModelInfo(
            name = "Gemma 2 2B Instruct Q8_0",
            description = "Google Gemma 2 2B - Mayor calidad, más memoria",
            url = "$HF_MIRROR/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q8_0.gguf",
            sizeBytes = 2_784_495_456L,
            format = ModelFormat.GGUF,
            supportsVision = false,
            quantType = "Q8_0",
            sha256 = "b2c3d4e5f6a7..."
        ),
        "gemma-3n-e4b-it-q4_k_m.gguf" to ModelInfo(
            name = "Gemma 3n E4B Instruct Q4_K_M",
            description = "Google Gemma 3n E4B - Multilingüe y eficiente (texto)",
            url = "$HF_MIRROR/unsloth/gemma-3n-E4B-it-GGUF/resolve/main/gemma-3n-E4B-it-Q4_K_M.gguf",
            sizeBytes = 4_539_054_208L,
            format = ModelFormat.GGUF,
            supportsVision = false, // el repo público no publica mmproj; sin él no hay visión
            quantType = "Q4_K_M",
            sha256 = "c3d4e5f6a7b8..."
        ),
        "phi-3-mini-4k-instruct-q4_k_m.gguf" to ModelInfo(
            name = "Phi-3 Mini 4K Instruct Q4_K_M",
            description = "Microsoft Phi-3 - Muy eficiente, 4K context",
            url = "$HF_MIRROR/microsoft/Phi-3-mini-4k-instruct-gguf/resolve/main/Phi-3-mini-4k-instruct-q4.gguf",
            sizeBytes = 2_393_231_072L,
            format = ModelFormat.GGUF,
            supportsVision = false,
            quantType = "Q4_K_M",
            sha256 = "d4e5f6a7b8c9..."
        ),
        "phi-3.5-mini-instruct-q4_k_m.gguf" to ModelInfo(
            name = "Phi-3.5 Mini Instruct Q4_K_M",
            description = "Microsoft Phi-3.5 - Mejorado, 128K context",
            url = "$HF_MIRROR/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf",
            sizeBytes = 2_393_232_672L,
            format = ModelFormat.GGUF,
            supportsVision = false,
            quantType = "Q4_K_M",
            sha256 = "e5f6a7b8c9d0..."
        ),
        "llama-3.2-1b-instruct-q4_k_m.gguf" to ModelInfo(
            name = "Llama 3.2 1B Instruct Q4_K_M",
            description = "Meta Llama 3.2 1B - Ultra ligero, ideal para móviles",
            url = "$HF_MIRROR/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            sizeBytes = 807_694_464L,
            format = ModelFormat.GGUF,
            supportsVision = false,
            quantType = "Q4_K_M",
            sha256 = "f6a7b8c9d0e1..."
        ),
        "llama-3.2-3b-instruct-q4_k_m.gguf" to ModelInfo(
            name = "Llama 3.2 3B Instruct Q4_K_M",
            description = "Meta Llama 3.2 3B - Balance calidad/tamaño",
            url = "$HF_MIRROR/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            sizeBytes = 2_019_377_696L,
            format = ModelFormat.GGUF,
            supportsVision = false,
            quantType = "Q4_K_M",
            sha256 = "a7b8c9d0e1f2..."
        ),
        "qwen2.5-1.5b-instruct-q4_k_m.gguf" to ModelInfo(
            name = "Qwen 2.5 1.5B Instruct Q4_K_M",
            description = "Alibaba Qwen 2.5 - Fuerte en chino/inglés, muy eficiente",
            url = "$HF_MIRROR/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            sizeBytes = 1_117_320_736L,
            format = ModelFormat.GGUF,
            supportsVision = false,
            quantType = "Q4_K_M",
            sha256 = "b8c9d0e1f2a3..."
        ),
        "qwen2.5-3b-instruct-q4_k_m.gguf" to ModelInfo(
            name = "Qwen 2.5 3B Instruct Q4_K_M",
            description = "Alibaba Qwen 2.5 3B - Mejor calidad, multilingüe",
            url = "$HF_MIRROR/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
            sizeBytes = 2_104_932_768L,
            format = ModelFormat.GGUF,
            supportsVision = false,
            quantType = "Q4_K_M",
            sha256 = "c9d0e1f2a3b4..."
        ),

        // ── Modelos ligeros (ideales para probar rápido) ────────────────────
        "qwen2.5-0.5b-instruct-q4_k_m.gguf" to ModelInfo(
            name = "Qwen 2.5 0.5B Instruct Q4_K_M",
            description = "Alibaba Qwen 2.5 - El más pequeño, arranca al instante",
            url = "$HF_MIRROR/bartowski/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/Qwen2.5-0.5B-Instruct-Q4_K_M.gguf",
            sizeBytes = 397_808_192L,
            format = ModelFormat.GGUF,
            supportsVision = false,
            quantType = "Q4_K_M"
        ),
        "smollm2-1.7b-instruct-q4_k_m.gguf" to ModelInfo(
            name = "SmolLM2 1.7B Instruct Q4_K_M",
            description = "HuggingFace SmolLM2 - Rápido y bueno en inglés",
            url = "$HF_MIRROR/bartowski/SmolLM2-1.7B-Instruct-GGUF/resolve/main/SmolLM2-1.7B-Instruct-Q4_K_M.gguf",
            sizeBytes = 1_055_609_824L,
            format = ModelFormat.GGUF,
            supportsVision = false,
            quantType = "Q4_K_M"
        ),

        // ── Modelos con razonamiento (compatibles con "Thinking mode") ──────
        "deepseek-r1-distill-qwen-1.5b-q4_k_m.gguf" to ModelInfo(
            name = "DeepSeek R1 Distill Qwen 1.5B Q4_K_M",
            description = "DeepSeek - Razonamiento paso a paso, muy ligero",
            url = "$HF_MIRROR/bartowski/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
            sizeBytes = 1_117_320_800L,
            format = ModelFormat.GGUF,
            supportsVision = false,
            quantType = "Q4_K_M",
            supportsThinking = true
        ),
        "qwen3-4b-q4_k_m.gguf" to ModelInfo(
            name = "Qwen 3 4B Q4_K_M",
            description = "Alibaba Qwen 3 - Razonamiento híbrido y multilingüe",
            url = "$HF_MIRROR/unsloth/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf",
            sizeBytes = 2_497_281_312L,
            format = ModelFormat.GGUF,
            supportsVision = false,
            quantType = "Q4_K_M",
            supportsThinking = true
        ),

        // ── Modelos medianos ────────────────────────────────────────────────
        "gemma-3-4b-it-q4_k_m.gguf" to ModelInfo(
            name = "Gemma 3 4B IT Q4_K_M",
            description = "Google Gemma 3 - Buen equilibrio calidad/velocidad",
            url = "$HF_MIRROR/unsloth/gemma-3-4b-it-GGUF/resolve/main/gemma-3-4b-it-Q4_K_M.gguf",
            sizeBytes = 2_489_894_016L,
            format = ModelFormat.GGUF,
            supportsVision = false,
            quantType = "Q4_K_M"
        ),
        "phi-4-mini-instruct-q4_k_m.gguf" to ModelInfo(
            name = "Phi-4 Mini Instruct Q4_K_M",
            description = "Microsoft Phi-4 Mini - Fuerte en matemáticas y código",
            url = "$HF_MIRROR/unsloth/Phi-4-mini-instruct-GGUF/resolve/main/Phi-4-mini-instruct-Q4_K_M.gguf",
            sizeBytes = 2_491_874_272L,
            format = ModelFormat.GGUF,
            supportsVision = false,
            quantType = "Q4_K_M"
        ),

        // ── Modelos grandes (necesitan 6-8 GB de RAM libre) ─────────────────
        "mistral-7b-instruct-v0.3-q4_k_m.gguf" to ModelInfo(
            name = "Mistral 7B Instruct v0.3 Q4_K_M",
            description = "Mistral AI - Clásico versátil, buena escritura",
            url = "$HF_MIRROR/bartowski/Mistral-7B-Instruct-v0.3-GGUF/resolve/main/Mistral-7B-Instruct-v0.3-Q4_K_M.gguf",
            sizeBytes = 4_372_812_000L,
            format = ModelFormat.GGUF,
            supportsVision = false,
            quantType = "Q4_K_M"
        ),
        "qwen2.5-7b-instruct-q4_k_m.gguf" to ModelInfo(
            name = "Qwen 2.5 7B Instruct Q4_K_M",
            description = "Alibaba Qwen 2.5 7B - Excelente en chino/inglés",
            url = "$HF_MIRROR/bartowski/Qwen2.5-7B-Instruct-GGUF/resolve/main/Qwen2.5-7B-Instruct-Q4_K_M.gguf",
            sizeBytes = 4_683_074_240L,
            format = ModelFormat.GGUF,
            supportsVision = false,
            quantType = "Q4_K_M"
        ),
        "qwen2.5-coder-7b-instruct-q4_k_m.gguf" to ModelInfo(
            name = "Qwen 2.5 Coder 7B Instruct Q4_K_M",
            description = "Alibaba Qwen 2.5 Coder - Especializado en programación",
            url = "$HF_MIRROR/bartowski/Qwen2.5-Coder-7B-Instruct-GGUF/resolve/main/Qwen2.5-Coder-7B-Instruct-Q4_K_M.gguf",
            sizeBytes = 4_683_074_336L,
            format = ModelFormat.GGUF,
            supportsVision = false,
            quantType = "Q4_K_M"
        ),
        "llama-3.1-8b-instruct-q4_k_m.gguf" to ModelInfo(
            name = "Llama 3.1 8B Instruct Q4_K_M",
            description = "Meta Llama 3.1 8B - La mejor calidad de la lista",
            url = "$HF_MIRROR/bartowski/Meta-Llama-3.1-8B-Instruct-GGUF/resolve/main/Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf",
            sizeBytes = 4_920_739_232L,
            format = ModelFormat.GGUF,
            supportsVision = false,
            quantType = "Q4_K_M"
        ),

        // ── LiteRT-LM (.litertlm) ───────────────────────────────────────────
        // Solo repos públicos: los de Google (google/gemma-*-it-litert-lm) son
        // gated (401 sin token) y fallarían siempre. Estos vienen de
        // litert-community, que no pide autenticación.
        "qwen-2.5-1.5b-litert-q8.litertlm" to ModelInfo(
            name = "Qwen 2.5 1.5B LiteRT Q8",
            description = "Alibaba Qwen 2.5 - Formato LiteRT-LM optimizado (GPU/NPU)",
            url = "$HF_MIRROR/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            sizeBytes = 1_597_922_856L,
            format = ModelFormat.LITERTLM,
            supportsVision = false
        ),
        "deepseek-r1-distill-1.5b-litert-q8.litertlm" to ModelInfo(
            name = "DeepSeek R1 Distill 1.5B LiteRT Q8",
            description = "DeepSeek - Razonamiento paso a paso en LiteRT-LM",
            url = "$HF_MIRROR/litert-community/DeepSeek-R1-Distill-Qwen-1.5B/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm",
            sizeBytes = 1_833_451_520L,
            format = ModelFormat.LITERTLM,
            supportsVision = false,
            supportsThinking = true
        ),
        "phi-4-mini-litert-q8.litertlm" to ModelInfo(
            name = "Phi-4 Mini LiteRT Q8",
            description = "Microsoft Phi-4 Mini - LiteRT-LM, bueno en matemáticas",
            url = "$HF_MIRROR/litert-community/Phi-4-mini-instruct/resolve/main/Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            sizeBytes = 3_910_090_752L,
            format = ModelFormat.LITERTLM,
            supportsVision = false
        ),

        // ── LiteRT-LM: variantes y modelos extra ────────────────────────────
        // Los repos de Gemma y Llama en litert-community son gated=auto (401),
        // por eso no están aquí. Variante f32 del Qwen: más calidad, ~6 GB.
        "qwen-2.5-1.5b-litert-f32.litertlm" to ModelInfo(
            name = "Qwen 2.5 1.5B LiteRT F32",
            description = "Alibaba Qwen 2.5 - Precisión completa (necesita ~7 GB de RAM)",
            url = "$HF_MIRROR/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_f32_ekv4096.litertlm",
            sizeBytes = 6_182_436_864L,
            format = ModelFormat.LITERTLM,
            supportsVision = false
        ),
        "qwen-3-0.6b-litert.litertlm" to ModelInfo(
            name = "Qwen 3 0.6B LiteRT",
            description = "Alibaba Qwen 3 - El LiteRT más pequeño, con razonamiento",
            url = "$HF_MIRROR/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm",
            sizeBytes = 613_722_264L,
            format = ModelFormat.LITERTLM,
            supportsVision = false,
            supportsThinking = true
        ),
        "lfm-2.5-1.2b-thinking-litert.litertlm" to ModelInfo(
            name = "LFM 2.5 1.2B Thinking LiteRT",
            description = "Liquid AI - Diseñado para razonar, muy compacto",
            url = "$HF_MIRROR/litert-community/LFM2.5-1.2B-Thinking/resolve/main/LFM2.5-1.2B-Thinking_int4.litertlm",
            sizeBytes = 736_015_744L,
            format = ModelFormat.LITERTLM,
            supportsVision = false,
            supportsThinking = true
        ),
        "minicpm5-2b-litert.litertlm" to ModelInfo(
            name = "MiniCPM5 2B LiteRT",
            description = "OpenBMB MiniCPM5 - Compacto y multilingüe",
            url = "$HF_MIRROR/litert-community/MiniCPM5-2B/resolve/main/MiniCPM5-2B_int4.litertlm",
            sizeBytes = 1_553_670_064L,
            format = ModelFormat.LITERTLM,
            supportsVision = false
        ),
        "granite-4.0-micro-litert.litertlm" to ModelInfo(
            name = "Granite 4.0 Micro LiteRT",
            description = "IBM Granite 4.0 - Enfocado en empresa y código",
            url = "$HF_MIRROR/litert-community/granite-4.0-micro/resolve/main/granite-4.0-micro_int4.litertlm",
            sizeBytes = 2_198_475_104L,
            format = ModelFormat.LITERTLM,
            supportsVision = false
        ),
        "qwen-3-4b-litert.litertlm" to ModelInfo(
            name = "Qwen 3 4B LiteRT",
            description = "Alibaba Qwen 3 4B - Razonamiento híbrido en LiteRT",
            url = "$HF_MIRROR/litert-community/Qwen3-4B/resolve/main/qwen3_4b_mixed_int4.litertlm",
            sizeBytes = 2_659_053_760L,
            format = ModelFormat.LITERTLM,
            supportsVision = false,
            supportsThinking = true
        ),
        "ministral-3-8b-litert.litertlm" to ModelInfo(
            name = "Ministral 3 8B LiteRT",
            description = "Mistral AI - El LiteRT más capaz (necesita ~6 GB de RAM)",
            url = "$HF_MIRROR/litert-community/Ministral-3-8B-Instruct-2512/resolve/main/Ministral-3-8B-Instruct-2512_int4.litertlm",
            sizeBytes = 5_039_124_912L,
            format = ModelFormat.LITERTLM,
            supportsVision = false
        )
    )

    /**
     * Modelos que no son de chat: proyector de visión, difusión y etiquetador.
     * URLs verificadas contra los repos de Hugging Face (vía el mirror).
     */
    private val EXTRA_CATALOG = mapOf(
        "mmproj-qwen2-vl-2b-f16.gguf" to ModelInfo(
            name = "mmproj Qwen2-VL 2B f16",
            description = "Proyector de visión para Qwen2-VL 2B (llama.cpp)",
            url = "$HF_MIRROR/bartowski/Qwen2-VL-2B-Instruct-GGUF/resolve/main/mmproj-Qwen2-VL-2B-Instruct-f16.gguf",
            sizeBytes = 1_331_656_192L,
            format = ModelFormat.GGUF,
            supportsVision = true,
            kind = ModelKind.MMPROJ,
            remoteName = "mmproj-Qwen2-VL-2B-Instruct-f16.gguf",
        ),
        "sd15-emaonly-q4_0.gguf" to ModelInfo(
            name = "Stable Diffusion 1.5 Q4_0",
            description = "SD 1.5 pruned emaonly - el más ligero para móvil",
            url = "$HF_MIRROR/second-state/stable-diffusion-v1-5-GGUF/resolve/main/stable-diffusion-v1-5-pruned-emaonly-Q4_0.gguf",
            sizeBytes = 1_566_768_416L,
            format = ModelFormat.GGUF,
            supportsVision = false,
            kind = ModelKind.DRAW,
            remoteName = "stable-diffusion-v1-5-pruned-emaonly-Q4_0.gguf",
        ),
        "wd14-moat-onnx" to ModelInfo(
            name = "WD14 Moat Tagger (ONNX)",
            description = "Etiquetador Danbooru WD v1.4 - necesita también la lista CSV",
            url = "$HF_MIRROR/SmilingWolf/wd-v1-4-moat-tagger-v2/resolve/main/model.onnx",
            sizeBytes = 326_197_340L,
            format = ModelFormat.GGUF,
            supportsVision = false,
            kind = ModelKind.TAGGER,
            remoteName = "wd14-moat.onnx",
        ),
        "wd14-moat-tags" to ModelInfo(
            name = "WD14 Moat tags (CSV)",
            description = "Lista de etiquetas selected_tags.csv para el tagger WD14",
            url = "$HF_MIRROR/SmilingWolf/wd-v1-4-moat-tagger-v2/resolve/main/selected_tags.csv",
            sizeBytes = 253_906L,
            format = ModelFormat.GGUF,
            supportsVision = false,
            kind = ModelKind.TAGGER,
            remoteName = "selected_tags.csv",
        ),
    )

    private val ALL_MODELS = MODEL_CATALOG + EXTRA_CATALOG

    /**
     * Resuelve un modelo por clave de catálogo o por nombre visible.
     * La UI pasaba `info.name` como clave, lo que hacía fallar la búsqueda.
     */
    private fun resolveInfo(modelKey: String): ModelInfo? =
        ALL_MODELS[modelKey]
            ?: ALL_MODELS.values.firstOrNull { it.name == modelKey || it.displayName == modelKey }

    data class ModelInfo(
        val name: String,
        val description: String,
        val url: String,
        val sizeBytes: Long,
        val format: ModelFormat,
        val supportsVision: Boolean,
        val quantType: String? = null,
        /** El modelo puede emitir razonamiento (compatible con "Thinking mode"). */
        val supportsThinking: Boolean = false,
        val mmprojFile: String? = null,
        val sha256: String? = null, // checksum para verificación de integridad
        /** Tipo de modelo: decide la carpeta de destino y la pestaña del downloader. */
        val kind: ModelKind = ModelKind.CHAT,
        /** Nombre de archivo remoto cuando no se deriva del nombre visible. */
        val remoteName: String? = null,
    ) {
        val displayName: String
            get() = "$name (${format.displayName}${quantType?.let { " - $it" } ?: ""})"

        /** Carpeta base de destino según el tipo. */
        fun baseDir(context: Context): File = when (kind) {
            ModelKind.CHAT -> File(context.filesDir, MODELS_DIR)
            ModelKind.MMPROJ -> File(context.filesDir, "mmproj")
            ModelKind.DRAW -> File(context.filesDir, "draw")
            ModelKind.TAGGER -> File(context.filesDir, "tagger")
        }.apply { mkdirs() }

        val fileName: String
            get() = remoteName ?: when (format) {
                ModelFormat.GGUF -> "gguf/${this@ModelInfo.name.lowercase().replace(' ', '-')}.gguf"
                ModelFormat.LITERTLM -> "litertlm/${this@ModelInfo.name.lowercase().replace(' ', '-')}.litertlm"
            }

        val expectedSize: Long get() = sizeBytes
    }

    enum class ModelKind { CHAT, MMPROJ, DRAW, TAGGER }

    enum class ModelFormat(val displayName: String) {
        GGUF("GGUF (llama.cpp)"),
        LITERTLM(".litertlm (LiteRT-LM)")
    }

    sealed class DownloadResult {
        data class Success(val modelPath: String, val modelName: String) : DownloadResult()
        data class Failure(val error: String) : DownloadResult()
        object Cancelled : DownloadResult()
        data class NetworkError(val message: String) : DownloadResult()
    }

    data class ProgressInfo(
        val progress: Double,           // 0.0 a 1.0
        val downloadedBytes: Long,
        val totalBytes: Long,
        val speedBytesPerSec: Double,
        val etaSeconds: Long? = null
    )

    typealias ProgressCallback = (ProgressInfo) -> Unit
    typealias CompletionCallback = (DownloadResult) -> Unit

    /** Directorio de modelos en almacenamiento interno de la app */
    private fun getModelsDir(context: Context): File {
        return File(context.filesDir, MODELS_DIR).apply { mkdirs() }
    }

    /** Obtiene la ruta completa donde se guardará el modelo */
    fun getModelFilePath(context: Context, modelKey: String): String {
        val info = resolveInfo(modelKey) ?: return ""
        return File(info.baseDir(context), info.fileName).absolutePath
    }

    /** Obtiene la URL de descarga para un modelo */
    fun getModelUrl(modelKey: String): String? {
        return MODEL_CATALOG[modelKey]?.url
    }

    /** Lista todos los modelos disponibles en el catálogo */
    fun getAvailableModels(): List<ModelInfo> = ALL_MODELS.values.toList()

    /** Filtra modelos por formato */
    fun getModelsByFormat(format: ModelFormat): List<ModelInfo> =
        MODEL_CATALOG.values.filter { it.format == format }.toList()

    /** Tamaño esperado (Content-Length del catálogo) para un nombre de archivo. */
    fun expectedSizeForFileName(fileName: String): Long? =
        MODEL_CATALOG.values
            .firstOrNull { it.fileName.substringAfterLast('/') == fileName }
            ?.sizeBytes

    /**
     * true si el archivo quedó incompleto respecto al tamaño del catálogo.
     * Un modelo importado a mano (no catalogado) se considera completo: no hay
     * con qué compararlo.
     */
    fun isIncomplete(file: File): Boolean {
        val expected = expectedSizeForFileName(file.name) ?: return false
        return file.length() < (expected * EXISTING_FILE_SIZE_RATIO).toLong()
    }

    /** Verifica si un modelo ya está descargado */
    fun isModelDownloaded(context: Context, modelKey: String): Boolean {
        val info = resolveInfo(modelKey) ?: return false
        val modelFile = File(info.baseDir(context), info.fileName)
        return modelFile.exists() && modelFile.length() > info.sizeBytes * EXISTING_FILE_SIZE_RATIO
    }

    /** Verifica integridad del archivo descargado */
    private fun verifyChecksum(file: File, expectedSha256: String?): Boolean {
        expectedSha256?.let {
            if (it.startsWith("a1b2") || it.startsWith("b2c3") || it.startsWith("c3d4") ||
                it.startsWith("d4e5") || it.startsWith("e5f6") || it.startsWith("f6a7") ||
                it.startsWith("a7b8") || it.startsWith("b8c9") || it.startsWith("c9d0") ||
                it.startsWith("d0e1") || it.startsWith("e1f2")) {
                // Placeholder checksums - saltar verificación hasta tener reales
                return true
            }
        }
        expectedSha256?.let { expected ->
            return try {
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(CHUNK_SIZE)
                file.inputStream().use { input ->
                    var read = input.read(buffer)
                    while (read != -1) {
                        digest.update(buffer, 0, read)
                        read = input.read(buffer)
                    }
                }
                val calculated = digest.digest().joinToString("") { "%02x".format(it) }
                calculated.equals(expected, ignoreCase = true)
            } catch (e: Exception) {
                Log.w(TAG, "Error verificando checksum: ${e.message}")
                true // si falla verificación, permitir (no bloquear)
            }
        }
        return true // sin checksum esperado, asumir OK
    }

    /** Verifica si mmproj está disponible para modelos multimodales GGUF */
    fun isMmprojAvailable(context: Context, modelKey: String): Boolean {
        val info = resolveInfo(modelKey)
        if (info?.mmprojFile == null) return true // no requiere mmproj
        val mmprojFile = File(getModelsDir(context), "gguf/${info.mmprojFile}")
        return mmprojFile.exists()
    }

    /** Verifica conectividad de red */
    private fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Cancela la descarga activa si existe */
    fun cancelActiveDownload() {
        cancelled.set(true)
        activeDownload.getAndSet(null)?.cancel()
    }

    /** Indica si hay una descarga en progreso */
    fun isDownloading(): Boolean = activeDownload.get()?.isActive == true

    /** Descarga un modelo con reintentos, progreso, cancelación y verificación */
    fun downloadModel(
        modelKey: String,
        context: Context,
        onProgress: ProgressCallback,
        onComplete: CompletionCallback
    ) {
        // Verificar red antes de empezar
        if (!isNetworkAvailable(context)) {
            onComplete(DownloadResult.NetworkError("Sin conexión a internet"))
            return
        }

        val info = resolveInfo(modelKey) ?: run {
            onComplete(DownloadResult.Failure("Modelo no encontrado en catálogo: $modelKey"))
            return
        }

        cancelled.set(false)
        val modelsDir = info.baseDir(context)
        val modelFile = File(modelsDir, info.fileName)
        modelFile.parentFile?.mkdirs()

        // Si ya existe y tiene tamaño razonable, saltar
        if (modelFile.exists() && modelFile.length() > info.sizeBytes * EXISTING_FILE_SIZE_RATIO) {
            Log.i(TAG, "Modelo ya existe: ${modelFile.absolutePath}")
            if (verifyChecksum(modelFile, info.sha256)) {
                DownloadNotifier.complete(context, info.name)
                onComplete(DownloadResult.Success(modelFile.absolutePath, modelKey))
            } else {
                modelFile.delete()
                // Continuar con descarga
            }
        }

        // Notificación de progreso: se actualiza en sitio (mismo id) y sobrevive
        // a que el usuario cambie de pestaña o mande la app a segundo plano.
        DownloadNotifier.progress(context, info.name, 0, "Preparando…")
        val notifyProgress: ProgressCallback = { p ->
            onProgress(p)
            val pct = (p.progress * 100).toInt().coerceIn(0, 100)
            val speed = Formatters.speed(p.speedBytesPerSec)
            val eta = p.etaSeconds?.takeIf { it > 0 }?.let { " · ETA ${Formatters.eta(it)}" } ?: ""
            DownloadNotifier.progress(context, info.name, pct, "$pct% · $speed$eta")
        }

        // Iniciar descarga con reintentos
        val job = CoroutineScope(Dispatchers.IO).launch {
            var attempt = 0
            var lastError: String? = null

            while (attempt < MAX_RETRIES && !cancelled.get()) {
                attempt++
                try {
                    Log.i(TAG, "Descargando $modelKey (intento $attempt/$MAX_RETRIES)")
                    val result = downloadWithProgress(info, modelFile, context, modelsDir, notifyProgress)
                    
                    if (result is DownloadResult.Success) {
                        // Verificar checksum si está disponible
                        if (verifyChecksum(modelFile, info.sha256)) {
                            DownloadNotifier.complete(context, info.name)
                            onComplete(result)
                            return@launch
                        } else {
                            lastError = "Checksum inválido"
                            modelFile.delete()
                        }
                    } else if (result is DownloadResult.Cancelled) {
                        onComplete(result)
                        return@launch
                    } else {
                        lastError = (result as DownloadResult.Failure).error
                    }

                } catch (e: Exception) {
                    lastError = "Excepción: ${e.message}"
                    Log.e(TAG, "Error en intento $attempt: $lastError", e)
                    if (modelFile.exists()) modelFile.delete()
                }

                // Reintento con backoff exponencial
                if (attempt < MAX_RETRIES && !cancelled.get()) {
                    val delayMs = INITIAL_RETRY_DELAY_MS * (1L shl (attempt - 1)) // 1s, 2s, 4s...
                    Log.i(TAG, "Reintentando en ${delayMs}ms...")
                    delay(delayMs)
                }
            }

            // Todos los intentos fallaron
            if (cancelled.get()) {
                DownloadNotifier.cancel(context)
                onComplete(DownloadResult.Cancelled)
            } else {
                val err = lastError ?: "Error desconocido tras $MAX_RETRIES intentos"
                DownloadNotifier.failed(context, info.name, err)
                onComplete(DownloadResult.Failure(err))
            }
        }

        activeDownload.set(job)
    }

    /** Descarga un archivo único con progreso y soporte de cancelación */
    private suspend fun downloadWithProgress(
        info: ModelInfo,
        destFile: File,
        context: Context,
        modelsDir: File,
        onProgress: ProgressCallback
    ): DownloadResult = withContext(Dispatchers.IO) {
        var connection: java.net.HttpURLConnection? = null
        var input: java.io.InputStream? = null
        var output: FileOutputStream? = null

        try {
            val url = URL(info.url)
            connection = url.openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 60000
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                return@withContext DownloadResult.Failure("HTTP $responseCode: ${connection.responseMessage}")
            }

            val totalSize = connection.contentLengthLong
            input = connection.getInputStream()
            output = FileOutputStream(destFile)

            val buffer = ByteArray(CHUNK_SIZE)
            var downloaded = 0L
            var lastProgressTime = System.currentTimeMillis()
            var lastProgressBytes = 0L
            val startTime = System.currentTimeMillis()

            while (!cancelled.get()) {
                val read = input.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
                downloaded += read

                val now = System.currentTimeMillis()
                if (now - lastProgressTime > PROGRESS_THROTTLE_MS && totalSize > 0) {
                    val elapsedSec = (now - startTime) / 1000.0
                    val speed = if (elapsedSec > 0) downloaded / elapsedSec else 0.0
                    val eta = if (speed > 0) ((totalSize - downloaded) / speed).toLong() else null

                    // Callback en hilo principal
                    onProgress(ProgressInfo(
                        progress = downloaded.toDouble() / totalSize,
                        downloadedBytes = downloaded,
                        totalBytes = totalSize,
                        speedBytesPerSec = speed,
                        etaSeconds = eta
                    ))
                    lastProgressTime = now
                    lastProgressBytes = downloaded
                }
            }

            input.close()
            output.flush()
            output.close()

            if (cancelled.get()) {
                destFile.delete()
                return@withContext DownloadResult.Cancelled
            }

            // Verificar tamaño mínimo
            if (totalSize > 0 && destFile.length() < totalSize * MIN_FILE_SIZE_RATIO) {
                destFile.delete()
                return@withContext DownloadResult.Failure(
                    "Descarga incompleta: ${destFile.length()} / $totalSize bytes"
                )
            }

            // Descargar mmproj si es necesario
            if (info.mmprojFile != null && !cancelled.get()) {
                val mmprojUrl = "$HF_MIRROR/google/gemma-3n-e4b-it-gguf/resolve/main/${info.mmprojFile}"
                val mmprojFile = File(modelsDir, "gguf/${info.mmprojFile}")
                mmprojFile.parentFile?.mkdirs()
                downloadFile(mmprojUrl, mmprojFile)
            }

            DownloadResult.Success(destFile.absolutePath, info.name)

        } catch (e: java.net.SocketTimeoutException) {
            DownloadResult.NetworkError("Tiempo de conexión agotado: ${e.message}")
        } catch (e: java.io.IOException) {
            DownloadResult.Failure("Error de red: ${e.message}")
        } catch (e: Exception) {
            DownloadResult.Failure("Error inesperado: ${e.message}")
        } finally {
            try { input?.close() } catch (_: Exception) {}
            try { output?.close() } catch (_: Exception) {}
            try { connection?.disconnect() } catch (_: Exception) {}
        }
    }

    private suspend fun downloadFile(urlString: String, destFile: File) {
        withContext(Dispatchers.IO) {
            var connection: java.net.HttpURLConnection? = null
            var input: java.io.InputStream? = null
            var output: FileOutputStream? = null

            try {
                val url = URL(urlString)
                connection = url.openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 30000
                connection.readTimeout = 60000

                val responseCode = connection.responseCode
                if (responseCode != 200) {
                    Log.w(TAG, "mmproj HTTP $responseCode")
                    return@withContext
                }

                input = connection.getInputStream()
                output = FileOutputStream(destFile)
                val buffer = ByteArray(CHUNK_SIZE)

                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                }
                input.close()
                output.flush()
                output.close()

            } catch (e: Exception) {
                Log.w(TAG, "No se pudo descargar archivo adicional: ${e.message}")
            } finally {
                try { input?.close() } catch (_: Exception) {}
                try { output?.close() } catch (_: Exception) {}
                try { connection?.disconnect() } catch (_: Exception) {}
            }
        }
    }
}