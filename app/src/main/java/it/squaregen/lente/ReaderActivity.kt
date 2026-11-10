package it.squaregen.lente

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.Locale

/**
 * Large-print reader: recognises the text in a photo (on the phone, no internet) and shows it
 * re-flowed in big type, with the low-vision colour schemes, generous line spacing
 * (WCAG 1.4.12), the Atkinson Hyperlegible font and optional read-aloud.
 */
class ReaderActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    companion object {
        const val EXTRA_IMAGE = "image"
    }

    private lateinit var prefs: Prefs
    private lateinit var scroll: ScrollView
    private lateinit var textView: TextView
    private lateinit var status: TextView
    private lateinit var bar: LinearLayout
    private lateinit var btnSpeak: TextView
    private lateinit var guard: ScreenGuard

    private var paragraphs: List<String> = emptyList()
    private var fullText = ""
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speaking = false
    private var currentParagraph = -1
    private val main = Handler(Looper.getMainLooper())
    private var lastTouch = SystemClock.elapsedRealtime()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = prefs.orientationFlag
        buildUi()
        applyScheme()
        tts = TextToSpeech(this, this)
        recognize()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        guard.start()
        main.postDelayed(idleCheck, 30_000)
    }

    override fun onPause() {
        super.onPause()
        guard.stop()
        main.removeCallbacks(idleCheck)
        stopSpeaking()
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.shutdown()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        lastTouch = SystemClock.elapsedRealtime()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        guard.poke()
    }

    private val idleCheck = object : Runnable {
        override fun run() {
            val min = prefs.screenOffMin
            if (min > 0 && !speaking && SystemClock.elapsedRealtime() - lastTouch > min * 60_000L) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            main.postDelayed(this, 30_000)
        }
    }

    private fun hideSystemBars() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    // ---------------------------------------------------------------- UI

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        val dp = { v: Int -> Ui.dp(this, v) }
        val root = FrameLayout(this)

        scroll = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        textView = TextView(this).apply {
            typeface = Ui.font(this@ReaderActivity, bold = true)
            setLineSpacing(0f, 1.5f)
            letterSpacing = 0.04f
            setPadding(dp(20), dp(40), dp(20), dp(220))
            setTextIsSelectable(false)
            isFocusable = true
        }
        scroll.addView(textView)
        root.addView(scroll)

        status = TextView(this).apply {
            text = "Sto leggendo il testo…"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            typeface = Ui.font(this@ReaderActivity)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        }
        root.addView(status)

        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xE6000000.toInt())
            setPadding(dp(6), dp(6), dp(6), dp(10))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        }
        fun barButton(label: String, icon: Int, desc: String, action: () -> Unit) = TextView(this).apply {
            text = label
            contentDescription = desc
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Ui.font(this@ReaderActivity)
            setTextColor(ContextCompat.getColor(this@ReaderActivity, R.color.fg))
            background = ContextCompat.getDrawable(this@ReaderActivity, R.drawable.big_button_bg)
            setCompoundDrawablesWithIntrinsicBounds(0, icon, 0, 0)
            gravity = Gravity.CENTER
            minHeight = dp(80)
            maxLines = 1
            setPadding(dp(2), dp(8), dp(2), dp(6))
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
            setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); action() }
        }
        bar.addView(barButton("Più", R.drawable.ic_plus, "Testo più grande") { changeSize(1.2f) })
        bar.addView(barButton("Meno", R.drawable.ic_minus, "Testo più piccolo") { changeSize(1 / 1.2f) })
        bar.addView(barButton("Colori", R.drawable.ic_contrast, "Cambia i colori") { nextScheme() })
        btnSpeak = barButton("Ascolta", R.drawable.ic_speak, "Leggi ad alta voce") { toggleSpeak() }
        bar.addView(btnSpeak)
        bar.addView(barButton("Chiudi", R.drawable.ic_camera, "Chiudi e torna alla lente") { finish() })
        root.addView(bar)

        setContentView(root)
        guard = ScreenGuard(listOf(bar), listOf(bar), resources.displayMetrics.density)
        guard.autoHideMs = prefs.autoHideSec * 1000L

        // Pinch anywhere to change the text size; a tap brings the buttons back.
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private var acc = 1f
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean { acc = 1f; return true }
            override fun onScale(d: ScaleGestureDetector): Boolean {
                acc *= d.scaleFactor
                if (acc > 1.12f || acc < 0.89f) { changeSize(acc, announce = false); acc = 1f }
                return true
            }
        })
        var downAt = 0L
        var moved = false
        var downY = 0f
        scroll.setOnTouchListener { _, e ->
            scale.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downAt = e.eventTime; moved = false; downY = e.y }
                MotionEvent.ACTION_POINTER_DOWN -> moved = true
                MotionEvent.ACTION_MOVE -> if (kotlin.math.abs(e.y - downY) > dp(20)) moved = true
                MotionEvent.ACTION_UP -> if (!moved && e.eventTime - downAt < 400) guard.show()
            }
            e.pointerCount > 1 // let the ScrollView scroll with one finger
        }
    }

    private fun applyScheme() {
        val s = ColorModes.byId(prefs.readerScheme).takeIf { it.kind == ColorModes.Kind.TWO_COLOR }
            ?: ColorModes.byId(ColorModes.YELLOW_ON_BLACK)
        scroll.setBackgroundColor(s.background)
        window.decorView.setBackgroundColor(s.background)
        textView.setTextColor(s.text)
        status.setTextColor(s.text)
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.readerTextSp.toFloat())
        renderText()
    }

    private fun nextScheme() {
        val list = ColorModes.READER_SCHEMES
        val i = list.indexOfFirst { it.id == prefs.readerScheme }
        val next = list[(i + 1) % list.size]
        prefs.readerScheme = next.id
        applyScheme()
        textView.announceForAccessibility(next.label)
    }

    private fun changeSize(factor: Float, announce: Boolean = true) {
        prefs.readerTextSp = (prefs.readerTextSp * factor).toInt().let {
            if (factor > 1 && it == prefs.readerTextSp) it + 1 else it
        }
        // Keep the same part of the text on screen while resizing.
        val layout = textView.layout
        val offset = layout?.getOffsetForHorizontal(layout.getLineForVertical(scroll.scrollY), 0f) ?: 0
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.readerTextSp.toFloat())
        textView.post {
            textView.layout?.let { scroll.scrollTo(0, it.getLineTop(it.getLineForOffset(offset))) }
        }
        if (announce) textView.announceForAccessibility("Dimensione ${prefs.readerTextSp}")
    }

    // ---------------------------------------------------------------- OCR

    private fun recognize() {
        // Either a photo taken by the magnifier, or an image shared from another app (Gallery, WhatsApp...).
        val uri: Uri? = if (intent.action == android.content.Intent.ACTION_SEND) {
            androidx.core.content.IntentCompat.getParcelableExtra(intent, android.content.Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            // Only our own photos (in the cache folder) can be opened by path.
            intent.getStringExtra(EXTRA_IMAGE)?.let { File(it) }
                ?.takeIf { it.canonicalPath.startsWith(cacheDir.canonicalPath + File.separator) }
                ?.let { Uri.fromFile(it) }
        }
        if (uri == null) { finish(); return }
        val input = try {
            InputImage.fromFilePath(this, uri)
        } catch (e: Exception) {
            showNoText(); return
        }
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(input)
            .addOnSuccessListener { result ->
                paragraphs = buildParagraphs(result)
                if (paragraphs.isEmpty()) showNoText() else showText()
            }
            .addOnFailureListener { showNoText() }
            .addOnCompleteListener { recognizer.close() }
    }

    /** Re-flows the recognised lines into paragraphs, joining words split with a hyphen. */
    private fun buildParagraphs(text: Text): List<String> = text.textBlocks.mapNotNull { block ->
        val sb = StringBuilder()
        for (line in block.lines) {
            val t = line.text.trim()
            if (t.isEmpty()) continue
            when {
                sb.isEmpty() -> sb.append(t)
                sb.endsWith("-") && t.first().isLowerCase() -> { sb.setLength(sb.length - 1); sb.append(t) }
                else -> sb.append(' ').append(t)
            }
        }
        sb.toString().trim().takeIf { it.count { c -> c.isLetterOrDigit() } >= 2 }
    }

    private fun showText() {
        status.visibility = View.GONE
        fullText = paragraphs.joinToString("\n\n")
        renderText()
        textView.announceForAccessibility("Testo pronto. ${paragraphs.size} paragrafi.")
    }

    private fun showNoText() {
        status.visibility = View.VISIBLE
        status.text = "Non ho trovato testo.\n\nInquadra meglio il foglio, accendi la luce e riprova."
        btnSpeak.visibility = View.GONE
    }

    /** Draws the text, with the paragraph being read aloud shown in inverted colours. */
    private fun renderText() {
        if (fullText.isEmpty()) return
        val s = ColorModes.byId(prefs.readerScheme)
        val span = SpannableString(fullText)
        if (currentParagraph in paragraphs.indices) {
            val start = paragraphStart(currentParagraph)
            val end = start + paragraphs[currentParagraph].length
            span.setSpan(BackgroundColorSpan(s.text), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            span.setSpan(ForegroundColorSpan(if (s.background == 0) Color.BLACK else s.background), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        textView.text = span
    }

    private fun paragraphStart(i: Int): Int = paragraphs.take(i).sumOf { it.length + 2 }

    // ---------------------------------------------------------------- read aloud

    override fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) return
        val r = t.setLanguage(Locale.ITALIAN)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) t.setLanguage(Locale.getDefault())
        t.setSpeechRate(prefs.speechRate)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) {
                runOnUiThread { highlight(id.toIntOrNull() ?: -1) }
            }
            override fun onDone(id: String) {
                if (id.toIntOrNull() == paragraphs.size - 1) runOnUiThread { speakingStopped() }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String) { runOnUiThread { speakingStopped() } }
        })
        ttsReady = true
    }

    private fun toggleSpeak() {
        if (speaking) { stopSpeaking(); return }
        val t = tts
        if (!ttsReady || t == null || paragraphs.isEmpty()) {
            textView.announceForAccessibility("Voce non disponibile")
            return
        }
        // Start from the paragraph at the top of the screen.
        val layout = textView.layout
        var from = 0
        if (layout != null) {
            val offset = layout.getOffsetForHorizontal(layout.getLineForVertical(scroll.scrollY), 0f)
            for (i in paragraphs.indices) if (paragraphStart(i) <= offset) from = i
        }
        t.setSpeechRate(prefs.speechRate)
        for (i in from until paragraphs.size) {
            t.speak(paragraphs[i], if (i == from) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, i.toString())
        }
        speaking = true
        btnSpeak.text = "Stop"
        btnSpeak.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_pause, 0, 0)
        btnSpeak.isActivated = true
    }

    private fun stopSpeaking() {
        tts?.stop()
        speakingStopped()
    }

    private fun speakingStopped() {
        speaking = false
        currentParagraph = -1
        renderText()
        btnSpeak.text = "Ascolta"
        btnSpeak.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_speak, 0, 0)
        btnSpeak.isActivated = false
    }

    private fun highlight(i: Int) {
        if (!speaking) return
        currentParagraph = i
        renderText()
        textView.post {
            val layout = textView.layout ?: return@post
            if (i !in paragraphs.indices) return@post
            val line = layout.getLineForOffset(paragraphStart(i))
            val y = layout.getLineTop(line) + textView.paddingTop - Ui.dp(this, 24)
            scroll.smoothScrollTo(0, maxOf(0, y))
        }
    }
}
