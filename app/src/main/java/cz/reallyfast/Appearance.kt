package cz.reallyfast

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/**
 * Vzhled a rozlozeni. Dole je pripnuty zivy nahled klavesnice ve skutecne velikosti.
 * Zalozka "Vzhled": barvy, tvar a efekty (nahled neni interaktivni).
 * Zalozka "Jazyk a rozlozeni": jazyk, spodni rada a znaky na klavesach; klepnuti na klavesu
 * v nahledu otevre volbu znaku. Zmena se ulozi do nastaveni (`key_map`, `key_alts`)
 * a Rust ji pouzije v rozlozeni i v swipe decoderu.
 */
class AppearanceActivity : SettingsScreen() {

    private lateinit var preview: KeyboardView
    private lateinit var previewBox: LinearLayout
    private lateinit var lookTab: LinearLayout
    private lateinit var layoutTab: LinearLayout
    private var customColors: LinearLayout? = null
    private var statusView: TextView? = null
    private var cancelSwapView: TextView? = null
    private val tabPills = ArrayList<TextView>()
    private val pills = ArrayList<TextView>()
    private val hintPills = ArrayList<TextView>()
    private var shownMode = KeyboardView.MODE_LETTERS
    private var tab = 0

    /** Cekajici prohozeni: puvodni kod pozice prvni klavesy a znak, ktery na ni ted je. */
    private var pendingSrc = -1
    private var pendingChar = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(bg)
        root.fitsSystemWindows = true

        val scroll = ScrollView(this)
        val container = contentColumn()
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

        // Kdyz je otevrena skutecna klavesnice, nahled se schova - je videt ta prava.
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                box.visibility = if (insets.isVisible(WindowInsets.Type.ime())) View.GONE else View.VISIBLE
                v.onApplyWindowInsets(insets)
            }
        }

        setContentView(root)
        build(container)
        showTab(0)
    }

    // ------------------------------------------------------------------ nahled a zalozky

    /** Nahled ukazuje presne to, co klavesnice: tema, vysku, radu cislic, zaobleni, znaky... */
    private fun updatePreview() {
        val s = RustBridge.settings
        val theme = Theme.from(s)
        preview.theme = theme
        // Nejvyse 45 % obrazovky, aby zbylo misto na nastaveni.
        val real = keyboardKeysHeight(resources, s, dp(44f))
        preview.desiredHeight = minOf(real, (resources.displayMetrics.heightPixels * 0.45f).toInt())
        preview.reloadLayout()
        previewBox.setBackgroundColor(theme.background)
    }

    override fun onSettingsChanged() {
        updatePreview()
        customColors?.visibility = if (RustBridge.settings.theme == "custom") View.VISIBLE else View.GONE
        updateStatus()
        paintPills()
    }

    override fun onSettingUpdated(key: String, value: Any) {
        // Nahled neni na zalozce Vzhled interaktivni, tak barvy ukazeme ukazkou.
        if (key == "neon_color" || key == "key_glow") preview.demoNeon()
    }

    private fun showTab(i: Int) {
        if (i == 0 && tab == 1) {
            cancelSwap()
            shownMode = KeyboardView.MODE_LETTERS
            preview.setMode(KeyboardView.MODE_LETTERS)
        }
        tab = i
        lookTab.visibility = if (i == 0) View.VISIBLE else View.GONE
        layoutTab.visibility = if (i == 1) View.VISIBLE else View.GONE
        preview.onKeyPick = if (i == 1) { key -> onPick(key) } else null
        paintPills()
    }

    // ------------------------------------------------------------------ obsah

    private fun build(container: LinearLayout) {
        title(container, getString(R.string.appearance_open), back = true)

        val tabs = LinearLayout(this)
        val tabNames = listOf(R.string.tab_look, R.string.tab_layout)
        for ((i, n) in tabNames.withIndex()) {
            val p = label(getString(n), 14f)
            p.gravity = Gravity.CENTER
            p.setPadding(dp(4f), dp(10f), dp(4f), dp(10f))
            p.setOnClickListener { showTab(i) }
            tabPills.add(p)
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (i > 0) lp.leftMargin = dp(6f)
            tabs.addView(p, lp)
        }
        val tlp = rowParams()
        tlp.topMargin = dp(14f)
        container.addView(tabs, tlp)

        lookTab = LinearLayout(this)
        lookTab.orientation = LinearLayout.VERTICAL
        container.addView(lookTab, rowParams())
        layoutTab = LinearLayout(this)
        layoutTab.orientation = LinearLayout.VERTICAL
        container.addView(layoutTab, rowParams())

        buildLook(lookTab)
        buildLayout(layoutTab)
    }

    private fun buildLook(tabBox: LinearLayout) {
        val box = cardBox(tabBox).also { (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(18f) }
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
    }

    private fun buildLayout(tabBox: LinearLayout) {
        // Jazyk
        var box = section(tabBox, getString(R.string.sec_language))
        choiceRow(box, R.string.language, "language", listOf("cs", "en", "cs+en"),
            listOf(getString(R.string.lang_cs), getString(R.string.lang_en), getString(R.string.lang_both)))
        choiceRow(box, R.string.layout, "layout", listOf("qwertz", "qwerty"), listOf("QWERTZ", "QWERTY"))
        switchRow(box, R.string.show_number_row, "show_number_row")

        // Spodni rada
        box = section(tabBox, getString(R.string.sec_bottom_row))
        val places = listOf("left", "right", "hidden")
        val placeLabels = listOf(getString(R.string.key_place_left), getString(R.string.key_place_right), getString(R.string.key_place_hidden))
        choiceRow(box, R.string.emoji_key_label, "emoji_key", places, placeLabels)
        choiceRow(box, R.string.macro_key_label, "macro_key", places, placeLabels)
        hintText(box, getString(R.string.bottom_row_hint))

        // Klavesy
        box = section(tabBox, getString(R.string.sec_keys))
        box.addView(
            label(getString(R.string.layout_hint), 14f, dim).also { it.setPadding(0, dp(10f), 0, dp(4f)) },
            rowParams(),
        )

        // Rezim nahledu: pismena / symboly / dalsi symboly
        val modes = listOf(
            KeyboardView.MODE_LETTERS to R.string.layout_mode_letters,
            KeyboardView.MODE_SYMBOLS to R.string.layout_mode_symbols,
            KeyboardView.MODE_SYMBOLS2 to R.string.layout_mode_symbols2,
        )
        val row = LinearLayout(this)
        for ((i, m) in modes.withIndex()) {
            val p = label(getString(m.second), 14f)
            p.gravity = Gravity.CENTER
            p.setPadding(dp(4f), dp(10f), dp(4f), dp(10f))
            p.setOnClickListener {
                shownMode = m.first
                preview.setMode(m.first)
                paintPills()
            }
            pills.add(p)
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (i > 0) lp.leftMargin = dp(6f)
            row.addView(p, lp)
        }
        val rlp = rowParams()
        rlp.topMargin = dp(10f)
        rlp.bottomMargin = dp(6f)
        box.addView(row, rlp)

        // Poloha male napovedy: dva radky po trech.
        box.addView(
            label(getString(R.string.layout_hint_pos), 14f, dim).also { it.setPadding(0, dp(8f), 0, dp(2f)) },
            rowParams(),
        )
        for (r in 0 until 2) {
            val hr = LinearLayout(this)
            for (c in 0 until 3) {
                val idx = r * 3 + c
                val p = label(getString(HINT_LABELS[idx]), 14f)
                p.gravity = Gravity.CENTER
                p.setPadding(dp(4f), dp(10f), dp(4f), dp(10f))
                p.setOnClickListener { update("key_hint_pos", HINT_POS[idx]) }
                hintPills.add(p)
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                if (c > 0) lp.leftMargin = dp(6f)
                hr.addView(p, lp)
            }
            val hlp = rowParams()
            hlp.topMargin = dp(6f)
            box.addView(hr, hlp)
        }

        statusView = label("", 14f, dim).also {
            it.setPadding(0, dp(10f), 0, dp(6f))
            box.addView(it, rowParams())
        }
        cancelSwapView = buttonRow(box, getString(R.string.layout_swap_cancel), accent) { cancelSwap() }
        cancelSwapView?.visibility = View.GONE
        buttonRow(box, getString(R.string.layout_reset), danger) {
            AlertDialog.Builder(this)
                .setMessage(R.string.layout_reset_confirm)
                .setPositiveButton(R.string.ok) { _, _ ->
                    cancelSwap()
                    writeMaps(LinkedHashMap(), LinkedHashMap())
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        updateStatus()
    }

    private fun paintPills() {
        val a = accent
        val onA = if (Theme.luminance(a) > 0.55f) 0xFF111113.toInt() else 0xFFFFFFFF.toInt()
        fun paint(p: TextView, on: Boolean) {
            p.background = rounded(if (on) a else field, dp(12f).toFloat())
            p.setTextColor(if (on) onA else text)
        }
        for ((i, p) in tabPills.withIndex()) paint(p, i == tab)
        val order = listOf(KeyboardView.MODE_LETTERS, KeyboardView.MODE_SYMBOLS, KeyboardView.MODE_SYMBOLS2)
        for ((i, p) in pills.withIndex()) paint(p, order[i] == shownMode)
        val cur = RustBridge.settings.keyHintPos
        for ((i, p) in hintPills.withIndex()) paint(p, HINT_POS[i] == cur)
    }

    private fun updateStatus() {
        val swapping = pendingSrc >= 0
        statusView?.text = if (swapping) {
            getString(R.string.layout_status_swap, pendingChar)
        } else {
            val n = (readMap().keys + readAlts().keys).size
            if (n == 0) getString(R.string.layout_status_none) else getString(R.string.layout_status_changed, n)
        }
        cancelSwapView?.visibility = if (swapping) View.VISIBLE else View.GONE
    }

    private fun cancelSwap() {
        pendingSrc = -1
        pendingChar = ""
        preview.markedSrc = Int.MIN_VALUE
        updateStatus()
    }

    // ------------------------------------------------------------------ mapa klaves

    private fun readMap(): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        val o = RustBridge.settings.json.optJSONObject("key_map") ?: return out
        for (k in o.keys()) out[k] = o.optString(k)
        return out
    }

    private fun readAlts(): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        val o = RustBridge.settings.json.optJSONObject("key_alts") ?: return out
        for (k in o.keys()) out[k] = o.optString(k)
        return out
    }

    /** Obe mapy se zapisuji jednim volanim, aby se rozlozeni prepocitalo jen jednou. */
    private fun writeMaps(map: Map<String, String>, alts: Map<String, String>) {
        val obj = JSONObject()
        for ((k, v) in map) if (k != v) obj.put(k, v)
        val altsObj = JSONObject()
        for ((k, v) in alts) altsObj.put(k, v)
        val j = RustBridge.settings.edit()
        j.put("key_map", obj)
        j.put("key_alts", altsObj)
        RustBridge.setSettings(j)
    }

    private fun srcString(key: KeyboardView.Key): String = String(Character.toChars(key.src))

    // ------------------------------------------------------------------ vyber klavesy

    private fun onPick(key: KeyboardView.Key) {
        if (key.kind != KeyboardView.KIND_CHAR) {
            Toast.makeText(this, R.string.layout_not_editable, Toast.LENGTH_SHORT).show()
            return
        }
        if (pendingSrc >= 0) {
            if (key.src != pendingSrc) {
                val map = readMap()
                map[String(Character.toChars(pendingSrc))] = key.label
                map[srcString(key)] = pendingChar
                cancelSwap()
                writeMaps(map, readAlts())
            } else {
                cancelSwap() // klepnuti na tu samou klavesu = zrusit
            }
            return
        }
        askChar(key)
    }

    private fun askChar(key: KeyboardView.Key) {
        val et = EditText(this)
        et.setTextColor(text)
        et.setHintTextColor(dim)
        et.hint = getString(R.string.layout_char_hint)
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        et.setSingleLine(true)
        et.setText(key.label)
        et.selectAll()
        val alts = EditText(this)
        alts.setTextColor(text)
        alts.setHintTextColor(dim)
        alts.hint = getString(R.string.layout_alts_hint)
        alts.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        alts.setSingleLine(true)
        alts.setText(key.alts)
        val form = LinearLayout(this)
        form.orientation = LinearLayout.VERTICAL
        form.setPadding(dp(20f), dp(12f), dp(20f), 0)
        form.addView(et, rowParams())
        form.addView(
            label(getString(R.string.layout_alts_label), 13f, dim).also { it.setPadding(0, dp(12f), 0, 0) },
            rowParams(),
        )
        form.addView(alts, rowParams())
        val dialog = AlertDialog.Builder(this)
            .setTitle(key.label)
            .setView(form)
            .setPositiveButton(R.string.save) { _, _ -> saveChar(key, et.text.toString().trim(), alts.text.toString(), key.alts) }
            .setNeutralButton(R.string.layout_swap) { _, _ ->
                pendingSrc = key.src
                pendingChar = key.label
                preview.markedSrc = key.src
                updateStatus()
            }
            .setNegativeButton(R.string.layout_original) { _, _ ->
                val map = readMap()
                val altsMap = readAlts()
                map.remove(srcString(key))
                altsMap.remove(srcString(key))
                writeMaps(map, altsMap)
            }
            .create()
        dialog.show()
        styleDialogButton(dialog.getButton(AlertDialog.BUTTON_NEUTRAL), Color.parseColor("#3B82F6"))
        styleDialogButton(dialog.getButton(AlertDialog.BUTTON_NEGATIVE), Color.parseColor("#F59E0B"))
        styleDialogButton(dialog.getButton(AlertDialog.BUTTON_POSITIVE), Color.parseColor("#22C55E"))
    }

    /** Tlacitko dialogu s barevnym zaobleny obrysem (bez vyplne). */
    private fun styleDialogButton(b: Button?, color: Int) {
        if (b == null) return
        b.background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(dp(1.5f), color)
            cornerRadius = dp(10f).toFloat()
        }
        b.setTextColor(color)
        val lp = (b.layoutParams as? ViewGroup.MarginLayoutParams) ?: return
        lp.setMargins(dp(4f), 0, dp(4f), 0)
        b.layoutParams = lp
    }

    private fun saveChar(key: KeyboardView.Key, typed: String, typedAlts: String, oldAlts: String) {
        // Podporuje se jen jeden znak z zakladni roviny (jedna Rust `char` = jeden UTF-16 znak).
        val cp = if (typed.isEmpty()) -1 else typed.codePointAt(0)
        if (cp < 0 || cp > 0xFFFF || Character.isWhitespace(cp) || Character.isISOControl(cp)) {
            Toast.makeText(this, R.string.layout_char_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        val map = readMap()
        map[srcString(key)] = String(Character.toChars(cp))
        val altsMap = readAlts()
        val cleanAlts = typedAlts.filter { !it.isWhitespace() && !Character.isISOControl(it) }
        if (cleanAlts != oldAlts) altsMap[srcString(key)] = cleanAlts
        writeMaps(map, altsMap)
    }

    private companion object {
        val HINT_POS = listOf("top_left", "top_center", "top_right", "bottom_left", "bottom_center", "bottom_right")
        val HINT_LABELS = listOf(
            R.string.hint_top_left, R.string.hint_top_center, R.string.hint_top_right,
            R.string.hint_bottom_left, R.string.hint_bottom_center, R.string.hint_bottom_right,
        )
    }
}
