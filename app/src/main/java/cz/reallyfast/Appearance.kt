package cz.reallyfast

import android.animation.ValueAnimator
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
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import android.view.animation.DecelerateInterpolator
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
import kotlin.math.min
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Vzhled a rozlozeni. Nahore je pevna hlavicka: Zpet, Test, Ulozit a posuvnik se styly 1-5. Pod ni
 * jezdi seznam kategorii; po rozbaleni kategorie vyjede dole zivy nahled klavesnice ve skutecne velikosti.
 * Kazda kategorie ma az tri urovne (I, II, III); klepnuti na radek nastaveni ukaze napovedu a na nahledu
 * zablikne v barve akcentu to, co se meni (ostatni klavesy se ztlumi). Klepnuti na klavesu v nahledu
 * v kategorii 5 otevre nad ni bublinu s editorem znaku. Posledni kategorie (IV) je zamcena.
 * Tlacitko TEST zapne zkusebni rezim nahledu: klavesy reaguji jako na pravde (klepnuti i swipe)
 * a napsany text se ukazuje v radku nad nahledem; nic se nikam nezapisuje ani neuci.
 * "Nastavit" u male napovedy zapne nad klavesnici ovladac polohy napovedy; stejny ovladac nastavuje
 * i polohu hlavniho znaku.
 */
class AppearanceActivity : SettingsScreen() {

    /** Jedna kategorie: nazev, zamcena?, builder pro kazdou uroven a popisky tlacitek urovni. */
    private class CatDef(
        val titleRes: Int,
        val levels: List<(LinearLayout) -> Unit>,
        val labels: List<String>,
        val showBar: Boolean,
        var locked: Boolean = false,
    )

    private lateinit var preview: KeyboardView
    private lateinit var previewBox: LinearLayout
    private lateinit var previewHost: LinearLayout
    private lateinit var backBtn: View
    private lateinit var backArrow: ChevronView
    private lateinit var restoreBtn: FrameLayout
    private lateinit var randomBtn: TextView
    private lateinit var restoreIcon: RestoreIconView
    private lateinit var saveBtn: TextView
    private val slotViews = ArrayList<StyleSlotView>()
    private var previewOpen = false
    private var leaving = false
    private var slideAnim: ValueAnimator? = null

    // Zmeny vzhledu od otevreni obrazovky: Ulozit se rozsviti az po zmene.
    private val undoStack = ArrayList<String>()
    private var lastSnap = ""
    private var changedSinceSave = false
    private var lastChangeAt = 0L
    private var restoring = false

    // Vlastni styly (sloty 1-5).
    private var chosenSlot = -1
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
    private lateinit var tabScroll: ScrollView
    private var extraOpen = false
    private var expandedCat = -1
    private val catDefs = ArrayList<CatDef>()
    private val catCards = ArrayList<LinearLayout>()
    private val catBodies = ArrayList<LinearLayout>()
    private val catArrows = ArrayList<ChevronView>()
    private val catLevelBoxes = ArrayList<ArrayList<LinearLayout>>()
    private val catLevelBtns = ArrayList<ArrayList<TextView>>()
    private val catLevel = IntArray(8)
    private val pills = ArrayList<TextView>()
    private var shownMode = KeyboardView.MODE_LETTERS
    private val hintRange = FloatArray(4)
    private val fontPainters = ArrayList<() -> Unit>()
    private val fontFaces = HashMap<String, Typeface>()

    /** Klic, jehoz ukazka uz zabliknula pred zapisem (zmizela klavesa), aby po zapisu nezablikla znovu. */
    private var spotDone = ""

    private var testMode = false
    // Zkusebni rezim zapnuty sam kategoriemi 0-4 (tlacitko Test je jen v kategorii 5).
    private var autoTest = false
    private var lastTouchAt = 0L
    private val ui = Handler(Looper.getMainLooper())
    private var loopIdx = 0
    private val loopRun = Runnable { runLoopStep() }
    // Waiting: za nazvem postupne naskakuji tecky (po 1 s), po treti zmizi a zacne se znovu.
    private var waitingTitle: TextView? = null
    private var waitingDots = 0
    private val dotsRun = object : Runnable {
        override fun run() {
            waitingDots = (waitingDots + 1) % 4
            waitingTitle?.text = getString(R.string.cat_waiting) + " .".repeat(waitingDots)
            ui.postDelayed(this, 1000L)
        }
    }
    private val testTyped = StringBuilder()
    private var imeVisible = false
    private var adjusting = false
    private var adjLabel = false
    /** Probiha uprava vysky radku (misto polohy znaku / napovedy). */
    private var adjRows = false
    private var adjOffsets = FloatArray(5)
    private var rowEditButton: TextView? = null
    private lateinit var rowHint: TextView
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
        lastSnap = RustBridge.settings.json.toString()

        // Pevna hlavicka: Zpet vlevo, Test a Ulozit vpravo, pod nimi posuvnik se styly.
        root.addView(buildHeader(), rowParams())

        // Obsah jezdi pod hlavickou. Obal ScrollView orezava, aby obsah nikdy nepreleznul hlavicku
        // (root neorezava kvuli slovu "Slovo", ktere se kresli nad klavesnici).
        val scrollHolder = FrameLayout(this)
        val scroll = ScrollView(this)
        tabScroll = scroll
        val container = contentColumn()
        scroll.addView(container, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        scrollHolder.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(scrollHolder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Zivy nahled: dole, ve skutecne velikosti klavesnice; vyjizdi plynule po rozbaleni kategorie.
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
        // Tlacitka editoru klavesy: behem upravy nahrazuji popisek a napsany text.
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
        // Napoveda k uprave radku (jen v tomto rezimu), misto ovladace polohy napovedy.
        val rh = label(getString(R.string.row_edit_hint), 13f, dim)
        rh.visibility = View.GONE
        panel.addView(rh, FrameLayout.LayoutParams(dp(210f), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL).also { it.leftMargin = dp(20f) })
        rowHint = rh
        hintPanel = panel
        hintPad = pad
        hintSave = save

        // Slovo po tahu (styl "Slovo") pristane v urovni radku nahledu nad klavesnici: pohled smi kreslit nad sebe.
        box.clipChildren = false
        root.clipChildren = false
        val host = LinearLayout(this)
        host.orientation = LinearLayout.VERTICAL
        host.clipChildren = false
        host.visibility = View.GONE
        val kv = KeyboardView(this)
        // Slovo po tahu (styl Text) pristane nad celym nahledem, az nad caption radkem (nad jeho horni caru), ne uvnitr nej.
        kv.addOnLayoutChangeListener { _, _, top, _, _, _, _, _, _ -> kv.overflowTop = 2 * (top - captionRow.top) + dp(80f) }
        kv.preview = true
        kv.swipeAllowed = false
        kv.voiceAllowed = false
        kv.onUserTouch = { userTouched() }
        box.addView(kv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        host.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(host, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        preview = kv
        previewBox = box
        previewHost = host
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

    /** Pevna hlavicka: Zpet (vlevo), Test a Ulozit (vpravo) a pod nimi posuvnik se styly 1-5 (nejvyse pul sirky). */
    private fun buildHeader(): LinearLayout {
        val header = LinearLayout(this)
        header.orientation = LinearLayout.VERTICAL
        header.setBackgroundColor(bg)
        header.setPadding(dp(14f), dp(10f), dp(14f), dp(6f))
        // Zpet vlevo, Test / Obnovit / Ulozit vpravo (pevne ke krajum, ne pres pruznou mezeru).
        // Jeden vodorovny radek: Zpet vlevo, pruzna mezera, Test / Obnovit / Ulozit vpravo (nic se neprekryva).
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        val right = LinearLayout(this)
        right.gravity = Gravity.CENTER_VERTICAL
        // Zpetna sipka: tlacitko zustane stat, sipka z nej po klepnuti vystreli doleva.
        val back = FrameLayout(this)
        back.clipChildren = false
        val arrow = chevron(false, 42f, field, text, back = true)
        arrow.contentDescription = getString(R.string.back)
        back.addView(arrow, FrameLayout.LayoutParams(dp(48f), dp(42f)))
        back.setOnClickListener { if (arrow.isEnabled) playBack(arrow) { if (!onBackRequested()) finish() else arrow.returnHome() } }
        arrow.setOnClickListener { back.performClick() }
        backBtn = back
        backArrow = arrow
        top.clipChildren = false
        header.clipChildren = false
        top.addView(back, LinearLayout.LayoutParams(dp(48f), dp(42f)))
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val test = label(getString(R.string.preview_test), 13f)
        test.typeface = Typeface.DEFAULT_BOLD
        test.letterSpacing = 0.08f
        test.gravity = Gravity.CENTER
        test.setPadding(dp(20f), 0, dp(20f), 0)
        test.setOnClickListener { if (testMode) stopTest() else startTest() }
        testButton = test
        val tlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42f))
        tlp.rightMargin = dp(8f)
        right.addView(test, tlp)
        // R: uplne nahodny styl (barvy, tvary, efekty); vratit ho jde tlacitkem Obnovit nebo dalsim R.
        val rnd = label("R", 17f)
        rnd.typeface = Typeface.DEFAULT_BOLD
        rnd.gravity = Gravity.CENTER
        rnd.contentDescription = getString(R.string.style_random)
        rnd.setOnClickListener {
            randomStyle()
            confetti(rnd)
        }
        randomBtn = rnd
        val rndLp = LinearLayout.LayoutParams(dp(42f), dp(42f))
        rndLp.rightMargin = dp(8f)
        // Kolem R bezi duhovy obrys: nabehne dokola, odbehne do nuly a znovu.
        val rndHolder = FrameLayout(this)
        rndHolder.addView(rnd, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        rndHolder.addView(RainbowRingView(this), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        right.addView(rndHolder, rndLp)
        // Obnova profilu: nedokonceny kruh se sipkou na konci; po zeptani vrati ulozenou podobu vybraneho profilu.
        val restore = FrameLayout(this)
        val ricon = RestoreIconView(this, text)
        restore.contentDescription = getString(R.string.style_restore)
        restore.addView(ricon, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        restore.setOnClickListener { askRestoreSlot() }
        restoreBtn = restore
        restoreIcon = ricon
        val rlp0 = LinearLayout.LayoutParams(dp(42f), dp(42f))
        rlp0.rightMargin = dp(8f)
        right.addView(restore, rlp0)
        val s = label(getString(R.string.save), 15f)
        s.gravity = Gravity.CENTER
        s.typeface = Typeface.DEFAULT_BOLD
        s.setPadding(dp(24f), 0, dp(24f), 0)
        saveBtn = s
        // Tlacitko Ulozit v obalu: po ulozeni text povyskoci (zvetsi se) a pres celou vysku tlacitka projede zleva doprava zlutozeleny pruh.
        val saveHolder = FrameLayout(this)
        saveHolder.clipChildren = false
        saveHolder.addView(s, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42f)))
        val saveWave = SweepView(this)
        val swp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        saveHolder.addView(saveWave, swp)
        right.clipChildren = false
        s.setOnClickListener {
            if (saveSlot()) {
                // Tlacitko se zvetsi, zleva nabiha zelena a zprava zluta do stredu, pak se vrati do normalu.
                saveHolder.animate().cancel()
                saveHolder.animate().scaleX(1.3f).scaleY(1.3f).setDuration(200L).start()
                saveWave.fire(900L) { saveHolder.animate().scaleX(1f).scaleY(1f).setDuration(260L).start() }
            }
        }
        right.addView(saveHolder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42f)))
        top.addView(right, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42f)))
        header.addView(top, rowParams())

        val bar = LinearLayout(this)
        bar.gravity = Gravity.CENTER
        val size = dp(40f)
        for (i in 0 until STYLE_SLOTS) {
            val v = StyleSlotView(this, (i + 1).toString(), size)
            v.contentDescription = getString(R.string.style_slot_n, i + 1)
            v.setOnClickListener { pickSlot(i) }
            slotViews.add(v)
            val lp = LinearLayout.LayoutParams(size, size)
            if (i > 0) lp.leftMargin = dp(10f)
            bar.addView(v, lp)
        }
        val remembered = getSharedPreferences(UI_PREFS, MODE_PRIVATE).getInt(KEY_CHOSEN_SLOT, -1)
        // Vzdy je vybrany nejaky profil (vychozi prvni), aby bylo jasne, co se upravuje.
        chosenSlot = (0 until STYLE_SLOTS).firstOrNull { slotMatches(it) }
            ?: remembered.takeIf { it in 0 until STYLE_SLOTS } ?: 0
        val plp = rowParams()
        plp.topMargin = dp(6f)
        header.addView(bar, plp)
        paintSlots()
        updateActions()
        return header
    }

    /** Odchod z obrazovky: nahled nejdriv plynule zajede dolu a az potom se obrazovka zavre. */
    override fun onBackRequested(): Boolean {
        if (!previewOpen || leaving) return false
        leaving = true
        if (testMode) stopTest()
        slidePreview(false)
        tabScroll.postDelayed({ finish() }, 300L)
        return true
    }

    override fun onResume() {
        super.onResume()
        Lantern.onLit = {
            extraOpen = true
            catDefs.lastOrNull()?.locked = false
            confirmDialog(getString(R.string.downgrade_done), getString(R.string.notice_ok), null, false, 0xFF22C55E.toInt()) { expandCat(6) }
        }
        ui.removeCallbacks(dotsRun)
        ui.postDelayed(dotsRun, 1000L)
        // Po navratu z plochy se zkusebni rezim kategorii obnovi (onPause ho vypina).
        if (::previewHost.isInitialized) autoTestIfNeeded()
    }

    override fun onPause() {
        // Odejiti z obrazovky ukonci nastavovani napovedy (bez ulozeni) i zkusebni rezim.
        if (adjusting) finishAdjust()
        if (testMode) stopTest()
        ui.removeCallbacks(loopRun)
        ui.removeCallbacks(dotsRun)
        Lantern.onLit = null
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
        // Text v radku nad nahledem lezi na pozadi klavesnice: vzdy kontrastni k nemu.
        testView.setTextColor(theme.barText)
        captionView.setTextColor(theme.barDim)
        // Nejvyse 45 % obrazovky, aby zbylo misto na nastaveni.
        val real = keyboardKeysHeight(resources, s, dp(s.barHeightDp.toFloat()))
        preview.desiredHeight = minOf(real, (resources.displayMetrics.heightPixels * 0.45f).toInt())
        preview.reloadLayout()
        previewBox.setBackgroundColor(theme.background)
    }

    override fun onSettingsChanged() {
        if (!restoring) trackChange()
        updateActions()
        updatePreview()
        updateStatus()
        paintPills()
        paintSlots()
        for (f in fontPainters) f()
        if (adjusting && !adjLabel && !adjRows) refreshHintRange()
    }

    override fun onSettingUpdated(key: String, value: Any) {
        // Zmena, kterou neni na staticke klavesnici videt, se ukaze kratkou ukazkou slova v nahledu.
        demoFor(key)
    }

    override fun onSettingWillUpdate(key: String, value: Any) {
        // Zmizi-li kvuli zmene klavesy (rada cislic, tlacitko), zablikne jejich misto jeste pred zapisem.
        val removed = (key == "show_number_row" && value == false) ||
            (key == "macro_key" && value == "hidden")
        if (removed) {
            spotFor(key)
            spotDone = key
        }
    }

    /** Klic nastaveni, jehoz vyber barvy je prave otevreny (prazdny = zadny). */
    private var pickerKey = ""

    override fun onColorPickerState(key: String, open: Boolean) {
        if (open) pickerKey = key else if (pickerKey == key) pickerKey = ""
        if (::previewHost.isInitialized && !explicitTest() && !adjusting && previewOpen) {
            if (key == "popup_color") preview.holdPopup(open)
            if (key == "alts_color") preview.holdAlts(open)
        }
    }

    override fun onSettingTouched(key: String, anchor: View) {
        showTip(key, anchor)
        if (spotDone == key) {
            spotDone = ""
        } else if (demoKind(key) == DEMO_NONE && key !in NO_SPOT) {
            spotFor(key)
        }
    }

    /** Skutecna klavesnice otevrena = nahled schovany; jinak je nahled videt, kdyz je rozbalena kategorie, pri testu a pri uprave klavesy. */
    private fun applyImeLayout() {
        if (leaving) return
        val editing = editor != null
        val want = (testMode || expandedCat >= 0 || editing) && !(imeVisible && !editing)
        preview.visibility = if (imeVisible && editing) View.GONE else View.VISIBLE
        slidePreview(want)
    }

    /** Plynule vyjede (nebo zajede) nahled dole: vyska obalu se animuje, nahled ma behem toho pevnou vysku. */
    private fun slidePreview(show: Boolean) {
        if (show == previewOpen) return
        previewOpen = show
        slideAnim?.cancel()
        val host = previewHost
        val hostLp = host.layoutParams
        val boxLp = previewBox.layoutParams
        val from: Int
        val to: Int
        if (show) {
            host.visibility = View.VISIBLE
            boxLp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            previewBox.measure(
                View.MeasureSpec.makeMeasureSpec(max(1, rootView.width), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            to = previewBox.measuredHeight
            from = if (host.height > 0 && hostLp.height >= 0) hostLp.height else 0
        } else {
            to = 0
            from = if (host.height > 0) host.height else 0
        }
        boxLp.height = if (show) to else max(previewBox.height, 1)
        hostLp.height = from
        host.requestLayout()
        val anim = ValueAnimator.ofInt(from, to)
        anim.duration = 280L
        anim.interpolator = DecelerateInterpolator()
        anim.addUpdateListener {
            hostLp.height = it.animatedValue as Int
            host.requestLayout()
        }
        var cancelled = false
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationCancel(animation: android.animation.Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (cancelled) return
                boxLp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                if (show) {
                    hostLp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                } else {
                    host.visibility = View.GONE
                }
                host.requestLayout()
            }
        })
        slideAnim = anim
        anim.start()
    }

    /** Radek nahledu: pri uprave klavesy tri tlacitka, jinak napsany text a TEST. */
    private fun setEditing(on: Boolean) {
        editBar.visibility = if (on) View.VISIBLE else View.GONE
        captionView.visibility = if (on) View.GONE else View.VISIBLE // tlacitka dostanou celou sirku radku
        testView.visibility = if (on) View.GONE else View.VISIBLE
        if (adjusting) testButton.visibility = View.GONE else applyTestButton()
        applyImeLayout()
    }

    /** Vyber klavesy v nahledu funguje porad, jen ne v zkusebnim rezimu a pri nastavovani napovedy. */
    private fun applyPickMode() {
        preview.onKeyPick = if (!testMode && !adjusting) { key -> onPick(key) } else null
    }

    // ------------------------------------------------------------------ zkusebni rezim nahledu

    private fun applyTestButton() {
        testButton.visibility = if (expandedCat == CAT_KEYS && !adjusting) View.VISIBLE else View.GONE
    }

    /** Kategorie 0-4: nahled je trvale v zkusebnim rezimu (jde psat); kategorie 5 a zavrene: bez nej. */
    private fun autoTestIfNeeded() {
        if (leaving || adjusting || editor != null) return
        if (expandedCat in 0 until CAT_KEYS || expandedCat == CAT_EXTRA) {
            if (!testMode) {
                startTest()
                autoTest = testMode
            }
        } else if (autoTest && testMode) {
            stopTest()
        }
        applyTestButton()
        restartLoop(900L)
    }

    private fun explicitTest() = testMode && !autoTest

    private fun userTouched() {
        lastTouchAt = SystemClock.uptimeMillis()
        ui.removeCallbacks(loopRun)
        // Po dopsani (3 s bez doteku) se ukazka znovu rozbehne.
        restartLoop(USER_IDLE_MS)
    }

    // ------------------------------------------------------------------ opakovana ukazka animaci (kategorie 3 a 4)

    /** Ukazka bezi dokola jen v kategoriich animaci, kdyz je nahled otevreny a nic jineho se nedeje. */
    private fun loopWanted(): Boolean =
        (expandedCat == CAT_TAP || expandedCat == CAT_SWIPE) && previewOpen && !leaving && !adjusting && editor == null && !explicitTest() && !imeVisible

    private fun restartLoop(delay: Long) {
        ui.removeCallbacks(loopRun)
        if (loopWanted()) ui.postDelayed(loopRun, delay)
    }

    /** Dve slova za sebou dokola: FAZT a ahoj (98 %), jednou za cas NEČUM (2 %). */
    private fun loopWord(): String {
        if (Random.nextInt(100) < 2) return "NE\u010cUM"
        return if (loopIdx++ % 2 == 0) "FAZT" else "ahoj"
    }

    private fun runLoopStep() {
        if (!loopWanted()) return
        val idle = SystemClock.uptimeMillis() - lastTouchAt
        if (idle < USER_IDLE_MS) {
            ui.postDelayed(loopRun, USER_IDLE_MS - idle + 50L)
            return
        }
        if (expandedCat == CAT_TAP) {
            preview.demoPress(loopWord())
            ui.postDelayed(loopRun, 2600L)
        } else {
            // Tah po domaci rade od a do l dokola; po nem se slovo z tech pismen rozsviti.
            preview.demoSwipe()
            val stamp = lastTouchAt
            ui.postDelayed({
                if (loopWanted() && stamp == lastTouchAt) preview.glowWord(SWIPE_WORD)
            }, 1800L)
            ui.postDelayed(loopRun, 4600L)
        }
    }

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
        applyImeLayout()
        preview.swipeAllowed = true
        // Zapne zkusebni rezim a zaregistruje geometrii nahledu pro swipe decoder.
        preview.previewTouch = true
        applyPickMode()
        testButton.text = getString(R.string.preview_back)
        paintPills()
    }

    private fun stopTest() {
        testMode = false
        autoTest = false
        applyImeLayout()
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

        override fun onKeyHold(key: KeyboardView.Key) {
            key.holdMacro?.let { testAppend(it.text) }
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
        override fun onVoiceSend() {}
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

    /** Zapne upravu vysky radku: ztmavena klavesnice bez pismen, u kazde rady sipka, tah posouva celou radu. */
    private fun startRowEdit() {
        if (adjusting) return
        if (testMode) stopTest()
        currentBubble?.hide(immediate = true)
        cancelSwap()
        adjusting = true
        adjLabel = false
        adjRows = true
        saving = false
        applyPickMode()
        if (shownMode != KeyboardView.MODE_LETTERS) {
            shownMode = KeyboardView.MODE_LETTERS
            preview.setMode(KeyboardView.MODE_LETTERS)
        }
        adjOffsets = RustBridge.settings.rowOffsets
        preview.editOffsets = adjOffsets.copyOf()
        preview.onRowEdited = { adjOffsets = it }
        preview.rowEditMode = true
        testButton.visibility = View.GONE
        testView.visibility = View.INVISIBLE
        rowEditButton?.text = getString(R.string.cancel)
        hintPad.visibility = View.GONE
        rowHint.visibility = View.VISIBLE
        hintPanel.visibility = View.VISIBLE
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
        if (adjRows) {
            adjRows = false
            preview.rowEditMode = false
            preview.editOffsets = null
            preview.onRowEdited = null
            hintPad.visibility = View.VISIBLE
            rowHint.visibility = View.GONE
            rowEditButton?.text = getString(R.string.row_edit)
        } else if (adjLabel) {
            preview.clearLabelOverride()
        } else {
            preview.hintFocus = false
            preview.clearHintOverride()
        }
        hintPad.linear = false
        applyTestButton()
        testView.visibility = View.VISIBLE
        hintAdjustButton?.text = getString(R.string.hint_adjust)
        labelAdjustButton?.text = getString(R.string.label_adjust)
        applyPickMode()
        // Po uprave se v kategoriich 0-4 nahled vraci do zkusebniho rezimu (jde zase psat).
        autoTestIfNeeded()
        paintPills()
    }

    /** Uplne nahodny styl: barvy, tvary klaves a efekty se vylosuji; citelnost textu se hlida jen minimalne. */
    /**
     * Cisty nahodny styl: vzdy nova zakladni paleta (s citelnym textem), efekty a jejich barvy; k tomu nahodny pocet
     * (jedna az vsechny) skupin uprav navic: tvary, druhe barvy s nahodnym smerem prechodu, cisla, obrysy, barvy po klepnuti a po tahu.
     * Co nevyjde, se vrati na vychozi (prazdne / top), aby po predchozim stylu nic nezustalo.
     */
    private fun randomStyle() {
        val r = java.util.Random()
        fun hex(): String = String.format("#%06X", r.nextInt(0x1000000))
        fun hsv(v0: Float, v1: Float): String {
            val c = android.graphics.Color.HSVToColor(floatArrayOf(r.nextFloat() * 360f, 0.45f + r.nextFloat() * 0.55f, v0 + r.nextFloat() * (v1 - v0)))
            return String.format("#%06X", c and 0xFFFFFF)
        }
        fun <T> pick(vararg v: T): T = v[r.nextInt(v.size)]
        fun int(lo: Int, hi: Int): Int = lo + r.nextInt(hi - lo + 1)
        fun dir(): String = pick("top", "bottom", "left", "right")
        fun rb(c: String): String = if (r.nextInt(8) == 0) "rainbow" else c
        val bgc = hex()
        val keyc = hex()
        val keyLight = Theme.luminance(Theme.parse(keyc, 0xFF000000.toInt())) > 0.5f
        // Text citelny na klavese: na svetle klavese tmavy, na tmave svetly.
        fun readable(): String = if (keyLight) hsv(0.05f, 0.35f) else hsv(0.75f, 1f)
        val m = LinkedHashMap<String, Any>()
        for (k in listOf(
            "key_color2", "text_color2", "background_color2", "digit_key_color2", "digit_text_color2", "outline_color", "outline_color2",
            "neon_color2", "trail_color2", "glow_color2", "tap_text_color", "tap_text_color2", "outline_tap_color", "outline_tap_color2",
            "hover_text_color", "passed_text_color", "outline_hover_color", "outline_passed_color",
        )) m[k] = ""
        for (k in listOf("bg_grad_dir", "key_grad_dir", "digit_key_grad_dir", "text_grad_dir", "digit_text_grad_dir", "outline_grad_dir")) m[k] = "top"
        m["theme"] = "custom"
        m["accent_color"] = hex()
        m["custom_background"] = bgc
        m["custom_key"] = keyc
        m["custom_text"] = readable()
        m["key_glow"] = true
        m["tap_effect"] = pick("neon", "glitch", "paint", "ghost", "orbit", "pop", "cut", "slide", "plasma")
        m["neon_color"] = hex()
        for (i in 1..6) m["paint_color$i"] = hex()
        m["glow_style"] = pick("neon", "paint", "ghost", "flip")
        m["glow_color"] = hex()
        m["trail_style"] = pick("line", "dashed", "fire", "pixel")
        m["trail_color"] = hex()
        // Kazda uprava zvlast (kazda druha barva i kazdy smer prechodu); sance na zarazeni je nahodna: od jedne po vsechny.
        val items = ArrayList<() -> Unit>()
        items.add { m["key_radius_dp"] = int(2, 22) }
        items.add { m["key_gap_dp"] = int(0, 10) }
        items.add { m["row_gap_dp"] = int(0, 16) }
        items.add { m["key_text_percent"] = int(90, 130) }
        items.add { m["key_shadow"] = r.nextBoolean() }
        items.add { m["special_outline"] = r.nextBoolean() }
        items.add { m["number_divider"] = r.nextBoolean() }
        items.add { m["outline_width_dp"] = int(1, 6) }
        items.add { m["key_color2"] = rb(hex()); m["key_grad_dir"] = dir() }
        items.add { m["text_color2"] = rb(readable()); m["text_grad_dir"] = dir() }
        items.add { m["background_color2"] = rb(hex()); m["bg_grad_dir"] = dir() }
        items.add { m["digit_key_color2"] = rb(hex()); m["digit_key_grad_dir"] = dir() }
        items.add { m["digit_text_color2"] = rb(readable()); m["digit_text_grad_dir"] = dir() }
        items.add { m["outline_color"] = hex(); m["outline_grad_dir"] = dir() }
        items.add { m["outline_color"] = hex(); m["outline_color2"] = rb(hex()); m["outline_grad_dir"] = dir() }
        items.add { m["neon_color2"] = rb(hex()) }
        items.add { m["tap_text_color"] = hex() }
        items.add { m["tap_text_color2"] = hex() }
        items.add { m["outline_tap_color"] = hex() }
        items.add { m["outline_tap_color2"] = hex() }
        items.add { m["hover_text_color"] = hex() }
        items.add { m["passed_text_color"] = hex() }
        items.add { m["outline_hover_color"] = hex() }
        items.add { m["outline_passed_color"] = hex() }
        items.add { m["glow_color2"] = hex() }
        items.add { m["trail_color2"] = hex() }
        val chance = r.nextFloat()
        var any = false
        for (it in items) if (r.nextFloat() < chance) {
            it()
            any = true
        }
        if (!any) items[r.nextInt(items.size)]()
        updateAll(*m.map { it.key to it.value }.toTypedArray())
    }

    private fun saveAdjust() {
        if (!adjusting || saving) return
        saving = true
        val j = RustBridge.settings.edit()
        if (adjRows) {
            val arr = org.json.JSONArray()
            for (v in adjOffsets) arr.put((v * 1000f).roundToInt() / 1000.0)
            j.put("row_offsets", arr)
        } else {
            j.put(if (adjLabel) "key_label_x" else "key_hint_x", (adjX * 1000f).roundToInt() / 1000.0)
            j.put(if (adjLabel) "key_label_y" else "key_hint_y", (adjY * 1000f).roundToInt() / 1000.0)
        }
        RustBridge.setSettings(j)
        // Tlacitko nejdriv odjede doprava za okraj, az pak se obrazovka vrati do normalu.
        hintSave.animate().cancel()
        hintSave.animate().translationX(resources.displayMetrics.widthPixels.toFloat()).setDuration(250)
            .withEndAction { finishAdjust() }.start()
    }

    // ------------------------------------------------------------------ obsah

    private fun build(container: LinearLayout) {
        // Kategorie 0.-5. + Waiting (IV) jako posledni zamcena. Kazda ma az tri urovne I, II, III.
        extraOpen = Lantern.lit(this)
        val roman = listOf("I", "II", "III")
        catDefs.add(CatDef(R.string.cat_fast, listOf<(LinearLayout) -> Unit>({ buildFastLevel(it) }), roman, showBar = false))
        catDefs.add(CatDef(R.string.cat_layout, listOf<(LinearLayout) -> Unit>({ buildLayoutDims(it) }, { buildLayoutPos(it) }, { buildLayoutBottom(it) }), roman, showBar = true))
        catDefs.add(CatDef(R.string.cat_colors, listOf<(LinearLayout) -> Unit>({ buildColors1(it) }, { buildColors2(it) }, { buildColors3(it) }), roman, showBar = true))
        catDefs.add(CatDef(R.string.cat_tap_anim, listOf<(LinearLayout) -> Unit>({ buildTap1(it) }, { buildTap2(it) }, { buildTap3(it) }), roman, showBar = true))
        catDefs.add(CatDef(R.string.cat_swipe, listOf<(LinearLayout) -> Unit>({ buildSwipe1(it) }, { buildSwipe2(it) }, { buildSwipe3(it) }), roman, showBar = true))
        catDefs.add(CatDef(R.string.cat_keys, listOf<(LinearLayout) -> Unit>({ buildCatKeys(it) }), roman, showBar = false))
        catDefs.add(CatDef(R.string.cat_waiting, listOf<(LinearLayout) -> Unit>({ buildCatWaiting(it) }), listOf("IV"), showBar = true, locked = !extraOpen))
        for ((i, d) in catDefs.withIndex()) buildCategory(container, i, d)
        for (i in catDefs.indices) paintCatCard(i)
        updateActions()
        applyPickMode()
        paintPills()
        applyTestButton()
        applyImeLayout()
        autoTestIfNeeded()
    }

    /** Jedna kategorie: hlavicka, tlacitka urovni I / II / III a pod nimi obsah vybrane urovne (sklada se az pri prvnim zobrazeni). */
    private fun buildCategory(container: LinearLayout, index: Int, def: CatDef) {
        val card = cardBox(container)
        (card.layoutParams as LinearLayout.LayoutParams).topMargin = dp(12f)
        val head = LinearLayout(this)
        head.gravity = Gravity.CENTER_VERTICAL
        head.minimumHeight = dp(56f)
        head.setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
        val titleView = label(getString(def.titleRes), 16f)
        if (def.titleRes == R.string.cat_waiting) waitingTitle = titleView
        head.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val arrow = ChevronView(this, true, dim)
        head.addView(arrow, LinearLayout.LayoutParams(dp(28f), dp(28f)))
        card.addView(head, rowParams())
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.visibility = View.GONE
        val btns = ArrayList<TextView>()
        if (def.showBar) {
            val bar = LinearLayout(this)
            bar.gravity = Gravity.CENTER_VERTICAL
            bar.setPadding(0, dp(2f), 0, dp(10f))
            for (j in def.levels.indices) {
                val b = label(def.labels[j], 15f)
                b.typeface = Typeface.DEFAULT_BOLD
                b.gravity = Gravity.CENTER
                b.minWidth = dp(54f)
                b.setPadding(dp(14f), dp(9f), dp(14f), dp(9f))
                b.setOnClickListener { showLevel(index, j) }
                btns.add(b)
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.rightMargin = dp(8f)
                bar.addView(b, lp)
            }
            body.addView(bar, rowParams())
        }
        val boxes = ArrayList<LinearLayout>()
        for (j in def.levels.indices) {
            val lb = LinearLayout(this)
            lb.orientation = LinearLayout.VERTICAL
            lb.visibility = View.GONE
            body.addView(lb, rowParams())
            boxes.add(lb)
        }
        card.addView(body, rowParams())
        catCards.add(card)
        card.background = null
        catBodies.add(body)
        catArrows.add(arrow)
        catLevelBoxes.add(boxes)
        catLevelBtns.add(btns)
        head.setOnClickListener {
            if (def.locked) { Toast.makeText(this, R.string.tab_locked, Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            if (expandedCat == index) collapseCat(index) else expandCat(index)
        }
    }

    /** Zobrazi uroven `lv` kategorie `index` (obsah se postavi pri prvnim zobrazeni). */
    private fun showLevel(index: Int, lv: Int) {
        catLevel[index] = lv
        for ((j, b) in catLevelBoxes[index].withIndex()) {
            b.visibility = if (j == lv) View.VISIBLE else View.GONE
            if (j == lv && b.childCount == 0) catDefs[index].levels[j](b)
        }
        paintCatCard(index)
        paintPills()
    }

    /**
     * Pozadi karty kategorie: zavrena = prechod zleva doprava mezi sedou a modrou (sude karty ze tmave sede do svetle modre,
     * liche z tmave modre do svetle sede); otevrena kategorie s urovnemi (1.-4.) = prechod shora dolu podle urovne
     * (I zelenosedou, II oranzovosedou, III cervenosedou) do barvy karty.
     */
    private fun paintCatCard(index: Int) {
        val open = expandedCat == index
        val def = catDefs[index]
        val g = if (open && def.showBar && def.titleRes != R.string.cat_waiting) {
            val tone = when (catLevel[index]) { 0 -> 0xFF3A5A3C.toInt(); 1 -> 0xFF66503A.toInt(); else -> 0xFF633B3B.toInt() }
            GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(tone, card))
        } else {
            val cols = if (index % 2 == 0) intArrayOf(0xFF26262C.toInt(), 0xFF2E4A68.toInt()) else intArrayOf(0xFF1F3552.toInt(), 0xFF38383F.toInt())
            GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, cols)
        }
        g.cornerRadius = dp(16f).toFloat()
        catCards[index].background = g
    }

    private fun expandCat(index: Int) {
        for (i in catBodies.indices) { if (i != index) collapseCat(i) }
        expandedCat = index
        animateArrow(index, true)
        paintCatCard(index)
        catBodies[index].visibility = View.VISIBLE
        // Vzdy se zacina prvni urovni.
        showLevel(index, 0)
        autoTestIfNeeded()
        applyImeLayout()
        tabScroll.post { tabScroll.smoothScrollTo(0, max(0, catCards[index].top - dp(8f))) }
    }

    /** Sipka otevrene kategorie: pomalu se otoci o 45 stupnu, pak se zvetsi na 150 % a zabarvi do akcentu. */
    private fun animateArrow(index: Int, open: Boolean) {
        val a = catArrows[index]
        a.animate().cancel()
        if (open) {
            a.animate().rotation(45f).setDuration(700L).setInterpolator(DecelerateInterpolator()).withEndAction {
                if (expandedCat == index) {
                    a.outline = accent
                    a.animate().scaleX(1.5f).scaleY(1.5f).setDuration(220L).start()
                    // Sipka se zleva doprava vyplni zlutou a vyletí jiskry (stejne jako u radku na hlavni obrazovce).
                    a.fillTo(true, ChevronView.YELLOW, true)
                }
            }.start()
        } else {
            // Nejdriv se otoci zpet o 45 stupnu, teprve potom se zmensi a zabarvi.
            a.animate().rotation(0f).setDuration(450L).setInterpolator(DecelerateInterpolator()).withEndAction {
                if (expandedCat != index) {
                    a.outline = dim
                    a.animate().scaleX(1f).scaleY(1f).setDuration(220L).start()
                    a.fillTo(false, ChevronView.YELLOW, false)
                }
            }.start()
        }
    }

    private fun collapseCat(index: Int) {
        if (index !in catBodies.indices) return
        catBodies[index].visibility = View.GONE
        animateArrow(index, false)
        if (expandedCat == index) {
            expandedCat = -1
            autoTestIfNeeded()
        }
        paintCatCard(index)
        applyImeLayout()
    }

    /** 0. Rychle: styly, jazyk, rada cislic, oddelovac, jemny stin, obrys specialnich, pisma. */
    private fun buildFastLevel(box: LinearLayout) {
        val s = section(box, getString(R.string.sec_fast))
        presetRow(s)
        choiceRow(s, R.string.language, "language", listOf("cs", "en", "cs+en"), listOf(getString(R.string.lang_cs), getString(R.string.lang_en), getString(R.string.lang_both)))
        choiceRow(s, R.string.layout, "layout", listOf("qwertz", "qwerty"), listOf("QWERTZ", "QWERTY"))
        switchRow(s, R.string.show_number_row, "show_number_row")
        switchRow(s, R.string.number_divider, "number_divider", hintRes = R.string.number_divider_hint, dependsOn = "show_number_row")
        switchRow(s, R.string.key_shadow, "key_shadow", hintRes = R.string.key_shadow_hint)
        switchRow(s, R.string.special_outline, "special_outline", hintRes = R.string.special_outline_hint)
        fontRow(s, R.string.key_font_label, "key_font")
    }

    /** 1. I. Rozmery. */
    private fun buildLayoutDims(box: LinearLayout) {
        val s = section(box, getString(R.string.sec_layout_dims))
        sliderRow(s, R.string.key_height, "key_height_dp", 48, 72, 1) { getString(R.string.unit_dp, it) }
        sliderRow(s, R.string.waiting_key_width, "key_width_dp", 24, 48, 1) { getString(R.string.unit_dp, it.coerceAtLeast(24)) }
        sliderRow(s, R.string.key_radius, "key_radius_dp", 2, 22, 1) { getString(R.string.unit_dp, it) }
        sliderRow(s, R.string.key_gap, "key_gap_dp", 0, 10, 1) { getString(R.string.unit_dp, it) }
        sliderRow(s, R.string.row_gap, "row_gap_dp", 0, 16, 1) { getString(R.string.unit_dp, it) }
        sliderRow(s, R.string.key_text_size, "key_text_percent", 70, 200, 1) { getString(R.string.unit_percent, it) }
        sliderRow(s, R.string.hint_size, "hint_percent", 60, 180, 1) { getString(R.string.unit_percent, it) }
    }

    /** 1. II. Poloha a tloustka. */
    private fun buildLayoutPos(box: LinearLayout) {
        val s = section(box, getString(R.string.sec_layout_pos))
        labelAdjustButton = adjustRow(s, R.string.label_pos, R.string.label_adjust, true)
        hintAdjustButton = adjustRow(s, R.string.layout_hint_pos, R.string.hint_adjust, false)
        // Tloustka se meni jen u systemoveho pisma; vlastni pismo ma tloustku pevnou, proto se posuvniky zatmi.
        val systemFont = { RustBridge.settings.json.optString("key_font", "system") == "system" }
        sliderRow(s, R.string.label_weight, "label_weight", 100, 900, 1, 100, systemFont) { it.toString() }
        sliderRow(s, R.string.digit_weight, "digit_weight", 100, 900, 1, 100, systemFont) { it.toString() }
    }

    /** 1. III. Spodni rada. */
    private fun buildLayoutBottom(box: LinearLayout) {
        val s = section(box, getString(R.string.sec_bottom_row))
        val places = listOf("left", "right", "hidden")
        val placeLabels = listOf(getString(R.string.key_place_left), getString(R.string.key_place_right), getString(R.string.key_place_hidden))
        choiceRow(s, R.string.macro_key_label, "macro_key", places, placeLabels)
        hintText(s, getString(R.string.bottom_row_hint))
        // Uprava vysky radku: klavesnice se ztmavi a u kazde rady je sipka, kterou jde rada posunout nahoru / dolu.
        rowEditButton = buttonRow(s, getString(R.string.row_edit), accent) { if (adjusting) finishAdjust() else startRowEdit() }
        // Vyska listy navrhu nad klavesami (dolaďovani vysky klavesnice).
        sliderRow(s, R.string.bar_height, "bar_height_dp", 36, 64, 1) { getString(R.string.unit_dp, it) }
    }

    /** 2. I. Zakladni barvy. */
    private fun buildColors1(box: LinearLayout) {
        val s = section(box, getString(R.string.cat_colors_1))
        colorRow(s, R.string.custom_background, "custom_background")
        colorRow(s, R.string.custom_key, "custom_key")
        colorRow(s, R.string.custom_text, "custom_text")
        colorRow(s, R.string.outline_color, "outline_color", allowAuto = true)
        sliderRow(s, R.string.outline_width, "outline_width_dp", 1, 6, 1) { getString(R.string.unit_dp, it) }
        colorRow(s, R.string.accent_color, "accent_color")
        colorRow(s, R.string.popup_color, "popup_color", allowAuto = true)
        colorRow(s, R.string.alts_color, "alts_color", allowAuto = true)
    }

    /** 2. II. Vsechny druhe barvy. */
    private fun buildColors2(box: LinearLayout) {
        val s = section(box, getString(R.string.cat_colors_2))
        colorRow(s, R.string.background_color2, "background_color2", allowAuto = true, allowRainbow = true)
        colorRow(s, R.string.key_color2, "key_color2", allowAuto = true, allowRainbow = true)
        colorRow(s, R.string.text_color2, "text_color2", allowAuto = true, allowRainbow = true)
        colorRow(s, R.string.outline_color2, "outline_color2", allowAuto = true, allowRainbow = true)
        colorRow(s, R.string.hint_color, "hint_color", allowAuto = true)
    }

    /** 2. III. Barvy cislic (a jejich druhe barvy). */
    private fun buildColors3(box: LinearLayout) {
        val s = section(box, getString(R.string.cat_colors_3))
        colorRow(s, R.string.digit_key_color, "digit_key_color", allowAuto = true)
        colorRow(s, R.string.digit_text_color, "digit_text_color", allowAuto = true)
        colorRow(s, R.string.digit_key_color2, "digit_key_color2", allowAuto = true, allowRainbow = true)
        colorRow(s, R.string.digit_text_color2, "digit_text_color2", allowAuto = true, allowRainbow = true)
    }

    private fun tapEffects(): Pair<List<String>, List<String>> = Pair(
        listOf("none", "neon", "glitch", "paint", "ghost", "orbit", "pop", "cut", "slide", "plasma"),
        listOf(getString(R.string.effect_none), getString(R.string.tap_effect_neon), getString(R.string.tap_effect_glitch), getString(R.string.tap_effect_paint), getString(R.string.tap_effect_ghost), getString(R.string.tap_effect_orbit), getString(R.string.tap_effect_pop), getString(R.string.glow_style_cut), getString(R.string.tap_effect_slide), getString(R.string.tap_effect_plasma)),
    )

    /** 3. I. Vyber animace + barva (rovnou 3 barvy vybarveni). */
    private fun buildTap1(box: LinearLayout) {
        val s = section(box, getString(R.string.cat_tap_1))
        val (values, labels) = tapEffects()
        dropdownRow(s, R.string.tap_effect_label, values, labels,
            { val j = RustBridge.settings.json; if (j.optBoolean("key_glow")) j.optString("tap_effect") else "none" },
            { v -> if (v == "none") update("key_glow", false) else updateAll("key_glow" to true, "tap_effect" to v) }, "tap_effect")
        colorDotsRow(s, listOf(R.string.paint_color1 to "paint_color1", R.string.paint_color2 to "paint_color2", R.string.paint_color3 to "paint_color3")) {
            val j = RustBridge.settings.json
            j.optBoolean("key_glow") && j.optString("tap_effect") == "paint"
        }
        colorRow(s, R.string.tap_text_color, "tap_text_color", allowAuto = true)
        colorRow(s, R.string.outline_tap_color, "outline_tap_color", allowAuto = true)
    }

    /** 3. II. Druha barva klavesy a znaku po klepnuti. */
    private fun buildTap2(box: LinearLayout) {
        val s = section(box, getString(R.string.cat_tap_2))
        // Barva klavesy po klepnuti (barva efektu); jeji druha barva je v III.
        colorRow(s, R.string.tap_key_color, "neon_color")
        colorRow(s, R.string.tap_text_color2, "tap_text_color2", allowAuto = true)
    }

    /** 3. III. Druha barva obrysu po klepnuti. */
    private fun buildTap3(box: LinearLayout) {
        val s = section(box, getString(R.string.cat_tap_3))
        // Druha barva efektu: Neon a Duch ji strida, Glitch losuje; Barvy maji dalsi tri barvy; Vybuch a Rez ji pouzivaji take.
        colorRow(s, R.string.tap_key_color2, "neon_color2", allowAuto = true, allowRainbow = true)
        colorDotsRow(s, listOf(R.string.paint_color4 to "paint_color4", R.string.paint_color5 to "paint_color5", R.string.paint_color6 to "paint_color6")) {
            val j = RustBridge.settings.json
            j.optBoolean("key_glow") && j.optString("tap_effect") == "paint"
        }
        colorRow(s, R.string.outline_tap_color2, "outline_tap_color2", allowAuto = true)
    }

    /** 4. I. Linka + pruchod + uspesne slovo. */
    private fun buildSwipe1(box: LinearLayout) {
        val s = section(box, getString(R.string.cat_swipe_1))
        switchRow(s, R.string.swipe_trail, "swipe_trail")
        val trailValues = listOf("line", "dashed", "fire", "pixel")
        val trailLabels = listOf(getString(R.string.trail_style_line), getString(R.string.trail_style_dashed), getString(R.string.trail_style_fire), getString(R.string.trail_style_pixel))
        dropdownRow(s, R.string.trail_style_label, trailValues, trailLabels, { RustBridge.settings.json.optString("trail_style") }, { update("trail_style", it) }, "trail_style")
        // Ohen ma vlastni barvy plamene: barvy stopy se u nej skryji, zustane jen sirka.
        val trailColors = LinearLayout(this)
        trailColors.orientation = LinearLayout.VERTICAL
        s.addView(trailColors, rowParams())
        colorRow(trailColors, R.string.trail_color, "trail_color", allowAuto = true)
        val applyTrailColors = { trailColors.visibility = if (RustBridge.settings.json.optString("trail_style") == "fire") View.GONE else View.VISIBLE }
        applyTrailColors()
        fontPainters.add { applyTrailColors() }
        sliderRow(s, R.string.trail_width, "trail_width_dp", 1, 12, 1) { getString(R.string.unit_dp, it) }
        val glowValues = listOf("none", "neon", "paint", "ghost", "flip")
        val glowLabels = listOf(getString(R.string.anim_none), getString(R.string.tap_effect_neon), getString(R.string.glow_style_paint), getString(R.string.glow_style_ghost), getString(R.string.glow_style_flip))
        dropdownRow(s, R.string.glow_style_label, glowValues, glowLabels,
            { val j = RustBridge.settings.json; if (j.optBoolean("swipe_glow")) j.optString("glow_style") else "none" },
            { v -> if (v == "none") update("swipe_glow", false) else updateAll("swipe_glow" to true, "glow_style" to v) }, "glow_style")
        colorRow(s, R.string.glow_color, "glow_color", allowRainbow = extraOpen)
    }

    /** 4. II. Barva pruchodu + druhe barvy. */
    private fun buildSwipe2(box: LinearLayout) {
        val s = section(box, getString(R.string.cat_swipe_2))
        // Druha barva cary: ohen ma vlastni barvy plamene, takze se u nej skryje.
        val trailColors2 = LinearLayout(this)
        trailColors2.orientation = LinearLayout.VERTICAL
        s.addView(trailColors2, rowParams())
        colorRow(trailColors2, R.string.trail_color2, "trail_color2", allowAuto = true)
        val applyTrailColors2 = { trailColors2.visibility = if (RustBridge.settings.json.optString("trail_style") == "fire") View.GONE else View.VISIBLE }
        applyTrailColors2()
        fontPainters.add { applyTrailColors2() }
        colorRow(s, R.string.glow_color2, "glow_color2", allowAuto = true)
    }

    /** 4. III. Barva pruchodu a barvy pod prstem. */
    private fun buildSwipe3(box: LinearLayout) {
        val s = section(box, getString(R.string.cat_swipe_3))
        colorRow(s, R.string.passed_text_color, "passed_text_color", allowAuto = true, dependsOn = "swipe_glow")
        colorRow(s, R.string.outline_passed_color, "outline_passed_color", allowAuto = true, dependsOn = "swipe_glow")
        colorRow(s, R.string.hover_text_color, "hover_text_color", allowAuto = true, dependsOn = "swipe_glow")
        colorRow(s, R.string.outline_hover_color, "outline_hover_color", allowAuto = true, dependsOn = "swipe_glow")
    }

    /** 5. Konfigurace klavesnice: klik + podrzeni = uprava pismenka / makra. */
    private fun buildCatKeys(box: LinearLayout) {
        section(box, getString(R.string.sec_keys))
        box.addView(label(getString(R.string.layout_hint), 14f, dim).also { it.setPadding(0, dp(10f), 0, dp(4f)) }, rowParams())
        val modes = listOf(KeyboardView.MODE_LETTERS to R.string.layout_mode_letters, KeyboardView.MODE_SYMBOLS to R.string.layout_mode_symbols, KeyboardView.MODE_SYMBOLS2 to R.string.layout_mode_symbols2)
        val row = LinearLayout(this)
        for ((i, m) in modes.withIndex()) {
            val p = label(getString(m.second), 14f); p.gravity = Gravity.CENTER; p.setPadding(dp(4f), dp(10f), dp(4f), dp(10f))
            p.setOnClickListener { if (adjusting) return@setOnClickListener; currentBubble?.hide(immediate = true); shownMode = m.first; preview.setMode(m.first); paintPills() }
            pills.add(p); val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); if (i > 0) lp.leftMargin = dp(6f); row.addView(p, lp)
        }
        val rlp = rowParams(); rlp.topMargin = dp(10f); rlp.bottomMargin = dp(6f); box.addView(row, rlp)
        statusView = label("", 14f, dim).also { it.setPadding(0, dp(10f), 0, dp(6f)); box.addView(it, rowParams()) }
        cancelSwapView = buttonRow(box, getString(R.string.layout_swap_cancel), accent) { cancelSwap() }
        cancelSwapView?.visibility = View.GONE
        buttonRow(box, getString(R.string.layout_reset), danger) { confirmDanger(getString(R.string.layout_reset_confirm), getString(R.string.layout_reset_do)) { cancelSwap(); writeMaps(LinkedHashMap(), LinkedHashMap(), resetSwaps = true, clearMacros = true) } }
        hintText(box, getString(R.string.cat_keys_hint))
        updateStatus()
    }

    /** Waiting jako posledni kategorie (IV), porad zamcena: obrys znaku (ano / ne, barva) a zacatek prechodu druhych barev. */
    private fun buildCatWaiting(box: LinearLayout) {
        var s = section(box, getString(R.string.sec_waiting_char))
        switchRow(s, R.string.waiting_char_outline, "char_outline", hintRes = R.string.waiting_char_outline_hint)
        colorRow(s, R.string.waiting_char_outline_color, "char_outline_color", allowAuto = true)
        s = section(box, getString(R.string.sec_waiting_second))
        val dirValues = listOf("top", "bottom", "left", "right")
        val dirLabels = listOf(getString(R.string.grad_top), getString(R.string.grad_bottom), getString(R.string.grad_left), getString(R.string.grad_right))
        choiceRow(s, R.string.grad_dir_bg, "bg_grad_dir", dirValues, dirLabels)
        choiceRow(s, R.string.grad_dir_text, "text_grad_dir", dirValues, dirLabels)
        choiceRow(s, R.string.grad_dir_key, "key_grad_dir", dirValues, dirLabels)
    }

    /** Tlacitko s popiskem, ktere zapne nastavovani polohy (znaku nebo napovedy) nad klavesnici. */
    private fun adjustRow(box: LinearLayout, labelRes: Int, buttonRes: Int, forLabel: Boolean): TextView {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(label(getString(labelRes), 14f, dim).also { it.setPadding(0, dp(10f), 0, dp(2f)) }, rowParams())
        val button = buttonView(getString(buttonRes), accent) { if (adjusting) finishAdjust() else startAdjust(forLabel) }
        val lp = rowParams()
        lp.topMargin = dp(5f)
        lp.bottomMargin = dp(5f)
        col.addView(button, lp)
        box.addView(col, rowParams())
        return button
    }

    /** Pismo ze souboru v assets/fonts (nebo systemove); pri chybe systemove. */
    private fun faceOf(name: String): Typeface {
        if (name == "system") return Typeface.DEFAULT
        return fontFaces.getOrPut(name) {
            try { Typeface.createFromAsset(assets, "fonts/$name") } catch (_: Throwable) { Typeface.DEFAULT }
        }
    }

    /**
     * Vyber pisma: radek ukazuje vybrane pismo jeho vlastnim pismem; klepnuti rozvine seznam, kde je
     * kazde pismo vykreslene sebou samym (ukazka textu + nazev). Seznam se sklada az pri prvnim otevreni.
     */
    private fun fontRow(box: LinearLayout, labelRes: Int, key: String) {
        val fonts = ArrayList<String>()
        fonts.add("system")
        try {
            val list = assets.list("fonts") ?: emptyArray()
            for (f in list.sorted()) if (f.endsWith(".ttf", true) || f.endsWith(".otf", true) || f.endsWith(".ttc", true)) fonts.add(f)
        } catch (_: Throwable) {}
        fun nice(f: String) = if (f == "system") getString(R.string.font_system) else f.substringBeforeLast('.')
        fun current(): String = RustBridge.settings.json.optString(key, "system").let { if (it in fonts) it else "system" }
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(0, dp(4f), 0, dp(6f))
        val head = LinearLayout(this)
        head.gravity = Gravity.CENTER_VERTICAL
        head.minimumHeight = dp(52f)
        head.addView(label(getString(labelRes)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val shown = label("", 16f, accent)
        shown.maxLines = 1
        head.addView(shown)
        val arrow = label("\u203A", 24f, dim)
        arrow.setPadding(dp(10f), 0, dp(4f), 0)
        head.addView(arrow)
        col.addView(head, rowParams())
        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        list.visibility = View.GONE
        col.addView(list, rowParams())
        val items = ArrayList<LinearLayout>()
        val names = ArrayList<TextView>()
        val samples = ArrayList<TextView>()
        fun paint() {
            val cur = current()
            shown.text = nice(cur)
            shown.typeface = faceOf(cur)
            shown.setTextColor(accent)
            val onA = if (Theme.luminance(accent) > 0.55f) 0xFF111113.toInt() else 0xFFFFFFFF.toInt()
            for ((i, row) in items.withIndex()) {
                val on = fonts[i] == cur
                row.background = rounded(if (on) accent else field, dp(14f).toFloat())
                samples[i].setTextColor(if (on) onA else text)
                names[i].setTextColor(if (on) onA else dim)
            }
        }
        fun fill() {
            for (f in fonts) {
                val item = LinearLayout(this)
                item.orientation = LinearLayout.VERTICAL
                item.setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
                val sample = label(getString(R.string.font_sample), 22f)
                sample.typeface = faceOf(f)
                sample.maxLines = 1
                val name = label(nice(f), 11f, dim)
                item.addView(sample, rowParams())
                item.addView(name, rowParams())
                item.setOnClickListener {
                    keepInPlace(head) {
                        update(key, f)
                        list.visibility = View.GONE
                        arrow.text = "›"
                    }
                    onSettingTouched(key, col)
                }
                val lp = rowParams()
                lp.topMargin = dp(6f)
                list.addView(item, lp)
                items.add(item)
                samples.add(sample)
                names.add(name)
            }
            paint()
        }
        head.setOnClickListener {
            val open = list.visibility != View.VISIBLE
            keepInPlace(head) {
                if (open && items.isEmpty()) fill()
                list.visibility = if (open) View.VISIBLE else View.GONE
                arrow.text = if (open) "⌄" else "›"
            }
            onSettingTouched(key, col)
        }
        box.addView(col, rowParams())
        paint()
        fontPainters.add { paint() }
    }

    // ------------------------------------------------------------------ zive ukazky a napoveda k radkum

    /** Slovo do ukazek: FAZT nebo ahoj, jednou za cas (2 %) NEČUM. */
    private fun demoWord(): String {
        val r = Random.nextInt(100)
        return if (r < 2) "NE\u010cUM" else if (r % 2 == 0) "FAZT" else "ahoj"
    }

    /** Zakladni pismena slova (bez diakritiky, malymi). */
    private fun baseLetters(word: String): String {
        val n = java.text.Normalizer.normalize(word, java.text.Normalizer.Form.NFD)
        val sb = StringBuilder()
        for (c in n) if (c in 'a'..'z' || c in 'A'..'Z') sb.append(c.lowercaseChar())
        return sb.toString()
    }

    /** Zablikne klavesami slova, ostatni ztlumi. */
    private fun spotWord(word: String) {
        val letters = baseLetters(word)
        preview.spotKeys(1400L) { it.isLetter && letters.indexOf(it.code.toChar()) >= 0 }
    }

    /** Druh zive ukazky k nastaveni: co se na klavesach zahraje (slovo, bublina...). */
    private fun demoKind(key: String): Int = when (key) {
        "neon_color", "neon_color2", "tap_effect", "tap_text_color", "tap_text_color2", "outline_tap_color", "outline_tap_color2" -> DEMO_TAP
        "paint_color1", "paint_color2", "paint_color3", "paint_color4", "paint_color5", "paint_color6" -> DEMO_PAINT
        "key_font" -> DEMO_FONT
        "key_popup", "popup_color" -> DEMO_POPUP
        "alts_color" -> DEMO_ALTS
        "animations" -> DEMO_PRESS
        "swipe_trail", "trail_style", "trail_color", "trail_color2", "trail_width_dp",
        "passed_text_color", "outline_passed_color", "hover_text_color", "outline_hover_color" -> DEMO_SWIPE
        "glow_style", "glow_color", "glow_color2", "swipe_glow" -> DEMO_GLOW
        else -> DEMO_NONE
    }

    private fun demoFor(key: String) {
        if (explicitTest() || adjusting || !previewOpen) return
        // Pri vyberu barvy bubliny bublina zustava videt (po zmene se jen obnovi).
        if (pickerKey == "popup_color" && demoKind(key) == DEMO_POPUP) {
            preview.holdPopup(true)
            return
        }
        if (pickerKey == "alts_color" && demoKind(key) == DEMO_ALTS) {
            preview.holdAlts(true)
            return
        }
        // V kategoriich animaci bezi opakovana ukazka: po zmene se jen rozbehne hned.
        if (loopWanted() && demoKind(key) != DEMO_NONE) {
            restartLoop(250L)
            return
        }
        if (SystemClock.uptimeMillis() - lastTouchAt < USER_IDLE_MS) return
        val kind = demoKind(key)
        if (kind == DEMO_NONE) return
        val w = demoWord()
        when (kind) {
            DEMO_TAP -> { spotWord(w); preview.demoNeon(w) }
            DEMO_PAINT -> { spotWord(w); preview.demoPaint(w) }
            DEMO_FONT -> preview.demoFont()
            DEMO_POPUP -> preview.demoPopup()
            DEMO_ALTS -> preview.demoAlts()
            DEMO_PRESS -> { spotWord(w); preview.demoPress(w) }
            DEMO_SWIPE -> preview.demoSwipe()
            DEMO_GLOW -> { spotWord(w); preview.glowWord(w) }
        }
    }

    /** V nahledu zablikne to, k cemu nastaveni patri (v barve akcentu), ostatni klavesy se ztlumi. */
    private fun spotFor(key: String) {
        if (explicitTest() || adjusting || !previewOpen || loopWanted()) return
        val kv = preview
        when (key) {
            "number_divider" -> kv.spotDivider()
            "outline_width_dp", "outline_color" -> kv.spotKeys(mode = KeyboardView.SPOT_OUTLINE) { true }
            "key_shadow" -> kv.spotShadow()
            "custom_background", "background_color2" -> kv.spotBackground()
            "show_number_row", "digit_text_color", "digit_key_color", "digit_weight" -> kv.spotKeys { it.isDigit }
            "layout" -> kv.spotKeys { it.isLetter && (it.code == 'y'.code || it.code == 'z'.code) }
            "language" -> kv.spotKeys { it.kind == KeyboardView.KIND_SPACE }
            "macro_key" -> kv.spotKeys { it.kind == KeyboardView.KIND_MACRO }
            "special_outline" -> kv.spotKeys(mode = KeyboardView.SPOT_OUTLINE) { it.kind == KeyboardView.KIND_SHIFT || it.kind == KeyboardView.KIND_MODE || it.kind == KeyboardView.KIND_BACKSPACE || it.kind == KeyboardView.KIND_EMOJI || it.kind == KeyboardView.KIND_MACRO }
            "accent_color" -> kv.spotKeys { it.kind == KeyboardView.KIND_ENTER || it.kind == KeyboardView.KIND_SHIFT }
            "hint_color", "hint_percent", "key_hints" -> kv.spotKeys { it.hint.isNotEmpty() }
            "key_font", "key_text_percent", "label_weight" -> kv.spotKeys { it.kind == KeyboardView.KIND_CHAR }
            else -> kv.spotKeys { true }
        }
    }

    /** Napoveda k radku: bublina s popiskem nad radkem, po chvilce se sama zasune. */
    private fun showTip(key: String, anchor: View) {
        if (editor != null || adjusting) return
        val res = TIPS[key] ?: return
        val tv = label(getString(res), 13.5f)
        tv.setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
        val bubble = Bubble(this, tv, passThrough = true, autoHideMs = 3500L)
        bubble.show(bubble.anchorOf(anchor))
    }

    // ------------------------------------------------------------------ vlastni styly (sloty)

    private fun slotJson(i: Int): JSONObject? = RustBridge.settings.json.optJSONArray("style_slots")?.optJSONObject(i)

    /** Soucasny vzhled je ulozeny ve slotu `i` (vsechny klice slotu maji stejnou hodnotu). */
    private fun slotMatches(i: Int): Boolean {
        val slot = slotJson(i) ?: return false
        val cur = RustBridge.settings.json
        for (k in slot.keys()) if (cur.opt(k)?.toString() != slot.opt(k)?.toString()) return false
        return true
    }

    /** Zaznamena zmenu nastaveni do historie; zmeny blize nez UNDO_MERGE_MS (tah posuvniku) tvori jeden krok. */
    private fun trackChange() {
        val cur = RustBridge.settings.json.toString()
        if (cur == lastSnap) return
        changedSinceSave = true // i zmena mimo vzhled profilu (jazyk, rozlozeni...) rozsviti Ulozit
        val now = SystemClock.uptimeMillis()
        if (undoStack.isEmpty() || now - lastChangeAt > UNDO_MERGE_MS) {
            undoStack.add(lastSnap)
            if (undoStack.size > UNDO_MAX) undoStack.removeAt(0)
        }
        lastChangeAt = now
        lastSnap = cur
    }

    /** Ulozit svici, kdyz se vzhled lisi od vybraneho stylu (bez vybraneho stylu po jakekoli zmene). */
    private fun saveEnabled() = if (chosenSlot in 0 until STYLE_SLOTS) (!slotMatches(chosenSlot) || changedSinceSave) else undoStack.isNotEmpty()

    /** Duhove konfety z tlacitka `from` do vsech stran (pridaji se na celou obrazovku a po skonceni zmizi). */
    private fun confetti(from: View) {
        val decor = window.decorView as? ViewGroup ?: return
        val loc = IntArray(2)
        from.getLocationInWindow(loc)
        val v = ConfettiView(this, loc[0] + from.width / 2f, loc[1] + from.height / 2f)
        decor.addView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        v.start { decor.post { decor.removeView(v) } }
    }

    private fun updateActions() {
        val a = accent
        val onA = if (Theme.luminance(a) > 0.55f) 0xFF111113.toInt() else 0xFFFFFFFF.toInt()
        val canSave = saveEnabled()
        backArrow.outline = text
        backBtn.background = rounded(field, dp(14f).toFloat())
        restoreIcon.color = text
        restoreBtn.background = rounded(field, dp(21f).toFloat())
        randomBtn.background = rounded(field, dp(21f).toFloat())
        randomBtn.setTextColor(text)
        // Obnovit jde vzdy: vrati vychozi vzhled vybraneho profilu.
        restoreBtn.isEnabled = true
        restoreBtn.alpha = 1f
        saveBtn.isEnabled = canSave
        saveBtn.alpha = if (canSave) 1f else 0.4f
        saveBtn.setTextColor(if (canSave) onA else text)
        saveBtn.background = rounded(if (canSave) a else field, dp(21f).toFloat())
    }

    private fun rememberSlot(i: Int) {
        getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit().putInt(KEY_CHOSEN_SLOT, i).apply()
    }

    private fun paintSlots() {
        for ((i, v) in slotViews.withIndex()) {
            v.colors = slotJson(i)?.let { Theme.from(Settings(it)) }
            v.ring = accent
            v.chosen = i == chosenSlot
        }
    }

    /**
     * Klepnuti na slot: rovnou prepne a nacte styl. Ma-li soucasny styl neulozene zmeny, zepta se, zda
     * ho opravdu zmenit bez ulozeni (Ano = prepnout, Ne = zustat).
     */
    private fun pickSlot(i: Int) {
        val unsaved = if (chosenSlot in 0 until STYLE_SLOTS) !slotMatches(chosenSlot) else (0 until STYLE_SLOTS).none { slotMatches(it) }
        if (unsaved) {
            confirmDialog(getString(R.string.style_switch_unsaved), getString(R.string.yes), getString(R.string.no), false) { switchTo(i) }
        } else {
            switchTo(i)
        }
    }

    private fun switchTo(i: Int) {
        chosenSlot = i
        rememberSlot(i)
        paintSlots()
        if (!slotMatches(i)) loadSlot(i)
        changedSinceSave = false
        updateActions()
    }

    private fun loadSlot(i: Int) {
        RustBridge.loadStyle(i)
        Toast.makeText(this, getString(R.string.style_loaded, i + 1), Toast.LENGTH_SHORT).show()
    }

    /** Obnova vybraneho profilu: zahodi neulozene zmeny a nacte ulozenou podobu (po potvrzeni). */
    private fun askRestoreSlot() {
        val i = chosenSlot
        if (i !in 0 until STYLE_SLOTS) return
        confirmDialog(getString(R.string.style_restore_confirm), getString(R.string.yes), getString(R.string.no), false) {
            RustBridge.loadDefaultStyle(i)
            Toast.makeText(this, getString(R.string.style_restored_default, i + 1), Toast.LENGTH_SHORT).show()
            changedSinceSave = true
            paintSlots()
            updateActions()
            restoreIcon.spin()
        }
    }

    /** Ulozi aktualni vzhled do vybraneho profilu; vraci true, kdyz se ulozilo. */
    private fun saveSlot(): Boolean {
        val i = chosenSlot
        if (i !in 0 until STYLE_SLOTS) {
            Toast.makeText(this, R.string.style_pick_first, Toast.LENGTH_SHORT).show()
            return false
        }
        RustBridge.saveStyle(i)
        changedSinceSave = false
        paintSlots()
        Toast.makeText(this, getString(R.string.style_saved, i + 1), Toast.LENGTH_SHORT).show()
        return true
    }

    private fun paintPills() {
        val a = accent
        val onA = if (Theme.luminance(a) > 0.55f) 0xFF111113.toInt() else 0xFFFFFFFF.toInt()
        fun paint(p: TextView, on: Boolean) {
            p.background = rounded(if (on) a else field, dp(12f).toFloat())
            p.setTextColor(if (on) onA else text)
        }
        val order = listOf(KeyboardView.MODE_LETTERS, KeyboardView.MODE_SYMBOLS, KeyboardView.MODE_SYMBOLS2)
        for ((i, p) in pills.withIndex()) paint(p, order[i] == shownMode)
        for ((i, btns) in catLevelBtns.withIndex()) {
            for ((j, b) in btns.withIndex()) paint(b, j == catLevel[i])
        }
        // Tlacitko TEST je plne zaobleny "pill".
        testButton.background = rounded(if (testMode) a else field, dp(21f).toFloat())
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
        val j = RustBridge.settings.json
        val out = HashSet<String>()
        for (name in arrayOf("key_macros", "key_macros_hold")) j.optJSONObject(name)?.keys()?.forEach { out.add(it) }
        return out
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
        for (name in MACRO_MAPS) {
            if (clearMacros) j.put(name, JSONObject()) else if (dropMacroFor != null) j.optJSONObject(name)?.remove(dropMacroFor)
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
            // Prohozeni specialnich klaves vzdy potvrdi uzivatel (Ano / Ne).
            val ask = if (swapKey == "swap_mode_enter") {
                if (on) R.string.swap_confirm_mode_enter_on else R.string.swap_confirm_mode_enter_off
            } else {
                if (on) R.string.swap_confirm_shift_delete_on else R.string.swap_confirm_shift_delete_off
            }
            confirmDialog(getString(ask), getString(R.string.yes), getString(R.string.no), false) {
                val j = RustBridge.settings.edit()
                j.put(swapKey, on)
                RustBridge.setSettings(j)
                val msg = if (swapKey == "swap_mode_enter") {
                    if (on) R.string.swap_mode_enter_on else R.string.swap_mode_enter_off
                } else {
                    if (on) R.string.swap_shift_delete_on else R.string.swap_shift_delete_off
                }
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
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
        charEdit.isSingleLine = true
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
        altsEdit.isSingleLine = true
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
        // Prirazena makra klavesy: male tlumene radky pod polem.
        key.macro?.let { m -> form.addView(macroLine(R.string.key_editor_macro_click, m), rowParams()) }
        key.holdMacro?.let { m -> form.addView(macroLine(R.string.key_editor_macro_hold, m), rowParams()) }
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

    private fun macroLine(fmtRes: Int, m: RustBridge.Macro): TextView =
        label(getString(fmtRes, m.name), 12f, dim).also {
            it.setPadding(0, dp(8f), 0, 0)
            it.maxLines = 1
            it.ellipsize = TextUtils.TruncateAt.END
        }

    /**
     * Seznam ulozenych maker (nazev + zkratka) a "Bez makra". Zvolene makro se pak priradi na klik nebo na podrzeni
     * (druhy dialog); pri klepnuti se klavese nahodne vybere jeden z obrazku. Po zapisu se editor zavre.
     */
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
                if (which == 0) writeKeyMacro(key, 0, hold = false) else pickMacroPlace(key, macros[which - 1].id)
            }
            .show()
    }

    /** Kam s makrem: na klik (klavesa ukaze obrazek) nebo na podrzeni (klavesa zustane, jak je). */
    private fun pickMacroPlace(key: KeyboardView.Key, id: Long) {
        AlertDialog.Builder(this)
            .setTitle(R.string.key_macro_place_title)
            .setItems(arrayOf(getString(R.string.key_macro_on_click), getString(R.string.key_macro_on_hold))) { _, which ->
                writeKeyMacro(key, id, hold = which == 1)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Zapise makro klavese (`id` 0 = smazat obe makra klavesy i s obrazkem) a zavre editor. */
    private fun writeKeyMacro(key: KeyboardView.Key, id: Long, hold: Boolean) {
        closeEditor()
        val src = srcString(key)
        val j = RustBridge.settings.edit()
        for (name in MACRO_MAPS) {
            val o = j.optJSONObject(name) ?: JSONObject().also { j.put(name, it) }
            if (id == 0L) o.remove(src)
        }
        if (id != 0L) {
            if (hold) {
                j.getJSONObject("key_macros_hold").put(src, id)
            } else {
                j.getJSONObject("key_macros").put(src, id)
            }
        }
        RustBridge.setSettings(j)
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
        if (cp !in 0..0xFFFF || Character.isWhitespace(cp) || Character.isISOControl(cp)) {
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
        const val STYLE_SLOTS = 5
        const val UNDO_MERGE_MS = 700L
        const val UNDO_MAX = 50
        const val UI_PREFS = "appearance_ui"
        const val KEY_CHOSEN_SLOT = "chosen_slot"
        const val MAX_ALTS_EDIT = 5

        const val CAT_TAP = 3
        const val CAT_SWIPE = 4
        const val CAT_KEYS = 5
        const val CAT_EXTRA = 6
        const val USER_IDLE_MS = 3000L
        const val SWIPE_WORD = "asdfghjkl"

        const val DEMO_NONE = 0
        const val DEMO_TAP = 1
        const val DEMO_PAINT = 2
        const val DEMO_POPUP = 3
        const val DEMO_ALTS = 4
        const val DEMO_PRESS = 5
        const val DEMO_SWIPE = 6
        const val DEMO_GLOW = 8
        const val DEMO_FONT = 9

        /** Plynule meneny rozmer: pri tazeni posuvniku klavesy neblikaji (zmena je videt sama). */
        val NO_SPOT: Set<String> = setOf(
            "key_height_dp", "bar_height_dp", "key_width_dp", "key_text_percent", "hint_percent",
            "key_radius_dp", "key_gap_dp", "row_gap_dp",
        )

        /** Napoveda k radkum nastaveni: klic -> text. */
        val TIPS: Map<String, Int> = mapOf(
            "show_number_row" to R.string.tip_number_row,
            "number_divider" to R.string.number_divider_hint,
            "key_shadow" to R.string.key_shadow_hint,
            "special_outline" to R.string.special_outline_hint,
            "language" to R.string.tip_language,
            "layout" to R.string.tip_layout,
            "key_height_dp" to R.string.tip_key_height,
            "key_width_dp" to R.string.tip_key_width,
            "digit_key_color2" to R.string.tip_digit_colors2,
            "digit_text_color2" to R.string.tip_digit_colors2,
            "key_radius_dp" to R.string.tip_key_radius,
            "key_gap_dp" to R.string.tip_key_gap,
            "row_gap_dp" to R.string.tip_row_gap,
            "key_text_percent" to R.string.tip_key_text,
            "hint_percent" to R.string.tip_hint_size,
            "key_font" to R.string.tip_key_font,
            "label_weight" to R.string.tip_label_weight,
            "digit_weight" to R.string.tip_digit_weight,
            "macro_key" to R.string.tip_macro_key,
            "hint_color" to R.string.tip_hint_color,
            "custom_background" to R.string.tip_colors_base,
            "custom_key" to R.string.tip_colors_base,
            "custom_text" to R.string.tip_colors_base,
            "outline_color" to R.string.tip_outline_color,
            "outline_width_dp" to R.string.tip_outline_width,
            "accent_color" to R.string.tip_accent,
            "key_popup" to R.string.key_popup_hint,
            "animations" to R.string.animations_hint,
            "popup_color" to R.string.tip_popup_color,
            "alts_color" to R.string.tip_alts_color,
            "digit_key_color" to R.string.tip_digit_colors,
            "digit_text_color" to R.string.tip_digit_colors,
            "background_color2" to R.string.tip_colors2,
            "key_color2" to R.string.tip_colors2,
            "text_color2" to R.string.tip_colors2,
            "outline_color2" to R.string.tip_colors2,
            "neon_color2" to R.string.tip_colors2,
            "trail_color2" to R.string.tip_colors2,
            "glow_color2" to R.string.tip_colors2,
            "tap_text_color2" to R.string.tip_colors2,
            "outline_tap_color2" to R.string.tip_colors2,
            "tap_effect" to R.string.tip_tap_effect,
            "neon_color" to R.string.tip_neon_color,
            "tap_text_color" to R.string.tip_tap_text,
            "outline_tap_color" to R.string.tip_outline_tap,
            "swipe_trail" to R.string.tip_swipe_trail,
            "trail_style" to R.string.tip_trail_style,
            "trail_color" to R.string.tip_trail_color,
            "trail_width_dp" to R.string.tip_trail_width,
            "swipe_glow" to R.string.tip_swipe_glow,
            "glow_style" to R.string.tip_glow_style,
            "glow_color" to R.string.tip_glow_color,
            "passed_text_color" to R.string.tip_passed,
            "outline_passed_color" to R.string.tip_passed,
            "hover_text_color" to R.string.tip_hover,
            "outline_hover_color" to R.string.tip_hover,
        )


        /** Mapy maker na klavesach (klik, podrzeni) a obrazku maker na klik; vsechny se rusi spolecne. */
        val MACRO_MAPS = arrayOf("key_macros", "key_macros_hold")
        const val BLUE = 0xFF3B82F6.toInt()
        const val ORANGE = 0xFFF59E0B.toInt()
        const val GREEN = 0xFF22C55E.toInt()
        const val PURPLE = 0xFFA855F7.toInt()
    }
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

/** Ctvercove tlacitko slotu stylu: pozadi, klavesa a cislo v barvach ulozeneho stylu; vybrany ma obrys v accent barve. */
@SuppressLint("ViewConstructor")
private class StyleSlotView(context: Context, private val number: String, private val fixed: Int = 0) : View(context) {
    var colors: Theme? = null
        set(value) {
            field = value
            invalidate()
        }
    var chosen = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }
    var ring = 0xFF0080FF.toInt()
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = android.graphics.RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = if (fixed > 0) fixed else MeasureSpec.getSize(widthMeasureSpec).takeIf { it > 0 } ?: (56f * d).toInt()
        setMeasuredDimension(w, w)
    }

    override fun onDraw(canvas: Canvas) {
        val c = colors ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val r = 12f * d
        paint.style = Paint.Style.FILL
        paint.color = c.background
        rect.set(d, d, w - d, h - d)
        canvas.drawRoundRect(rect, r, r, paint)
        val inset = w * 0.2f
        paint.color = c.key
        rect.set(inset, inset, w - inset, h - inset)
        canvas.drawRoundRect(rect, 6f * d, 6f * d, paint)
        paint.color = c.text
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = h * 0.32f
        paint.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText(number, w / 2f, h / 2f - (paint.ascent() + paint.descent()) / 2f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (if (chosen) 3f else 1f) * d
        paint.color = if (chosen) ring else 0x33FFFFFF
        rect.set(1.5f * d, 1.5f * d, w - 1.5f * d, h - 1.5f * d)
        canvas.drawRoundRect(rect, r, r, paint)
    }
}
