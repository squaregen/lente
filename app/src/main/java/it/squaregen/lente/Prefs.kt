package it.squaregen.lente

import android.content.Context

/** All user settings, stored in SharedPreferences. Defaults follow common low-vision magnifier practice. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("lente", Context.MODE_PRIVATE)

    var zoom: Float
        get() = sp.getFloat("zoom", 2f)
        set(v) = sp.edit().putFloat("zoom", v).apply()

    var colorMode: String
        get() = sp.getString("colorMode", ColorModes.NATURAL) ?: ColorModes.NATURAL
        set(v) = sp.edit().putString("colorMode", v).apply()

    /** Modes the "Colori" button cycles through. */
    var enabledModes: Set<String>
        get() = sp.getStringSet("enabledModes", null) ?: ColorModes.DEFAULT_CYCLE
        set(v) = sp.edit().putStringSet("enabledModes", v).apply()

    /** 0..10, 5 = mode default. */
    var contrast: Int
        get() = sp.getInt("contrast", 5)
        set(v) = sp.edit().putInt("contrast", v.coerceIn(0, 10)).apply()

    /** -5..5, 0 = neutral. */
    var brightness: Int
        get() = sp.getInt("brightness", 0)
        set(v) = sp.edit().putInt("brightness", v.coerceIn(-5, 5)).apply()

    /** Seconds before the controls hide themselves; 0 = never. */
    var autoHideSec: Int
        get() = sp.getInt("autoHideSec", 8)
        set(v) = sp.edit().putInt("autoHideSec", v).apply()

    /** "landscape" (default: how she usually holds it), "portrait" or "auto". */
    var orientation: String
        get() = sp.getString("orientation", "landscape") ?: "landscape"
        set(v) = sp.edit().putString("orientation", v).apply()

    /** Landscape follows the sensor only between the two landscape sides, so picking the phone up never flips it to portrait. */
    val orientationFlag: Int
        get() = when (orientation) {
            "portrait" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "auto" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

    /** Highest magnification offered (camera zoom, then enlarging the picture itself). */
    var maxZoom: Int
        get() = sp.getInt("maxZoom", 30)
        set(v) = sp.edit().putInt("maxZoom", v).apply()

    /** Before reading, guide the user to frame the whole text (voice and big messages). */
    var guidedCapture: Boolean
        get() = sp.getBoolean("guidedCapture", true)
        set(v) = sp.edit().putBoolean("guidedCapture", v).apply()

    var voiceHints: Boolean
        // Off by default: she uses the app in public and does not want it to be noticed.
        get() = sp.getBoolean("voiceHints", false)
        set(v) = sp.edit().putBoolean("voiceHints", v).apply()

    /** Reader: "page", "line", "words" or "photo". */
    var readerMode: String
        get() = sp.getString("readerMode", "page") ?: "page"
        set(v) = sp.edit().putString("readerMode", v).apply()

    /** Words per minute for the moving reading modes. */
    var readingWpm: Int
        get() = sp.getInt("readingWpm", 90)
        set(v) = sp.edit().putInt("readingWpm", v.coerceIn(20, 400)).apply()

    var wordsAtOnce: Int
        get() = sp.getInt("wordsAtOnce", 2)
        set(v) = sp.edit().putInt("wordsAtOnce", v.coerceIn(1, 5)).apply()

    /** In the moving modes, read aloud and follow the voice instead of a fixed speed. */
    var speakWhileMoving: Boolean
        get() = sp.getBoolean("speakWhileMoving", false)
        set(v) = sp.edit().putBoolean("speakWhileMoving", v).apply()

    var maxBrightness: Boolean
        get() = sp.getBoolean("maxBrightness", false)
        set(v) = sp.edit().putBoolean("maxBrightness", v).apply()

    var volumeZoom: Boolean
        get() = sp.getBoolean("volumeZoom", true)
        set(v) = sp.edit().putBoolean("volumeZoom", v).apply()

    var textDetect: Boolean
        get() = sp.getBoolean("textDetect", true)
        set(v) = sp.edit().putBoolean("textDetect", v).apply()

    var smartFocus: Boolean
        get() = sp.getBoolean("smartFocus", true)
        set(v) = sp.edit().putBoolean("smartFocus", v).apply()

    /** Minutes without use before the screen may switch off; 0 = never. */
    var screenOffMin: Int
        get() = sp.getInt("screenOffMin", 10)
        set(v) = sp.edit().putInt("screenOffMin", v).apply()

    var lockOnStart: Boolean
        get() = sp.getBoolean("lockOnStart", false)
        set(v) = sp.edit().putBoolean("lockOnStart", v).apply()

    var readingMask: Boolean
        get() = sp.getBoolean("readingMask", false)
        set(v) = sp.edit().putBoolean("readingMask", v).apply()

    var torch: Boolean
        get() = sp.getBoolean("torch", false)
        set(v) = sp.edit().putBoolean("torch", v).apply()

    var readerTextSp: Int
        get() = sp.getInt("readerTextSp", 40)
        set(v) = sp.edit().putInt("readerTextSp", v.coerceIn(18, 260)).apply()

    var readerScheme: String
        get() = sp.getString("readerScheme", ColorModes.YELLOW_ON_BLACK) ?: ColorModes.YELLOW_ON_BLACK
        set(v) = sp.edit().putString("readerScheme", v).apply()

    var speechRate: Float
        get() = sp.getFloat("speechRate", 1f)
        set(v) = sp.edit().putFloat("speechRate", v).apply()
}
