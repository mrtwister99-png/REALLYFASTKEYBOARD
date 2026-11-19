package cz.reallyfast

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Window
import android.os.Build
import android.os.Bundle
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
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

    /** Prave otevrena bublina (napoveda, editor klavesy); nejvyse jedna. */
    var currentBubble: Bubble? = null

    private var backCallback: Any? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            val cb = OnBackInvokedCallback { handleBack() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            backCallback = cb
        }
    }

    override fun onPause() {
        currentBubble?.hide(immediate = true)
        super.onPause()
    }

    override fun onDestroy() {
        currentBubble?.hide(immediate = true)
        if (Build.VERSION.SDK_INT >= 33) {
            (backCallback as? OnBackInvokedCallback)?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        }
        backCallback = null
        super.onDestroy()
    }

    // Bez AndroidX: od API 33 zpet ridi OnBackInvokedCallback, tohle zustava jen pro starsi systemy.
    @SuppressLint("GestureBackNavigation")
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = handleBack()

    /** Zpet: nejdriv zavre otevrenou bublinu, pak necha obrazovku rozhodnout, jinak se zavre. */
    private fun handleBack() {
        val b = currentBubble
        if (b != null && b.isShowing) {
            b.hide()
            return
        }
        if (!onBackRequested()) finish()
    }

    /** Obrazovka muze zpet zachytit (true = zpracovano, napr. potvrzovaci dialog). */
    protected open fun onBackRequested(): Boolean = false

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

    /** Mensi tlumeny popisek pod radky (vysvetleni volby). */
    protected fun hintText(box: LinearLayout, s: CharSequence): TextView {
        val h = label(s, 12f, dim)
        h.setPadding(0, 0, 0, dp(10f))
        box.addView(h, rowParams())
        return h
    }

    /** Velky nadpis obrazovky se sipkou zpet. Vraci text nadpisu (kvuli animaci). */
    protected fun title(parent: LinearLayout, s: CharSequence): TextView {
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        val arrow = label("‹", 34f, dim)
        arrow.setPadding(0, 0, dp(14f), dp(4f))
        arrow.contentDescription = getString(R.string.back)
        arrow.setOnClickListener { finish() }
        row.addView(arrow)
        val t = label(s, 26f)
        t.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        row.addView(t)
        parent.addView(row, rowParams())
        return t
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

    /**
     * Tlacitko pres celou sirku karty: normalni = vypln `field` s barevnym textem, nebezpecne
     * (barva `danger`) = jen cerveny obrys. Vraci TextView, aby volajici mohl menit text.
     */
    protected fun buttonRow(box: LinearLayout, s: CharSequence, color: Int = text, onClick: () -> Unit): TextView {
        val b = buttonView(s, color, onClick)
        val lp = rowParams()
        lp.topMargin = dp(5f)
        lp.bottomMargin = dp(5f)
        box.addView(b, lp)
        return b
    }

    /** Samotne tlacitko (vzhled viz `buttonRow`), bez vlozeni do karty. */
    protected fun buttonView(s: CharSequence, color: Int = text, onClick: () -> Unit): TextView {
        val b = label(s, 15f, color)
        b.gravity = Gravity.CENTER
        b.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        b.minHeight = dp(46f)
        b.setPadding(dp(14f), 0, dp(14f), 0)
        val radius = dp(12f).toFloat()
        val face = GradientDrawable().apply {
            cornerRadius = radius
            if (color == danger) {
                setColor(0x00000000)
                setStroke(dp(1.5f), danger)
            } else {
                setColor(field)
            }
        }
        val mask = GradientDrawable().apply {
            setColor(0xFFFFFFFF.toInt())
            cornerRadius = radius
        }
        b.background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), face, mask)
        b.setOnClickListener { onClick() }
        return b
    }

    /**
     * Potvrzovaci dialog s vlastnim vzhledem: zaoblena karta, volitelne cerveny obrys (nebo obrys barvy
     * `tint`), zprava a dve tlacitka (zrusit bez obrysu, potvrdit s obrysem). Bez `cancelLabel` je jen
     * jedno tlacitko a dialog nejde zavrit jinak. Okno je mimo kartu pruhledne.
     */
    protected fun confirmDialog(
        message: CharSequence, confirmLabel: CharSequence, cancelLabel: CharSequence?, red: Boolean, tint: Int = 0,
        onConfirm: () -> Unit,
    ) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(20f), dp(20f), dp(20f), dp(14f))
        root.background = GradientDrawable().apply {
            setColor(card)
            cornerRadius = dp(16f).toFloat()
            if (red) setStroke(dp(2f), danger) else if (tint != 0) setStroke(dp(2f), tint)
        }
        root.addView(label(message, 16f), rowParams())
        val row = LinearLayout(this)
        row.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        val rlp = rowParams()
        rlp.topMargin = dp(18f)
        root.addView(row, rlp)
        fun dialogButton(t: CharSequence, color: Int, outlined: Boolean, action: () -> Unit) {
            val b = label(t, 15f, color)
            b.gravity = Gravity.CENTER
            b.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            b.minHeight = dp(44f)
            b.minWidth = dp(88f)
            b.setPadding(dp(16f), 0, dp(16f), 0)
            val face = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(0x00000000)
                if (outlined) setStroke(dp(1.5f), color)
            }
            val mask = GradientDrawable().apply {
                setColor(0xFFFFFFFF.toInt())
                cornerRadius = dp(12f).toFloat()
            }
            b.background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), face, mask)
            b.setOnClickListener {
                dialog.dismiss()
                action()
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.leftMargin = dp(8f)
            row.addView(b, lp)
        }
        if (cancelLabel != null) dialogButton(cancelLabel, dim, false) {} else dialog.setCancelable(false)
        dialogButton(confirmLabel, if (red) danger else if (tint != 0) tint else accent, true, onConfirm)
        dialog.setContentView(root)
        dialog.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(0x00000000))
            val width = minOf(resources.displayMetrics.widthPixels - dp(48f), dp(420f))
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }

    /** Potvrzeni mazani / odchodu: cerveny obrys. */
    protected fun confirmDanger(message: CharSequence, confirmLabel: CharSequence, onConfirm: () -> Unit) =
        confirmDialog(message, confirmLabel, getString(R.string.cancel), true, onConfirm = onConfirm)

    /** Radek, ktery otevira dalsi obrazovku (text + sipka vpravo). */
    protected fun navRow(box: LinearLayout, s: CharSequence, icon: Int = 0, onClick: () -> Unit) {
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.minimumHeight = dp(56f)
        if (icon != 0) {
            // Maly obrazek pred popiskem (maskot u Maker).
            val img = android.widget.ImageView(this)
            img.setImageResource(icon)
            img.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            val ilp = LinearLayout.LayoutParams(dp(34f), dp(28f))
            ilp.rightMargin = dp(12f)
            row.addView(img, ilp)
        }
        row.addView(label(s, 16f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(label("›", 24f, dim))
        row.setOnClickListener { onClick() }
        box.addView(row, rowParams())
    }
}
