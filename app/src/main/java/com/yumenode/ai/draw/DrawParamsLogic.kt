// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai.draw

/**
 * Logica pura de los parametros de dibujo.
 *
 * Extraida de `DrawPage` (que repetia los mismos clamps en la generacion, el
 * upscale y el img2img) para que las reglas se definan **una sola vez** y
 * sean verificables en JVM.
 *
 * No depende de Android: recibe strings/ints/floats ya leidos de los campos y
 * devuelve valores validos. Los limites vienen de sd.cpp (multiplos de 64,
 * rango 64..2048) y de los rangos que el propio motor acepta.
 */
object DrawParamsLogic {

    // ---- tamano ----
    /** sd.cpp exige multiplos de 64. */
    const val SIZE_MULTIPLE = 64
    const val MIN_SIZE = 64
    const val MAX_SIZE = 2048
    const val DEF_SIZE = 512

    // ---- pasos / cfg / lora ----
    const val MIN_STEPS = 1
    const val MAX_STEPS = 150
    const val DEF_STEPS = 20

    const val MIN_CFG = 1f
    const val MAX_CFG = 30f
    const val DEF_CFG = 7.0f

    const val MIN_LORA_SCALE = 0f
    const val MAX_LORA_SCALE = 2f
    const val DEF_LORA_SCALE = 1.0f

    // ---- fuerza de re-dibujo (upscale / img2img) ----
    /** 0.05 = casi la imagen original, 0.99 = casi re-dibujada. */
    const val MIN_STRENGTH = 0.05f
    const val MAX_STRENGTH = 0.99f
    const val DEF_I2I_STRENGTH = 0.75f

    // ---- VAE tiling ----
    const val MIN_TILE_SIZE = 64
    const val MAX_TILE_SIZE = 2048
    const val DEF_TILE_SIZE = 512
    const val MIN_TILE_OVERLAP = 0f
    const val MAX_TILE_OVERLAP = 0.5f
    const val DEF_TILE_OVERLAP = 0.25f

    // ---- tagger ----
    const val MIN_TAG_THRESHOLD = 0f
    const val MAX_TAG_THRESHOLD = 1f
    const val DEF_TAG_THRESHOLD = 0.35f
    const val MIN_TAG_TOPK = 1
    const val MAX_TAG_TOPK = 300
    const val DEF_TAG_TOPK = 40

    /**
     * Alinea un tamano al multiplo de 64 que exige sd.cpp y lo acota al rango
     * valido (64..2048).
     *
     * Redondea **hacia abajo** al multiplo: 500 -> 448, 512 -> 512.
     */
    fun alignSize(value: Int): Int =
        ((value / SIZE_MULTIPLE) * SIZE_MULTIPLE).coerceIn(MIN_SIZE, MAX_SIZE)

    /** Tamano alineado desde el texto del campo (default 512 si esta vacio). */
    fun size(raw: String?): Int = alignSize(raw?.toIntOrNull() ?: DEF_SIZE)

    /** Pasos de muestreo, acotados y con default. */
    fun steps(raw: String?): Int =
        raw?.toIntOrNull()?.coerceIn(MIN_STEPS, MAX_STEPS) ?: DEF_STEPS

    /** CFG, acotado y con default. */
    fun cfg(raw: String?): Float =
        raw?.toFloatOrNull()?.coerceIn(MIN_CFG, MAX_CFG) ?: DEF_CFG

    /** Peso de la LoRA, acotado y con default. */
    fun loraScale(raw: String?): Float =
        raw?.toFloatOrNull()?.coerceIn(MIN_LORA_SCALE, MAX_LORA_SCALE) ?: DEF_LORA_SCALE

    /**
     * Fuerza de re-dibujo a partir del progreso de la barra (0..100).
     *
     * La barra va de 0 a 100 y la fuerza real de 0.05 a 0.99, asi que el
     * progreso se convierte en fraccion y se le anade el minimo.
     */
    fun upscaleStrength(progress: Int): Float =
        (progress / 100f + MIN_STRENGTH).coerceIn(MIN_STRENGTH, MAX_STRENGTH)

    /** Fuerza de re-dibujo del img2img, acotada y con default. */
    fun i2iStrength(raw: String?): Float =
        raw?.toFloatOrNull()?.coerceIn(MIN_STRENGTH, MAX_STRENGTH) ?: DEF_I2I_STRENGTH

    /**
     * Fuerza de denoise del hires nativo de sd.cpp.
     *
     * El slider va de 0.05 a 0.99, pero en hires fix ese valor es demasiado
     * extremo: pasarlo tal cual deja la imagen sin detalle o destruye la
     * composicion. Se mapea al rango que el hires nativo usa bien:
     *
     *  - 0.15 = casi la original + un toque de nitidez (conservador)
     *  - 0.35 = balance (por defecto)
     *  - 0.55 = anade detalles visibles (puede mover la composicion)
     */
    fun hiresDenoise(strength: Float): Float =
        (0.15f + (strength - 0.05f) * (0.40f / 0.94f)).coerceIn(0.10f, 0.60f)

    /** Tamano objetivo del upscale: el doble del original, alineado. */
    fun upscaleTargetSize(width: Int, height: Int): Pair<Int, Int> =
        alignSize(width * 2) to alignSize(height * 2)

    /**
     * Tamano de la primera ronda del hires nativo: el tamano original
     * (alineado), para que la composicion no cambie.
     */
    fun hiresFirstPassSize(width: Int, height: Int): Pair<Int, Int> =
        alignSize(width) to alignSize(height)

    /** Lado del bloque de VAE tiling, acotado y con default. */
    fun vaeTileSize(raw: String?): Int =
        raw?.toIntOrNull()?.coerceIn(MIN_TILE_SIZE, MAX_TILE_SIZE) ?: DEF_TILE_SIZE

    /** Solapamiento de los bloques de VAE tiling, acotado y con default. */
    fun vaeTileOverlap(raw: String?): Float =
        raw?.toFloatOrNull()?.coerceIn(MIN_TILE_OVERLAP, MAX_TILE_OVERLAP) ?: DEF_TILE_OVERLAP

    /** Umbral del tagger (0..1), acotado y con default. */
    fun taggerThreshold(raw: String?): Float =
        raw?.toFloatOrNull()?.coerceIn(MIN_TAG_THRESHOLD, MAX_TAG_THRESHOLD) ?: DEF_TAG_THRESHOLD

    /** Numero de tags del tagger, acotado y con default. */
    fun taggerTopK(raw: String?): Int =
        raw?.toIntOrNull()?.coerceIn(MIN_TAG_TOPK, MAX_TAG_TOPK) ?: DEF_TAG_TOPK
}
