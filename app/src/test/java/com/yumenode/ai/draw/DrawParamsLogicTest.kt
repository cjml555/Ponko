package com.yumenode.ai.draw

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests de caracterizacion de [DrawParamsLogic].
 *
 * Fijan el comportamiento actual de los clamps que estaban repetidos en
 * `DrawPage` (generacion, upscale e img2img) y de la matematica del hires.
 */
class DrawParamsLogicTest {

    // ---------- alignSize / size ----------

    @Test
    fun sizeIsRoundedDownToMultipleOf64() {
        assertEquals(448, DrawParamsLogic.alignSize(500))
        assertEquals(512, DrawParamsLogic.alignSize(512))
        assertEquals(64, DrawParamsLogic.alignSize(65))
    }

    @Test
    fun sizeIsClampedToEngineRange() {
        assertEquals(64, DrawParamsLogic.alignSize(0))
        assertEquals(64, DrawParamsLogic.alignSize(32))
        assertEquals(2048, DrawParamsLogic.alignSize(4096))
    }

    @Test
    fun sizeFromTextFallsBackTo512() {
        assertEquals(512, DrawParamsLogic.size(null))
        assertEquals(512, DrawParamsLogic.size(""))
        assertEquals(512, DrawParamsLogic.size("no es un numero"))
    }

    @Test
    fun sizeFromTextIsAligned() {
        assertEquals(768, DrawParamsLogic.size("800"))
        assertEquals(512, DrawParamsLogic.size("512"))
    }

    // ---------- steps / cfg / loraScale ----------

    @Test
    fun stepsAreClampedAndDefaulted() {
        assertEquals(20, DrawParamsLogic.steps(null))
        assertEquals(20, DrawParamsLogic.steps("abc"))
        assertEquals(1, DrawParamsLogic.steps("0"))
        assertEquals(150, DrawParamsLogic.steps("999"))
        assertEquals(30, DrawParamsLogic.steps("30"))
    }

    @Test
    fun cfgIsClampedAndDefaulted() {
        assertEquals(7.0f, DrawParamsLogic.cfg(null), 0.0001f)
        assertEquals(1f, DrawParamsLogic.cfg("0"), 0.0001f)
        assertEquals(30f, DrawParamsLogic.cfg("100"), 0.0001f)
        assertEquals(1.5f, DrawParamsLogic.cfg("1.5"), 0.0001f)
    }

    @Test
    fun loraScaleIsClampedAndDefaulted() {
        assertEquals(1.0f, DrawParamsLogic.loraScale(null), 0.0001f)
        assertEquals(0f, DrawParamsLogic.loraScale("-1"), 0.0001f)
        assertEquals(2f, DrawParamsLogic.loraScale("5"), 0.0001f)
        assertEquals(0.8f, DrawParamsLogic.loraScale("0.8"), 0.0001f)
    }

    // ---------- upscaleStrength ----------

    @Test
    fun upscaleStrengthMapsProgressToRange() {
        assertEquals(0.05f, DrawParamsLogic.upscaleStrength(0), 0.0001f)
        assertEquals(0.55f, DrawParamsLogic.upscaleStrength(50), 0.0001f)
        assertEquals(0.99f, DrawParamsLogic.upscaleStrength(100), 0.0001f)
    }

    @Test
    fun upscaleStrengthIsClampedOutsideProgress() {
        assertEquals(0.05f, DrawParamsLogic.upscaleStrength(-10), 0.0001f)
        assertEquals(0.99f, DrawParamsLogic.upscaleStrength(500), 0.0001f)
    }

    // ---------- i2iStrength ----------

    @Test
    fun i2iStrengthIsClampedAndDefaulted() {
        assertEquals(0.75f, DrawParamsLogic.i2iStrength(null), 0.0001f)
        assertEquals(0.05f, DrawParamsLogic.i2iStrength("0"), 0.0001f)
        assertEquals(0.99f, DrawParamsLogic.i2iStrength("1"), 0.0001f)
    }

    // ---------- hiresDenoise ----------

    @Test
    fun hiresDenoiseMapsStrengthToUsableRange() {
        // 0.05 (conservador) -> 0.15 ; 0.99 (agresivo) -> 0.55
        assertEquals(0.15f, DrawParamsLogic.hiresDenoise(0.05f), 0.001f)
        assertEquals(0.55f, DrawParamsLogic.hiresDenoise(0.99f), 0.001f)
    }

    @Test
    fun hiresDenoiseIsMonotonic() {
        val low = DrawParamsLogic.hiresDenoise(0.10f)
        val mid = DrawParamsLogic.hiresDenoise(0.50f)
        val high = DrawParamsLogic.hiresDenoise(0.90f)

        assertEquals(true, low < mid)
        assertEquals(true, mid < high)
    }

    @Test
    fun hiresDenoiseStaysInsideHardBounds() {
        // Aunque la fuerza se salga de rango, el denoise queda acotado.
        assertEquals(0.10f, DrawParamsLogic.hiresDenoise(-1f), 0.001f)
        assertEquals(0.60f, DrawParamsLogic.hiresDenoise(5f), 0.001f)
    }

    @Test
    fun hiresDenoiseMapsSliderMiddleToBalancedValue() {
        // El punto medio del slider (0.52) debe dar el valor balanceado (~0.35).
        val mid = DrawParamsLogic.hiresDenoise(0.52f)
        assertEquals(0.35f, mid, 0.01f)
    }

    // ---------- tamanos del upscale ----------

    @Test
    fun upscaleTargetIsDoubleTheOriginal() {
        val (w, h) = DrawParamsLogic.upscaleTargetSize(512, 768)
        assertEquals(1024, w)
        assertEquals(1536, h)
    }

    @Test
    fun upscaleTargetIsAlignedAndClamped() {
        val (w, _) = DrawParamsLogic.upscaleTargetSize(1000, 512)
        assertEquals(1984, w)          // 2000 alineado a 64 -> 1984
        val (w2, _) = DrawParamsLogic.upscaleTargetSize(2048, 512)
        assertEquals(2048, w2)         // el doble excede el maximo -> 2048
    }

    @Test
    fun hiresFirstPassKeepsOriginalSize() {
        val (w, h) = DrawParamsLogic.hiresFirstPassSize(500, 300)
        assertEquals(448, w)
        assertEquals(256, h)
    }

    // ---------- VAE tiling ----------

    @Test
    fun vaeTileSizeIsClampedAndDefaulted() {
        assertEquals(512, DrawParamsLogic.vaeTileSize(null))
        assertEquals(64, DrawParamsLogic.vaeTileSize("10"))
        assertEquals(2048, DrawParamsLogic.vaeTileSize("9999"))
        assertEquals(256, DrawParamsLogic.vaeTileSize("256"))
    }

    @Test
    fun vaeTileOverlapIsClampedAndDefaulted() {
        assertEquals(0.25f, DrawParamsLogic.vaeTileOverlap(null), 0.0001f)
        assertEquals(0f, DrawParamsLogic.vaeTileOverlap("-0.5"), 0.0001f)
        assertEquals(0.5f, DrawParamsLogic.vaeTileOverlap("0.9"), 0.0001f)
    }

    // ---------- tagger ----------

    @Test
    fun taggerThresholdIsClampedAndDefaulted() {
        assertEquals(0.35f, DrawParamsLogic.taggerThreshold(null), 0.0001f)
        assertEquals(0f, DrawParamsLogic.taggerThreshold("-1"), 0.0001f)
        assertEquals(1f, DrawParamsLogic.taggerThreshold("5"), 0.0001f)
    }

    @Test
    fun taggerTopKIsClampedAndDefaulted() {
        assertEquals(40, DrawParamsLogic.taggerTopK(null))
        assertEquals(1, DrawParamsLogic.taggerTopK("0"))
        assertEquals(300, DrawParamsLogic.taggerTopK("9999"))
    }
}
