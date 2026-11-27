package cz.reallyfast

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.roundToInt

/** Hotovy styl: barvy, ktere se nastavi jednim klepnutim (vzdy jako vlastni tema). */
private class Preset(val name: String, val bg: String, val key: String, val text: String, val accent: String)

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
            Preset("Graphite", "#101012", "#2A2A30", "#F5F5F7", "#0080FF"),
            Preset("Amber", "#101012", "#2A2A30", "#F5F5F7", "#F59E0B"),
            Preset("Paper", "#E6E8EC", "#FFFFFF", "#15151A", "#2563EB"),
            Preset("OLED", "#000000", "#17171A", "#F5F5F7", "#22D3EE"),
            Preset("Midnight", "#0B1220", "#1B2740", "#E6EDF7", "#60A5FA"),
            Preset("Forest", "#0F1A14", "#1E3328", "#E8F3EC", "#34D399"),
            Preset("Sand", "#EFE7DA", "#FBF7F0", "#2B241B", "#C2410C"),
            Preset("Rose", "#1A1114", "#33202A", "#F8E9EE", "#FB7185"),
            Preset("Mono", "#18181B", "#2E2E33", "#FAFAFA", "#FAFAFA"),
        )
        /** Klice tri barev motivu: pri zmene se motiv prepne na "custom" (viz writeColor). */
        val THEME_COLOR_KEYS = listOf("custom_background", "custom_key", "custom_text")
    }

    private val refreshers = ArrayList<() -> Unit>()
    private var colorDialog: Dialog? = null
    private val listener: () -> Unit = {
        onSettingsChanged()
        for (r in refreshers) r()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RustBridge.init(this)
        RustBridge.listeners.add(listener)
    }

    override fun onPause() {
        colorDialog?.dismiss()
        super.onPause()
    }

    override fun onDestroy() {
        colorDialog?.dismiss()
        RustBridge.listeners.remove(listener)
        super.onDestroy()
    }

    /** Zavolano pri kazde zmene nastaveni, pred obnovenim radku. */
    protected open fun onSettingsChanged() {}

    /** Zavolano po zapisu jedne hodnoty z radku (napr. ukazka neonu v nahledu). */
    protected open fun onSettingUpdated(key: String, value: Any) {}

    /** Vyber barvy nastaveni `key` se otevrel / zavrel (nahled muze po dobu vyberu drzet ukazku). */
    protected open fun onColorPickerState(key: String, open: Boolean) {}

    /** Zavolano tesne pred zapisem hodnoty (rozlozeni je jeste puvodni). */
    protected open fun onSettingWillUpdate(key: String, value: Any) {}

    /** Uzivatel se dotkl radku nastaveni `key` (klepnuti, zmena, zacatek tahu): ukazka a napoveda. `anchor` = radek. */
    protected open fun onSettingTouched(key: String, anchor: View) {}

    /** Svisly kontejner rolovaciho obsahu obrazovky. */
    protected fun contentColumn(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(16f), dp(20f), dp(16f), dp(28f))
        return c
    }

    protected fun update(key: String, value: Any) {
        onSettingWillUpdate(key, value)
        val j = RustBridge.settings.edit()
        j.put(key, value)
        RustBridge.setSettings(j)
        onSettingUpdated(key, value)
    }

    /** Zapise vic hodnot jednim volanim (rozlozeni se prepocita jednou); ukazky se pusti pro kazdy klic. */
    protected fun updateAll(vararg pairs: Pair<String, Any>) {
        for ((k, v) in pairs) onSettingWillUpdate(k, v)
        val j = RustBridge.settings.edit()
        for ((k, v) in pairs) j.put(k, v)
        RustBridge.setSettings(j)
        for ((k, v) in pairs) onSettingUpdated(k, v)
    }

    // ------------------------------------------------------------------ stavebni prvky

    /**
     * Prepinac. S `helpRes` je mezi popiskem a prepinacem kulate "?"; klepnuti otevre nad nim
     * bublinu s napovedou, ktera se po `helpMs` (nebo klepnutim kamkoli) zase zasune.
     */
    protected fun switchRow(
        box: LinearLayout, labelRes: Int, key: String, helpRes: Int = 0, helpMs: Long = 5000, hintRes: Int = 0,
        dependsOn: String = "",
    ) {
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.minimumHeight = dp(52f)
        row.addView(label(getString(labelRes)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (helpRes != 0) {
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
            hit.contentDescription = getString(helpRes)
            hit.addView(circle, FrameLayout.LayoutParams(dp(24f), dp(24f), Gravity.CENTER))
            var help: Bubble? = null
            hit.setOnClickListener {
                val old = help
                // Klepnuti na "?" pri otevrene bubline ji jen zavre (zavrela se uz pri dotyku).
                if (old != null && (old.isShowing || SystemClock.uptimeMillis() - old.hideStartedAt < 500)) return@setOnClickListener
                val tv = label(getString(helpRes), 13.5f)
                tv.setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
                val bubble = Bubble(this, tv, passThrough = true, autoHideMs = helpMs)
                help = bubble
                bubble.show(bubble.anchorOf(circle))
            }
            row.addView(hit, LinearLayout.LayoutParams(dp(44f), dp(44f)))
        }
        val sw = ToggleView(this)
        sw.contentDescription = getString(labelRes)
        sw.setChecked(RustBridge.settings.json.optBoolean(key), false)
        sw.onChange = { checked ->
            if (RustBridge.settings.json.optBoolean(key) != checked) {
                update(key, checked)
                onSettingTouched(key, row)
            }
        }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        box.addView(row, rowParams())
        val hint = if (hintRes != 0) hintText(box, getString(hintRes)) else null
        // Radek zavisly na jinem prepinaci je zasedly a nereaguje, dokud je ten vypnuty.
        fun applyEnabled() {
            val on = dependsOn.isEmpty() || RustBridge.settings.json.optBoolean(dependsOn)
            row.isEnabled = on
            sw.isEnabled = on
            val a = if (on) 1f else 0.4f
            row.alpha = a
            hint?.alpha = a
        }
        applyEnabled()
        refreshers.add {
            sw.setChecked(RustBridge.settings.json.optBoolean(key), false)
            applyEnabled()
        }
    }

    /**
     * Posuvnik. `scale` = delitel (100 pro float hodnoty typu 0.5-1.5).
     * Hodnota se zapisuje uz behem tazeni, aby nahled reagoval okamzite.
     */
    protected fun sliderRow(box: LinearLayout, labelRes: Int, key: String, min: Int, max: Int, scale: Int, step: Int = 1, enabledWhen: (() -> Boolean)? = null, format: (Int) -> String) {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(0, dp(10f), 0, dp(6f))
        val head = LinearLayout(this)
        head.addView(label(getString(labelRes)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val value = label("", 14f, dim)
        head.addView(value)
        col.addView(head, rowParams())
        val seekRow = LinearLayout(this)
        seekRow.gravity = android.view.Gravity.CENTER_VERTICAL
        val minus = label("‹", 24f, dim).apply { setPadding(dp(4f), 0, dp(8f), 0) }
        val plus = label("›", 24f, dim).apply { setPadding(dp(8f), 0, dp(4f), 0) }
        seekRow.addView(minus)
        val seek = SeekBar(this)
        seek.max = (max - min) / step
        fun current(): Int {
            val j = RustBridge.settings.json
            return if (scale == 1) j.optInt(key, min) else (j.optDouble(key, min.toDouble() / scale) * scale).roundToInt()
        }
        seek.progress = (current() - min) / step
        value.text = format(current())
        var lastWrite = 0L
        fun commit(v: Int) {
            if (v == current()) return
            if (scale == 1) update(key, v) else update(key, v.toDouble() / scale)
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val v = progress * step + min
                value.text = format(v)
                if (fromUser) {
                    // Pri tahu se zapisuje nejvyse jednou za 100 ms, posledni hodnota az po pusteni.
                    val now = SystemClock.uptimeMillis()
                    if (now - lastWrite >= 100L) {
                        lastWrite = now
                        commit(v)
                    }
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
                onSettingTouched(key, col)
            }
            override fun onStopTrackingTouch(sb: SeekBar) {
                commit(sb.progress * step + min)
            }
        })
        minus.setOnClickListener { val v = (current() - step).coerceAtLeast(min); seek.progress = (v - min) / step; commit(v); value.text = format(v); onSettingTouched(key, col) }
        plus.setOnClickListener { val v = (current() + step).coerceAtMost(max); seek.progress = (v - min) / step; commit(v); value.text = format(v); onSettingTouched(key, col) }
        seekRow.addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        seekRow.addView(plus)
        col.addView(seekRow, rowParams())
        box.addView(col, rowParams())
        // Zatmaveny posuvnik: nastaveni, ktere zvolene pismo nemeni, se zasedne a nereaguje.
        fun applyEnabled() {
            val on = enabledWhen?.invoke() ?: true
            col.alpha = if (on) 1f else 0.35f
            seek.isEnabled = on
            minus.isEnabled = on
            plus.isEnabled = on
        }
        applyEnabled()
        refreshers.add {
            val v = current()
            if (seek.progress != (v - min) / step) seek.progress = (v - min) / step
            value.text = format(v)
            applyEnabled()
        }
    }

    protected fun choiceRow(box: LinearLayout, labelRes: Int, key: String, values: List<String>, labels: List<String>) {
        choiceRow(box, labelRes, values, labels, { RustBridge.settings.json.optString(key) }, { update(key, it) }, key)
    }

    /** Volba z pilulek; `current` vraci vybranou hodnotu (nebo cokoli mimo `values` = nic vybrano), `onPick` ji zapise. */
    protected fun choiceRow(
        box: LinearLayout, labelRes: Int, values: List<String>, labels: List<String>,
        current: () -> String, onPick: (String) -> Unit, touchKey: String = "",
    ) {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(0, dp(10f), 0, dp(10f))
        col.addView(label(getString(labelRes)), rowParams())
        val row = LinearLayout(this)
        val pills = ArrayList<TextView>()
        fun paint() {
            val cur = current()
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
            p.setOnClickListener {
                onPick(values[i])
                if (touchKey.isNotEmpty()) onSettingTouched(touchKey, col)
            }
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

    /**
     * Rozbalovaci volba: radek ukazuje vybranou hodnotu a sipku; klepnuti rozvine cely seznam
     * (vybrana polozka v accent barve), vyber seznam zase sbali.
     */
    protected fun dropdownRow(
        box: LinearLayout, labelRes: Int, values: List<String>, labels: List<String>,
        current: () -> String, onPick: (String) -> Unit, touchKey: String = "",
    ) {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(0, dp(4f), 0, dp(6f))
        val head = LinearLayout(this)
        head.gravity = Gravity.CENTER_VERTICAL
        head.minimumHeight = dp(52f)
        head.addView(label(getString(labelRes)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val shown = label("", 15f, accent)
        head.addView(shown)
        val arrow = label("\u203A", 24f, dim)
        arrow.setPadding(dp(10f), 0, dp(4f), 0)
        head.addView(arrow)
        col.addView(head, rowParams())
        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        list.visibility = View.GONE
        val items = ArrayList<TextView>()
        fun paint() {
            val cur = current()
            val a = accent
            val onA = if (Theme.luminance(a) > 0.55f) 0xFF111113.toInt() else 0xFFFFFFFF.toInt()
            for ((i, p) in items.withIndex()) {
                val on = values[i] == cur
                p.background = rounded(if (on) a else field, dp(12f).toFloat())
                p.setTextColor(if (on) onA else text)
            }
            shown.text = values.indexOf(cur).let { if (it >= 0) labels[it] else "" }
        }
        fun open(on: Boolean) {
            list.visibility = if (on) View.VISIBLE else View.GONE
            arrow.text = if (on) "\u2304" else "\u203A"
        }
        for (i in values.indices) {
            val p = label(labels[i], 15f)
            p.setPadding(dp(14f), dp(11f), dp(14f), dp(11f))
            p.setOnClickListener {
                onPick(values[i])
                open(false)
                if (touchKey.isNotEmpty()) onSettingTouched(touchKey, col)
            }
            items.add(p)
            val lp = rowParams()
            lp.topMargin = dp(6f)
            list.addView(p, lp)
        }
        col.addView(list, rowParams())
        head.setOnClickListener {
            open(list.visibility != View.VISIBLE)
            if (touchKey.isNotEmpty()) onSettingTouched(touchKey, col)
        }
        box.addView(col, rowParams())
        paint()
        refreshers.add { paint() }
    }

    private fun hex(c: Int): String = String.format("#%06X", c and 0xFFFFFF)

    /** Barva, kterou radek ukazuje: u barev motivu ta skutecna (z Theme), jinak ulozena hodnota ("" = automaticka). */
    private fun shownColor(key: String): String {
        val t = Theme.from(RustBridge.settings)
        return when (key) {
            "custom_background" -> hex(t.background)
            "custom_key" -> hex(t.key)
            "custom_text" -> hex(t.text)
            else -> RustBridge.settings.json.optString(key)
        }
    }

    /** Barva, ktera se ukaze v kole u automaticke hodnoty (to, co se pouzije misto ni). */
    private fun autoColor(key: String): Int {
        val t = Theme.from(RustBridge.settings)
        return when (key) {
            "popup_color", "alts_color" -> Theme.autoPopup(t.key)
            "trail_color" -> t.accent
            "hint_color" -> t.hint
            "tap_text_color" -> t.accent
            "digit_key_color", "key_color2" -> t.key
            "background_color2" -> t.background
            "glow_color" -> t.accent
            "neon_color2", "outline_color", "outline_color2", "outline_tap_color", "outline_hover_color",
            "outline_passed_color", "trail_color2", "glow_color2", "tap_text_color2", "outline_tap_color2" -> t.accent
            else -> t.text
        }
    }

    /**
     * Zapis barvy. U barev motivu (pozadi, klavesy, text) se pri jinem motivu nez "custom" zapise jednim
     * volanim motiv "custom" se vsemi tremi aktualnimi (skutecnymi) barvami a upravenou barvou.
     */
    private fun writeColor(key: String, color: String) {
        val s = RustBridge.settings
        if (key in THEME_COLOR_KEYS && s.theme != "custom") {
            val t = Theme.from(s)
            val j = s.edit()
            j.put("theme", "custom")
            j.put("custom_background", hex(t.background))
            j.put("custom_key", hex(t.key))
            j.put("custom_text", hex(t.text))
            j.put(key, color)
            RustBridge.setSettings(j)
            onSettingUpdated(key, color)
        } else {
            update(key, color)
        }
    }

    /** Barevny radek: popisek a vpravo kulaty vzorek aktualni barvy; klepnuti otevre vyber barvy nahore. */
    protected fun colorRow(box: LinearLayout, labelRes: Int, key: String, allowAuto: Boolean = false, allowRainbow: Boolean = false, dependsOn: String = "") {
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.minimumHeight = dp(52f)
        row.addView(label(getString(labelRes)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val swatch = View(this)
        row.addView(swatch, LinearLayout.LayoutParams(dp(36f), dp(36f)))
        fun paint() {
            val cur = shownColor(key)
            val d = GradientDrawable()
            d.shape = GradientDrawable.OVAL
            if (cur == Theme.RAINBOW) {
                // Duha: kruh s kruhovym prechodem vsech odstinu.
                d.gradientType = GradientDrawable.SWEEP_GRADIENT
                d.colors = Theme.RAINBOW_COLORS + Theme.RAINBOW_COLORS[0]
                d.setStroke(dp(1.5f), 0x66FFFFFF)
            } else if (cur.isEmpty()) {
                // Automaticka barva: prazdny kruh s carkovanym obrysem.
                d.setColor(0)
                d.setStroke(dp(1.5f), 0x99FFFFFF.toInt(), dp(3f).toFloat(), dp(3f).toFloat())
            } else {
                d.setColor(Theme.parse(cur, 0xFF000000.toInt()))
                d.setStroke(dp(1.5f), 0x66FFFFFF)
            }
            swatch.background = d
        }
        // Jen kolecko s barvou otevre vyber barvy; klepnuti na popisek ukaze jen napovedu.
        row.setOnClickListener { onSettingTouched(key, row) }
        swatch.setOnClickListener {
            onSettingTouched(key, row)
            val cur = shownColor(key)
            val initial = if (cur.isEmpty() || cur == Theme.RAINBOW) autoColor(key) else Theme.parse(cur, 0xFFFFFFFF.toInt())
            showColorPicker(getString(labelRes), initial, allowAuto,
                onCommit = { c -> if (!hex(c).equals(shownColor(key), ignoreCase = true)) writeColor(key, hex(c)) },
                onAuto = { if (shownColor(key).isNotEmpty()) update(key, "") },
                onRainbow = if (allowRainbow) ({ if (shownColor(key) != Theme.RAINBOW) update(key, Theme.RAINBOW) }) else null,
                key = key)
        }
        box.addView(row, rowParams())
        // Radek zavisly na prepinaci je zasedly a nereaguje, dokud je ten vypnuty.
        fun applyEnabled() {
            val on = dependsOn.isEmpty() || RustBridge.settings.json.optBoolean(dependsOn)
            row.isEnabled = on
            swatch.isEnabled = on
            row.alpha = if (on) 1f else 0.4f
        }
        applyEnabled()
        paint()
        refreshers.add {
            paint()
            applyEnabled()
        }
    }

    /** Rada kulatych vzorku barev (klepnutim se otevre vyber barvy); zobrazuje se jen, kdyz `visible` vraci true. */
    protected fun colorDotsRow(box: LinearLayout, items: List<Pair<Int, String>>, visible: () -> Boolean) {
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(4f), 0, dp(12f))
        val dots = ArrayList<View>()
        for ((labelRes, key) in items) {
            val dot = View(this)
            val lp = LinearLayout.LayoutParams(dp(40f), dp(40f))
            lp.rightMargin = dp(14f)
            row.addView(dot, lp)
            dots.add(dot)
            dot.setOnClickListener {
                onSettingTouched(key, row)
                showColorPicker(getString(labelRes), Theme.parse(shownColor(key), 0xFFFFFFFF.toInt()), false,
                    onCommit = { c -> if (!hex(c).equals(shownColor(key), ignoreCase = true)) writeColor(key, hex(c)) },
                    onAuto = {})
            }
        }
        fun paint() {
            row.visibility = if (visible()) View.VISIBLE else View.GONE
            for ((i, d) in dots.withIndex()) {
                d.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Theme.parse(shownColor(items[i].second), 0xFF000000.toInt()))
                    setStroke(dp(1.5f), 0x66FFFFFF)
                }
            }
        }
        box.addView(row, rowParams())
        paint()
        refreshers.add { paint() }
    }

    /**
     * Vyber barvy: dialog nahore na obrazovce bez ztmaveni (nahled klavesnice dole zustava videt): ctverec
     * (sytost a jas) s pruhem odstinu, hex kod a tlacitko OK. Hodnota se zapisuje (`onCommit`) po zvednuti
     * prstu nebo po zadani platneho hex kodu; "Vychozi" (jen s `allowAuto`) zavola `onAuto` a dialog zavre.
     */
    private fun showColorPicker(titleText: String, initial: Int, allowAuto: Boolean, onCommit: (Int) -> Unit, onAuto: () -> Unit, onRainbow: (() -> Unit)? = null, key: String = "") {
        colorDialog?.dismiss()
        if (key.isNotEmpty()) onColorPickerState(key, true)
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16f), dp(14f), dp(16f), dp(12f))
        root.background = rounded(card, dp(16f).toFloat())
        root.addView(label(titleText, 15f, dim), rowParams())

        val plane = ColorPlaneView(this)
        plane.setColor(initial)
        val plp = rowParams()
        plp.topMargin = dp(6f)
        plp.height = dp(220f)
        root.addView(plane, plp)

        // Hex kod barvy: jde z nej kopirovat i do nej vlozit (bez #).
        val hex = android.widget.EditText(this)
        var hexBusy = false
        fun showHex(c: Int) {
            hexBusy = true
            hex.setText(String.format("%06x", c and 0xFFFFFF))
            hexBusy = false
        }
        hex.setTextColor(text)
        hex.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
        hex.typeface = Typeface.MONOSPACE
        hex.isSingleLine = true
        hex.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        hex.filters = arrayOf(android.text.InputFilter.LengthFilter(7))
        hex.contentDescription = getString(R.string.color_hex)
        hex.background = rounded(0xFF2A2A30.toInt(), dp(10f).toFloat())
        hex.setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
        hex.setSelectAllOnFocus(true)
        showHex(initial)

        fun dialogButton(target: LinearLayout, t: CharSequence, color: Int, outlined: Boolean, action: () -> Unit) {
            val b = label(t, 15f, color)
            b.gravity = Gravity.CENTER
            b.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            b.minHeight = dp(44f)
            b.minWidth = dp(72f)
            b.setPadding(dp(14f), 0, dp(14f), 0)
            b.background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(0)
                if (outlined) setStroke(dp(1.5f), color)
            }
            b.setOnClickListener { action() }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.leftMargin = dp(8f)
            target.addView(b, lp)
        }

        val bottom = LinearLayout(this)
        bottom.gravity = Gravity.CENTER_VERTICAL
        bottom.addView(hex, LinearLayout.LayoutParams(dp(130f), ViewGroup.LayoutParams.WRAP_CONTENT))
        bottom.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        dialogButton(bottom, getString(R.string.color_done), accent, true) { dialog.dismiss() }
        val blp = rowParams()
        blp.topMargin = dp(10f)
        root.addView(bottom, blp)

        if (allowAuto || onRainbow != null) {
            val extra = LinearLayout(this)
            extra.gravity = Gravity.CENTER_VERTICAL or Gravity.END
            if (onRainbow != null) dialogButton(extra, getString(R.string.color_rainbow), Theme.RAINBOW_COLORS[1], false) {
                dialog.dismiss()
                onRainbow()
            }
            if (allowAuto) dialogButton(extra, getString(R.string.color_default), dim, false) {
                dialog.dismiss()
                onAuto()
            }
            val elp = rowParams()
            elp.topMargin = dp(4f)
            root.addView(extra, elp)
        }

        hex.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) {
                if (hexBusy) return
                val t = e.toString().trim().removePrefix("#")
                if (t.length != 6) return
                val v = t.toIntOrNull(16) ?: return
                val c = 0xFF000000.toInt() or v
                plane.setColor(c)
                onCommit(c)
            }
        })
        plane.onPicking = { c -> showHex(c) }
        plane.onCommit = { c ->
            showHex(c)
            onCommit(c)
        }

        dialog.setContentView(root)
        dialog.setOnDismissListener {
            if (colorDialog === dialog) colorDialog = null
            if (key.isNotEmpty()) onColorPickerState(key, false)
        }
        dialog.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(0))
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            val width = minOf(resources.displayMetrics.widthPixels - dp(32f), dp(380f))
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            val lp = w.attributes
            lp.y = dp(36f)
            w.attributes = lp
        }
        colorDialog = dialog
        dialog.show()
    }

    /** Vodorovny pruh hotovych stylu s miniaturami. */
    protected fun presetRow(box: LinearLayout) {
        box.addView(label(getString(R.string.styles), 15f).also { it.setPadding(0, dp(12f), 0, dp(8f)) }, rowParams())
        val scroll = HorizontalScrollView(this)
        scroll.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(this)
        val tiles = ArrayList<PresetTile>()
        // Styl je vybrany, kdyz se skutecne barvy (z Theme) shoduji s jeho barvami.
        fun isCurrent(p: Preset): Boolean {
            val t = Theme.from(RustBridge.settings)
            return t.background == Theme.parse(p.bg, 0) && t.key == Theme.parse(p.key, 0) &&
                t.text == Theme.parse(p.text, 0) && t.accent == Theme.parse(p.accent, 0)
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
                j.put("theme", "custom")
                j.put("accent_color", p.accent)
                j.put("custom_background", p.bg)
                j.put("custom_key", p.key)
                j.put("custom_text", p.text)
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
