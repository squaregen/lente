package it.squaregen.lente

import android.hardware.camera2.CaptureResult
import android.os.SystemClock
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.MeteringPoint
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import java.util.concurrent.TimeUnit

/**
 * Focus logic tuned for a magnifier that is mostly pointed at text held close to the lens.
 *
 * On top of the camera's continuous autofocus it:
 *  1. refocuses when the picture gets blurry while the phone is held still (continuous AF on
 *     phones often "settles" on a blurred page and stays there);
 *  2. aims the focus at the text the recogniser found, not just at the centre;
 *  3. notices when the lens is already at its closest focus distance and the picture is still
 *     not sharp, which means the phone is too close: the app then suggests moving it away and
 *     using zoom instead (the most common reason for a blurry magnifier image).
 */
class SmartFocus(private val callbacks: Callbacks) {

    interface Callbacks {
        fun isDeviceMoving(): Boolean
        fun onTooClose()
    }

    var camera: Camera? = null
    /** Factory in analysis-buffer coordinates (0..1). */
    var bufferPointFactory: SurfaceOrientedMeteringPointFactory? = null
    /** Closest focus distance in diopters (0 = fixed focus). */
    var minFocusDiopters = 0f
    var enabled = true
    var locked = false
        private set

    private var peak = 0f
    private var blurSince = 0L
    private var lastTrigger = 0L
    private var textX = 0.5f
    private var textY = 0.5f
    private var textSeenAt = 0L
    private var textVisible = false

    private var nearLimitSince = 0L
    private var lastTooClose = 0L
    private var lastSharpness = 0f

    /** Main thread, every frame. */
    fun onSharpness(s: Float) {
        lastSharpness = s
        val now = SystemClock.elapsedRealtime()
        peak = maxOf(s, peak * 0.99f)
        if (!enabled || locked) return
        if (s < peak * 0.55f && !callbacks.isDeviceMoving()) {
            if (blurSince == 0L) blurSince = now
            if (now - blurSince > 700 && now - lastTrigger > 2500) {
                focusOnSubject()
                blurSince = 0L
            }
        } else {
            blurSince = 0L
        }
    }

    /** Main thread, about once a second. */
    fun onText(found: Boolean, bx: Float, by: Float) {
        val now = SystemClock.elapsedRealtime()
        if (found) {
            textX = bx; textY = by; textSeenAt = now
            if (!textVisible && enabled && !locked && now - lastTrigger > 1500 && !callbacks.isDeviceMoving()) {
                // Text just came into view: focus on it once.
                focusAt(bx, by)
            }
        }
        textVisible = found
    }

    /** Camera thread: every capture result. Returns true if "too close" should be shown. */
    fun onCaptureResult(afState: Int?, focusDiopters: Float?) {
        if (!enabled || locked || minFocusDiopters <= 0f || afState == null || focusDiopters == null) {
            nearLimitSince = 0L
            return
        }
        val now = SystemClock.elapsedRealtime()
        val atLimit = focusDiopters >= minFocusDiopters * 0.9f
        val unfocused = afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED ||
            afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED ||
            afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN
        if (atLimit && unfocused && lastSharpness < peak * 0.7f) {
            if (nearLimitSince == 0L) nearLimitSince = now
            if (now - nearLimitSince > 1500 && now - lastTooClose > 20000) {
                lastTooClose = now
                nearLimitSince = 0L
                callbacks.onTooClose()
            }
        } else {
            nearLimitSince = 0L
        }
    }

    /** Focus where the text is (if seen recently), otherwise in the centre. */
    fun focusOnSubject() {
        val now = SystemClock.elapsedRealtime()
        if (now - textSeenAt < 2500) focusAt(textX, textY) else focusAt(0.5f, 0.5f)
    }

    fun focusAt(bx: Float, by: Float) {
        val f = bufferPointFactory ?: return
        focus(f.createPoint(bx, by), autoCancel = true)
    }

    fun focusAtPoint(point: MeteringPoint) {
        focus(point, autoCancel = !locked)
    }

    /** Locks focus and exposure on the current subject (useful when writing under the camera). */
    fun setLocked(lock: Boolean) {
        locked = lock
        val cam = camera ?: return
        if (lock) {
            val f = bufferPointFactory
            val now = SystemClock.elapsedRealtime()
            val p = when {
                f == null -> return
                now - textSeenAt < 2500 -> f.createPoint(textX, textY)
                else -> f.createPoint(0.5f, 0.5f)
            }
            focus(p, autoCancel = false)
        } else {
            cam.cameraControl.cancelFocusAndMetering()
        }
    }

    /** Runs a focus, then calls [then] when done or after at most 1.5 s. */
    fun focusThen(then: () -> Unit) {
        val cam = camera
        val f = bufferPointFactory
        if (cam == null || f == null || locked) { then(); return }
        val now = SystemClock.elapsedRealtime()
        val p = if (now - textSeenAt < 2500) f.createPoint(textX, textY) else f.createPoint(0.5f, 0.5f)
        val action = FocusMeteringAction.Builder(p, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(3, TimeUnit.SECONDS).build()
        lastTrigger = now
        var done = false
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val finish = Runnable { if (!done) { done = true; then() } }
        handler.postDelayed(finish, 1500)
        try {
            val future = cam.cameraControl.startFocusAndMetering(action)
            future.addListener({ handler.removeCallbacks(finish); handler.post(finish) }, { handler.post(it) })
        } catch (_: Exception) {
        }
    }

    private fun focus(point: MeteringPoint, autoCancel: Boolean) {
        val cam = camera ?: return
        lastTrigger = SystemClock.elapsedRealtime()
        val b = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
        if (autoCancel) b.setAutoCancelDuration(4, TimeUnit.SECONDS) else b.disableAutoCancel()
        try {
            cam.cameraControl.startFocusAndMetering(b.build())
        } catch (_: Exception) {
        }
    }
}
