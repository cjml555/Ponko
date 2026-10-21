// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.ai.edge.litertlm.ModelInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * ChatImageHandler — 集中处理图片读写、预览、全屏查看、保存 a la galería, 拍照。
 *
 * Extraído de MainActivity para limpiar el God Object.
 *
 * Toda la lógica de imágenes del chat vive aquí. MainActivity le delega a través
 * de [ChatImageHost], que expone solo lo necesario (visión, adjuntos, toast,
 * cambio de pestaña, drawPage) sin acoplar a la Activity entera.
 */
class ChatImageHandler(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val host: ChatImageHost,
) : ThemeAliases {

    /** directorio privado donde se guardan las imágenes del chat */
    private val chatImgDir: File
        get() = File(ctx.filesDir, "chatimg").apply { mkdirs() }

    // ── colores ──
    override val palette: YumenodeTheme get() = host.palette

    // ── lectura / escritura de imágenes ──
    /** Lee una imagen ya guardada en chatimg/ por nombre de archivo. */
    fun readChatImage(name: String): Bitmap? = try {
        BitmapFactory.decodeFile(File(chatImgDir, name).absolutePath)
    } catch (_: Throwable) {
        null
    }

    /** Escribe un Bitmap como PNG en chatimg/, retorna si tuvo éxito. */
    fun writeChatImage(bmp: Bitmap, name: String): Boolean = try {
        File(chatImgDir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        true
    } catch (_: Throwable) {
        false
    }

    /** Convierte un Bitmap a bytes JPEG (para Content.ImageBytes de LiteRT-LM). */
    fun bitmapToJpeg(bmp: Bitmap, quality: Int = 88): ByteArray {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    /** Guarda un JPEG del chat (JPEG) por nombre de archivo. */
    fun writeChatJpeg(bmp: Bitmap, name: String): Boolean = try {
        File(chatImgDir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        true
    } catch (_: Throwable) {
        false
    }

    // ── visión ──
    /** Explora si un modelo .litertlm soporta entrada visual. Cualquier excepción → false. */
    fun probeVision(path: String): Boolean = try {
        ModelInfo.from(path).use { it.inputModalities().vision }
    } catch (_: Throwable) {
        false
    }

    // ── inserción de burbuja de imagen en el chat ──
    /** Inserta una imagen en una burbuja de respuesta IA. */
    fun attachImageBubble(ai: AiArea, bmp: Bitmap, name: String) {
        val iv = ImageView(ctx).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
            isClickable = true
            isFocusable = false
            setPadding(0, dp(6), 0, 0)
            setOnClickListener { host.showImageFullscreen(bmp) }
            setOnLongClickListener { host.showImageMenu(bmp, name); true }
        }
        ai.root.addView(iv, matchWrap())
        ai.root.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.s_141)
            textSize = 11f
            setTextColor(C_SUBTEXT)
            setPadding(0, dp(4), 0, 0)
        }, matchWrap())
    }

    // ── pantalla completa con zoom ──
    /** Abre el visor fullscreen de una imagen (delegado al host para tener Activity). */
    fun showImageFullscreen(bmp: Bitmap) {
        host.showImageFullscreen(bmp)
    }

    /** Menú de long-press sobre una imagen: guardar / citar / i2i / tagger. */
    fun showImageMenu(bmp: Bitmap, name: String) {
        host.showImageMenu(bmp, name)
    }

    /** Cita: coloca la imagen directamente como imagen pendiente de envío. */
    fun quoteImage(bmp: Bitmap) {
        if (!host.visionOk) {
            host.toast(ctx.getString(R.string.s_272))
            return
        }
        if (host.pendingImageCount >= host.maxAttach) {
            host.toast(ctx.getString(R.string.s_279))
            return
        }
        host.onQuoteImage(bmp)
    }

    // ── guardar a la galería del sistema ──
    /** Pide permiso de almacenamiento si es necesario y guarda la imagen en la galería. */
    fun saveImageToGallery(bmp: Bitmap, name: String, onResult: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P ||
            ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        ) {
            doSaveImageToGallery(bmp, name, onResult)
            return
        }
        host.onRequestStoragePermission { granted ->
            if (granted) doSaveImageToGallery(bmp, name, onResult)
            else host.toast(ctx.getString(R.string.s_294))
        }
    }

    private fun doSaveImageToGallery(bmp: Bitmap, name: String, onResult: (Boolean) -> Unit) {
        scope.launch {
            val ok = try {
                withContext(Dispatchers.IO) {
                    try {
                        val values = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, name)
                            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                put(MediaStore.Images.Media.RELATIVE_PATH,
                                    Environment.DIRECTORY_PICTURES + "/Yumenode AI")
                            }
                        }
                        val uri = ctx.contentResolver.insert(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        if (uri != null) {
                            ctx.contentResolver.openOutputStream(uri)?.use {
                                bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                            }
                            true
                        } else false
                    } catch (_: Throwable) {
                        false
                    }
                }
            } catch (_: Throwable) {
                false
            }
            withContext(Dispatchers.Main) {
                onResult(ok)
            }
        }
    }

    // ── cápsulas de utilidad ─

    /** dp → px redondeando (aqui un pixel de diferencia se nota; ver [UiScale]). */
    private fun dp(d: Int): Int =
        UiScale.dpToPxRounded(d, ctx.resources.displayMetrics.density)

    /** MATCH_PARENT x WRAP_CONTENT (helper de Context, reutilizado aqui). */
    private fun matchWrap() = with(ctx) { matchWrap() }

}

/** AI reply card — extraída de MainActivity como clase top-level para compartirla. */
class AiArea(
    val root: LinearLayout,
    val thoughtBox: LinearLayout,
    val thoughtHeader: TextView,
    val thoughtBody: TextView,
    val answer: TextView,
    val regenButton: TextView,
) {
}