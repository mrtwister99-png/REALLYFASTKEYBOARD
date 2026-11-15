package cz.reallyfast

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.provider.Settings as SystemSettings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/** Klavesnice zada o povoleni mikrofonu (sluzba o nej pozadat nemuze). */
const val EXTRA_REQUEST_MIC = "request_mic"

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
 * Obrazovka nastaveni. Kazda zmena se hned zapise pres Rust do settings.json
 * (RustBridge.setSettings -> validace -> soubor + zaloha v SharedPreferences)
 * a bezici klavesnice ji prevezme bez restartu.
 *
 * Dole je pripnuty zivy nahled klavesnice ve skutecne velikosti - kazda zmena je hned videt.
 */
class SettingsActivity : BaseScreen() {

    private companion object {
        const val REQ_MIC = 3
        const val REQ_BACKUP_EXPORT = 10
        const val REQ_BACKUP_IMPORT = 11
        const val REQ_SWIPE_LOG_EXPORT = 12
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

    private lateinit var container: LinearLayout
    private var statusView: TextView? = null
    private var preview: KeyboardView? = null
    private var previewBox: LinearLayout? = null
    private var customColors: LinearLayout? = null
    private var aboutView: TextView? = null
    private var swipeLogButton: TextView? = null
    private val refreshers = ArrayList<() -> Unit>()
    private val listener: () -> Unit = { onSettingsChanged() }

    // ------------------------------------------------------------------ zivotni cyklus

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RustBridge.init(this)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(bg)
        root.fitsSystemWindows = true

        val scroll = ScrollView(this)
        container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(dp(16f), dp(20f), dp(16f), dp(28f))
        scroll.addView(container, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Zivy nahled: pripnuty dole, ve skutecne velikosti klavesnice.
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val caption = label(getString(R.string.preview).uppercase(), 11f, dim)
        caption.letterSpacing = 0.08f
        caption.setPadding(dp(22f), dp(8f), 0, dp(6f))
        box.addView(caption)
        val kv = KeyboardView(this)
        kv.preview = true
        box.addView(kv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        preview = kv
        previewBox = box
        updatePreview()

        // Kdyz je otevrena skutecna klavesnice (zkusebni pole), nahled se schova - je videt ta prava.
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                box.visibility = if (insets.isVisible(WindowInsets.Type.ime())) View.GONE else View.VISIBLE
                v.onApplyWindowInsets(insets)
            }
        }

        setContentView(root)
        build()
        RustBridge.listeners.add(listener)
        if (intent?.getBooleanExtra(EXTRA_REQUEST_MIC, false) == true) ensureMicrophone()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra(EXTRA_REQUEST_MIC, false) == true) ensureMicrophone()
    }

    private fun ensureMicrophone() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.voice_permission, Toast.LENGTH_SHORT).show()
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
    }

    override fun onDestroy() {
        RustBridge.listeners.remove(listener)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        aboutView?.text = RustBridge.info()
        updateSwipeLogButton()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) updateStatus()
    }

    /** Nahled ukazuje presne to, co klavesnice: tema, vysku, radu cislic, zaobleni, napovedy... */
    private fun updatePreview() {
        val kv = preview ?: return
        val s = RustBridge.settings
        val theme = Theme.from(s)
        kv.theme = theme
        // Nejvyse 45 % obrazovky, aby zbylo misto na nastaveni.
        val real = keyboardKeysHeight(resources, s, dp(44f))
        kv.desiredHeight = minOf(real, (resources.displayMetrics.heightPixels * 0.45f).toInt())
        kv.reloadLayout()
        previewBox?.setBackgroundColor(theme.background)
    }

    private fun onSettingsChanged() {
        updatePreview()
        customColors?.visibility = if (RustBridge.settings.theme == "custom") View.VISIBLE else View.GONE
        for (r in refreshers) r()
        aboutView?.text = RustBridge.info()
    }

    private fun update(key: String, value: Any) {
        val j = RustBridge.settings.edit()
        j.put(key, value)
        RustBridge.setSettings(j)
        if (key == "voice_input" && value == true) ensureMicrophone()
        // Nahled neni interaktivni, tak barvy ukazeme ukazkou (listener uz nahled prekreslil).
        if (key == "neon_color" || key == "key_glow") preview?.demoNeon()
        if (key == "glow_color" || key == "swipe_glow") preview?.glowWord("ahoj")
    }

    // ------------------------------------------------------------------ stavebni prvky

    private fun switchRow(box: LinearLayout, labelRes: Int, key: String) {
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.minimumHeight = dp(52f)
        row.addView(label(getString(labelRes)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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
    private fun sliderRow(box: LinearLayout, labelRes: Int, key: String, min: Int, max: Int, scale: Int, format: (Int) -> String) {
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

    private fun choiceRow(box: LinearLayout, labelRes: Int, key: String, values: List<String>, labels: List<String>) {
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

    private fun colorRow(box: LinearLayout, labelRes: Int, key: String) {
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
    private fun presetRow(box: LinearLayout) {
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

    // ------------------------------------------------------------------ obsah

    private fun build() {
        title(container, getString(R.string.app_name), back = false)
        container.addView(label(getString(R.string.settings_subtitle), 14f, dim))

        if (!RustBridge.isLoaded) {
            val warn = label(getString(R.string.core_missing), 14f, danger)
            warn.setPadding(0, dp(12f), 0, 0)
            container.addView(warn)
        }

        // Aktivace
        var box = section(container, getString(R.string.sec_setup))
        statusView = label("", 14f, dim).also {
            it.setPadding(0, dp(12f), 0, dp(4f))
            box.addView(it, rowParams())
        }
        buttonRow(box, getString(R.string.setup_enable), accent) {
            startActivity(Intent(SystemSettings.ACTION_INPUT_METHOD_SETTINGS))
        }
        buttonRow(box, getString(R.string.setup_select), accent) {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
        val test = EditText(this)
        test.hint = getString(R.string.test_hint)
        test.setHintTextColor(dim)
        test.setTextColor(text)
        test.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        test.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        test.background = rounded(field, dp(12f).toFloat())
        test.setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
        val tlp = rowParams()
        tlp.topMargin = dp(6f)
        tlp.bottomMargin = dp(10f)
        box.addView(test, tlp)

        // Slovnik a makra: samostatne obrazovky
        box = cardBox(container).also { (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(26f) }
        navRow(box, getString(R.string.dictionary_open)) { startActivity(Intent(this, DictionaryActivity::class.java)) }
        navRow(box, getString(R.string.macros_open)) { startActivity(Intent(this, MacrosActivity::class.java)) }
        navRow(box, getString(R.string.layout_open)) { startActivity(Intent(this, LayoutActivity::class.java)) }
        navRow(box, getString(R.string.voice_open)) { startActivity(Intent(this, VoiceActivity::class.java)) }

        // Zaloha a ladeni
        box = section(container, getString(R.string.sec_backup))
        buttonRow(box, getString(R.string.backup_export), accent) {
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "application/json"
            i.putExtra(Intent.EXTRA_TITLE, "reallyfast-zaloha.json")
            @Suppress("DEPRECATION") startActivityForResult(i, REQ_BACKUP_EXPORT)
        }
        buttonRow(box, getString(R.string.backup_import), accent) {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "*/*"
            @Suppress("DEPRECATION") startActivityForResult(i, REQ_BACKUP_IMPORT)
        }
        hintText(box, getString(R.string.backup_hint))
        switchRow(box, R.string.swipe_log, "swipe_log")
        hintText(box, getString(R.string.swipe_log_hint))
        swipeLogButton = buttonRow(box, getString(R.string.swipe_log_export, RustBridge.swipeLogCount()), accent) {
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "application/json"
            i.putExtra(Intent.EXTRA_TITLE, "reallyfast-tahy.json")
            @Suppress("DEPRECATION") startActivityForResult(i, REQ_SWIPE_LOG_EXPORT)
        }
        buttonRow(box, getString(R.string.swipe_log_clear), danger) {
            AlertDialog.Builder(this)
                .setMessage(R.string.swipe_log_clear_confirm)
                .setPositiveButton(R.string.delete) { _, _ ->
                    RustBridge.clearSwipeLog()
                    updateSwipeLogButton()
                    Toast.makeText(this, R.string.swipe_log_cleared, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        // Jazyk
        box = section(container, getString(R.string.sec_language))
        choiceRow(box, R.string.language, "language", listOf("cs", "en", "cs+en"),
            listOf(getString(R.string.lang_cs), getString(R.string.lang_en), getString(R.string.lang_both)))
        choiceRow(box, R.string.layout, "layout", listOf("qwertz", "qwerty"), listOf("QWERTZ", "QWERTY"))

        // Psani
        box = section(container, getString(R.string.sec_typing))
        switchRow(box, R.string.autocorrect, "autocorrect")
        switchRow(box, R.string.predictions, "predictions")
        switchRow(box, R.string.show_suggestions, "show_suggestions")
        switchRow(box, R.string.auto_capitalize, "auto_capitalize")
        switchRow(box, R.string.double_space_to_period, "double_space_to_period")
        switchRow(box, R.string.learn_from_typing, "learn_from_typing")
        switchRow(box, R.string.show_number_row, "show_number_row")

        // Swipe
        box = section(container, getString(R.string.sec_swipe))
        switchRow(box, R.string.swipe_enabled, "swipe_enabled")
        sliderRow(box, R.string.swipe_sensitivity, "swipe_sensitivity", 50, 150, 100) { String.format(Locale.getDefault(), "%.2f", it / 100f) }
        switchRow(box, R.string.swipe_trail, "swipe_trail")
        switchRow(box, R.string.swipe_glow, "swipe_glow")
        colorRow(box, R.string.glow_color, "glow_color")
        switchRow(box, R.string.swipe_adapt, "swipe_adapt")
        buttonRow(box, getString(R.string.swipe_adapt_reset), danger) {
            RustBridge.resetCalibration()
            Toast.makeText(this, R.string.swipe_adapt_reset_done, Toast.LENGTH_SHORT).show()
        }

        // Vzhled
        box = section(container, getString(R.string.sec_look))
        presetRow(box)
        choiceRow(box, R.string.theme, "theme", listOf("dark", "light", "oled_black", "custom"),
            listOf(getString(R.string.theme_dark), getString(R.string.theme_light), getString(R.string.theme_oled), getString(R.string.theme_custom)))
        colorRow(box, R.string.accent_color, "accent_color")
        val custom = LinearLayout(this)
        custom.orientation = LinearLayout.VERTICAL
        colorRow(custom, R.string.custom_background, "custom_background")
        colorRow(custom, R.string.custom_key, "custom_key")
        colorRow(custom, R.string.custom_text, "custom_text")
        custom.visibility = if (RustBridge.settings.theme == "custom") View.VISIBLE else View.GONE
        box.addView(custom, rowParams())
        customColors = custom
        sliderRow(box, R.string.key_height, "key_height_dp", 48, 72, 1) { getString(R.string.unit_dp, it) }
        sliderRow(box, R.string.keyboard_height, "keyboard_height_percent", 30, 50, 1) { getString(R.string.unit_percent, it) }
        sliderRow(box, R.string.key_radius, "key_radius_dp", 2, 22, 1) { getString(R.string.unit_dp, it) }
        switchRow(box, R.string.key_popup, "key_popup")
        switchRow(box, R.string.key_hints, "key_hints")
        switchRow(box, R.string.key_shadow, "key_shadow")
        switchRow(box, R.string.animations, "animations")
        switchRow(box, R.string.key_glow, "key_glow")
        colorRow(box, R.string.neon_color, "neon_color")

        // Odezva
        box = section(container, getString(R.string.sec_feedback))
        sliderRow(box, R.string.vibration, "vibration_ms", 0, 50, 1) {
            if (it == 0) getString(R.string.unit_off) else getString(R.string.unit_ms, it)
        }
        switchRow(box, R.string.sound_enabled, "sound_enabled")
        sliderRow(box, R.string.long_press_delay, "long_press_delay_ms", 150, 500, 1) { getString(R.string.unit_ms, it) }

        // Gesta
        box = section(container, getString(R.string.sec_gestures))
        switchRow(box, R.string.gesture_delete_word, "gesture_delete_word")
        switchRow(box, R.string.gesture_space_cursor, "gesture_space_cursor")
        switchRow(box, R.string.gesture_swipe_up_caps, "gesture_swipe_up_caps")
        switchRow(box, R.string.gesture_swipe_down_hide, "gesture_swipe_down_hide")
        switchRow(box, R.string.gesture_pinch_resize, "gesture_pinch_resize")

        // Schranka
        box = section(container, getString(R.string.sec_clipboard))
        switchRow(box, R.string.clipboard_enabled, "clipboard_enabled")
        sliderRow(box, R.string.clipboard_ttl, "clipboard_ttl_minutes", 5, 240, 1) { getString(R.string.unit_minutes, it) }
        buttonRow(box, getString(R.string.clipboard_clear), danger) {
            RustBridge.clipClear()
            Toast.makeText(this, R.string.clipboard_cleared, Toast.LENGTH_SHORT).show()
        }

        // O aplikaci
        box = section(container, getString(R.string.sec_about))
        box.addView(label(getString(R.string.about_privacy), 14f).also { it.setPadding(0, dp(12f), 0, dp(8f)) }, rowParams())
        aboutView = label(RustBridge.info(), 12f, dim).also {
            it.setPadding(0, 0, 0, dp(12f))
            box.addView(it, rowParams())
        }

        updateStatus()
    }

    private fun updateSwipeLogButton() {
        swipeLogButton?.text = getString(R.string.swipe_log_export, RustBridge.swipeLogCount())
    }

    @Suppress("DEPRECATION") // bez AndroidX neni jina cesta k vysledku aktivity
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        try {
            when (requestCode) {
                REQ_BACKUP_EXPORT -> {
                    contentResolver.openOutputStream(uri)?.use {
                        it.write(RustBridge.exportBackup().toByteArray(Charsets.UTF_8))
                    }
                    Toast.makeText(this, R.string.backup_exported, Toast.LENGTH_SHORT).show()
                }
                REQ_BACKUP_IMPORT -> {
                    val json = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                    confirmRestore(json)
                }
                REQ_SWIPE_LOG_EXPORT -> {
                    contentResolver.openOutputStream(uri)?.use {
                        it.write(RustBridge.exportSwipeLog().toByteArray(Charsets.UTF_8))
                    }
                    Toast.makeText(this, R.string.swipe_log_exported, Toast.LENGTH_SHORT).show()
                }
            }
        } catch (_: Throwable) {
            val msg = if (requestCode == REQ_BACKUP_IMPORT) R.string.backup_restore_failed else R.string.backup_export_failed
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    /** Obnova prepise nastaveni, proto se pred ni zeptame. Po uspechu se obrazovka postavi znovu. */
    private fun confirmRestore(json: String) {
        AlertDialog.Builder(this)
            .setMessage(R.string.backup_restore_confirm)
            .setPositiveButton(R.string.backup_restore) { _, _ ->
                val n = RustBridge.importBackup(json)
                if (n >= 0) {
                    Toast.makeText(this, getString(R.string.backup_restored, n), Toast.LENGTH_SHORT).show()
                    recreate()
                } else {
                    Toast.makeText(this, R.string.backup_restore_failed, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateStatus() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = try {
            imm.enabledInputMethodList.any { it.packageName == packageName }
        } catch (_: Throwable) {
            false
        }
        val current = SystemSettings.Secure.getString(contentResolver, SystemSettings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        val active = current.startsWith("$packageName/")
        statusView?.setText(
            when {
                active -> R.string.setup_status_active
                enabled -> R.string.setup_status_enabled
                else -> R.string.setup_status_disabled
            }
        )
        statusView?.setTextColor(if (active) 0xFF10B981.toInt() else dim)
    }
}
