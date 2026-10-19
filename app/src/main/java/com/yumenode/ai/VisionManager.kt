// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Gestión del estado de visión/mmproj (proyección visual para gguf multimodal).
 *
 * Extraído de MainActivity: encapsula la selección, importación, borrado y
 * estado del archivo mmproj, así como la UI de la sección «多模态 gguf».
 *
 * Las lambdas de UI permiten que el manager controle la lógica mientras
 * MainActivity sigue dueño de las vistas y el ciclo de vida.
 */
class VisionManager(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val mmprojContainer: LinearLayout,
    private val mmprojStatusTv: TextView,
    private val onProgress: (Int?) -> Unit,
    private val onStatus: (String, Int) -> Unit,
    private val onError: (String) -> Unit,
    private val onModelChanged: () -> Unit,
) {

    // ── estado ──

    /** Ruta absoluta al mmproj seleccionado, o null si no hay ninguno. */
    var mmprojPath: String? = null

    /** true = el modelo actual soporta entrada visual. */
    var visionOk: Boolean = false
        private set

    fun setVisionOk(v: Boolean) { visionOk = v }

    // ── intents ──

    fun pickMmprojFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        // Nota: el ActivityResult se maneja en MainActivity.onActivityResult
        // con REQ_PICK_MMPROJ. Aquí solo construimos el Intent.
        // Decoupled from MainActivity: request code comes from the shared Constants.
        (ctx as? android.app.Activity)?.startActivityForResult(i, REQ_PICK_MMPROJ)
    }

    // ── importación ──

    /** Copia un .gguf seleccionado al directorio privado mmproj. */
    fun copyMmprojToPrivate(uri: Uri) {
        val name = queryDisplayName(uri) ?: "mmproj_${System.currentTimeMillis()}.gguf"
        if (!name.endsWith(".gguf", ignoreCase = true)) {
            onError("El archivo debe ser .gguf")
            return
        }
        onBusy(true)
        onStatus("Importando…", 0) // C_WARN
        scope.launch(Dispatchers.IO) {
            try {
                val dir = File(ctx.filesDir, "mmproj").apply { mkdirs() }
                val dest = File(dir, name)
                if (!dest.exists()) {
                    val input = ctx.contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("No se puede abrir el archivo")
                    val total = ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.length ?: -1L
                    input.use { ins ->
                        dest.outputStream().use { outs ->
                            val buf = ByteArray(1 shl 16)
                            var copied = 0L
                            while (true) {
                                val n = ins.read(buf)
                                if (n < 0) break
                                outs.write(buf, 0, n)
                                copied += n
                                if (total > 0) {
                                    val pct = (copied * 100 / total).toInt()
                                    withContext(Dispatchers.Main) { onProgress(pct) }
                                }
                            }
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    mmprojPath = dest.absolutePath
                    refreshMmprojUi()
                    onStatus("mmproj importado: ${dest.name}", 2) // C_OK
                    onModelChanged()
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    onStatus("Error de importación", 1) // C_ERR
                    onError(e.message ?: "Error desconocido")
                }
            } finally {
                withContext(Dispatchers.Main) { onBusy(false) }
            }
        }
    }

    // ── UI ──

    /** Refresca la sección mmproj: estado + lista de chips. */
    fun refreshMmprojUi() {
        val f = mmprojPath?.let { File(it) }
        mmprojStatusTv.text = if (f != null && f.isFile) {
            "mmproj: ${f.name}"
        } else {
            "Sin mmproj"
        }
        mmprojContainer.removeAllViews()
        val dir = File(ctx.filesDir, "mmproj")
        val files = dir.listFiles { x -> x.isFile && x.name.endsWith(".gguf", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (files.isEmpty()) {
            mmprojContainer.visibility = View.GONE
            return
        }
        mmprojContainer.visibility = View.VISIBLE
        mmprojContainer.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.s_472)
            textSize = 12f
            setTextColor(0xFF888888.toInt())
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(4) })
        // «不使用」
        mmprojContainer.addView(mmprojChip("Sin mmproj", mmprojPath == null, null))
        for (x in files) {
            mmprojContainer.addView(mmprojChip(x.name, x.absolutePath == mmprojPath, x))
        }
    }

    /** Un chip de opción: clic para seleccionar, largar para borrar. */
    private fun mmprojChip(label: String, selected: Boolean, file: File?): TextView {
        return TextView(ctx).apply {
            text = label
            textSize = 12.5f
            setTextColor(if (selected) 0xFF6200EE.toInt() else 0xFF333333.toInt())
            setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(12), ctx.dp(8))
            background = ctx.rounded(if (selected) 0xFFE8DEF8.toInt() else 0xFFF5F5F5.toInt(), 10,
                strokeDp = if (selected) 1 else 0, strokeColor = 0xFF6200EE.toInt())
            isClickable = true
            gravity = Gravity.CENTER
        }.also { chip ->
            chip.setOnClickListener {
                val want = file?.absolutePath
                if (want == mmprojPath) return@setOnClickListener
                mmprojPath = want
                refreshMmprojUi()
                onModelChanged()
            }
            if (file != null) {
                chip.setOnLongClickListener { confirmDeleteMmproj(file); true }
            }
        }
    }

    /** Confirmación para borrar un mmproj importado. */
    private fun confirmDeleteMmproj(f: File) {
        android.app.AlertDialog.Builder(ctx)
            .setMessage("¿Eliminar ${f.name}?")
            .setPositiveButton("Eliminar") { _, _ ->
                runCatching { f.delete() }
                if (mmprojPath == f.absolutePath) mmprojPath = null
                refreshMmprojUi()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ── helpers ──

    private fun queryDisplayName(uri: Uri): String? {
        return ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }

    private fun onBusy(busy: Boolean) {
        onProgress(if (busy) 0 else null) // null = ocultar
    }
}
