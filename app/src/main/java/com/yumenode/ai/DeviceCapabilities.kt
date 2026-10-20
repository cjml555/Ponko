// Yumenode AI · (c) 2026 visitor257 · MIT License
package com.yumenode.ai

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

/**
 * Qué hardware tiene el teléfono, leído del sistema (no inventado).
 *
 * Sirve para tres cosas del backend:
 *  - elegir un número de hilos sensato en SoC big.LITTLE (Snapdragon/Mali),
 *  - decir si hay Vulkan antes de ofrecer GPU,
 *  - mostrar la ficha del equipo en Ajustes.
 */
internal data class DeviceCapabilities(
    val manufacturer: String,
    val model: String,
    val soc: String,
    val socVendor: String,
    val abi: String,
    val sdk: Int,
    val totalRamMb: Long,
    val coreCount: Int,
    /** Núcleos del grupo más rápido (los "big"); en SoC sin big.LITTLE = todos. */
    val bigCores: Int,
    val maxFreqMhz: Int,
    val vulkanVersion: String?,
    val vulkanLevel: Int,
    val glesVersion: String,
) {
    val hasVulkan: Boolean get() = vulkanVersion != null

    val socLabel: String
        get() {
            val parts = listOf(socVendor, soc)
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != Build.UNKNOWN }
            return if (parts.isEmpty()) model else parts.joinToString(" ")
        }

    val ramLabel: String
        get() = if (totalRamMb >= 1024) "%.1f GB".format(totalRamMb / 1024.0) else "$totalRamMb MB"

    val cpuLabel: String
        get() = if (maxFreqMhz > 0) "$bigCores/$coreCount · $maxFreqMhz MHz" else "$bigCores/$coreCount"

    val vulkanLabel: String
        get() = vulkanVersion?.let { v -> if (vulkanLevel > 0) "$v · nivel $vulkanLevel" else v } ?: "—"
}

internal object DeviceProbe {

    @Volatile
    private var cached: DeviceCapabilities? = null

    fun read(ctx: Context): DeviceCapabilities = cached ?: synchronized(this) {
        cached ?: probe(ctx.applicationContext).also { cached = it }
    }

    private fun probe(ctx: Context): DeviceCapabilities {
        val pm = ctx.packageManager
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val freqs = (0 until cores).map { readLong("/sys/devices/system/cpu/cpu$it/cpufreq/cpuinfo_max_freq") ?: 0L }
        val max = freqs.maxOrNull() ?: 0L
        val big = if (max > 0) freqs.count { it > 0 && it >= (max * 0.85).toLong() }.coerceAtLeast(1) else cores

        val vkFeature = pm.systemAvailableFeatures
            ?.firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }
        val vkVersion = vkFeature?.version?.let { v ->
            "${(v shr 22) and 0x3FF}.${(v shr 12) and 0x3FF}"
        }
        val vkLevel = pm.systemAvailableFeatures
            ?.firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL }
            ?.version ?: 0

        val gles = runCatching {
            val am = ctx.getSystemService(ActivityManager::class.java)
            val v = am.deviceConfigurationInfo.reqGlEsVersion
            "%d.%d".format((v shr 16) and 0xFFFF, v and 0xFFFF)
        }.getOrDefault("—")

        val mem = ActivityManager.MemoryInfo()
        runCatching { ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(mem) }

        return DeviceCapabilities(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE,
            socVendor = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else "",
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "?",
            sdk = Build.VERSION.SDK_INT,
            totalRamMb = mem.totalMem / (1024L * 1024L),
            coreCount = cores,
            bigCores = big,
            maxFreqMhz = (max / 1000L).toInt(),
            vulkanVersion = vkVersion,
            vulkanLevel = vkLevel,
            glesVersion = gles,
        )
    }

    /**
     * Hilos recomendados: los núcleos "big".
     *
     * En Snapdragon/Mali, repartir el trabajo a los little (o esperar a que
     * migren) suele salir más caro que usar solo los grandes. Si el equipo no
     * expone frecuencias, se asume el total.
     */
    /**
     * Hilos recomendados: los nucleos "big", con un minimo de 4.
     *
     * El minimo importa en los SoC con un solo nucleo prime: un Dimensity 8350
     * expone 1 nucleo a 4.21 GHz y 3 a ~3.0 GHz, asi que el conteo por frecuencia
     * da 1 y el motor se queda con un hilo (unas 6x mas lento). Con 4 hilos el
     * scheduler reparte entre prime + los tres medios.
     */
    fun recommendedThreads(caps: DeviceCapabilities): Int {
        val floor = minOf(MIN_THREADS, caps.coreCount)
        return caps.bigCores.coerceAtLeast(floor).coerceIn(1, caps.coreCount)
    }

    /** Suelo de hilos: por debajo de esto el dibujo se arrastra. */
    private const val MIN_THREADS = 4

    private fun readLong(path: String): Long? = runCatching {
        val f = File(path)
        if (!f.canRead()) return@runCatching null
        f.readText().trim().toLongOrNull()
    }.getOrNull()
}
