package cz.reallyfast

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings as SystemSettings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Obrazovka nastaveni. Kazda zmena se hned zapise pres Rust do settings.json
 * (RustBridge.setSettings -> validace -> soubor + zaloha v SharedPreferences)
 * a bezici klavesnice ji prevezme bez restartu.
 */
class SettingsActivity : Activity() {

    private companion object {
        const val REQ_EXPORT = 1
        const val REQ_IMPORT = 2
        const val MAX_WORDS_SHOWN = 60
        val PALETTE = listOf(
            "#F59E0B", "#EF4444", "#EC4899", "#8B5CF6", "#3B82F6", "#06B6D4", "#10B981", "#84CC16", "#F4F4F5", "#111113",
        )
    }

    private val bg = 0xFF111113.toInt()
    private val card = 0xFF1B1B1F.toInt()
    private val text = 0xFFF4F4F5.toInt()
    private val dim = 0xFF9A9AA3.toInt()
    private val accent: Int get() = Theme.parse(RustBridge.settings.accentColor, 0xFFF59E0B.toInt())

    private lateinit var container: LinearLayout
    private var statusView: TextView? = null
    private var preview: KeyboardView? = null
    private var customColors: LinearLayout? = null
    private var wordsBox: LinearLayout? = null
    private var aboutView: TextView? = null
    private val refreshers = ArrayList<() -> Unit>()
    private val listener: () -> Unit = { onSettingsChanged() }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    // ------------------------------------------------------------------ zivotni cyklus

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RustBridge.init(this)
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(bg)
        scroll.fitsSystemWindows = true
        container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(dp(16f), dp(20f), dp(16f), dp(40f))
        scroll.addView(container, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)
        build()
        RustBridge.listeners.add(listener)
    }

    override fun onDestroy() {
        RustBridge.listeners.remove(listener)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        refreshWords()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) updateStatus()
    }

    private fun onSettingsChanged() {
        val s = RustBridge.settings
        preview?.let {
            it.theme = Theme.from(s)
            it.reloadLayout()
        }
        customColors?.visibility = if (s.theme == "custom") View.VISIBLE else View.GONE
        for (r in refreshers) r()
        aboutView?.text = RustBridge.info()
    }

    private fun update(key: String, value: Any) {
        val j = RustBridge.settings.edit()
        j.put(key, value)
        RustBridge.setSettings(j)
    }

    // ------------------------------------------------------------------ stavebni prvky

    private fun label(s: CharSequence, size: Float = 15f, color: Int = text): TextView = TextView(this).apply {
        this.text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
    }

    private fun section(titleRes: Int): LinearLayout {
        val title = label(getString(titleRes).uppercase(), 12f, dim)
        title.letterSpacing = 0.08f
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(26f)
        lp.bottomMargin = dp(8f)
        lp.leftMargin = dp(6f)
        container.addView(title, lp)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.background = rounded(card, dp(16f).toFloat())
        box.setPadding(dp(16f), dp(6f), dp(16f), dp(6f))
        container.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return box
    }

    private fun rowParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

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

    /** Posuvnik. `scale` = delitel (100 pro float hodnoty typu 0.5-1.5). */
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
                value.text = format(progress + min)
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}

            override fun onStopTrackingTouch(sb: SeekBar) {
                val v = sb.progress + min
                if (scale == 1) update(key, v) else update(key, v.toDouble() / scale)
            }
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
            for ((i, p) in pills.withIndex()) {
                val on = values[i] == cur
                p.background = rounded(if (on) accent else 0xFF26262B.toInt(), dp(12f).toFloat())
                p.setTextColor(if (on) 0xFF111113.toInt() else text)
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

    private fun buttonRow(box: LinearLayout, labelRes: Int, color: Int = text, onClick: () -> Unit) {
        val b = label(getString(labelRes), 15f, color)
        b.gravity = Gravity.CENTER_VERTICAL
        b.minimumHeight = dp(52f)
        b.setOnClickListener { onClick() }
        box.addView(b, rowParams())
    }

    // ------------------------------------------------------------------ obsah

    private fun build() {
        val title = label(getString(R.string.app_name), 30f)
        title.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        container.addView(title)
        container.addView(label(getString(R.string.settings_subtitle), 14f, dim))

        if (!RustBridge.isLoaded) {
            val warn = label(getString(R.string.core_missing), 14f, 0xFFEF4444.toInt())
            warn.setPadding(0, dp(12f), 0, 0)
            container.addView(warn)
        }

        // Aktivace
        var box = section(R.string.sec_setup)
        statusView = label("", 14f, dim).also {
            it.setPadding(0, dp(12f), 0, dp(4f))
            box.addView(it, rowParams())
        }
        buttonRow(box, R.string.setup_enable, accent) {
            startActivity(Intent(SystemSettings.ACTION_INPUT_METHOD_SETTINGS))
        }
        buttonRow(box, R.string.setup_select, accent) {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
        val test = EditText(this)
        test.hint = getString(R.string.test_hint)
        test.setHintTextColor(dim)
        test.setTextColor(text)
        test.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        test.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        test.background = rounded(0xFF26262B.toInt(), dp(12f).toFloat())
        test.setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
        val tlp = rowParams()
        tlp.topMargin = dp(6f)
        tlp.bottomMargin = dp(10f)
        box.addView(test, tlp)

        // Jazyk
        box = section(R.string.sec_language)
        choiceRow(box, R.string.language, "language", listOf("cs", "en", "cs+en"),
            listOf(getString(R.string.lang_cs), getString(R.string.lang_en), getString(R.string.lang_both)))
        choiceRow(box, R.string.layout, "layout", listOf("qwertz", "qwerty"), listOf("QWERTZ", "QWERTY"))

        // Psani
        box = section(R.string.sec_typing)
        switchRow(box, R.string.autocorrect, "autocorrect")
        switchRow(box, R.string.predictions, "predictions")
        switchRow(box, R.string.show_suggestions, "show_suggestions")
        switchRow(box, R.string.auto_capitalize, "auto_capitalize")
        switchRow(box, R.string.double_space_to_period, "double_space_to_period")
        switchRow(box, R.string.learn_from_typing, "learn_from_typing")
        switchRow(box, R.string.show_number_row, "show_number_row")

        // Swipe
        box = section(R.string.sec_swipe)
        switchRow(box, R.string.swipe_enabled, "swipe_enabled")
        sliderRow(box, R.string.swipe_sensitivity, "swipe_sensitivity", 50, 150, 100) { String.format("%.2f", it / 100f) }

        // Vzhled
        box = section(R.string.sec_look)
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
        box.addView(label(getString(R.string.preview), 13f, dim).also { it.setPadding(0, dp(8f), 0, dp(6f)) }, rowParams())
        val kv = KeyboardView(this)
        kv.preview = true
        kv.desiredHeight = dp(200f)
        kv.theme = Theme.from(RustBridge.settings)
        val plp = rowParams()
        plp.bottomMargin = dp(10f)
        box.addView(kv, plp)
        preview = kv
        sliderRow(box, R.string.key_height, "key_height_dp", 48, 72, 1) { getString(R.string.unit_dp, it) }
        sliderRow(box, R.string.keyboard_height, "keyboard_height_percent", 30, 50, 1) { getString(R.string.unit_percent, it) }

        // Odezva
        box = section(R.string.sec_feedback)
        sliderRow(box, R.string.vibration, "vibration_ms", 0, 50, 1) {
            if (it == 0) getString(R.string.unit_off) else getString(R.string.unit_ms, it)
        }
        switchRow(box, R.string.sound_enabled, "sound_enabled")
        sliderRow(box, R.string.long_press_delay, "long_press_delay_ms", 150, 500, 1) { getString(R.string.unit_ms, it) }

        // Gesta
        box = section(R.string.sec_gestures)
        switchRow(box, R.string.gesture_delete_word, "gesture_delete_word")
        switchRow(box, R.string.gesture_space_cursor, "gesture_space_cursor")
        switchRow(box, R.string.gesture_swipe_up_caps, "gesture_swipe_up_caps")
        switchRow(box, R.string.gesture_swipe_down_hide, "gesture_swipe_down_hide")
        switchRow(box, R.string.gesture_pinch_resize, "gesture_pinch_resize")

        // Schranka
        box = section(R.string.sec_clipboard)
        switchRow(box, R.string.clipboard_enabled, "clipboard_enabled")
        sliderRow(box, R.string.clipboard_ttl, "clipboard_ttl_minutes", 5, 240, 1) { getString(R.string.unit_minutes, it) }
        buttonRow(box, R.string.clipboard_clear, 0xFFEF4444.toInt()) {
            RustBridge.clipClear()
            Toast.makeText(this, R.string.clipboard_cleared, Toast.LENGTH_SHORT).show()
        }

        // Osobni slovnik
        box = section(R.string.sec_dictionary)
        val words = LinearLayout(this)
        words.orientation = LinearLayout.VERTICAL
        box.addView(words, rowParams())
        wordsBox = words
        buttonRow(box, R.string.dictionary_export, accent) {
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "application/json"
            i.putExtra(Intent.EXTRA_TITLE, "reallyfast-slovnik.json")
            startActivityForResult(i, REQ_EXPORT)
        }
        buttonRow(box, R.string.dictionary_import, accent) {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "*/*"
            startActivityForResult(i, REQ_IMPORT)
        }
        buttonRow(box, R.string.dictionary_clear, 0xFFEF4444.toInt()) {
            AlertDialog.Builder(this)
                .setMessage(R.string.dictionary_clear_confirm)
                .setPositiveButton(R.string.delete) { _, _ ->
                    RustBridge.clearUserDictionary()
                    refreshWords()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        // O aplikaci
        box = section(R.string.sec_about)
        box.addView(label(getString(R.string.about_privacy), 14f).also { it.setPadding(0, dp(12f), 0, dp(8f)) }, rowParams())
        aboutView = label(RustBridge.info(), 12f, dim).also {
            it.setPadding(0, 0, 0, dp(12f))
            box.addView(it, rowParams())
        }

        updateStatus()
        refreshWords()
    }

    private fun updateStatus() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = try {
            imm.enabledInputMethodList.any { it.packageName == packageName }
        } catch (t: Throwable) {
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

    private fun refreshWords() {
        val box = wordsBox ?: return
        box.removeAllViews()
        val list = RustBridge.userWords()
        val head = label(
            if (list.isEmpty()) getString(R.string.dictionary_empty) else getString(R.string.dictionary_count, list.size),
            14f, dim,
        )
        head.setPadding(0, dp(12f), 0, dp(6f))
        box.addView(head, rowParams())
        for (w in list.take(MAX_WORDS_SHOWN)) {
            val row = LinearLayout(this)
            row.gravity = Gravity.CENTER_VERTICAL
            row.addView(label(w.word), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(label("${w.count}×", 13f, dim))
            val del = IconView(this, IconView.CLOSE)
            del.color = dim
            del.contentDescription = getString(R.string.delete)
            del.setOnClickListener {
                RustBridge.deleteUserWord(w.word)
                refreshWords()
            }
            row.addView(del, LinearLayout.LayoutParams(dp(44f), dp(44f)))
            box.addView(row, rowParams())
        }
        if (list.size > MAX_WORDS_SHOWN) {
            box.addView(label(getString(R.string.dictionary_show_more, MAX_WORDS_SHOWN), 13f, dim), rowParams())
        }
        aboutView?.text = RustBridge.info()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        try {
            when (requestCode) {
                REQ_EXPORT -> {
                    contentResolver.openOutputStream(uri)?.use {
                        it.write(RustBridge.exportUserDictionary().toByteArray(Charsets.UTF_8))
                    }
                    Toast.makeText(this, R.string.dictionary_exported, Toast.LENGTH_SHORT).show()
                }
                REQ_IMPORT -> {
                    val json = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                    val n = RustBridge.importUserDictionary(json)
                    val msg = if (n >= 0) getString(R.string.dictionary_imported, n) else getString(R.string.dictionary_import_failed)
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    refreshWords()
                }
            }
        } catch (t: Throwable) {
            Toast.makeText(this, R.string.dictionary_import_failed, Toast.LENGTH_SHORT).show()
        }
    }
}
