// Yumenode AI · (c) 2026 visitor257 · MIT License
package com.yumenode.ai

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Galería de imágenes guardadas por la app en MediaStore (Pictures/Yumenode AI).
 *
 * Rejilla de miniaturas, visor a pantalla completa con zoom, compartir y borrar.
 * Autocontenida: solo depende del Activity, un CoroutineScope y el tema.
 */
internal class GalleryPage(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val pal: YumenodeTheme,
    private val cardFactory: () -> LinearLayout,
    private val titleFactory: (String) -> TextView,
    private val hintFactory: (String) -> TextView,
) {
    companion object {
        const val FOLDER_NAME = "Yumenode AI"
        const val REQ_PERM = 0x9A11
        const val REQ_DELETE = 0x9A12
        private const val COLS = 3
        private const val MAX_VIEW_PX = 2048
    }

    private data class Item(val uri: Uri, val name: String)

    private var gridHost: LinearLayout? = null
    private var emptyBox: LinearLayout? = null
    private var countView: TextView? = null
    private var permButton: TextView? = null

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

    fun build(): View {
        val sv = ScrollView(activity).apply {
            setBackgroundColor(pal.bg)
            clipToPadding = false
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val card = cardFactory()

        card.addView(titleFactory(activity.getString(R.string.s_499)))
        countView = hintFactory("").apply { visibility = View.GONE }
        card.addView(countView!!)

        emptyBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(30), 0, dp(20))
        }.also { box ->
            box.addView(TextView(activity).apply {
                text = "🖼️"
                textSize = 34f
                gravity = Gravity.CENTER
            })
            box.addView(hintFactory(activity.getString(R.string.s_500)).apply {
                gravity = Gravity.CENTER
                setTextColor(pal.text)
                textSize = 16f
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
            box.addView(hintFactory(activity.getString(R.string.s_501)).apply {
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(2) })
            permButton = TextView(activity).apply {
                text = activity.getString(R.string.s_502)
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(pal.primary)
                setPadding(dp(16), dp(12), dp(16), dp(12))
                isClickable = true
                visibility = View.GONE
                setOnClickListener { requestPermission() }
            }
            box.addView(permButton!!, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(10) })
        }
        card.addView(emptyBox!!)

        gridHost = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        card.addView(gridHost!!)

        column.addView(card)
        sv.addView(column, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        refresh()
        return sv
    }

    /** Recarga la rejilla (al abrir la pestaña o tras borrar). */
    fun refresh() {
        if (gridHost == null) return
        scope.launch {
            val items = withContext(Dispatchers.IO) { query() }
            render(items)
        }
    }

    // ---- MediaStore ----

    private fun query(): List<Item> {
        val out = ArrayList<Item>()
        val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME)
        val selection: String
        val args: Array<String>
        if (Build.VERSION.SDK_INT >= 29) {
            selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            args = arrayOf("Pictures/$FOLDER_NAME%")
        } else {
            @Suppress("DEPRECATION")
            selection = "${MediaStore.Images.Media.DATA} LIKE ?"
            args = arrayOf("%/Pictures/$FOLDER_NAME/%")
        }
        try {
            activity.contentResolver.query(
                base, projection, selection, args,
                "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    out.add(Item(ContentUris.withAppendedId(base, id), c.getString(nameCol) ?: "image"))
                }
            }
        } catch (_: Exception) {
            // sin permiso o proveedor no disponible: se muestra el estado vacío
        }
        return out
    }

    // ---- Render ----

    private fun render(items: List<Item>) {
        val host = gridHost ?: return
        val empty = emptyBox ?: return
        host.removeAllViews()

        if (items.isEmpty()) {
            empty.visibility = View.VISIBLE
            countView?.visibility = View.GONE
            permButton?.visibility = if (hasPermission()) View.GONE else View.VISIBLE
            return
        }
        empty.visibility = View.GONE
        countView?.visibility = View.VISIBLE
        countView?.text = activity.getString(R.string.s_506, items.size)

        val dm = activity.resources.displayMetrics
        val gap = dp(3)
        // Ancho por peso: las 3 celdas se reparten la fila sin recortar la última.
        val cell = ((dm.widthPixels - dp(24) - gap * 2 * COLS) / COLS).coerceAtLeast(dp(80))
        var row: LinearLayout? = null
        items.forEachIndexed { i, item ->
            if (i % COLS == 0) {
                row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                host.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }
            val iv = ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(pal.mediaBg)
                contentDescription = item.name
                layoutParams = LinearLayout.LayoutParams(0, cell, 1f).apply {
                    setMargins(gap, gap, gap, gap)
                }
                setOnClickListener { openViewer(item) }
            }
            row?.addView(iv)
            loadThumb(item.uri, iv, cell)
        }
        // Relleno de la última fila: sin esto, una celda sola se estira a todo el ancho.
        val inLastRow = items.size % COLS
        if (inLastRow != 0) {
            row?.let { r ->
                repeat(COLS - inLastRow) {
                    r.addView(View(activity), LinearLayout.LayoutParams(0, cell, 1f).apply {
                        setMargins(gap, gap, gap, gap)
                    })
                }
            }
        }
    }

    private fun loadThumb(uri: Uri, iv: ImageView, size: Int) {
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                try {
                    if (Build.VERSION.SDK_INT >= 29) {
                        activity.contentResolver.loadThumbnail(uri, Size(size, size), null)
                    } else {
                        @Suppress("DEPRECATION")
                        MediaStore.Images.Thumbnails.getThumbnail(
                            activity.contentResolver, ContentUris.parseId(uri),
                            MediaStore.Images.Thumbnails.MINI_KIND, null,
                        )
                    }
                } catch (_: Exception) {
                    null
                }
            }
            if (bmp != null) iv.setImageBitmap(bmp)
        }
    }

    // ---- Visor ----

    private fun openViewer(item: Item) {
        val dlg = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val frame = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
        val ziv = ZoomImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            onTapAtFit = { dlg.dismiss() }
        }
        frame.addView(ziv, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(8), dp(6), dp(8))
            setBackgroundColor(0x99000000.toInt())
        }
        fun actionButton(label: String, onClick: () -> Unit) = TextView(activity).apply {
            text = label
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            isClickable = true
            setOnClickListener { onClick() }
        }
        bar.addView(actionButton("✕") { dlg.dismiss() })
        bar.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(actionButton("↗") { share(item) })
        bar.addView(actionButton("🗑") { confirmDelete(item, dlg) })
        frame.addView(bar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { gravity = Gravity.TOP })

        dlg.setContentView(frame)
        dlg.show()
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { decode(item.uri) }
            if (bmp != null) ziv.setImageBitmap(bmp)
        }
    }

    private fun decode(uri: Uri): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        activity.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / sample > MAX_VIEW_PX) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        activity.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (_: Exception) {
        null
    }

    private fun share(item: Item) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, item.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(send, activity.getString(R.string.s_503)))
    }

    private fun confirmDelete(item: Item, dlg: Dialog) {
        AlertDialog.Builder(activity)
            .setMessage(activity.getString(R.string.s_505))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(activity.getString(R.string.s_504)) { _, _ ->
                if (Build.VERSION.SDK_INT >= 30) {
                    // El sistema pide confirmación y permite borrar también imágenes ajenas.
                    try {
                        val pending = MediaStore.createDeleteRequest(activity.contentResolver, listOf(item.uri))
                        activity.startIntentSenderForResult(pending.intentSender, REQ_DELETE, null, 0, 0, 0)
                        dlg.dismiss()
                        return@setPositiveButton
                    } catch (_: Exception) {
                        // cae al borrado directo
                    }
                }
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        try {
                            activity.contentResolver.delete(item.uri, null, null) > 0
                        } catch (_: Exception) {
                            false
                        }
                    }
                    if (ok) {
                        dlg.dismiss()
                        refresh()
                    } else {
                        android.widget.Toast.makeText(
                            activity, activity.getString(R.string.s_508), android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .show()
    }

    // ---- Permiso ----

    private fun permission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasPermission(): Boolean =
        activity.checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED

    private fun requestPermission() {
        activity.requestPermissions(arrayOf(permission()), REQ_PERM)
    }
}
