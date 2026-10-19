// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

/**
 * Formateo de magnitudes compartido por la UI y el descargador.
 *
 * Antes había tres copias (MainActivity para tamaños/velocidad/ETA y
 * ModelDownloader para velocidad/ETA). Una sola fuente evita que se
 * desincronicen los formatos entre la lista de descargas y la notificación.
 */
object Formatters {

    /** 1.7 GB / 807.7 MB / 379.4 MB. */
    fun size(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> String.format("%.1f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> String.format("%.1f MB", bytes / 1_000_000.0)
        else -> String.format("%.1f KB", bytes / 1024.0)
    }

    /** 18.9 MB/s / 640.2 KB/s / 900 B/s. */
    fun speed(bytesPerSec: Double): String = when {
        bytesPerSec >= 1_000_000_000L -> String.format("%.1f GB/s", bytesPerSec / 1_000_000_000.0)
        bytesPerSec >= 1_000_000L -> String.format("%.1f MB/s", bytesPerSec / 1_000_000.0)
        bytesPerSec >= 1024L -> String.format("%.1f KB/s", bytesPerSec / 1024.0)
        else -> "${bytesPerSec.toInt()} B/s"
    }

    /** 1:16 / 4:05 / 45s. */
    fun eta(seconds: Long): String = when {
        seconds >= 3600L -> String.format("%d:%02d", seconds / 3600, (seconds % 3600) / 60)
        seconds >= 60L -> String.format("%d:%02d", seconds / 60, seconds % 60)
        else -> "${seconds}s"
    }
}
