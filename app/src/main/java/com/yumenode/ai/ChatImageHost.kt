package com.yumenode.ai

import android.graphics.Bitmap

/**
 * ChatImageHost — Host que ChatImageHandler necesita de MainActivity.
 *
 * Solo los métodos que ChatImageHandler usa deben estar aquí:
 * - visionOk (Boolean)
 * - pendingImageCount (Int)
 * - maxAttach (Int)
 * - toast(msg: String)
 * - showImageFullscreen(bmp: Bitmap)
 * - showImageMenu(bmp: Bitmap, name: String)
 * - onQuoteImage(bmp: Bitmap)
 * - onRequestStoragePermission(granted: (Boolean) -> Unit)
 * - palette: YumenodeTheme
 */
interface ChatImageHost {
    val palette: YumenodeTheme
    val visionOk: Boolean
    val pendingImageCount: Int
    val maxAttach: Int
    fun toast(msg: String)
    fun showImageFullscreen(bmp: Bitmap)
    fun showImageMenu(bmp: Bitmap, name: String)
    fun onQuoteImage(bmp: Bitmap)
    fun onRequestStoragePermission(granted: (Boolean) -> Unit)
}