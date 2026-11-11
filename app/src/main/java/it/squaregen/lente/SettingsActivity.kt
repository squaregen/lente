package it.squaregen.lente

import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** Settings, all as big rows that change with a single tap (no small switches or dialogs). */
class SettingsActivity : ComponentActivity() {

    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        requestedOrientation = prefs.orientationFlag
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val scroll = ScrollView(this)
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(this@SettingsActivity, 16)
            setPadding(p, p, p, p * 2)
        }
        scroll.addView(c, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        build(c)
    }

    private fun build(c: LinearLayout) {
        c.addView(Ui.button(this, "Torna alla lente", R.drawable.ic_close) { finish() })

        c.addView(Ui.header(this, "Pulsante Colori"))
        c.addView(Ui.note(this, "Scegli quali combinazioni di colori usare quando tocchi Colori."))
        for (mode in ColorModes.ALL) {
            c.addView(Ui.toggle(this, mode.label, { prefs.enabledModes.contains(mode.id) }) { on ->
                val set = prefs.enabledModes.toMutableSet()
                if (on) set.add(mode.id) else if (set.size > 1) set.remove(mode.id)
                prefs.enabledModes = set
            })
        }

        c.addView(Ui.header(this, "Schermo"))
        c.addView(Ui.choice(this, "Nascondi i pulsanti dopo",
            listOf(5 to "5 secondi", 8 to "8 secondi", 15 to "15 secondi", 30 to "30 secondi", 0 to "mai (sconsigliato)"),
            { prefs.autoHideSec }) { prefs.autoHideSec = it })
        c.addView(Ui.note(this, "Nascondere i pulsanti protegge lo schermo dalle immagini che restano impresse. " +
            "Tocca lo schermo per farli ricomparire."))
        c.addView(Ui.choice(this, "Spegni lo schermo se non lo usi da",
            listOf(5 to "5 minuti", 10 to "10 minuti", 20 to "20 minuti", 30 to "30 minuti", 0 to "mai"),
            { prefs.screenOffMin }) { prefs.screenOffMin = it })
        c.addView(Ui.toggle(this, "Luminosità schermo al massimo", { prefs.maxBrightness }) { prefs.maxBrightness = it })
        c.addView(Ui.choice(this, "Orientamento", listOf("landscape" to "orizzontale", "portrait" to "verticale", "auto" to "automatico"),
            { prefs.orientation }) { prefs.orientation = it; requestedOrientation = prefs.orientationFlag })

        c.addView(Ui.header(this, "Ingrandimento"))
        c.addView(Ui.choice(this, "Ingrandimento massimo",
            listOf(12 to "12 volte", 20 to "20 volte", 30 to "30 volte", 50 to "50 volte", 80 to "80 volte", 120 to "120 volte"),
            { prefs.maxZoom }) { prefs.maxZoom = it })
        c.addView(Ui.note(this, "Oltre lo zoom della fotocamera l'immagine viene ingrandita ancora, ma diventa meno nitida. " +
            "Per i dettagli più piccoli usa Ferma: l'immagine ferma è una foto ad alta risoluzione e si ingrandisce meglio."))

        c.addView(Ui.header(this, "Comandi"))
        c.addView(Ui.toggle(this, "Tasti del volume per lo zoom", { prefs.volumeZoom }) { prefs.volumeZoom = it })
        c.addView(Ui.toggle(this, "Blocca dentro l'app all'apertura", { prefs.lockOnStart }) { prefs.lockOnStart = it })
        c.addView(Ui.note(this, "Il blocco usa la funzione di Android \"Blocco app\" (o \"Fissa app\"). " +
            "Se il telefono lo chiede, conferma. Per sbloccare a mano: tieni premuti insieme Indietro e Panoramica, " +
            "oppure scorri dal basso e tieni premuto."))

        c.addView(Ui.header(this, "Testo"))
        c.addView(Ui.toggle(this, "Proponi la lettura quando vedo un testo", { prefs.textDetect }) { prefs.textDetect = it })
        c.addView(Ui.toggle(this, "Leggi: guidami a inquadrare il foglio", { prefs.guidedCapture }) { prefs.guidedCapture = it })
        c.addView(Ui.toggle(this, "Suggerimenti a voce e suoni", { prefs.voiceHints }) { prefs.voiceHints = it })
        c.addView(Ui.note(this, "Con la guida, Leggi allarga l'inquadratura e ti dice come spostare il telefono finché " +
            "tutto il testo è dentro, poi scatta da solo (vibra quando è inquadrato e quando scatta). " +
            "La voce è spenta, per essere discreti in pubblico. Tieni premuto Leggi per scattare subito."))
        c.addView(Ui.choice(this, "Modo di lettura iniziale",
            listOf("page" to "pagina", "line" to "riga che scorre", "words" to "poche parole alla volta", "photo" to "foto guidata"),
            { prefs.readerMode }) { prefs.readerMode = it })
        c.addView(Ui.choice(this, "Velocità di scorrimento",
            listOf(50 to "molto lenta", 70 to "lenta", 90 to "media", 120 to "veloce", 160 to "molto veloce"),
            { prefs.readingWpm }) { prefs.readingWpm = it })
        c.addView(Ui.choice(this, "Parole alla volta", listOf(1 to "1", 2 to "2", 3 to "3", 4 to "4", 5 to "5"),
            { prefs.wordsAtOnce }) { prefs.wordsAtOnce = it })
        c.addView(Ui.toggle(this, "Leggi ad alta voce anche mentre scorre", { prefs.speakWhileMoving }) { prefs.speakWhileMoving = it })
        c.addView(Ui.toggle(this, "Messa a fuoco intelligente", { prefs.smartFocus }) { prefs.smartFocus = it })
        c.addView(Ui.stepper(this, { "Grandezza testo in lettura: ${prefs.readerTextSp}" },
            { prefs.readerTextSp = prefs.readerTextSp - 4 }, { prefs.readerTextSp = prefs.readerTextSp + 4 }))
        c.addView(Ui.choice(this, "Velocità della voce",
            listOf(0.7f to "lenta", 0.85f to "un po' lenta", 1f to "normale", 1.25f to "veloce", 1.5f to "molto veloce"),
            { prefs.speechRate }) { prefs.speechRate = it })

        c.addView(Ui.header(this, "Come si usa"))
        c.addView(Ui.note(this,
            "• Pizzica lo schermo o usa i tasti del volume per ingrandire.\n" +
            "• Tocca un punto per metterlo a fuoco.\n" +
            "• Ferma blocca l'immagine: poi puoi ingrandirla e spostarla con il dito.\n" +
            "• Leggi fotografa il testo e lo mostra grande, e può leggerlo ad alta voce.\n" +
            "• Colori cambia i contrasti (tieni premuto per tutte le regolazioni).\n" +
            "• Se l'immagine resta sfocata, allontana un po' il telefono e usa lo zoom."))
    }
}
