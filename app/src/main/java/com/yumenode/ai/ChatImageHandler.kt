// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.app.Dialog
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.ai.edge.litertlm.Capabilities
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
) {

    /** directorio privado donde se guardan las imágenes del chat */
    private val chatImgDir: File
        get() = File(ctx.filesDir, "chatimg").apply { mkdirs() }

    // ── colores ──
    private val pal get() = host.palette
    private val C_TEXT get() = pal.text
    private val C_SUBTEXT get() = pal.subText
    private val C_ERR_TEXT get() = pal.errText

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
        Capabilities(path).use { it.inputModalities().vision }
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

    private fun dp(d: Int): Int {
        val scale = ctx.resources.displayMetrics.density
        return (d * scale + 0.5f).toInt()
    }

    private fun matchWrap() =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
}

/** ImageView con doble toque para zoom y arrastre: 1x ~ 8x. */
class ZoomImageView(ctx: android.content.Context) : ImageView(ctx) {

    private val mtx = Matrix()
    private var base = 1f
    private var cur = 1f
    private var lastX = 0f
    private var lastY = 0f
    private var down = false
    var onTapAtFit: (() -> Unit)? = null

    private val scaleDet = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(det: ScaleGestureDetector): Boolean {
            val want = (cur * det.scaleFactor).coerceIn(1f, 8f)
            val r = want / cur
            if (r == 1f) return true
            cur = want
            mtx.postScale(r, r, det.focusX, det.focusY)
            clamp()
            imageMatrix = mtx
            return true
        }
    })

    private val tapDet = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (cur <= 1.01f) onTapAtFit?.invoke()
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val want = if (cur > 1.05f) 1f else 2.5f
            val r = want / cur
            cur = want
            mtx.postScale(r, r, e.x, e.y)
            clamp()
            imageMatrix = mtx
            return true
        }
    })

    init { setScaleType(ImageView.ScaleType.MATRIX) }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        reset()
    }

    /** Vuelve a «ajustado y centrado» */
    fun reset() {
        val d = drawable ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (vw <= 0f || vh <= 0f || dw <= 0f || dh <= 0f) return
        base = minOf(vw / dw, vh / dh)
        cur = 1f
        mtx.reset()
        mtx.postScale(base, base)
        mtx.postTranslate((vw - dw * base) / 2f, (vh - dh * base) / 2f)
        imageMatrix = mtx
    }

    /** Después del zoom, devuelve la imagen al área visible para que no se pierda. */
    private fun clamp() {
        if (cur <= 1.01f) { reset(); return }
        val d = drawable ?: return
        val v = FloatArray(9)
        mtx.getValues(v)
        val sw = d.intrinsicWidth * base * cur
        val sh = d.intrinsicHeight * base * cur
        v[Matrix.MTRANS_X] = if (sw <= width) (width - sw) / 2f else v[Matrix.MTRANS_X].coerceIn(width - sw, 0f)
        v[Matrix.MTRANS_Y] = if (sh <= height) (height - sh) / 2f else v[Matrix.MTRANS_Y].coerceIn(height - sh, 0f)
        mtx.setValues(v)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        scaleDet.onTouchEvent(ev)
        tapDet.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = ev.x; lastY = ev.y; down = true }
            MotionEvent.ACTION_MOVE -> {
                if (down && cur > 1.01f && !scaleDet.isInProgress) {
                    mtx.postTranslate(ev.x - lastX, ev.y - lastY)
                    clamp()
                    imageMatrix = mtx
                }
                lastX = ev.x
                lastY = ev.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> down = false
        }
        return true
    }
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