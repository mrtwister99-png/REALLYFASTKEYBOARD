package cz.reallyfast

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** Hotovy styl: tema + barvy, ktere se nastavi jednim klepnutim. */
private class Preset(val name: String, val theme: String, val bg: String, val key: String, val text: String, val accent: String)

/** Miniatura klavesnice v barvach stylu. */
@SuppressLint("ViewConstructor")
private class PresetTile(context: Context, private val colors: Theme) : View(context) {
    var selectedStyle = false
        set(value) {
            field = value
            invalidate()
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val w = width.toFloat()
        val h = height.toFloat()
        val r = 12f * d
        paint.style = Paint.Style.FILL
        paint.color = colors.background
        rect.set(d, d, w - d, h - d)
        canvas.drawRoundRect(rect, r, r, paint)
        val pad = 8f * d
        val gap = 3f * d
        val kw = (w - 2 * pad - 3 * gap) / 4f
        val kh = (h - 2 * pad - 2 * gap) / 3f
        paint.color = colors.key
        for (row in 0 until 2) for (col in 0 until 4) {
            val x = pad + col * (kw + gap)
            val y = pad + row * (kh + gap)
            rect.set(x, y, x + kw, y + kh)
            canvas.drawRoundRect(rect, 3f * d, 3f * d, paint)
        }
        val y = pad + 2 * (kh + gap)
        rect.set(pad, y, pad + 3 * kw + 2 * gap, y + kh)
        canvas.drawRoundRect(rect, 3f * d, 3f * d, paint)
        paint.color = colors.accent
        rect.set(pad + 3 * (kw + gap), y, w - pad, y + kh)
        canvas.drawRoundRect(rect, 3f * d, 3f * d, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (if (selectedStyle) 2f else 1f) * d
        paint.color = if (selectedStyle) colors.accent else 0x33FFFFFF
        rect.set(d, d, w - d, h - d)
        canvas.drawRoundRect(rect, r, r, paint)
    }
}

/**
 * Spolecny zaklad obrazovek s nastavenim: radky (prepinac, posuvnik, volba, barva, styly).
 * Kazda zmena se hned zapise pres Rust do settings.json (RustBridge.setSettings -> validace ->
 * soubor + zaloha v SharedPreferences) a bezici klavesnice ji prevezme bez restartu.
 */
abstract class SettingsScreen : BaseScreen() {

    private companion object {
        val PRESETS = listOf(
            Preset("Graphite", "dark", "#101012", "#2A2A30", "#F5F5F7", "#0080FF"),
            Preset("Amber", "dark", "#101012", "#2A2A30", "#F5F5F7", "#F59E0B"),
            Preset("Paper", "light", "#E6E8EC", "#FFFFFF", "#15151A", "#2563EB"),
            Preset("OLED", "oled_black", "#000000", "#17171A", "#F5F5F7", "#22D3EE"),
            Preset("Midnight", "custom", "#0B1220", "#1B2740", "#E6EDF7", "#60A5FA"),
            Preset("Forest", "custom", "#0F1A14", "#1E3328", "#E8F3EC", "#34D399"),
            Preset("Sand", "custom", "#EFE7DA", "#FBF7F0", "#2B241B", "#C2410C"),
            Preset("Rose", "custom", "#1A1114", "#33202A", "#F8E9EE", "#FB7185"),
            Preset("Mono", "custom", "#18181B", "#2E2E33", "#FAFAFA", "#FAFAFA"),
        )
        val PALETTE = listOf(
            "#0080FF", "#F59E0B", "#EF4444", "#EC4899", "#8B5CF6", "#06B6D4", "#10B981", "#84CC16", "#F4F4F5", "#111113",
        )
    }

    private val refreshers = ArrayList<() -> Unit>()
    private val listener: () -> Unit = {
        onSettingsChanged()
        for (r in refreshers) r()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RustBridge.init(this)
        RustBridge.listeners.add(listener)
    }

    override fun onDestroy() {
        RustBridge.listeners.remove(listener)
        super.onDestroy()
    }

    /** Zavolano pri kazde zmene nastaveni, pred obnovenim radku. */
    protected open fun onSettingsChanged() {}

    /** Zavolano po zapisu jedne hodnoty z radku (napr. ukazka neonu v nahledu). */
    protected open fun onSettingUpdated(key: String, value: Any) {}

    /** Svisly kontejner rolovaciho obsahu obrazovky. */
    protected fun contentColumn(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(16f), dp(20f), dp(16f), dp(28f))
        return c
    }

    protected fun update(key: String, value: Any) {
        val j = RustBridge.settings.edit()
        j.put(key, value)
        RustBridge.setSettings(j)
        onSettingUpdated(key, value)
    }

    // ------------------------------------------------------------------ stavebni prvky

    /** Prepinac; s `onHelp` je mezi popiskem a prepinacem kulate "?" s vlastnim cilem klepnuti. */
    protected fun switchRow(box: LinearLayout, labelRes: Int, key: String, onHelp: (() -> Unit)? = null) {
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.minimumHeight = dp(52f)
        row.addView(label(getString(labelRes)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (onHelp != null) {
            val circle = TextView(this)
            circle.text = "?"
            circle.gravity = Gravity.CENTER
            circle.setTextColor(dim)
            circle.textSize = 13f
            circle.typeface = Typeface.DEFAULT_BOLD
            circle.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0)
                setStroke(dp(1.5f), dim)
            }
            val hit = FrameLayout(this)
            hit.contentDescription = getString(R.string.clipboard_help)
            hit.addView(circle, FrameLayout.LayoutParams(dp(24f), dp(24f), Gravity.CENTER))
            hit.setOnClickListener { onHelp() }
            row.addView(hit, LinearLayout.LayoutParams(dp(44f), dp(44f)))
        }
        val sw = Switch(this)
        sw.isChecked = RustBridge.settings.json.optBoolean(key)
        sw.setOnCheckedChangeListener { _, checked ->
            if (RustBridge.settings.json.optBoolean(key) != checked) update(key, checked)
        }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        box.addView(row, rowParams())
        refreshers.add { sw.isChecked = RustBridge.settings.json.optBoolean(key) }
    }

    /**
     * Posuvnik. `scale` = delitel (100 pro float hodnoty typu 0.5-1.5).
     * Hodnota se zapisuje uz behem tazeni, aby nahled reagoval okamzite.
     */
    protected fun sliderRow(box: LinearLayout, labelRes: Int, key: String, min: Int, max: Int, scale: Int, format: (Int) -> String) {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(0, dp(10f), 0, dp(6f))
        val head = LinearLayout(this)
        head.addView(label(getString(labelRes)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val value = label("", 14f, dim)
        head.addView(value)
        col.addView(head, rowParams())
        val seek = SeekBar(this)
        seek.max = max - min
        fun current(): Int {
            val j = RustBridge.settings.json
            return if (scale == 1) j.optInt(key, min) else Math.round(j.optDouble(key, min.toDouble() / scale) * scale).toInt()
        }
        seek.progress = current() - min
        value.text = format(current())
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val v = progress + min
                value.text = format(v)
                if (fromUser && v != current()) {
                    if (scale == 1) update(key, v) else update(key, v.toDouble() / scale)
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        col.addView(seek, rowParams())
        box.addView(col, rowParams())
        refreshers.add {
            val v = current()
            if (seek.progress != v - min) seek.progress = v - min
            value.text = format(v)
        }
    }

    protected fun choiceRow(box: LinearLayout, labelRes: Int, key: String, values: List<String>, labels: List<String>) {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(0, dp(10f), 0, dp(10f))
        col.addView(label(getString(labelRes)), rowParams())
        val row = LinearLayout(this)
        val pills = ArrayList<TextView>()
        fun paint() {
            val cur = RustBridge.settings.json.optString(key)
            val a = accent
            val onA = if (Theme.luminance(a) > 0.55f) 0xFF111113.toInt() else 0xFFFFFFFF.toInt()
            for ((i, p) in pills.withIndex()) {
                val on = values[i] == cur
                p.background = rounded(if (on) a else field, dp(12f).toFloat())
                p.setTextColor(if (on) onA else text)
            }
        }
        for (i in values.indices) {
            val p = label(labels[i], 14f)
            p.gravity = Gravity.CENTER
            p.setPadding(dp(4f), dp(10f), dp(4f), dp(10f))
            p.setOnClickListener { update(key, values[i]) }
            pills.add(p)
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (i > 0) lp.leftMargin = dp(6f)
            row.addView(p, lp)
        }
        val lp = rowParams()
        lp.topMargin = dp(8f)
        col.addView(row, lp)
        box.addView(col, rowParams())
        paint()
        refreshers.add { paint() }
    }

    protected fun colorRow(box: LinearLayout, labelRes: Int, key: String) {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(0, dp(10f), 0, dp(10f))
        val head = LinearLayout(this)
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(label(getString(labelRes)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val hex = label("", 14f, dim)
        hex.setPadding(dp(8f), dp(6f), 0, dp(6f))
        hex.setOnClickListener { askHex(key) }
        head.addView(hex)
        col.addView(head, rowParams())
        val row = LinearLayout(this)
        val swatches = ArrayList<View>()
        fun paint() {
            val cur = RustBridge.settings.json.optString(key).uppercase()
            hex.text = cur
            for ((i, v) in swatches.withIndex()) {
                val d = rounded(Theme.parse(PALETTE[i], 0), dp(10f).toFloat())
                d.setStroke(dp(2f), if (PALETTE[i] == cur) text else 0x33FFFFFF)
                v.background = d
            }
        }
        for (i in PALETTE.indices) {
            val v = View(this)
            v.setOnClickListener { update(key, PALETTE[i]) }
            swatches.add(v)
            val lp = LinearLayout.LayoutParams(0, dp(30f), 1f)
            if (i > 0) lp.leftMargin = dp(5f)
            row.addView(v, lp)
        }
        val lp = rowParams()
        lp.topMargin = dp(8f)
        col.addView(row, lp)
        box.addView(col, rowParams())
        paint()
        refreshers.add { paint() }
    }

    private fun askHex(key: String) {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        input.setText(RustBridge.settings.json.optString(key))
        AlertDialog.Builder(this)
            .setTitle(R.string.color_hex_title)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                var v = input.text.toString().trim()
                if (!v.startsWith("#")) v = "#$v"
                update(key, v) // neplatnou hodnotu Rust nahradi vychozi barvou
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Vodorovny pruh hotovych stylu s miniaturami. */
    protected fun presetRow(box: LinearLayout) {
        box.addView(label(getString(R.string.styles), 15f).also { it.setPadding(0, dp(12f), 0, dp(8f)) }, rowParams())
        val scroll = HorizontalScrollView(this)
        scroll.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(this)
        val tiles = ArrayList<PresetTile>()
        fun isCurrent(p: Preset): Boolean {
            val s = RustBridge.settings
            if (s.theme != p.theme || !s.accentColor.equals(p.accent, true)) return false
            return p.theme != "custom" || (s.customBackground.equals(p.bg, true) &&
                s.customKey.equals(p.key, true) && s.customText.equals(p.text, true))
        }
        for ((i, p) in PRESETS.withIndex()) {
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER_HORIZONTAL
            val colors = Theme.of(Theme.parse(p.bg, 0), Theme.parse(p.key, 0), Theme.parse(p.text, 0), Theme.parse(p.accent, 0))
            val tile = PresetTile(this, colors)
            tile.contentDescription = p.name
            tiles.add(tile)
            col.addView(tile, LinearLayout.LayoutParams(dp(92f), dp(62f)))
            col.addView(label(p.name, 12f, dim).also {
                it.gravity = Gravity.CENTER
                it.setPadding(0, dp(5f), 0, 0)
            })
            col.setOnClickListener {
                val j = RustBridge.settings.edit()
                j.put("theme", p.theme)
                j.put("accent_color", p.accent)
                if (p.theme == "custom") {
                    j.put("custom_background", p.bg)
                    j.put("custom_key", p.key)
                    j.put("custom_text", p.text)
                }
                RustBridge.setSettings(j)
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (i > 0) lp.leftMargin = dp(10f)
            row.addView(col, lp)
        }
        scroll.addView(row)
        val lp = rowParams()
        lp.bottomMargin = dp(8f)
        box.addView(scroll, lp)
        val paint = { for ((i, t) in tiles.withIndex()) t.selectedStyle = isCurrent(PRESETS[i]) }
        paint()
        refreshers.add(paint)
    }
}
