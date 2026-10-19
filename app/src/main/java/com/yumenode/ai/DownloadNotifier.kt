// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Notificación de progreso para las descargas de modelos.
 *
 * Usa una sola notificación (id fijo) que se actualiza en sitio: así no se
 * acumulan avisos cuando se descargan varios modelos seguidos y el usuario
 * ve el avance incluso con la app en segundo plano.
 *
 * En Android 13+ requiere POST_NOTIFICATIONS; si el usuario no la concede,
 * `notify` falla en silencio y la descarga sigue igual.
 */
object DownloadNotifier {

    private const val CHANNEL_ID = "ponko_downloads"
    private const val NOTIF_ID = 4201

    private fun nm(ctx: Context): NotificationManager =
        ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "Descargas de modelos",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Progreso al descargar modelos GGUF"
            setShowBadge(false)
        }
        nm(ctx).createNotificationChannel(ch)
    }

    private fun builder(ctx: Context, title: String): Notification.Builder {
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(ctx, CHANNEL_ID)
        else
            Notification.Builder(ctx)
        return b.setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
    }

    /** Barra de progreso determinada (0..100). */
    fun progress(ctx: Context, modelName: String, pct: Int, detail: String) {
        ensureChannel(ctx)
        val p = pct.coerceIn(0, 100)
        val n = builder(ctx, "Descargando $modelName")
            .setContentText(detail)
            .setSubText("$p%")
            .setProgress(100, p, false)
            .setOngoing(true)
            .build()
        runCatching { nm(ctx).notify(NOTIF_ID, n) }
    }

    fun complete(ctx: Context, modelName: String) {
        ensureChannel(ctx)
        val n = builder(ctx, "Descarga completada")
            .setContentText(modelName)
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
        runCatching { nm(ctx).notify(NOTIF_ID, n) }
    }

    fun failed(ctx: Context, modelName: String, error: String) {
        ensureChannel(ctx)
        val n = builder(ctx, "Descarga fallida")
            .setContentText("$modelName · $error")
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
        runCatching { nm(ctx).notify(NOTIF_ID, n) }
    }

    fun cancel(ctx: Context) {
        runCatching { nm(ctx).cancel(NOTIF_ID) }
    }
}
