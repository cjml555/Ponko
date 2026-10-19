// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File

/** File I/O helper functions extracted from MainActivity for cleaner separation of concerns. */
object FileManager {

    private const val MAX_READ_BYTES = 10_000_000

    /** Read input stream into ByteArray with a byte limit cap. Returns null if exceeds limit. */
    fun readAllCapped(ins: java.io.InputStream, cap: Int): ByteArray? {
        val buf = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val n = ins.read(chunk)
            if (n <= 0) break
            total += n
            if (total > cap) return null
            buf.write(chunk, 0, n)
        }
        return buf.toByteArray()
    }

    /** Copy input stream to output stream with a byte limit cap. Returns -1 if exceeds cap. */
    fun copyCapped(ins: java.io.InputStream, outs: java.io.OutputStream, cap: Long): Long {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = ins.read(buf)
            if (n <= 0) break
            total += n
            if (total > cap) return -1L
            outs.write(buf, 0, n)
        }
        return total
    }

    /** Query display name from Uri via ContentResolver. Returns null on failure. */
    fun queryDisplayName(uri: Uri, cr: ContentResolver): String? = try {
        cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Throwable) { null }

    /** Decode image with downsampling to avoid memory issues. Returns input stream or null. */
    fun decodeChatImage(ctx: android.content.Context, uri: Uri): Bitmap? = try {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (maxDim / sample > 1280) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        android.graphics.BitmapFactory.decodeStream(
            ctx.contentResolver.openInputStream(uri), null, opts
        )?.run {
            // Compress to JPEG byte stream for better memory usage
            val baos = ByteArrayOutputStream()
            compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, baos)
            // Actually let's just return the bitmap - callers handle it directly
            this
        }
    } catch (_: Throwable) { null }
}

data class FileRead(
    val displayName: String,
    val rawBytes: Long?,
    val text: String?,
    val truncated: Boolean?,
    val detail: String?  // e.g. "X pages"
)