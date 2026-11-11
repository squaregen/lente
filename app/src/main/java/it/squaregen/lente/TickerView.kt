package it.squaregen.lente

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * "Line view" of desktop OCR magnifiers: the whole text on a single, very large line that slides
 * from right to left, so the eyes stay still and the text moves. The current word sits at about
 * one third of the width and is underlined. The user can drag the line with a finger.
 */
class TickerView(context: Context) : View(context) {

    var onTap: (() -> Unit)? = null
    /** Called while the user drags the line (new position in words). */
    var onScrub: ((Float) -> Unit)? = null

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val underline = Paint()
    private var text = ""
    private var starts = IntArray(0)
    private var xs = FloatArray(0)
    private var ends = FloatArray(0)
    private var bg = 0

    /** Position in words (fractional: 3.5 = half-way between word 3 and word 4). */
    var position = 0f
        set(v) {
            field = v.coerceIn(0f, maxOf(0f, (starts.size - 1).toFloat()))
            invalidate()
        }

    private var lengths = IntArray(0)

    fun setContent(text: String, tokenStarts: IntArray, tokenLengths: IntArray) {
        this.text = text
        starts = tokenStarts
        lengths = tokenLengths
        measure()
        invalidate()
    }

    fun setStyle(sizePx: Float, typeface: Typeface, textColor: Int, background: Int) {
        paint.textSize = sizePx
        paint.typeface = typeface
        paint.color = textColor
        underline.color = textColor
        bg = background
        measure()
        invalidate()
    }

    private fun measure() {
        xs = FloatArray(starts.size)
        ends = FloatArray(starts.size)
        if (text.isEmpty() || paint.textSize == 0f) return
        for (i in starts.indices) {
            xs[i] = paint.measureText(text, 0, starts[i])
            ends[i] = paint.measureText(text, 0, (starts[i] + lengths[i]).coerceAtMost(text.length))
        }
    }

    private fun posToX(p: Float): Float {
        if (xs.isEmpty()) return 0f
        val i = p.toInt().coerceIn(0, xs.size - 1)
        val next = if (i + 1 < xs.size) xs[i + 1] else ends[i]
        return xs[i] + (next - xs[i]) * (p - i)
    }

    private fun xToPos(x: Float): Float {
        if (xs.isEmpty()) return 0f
        var i = xs.indexOfLast { it <= x }
        if (i < 0) return 0f
        val next = if (i + 1 < xs.size) xs[i + 1] else ends[i]
        return i + if (next > xs[i]) ((x - xs[i]) / (next - xs[i])).coerceIn(0f, 1f) else 0f
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bg)
        if (xs.isEmpty()) return
        val anchor = width * 0.3f
        val shift = anchor - posToX(position)
        val baseline = height / 2f - (paint.descent() + paint.ascent()) / 2f
        // Draw only the words that are on screen.
        val first = xToPos(-shift).toInt().coerceIn(0, starts.size - 1)
        val last = xToPos(width - shift).toInt().coerceIn(0, starts.size - 1)
        val from = starts[first]
        val to = if (last + 1 < starts.size) starts[last + 1] else text.length
        canvas.drawText(text, from, to, xs[first] + shift, baseline, paint)
        val cur = position.toInt().coerceIn(0, starts.size - 1)
        val thick = paint.textSize / 14f
        canvas.drawRect(xs[cur] + shift, baseline + thick, ends[cur] + shift, baseline + thick * 2.5f, underline)
    }

    private var downX = 0f
    private var downPos = 0f
    private var dragging = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downPos = position; dragging = false }
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.x - downX) > 20 * resources.displayMetrics.density) dragging = true
                if (dragging) {
                    position = xToPos(posToX(downPos) - (e.x - downX))
                    onScrub?.invoke(position)
                }
            }
            MotionEvent.ACTION_UP -> if (!dragging) onTap?.invoke()
        }
        return true
    }
}
