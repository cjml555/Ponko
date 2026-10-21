// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.Gravity
import android.net.Uri
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.view.View
import org.json.JSONArray
import java.io.File

/**
 * AttachmentHandler — 集中处理图片与文本文件的读取、存储与预览逻辑。
 *
 * 抽取自 MainActivity 以清理 God Object (5654+ 行)。
 * MainActivity 通过 `attachmentHandler` 一次获取全部附件能力。
 */
class AttachmentHandler private constructor(
    private val context: Context,
    private val activity: Activity
) {

    // ──────────────────────────────────────────────
    //  常量（公开供 MainActivity 引用）
    // ──────────────────────────────────────────────
    companion object {
        const val REQ_PICK_CHAT_IMAGE = 1007
        const val REQ_PICK_FILE = 1010
        const val MAX_ATTACH = 4
        const val MAX_FILES = 2
        const val MAX_FILE_BYTES = 4 * 1024 * 1024

        @JvmStatic
        fun create(context: Context, activity: Activity): AttachmentHandler {
            return AttachmentHandler(context, activity)
        }
    }

    // ──────────────────────────────────────────────
    //  数据类
    // ──────────────────────────────────────────────
    /** 图片待发送项（供 MainActivity 引用） */
    data class PendingImage(
        val bmp: Bitmap,
        val jpeg: ByteArray
    )

    /** 文本文件待发送项（供 MainActivity 引用） */
    data class PendingFile(
        val name: String,
        val bytes: Long,
        var text: String,
        var truncated: Boolean,
        val detail: String? = null
    )

    /** 文件读取结果（供 MainActivity 引用） */
    data class FileRead(
        val file: PendingFile?,
        val error: String?
    )

    // ──────────────────────────────────────────────
    //  内部状态
    // ──────────────────────────────────────────────
    private val pendingImages = mutableListOf<PendingImage>()
    private val pendingFiles = mutableListOf<PendingFile>()

    // ──────────────────────────────────────────────
    //  UI 辅助
    // ──────────────────────────────────────────────
    private var attachPreviewStrip: HorizontalScrollView? = null
    private var attachPreviewBox: LinearLayout? = null

    fun attachViews(previewStrip: HorizontalScrollView, previewBox: LinearLayout) {
        this.attachPreviewStrip = previewStrip
        this.attachPreviewBox = previewBox
    }

    // ──────────────────────────────────────────────
    //  文件读写辅助
    // ──────────────────────────────────────────────
    fun readAllCapped(ins: java.io.InputStream, cap: Int): ByteArray? =
        FileManager.readAllCapped(ins, cap)

    fun readAllCapped(ins: java.io.InputStream): ByteArray? =
        readAllCapped(ins, MAX_FILE_BYTES)

    fun decodeChatImage(uri: Uri): Bitmap? = FileManager.decodeChatImage(activity, uri)

    fun queryDisplayName(uri: Uri): String? =
        FileManager.queryDisplayName(uri, activity.contentResolver)

    // ──────────────────────────────────────────────
    //  图片操作（对外 public）
    // ──────────────────────────────────────────────
    fun pickChatImage() {
        activity.startActivityForResult(
            Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            },
            REQ_PICK_CHAT_IMAGE
        )
    }

    fun pickChatFile() {
        activity.startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            },
            REQ_PICK_FILE
        )
    }

    fun addPendingImage(bmp: Bitmap, jpeg: ByteArray) {
        if (pendingImages.size >= MAX_ATTACH) {
            context.toast(context.getString(R.string.s_279), short = true)
            return
        }
        pendingImages.add(PendingImage(bmp, jpeg))
        rebuildAttachPreview()
    }

    /**
     * Importa las imágenes elegidas: decodifica en IO y añade en el hilo de UI
     * (añadir reconstruye la tira de miniaturas). Devuelve cuántas entraron.
     */
    suspend fun importPickedImages(uris: List<Uri>, toJpeg: (Bitmap) -> ByteArray): Int {
        var added = 0
        for (u in uris) {
            if (pendingImages.size >= MAX_ATTACH) break
            val pair = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                decodeChatImage(u)?.let { bmp -> bmp to toJpeg(bmp) }
            } ?: continue
            addPendingImage(pair.first, pair.second)
            added++
        }
        return added
    }

    /** Importa los archivos elegidos; devuelve el primer error, si lo hubo. */
    suspend fun importPickedFiles(uris: List<Uri>): String? {
        var err: String? = null
        for (u in uris) {
            if (pendingFiles.size >= MAX_FILES) break
            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { readTextFile(u) }
            val f = r.file
            if (f == null) {
                if (err == null) err = r.error
                continue
            }
            addPendingFile(f)
        }
        return err
    }

    fun removePendingImage(index: Int) {
        if (index in pendingImages.indices) pendingImages.removeAt(index)
        rebuildAttachPreview()
    }

    fun clearPendingImages() {
        pendingImages.clear()
        rebuildAttachPreview()
    }

    // ──────────────────────────────────────────────
    //  文本/文档文件操作（对外 public）
    // ──────────────────────────────────────────────
    fun readTextFile(uri: Uri): FileRead {
        val name = queryDisplayName(uri) ?: "file.txt"
        val ext = name.substringAfterLast('.', "").lowercase()

        if (ext in DocExtractor.LEGACY_DOCS) return FileRead(null, getString(R.string.s_389))
        if (ext in DocExtractor.ALL) return readDocFile(uri, name, ext, null)

        val raw = try {
            activity.contentResolver.openInputStream(uri)?.use { ins -> readAllCapped(ins) }
        } catch (_: Throwable) {
            null
        } ?: return FileRead(null, getString(R.string.s_296))
        if (raw.isEmpty()) return FileRead(null, getString(R.string.s_296))

        // PDF magic number fallback
        if (raw.size >= 4 && raw[0] == '%'.code.toByte() && raw[1] == 'P'.code.toByte() &&
            raw[2] == 'D'.code.toByte() && raw[3] == 'F'.code.toByte()
        ) {
            return readDocFile(uri, name, "pdf", raw)
        }
        for (i in 0 until minOf(raw.size, 4096)) {
            if (raw[i] == 0.toByte()) return FileRead(null, getString(R.string.s_296))
        }
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(raw)).toString()
        } catch (_: Throwable) {
            try { String(raw, charset("GBK")) } catch (_: Throwable) {
                return FileRead(null, getString(R.string.s_296))
            }
        }
        val clean = text.replace("\u0000", "")
        val body = fitTokens(clean, maxFileTokens())
        val truncated = body.length < clean.length
        return FileRead(PendingFile(name, raw.size.toLong(), body, truncated), null)
    }

    fun readDocFile(uri: Uri, name: String, ext: String, raw: ByteArray?): FileRead {
        val tmp = File(activity.cacheDir, "doc_${System.nanoTime()}.$ext")
        val copied: Long = try {
            if (raw != null) {
                tmp.outputStream().use { it.write(raw) }
                raw.size.toLong()
            } else {
                activity.contentResolver.openInputStream(uri)?.use { ins ->
                    tmp.outputStream().use { outs ->
                        FileManager.copyCapped(ins, outs, DocExtractor.MAX_DOC_BYTES)
                    }
                } ?: -1L
            }
        } catch (_: Throwable) {
            -1L
        }
        if (copied < 0) {
            tmp.delete()
            return FileRead(null, getString(R.string.s_296))
        }
        val ok = try {
            DocExtractor.extract(activity, tmp, ext)
        } finally {
            tmp.delete()
        }
        if (ok == null) return FileRead(null, getString(R.string.s_390))
        val body = fitTokens(ok.text, maxFileTokens())
        val detail = if (ok.units > 0 && ok.unitRes != 0) "${ok.units} ${getString(ok.unitRes)}" else null
        return FileRead(PendingFile(name, copied, body, body.length < ok.text.length, detail), null)
    }

    fun addPendingFile(file: PendingFile) {
        if (pendingFiles.size >= MAX_FILES) {
            context.toast(context.getString(R.string.s_297), short = true)
            return
        }
        pendingFiles.add(file)
        rebuildAttachPreview()
    }

    fun removePendingFile(index: Int) {
        if (index in pendingFiles.indices) pendingFiles.removeAt(index)
        rebuildAttachPreview()
    }

    fun clearPendingFiles() {
        pendingFiles.clear()
        rebuildAttachPreview()
    }

    // ──────────────────────────────────────────────
    //  附件预览构建（UI 层，需 MainActivity 注入 attachViews）
    // ──────────────────────────────────────────────
    fun rebuildAttachPreview() {
        val strip = attachPreviewStrip ?: return
        val box = attachPreviewBox ?: return
        val pal = (activity as MainActivity).themeManager.pal
        box.removeAllViews()

        for (i in pendingImages.indices) {
            val img = pendingImages[i]
            val chip = FrameLayout(activity)
            chip.addView(ImageView(activity).apply {
                setImageBitmap(img.bmp)
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = context.rounded(pal.softBtn, 10)
            }, FrameLayout.LayoutParams(context.dp(56), context.dp(56)))
            chip.addView(TextView(activity).apply {
                text = "×"
                textSize = 13f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = context.rounded(0xFFDC2626.toInt(), 10)
                isClickable = true
                setOnClickListener { removePendingImage(i) }
            }, FrameLayout.LayoutParams(context.dp(20), context.dp(20)).apply {
                gravity = Gravity.TOP or Gravity.END
            })
            box.addView(chip, LinearLayout.LayoutParams(context.dp(60), context.dp(56)).apply {
                rightMargin = context.dp(6)
            })
        }
        for (i in pendingFiles.indices) {
            val f = pendingFiles[i]
            val chip = FrameLayout(activity)
            chip.addView(TextView(activity).apply {
                val head = if (f.detail.isNullOrEmpty()) "📄 ${f.name}" else "📄 ${f.name} · ${f.detail}"
                text = "$head\n${f.text.length} ${activity.getString(R.string.s_298)}" +
                    if (f.truncated) " · ${activity.getString(R.string.s_299)}" else ""
                textSize = 11f
                setTextColor(pal.text)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                background = context.rounded(pal.softBtn, 10)
                setPadding(context.dp(8), context.dp(8), context.dp(8), context.dp(8))
            }, FrameLayout.LayoutParams(context.dp(124), context.dp(56)))
            chip.addView(TextView(activity).apply {
                text = "×"
                textSize = 13f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = context.rounded(0xFFDC2626.toInt(), 10)
                isClickable = true
                setOnClickListener { removePendingFile(i) }
            }, FrameLayout.LayoutParams(context.dp(20), context.dp(20)).apply {
                gravity = Gravity.TOP or Gravity.END
            })
            box.addView(chip, LinearLayout.LayoutParams(context.dp(128), context.dp(56)).apply {
                rightMargin = context.dp(6)
            })
        }
        strip.visibility =
            if (pendingImages.isEmpty() && pendingFiles.isEmpty()) View.GONE else View.VISIBLE
    }

    // ──────────────────────────────────────────────
    //  附件还原（对外 public）
    // ──────────────────────────────────────────────
    fun restoreFiles(store: String?): Int {
        if (store.isNullOrEmpty()) return 0
        var n = 0
        try {
            val arr = JSONArray(store)
            for (i in 0 until arr.length()) {
                if (pendingFiles.size >= MAX_FILES) break
                val o = arr.getJSONObject(i)
                val body = o.optString("t")
                if (body.isEmpty()) continue
                pendingFiles.add(
                    PendingFile(
                        o.optString("n"), o.optLong("b"), o.optString("t"),
                        o.optBoolean("tr"), o.optString("d").takeIf { it.isNotEmpty() }
                    )
                )
                n++
            }
        } catch (_: Throwable) {}
        rebuildAttachPreview()
        return n
    }

    // ──────────────────────────────────────────────
    //  Token 估算（私有）
    // ──────────────────────────────────────────────
    /** Recorta texto para no exceder maxTokens. Delegado en [ContextBudget]. */
    private fun fitTokens(text: String, maxTokens: Int): String =
        ContextBudget.fitTokens(text, maxTokens)


    private fun maxFileTokens(): Int {
        return (4096 / 4).coerceIn(600, 8000)
    }

    // ──────────────────────────────────────────────
    //  便捷 API（对外 public，供 MainActivity 引用）
    // ──────────────────────────────────────────────
    fun getPendingImages(): List<PendingImage> = pendingImages.toList()
    fun getPendingFiles(): List<PendingFile> = pendingFiles.toList()

    // ──────────────────────────────────────────────
    //  内部工具
    // ──────────────────────────────────────────────
    fun getString(resId: Int): String = activity.getString(resId)
}
