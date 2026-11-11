package it.squaregen.lente

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs

/**
 * Looks at every preview-sized frame:
 *  - measures sharpness and brightness of the centre (cheap, every frame), used by the smart focus
 *    and by the inactivity detector;
 *  - about once a second runs on-device text recognition to notice when the camera is pointed
 *    at text, so the app can offer to open it in the large-print reader.
 */
class FrameAnalyzer(private val listener: Listener) : ImageAnalysis.Analyzer {

    interface Listener {
        /** Called on the analysis thread. */
        fun onFrameStats(sharpness: Float, meanLuma: Float, dark: Int, light: Int)
        /**
         * Called on the main thread. [bufferX]/[bufferY] are the centre of the text in normalised
         * coordinates of the un-rotated camera buffer (what metering point factories expect).
         */
        fun onTextScan(found: Boolean, bufferX: Float, bufferY: Float)
        /** Guided capture only (main thread): where the text sits in the picture. */
        fun onGuide(info: GuideInfo) {}
    }

    /**
     * Framing of the text in the camera picture, used to guide the user before taking the photo.
     * The "touches" flags say that a line of text reaches that edge, so text is probably cut off.
     */
    class GuideInfo(
        val found: Boolean,
        val lines: Int,
        val touchesLeft: Boolean,
        val touchesTop: Boolean,
        val touchesRight: Boolean,
        val touchesBottom: Boolean,
        val meanLuma: Float,
    )

    @Volatile var ocrEnabled = true
    /** While guiding, recognise every frame (as fast as the phone allows) instead of once a second. */
    @Volatile var guideMode = false
    @Volatile private var lastMeanLuma = 128f

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    @Volatile private var ocrBusy = false
    private var lastOcr = 0L

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        try {
            computeStats(image)
        } catch (_: Exception) {
        }
        val now = SystemClock.elapsedRealtime()
        val media = image.image
        val interval = if (guideMode) 0 else 900
        if ((ocrEnabled || guideMode) && !ocrBusy && media != null && now - lastOcr > interval) {
            ocrBusy = true
            lastOcr = now
            val rotation = image.imageInfo.rotationDegrees
            val w = image.width
            val h = image.height
            val input = InputImage.fromMediaImage(media, rotation)
            recognizer.process(input).addOnCompleteListener { task ->
                try {
                    val text = if (task.isSuccessful) task.result else null
                    report(text, rotation, w, h)
                } finally {
                    image.close()
                    ocrBusy = false
                }
            }
            return
        }
        image.close()
    }

    private fun computeStats(image: ImageProxy) {
        val plane = image.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixStride = plane.pixelStride
        val w = image.width
        val h = image.height
        val x0 = w / 4; val x1 = w * 3 / 4 - 3
        val y0 = h / 4; val y1 = h * 3 / 4 - 3
        var sumGrad = 0L
        val hist = IntArray(256)
        var sumLuma = 0L
        var n = 0
        var y = y0
        while (y < y1) {
            val row = y * rowStride
            val rowBelow = (y + 2) * rowStride
            var x = x0
            while (x < x1) {
                val p = buf.get(row + x * pixStride).toInt() and 0xFF
                val right = buf.get(row + (x + 2) * pixStride).toInt() and 0xFF
                val below = buf.get(rowBelow + x * pixStride).toInt() and 0xFF
                sumGrad += abs(p - right) + abs(p - below)
                sumLuma += p
                hist[p]++
                n++
                x += 4
            }
            y += 4
        }
        if (n == 0) return
        // 5th and 95th percentile of brightness: roughly the ink and the paper.
        var acc = 0; var dark = 0; var light = 255
        for (i in 0..255) { acc += hist[i]; if (acc >= n * 0.05) { dark = i; break } }
        acc = 0
        for (i in 255 downTo 0) { acc += hist[i]; if (acc >= n * 0.05) { light = i; break } }
        lastMeanLuma = sumLuma.toFloat() / n
        listener.onFrameStats(sumGrad.toFloat() / n, sumLuma.toFloat() / n, dark, light)
    }

    private fun report(text: Text?, rotation: Int, w: Int, h: Int) {
        if (guideMode) reportGuide(text, rotation, w, h)
        if (text == null) {
            listener.onTextScan(false, 0.5f, 0.5f)
            return
        }
        var letters = 0
        var lines = 0
        var left = Int.MAX_VALUE; var top = Int.MAX_VALUE; var right = Int.MIN_VALUE; var bottom = Int.MIN_VALUE
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val l = line.text.count { it.isLetterOrDigit() }
                if (l >= 3) lines++
                letters += l
            }
            block.boundingBox?.let {
                left = minOf(left, it.left); top = minOf(top, it.top)
                right = maxOf(right, it.right); bottom = maxOf(bottom, it.bottom)
            }
        }
        val found = (letters >= 20 && lines >= 2) || letters >= 40
        if (!found || left == Int.MAX_VALUE) {
            listener.onTextScan(found, 0.5f, 0.5f)
            return
        }
        // Bounding boxes are in upright coordinates; convert the centre back to buffer space.
        val uprightW = if (rotation % 180 == 0) w else h
        val uprightH = if (rotation % 180 == 0) h else w
        val u = ((left + right) / 2f / uprightW).coerceIn(0f, 1f)
        val v = ((top + bottom) / 2f / uprightH).coerceIn(0f, 1f)
        val (bx, by) = when (rotation) {
            90 -> v to 1f - u
            180 -> 1f - u to 1f - v
            270 -> 1f - v to u
            else -> u to v
        }
        listener.onTextScan(true, bx, by)
    }

    private fun reportGuide(text: Text?, rotation: Int, w: Int, h: Int) {
        val uprightW = (if (rotation % 180 == 0) w else h).toFloat()
        val uprightH = (if (rotation % 180 == 0) h else w).toFloat()
        val margin = 0.025f
        var lines = 0
        var letters = 0
        var l = false; var t = false; var r = false; var b = false
        text?.textBlocks?.forEach { block ->
            block.lines.forEach { line ->
                val count = line.text.count { it.isLetterOrDigit() }
                letters += count
                if (count >= 2) lines++
                line.boundingBox?.let { box ->
                    if (box.left / uprightW < margin) l = true
                    if (box.top / uprightH < margin) t = true
                    if (box.right / uprightW > 1 - margin) r = true
                    if (box.bottom / uprightH > 1 - margin) b = true
                }
            }
        }
        val found = letters >= 12
        listener.onGuide(GuideInfo(found, lines, l && found, t && found, r && found, b && found, lastMeanLuma))
    }

    fun close() = recognizer.close()
}
