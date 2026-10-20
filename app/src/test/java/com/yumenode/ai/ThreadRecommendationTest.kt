package com.yumenode.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadRecommendationTest {

    private fun caps(cores: Int, big: Int) = DeviceCapabilities(
        manufacturer = "test", model = "test", soc = "test", socVendor = "test",
        abi = "arm64-v8a", sdk = 36, totalRamMb = 8000,
        coreCount = cores, bigCores = big, maxFreqMhz = 2000,
        vulkanVersion = "1.1", vulkanLevel = 1, glesVersion = "3.2",
    )

    @Test
    fun snapdragonStyleClusterUsesItsBigCores() {
        // 4 grandes + 4 little: el conteo ya da 4
        assertEquals(4, DeviceProbe.recommendedThreads(caps(cores = 8, big = 4)))
    }

    @Test
    fun singlePrimeCoreGetsFourThreads() {
        // Dimensity 8350: 1 nucleo a la frecuencia maxima -> 4 hilos, no 1
        assertEquals(4, DeviceProbe.recommendedThreads(caps(cores = 8, big = 1)))
    }

    @Test
    fun neverExceedsCoreCount() {
        assertEquals(2, DeviceProbe.recommendedThreads(caps(cores = 2, big = 1)))
        assertEquals(1, DeviceProbe.recommendedThreads(caps(cores = 1, big = 1)))
    }
}
