package com.yumenode.ai.draw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DistilledPresetsTest {

    @Test
    fun turboNeedsFourSteps() {
        assertEquals(4, DistilledPresets.stepsFor("sd_turbo-f16-q8_0.gguf"))
        assertEquals(1f, DistilledPresets.cfgFor("sd_turbo-f16-q8_0.gguf"))
    }

    @Test
    fun lightningTwoStepWins() {
        assertEquals(2, DistilledPresets.stepsFor("sdxl-lightning-2step.q4_0.gguf"))
    }

    @Test
    fun hyperAndLcmAreOneStep() {
        assertEquals(1, DistilledPresets.stepsFor("hyper-sd-sd15-1step.safetensors"))
        assertEquals(1, DistilledPresets.stepsFor("hyper-sd-sd15-1step"))
    }

    @Test
    fun sdxsAndLcmNeedFour() {
        assertEquals(4, DistilledPresets.stepsFor("SDXS-512-0.9"))
        assertEquals(4, DistilledPresets.stepsFor("lcm-lora-sdv1-5.safetensors"))
    }

    @Test
    fun normalModelHasNoPreset() {
        assertNull(DistilledPresets.stepsFor("stable-diffusion-v1-5-pruned-emaonly-Q4_0.gguf"))
        assertNull(DistilledPresets.cfgFor("stable-diffusion-v1-5-pruned-emaonly-Q4_0.gguf"))
    }

    @Test
    fun blankNamesAreIgnored() {
        assertNull(DistilledPresets.stepsFor(null, "", "  "))
        assertEquals(4, DistilledPresets.stepsFor(null, "sd_turbo-q8_0.gguf"))
    }
}
