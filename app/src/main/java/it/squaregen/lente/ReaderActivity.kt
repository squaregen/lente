package it.squaregen.lente

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
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
import android.view.Choreographer
import android.view.GestureDetector
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
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Large-print reader. Recognises the text of a photo on the phone (no internet) and offers the
 * reading views of desktop OCR magnifiers:
 *  - Pagina: the text re-flowed in big type (Atkinson Hyperlegible, line spacing 1.5), the word
 *    being read aloud is highlighted;
 *  - Riga: one very large line that slides right to left, so the eyes stay still;
 *  - Parole: a few words at a time, as big as the screen allows;
 *  - Foto: the original photo, magnified, moving by itself from word group to word group along
 *    each line and then back to the start of the next line (the movement low-vision readers
 *    otherwise make by hand with a magnifier).
 * All views share the same reading position, can be read aloud and use the same colours.
 */
class ReaderActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    companion object {
        const val EXTRA_IMAGE = "image"
        private val MODES = listOf("page", "line", "words", "photo")
        private val MODE_NAMES = mapOf("page" to "Pagina", "line" to "Riga", "words" to "Parole", "photo" to "Foto")
        private val SPEEDS = listOf(50 to "molto lenta", 70 to "lenta", 90 to "media", 120 to "veloce", 160 to "molto veloce")
    }

    // ------------------------------------------------------------ text model

    /** One word, its place in [fullText] and its paragraph. */
    private class Tok(var text: String, val para: Int) { var start = 0 }
    /** One recognised word box in the photo, pointing to its word. */
    private class El(val box: Rect, val tok: Int)
    /** A group of words on one line of the photo, shown together in the "Foto" view. */
    private class Group(val rect: RectF, val first: Int, val last: Int, val line: Int, val angle: Float)

    private val toks = ArrayList<Tok>()
    private val lines = ArrayList<List<El>>()
    /** Slant of each line in the photo, in degrees (from the recogniser). */
    private val lineAngles = ArrayList<Float>()
    private var fullText = ""
    private var starts = IntArray(0)
    private var paraStart = IntArray(0)
    private var paraEnd = IntArray(0)
    private var photo: Bitmap? = null
    private var photoThreshold = 128f

    // ------------------------------------------------------------ state

    private lateinit var prefs: Prefs
    private lateinit var root: FrameLayout
    private lateinit var scroll: ScrollView
    private lateinit var textView: TextView
    private lateinit var ticker: TickerView
    private lateinit var wordsView: TextView
    private lateinit var photoView: ZoomImageView
    private lateinit var status: TextView
    private lateinit var label: TextView
    private lateinit var bar: LinearLayout
    private lateinit var btnPlus: TextView
    private lateinit var btnMinus: TextView
    private lateinit var btnThird: TextView
    private lateinit var btnMode: TextView
    private lateinit var btnPlay: TextView
    private lateinit var guard: ScreenGuard

    private var mode = "page"
    private var pos = 0
    private var marker = false
    private var playing = false
    private var groupStart = 0
    private var groups: List<Group> = emptyList()
    private var groupIndex = -1
    private var photoScale = 0f
    private var rescalePending = false
    private var tickerPos = 0f
    private var lastFrame = 0L

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var lastUtterance = ""
    private val main = Handler(Looper.getMainLooper())
    private var lastTouch = SystemClock.elapsedRealtime()

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(Ui.standardScale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = prefs.orientationFlag
        mode = prefs.readerMode.takeIf { it in MODES } ?: "page"
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
        stopPlay()
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
            if (min > 0 && !playing && SystemClock.elapsedRealtime() - lastTouch > min * 60_000L) {
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

    private fun dp(v: Int) = Ui.dp(this, v)
    private fun spToPx(sp: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)

    // ------------------------------------------------------------ UI

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        root = FrameLayout(this)
        val full = { FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }

        scroll = ScrollView(this).apply { isFillViewport = true; layoutParams = full() }
        textView = TextView(this).apply {
            typeface = Ui.font(this@ReaderActivity, bold = true)
            setLineSpacing(0f, 1.5f)
            letterSpacing = 0.04f
            setPadding(dp(20), dp(40), dp(20), dp(220))
            isFocusable = true
        }
        scroll.addView(textView)
        root.addView(scroll)

        ticker = TickerView(this).apply { layoutParams = full(); visibility = View.GONE }
        root.addView(ticker)

        wordsView = TextView(this).apply {
            layoutParams = full()
            gravity = Gravity.CENTER
            typeface = Ui.font(this@ReaderActivity, bold = true)
            maxLines = 2
            setPadding(dp(16), dp(16), dp(16), dp(120))
            setAutoSizeTextTypeUniformWithConfiguration(24, 400, 2, TypedValue.COMPLEX_UNIT_SP)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
        }
        root.addView(wordsView)

        photoView = ZoomImageView(this).apply { layoutParams = full(); visibility = View.GONE }
        root.addView(photoView)

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

        label = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            typeface = Ui.font(this@ReaderActivity)
            setTextColor(ContextCompat.getColor(this@ReaderActivity, R.color.fg))
            background = ContextCompat.getDrawable(this@ReaderActivity, R.drawable.chip_bg)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(10), dp(24), dp(10))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(24) }
        }
        root.addView(label)

        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xE6000000.toInt())
            setPadding(dp(6), dp(6), dp(6), dp(10))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        }
        fun barButton(icon: Int, action: () -> Unit) = TextView(this).apply {
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
        btnPlus = barButton(R.drawable.ic_plus) { changeSize(true) }
        btnMinus = barButton(R.drawable.ic_minus) { changeSize(false) }
        btnThird = barButton(R.drawable.ic_contrast) { if (mode == "page") nextScheme() else nextSpeed() }
        btnMode = barButton(R.drawable.ic_text) { nextMode() }
        btnPlay = barButton(R.drawable.ic_speak) { togglePlay() }
        val btnClose = barButton(R.drawable.ic_camera) { finish() }.apply { text = "Chiudi"; contentDescription = "Chiudi e torna alla lente" }
        listOf(btnPlus, btnMinus, btnThird, btnMode, btnPlay, btnClose).forEach { bar.addView(it) }
        root.addView(bar)

        setContentView(root)
        guard = ScreenGuard(listOf(bar), listOf(bar, label), resources.displayMetrics.density)
        guard.autoHideMs = prefs.autoHideSec * 1000L
        updateBar()

        // Page view: pinch to resize, tap a word to start reading from there.
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private var acc = 1f
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean { acc = 1f; return true }
            override fun onScale(d: ScaleGestureDetector): Boolean {
                acc *= d.scaleFactor
                if (acc > 1.12f || acc < 0.89f) { resizeText(acc); acc = 1f }
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
                MotionEvent.ACTION_MOVE -> if (abs(e.y - downY) > dp(20)) moved = true
                MotionEvent.ACTION_UP -> if (!moved && e.eventTime - downAt < 400) {
                    if (!guard.visible) guard.show() else tapWord(e.x - textView.left, e.y + scroll.scrollY - textView.top)
                }
            }
            e.pointerCount > 1
        }

        ticker.onTap = { tapMoving() }
        ticker.onScrub = { p -> tickerPos = p; pos = p.toInt() }

        val swipe = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean { tapMoving(); return true }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vx) < abs(vy)) return false
                if (vx < 0) stepWords(+1) else stepWords(-1)
                return true
            }
        })
        wordsView.setOnTouchListener { _, e -> swipe.onTouchEvent(e) }

        photoView.onSingleTap = { tapMoving() }
        // Pinching or dragging the photo pauses; the new magnification is kept for the next groups.
        photoView.onUserGesture = { if (playing) stopPlay(); rescalePending = true }
    }

    private fun showLabel(text: String, ms: Long = 1500) {
        label.text = text
        label.alpha = 1f
        label.visibility = View.VISIBLE
        main.removeCallbacks(hideLabel)
        main.postDelayed(hideLabel, ms)
    }

    private val hideLabel = Runnable { label.visibility = View.GONE }

    private fun scheme(): ColorModes.Mode = ColorModes.byId(prefs.readerScheme).takeIf { it.kind == ColorModes.Kind.TWO_COLOR }
        ?: ColorModes.byId(ColorModes.YELLOW_ON_BLACK)

    private fun applyScheme() {
        val s = scheme()
        root.setBackgroundColor(s.background)
        scroll.setBackgroundColor(s.background)
        window.decorView.setBackgroundColor(s.background)
        textView.setTextColor(s.text)
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.readerTextSp.toFloat())
        status.setTextColor(s.text)
        wordsView.setTextColor(s.text)
        wordsView.setBackgroundColor(s.background)
        photoView.setBackgroundColor(s.background)
        photoView.colorFilter = ColorModes.filter(s, 5, 0, photoThreshold)
        ticker.setStyle(spToPx(prefs.readerTextSp * 1.6f), Ui.font(this, bold = true), s.text, s.background)
        render()
    }

    private fun updateBar() {
        val moving = mode != "page"
        btnPlus.text = "Più"; btnPlus.contentDescription = "Più grande"
        btnMinus.text = "Meno"; btnMinus.contentDescription = "Più piccolo"
        btnThird.text = if (moving) "Velocità" else "Colori"
        btnThird.contentDescription = if (moving) "Cambia la velocità" else "Cambia i colori"
        btnMode.text = MODE_NAMES[mode]
        btnMode.contentDescription = "Modo di lettura: ${MODE_NAMES[mode]}. Tocca per cambiarlo"
        val playLabel = if (playing) "Pausa" else if (moving) "Avvia" else "Ascolta"
        btnPlay.text = playLabel
        btnPlay.contentDescription = if (playing) "Ferma" else if (moving) "Avvia la lettura" else "Leggi ad alta voce"
        btnPlay.setCompoundDrawablesWithIntrinsicBounds(0,
            if (playing) R.drawable.ic_pause else if (moving) R.drawable.ic_play else R.drawable.ic_speak, 0, 0)
        btnPlay.isActivated = playing
    }

    // ------------------------------------------------------------ modes

    private fun nextMode() {
        val next = MODES[(MODES.indexOf(mode) + 1) % MODES.size]
        setMode(next)
        showLabel(MODE_NAMES[next] ?: "")
        textView.announceForAccessibility("Modo ${MODE_NAMES[next]}")
    }

    private fun setMode(m: String) {
        stopPlay()
        mode = m
        prefs.readerMode = m
        if (toks.isEmpty()) { updateBar(); return }
        scroll.visibility = if (m == "page") View.VISIBLE else View.GONE
        ticker.visibility = if (m == "line") View.VISIBLE else View.GONE
        wordsView.visibility = if (m == "words") View.VISIBLE else View.GONE
        photoView.visibility = if (m == "photo") View.VISIBLE else View.GONE
        groupStart = pos
        tickerPos = pos.toFloat()
        groupIndex = -1
        updateBar()
        render()
    }

    private fun render() {
        if (toks.isEmpty()) return
        when (mode) {
            "page" -> renderPage()
            "line" -> ticker.position = tickerPos
            "words" -> renderWords()
            "photo" -> photoView.post { showGroupFor(pos, animate = true) }
        }
    }

    private fun changeSize(bigger: Boolean) {
        when (mode) {
            "words" -> {
                // Fewer words at a time = bigger letters.
                prefs.wordsAtOnce = prefs.wordsAtOnce + if (bigger) -1 else 1
                renderWords()
                showLabel(if (prefs.wordsAtOnce == 1) "1 parola alla volta" else "${prefs.wordsAtOnce} parole alla volta")
            }
            "photo" -> {
                photoScale = (if (photoScale > 0) photoScale else defaultPhotoScale()) * if (bigger) 1.25f else 0.8f
                groups = emptyList()
                groupIndex = -1
                showGroupFor(pos, animate = true)
            }
            else -> resizeText(if (bigger) 1.2f else 1 / 1.2f)
        }
    }

    private fun resizeText(factor: Float) {
        val old = prefs.readerTextSp
        prefs.readerTextSp = (old * factor).toInt().let { if (factor > 1 && it == old) it + 1 else it }
        if (mode == "line") {
            ticker.setStyle(spToPx(prefs.readerTextSp * 1.6f), Ui.font(this, bold = true), scheme().text, scheme().background)
        } else {
            val layout = textView.layout
            val offset = layout?.getOffsetForHorizontal(layout.getLineForVertical(scroll.scrollY), 0f) ?: 0
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.readerTextSp.toFloat())
            textView.post { textView.layout?.let { scroll.scrollTo(0, it.getLineTop(it.getLineForOffset(offset))) } }
        }
        textView.announceForAccessibility("Dimensione ${prefs.readerTextSp}")
    }

    private fun nextScheme() {
        val list = ColorModes.READER_SCHEMES
        val i = list.indexOfFirst { it.id == prefs.readerScheme }
        val next = list[(i + 1) % list.size]
        prefs.readerScheme = next.id
        applyScheme()
        showLabel(next.label)
    }

    private fun nextSpeed() {
        if (prefs.speakWhileMoving) {
            val rates = listOf(0.7f to "lenta", 0.85f to "un po' lenta", 1f to "normale", 1.25f to "veloce", 1.5f to "molto veloce")
            val i = rates.indexOfFirst { it.first >= prefs.speechRate - 0.01f }.let { if (it < 0) 0 else it }
            val next = rates[(i + 1) % rates.size]
            prefs.speechRate = next.first
            showLabel("Voce ${next.second}")
            if (playing) { stopPlay(); startPlay() }
        } else {
            val i = SPEEDS.indexOfFirst { it.first >= prefs.readingWpm }.let { if (it < 0) 0 else it }
            val next = SPEEDS[(i + 1) % SPEEDS.size]
            prefs.readingWpm = next.first
            showLabel("Velocità ${next.second}")
        }
    }

    // ------------------------------------------------------------ page view

    private fun renderPage() {
        if (fullText.isEmpty()) return
        val s = scheme()
        val span = SpannableString(fullText)
        if ((playing || marker) && pos in toks.indices) {
            val t = toks[pos]
            span.setSpan(BackgroundColorSpan(s.text), t.start, t.start + t.text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            span.setSpan(ForegroundColorSpan(if (s.background == 0) Color.BLACK else s.background),
                t.start, t.start + t.text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        textView.text = span
        if (playing) keepWordVisible()
    }

    private fun keepWordVisible() {
        textView.post {
            val layout = textView.layout ?: return@post
            if (pos !in toks.indices) return@post
            val y = layout.getLineTop(layout.getLineForOffset(toks[pos].start)) + textView.paddingTop
            val h = scroll.height
            if (y < scroll.scrollY + h * 0.1f || y > scroll.scrollY + h * 0.55f) scroll.smoothScrollTo(0, maxOf(0, y - (h * 0.25f).toInt()))
        }
    }

    private fun tapWord(x: Float, y: Float) {
        if (toks.isEmpty()) return
        val offset = textView.getOffsetForPosition(x, y)
        pos = tokenAt(offset)
        marker = true
        if (playing) { stopPlay(); startPlay() } else renderPage()
    }

    // ------------------------------------------------------------ words view

    private fun groupSize(from: Int): Int {
        if (from !in toks.indices) return 0
        var n = 0
        while (from + n < toks.size && n < prefs.wordsAtOnce && toks[from + n].para == toks[from].para) n++
        return n
    }

    private fun renderWords() {
        if (toks.isEmpty()) return
        groupStart = groupStart.coerceIn(0, toks.size - 1)
        val n = groupSize(groupStart)
        wordsView.text = (groupStart until groupStart + n).joinToString(" ") { toks[it].text }
    }

    private fun stepWords(dir: Int) {
        if (toks.isEmpty()) return
        if (dir > 0) groupStart += groupSize(groupStart)
        else {
            var back = groupStart - 1
            var n = 0
            while (back > 0 && n < prefs.wordsAtOnce - 1 && toks[back - 1].para == toks[groupStart - 1].para) { back--; n++ }
            groupStart = maxOf(0, back)
        }
        groupStart = groupStart.coerceIn(0, toks.size - 1)
        pos = groupStart
        renderWords()
    }

    // ------------------------------------------------------------ photo view

    private fun medianLineHeight(): Float {
        val hs = lines.flatMap { l -> l.map { it.box.height() } }.sorted()
        return if (hs.isEmpty()) 40f else hs[hs.size / 2].toFloat()
    }

    /** Magnification that makes the photo's letters about as tall as the reader's text size. */
    private fun defaultPhotoScale(): Float = spToPx(prefs.readerTextSp * 1.3f) / medianLineHeight()

    private fun buildGroups() {
        if (photoView.width == 0) return
        if (photoScale <= 0f) photoScale = defaultPhotoScale()
        val visibleW = photoView.width / photoScale * 0.8f
        val out = ArrayList<Group>()
        for ((li, line) in lines.withIndex()) {
            // Work in the line's own levelled coordinates, so slanted lines group correctly.
            val angle = lineAngles.getOrElse(li) { 0f }.let { if (abs(it) > 30f) 0f else it }
            val level = Matrix().apply { setRotate(-angle) }
            var cur: RectF? = null
            var first = 0
            var last = 0
            for (el in line) {
                val box = RectF(el.box).also { level.mapRect(it) }
                if (cur != null && box.right - cur.left > visibleW) {
                    out.add(Group(cur, first, last, li, angle))
                    cur = null
                }
                if (cur == null) { cur = box; first = el.tok } else cur.union(box)
                last = el.tok
            }
            if (cur != null) out.add(Group(cur, first, last, li, angle))
        }
        groups = out
    }

    private fun showGroupFor(token: Int, animate: Boolean) {
        if (photo == null || lines.isEmpty()) return
        if (rescalePending) {
            rescalePending = false
            photoScale = photoView.currentScale()
            groups = emptyList()
            groupIndex = -1
        }
        if (groups.isEmpty()) buildGroups()
        if (groups.isEmpty()) return
        val gi = groups.indexOfFirst { it.last >= token }.let { if (it < 0) groups.size - 1 else it }
        if (gi == groupIndex && !animate) return
        val newLine = groupIndex < 0 || groups[gi].line != groups.getOrNull(groupIndex)?.line
        groupIndex = gi
        val g = groups[gi]
        val padX = g.rect.height() * 0.35f
        val padY = g.rect.height() * 0.1f
        photoView.focusAngle = g.angle
        photoView.focusRect = RectF(g.rect.left - padX, g.rect.top - padY, g.rect.right + padX, g.rect.bottom + padY)
        photoView.animateTo(g.rect, photoScale, g.angle, if (newLine) 650 else 350)
    }

    // ------------------------------------------------------------ playback

    private fun tapMoving() {
        if (!guard.visible) guard.show() else togglePlay()
    }

    private fun togglePlay() = if (playing) stopPlay() else startPlay()

    private fun startPlay() {
        if (toks.isEmpty()) return
        if (pos >= toks.size - 1) { pos = 0; groupStart = 0; tickerPos = 0f; groupIndex = -1 }
        playing = true
        updateBar()
        val voice = mode == "page" || prefs.speakWhileMoving
        if (voice) {
            if (!ttsReady) { showLabel("Voce non disponibile", 2500); playing = false; updateBar(); return }
            speakFrom(pos)
        }
        when (mode) {
            "page" -> renderPage()
            "line" -> { lastFrame = 0L; Choreographer.getInstance().postFrameCallback(frame) }
            else -> if (!voice) main.post(step)
        }
    }

    private fun stopPlay() {
        if (!playing) return
        playing = false
        tts?.stop()
        main.removeCallbacks(step)
        Choreographer.getInstance().removeFrameCallback(frame)
        updateBar()
        if (mode == "page") renderPage()
    }

    private fun finished() {
        stopPlay()
        pos = toks.size - 1 // next start begins again from the top
        showLabel("Fine del testo", 2000)
    }

    /** Words view and photo view, without voice: one group at a time at the chosen speed. */
    private val step = object : Runnable {
        override fun run() {
            if (!playing) return
            val count: Int
            when (mode) {
                "words" -> {
                    groupStart = pos
                    renderWords()
                    count = groupSize(groupStart)
                }
                "photo" -> {
                    showGroupFor(pos, animate = true)
                    val g = groups.getOrNull(groupIndex) ?: return
                    count = maxOf(1, g.last - pos + 1)
                }
                else -> return
            }
            val lastTok = toks.getOrNull(pos + count - 1)?.text ?: ""
            val pause = when {
                lastTok.endsWith(".") || lastTok.endsWith("!") || lastTok.endsWith("?") || lastTok.endsWith(":") -> 1.8f
                lastTok.endsWith(",") || lastTok.endsWith(";") -> 1.3f
                else -> 1f
            }
            val nextLine = mode == "photo" && groups.getOrNull(groupIndex + 1)?.line != groups.getOrNull(groupIndex)?.line
            val delay = (count * 60_000f / prefs.readingWpm * pause).toLong() + if (nextLine) 450 else 0
            if (pos + count >= toks.size) {
                main.postDelayed({ if (playing) finished() }, delay)
                return
            }
            pos += maxOf(1, count)
            main.postDelayed(this, delay)
        }
    }

    /** Line view: moves smoothly, following the voice or at the chosen speed. */
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            if (!playing || mode != "line") return
            val dt = if (lastFrame == 0L) 0f else (now - lastFrame) / 1_000_000f
            lastFrame = now
            if (prefs.speakWhileMoving) {
                tickerPos += (pos - tickerPos) * minOf(1f, dt / 300f)
            } else {
                tickerPos += dt * prefs.readingWpm / 60_000f
                pos = tickerPos.toInt()
                if (tickerPos >= toks.size - 1) { ticker.position = tickerPos; finished(); return }
            }
            ticker.position = tickerPos
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** The voice reached word [i]: move every view there. */
    private fun onSpokenWord(i: Int) {
        if (!playing || i !in toks.indices) return
        pos = i
        when (mode) {
            "page" -> renderPage()
            "words" -> if (i < groupStart || i >= groupStart + groupSize(groupStart)) { groupStart = i; renderWords() }
            "photo" -> showGroupFor(i, animate = false)
            "line" -> Unit // the frame callback follows pos
        }
    }

    private fun tokenAt(offset: Int): Int {
        var lo = 0
        var hi = starts.size - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (starts[mid] <= offset) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    // ------------------------------------------------------------ read aloud

    override fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) return
        val r = t.setLanguage(Locale.ITALIAN)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) t.setLanguage(Locale.getDefault())
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) {
                // Engines without word timing still move paragraph by paragraph.
                val base = id.removePrefix("u").toIntOrNull() ?: return
                runOnUiThread { onSpokenWord(tokenAt(base)) }
            }
            override fun onRangeStart(id: String, start: Int, end: Int, frame: Int) {
                val base = id.removePrefix("u").toIntOrNull() ?: return
                runOnUiThread { onSpokenWord(tokenAt(base + start)) }
            }
            override fun onDone(id: String) {
                if (id == lastUtterance) runOnUiThread { if (playing) finished() }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String) { runOnUiThread { stopPlay() } }
        })
        ttsReady = true
    }

    private fun speakFrom(i: Int) {
        val t = tts ?: return
        if (i !in toks.indices) return
        t.setSpeechRate(prefs.speechRate)
        val firstPara = toks[i].para
        for (p in firstPara until paraStart.size) {
            val from = if (p == firstPara) toks[i].start else paraStart[p]
            val id = "u$from"
            t.speak(fullText.substring(from, paraEnd[p]), if (p == firstPara) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
            lastUtterance = id
        }
    }

    // ------------------------------------------------------------ OCR

    private fun recognize() {
        // Either a photo taken by the magnifier, or an image shared from another app (Gallery, WhatsApp...).
        val uri: Uri? = if (intent.action == Intent.ACTION_SEND) {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            // Only our own photos (in the cache folder) can be opened by path.
            intent.getStringExtra(EXTRA_IMAGE)?.let { File(it) }
                ?.takeIf { it.canonicalPath.startsWith(cacheDir.canonicalPath + File.separator) }
                ?.let { Uri.fromFile(it) }
        }
        if (uri == null) { finish(); return }
        Thread {
            val bmp = try { decodeUpright(uri, 3000) } catch (e: Throwable) { null }
            val threshold = bmp?.let { inkPaperThreshold(it) } ?: 128f
            runOnUiThread {
                if (bmp == null) { showNoText(); return@runOnUiThread }
                photo = bmp
                photoThreshold = threshold
                photoView.setPicture(bmp)
                applyScheme()
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                recognizer.process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener { result ->
                        buildModel(result)
                        if (toks.isEmpty()) showNoText() else showText()
                    }
                    .addOnFailureListener { showNoText() }
                    .addOnCompleteListener { recognizer.close() }
            }
        }.start()
    }

    private fun decodeUpright(uri: Uri, maxSide: Int): Bitmap? {
        val cr = contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        val bmp = cr.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val deg = try {
            cr.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (_: Exception) { 0 }
        return if (deg == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
    }

    /** Midpoint between dark ink and light paper, for the two-colour view of the photo. */
    private fun inkPaperThreshold(b: Bitmap): Float {
        val hist = IntArray(256)
        var n = 0
        val stepX = maxOf(1, b.width / 300)
        val stepY = maxOf(1, b.height / 300)
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) {
                val c = b.getPixel(x, y)
                hist[(Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000]++
                n++
                x += stepX
            }
            y += stepY
        }
        var acc = 0; var dark = 0; var light = 255
        for (i in 0..255) { acc += hist[i]; if (acc >= n * 0.05) { dark = i; break } }
        acc = 0
        for (i in 255 downTo 0) { acc += hist[i]; if (acc >= n * 0.05) { light = i; break } }
        return if (light - dark >= 40) (dark + light) / 2f else 128f
    }

    /**
     * Builds the words in reading order (ML Kit blocks, then lines, then words), joining words
     * split with a hyphen at the end of a line, and keeps each word's box in the photo.
     */
    private fun buildModel(text: Text) {
        toks.clear()
        lines.clear()
        lineAngles.clear()
        var para = 0
        for (block in text.textBlocks) {
            val firstTok = toks.size
            val firstLine = lines.size
            var letters = 0
            for ((li, line) in block.lines.withIndex()) {
                val els = ArrayList<El>()
                for ((ei, el) in line.elements.withIndex()) {
                    val w = el.text.trim()
                    if (w.isEmpty()) continue
                    letters += w.count { it.isLetterOrDigit() }
                    val prev = toks.lastOrNull()
                    if (ei == 0 && li > 0 && prev != null && prev.para == para && prev.text.length > 1 &&
                        prev.text.endsWith("-") && w.first().isLowerCase()) {
                        prev.text = prev.text.dropLast(1) + w
                    } else {
                        toks.add(Tok(w, para))
                    }
                    el.boundingBox?.let { els.add(El(it, toks.size - 1)) }
                }
                if (els.isNotEmpty()) { lines.add(els); lineAngles.add(line.angle) }
            }
            if (letters < 2) {
                // Noise (a stray mark): drop it.
                while (toks.size > firstTok) toks.removeAt(toks.size - 1)
                while (lines.size > firstLine) { lines.removeAt(lines.size - 1); lineAngles.removeAt(lineAngles.size - 1) }
            } else if (toks.size > firstTok) {
                para++
            }
        }
        val sb = StringBuilder()
        val ps = ArrayList<Int>()
        val pe = ArrayList<Int>()
        var lastPara = -1
        for (t in toks) {
            if (t.para != lastPara) {
                if (sb.isNotEmpty()) { pe.add(sb.length); sb.append("\n\n") }
                ps.add(sb.length)
                lastPara = t.para
            } else {
                sb.append(' ')
            }
            t.start = sb.length
            sb.append(t.text)
        }
        if (ps.size > pe.size) pe.add(sb.length)
        fullText = sb.toString()
        starts = IntArray(toks.size) { toks[it].start }
        paraStart = ps.toIntArray()
        paraEnd = pe.toIntArray()
        ticker.setContent(fullText.replace("\n\n", "  "), starts, IntArray(toks.size) { toks[it].text.length })
    }

    private fun showText() {
        status.visibility = View.GONE
        pos = 0
        setMode(mode)
        textView.announceForAccessibility("Testo pronto. ${paraStart.size} paragrafi. Modo ${MODE_NAMES[mode]}.")
    }

    private fun showNoText() {
        status.visibility = View.VISIBLE
        status.text = "Non ho trovato testo.\n\nInquadra meglio il foglio, accendi la luce e riprova."
        scroll.visibility = View.VISIBLE
        listOf(ticker, wordsView, photoView).forEach { it.visibility = View.GONE }
        btnPlay.visibility = View.GONE
        btnMode.visibility = View.GONE
        updateBar()
    }
}
