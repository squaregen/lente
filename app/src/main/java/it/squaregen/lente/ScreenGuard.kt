package it.squaregen.lente

import android.os.Handler
import android.os.Looper
import android.view.View

/**
 * Protects OLED screens from burn-in (a previous magnifier app burned its permanently shown
 * icons into the screen):
 *  - the controls hide themselves after a few seconds without use (a tap brings them back);
 *  - while visible, they drift by a few pixels every 30 seconds ("pixel shift", as OLED TVs do),
 *    so no pixel shows the same bright edge for long;
 *  - the panels use black backgrounds (black pixels are off on OLED and do not wear).
 */
class ScreenGuard(private val views: List<View>, private val shiftTargets: List<View>, private val dpUnit: Float) {

    var autoHideMs = 8000L
    var onVisibilityChanged: ((Boolean) -> Unit)? = null
    var visible = true
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var shiftIndex = 0
    private val offsets = listOf(0 to 0, 3 to 2, -2 to 3, -3 to -2, 2 to -3, 0 to 3, -3 to 0, 3 to -1)

    private val hideRunnable = Runnable { hide() }
    private val shiftRunnable = object : Runnable {
        override fun run() {
            shiftIndex = (shiftIndex + 1) % offsets.size
            val (x, y) = offsets[shiftIndex]
            shiftTargets.forEach {
                it.animate().translationX(x * dpUnit).translationY(y * dpUnit).setDuration(1500).start()
            }
            handler.postDelayed(this, 30_000)
        }
    }

    fun start() {
        handler.removeCallbacks(shiftRunnable)
        handler.postDelayed(shiftRunnable, 30_000)
        show()
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
    }

    fun show() {
        if (!visible) {
            visible = true
            views.forEach {
                it.animate().cancel()
                it.alpha = 0f
                it.visibility = View.VISIBLE
                it.animate().alpha(1f).setDuration(150).start()
            }
            onVisibilityChanged?.invoke(true)
        }
        poke()
    }

    /** Restarts the hide countdown (call on every user interaction). */
    fun poke() {
        handler.removeCallbacks(hideRunnable)
        if (autoHideMs > 0 && visible) handler.postDelayed(hideRunnable, autoHideMs)
    }

    fun hide() {
        handler.removeCallbacks(hideRunnable)
        if (!visible) return
        visible = false
        views.forEach { v ->
            v.animate().cancel()
            v.animate().alpha(0f).setDuration(400).withEndAction { if (!visible) v.visibility = View.GONE }.start()
        }
        onVisibilityChanged?.invoke(false)
    }

    /** Keeps controls visible (e.g. while a menu is open). */
    fun hold() {
        handler.removeCallbacks(hideRunnable)
    }
}
