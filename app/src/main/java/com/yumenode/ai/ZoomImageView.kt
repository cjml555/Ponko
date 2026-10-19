// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.content.Context
import android.graphics.Matrix
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView

/**
 * 全屏看图用的 ImageView：双指缩放 + 拖动 + 双击放大，缩放范围 1x~8x。
 *
 * 从 MainActivity 摘出：自包含的自定义 View，无 Activity 依赖。
 */
internal class ZoomImageView(ctx: Context) : ImageView(ctx) {
    private val mtx = Matrix()
    private var base = 1f
    private var cur = 1f
    private var lastX = 0f
    private var lastY = 0f
    private var down = false
    var onTapAtFit: (() -> Unit)? = null

    private val scaleDet = ScaleGestureDetector(ctx,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
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

    /** 回到「适配并居中」状态 */
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

    /** 缩放后把图拉回可视范围，避免拖出去找不回来 */
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
