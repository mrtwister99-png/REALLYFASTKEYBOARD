package cz.reallyfast

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** Spolecny zaklad obrazovek nastaveni: barvy a male stavebni prvky (bez AndroidX, bez XML layoutu). */
abstract class BaseScreen : Activity() {
    protected val bg = 0xFF101012.toInt()
    protected val card = 0xFF1B1B1F.toInt()
    protected val field = 0xFF26262B.toInt()
    protected val text = 0xFFF4F4F5.toInt()
    protected val dim = 0xFF9A9AA3.toInt()
    protected val danger = 0xFFEF4444.toInt()
    protected val accent: Int get() = Theme.parse(RustBridge.settings.accentColor, 0xFF0080FF.toInt())

    protected fun dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    protected fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    protected fun label(s: CharSequence, size: Float = 15f, color: Int = text): TextView = TextView(this).apply {
        this.text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
    }

    protected fun rowParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    /** Velky nadpis obrazovky; s `back` i sipka zpet. */
    protected fun title(parent: LinearLayout, s: CharSequence, back: Boolean) {
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        if (back) {
            val arrow = label("‹", 34f, dim)
            arrow.setPadding(0, 0, dp(14f), dp(4f))
            arrow.contentDescription = getString(R.string.back)
            arrow.setOnClickListener { finish() }
            row.addView(arrow)
        }
        val t = label(s, if (back) 26f else 30f)
        t.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        row.addView(t)
        parent.addView(row, rowParams())
    }

    /** Nadpis sekce + zaoblena karta, do ktere se vkladaji radky. */
    protected fun section(parent: LinearLayout, titleText: CharSequence): LinearLayout {
        val t = label(titleText.toString().uppercase(), 12f, dim)
        t.letterSpacing = 0.08f
        val lp = rowParams()
        lp.topMargin = dp(26f)
        lp.bottomMargin = dp(8f)
        lp.leftMargin = dp(6f)
        parent.addView(t, lp)
        return cardBox(parent)
    }

    protected fun cardBox(parent: LinearLayout): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.background = rounded(card, dp(16f).toFloat())
        box.setPadding(dp(16f), dp(6f), dp(16f), dp(6f))
        parent.addView(box, rowParams())
        return box
    }

    protected fun buttonRow(box: LinearLayout, s: CharSequence, color: Int = text, onClick: () -> Unit): TextView {
        val b = label(s, 15f, color)
        b.gravity = Gravity.CENTER_VERTICAL
        b.minimumHeight = dp(52f)
        b.setOnClickListener { onClick() }
        box.addView(b, rowParams())
        return b
    }

    /** Radek, ktery otevira dalsi obrazovku (text + sipka vpravo). */
    protected fun navRow(box: LinearLayout, s: CharSequence, onClick: () -> Unit) {
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.minimumHeight = dp(56f)
        row.addView(label(s, 16f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(label("›", 24f, dim))
        row.setOnClickListener { onClick() }
        box.addView(row, rowParams())
    }
}
