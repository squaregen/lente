package it.squaregen.lente

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat

/**
 * Builders for big, high-contrast controls (yellow on black, at least 72dp tall,
 * text in sp so it follows the system font size). Used by the menus and settings.
 */
object Ui {
    fun dp(ctx: Context, v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    fun font(ctx: Context, bold: Boolean = true): Typeface {
        val base = ResourcesCompat.getFont(ctx, R.font.atkinson) ?: Typeface.DEFAULT
        return Typeface.create(base, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun lp(ctx: Context) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        val m = dp(ctx, 6)
        setMargins(0, m, 0, m)
    }

    fun header(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        typeface = font(ctx)
        setTextColor(ContextCompat.getColor(ctx, R.color.fg))
        setPadding(dp(ctx, 4), dp(ctx, 18), dp(ctx, 4), dp(ctx, 6))
        isFocusable = true
        if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        layoutParams = lp(ctx)
    }

    fun note(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        typeface = font(ctx, bold = false)
        setLineSpacing(0f, 1.4f)
        setTextColor(ContextCompat.getColor(ctx, R.color.fg))
        setPadding(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4))
        isFocusable = true
        layoutParams = lp(ctx)
    }

    fun button(ctx: Context, text: String, icon: Int = 0, onClick: (TextView) -> Unit): TextView = TextView(ctx).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        typeface = font(ctx)
        setTextColor(ContextCompat.getColor(ctx, R.color.fg))
        background = ContextCompat.getDrawable(ctx, R.drawable.big_button_bg)
        minHeight = dp(ctx, 76)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setPadding(dp(ctx, 18), dp(ctx, 12), dp(ctx, 18), dp(ctx, 12))
        if (icon != 0) {
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
            compoundDrawablePadding = dp(ctx, 16)
        }
        isClickable = true
        isFocusable = true
        layoutParams = lp(ctx)
        setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onClick(this)
        }
    }

    /** On/off row: the label always says the current state in words ("Sì"/"No"). */
    fun toggle(ctx: Context, label: String, get: () -> Boolean, set: (Boolean) -> Unit): TextView {
        fun render(v: TextView) {
            val on = get()
            v.text = "$label: ${if (on) "Sì" else "No"}"
            v.isActivated = on
        }
        return button(ctx, "") { v ->
            set(!get())
            render(v)
            v.announceForAccessibility(if (get()) "Attivo" else "Disattivo")
        }.also { render(it) }
    }

    /** Row that cycles through a short list of choices on each tap. */
    fun <T> choice(ctx: Context, label: String, options: List<Pair<T, String>>, get: () -> T, set: (T) -> Unit): TextView {
        fun render(v: TextView) {
            val cur = options.firstOrNull { it.first == get() }?.second ?: options[0].second
            v.text = "$label: $cur"
        }
        return button(ctx, "") { v ->
            val i = options.indexOfFirst { it.first == get() }
            set(options[(i + 1) % options.size].first)
            render(v)
            v.announceForAccessibility(v.text)
        }.also { render(it) }
    }

    /** Label with big minus / plus buttons. */
    fun stepper(ctx: Context, label: () -> String, onMinus: () -> Unit, onPlus: () -> Unit): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp(ctx)
        }
        val text = TextView(ctx).apply {
            this.text = label()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = font(ctx)
            setTextColor(ContextCompat.getColor(ctx, R.color.fg))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(ctx, 4), 0, dp(ctx, 8), 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        fun square(icon: Int, desc: String, action: () -> Unit) = TextView(ctx).apply {
            background = ContextCompat.getDrawable(ctx, R.drawable.big_button_bg)
            setCompoundDrawablesRelativeWithIntrinsicBounds(0, icon, 0, 0)
            gravity = Gravity.CENTER
            setPadding(0, dp(ctx, 20), 0, 0)
            contentDescription = desc
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 80), dp(ctx, 80)).apply { marginStart = dp(ctx, 8) }
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                action()
                text.text = label()
            }
        }
        row.addView(text)
        row.addView(square(R.drawable.ic_minus, "Diminuisci", onMinus))
        row.addView(square(R.drawable.ic_plus, "Aumenta", onPlus))
        return row
    }
}
