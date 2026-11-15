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
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Vzhled a rozlozeni. Dole je pripnuty zivy nahled klavesnice ve skutecne velikosti.
 * Zalozka "Hlavni": barvy, rozmery, efekty, jazyk, spodni rada a znaky na klavesach; klepnuti na
 * klavesu v nahledu otevre nad ni bublinu s editorem znaku. Druha zalozka ("Waiting ...") je zamcena. Zmena se ulozi do nastaveni (`key_map`,
 * `key_alts`) a Rust ji pouzije v rozlozeni i v swipe decoderu.
 * Tlacitko TEST zapne zkusebni rezim nahledu: klavesy reaguji jako na pravde (klepnuti i swipe)
 * a napsany text se ukazuje v radku nad nahledem; nic se nikam nezapisuje ani neuci.
 * "Nastavit" u male napovedy zapne nad klavesnici ovladac polohy napovedy; stejny ovladac nastavuje
 * i polohu hlavniho znaku (druha zalozka).
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
    private var hintAdjustButton: TextView? = null
    private var labelAdjustButton: TextView? = null
    private lateinit var captionView: TextView
    private var statusView: TextView? = null
    private var cancelSwapView: TextView? = null
    private val tabPills = ArrayList<TextView>()
    private lateinit var tabScroll: ScrollView
    private lateinit var mainTab: LinearLayout
    private lateinit var extraTab: LinearLayout
    private var shownTab = 0
    private var extraOpen = false
    private val pills = ArrayList<TextView>()
    private var shownMode = KeyboardView.MODE_LETTERS
    private val hintRange = FloatArray(4)

    private var testMode = false
    private val testTyped = StringBuilder()
    private var imeVisible = false
    private var adjusting = false
    private var adjLabel = false
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
    private var editMacro: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(bg)
        root.fitsSystemWindows = true

        val scroll = ScrollView(this)
        tabScroll = scroll
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
        captionView = caption
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
        editButton(R.string.macro_short, PURPLE, false) { editMacro?.invoke() }
        val blp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        blp.leftMargin = dp(8f)
        captionRow.addView(bar, blp)
        editBar = bar
        box.addView(captionRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Ovladac polohy male napovedy (jen v rezimu nastavovani): kruh s kulickou + tlacitko Ulozit.
        val panel = FrameLayout(this)
        panel.visibility = View.GONE
        val pad = HintPad(this) { x, y ->
            adjX = x
            adjY = y
            if (adjLabel) preview.setLabelOverride(x, y) else preview.setHintOverride(x, y)
        }
        panel.addView(pad, FrameLayout.LayoutParams(dp(156f), dp(156f), Gravity.CENTER))
        val save = label(getString(R.string.save), 15f, GREEN)
        save.typeface = Typeface.DEFAULT_BOLD
        save.gravity = Gravity.CENTER
        save.setPadding(dp(22f), dp(11f), dp(22f), dp(11f))
        save.background = outline(GREEN)
        save.setOnClickListener { saveAdjust() }
        val slp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL)
        slp.rightMargin = dp(22f)
        panel.addView(save, slp)
        box.addView(panel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170f)))
        hintPanel = panel
        hintPad = pad
        hintSave = save

        // Slovo po tahu (styl "Slovo") pristane v urovni radku nahledu nad klavesnici: pohled smi kreslit nad sebe.
        box.clipChildren = false
        root.clipChildren = false
        val kv = KeyboardView(this)
        kv.addOnLayoutChangeListener { _, _, top, _, _, _, _, _, _ -> kv.overflowTop = top - captionRow.top }
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
        updateStatus()
        paintPills()
        if (adjusting && !adjLabel) refreshHintRange()
    }

    override fun onSettingUpdated(key: String, value: Any) {
        // Zmena, kterou neni na staticke klavesnici videt, se ukaze kratkou ukazkou v nahledu.
        when (key) {
            "neon_color", "key_glow", "tap_effect", "tap_text_color" -> preview.demoNeon()
            "paint_color1", "paint_color2", "paint_color3" -> preview.demoPaint()
            "key_popup", "popup_color" -> preview.demoPopup()
            "alts_color" -> preview.demoAlts()
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
        captionView.visibility = if (on) View.GONE else View.VISIBLE // tlacitka dostanou celou sirku radku
        testView.visibility = if (on) View.GONE else View.VISIBLE
        testButton.visibility = if (on || adjusting) View.GONE else View.VISIBLE
        applyImeLayout()
    }

    /** Vyber klavesy v nahledu funguje porad, jen ne v zkusebnim rezimu a pri nastavovani napovedy. */
    private fun applyPickMode() {
        preview.onKeyPick = if (!testMode && !adjusting) { key -> onPick(key) } else null
    }

    // ------------------------------------------------------------------ zkusebni rezim nahledu

    private fun startTest() {
        if (adjusting) return
        currentBubble?.hide(immediate = true)
        cancelSwap()
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
                    val macro = key.macro
                    val upper = preview.shiftState != KeyboardView.SHIFT_OFF && preview.mode == KeyboardView.MODE_LETTERS
                    testAppend(macro?.text ?: if (upper) key.label.uppercase() else key.label)
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

    // ------------------------------------------------------------------ poloha male napovedy a hlavniho znaku

    /** Zapne nastavovani polohy napovedy, nebo (`label`) polohy hlavniho znaku na klavese. */
    private fun startAdjust(label: Boolean) {
        if (adjusting) return
        if (testMode) stopTest()
        currentBubble?.hide(immediate = true)
        cancelSwap()
        adjusting = true
        adjLabel = label
        saving = false
        applyPickMode()
        if (shownMode != KeyboardView.MODE_LETTERS) {
            shownMode = KeyboardView.MODE_LETTERS
            preview.setMode(KeyboardView.MODE_LETTERS)
        }
        val s = RustBridge.settings
        hintPad.linear = label
        if (label) {
            adjX = s.keyLabelX
            adjY = s.keyLabelY
            hintPad.setHint(adjX, adjY)
            preview.setLabelOverride(adjX, adjY)
        } else {
            adjX = s.keyHintX
            adjY = s.keyHintY
            preview.hintFocus = true
            refreshHintRange()
        }
        testButton.visibility = View.GONE
        testView.visibility = View.INVISIBLE
        (if (label) labelAdjustButton else hintAdjustButton)?.text = getString(R.string.cancel)
        hintPanel.visibility = View.VISIBLE
        // Tlacitko Ulozit pritece zprava zpoza okraje obrazovky.
        hintSave.animate().cancel()
        hintSave.translationX = resources.displayMetrics.widthPixels.toFloat()
        hintSave.animate().translationX(0f).setDuration(250).start()
        paintPills()
    }

    /**
     * Prenastavi ovladac na rozsah polohy napovedy pro soucasne mezery a zasadi do nej ulozenou
     * hodnotu (pri zmene mezer behem nastavovani se hodnota ohrani novym rozsahem).
     */
    private fun refreshHintRange() {
        val r = hintRange
        if (!preview.hintRange(r)) {
            r[0] = -0.5f
            r[1] = 1.5f
            r[2] = -0.5f
            r[3] = 1.5f
        }
        hintPad.setRange(r[0], r[1], r[2], r[3])
        adjX = adjX.coerceIn(r[0], r[1])
        adjY = adjY.coerceIn(r[2], r[3])
        hintPad.setHint(adjX, adjY)
        preview.setHintOverride(adjX, adjY)
    }

    /** Ukonci rezim nastavovani (pri ulozeni jsou hodnoty zapsane uz predtim). */
    private fun finishAdjust() {
        if (!adjusting) return
        adjusting = false
        saving = false
        hintSave.animate().cancel()
        hintSave.translationX = 0f
        hintPanel.visibility = View.GONE
        if (adjLabel) {
            preview.clearLabelOverride()
        } else {
            preview.hintFocus = false
            preview.clearHintOverride()
        }
        hintPad.linear = false
        testButton.visibility = View.VISIBLE
        testView.visibility = View.VISIBLE
        hintAdjustButton?.text = getString(R.string.hint_adjust)
        labelAdjustButton?.text = getString(R.string.label_adjust)
        applyPickMode()
        paintPills()
    }

    private fun saveAdjust() {
        if (!adjusting || saving) return
        saving = true
        val j = RustBridge.settings.edit()
        j.put(if (adjLabel) "key_label_x" else "key_hint_x", Math.round(adjX * 1000f) / 1000.0)
        j.put(if (adjLabel) "key_label_y" else "key_hint_y", Math.round(adjY * 1000f) / 1000.0)
        RustBridge.setSettings(j)
        // Tlacitko nejdriv odjede doprava za okraj, az pak se obrazovka vrati do normalu.
        hintSave.animate().cancel()
        hintSave.animate().translationX(resources.displayMetrics.widthPixels.toFloat()).setDuration(250)
            .withEndAction { finishAdjust() }.start()
    }

    // ------------------------------------------------------------------ obsah

    private fun build(container: LinearLayout) {
        title(container, getString(R.string.appearance_open), back = true)

        // Dve zalozky: "Hlavni" (vse) a "Waiting ..." (zamcena, dokud se neodemkne).
        extraOpen = Lantern.lit(this)
        val tabs = LinearLayout(this)
        val tabNames = listOf(R.string.tab_main, R.string.tab_waiting)
        for ((i, n) in tabNames.withIndex()) {
            val p = label(getString(n), 14f)
            p.gravity = Gravity.CENTER
            p.setPadding(dp(4f), dp(10f), dp(4f), dp(10f))
            if (i == 0) {
                p.setOnClickListener { showTab(0) }
            } else if (extraOpen) {
                p.setOnClickListener { showTab(1) }
            } else {
                p.compoundDrawablePadding = dp(6f)
                p.setCompoundDrawablesRelative(LockDrawable(dp(12f), dp(14f)), null, null, null)
                p.setOnClickListener { Toast.makeText(this, R.string.tab_locked, Toast.LENGTH_SHORT).show() }
            }
            tabPills.add(p)
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (i > 0) lp.leftMargin = dp(6f)
            tabs.addView(p, lp)
        }
        val tlp = rowParams()
        tlp.topMargin = dp(14f)
        container.addView(tabs, tlp)

        mainTab = LinearLayout(this)
        mainTab.orientation = LinearLayout.VERTICAL
        extraTab = LinearLayout(this)
        extraTab.orientation = LinearLayout.VERTICAL
        extraTab.visibility = View.GONE
        container.addView(mainTab, rowParams())
        container.addView(extraTab, rowParams())
        buildMain(mainTab)
        if (extraOpen) buildExtra(extraTab)
        applyPickMode()
        if (extraOpen && intent?.getBooleanExtra(Lantern.MOTH, false) == true) showTab(1)
        paintPills()
    }

    private fun showTab(i: Int) {
        if (i == shownTab) return
        if (adjusting) finishAdjust()
        currentBubble?.hide(immediate = true)
        shownTab = i
        mainTab.visibility = if (i == 0) View.VISIBLE else View.GONE
        extraTab.visibility = if (i == 1) View.VISIBLE else View.GONE
        tabScroll.scrollTo(0, 0)
        paintPills()
    }

    /** Druha zalozka: barvy cislic, prechody klaves a pozadi, zvlastni efekty a znaky na klavesach. */
    private fun buildExtra(tabBox: LinearLayout) {
        var box = section(tabBox, getString(R.string.sec_extra_digits))
        colorRow(box, R.string.digit_text_color, "digit_text_color", allowAuto = true)
        colorRow(box, R.string.digit_key_color, "digit_key_color", allowAuto = true)

        box = section(tabBox, getString(R.string.sec_extra_gradients))
        colorRow(box, R.string.key_color2, "key_color2", allowAuto = true)
        hintText(box, getString(R.string.key_color2_hint))
        colorRow(box, R.string.background_color2, "background_color2", allowAuto = true)
        hintText(box, getString(R.string.background_color2_hint))

        box = section(tabBox, getString(R.string.sec_extra_effects))
        valueSwitchRow(box, R.string.paint_effect, "tap_effect", "paint", "neon")
        colorRow(box, R.string.paint_color1, "paint_color1")
        colorRow(box, R.string.paint_color2, "paint_color2")
        colorRow(box, R.string.paint_color3, "paint_color3")
        hintText(box, getString(R.string.paint_hint))
        valueSwitchRow(box, R.string.fly_effect, "glow_style", "fly", "blink")
        hintText(box, getString(R.string.fly_hint))

        box = section(tabBox, getString(R.string.sec_extra_chars))
        // Poloha hlavniho znaku: popisek a pod nim tlacitko, ktere zapne nastavovani nad klavesnici.
        val labelRow = LinearLayout(this)
        labelRow.orientation = LinearLayout.VERTICAL
        labelRow.addView(label(getString(R.string.label_pos), 14f, dim).also { it.setPadding(0, dp(10f), 0, dp(2f)) }, rowParams())
        val labelAdjust = buttonView(getString(R.string.label_adjust), accent) { if (adjusting) finishAdjust() else startAdjust(true) }
        val llp = rowParams()
        llp.topMargin = dp(5f)
        llp.bottomMargin = dp(5f)
        labelRow.addView(labelAdjust, llp)
        labelAdjustButton = labelAdjust
        box.addView(labelRow, rowParams())
        colorRow(box, R.string.hover_text_color, "hover_text_color", allowAuto = true)
        colorRow(box, R.string.passed_text_color, "passed_text_color", allowAuto = true)
        colorRow(box, R.string.tap_text_color, "tap_text_color", allowAuto = true)
        sliderRow(box, R.string.label_weight, "label_weight", 100, 900, 1, 100) { it.toString() }
        sliderRow(box, R.string.digit_weight, "digit_weight", 100, 900, 1, 100) { it.toString() }
    }

    private fun buildMain(tabBox: LinearLayout) {
        // Barvy
        var box = section(tabBox, getString(R.string.sec_colors))
        presetRow(box)
        colorRow(box, R.string.custom_background, "custom_background")
        colorRow(box, R.string.custom_key, "custom_key")
        colorRow(box, R.string.accent_color, "accent_color")
        colorRow(box, R.string.custom_text, "custom_text")

        // Rozmery
        box = section(tabBox, getString(R.string.sec_dimensions))
        sliderRow(box, R.string.key_height, "key_height_dp", 48, 72, 1) { getString(R.string.unit_dp, it) }
        sliderRow(box, R.string.key_gap, "key_gap_dp", 1, 10, 1) { getString(R.string.unit_dp, it) }
        sliderRow(box, R.string.row_gap, "row_gap_dp", 2, 16, 1) { getString(R.string.unit_dp, it) }
        sliderRow(box, R.string.key_radius, "key_radius_dp", 2, 22, 1) { getString(R.string.unit_dp, it) }
        sliderRow(box, R.string.key_text_size, "key_text_percent", 70, 150, 1) { getString(R.string.unit_percent, it) }

        // Klavesy a efekty
        box = section(tabBox, getString(R.string.sec_effects))
        switchRow(box, R.string.key_popup, "key_popup", hintRes = R.string.key_popup_hint)
        colorRow(box, R.string.popup_color, "popup_color", allowAuto = true)
        switchRow(box, R.string.key_shadow, "key_shadow", hintRes = R.string.key_shadow_hint)
        colorRow(box, R.string.alts_color, "alts_color", allowAuto = true)
        switchRow(box, R.string.key_hints, "key_hints")
        colorRow(box, R.string.hint_color, "hint_color", allowAuto = true)
        sliderRow(box, R.string.hint_size, "hint_percent", 60, 180, 1) { getString(R.string.unit_percent, it) }
        // Poloha male napovedy: popisek a pod nim tlacitko, ktere zapne nastavovani nad klavesnici.
        val hintRow = LinearLayout(this)
        hintRow.orientation = LinearLayout.VERTICAL
        hintRow.addView(label(getString(R.string.layout_hint_pos), 14f, dim).also { it.setPadding(0, dp(10f), 0, dp(2f)) }, rowParams())
        val adjust = buttonView(getString(R.string.hint_adjust), accent) { if (adjusting) finishAdjust() else startAdjust(false) }
        val alp = rowParams()
        alp.topMargin = dp(5f)
        alp.bottomMargin = dp(5f)
        hintRow.addView(adjust, alp)
        hintAdjustButton = adjust
        box.addView(hintRow, rowParams())
        switchRow(box, R.string.animations, "animations", hintRes = R.string.animations_hint)
        switchRow(box, R.string.key_glow, "key_glow", hintRes = R.string.key_glow_hint)
        // Zvlastni styly (Barvy, Slovo) se zapinaji na druhe zalozce; pri jejich aktivaci neni zadna volba vybrana.
        choiceRow(box, R.string.tap_effect_label, "tap_effect", listOf("neon", "glitch"),
            listOf(getString(R.string.tap_effect_neon), getString(R.string.tap_effect_glitch)))
        colorRow(box, R.string.neon_color, "neon_color")
        choiceRow(box, R.string.glow_style_label, "glow_style", listOf("blink", "fill"),
            listOf(getString(R.string.glow_style_blink), getString(R.string.glow_style_fill)))
        colorRow(box, R.string.glow_color, "glow_color")

        // Jazyk
        box = section(tabBox, getString(R.string.sec_language))
        choiceRow(box, R.string.language, "language", listOf("cs", "en", "cs+en"),
            listOf(getString(R.string.lang_cs), getString(R.string.lang_en), getString(R.string.lang_both)))
        choiceRow(box, R.string.layout, "layout", listOf("qwertz", "qwerty"), listOf("QWERTZ", "QWERTY"))
        switchRow(box, R.string.show_number_row, "show_number_row")
        switchRow(box, R.string.number_divider, "number_divider", hintRes = R.string.number_divider_hint, dependsOn = "show_number_row")

        // Spodni rada
        box = section(tabBox, getString(R.string.sec_bottom_row))
        val places = listOf("left", "right", "hidden")
        val placeLabels = listOf(getString(R.string.key_place_left), getString(R.string.key_place_right), getString(R.string.key_place_hidden))
        choiceRow(box, R.string.emoji_key_label, "emoji_key", places, placeLabels)
        choiceRow(box, R.string.macro_key_label, "macro_key", places, placeLabels)
        hintText(box, getString(R.string.bottom_row_hint))

        // Uprava klaves
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

        statusView = label("", 14f, dim).also {
            it.setPadding(0, dp(10f), 0, dp(6f))
            box.addView(it, rowParams())
        }
        cancelSwapView = buttonRow(box, getString(R.string.layout_swap_cancel), accent) { cancelSwap() }
        cancelSwapView?.visibility = View.GONE
        buttonRow(box, getString(R.string.layout_reset), danger) {
            confirmDanger(getString(R.string.layout_reset_confirm), getString(R.string.layout_reset_do)) {
                cancelSwap()
                writeMaps(LinkedHashMap(), LinkedHashMap(), resetSwaps = true, clearMacros = true)
            }
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
        paint(tabPills[0], shownTab == 0)
        if (extraOpen) {
            paint(tabPills[1], shownTab == 1)
        } else {
            // Zamcena zalozka: ztlumena, nelze ji otevrit.
            tabPills[1].background = rounded(field, dp(12f).toFloat())
            tabPills[1].setTextColor(dim)
            tabPills[1].alpha = 0.55f
        }
        val order = listOf(KeyboardView.MODE_LETTERS, KeyboardView.MODE_SYMBOLS, KeyboardView.MODE_SYMBOLS2)
        for ((i, p) in pills.withIndex()) paint(p, order[i] == shownMode)
        // Tlacitko TEST je maly "pill" (plne zaobleny).
        testButton.background = rounded(if (testMode) a else field, dp(50f).toFloat())
        testButton.setTextColor(if (testMode) onA else text)
        hintAdjustButton?.setTextColor(a)
        labelAdjustButton?.setTextColor(a)
    }

    private fun updateStatus() {
        val swapping = pendingSrc >= 0
        statusView?.text = if (swapping) {
            getString(R.string.layout_status_swap, pendingChar)
        } else {
            val n = (readMap().keys + readAlts().keys + readMacroKeys()).size
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

    private fun readMacroKeys(): Set<String> {
        val o = RustBridge.settings.json.optJSONObject("key_macros") ?: return emptySet()
        return o.keys().asSequence().toSet()
    }

    /**
     * Mapy se zapisuji jednim volanim, aby se rozlozeni prepocitalo jen jednou. Makra na klavesach se pri
     * zapisu bud vsechna smazou (`clearMacros`), nebo se smaze makro jedne klavesy (`dropMacroFor`).
     */
    private fun writeMaps(
        map: Map<String, String>, alts: Map<String, String>, resetSwaps: Boolean = false,
        clearMacros: Boolean = false, dropMacroFor: String? = null,
    ) {
        val obj = JSONObject()
        for ((k, v) in map) if (k != v) obj.put(k, v)
        val altsObj = JSONObject()
        for ((k, v) in alts) altsObj.put(k, v)
        val j = RustBridge.settings.edit()
        j.put("key_map", obj)
        j.put("key_alts", altsObj)
        if (clearMacros) {
            j.put("key_macros", JSONObject())
        } else if (dropMacroFor != null) {
            j.optJSONObject("key_macros")?.remove(dropMacroFor)
        }
        if (resetSwaps) {
            j.put("swap_mode_enter", false)
            j.put("swap_shift_delete", false)
        }
        RustBridge.setSettings(j)
    }

    private fun srcString(key: KeyboardView.Key): String = String(Character.toChars(key.src))

    // ------------------------------------------------------------------ vyber klavesy

    private fun onPick(key: KeyboardView.Key) {
        if (key.kind != KeyboardView.KIND_CHAR) {
            // ?123/ABC (spodni rada) a Enter se prohazuji spolu, Shift (nebo prepinac symbolu v jeho slotu) a smazani take.
            val swapKey = when (key.kind) {
                KeyboardView.KIND_ENTER -> "swap_mode_enter"
                KeyboardView.KIND_MODE -> if (preview.isBottomRow(key)) "swap_mode_enter" else "swap_shift_delete"
                KeyboardView.KIND_SHIFT, KeyboardView.KIND_BACKSPACE -> "swap_shift_delete"
                else -> null
            }
            if (swapKey == null) {
                Toast.makeText(this, R.string.layout_not_editable, Toast.LENGTH_SHORT).show()
                return
            }
            val on = !RustBridge.settings.json.optBoolean(swapKey)
            val j = RustBridge.settings.edit()
            j.put(swapKey, on)
            RustBridge.setSettings(j)
            val msg = if (swapKey == "swap_mode_enter") {
                if (on) R.string.swap_mode_enter_on else R.string.swap_mode_enter_off
            } else {
                if (on) R.string.swap_shift_delete_on else R.string.swap_shift_delete_off
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
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
            writeMaps(map, altsMap, dropMacroFor = srcString(key))
        }
        editMacro = { pickMacro(key) }
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
            editMacro = null
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

    /** Seznam ulozenych maker (nazev + zkratka) a "Bez makra"; zvolene makro se zapise na klavesu a editor se zavre. */
    private fun pickMacro(key: KeyboardView.Key) {
        val macros = RustBridge.macros()
        if (macros.isEmpty()) {
            Toast.makeText(this, R.string.key_macro_empty, Toast.LENGTH_LONG).show()
            return
        }
        val names = ArrayList<String>(macros.size + 1)
        names.add(getString(R.string.key_macro_none))
        for (m in macros) names.add(if (m.shortcut.isEmpty()) m.name else "${m.name}  (${m.shortcut})")
        AlertDialog.Builder(this)
            .setTitle(R.string.key_macro_title)
            .setItems(names.toTypedArray()) { _, which ->
                closeEditor()
                val j = RustBridge.settings.edit()
                val o = j.optJSONObject("key_macros") ?: JSONObject()
                if (which == 0) o.remove(srcString(key)) else o.put(srcString(key), macros[which - 1].id)
                j.put("key_macros", o)
                RustBridge.setSettings(j)
            }
            .show()
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
        val PURPLE = 0xFFA855F7.toInt()
    }
}

/**
 * Maly zamek (kresleny, bez obrazku) u zamcene zalozky. Barva se bere z `setTint` (barva textu popisku).
 */
private class LockDrawable(private val w: Int, private val h: Int) : android.graphics.drawable.Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = android.graphics.RectF()

    init {
        setBounds(0, 0, w, h)
    }

    override fun getIntrinsicWidth() = w
    override fun getIntrinsicHeight() = h

    override fun draw(canvas: Canvas) {
        val bodyTop = h * 0.45f
        paint.color = 0xFF9A9AA3.toInt()
        paint.style = Paint.Style.FILL
        rect.set(0f, bodyTop, w.toFloat(), h.toFloat())
        canvas.drawRoundRect(rect, w * 0.2f, w * 0.2f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = w * 0.16f
        val inset = w * 0.26f
        rect.set(inset, paint.strokeWidth / 2f, w - inset, bodyTop * 1.7f)
        canvas.drawArc(rect, 180f, 180f, false, paint)
        canvas.drawLine(inset, bodyTop * 0.85f, inset, bodyTop, paint)
        canvas.drawLine(w - inset, bodyTop * 0.85f, w - inset, bodyTop, paint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/**
 * Ovladac polohy male napovedy (a v linearnim rezimu hlavniho znaku): velky velmi tmavy modry kruh a v nem mensi modra kulicka.
 * Poloha kulicky = poloha napovedy na klavese (x: 0 vlevo .. 1 vpravo, y: 0 nahore .. 1 dole; mimo
 * 0..1 je napoveda za okrajem klavesy, az na obrysu sousedni klavesy). Kruh se premapuje na ctverec
 * [-1, 1]^2; vnitrni cast (+-INNER) pokryva vnitrek klavesy, zbytek az po krajni hodnotu (`setRange`,
 * podle aktualnich mezer). Okraj kruhu = nejdal, kam napoveda smi. Tece se prstem (dotyk kdekoli v
 * kruhu presune kulicku tam); vsechny rozmery se pocitaji mimo onDraw, onDraw nealokuje.
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
    private var xMin = -0.5f
    private var xMax = 1.5f
    private var yMin = -0.5f
    private var yMax = 1.5f

    /** Linearni rezim (poloha znaku): cely ovladac je rozsah 0..1, bez vnitrniho kruhu. */
    var linear = false
        set(value) {
            field = value
            invalidate()
        }

    /** Rozsah hodnot napovedy (viz KeyboardView.hintRange); zavolat pred setHint. */
    fun setRange(minX: Float, maxX: Float, minY: Float, maxY: Float) {
        xMin = minX.coerceAtMost(-0.01f)
        xMax = maxX.coerceAtLeast(1.01f)
        yMin = minY.coerceAtMost(-0.01f)
        yMax = maxY.coerceAtLeast(1.01f)
    }

    /** Hodnota napovedy -> poloha na ose ctverce [-1, 1]. */
    private fun toAxis(h: Float, lo: Float, hi: Float): Float = when {
        linear -> (h - 0.5f) * 2f
        h < 0f -> -INNER - (h / lo) * (1f - INNER)
        h > 1f -> INNER + ((h - 1f) / (hi - 1f)) * (1f - INNER)
        else -> (h - 0.5f) * 2f * INNER
    }.coerceIn(-1f, 1f)

    /** Poloha na ose ctverce [-1, 1] -> hodnota napovedy. */
    private fun fromAxis(u: Float, lo: Float, hi: Float): Float = when {
        linear -> 0.5f + u / 2f
        u < -INNER -> (-(u + INNER) / (1f - INNER)) * lo
        u > INNER -> 1f + ((u - INNER) / (1f - INNER)) * (hi - 1f)
        else -> 0.5f + u / (2f * INNER)
    }

    /** Nastavi kulicku podle polohy napovedy (bez volani onChange). */
    fun setHint(x: Float, y: Float) {
        val u = toAxis(x, xMin, xMax)
        val v = toAxis(y, yMin, yMax)
        val n = hypot(u, v)
        val m = max(abs(u), abs(v))
        // Ctverec -> kruh: smer zustane, delka = vetsi ze souradnic.
        val f = if (n > 0f) m / n else 0f
        kx = u * f * reach
        ky = v * f * reach
        invalidate()
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
        // Vnitrek klavesy: stred kruhu (ctverec [-INNER, INNER]^2 se na kruh premapovava na kruh o polomeru INNER).
        if (!linear) canvas.drawCircle(cx, cy, INNER * reach, paint)
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
        val dist = hypot(kx, ky)
        if (dist > reach) {
            kx *= reach / dist
            ky *= reach / dist
        }
        invalidate()
        // Kruh -> ctverec: smer zustane, delka se natahne na vetsi ze souradnic (okraj kruhu = okraj ctverce).
        val a = kx / reach
        val b = ky / reach
        val n = hypot(a, b)
        val m = max(abs(a), abs(b))
        val s = if (m > 0f) n / m else 0f
        onChange(fromAxis(a * s, xMin, xMax), fromAxis(b * s, yMin, yMax))
        return true
    }

    private companion object {
        /** Cast polomeru (na ose), kterou zabira vnitrek klavesy. */
        const val INNER = 0.6f
    }
}
