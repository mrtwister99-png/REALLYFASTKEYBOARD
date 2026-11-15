package cz.reallyfast

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import kotlin.math.hypot
import kotlin.math.max

/**
 * Vzhled a rozlozeni. Dole je pripnuty zivy nahled klavesnice ve skutecne velikosti.
 * Zalozka "Vzhled": barvy, tvar a efekty (nahled neni interaktivni).
 * Zalozka "Jazyk a rozlozeni": jazyk, spodni rada a znaky na klavesach; klepnuti na klavesu
 * v nahledu otevre nad ni bublinu s editorem znaku. Zmena se ulozi do nastaveni (`key_map`,
 * `key_alts`) a Rust ji pouzije v rozlozeni i v swipe decoderu.
 * Tlacitko TEST zapne zkusebni rezim nahledu: klavesy reaguji jako na pravde (klepnuti i swipe)
 * a napsany text se ukazuje v radku nad nahledem; nic se nikam nezapisuje ani neuci.
 * "Nastavit" u male napovedy zapne nad klavesnici ovladac polohy napovedy.
 */
class AppearanceActivity : SettingsScreen() {

    private lateinit var preview: KeyboardView
    private lateinit var previewBox: LinearLayout
    private lateinit var rootView: View
    private lateinit var testButton: TextView
    private lateinit var testView: TextView
    private lateinit var captionRow: LinearLayout
    private lateinit var editBar: LinearLayout
    private lateinit var hintPanel: FrameLayout
    private lateinit var hintPad: HintPad
    private lateinit var hintSave: TextView
    private lateinit var lookTab: LinearLayout
    private lateinit var layoutTab: LinearLayout
    private var hintAdjustButton: TextView? = null
    private var customColors: LinearLayout? = null
    private var statusView: TextView? = null
    private var cancelSwapView: TextView? = null
    private val tabPills = ArrayList<TextView>()
    private val pills = ArrayList<TextView>()
    private var shownMode = KeyboardView.MODE_LETTERS
    private var tab = 0

    private var testMode = false
    private val testTyped = StringBuilder()
    private var imeVisible = false
    private var adjusting = false
    private var saving = false
    private var adjX = 1f
    private var adjY = 0f

    /** Editor znaku (bublina nad klavesou) a kotva klavesy v okne - nahled se muze schovat za klavesnici. */
    private var editor: Bubble? = null
    private var editorKey: KeyboardView.Key? = null
    private val editorKeyRect = Rect()
    private val editorLayoutListener = ViewTreeObserver.OnGlobalLayoutListener { repositionEditor() }

    /** Cekajici prohozeni: puvodni kod pozice prvni klavesy a znak, ktery na ni ted je. */
    private var pendingSrc = -1
    private var pendingChar = ""

    // Akce tri tlacitek v radku nahledu (nastavuje editor klavesy).
    private var editSwap: (() -> Unit)? = null
    private var editOriginal: (() -> Unit)? = null
    private var editSave: (() -> Unit)? = null

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
        val captionRow = LinearLayout(this)
        captionRow.gravity = Gravity.CENTER_VERTICAL
        captionRow.minimumHeight = dp(46f)
        captionRow.setPadding(dp(22f), dp(6f), dp(16f), dp(4f))
        this.captionRow = captionRow
        val caption = label(getString(R.string.preview).uppercase(), 11f, dim)
        caption.letterSpacing = 0.08f
        captionRow.addView(caption, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // Text napsany v zkusebnim rezimu (jednoradkovy, zkracuje se zepredu).
        val typed = label("", 13f)
        typed.maxLines = 1
        typed.isSingleLine = true
        typed.ellipsize = TextUtils.TruncateAt.START
        typed.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        typed.setPadding(dp(12f), 0, dp(12f), 0)
        captionRow.addView(typed, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        testView = typed
        val test = label(getString(R.string.preview_test), 11f)
        test.typeface = Typeface.DEFAULT_BOLD
        test.letterSpacing = 0.08f
        test.gravity = Gravity.CENTER
        test.setPadding(dp(14f), dp(5f), dp(14f), dp(5f))
        test.setOnClickListener { if (testMode) stopTest() else startTest() }
        captionRow.addView(test)
        testButton = test
        // Tlacitka editoru klavesy: behem upravy nahrazuji TEST a napsany text.
        val bar = LinearLayout(this)
        bar.visibility = View.GONE
        fun editButton(textRes: Int, color: Int, first: Boolean, onClick: () -> Unit) {
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (!first) lp.leftMargin = dp(6f)
            bar.addView(outlinedButton(textRes, color, onClick), lp)
        }
        editButton(R.string.swap_short, BLUE, true) { editSwap?.invoke() }
        editButton(R.string.original_short, ORANGE, false) { editOriginal?.invoke() }
        editButton(R.string.save, GREEN, false) { editSave?.invoke() }
        val blp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        blp.leftMargin = dp(12f)
        captionRow.addView(bar, blp)
        editBar = bar
        box.addView(captionRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Ovladac polohy male napovedy (jen v rezimu nastavovani): kruh s kulickou + tlacitko Ulozit.
        val panel = FrameLayout(this)
        panel.visibility = View.GONE
        val pad = HintPad(this) { x, y ->
            adjX = x
            adjY = y
            preview.setHintOverride(x, y)
        }
        panel.addView(pad, FrameLayout.LayoutParams(dp(156f), dp(156f), Gravity.CENTER))
        val save = label(getString(R.string.save), 15f, GREEN)
        save.typeface = Typeface.DEFAULT_BOLD
        save.gravity = Gravity.CENTER
        save.setPadding(dp(22f), dp(11f), dp(22f), dp(11f))
        save.background = outline(GREEN)
        save.setOnClickListener { saveHint() }
        val slp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL)
        slp.rightMargin = dp(22f)
        panel.addView(save, slp)
        box.addView(panel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170f)))
        hintPanel = panel
        hintPad = pad
        hintSave = save

        val kv = KeyboardView(this)
        kv.preview = true
        kv.swipeAllowed = false
        kv.voiceAllowed = false
        box.addView(kv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        preview = kv
        previewBox = box
        rootView = root
        updatePreview()

        // Kdyz je otevrena skutecna klavesnice, nahled se schova - je videt ta prava. Behem upravy
        // klavesy se schova jen klavesnice nahledu, radek s tlacitky zustane nad skutecnou klavesnici.
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                imeVisible = insets.isVisible(WindowInsets.Type.ime())
                applyImeLayout()
                v.onApplyWindowInsets(insets)
            }
        }

        setContentView(root)
        build(container)
        showTab(0)
    }

    override fun onPause() {
        // Odejiti z obrazovky ukonci nastavovani napovedy (bez ulozeni) i zkusebni rezim.
        if (adjusting) finishAdjust()
        if (testMode) stopTest()
        super.onPause()
    }

    override fun onDestroy() {
        removeEditorListener()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ nahled a zalozky

    /** Nahled ukazuje presne to, co klavesnice: tema, vysku, radu cislic, zaobleni, znaky... */
    private fun updatePreview() {
        val s = RustBridge.settings
        val theme = Theme.from(s)
        preview.theme = theme
        testView.setTextColor(theme.text)
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
        // Zmena, kterou neni na staticke klavesnici videt, se ukaze kratkou ukazkou v nahledu.
        when (key) {
            "neon_color", "key_glow" -> preview.demoNeon()
            "key_popup" -> preview.demoPopup()
            "animations" -> preview.demoPress()
            "glow_style", "glow_color" -> preview.glowWord("ahoj")
        }
    }

    /** Skutecna klavesnice otevrena = nahled schovany; behem upravy klavesy zustane radek s tlacitky. */
    private fun applyImeLayout() {
        val editing = editor != null
        previewBox.visibility = if (imeVisible && !editing) View.GONE else View.VISIBLE
        preview.visibility = if (imeVisible && editing) View.GONE else View.VISIBLE
    }

    /** Radek nahledu: pri uprave klavesy tri tlacitka, jinak napsany text a TEST. */
    private fun setEditing(on: Boolean) {
        editBar.visibility = if (on) View.VISIBLE else View.GONE
        testView.visibility = if (on) View.GONE else View.VISIBLE
        testButton.visibility = if (on || adjusting) View.GONE else View.VISIBLE
        applyImeLayout()
    }

    private fun showTab(i: Int) {
        if (adjusting) finishAdjust()
        if (testMode) stopTest()
        currentBubble?.hide(immediate = true)
        if (i == 0 && tab == 1) {
            cancelSwap()
            shownMode = KeyboardView.MODE_LETTERS
            preview.setMode(KeyboardView.MODE_LETTERS)
        }
        tab = i
        lookTab.visibility = if (i == 0) View.VISIBLE else View.GONE
        layoutTab.visibility = if (i == 1) View.VISIBLE else View.GONE
        applyPickMode()
        paintPills()
    }

    /** Vyber klavesy v nahledu funguje jen na zalozce rozlozeni, mimo zkusebni rezim a nastavovani napovedy. */
    private fun applyPickMode() {
        preview.onKeyPick = if (tab == 1 && !testMode && !adjusting) { key -> onPick(key) } else null
    }

    // ------------------------------------------------------------------ zkusebni rezim nahledu

    private fun startTest() {
        if (adjusting) return
        currentBubble?.hide(immediate = true)
        testMode = true
        testTyped.setLength(0)
        testView.text = ""
        // Swipe se dekoduje jen v pismenech.
        if (shownMode != KeyboardView.MODE_LETTERS) {
            shownMode = KeyboardView.MODE_LETTERS
            preview.setMode(KeyboardView.MODE_LETTERS)
        }
        preview.listener = testListener
        preview.swipeAllowed = true
        // Zapne zkusebni rezim a zaregistruje geometrii nahledu pro swipe decoder.
        preview.previewTouch = true
        applyPickMode()
        testButton.text = getString(R.string.preview_back)
        paintPills()
    }

    private fun stopTest() {
        testMode = false
        preview.previewTouch = false
        preview.swipeAllowed = false
        preview.listener = null
        preview.shiftState = KeyboardView.SHIFT_OFF
        testTyped.setLength(0)
        testView.text = ""
        applyPickMode()
        testButton.text = getString(R.string.preview_test)
        paintPills()
    }

    /** Pridani textu do zkusebniho pole (poslednich ~300 znaku). */
    private fun testAppend(t: String) {
        testTyped.append(t)
        if (testTyped.length > 300) testTyped.delete(0, testTyped.length - 300)
        testView.text = testTyped.toString()
    }

    /**
     * Posluchac zkusebniho rezimu: jen vypisuje do pole nahledu. Zadne uceni, slovnik, schranka ani
     * diktovani; ostatni udalosti (gesta, kurzor, nabidky, pinch) se ignoruji.
     */
    private val testListener = object : KeyboardView.Listener {
        private fun afterLetter() {
            if (preview.shiftState == KeyboardView.SHIFT_ONCE) preview.shiftState = KeyboardView.SHIFT_OFF
        }

        override fun onPressFeedback(key: KeyboardView.Key) {}

        override fun onKeyTap(key: KeyboardView.Key) {
            when (key.kind) {
                KeyboardView.KIND_CHAR -> {
                    val upper = preview.shiftState != KeyboardView.SHIFT_OFF && preview.mode == KeyboardView.MODE_LETTERS
                    testAppend(if (upper) key.label.uppercase() else key.label)
                    afterLetter()
                }
                KeyboardView.KIND_SPACE -> testAppend(" ")
                KeyboardView.KIND_BACKSPACE -> if (testTyped.isNotEmpty()) {
                    testTyped.setLength(testTyped.length - Character.charCount(Character.codePointBefore(testTyped, testTyped.length)))
                    testView.text = testTyped.toString()
                }
                KeyboardView.KIND_SHIFT -> preview.shiftState =
                    if (preview.shiftState == KeyboardView.SHIFT_OFF) KeyboardView.SHIFT_ONCE else KeyboardView.SHIFT_OFF
            }
        }

        override fun onAltText(text: String) {
            testAppend(text)
            afterLetter()
        }

        override fun onSwipeWords(words: List<String>) {
            val word = words.firstOrNull() ?: return
            testAppend("$word ")
            preview.glowWord(word)
            afterLetter()
        }

        override fun onGestureAction(action: Int) {}
        override fun onCursorSteps(steps: Int) {}
        override fun onSpaceLongPress() {}
        override fun onPinchScale(scale: Float) {}
        override fun onMenuAction(kind: Int, index: Int) {}
        override fun onBeforeSwipe() {}
        override fun onVoiceStart() {}
        override fun onVoiceStop() {}
        override fun onVoiceDelete() {}
        override fun onVoiceResume() {}
    }

    // ------------------------------------------------------------------ poloha male napovedy

    private fun startAdjust() {
        if (adjusting) return
        if (testMode) stopTest()
        currentBubble?.hide(immediate = true)
        cancelSwap()
        adjusting = true
        saving = false
        applyPickMode()
        if (shownMode != KeyboardView.MODE_LETTERS) {
            shownMode = KeyboardView.MODE_LETTERS
            preview.setMode(KeyboardView.MODE_LETTERS)
        }
        val s = RustBridge.settings
        adjX = s.keyHintX
        adjY = s.keyHintY
        hintPad.setHint(adjX, adjY)
        preview.setHintOverride(adjX, adjY)
        preview.hintFocus = true
        testButton.visibility = View.GONE
        testView.visibility = View.INVISIBLE
        hintAdjustButton?.text = getString(R.string.cancel)
        hintPanel.visibility = View.VISIBLE
        // Tlacitko Ulozit pritece zprava zpoza okraje obrazovky.
        hintSave.animate().cancel()
        hintSave.translationX = resources.displayMetrics.widthPixels.toFloat()
        hintSave.animate().translationX(0f).setDuration(250).start()
        paintPills()
    }

    /** Ukonci rezim nastavovani (pri ulozeni jsou hodnoty zapsane uz predtim). */
    private fun finishAdjust() {
        if (!adjusting) return
        adjusting = false
        saving = false
        hintSave.animate().cancel()
        hintSave.translationX = 0f
        hintPanel.visibility = View.GONE
        preview.hintFocus = false
        preview.clearHintOverride()
        testButton.visibility = View.VISIBLE
        testView.visibility = View.VISIBLE
        hintAdjustButton?.text = getString(R.string.hint_adjust)
        applyPickMode()
        paintPills()
    }

    private fun saveHint() {
        if (!adjusting || saving) return
        saving = true
        val j = RustBridge.settings.edit()
        j.put("key_hint_x", Math.round(adjX * 1000f) / 1000.0)
        j.put("key_hint_y", Math.round(adjY * 1000f) / 1000.0)
        RustBridge.setSettings(j)
        // Tlacitko nejdriv odjede doprava za okraj, az pak se obrazovka vrati do normalu.
        hintSave.animate().cancel()
        hintSave.animate().translationX(resources.displayMetrics.widthPixels.toFloat()).setDuration(250)
            .withEndAction { finishAdjust() }.start()
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
        sliderRow(box, R.string.key_gap, "key_gap_dp", 1, 10, 1) { getString(R.string.unit_dp, it) }
        sliderRow(box, R.string.key_radius, "key_radius_dp", 2, 22, 1) { getString(R.string.unit_dp, it) }
        sliderRow(box, R.string.key_text_size, "key_text_percent", 70, 150, 1) { getString(R.string.unit_percent, it) }
        switchRow(box, R.string.key_popup, "key_popup", hintRes = R.string.key_popup_hint)
        switchRow(box, R.string.key_hints, "key_hints")
        switchRow(box, R.string.key_shadow, "key_shadow", hintRes = R.string.key_shadow_hint)
        switchRow(box, R.string.animations, "animations", hintRes = R.string.animations_hint)
        switchRow(box, R.string.key_glow, "key_glow", hintRes = R.string.key_glow_hint)
        colorRow(box, R.string.neon_color, "neon_color")
        choiceRow(box, R.string.glow_style_label, "glow_style", listOf("blink", "fill"),
            listOf(getString(R.string.glow_style_blink), getString(R.string.glow_style_fill)))
        colorRow(box, R.string.glow_color, "glow_color")
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
                if (adjusting) return@setOnClickListener // pri nastavovani napovedy zustavaji pismena
                currentBubble?.hide(immediate = true)
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

        // Poloha male napovedy: popisek + tlacitko, ktere zapne nastavovani nad klavesnici.
        val hintRow = LinearLayout(this)
        hintRow.gravity = Gravity.CENTER_VERTICAL
        hintRow.minimumHeight = dp(48f)
        hintRow.addView(label(getString(R.string.layout_hint_pos), 14f, dim), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val adjust = label(getString(R.string.hint_adjust), 15f, accent)
        adjust.typeface = Typeface.DEFAULT_BOLD
        adjust.setPadding(dp(14f), dp(10f), 0, dp(10f))
        adjust.setOnClickListener { if (adjusting) finishAdjust() else startAdjust() }
        hintRow.addView(adjust)
        hintAdjustButton = adjust
        box.addView(hintRow, rowParams())

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
        // Tlacitko TEST je maly "pill" (plne zaobleny).
        testButton.background = rounded(if (testMode) a else field, dp(50f).toFloat())
        testButton.setTextColor(if (testMode) onA else text)
        hintAdjustButton?.setTextColor(a)
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

    // ------------------------------------------------------------------ editor klavesy (bublina)

    private fun outline(color: Int) = GradientDrawable().apply {
        setColor(0)
        setStroke(dp(1.5f), color)
        cornerRadius = dp(10f).toFloat()
    }

    /** Tlacitko editoru: pruhledne s barevnym obrysem a barevnym textem. */
    private fun outlinedButton(textRes: Int, color: Int, onClick: () -> Unit): TextView {
        val b = label(getString(textRes), 12f, color)
        b.typeface = Typeface.DEFAULT_BOLD
        b.gravity = Gravity.CENTER
        b.maxLines = 1
        b.isSingleLine = true
        b.setPadding(dp(2f), dp(7f), dp(2f), dp(7f))
        b.background = outline(color)
        b.setOnClickListener { onClick() }
        return b
    }

    private fun keyRectInWindow(key: KeyboardView.Key): Rect {
        val loc = IntArray(2)
        preview.getLocationInWindow(loc)
        return Rect(
            loc[0] + key.vx.toInt(), loc[1] + key.y.toInt(),
            loc[0] + (key.vx + key.vw).toInt(), loc[1] + (key.y + key.h).toInt(),
        )
    }

    private fun askChar(key: KeyboardView.Key) {
        val theme = preview.theme
        val preAlts = key.alts.take(MAX_ALTS_EDIT)

        // Levy sloupec: "Klik" + pole vypadajici jako klavesa.
        val keyW = max(dp(44f), (key.vw - dp(5f)).toInt())
        val keyH = max(dp(44f), (key.h - dp(8f)).toInt())
        val charEdit = EditText(this)
        charEdit.background = rounded(theme.key, RustBridge.settings.keyRadiusDp * resources.displayMetrics.density)
        charEdit.setTextColor(theme.text)
        charEdit.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20f)
        charEdit.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        charEdit.gravity = Gravity.CENTER
        charEdit.setPadding(0, 0, 0, 0)
        charEdit.setSingleLine(true)
        charEdit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        charEdit.filters = arrayOf(InputFilter.LengthFilter(1))
        charEdit.setSelectAllOnFocus(true)
        charEdit.setText(key.label)
        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        left.addView(label(getString(R.string.key_editor_click), 12f, dim).also { it.setPadding(0, 0, 0, dp(4f)) })
        left.addView(charEdit, LinearLayout.LayoutParams(keyW, keyH))

        // Pravy sloupec: "Podrz" + pole pro znaky pri podrzeni.
        val altsEdit = EditText(this)
        altsEdit.background = rounded(field, dp(10f).toFloat())
        altsEdit.setTextColor(text)
        altsEdit.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
        altsEdit.setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
        altsEdit.setSingleLine(true)
        altsEdit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        altsEdit.filters = arrayOf(InputFilter.LengthFilter(MAX_ALTS_EDIT))
        altsEdit.setText(preAlts)
        val right = LinearLayout(this)
        right.orientation = LinearLayout.VERTICAL
        right.addView(label(getString(R.string.key_editor_hold), 12f, dim).also { it.setPadding(0, 0, 0, dp(4f)) })
        right.addView(altsEdit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, keyH))

        val top = LinearLayout(this)
        top.addView(left, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val rlp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        rlp.leftMargin = dp(12f)
        top.addView(right, rlp)

        // Tlacitka Prohodit / Puvodni / Ulozit jsou v radku nahledu (editBar); tady jen jejich akce.
        editSwap = {
            closeEditor()
            pendingSrc = key.src
            pendingChar = key.label
            preview.markedSrc = key.src
            updateStatus()
        }
        editOriginal = {
            closeEditor()
            val map = readMap()
            val altsMap = readAlts()
            map.remove(srcString(key))
            altsMap.remove(srcString(key))
            writeMaps(map, altsMap)
        }
        editSave = {
            val typedAlts = altsEdit.text.toString()
            // Nezmenene pole zustane beze zmeny (vychozi varianty delsi nez 5 se tak neorezou).
            val outAlts = if (typedAlts == preAlts) key.alts else typedAlts
            if (saveChar(key, charEdit.text.toString().trim(), outAlts, key.alts)) closeEditor()
        }

        val form = LinearLayout(this)
        form.orientation = LinearLayout.VERTICAL
        form.setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
        form.addView(top, rowParams())
        // Fokus drzi nepsaci pohled, aby se skutecna klavesnice neotevrela sama.
        form.isFocusableInTouchMode = true

        val bubble = Bubble(this, form, passThrough = false, widthDp = 300)
        bubble.passViews = listOf(editBar) // tlacitka v radku nahledu zustavaji pouzitelna
        editor = bubble
        editorKey = key
        editorKeyRect.set(keyRectInWindow(key))
        bubble.onHidden = {
            removeEditorListener()
            editor = null
            editorKey = null
            editSwap = null
            editOriginal = null
            editSave = null
            preview.hideAltsDemo()
            setEditing(false)
        }
        // Nabidka variant jako pri dlouhem stisku, zive podle pole "Podrz".
        preview.showAltsDemo(key, preAlts)
        altsEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (editor == null) return
                preview.showAltsDemo(key, s?.toString() ?: "")
                repositionEditor(animate = true)
            }
        })
        setEditing(true)
        bubble.show(editorAnchor())
        form.requestFocus()
        rootView.viewTreeObserver.addOnGlobalLayoutListener(editorLayoutListener)
    }

    private fun removeEditorListener() {
        val vto = rootView.viewTreeObserver
        if (vto.isAlive) vto.removeOnGlobalLayoutListener(editorLayoutListener)
    }

    private fun closeEditor() {
        hideIme()
        editor?.hide()
    }

    private fun hideIme() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(rootView.windowToken, 0)
    }

    /** Radek nahledu (kde jsou tlacitka) v souradnicich okna. */
    private fun captionRectInWindow(): Rect {
        val loc = IntArray(2)
        captionRow.getLocationInWindow(loc)
        return Rect(loc[0], loc[1], loc[0] + captionRow.width, loc[1] + captionRow.height)
    }

    /**
     * Kotva editoru: klavesa v dolni rade -> bublina primo nad nabidkou variant (nebo nad klavesou,
     * kdyz variant neni); klavesa ve vyssi rade -> primo nad radkem nahledu, tedy cela nad klavesnici.
     * Kdyz je klavesnice nahledu schovana (otevrena skutecna klavesnice), je kotvou radek s tlacitky.
     * Vzdy v ramci viditelne oblasti okna.
     */
    private fun editorAnchor(): Rect {
        val key = editorKey ?: return Rect(editorKeyRect)
        val r = Rect()
        rootView.getWindowVisibleDisplayFrame(r)
        val l0 = IntArray(2)
        val l1 = IntArray(2)
        rootView.getLocationInWindow(l0)
        rootView.getLocationOnScreen(l1)
        val visibleBottom = r.bottom - (l1[1] - l0[1])
        val keyboardShown = previewBox.visibility == View.VISIBLE && preview.visibility == View.VISIBLE && preview.width > 0
        var top = captionRectInWindow().top
        if (keyboardShown) {
            editorKeyRect.set(keyRectInWindow(key))
            if (preview.isBottomRow(key)) {
                val loc = IntArray(2)
                preview.getLocationInWindow(loc)
                top = loc[1] + preview.stripTopFor(key).toInt()
            }
        }
        top = minOf(top, visibleBottom)
        return Rect(editorKeyRect.left, top, editorKeyRect.right, top)
    }

    private fun repositionEditor(animate: Boolean = false) {
        val b = editor ?: return
        b.moveTo(editorAnchor(), animate)
    }

    /** Ulozi znak a znaky pri podrzeni; false = neplatny znak (editor zustane otevreny). */
    private fun saveChar(key: KeyboardView.Key, typed: String, typedAlts: String, oldAlts: String): Boolean {
        // Podporuje se jen jeden znak z zakladni roviny (jedna Rust `char` = jeden UTF-16 znak).
        val cp = if (typed.isEmpty()) -1 else typed.codePointAt(0)
        if (cp < 0 || cp > 0xFFFF || Character.isWhitespace(cp) || Character.isISOControl(cp)) {
            Toast.makeText(this, R.string.layout_char_invalid, Toast.LENGTH_SHORT).show()
            return false
        }
        val map = readMap()
        map[srcString(key)] = String(Character.toChars(cp))
        val altsMap = readAlts()
        val cleanAlts = typedAlts.filter { !it.isWhitespace() && !Character.isISOControl(it) }
        if (cleanAlts != oldAlts) altsMap[srcString(key)] = cleanAlts
        writeMaps(map, altsMap)
        return true
    }

    private companion object {
        const val MAX_ALTS_EDIT = 5
        val BLUE = 0xFF3B82F6.toInt()
        val ORANGE = 0xFFF59E0B.toInt()
        val GREEN = 0xFF22C55E.toInt()
    }
}

/**
 * Ovladac polohy male napovedy: velky velmi tmavy modry kruh a v nem mensi modra kulicka.
 * Poloha kulicky = poloha napovedy na klavese (x: 0 vlevo .. 1 vpravo, y: 0 nahore .. 1 dole).
 * Kulicka se tahne prstem (dotyk kdekoli v kruhu ji presune tam) a zustane v kruhu.
 * Vsechny rozmery se pocitaji mimo onDraw, onDraw nealokuje.
 */
@SuppressLint("ViewConstructor")
private class HintPad(context: Context, private val onChange: (Float, Float) -> Unit) : View(context) {
    private val d = resources.displayMetrics.density
    private val bigR = 75f * d
    private val knobR = 16f * d
    private val reach = bigR - knobR
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var kx = 0f
    private var ky = 0f

    /** Nastavi kulicku podle polohy napovedy (bez volani onChange). */
    fun setHint(x: Float, y: Float) {
        kx = (x - 0.5f) * reach * SQRT2
        ky = (y - 0.5f) * reach * SQRT2
        clampKnob()
        invalidate()
    }

    private fun clampKnob() {
        val dist = hypot(kx, ky)
        if (dist > reach) {
            kx *= reach / dist
            ky *= reach / dist
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        paint.style = Paint.Style.FILL
        paint.color = 0xFF0A1730.toInt()
        canvas.drawCircle(cx, cy, bigR, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = d
        paint.color = 0x553B82F6
        canvas.drawCircle(cx, cy, bigR - d / 2f, paint)
        paint.style = Paint.Style.FILL
        paint.color = 0xFF3B82F6.toInt()
        canvas.drawCircle(cx + kx, cy + ky, knobR, paint)
    }

    @SuppressLint("ClickableViewAccessibility") // ovladani prstem, pro TalkBack je tlacitko Ulozit
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val cx = width / 2f
        val cy = height / 2f
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (hypot(e.x - cx, e.y - cy) > bigR) return false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {}
            else -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        kx = e.x - cx
        ky = e.y - cy
        clampKnob()
        invalidate()
        onChange((0.5f + kx / (reach * SQRT2)).coerceIn(0f, 1f), (0.5f + ky / (reach * SQRT2)).coerceIn(0f, 1f))
        return true
    }

    private companion object {
        const val SQRT2 = 1.41421f
    }
}
