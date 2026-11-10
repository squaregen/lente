package it.squaregen.lente

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView

/** Frozen picture with pinch zoom, one-finger panning and double-tap zoom. */
class ZoomImageView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ImageView(context, attrs) {

    var onSingleTap: (() -> Unit)? = null
    var onZoomChanged: ((Float) -> Unit)? = null

    private val m = Matrix()
    private var bmp: Bitmap? = null
    private var minScale = 1f
    private var cropScale = 1f
    private val maxScale get() = cropScale * 12f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomAround(d.scaleFactor, d.focusX, d.focusY)
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            m.postTranslate(-dx, -dy)
            clamp()
            return true
        }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onSingleTap?.invoke()
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val s = currentScale()
            val target = if (s < cropScale * 2.4f) s * 2.5f else cropScale
            zoomAround(target / s, e.x, e.y)
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    fun currentScale(): Float {
        val v = FloatArray(9); m.getValues(v); return v[Matrix.MSCALE_X]
    }

    /** Relative zoom compared with what the live camera showed (1 = same). */
    fun relativeZoom(): Float = currentScale() / cropScale

    fun setPicture(b: Bitmap) {
        bmp = b
        setImageBitmap(b)
        post { resetToCrop() }
    }

    /** Swaps in a sharper copy of the same picture without moving what is on screen. */
    fun replaceKeepingView(b: Bitmap) {
        val old = bmp
        if (old == null || width == 0) { setPicture(b); return }
        val f = old.width.toFloat() / b.width
        bmp = b
        setImageBitmap(b)
        m.preScale(f, f)
        computeScales()
        clamp()
    }

    fun clear() {
        bmp = null
        setImageDrawable(null)
    }

    fun zoomBy(factor: Float) = zoomAround(factor, width / 2f, height / 2f)

    private fun computeScales() {
        val b = bmp ?: return
        if (width == 0 || height == 0) return
        val sx = width.toFloat() / b.width
        val sy = height.toFloat() / b.height
        minScale = minOf(sx, sy)
        cropScale = maxOf(sx, sy)
    }

    private fun resetToCrop() {
        val b = bmp ?: return
        computeScales()
        m.reset()
        m.postScale(cropScale, cropScale)
        m.postTranslate((width - b.width * cropScale) / 2f, (height - b.height * cropScale) / 2f)
        imageMatrix = m
        onZoomChanged?.invoke(relativeZoom())
    }

    private fun zoomAround(factor: Float, fx: Float, fy: Float) {
        val s = currentScale()
        val target = (s * factor).coerceIn(minScale, maxScale)
        val f = target / s
        m.postScale(f, f, fx, fy)
        clamp()
        onZoomChanged?.invoke(relativeZoom())
    }

    private fun clamp() {
        val b = bmp ?: return
        val r = RectF(0f, 0f, b.width.toFloat(), b.height.toFloat())
        m.mapRect(r)
        var dx = 0f
        var dy = 0f
        if (r.width() <= width) dx = (width - r.width()) / 2f - r.left
        else if (r.left > 0) dx = -r.left
        else if (r.right < width) dx = width - r.right
        if (r.height() <= height) dy = (height - r.height()) / 2f - r.top
        else if (r.top > 0) dy = -r.top
        else if (r.bottom < height) dy = height - r.bottom
        m.postTranslate(dx, dy)
        imageMatrix = m
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (bmp != null) resetToCrop()
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestures.onTouchEvent(event)
        return true
    }
}
