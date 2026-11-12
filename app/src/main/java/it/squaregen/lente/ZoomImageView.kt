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
    /** If set, a double tap does this instead of zooming. */
    var onDoubleTapAction: (() -> Unit)? = null
    var onZoomChanged: ((Float) -> Unit)? = null
    /** Called when the user pinches or drags (not when the app moves the picture). */
    var onUserGesture: (() -> Unit)? = null

    /** Part of the picture (picture coordinates) left bright; the rest is darkened. */
    var focusRect: RectF? = null
        set(v) { field = v; invalidate() }
    private val dimPaint = android.graphics.Paint().apply { color = 0xB0000000.toInt() }
    private var animator: android.animation.ValueAnimator? = null

    private val m = Matrix()
    private var bmp: Bitmap? = null
    private var minScale = 1f
    private var cropScale = 1f
    var maxRelativeZoom = 30f
    private val maxScale get() = cropScale * maxRelativeZoom

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            animator?.cancel()
            zoomAround(d.scaleFactor, d.focusX, d.focusY)
            onUserGesture?.invoke()
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            animator?.cancel()
            onUserGesture?.invoke()
            m.postTranslate(-dx, -dy)
            clamp()
            return true
        }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onSingleTap?.invoke()
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            onDoubleTapAction?.let { it(); return true }
            val s = currentScale()
            val target = if (s < cropScale * 2.4f) s * 2.5f else cropScale
            zoomAround(target / s, e.x, e.y)
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    /** Rotation (degrees) of the text line being shown: the picture is turned to make it level. */
    var focusAngle = 0f
    private var currentAngle = 0f

    /**
     * Smoothly shows [rect] centred on screen at [scale], turning the picture by -[angle] so a
     * slanted line of text appears level. [rect] is in the picture's coordinates turned by -[angle].
     */
    fun animateTo(rect: RectF, scale: Float, angle: Float = 0f, ms: Long = 450) {
        if (width == 0 || bmp == null) return
        val s1 = scale.coerceIn(minScale, maxScale)
        val s0 = currentScale()
        val a0 = currentAngle
        // Picture point now at the centre of the screen, and the one to bring there.
        val inv = Matrix()
        m.invert(inv)
        val p0 = floatArrayOf(width / 2f, height / 2f)
        inv.mapPoints(p0)
        val p1 = floatArrayOf(rect.centerX(), rect.centerY())
        Matrix().apply { setRotate(angle) }.mapPoints(p1)
        animator?.cancel()
        animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                val sc = s0 + (s1 - s0) * f
                val ang = a0 + (angle - a0) * f
                val q = floatArrayOf(p0[0] + (p1[0] - p0[0]) * f, p0[1] + (p1[1] - p0[1]) * f)
                m.reset()
                m.postScale(sc, sc)
                m.postRotate(-ang)
                m.mapPoints(q)
                m.postTranslate(width / 2f - q[0], height / 2f - q[1])
                currentAngle = ang
                imageMatrix = m
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val r = focusRect ?: return
        // Darken everything outside the focus window, in the levelled line's coordinates.
        canvas.save()
        canvas.concat(m)
        canvas.rotate(focusAngle)
        val big = 100_000f
        canvas.drawRect(-big, -big, big, r.top, dimPaint)
        canvas.drawRect(-big, r.bottom, big, big, dimPaint)
        canvas.drawRect(-big, r.top, r.left, r.bottom, dimPaint)
        canvas.drawRect(r.right, r.top, big, r.bottom, dimPaint)
        canvas.restore()
    }

    fun currentScale(): Float {
        val v = FloatArray(9); m.getValues(v)
        return kotlin.math.sqrt(v[Matrix.MSCALE_X] * v[Matrix.MSCALE_X] + v[Matrix.MSKEW_Y] * v[Matrix.MSKEW_Y])
    }

    /** Relative zoom compared with what the live camera showed (1 = same). */
    fun relativeZoom(): Float = currentScale() / cropScale

    fun setPicture(b: Bitmap) {
        // One picture pixel = one drawable unit, so picture coordinates (text boxes) match the matrix.
        b.density = resources.displayMetrics.densityDpi
        bmp = b
        setImageBitmap(b)
        post { resetToCrop() }
    }

    /** Swaps in a sharper copy of the same picture without moving what is on screen. */
    fun replaceKeepingView(b: Bitmap) {
        val old = bmp
        if (old == null || width == 0) { setPicture(b); return }
        // The sharp photo may cover a wider field than the screen showed: the old picture is the
        // centre of it, with the old picture's proportions.
        val aspect = old.width.toFloat() / old.height
        val cropW: Float
        val cropH: Float
        if (b.width.toFloat() / b.height > aspect) { cropH = b.height.toFloat(); cropW = cropH * aspect }
        else { cropW = b.width.toFloat(); cropH = cropW / aspect }
        val s = cropW / old.width
        bmp = b
        b.density = resources.displayMetrics.densityDpi
        setImageBitmap(b)
        m.preScale(1 / s, 1 / s)
        m.preTranslate(-(b.width - cropW) / 2f, -(b.height - cropH) / 2f)
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
        currentAngle = 0f
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

    /** Fits the whole picture on screen. */
    fun showWhole() {
        val b = bmp ?: return
        computeScales()
        m.reset()
        m.postScale(minScale, minScale)
        m.postTranslate((width - b.width * minScale) / 2f, (height - b.height * minScale) / 2f)
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
