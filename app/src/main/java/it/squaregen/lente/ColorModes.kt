package it.squaregen.lente

import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import kotlin.math.pow

/**
 * The colour modes found on electronic video magnifiers (CCTV / desktop and handheld magnifiers):
 * natural colours, greyscale, and two-colour high-contrast modes where dark ink becomes the
 * "text" colour and light paper becomes the "background" colour.
 *
 * Two-colour modes use pure colours (each channel 0 or 255) so that a strong contrast gain
 * saturates cleanly to exactly two colours without tinting.
 */
object ColorModes {
    const val NATURAL = "natural"
    const val VIVID = "vivid"
    const val GRAY = "gray"
    const val BLACK_ON_WHITE = "black_on_white"
    const val WHITE_ON_BLACK = "white_on_black"
    const val YELLOW_ON_BLACK = "yellow_on_black"
    const val BLACK_ON_YELLOW = "black_on_yellow"
    const val YELLOW_ON_BLUE = "yellow_on_blue"
    const val WHITE_ON_BLUE = "white_on_blue"
    const val GREEN_ON_BLACK = "green_on_black"

    enum class Kind { NATURAL, GRAY, TWO_COLOR }

    class Mode(val id: String, val label: String, val kind: Kind, val text: Int = 0, val background: Int = 0, val baseGain: Float)

    private const val YELLOW = 0xFFFFFF00.toInt()
    private const val BLUE = 0xFF0000FF.toInt()
    private const val GREEN = 0xFF00FF00.toInt()

    val ALL = listOf(
        Mode(NATURAL, "Colori naturali", Kind.NATURAL, baseGain = 1f),
        Mode(VIVID, "Colori contrastati", Kind.NATURAL, baseGain = 1.6f),
        Mode(GRAY, "Grigi contrastati", Kind.GRAY, baseGain = 1.6f),
        Mode(BLACK_ON_WHITE, "Nero su bianco", Kind.TWO_COLOR, Color.BLACK, Color.WHITE, 3f),
        Mode(WHITE_ON_BLACK, "Bianco su nero", Kind.TWO_COLOR, Color.WHITE, Color.BLACK, 3f),
        Mode(YELLOW_ON_BLACK, "Giallo su nero", Kind.TWO_COLOR, YELLOW, Color.BLACK, 3f),
        Mode(BLACK_ON_YELLOW, "Nero su giallo", Kind.TWO_COLOR, Color.BLACK, YELLOW, 3f),
        Mode(YELLOW_ON_BLUE, "Giallo su blu", Kind.TWO_COLOR, YELLOW, BLUE, 3f),
        Mode(WHITE_ON_BLUE, "Bianco su blu", Kind.TWO_COLOR, Color.WHITE, BLUE, 3f),
        Mode(GREEN_ON_BLACK, "Verde su nero", Kind.TWO_COLOR, GREEN, Color.BLACK, 3f),
    )

    val DEFAULT_CYCLE = setOf(NATURAL, BLACK_ON_WHITE, WHITE_ON_BLACK, YELLOW_ON_BLACK, BLACK_ON_YELLOW)

    val READER_SCHEMES = ALL.filter { it.kind == Kind.TWO_COLOR }

    fun byId(id: String): Mode = ALL.firstOrNull { it.id == id } ?: ALL[0]

    /** Contrast level 0..10 (5 = default) to a gain multiplier 0.25x..4x. */
    private fun gainFactor(level: Int): Float = 2f.pow((level - 5) / 2.5f)

    // Two-colour modes split ink from paper at [threshold] (0..255). The magnifier passes the
    // midpoint between the darkest and brightest parts of the page, measured live (auto contrast).
    /**
     * Builds the filter for a mode, or null when the image should be shown untouched
     * (saves GPU work in the most common case).
     */
    fun filter(mode: Mode, contrast: Int, brightness: Int, threshold: Float = 128f): ColorMatrixColorFilter? {
        val g = mode.baseGain * gainFactor(contrast)
        val b = brightness * 16f // -80..80 on the 0..255 scale
        val wr = 0.299f; val wg = 0.587f; val wb = 0.114f
        val m: FloatArray = when (mode.kind) {
            Kind.NATURAL -> {
                if (g == 1f && b == 0f) return null
                val o = 128f * (1 - g) + b
                floatArrayOf(
                    g, 0f, 0f, 0f, o,
                    0f, g, 0f, 0f, o,
                    0f, 0f, g, 0f, o,
                    0f, 0f, 0f, 1f, 0f,
                )
            }
            Kind.GRAY -> {
                val o = 128f * (1 - g) + b
                floatArrayOf(
                    g * wr, g * wg, g * wb, 0f, o,
                    g * wr, g * wg, g * wb, 0f, o,
                    g * wr, g * wg, g * wb, 0f, o,
                    0f, 0f, 0f, 1f, 0f,
                )
            }
            Kind.TWO_COLOR -> {
                // E = g * (L - t) + 128, then out = text + E/255 * (background - text).
                // Brighter setting lowers the threshold, so more of the image becomes "paper".
                val t = threshold - b
                val rows = FloatArray(20)
                val fg = intArrayOf(Color.red(mode.text), Color.green(mode.text), Color.blue(mode.text))
                val bg = intArrayOf(Color.red(mode.background), Color.green(mode.background), Color.blue(mode.background))
                for (c in 0..2) {
                    val k = (bg[c] - fg[c]) / 255f
                    rows[c * 5 + 0] = k * g * wr
                    rows[c * 5 + 1] = k * g * wg
                    rows[c * 5 + 2] = k * g * wb
                    rows[c * 5 + 4] = fg[c] + k * (128f - g * t)
                }
                rows[18] = 1f
                rows
            }
        }
        return ColorMatrixColorFilter(ColorMatrix(m))
    }
}
