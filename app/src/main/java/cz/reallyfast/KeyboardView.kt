package cz.reallyfast

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Vykresleni klaves pres Canvas a zpracovani dotyku.
 *
 * Geometrii klaves pocita Rust (RustBridge.layout); tady se jen kresli a sbiraji vzorky tahu.
 * 120 Hz: onDraw nealokuje (Paint/Path/RectF se znovu pouzivaji), prekresluje se jen pri zmene
 * a animace stisku (scale 0.96 + barva, 80 ms) bezi pres postInvalidateOnAnimation.
 * S Pen: hover se ignoruje (zadne nahodne stisky), dotyk perem funguje jako prst.
 */
class KeyboardView(context: Context) : View(context) {

    class Key(
        val x: Float, val y: Float, val w: Float, val h: Float,
        val vx: Float, val vw: Float,
        val code: Int, val kind: Int,
        val label: String, val alts: String, val desc: String,
        /** Puvodni kod pozice (jiny nez `code`, kdyz je klavesa premapovana). */
        val src: Int = code,
    ) {
        var pressed = false

        /** Prst pres klavesu prejel behem aktualniho swipe. */
        var passed = false

        /** Zacatek doznivani barvy znaku po projeti (po skonceni tahu); 0 = nedoznivaji. */
        var passedFadeAt = 0L

        /** Zacatek doznivani neonu po klepnuti; 0 = nesviti, -1 = klavesa je drzena (naplno). */
        var neonAt = 0L
        /** Barva neonu tohoto klepnuti (stridani dvou barev / duha); 0 = barva efektu. Nastavuje se pri stisku. */
        var neonCol = 0
        /** Cas klepnuti pro efekt Duch (dozvuk), 0 = zadny. */
        var ghostAt = 0L
        var ghostCol2 = 0
        /** Efekt Obvod: cas klepnuti (0 = nic) a nahodny bod startu na obrysu (0..1 delky obvodu). */
        var orbitAt = 0L
        var orbitStart = 0f
        /** Efekt Plazma: cas klepnuti (0 = nic) a bod (0..1 v klavese), odkud se vlna rozbehne. */
        var plasmaAt = 0L
        var plasmaX = 0.5f
        var plasmaY = 0.5f
        /** Efekt Vybuch: cas klepnuti (0 = nic) a nahodne zrno, z ktereho se odvozuje draha kazde jiskry. */
        var popAt = 0L
        var popSeed = 0f
        /** Barva znaku / obrysu tohoto klepnuti (stridani dvou barev); 0 = zakladni barva. Nastavuje se pri stisku. */
        var tapTxt = 0
        var tapOut = 0
        var changedAt = 0L

        /** Cas posledniho klepnuti (pro barvu znaku po klepnuti); 0 = zadne. */
        var tapAt = 0L
        var cutAt = 0L
        var cutTilt = 0f
        var slideC = 1f
        var slideS = 0f
        var flipAt = 0L
        var flipTarget = 0f
        var flipMix = 0f
        /** Poradi pismene ve slove pri otoceni po tahu (stridani prvni a druhe barvy). */
        var flipIdx = 0
        var slideAt = 0L
        var passAt = 0L
        var passTarget = 0f

        /** Makro prirazene klavese (null = obycejna znakova klavesa); nastavuje reloadLayout. */
        var macro: RustBridge.Macro? = null

        /** Makro na podrzeni klavesy (klavesa si drzi znak i vzhled; null = zadne); nastavuje reloadLayout. */
        var holdMacro: RustBridge.Macro? = null

        /** Ktery obrazek (index do `faceBitmaps`) je na klavese s makrem na klik; nastavuje reloadLayout. */
        var macroFace = 0
        val isLetter: Boolean get() = kind == KIND_CHAR && code in 'a'.code..'z'.code

        /** Hlavni popisek je jedna ASCII cislice. */
        val isDigit: Boolean = kind == KIND_CHAR && label.length == 1 && label[0] in '0'..'9'

        /** Prvni varianta dlouheho stisku - mala napoveda v rohu klavesy. */
        val hint: String =
            if (kind == KIND_CHAR && alts.isNotEmpty()) String(Character.toChars(alts.codePointAt(0))) else ""
    }

    interface Listener {
        /** Okamzita odezva na dotyk (haptika, zvuk). */
        fun onPressFeedback(key: Key)
        fun onKeyTap(key: Key)
        /** Znak vybrany z nabidky dlouheho stisku. */
        fun onAltText(text: String)
        /** Dlouhy stisk klavesy s makrem na podrzeni (`Key.holdMacro`). */
        fun onKeyHold(key: Key)
        fun onSwipeWords(words: List<String>)
        fun onGestureAction(action: Int)
        fun onCursorSteps(steps: Int)
        fun onSpaceLongPress()
        fun onPinchScale(scale: Float)
        /** Polozka nabidky po dlouhem stisku ?123: 0 = schranka, 1 = nastaveni (emoji jsou jen na podrzeni tlacitka maker). */
        fun onMenuAction(kind: Int, index: Int)
        /** Tesne pred dekodovanim noveho swipe (sluzba uzavre uceni predchoziho slova). */
        fun onBeforeSwipe()
        /** Enter podrzen: zacit diktovat. */
        fun onVoiceStart()
        /** Enter pusten (nebo dotyk zrusen): ukoncit poslech. */
        fun onVoiceStop()
        /** Tlacitko smazani na obrazovce diktovani (opakuje se pri drzeni). */
        fun onVoiceDelete()
        /** Tlacitko Play na pozastavene obrazovce diktovani: znovu spustit poslech. */
        fun onVoiceResume()
        /** Velke tlacitko "Odeslat" vpravo uprostred pri diktovani: dokonci prepis a stiskne Enter. */
        fun onVoiceSend()
    }

    companion object {
        /** Zakladni pismeno pro znaky do 0x17F (diakritika pryc); pocita se jednou pri nacteni tridy. */
        private val BASE_LETTER = IntArray(0x180).also { t ->
            for (i in t.indices) {
                t[i] = java.text.Normalizer.normalize(i.toChar().toString(), java.text.Normalizer.Form.NFD)[0].code
            }
        }

        /** Zakladni pismeno (bez diakritiky, malym) jako kod znaku. */
        private fun baseCode(ch: Char): Int {
            val c = Character.toLowerCase(ch).code
            return if (c < BASE_LETTER.size) BASE_LETTER[c] else c
        }

        const val KIND_CHAR = 0
        const val KIND_SHIFT = 1
        const val KIND_BACKSPACE = 2
        const val KIND_MODE = 3
        const val KIND_ENTER = 4
        const val KIND_SPACE = 5
        const val KIND_EMOJI = 7

        const val CODE_SYMBOLS = -3
        const val CODE_LETTERS = -4
        const val CODE_SYMBOLS2 = -5

        const val MODE_LETTERS = 0
        const val MODE_SYMBOLS = 1
        const val MODE_SYMBOLS2 = 2

        const val SHIFT_OFF = 0
        const val SHIFT_ONCE = 1
        const val SHIFT_CAPS = 2

        const val ENTER_DEFAULT = 0
        const val ENTER_SEARCH = 1
        const val ENTER_SEND = 2
        const val ENTER_NEXT = 3
        const val ENTER_DONE = 4

        private const val PRESS_ANIM_MS = 80f
        private const val RELEASE_ANIM_MS = 140f
        private const val PREVIEW_IN_MS = 60f
        private const val PREVIEW_OUT_MS = 90f
        private const val TRAIL_FADE_MS = 180f
        private const val TRAIL_POINTS = 56
        private const val MAX_SAMPLES = 2000

        private const val T_NONE = 0
        private const val T_TAP = 1
        private const val T_SWIPE = 2
        private const val T_SPACE = 3
        private const val T_BACKSPACE = 4
        private const val T_POPUP = 5
        private const val T_CONSUMED = 6
        private const val T_MENU = 7
        private const val T_VOICE = 8

        const val KIND_MACRO = 8

        const val MENU_CLIPBOARD = 0
        const val MENU_SETTINGS = 1

        /** Druh nabidky: rychla (drzeni ?123) nebo seznam maker. */
        const val MENU_KIND_QUICK = 0
        const val MENU_KIND_MACRO = 1

        const val ICON_NONE = -1
        const val ICON_DIAMOND = 0
        const val ICON_CLIPBOARD = 1
        const val ICON_GEAR = 2
        const val ICON_BOLT = 3

        private const val NEON_FADE_MS = 1000f
        private const val GLOW_MAX_LETTERS = 48
        private const val GLOW_SIDE_LTR = 0
        private const val GLOW_SIDE_RTL = 1
        private const val GLOW_SIDE_BTT = 2
        private const val GLOW_SIDE_TTB = 3
        // Styl "postupne": pismeno i startuje v i * STEP, plynule se rozsviti za RISE a zustane svitit;
        // po rozsviceni posledniho a prodleve HOLD zhasnou vsechna naraz behem FADE (kratke casy = svizny prubeh).
        private const val BLINK_STEP_MS = 280L
        private const val BLINK_RISE_MS = 520L
        private const val BLINK_HOLD_MS = 400L
        private const val BLINK_FADE_MS = 300L
        const val HINT_MIN = -1f
        const val HINT_MAX = 2f
        // Efekt "glitch": 2-4 zablesky (55 ms svit, 45 ms pauza) dvou obrysu klavesy; nahodne hodnoty se losuji pri klepnuti.
        private const val GLITCH_SLOTS = 8
        private const val GLITCH_MAX_FLASHES = 4
        private const val GLITCH_ON_MS = 55L
        private const val GLITCH_PERIOD_MS = 100L
        private const val GLITCH_MIN_ANGLE = 30f
        private const val GLITCH_ANGLE_SPAN = 147f
        // Posun kopie: nahodne 4-22 % rozmeru klavesy -> kopie vzdy z vetsiny prekryva klavesu.
        private const val GLITCH_MIN_SHIFT = 0.04f
        private const val GLITCH_MAX_SHIFT = 0.22f
        // Efekt "barvy": klavesa drzi barvu 3,0-7,5 s a pak za 1,5 s vybledne; barva znaku po klepnuti drzi 2,5 s a vybledne za 0,4 s.
        private const val PAINT_SLOTS = 48
        private const val PAINT_MIN_MS = 3000
        private const val PAINT_SPAN_MS = 4500
        private const val PAINT_FADE_MS = 1500f
        private const val TAP_TEXT_MS = 2500L
        private const val TAP_TEXT_FADE_MS = 400f
        private const val PASSED_FADE_MS = 500f
        private const val GHOST_MS = 800L
        private const val GHOST_STEP_MS = 110L
        private const val GHOST_ECHOES = 3
        // Efekt Obvod: obrys o 2 dp tlustsi probehne obvodem od nahodneho bodu (RUN), chvili drzi (HOLD) a zhasne (FADE).
        private const val ORBIT_RUN_MS = 700L
        private const val ORBIT_HOLD_MS = 80L
        private const val ORBIT_FADE_MS = 220L
        private const val ORBIT_TOTAL_MS = ORBIT_RUN_MS + ORBIT_HOLD_MS + ORBIT_FADE_MS
        private const val PLASMA_MS = 650L
        // Efekt Vybuch: pismeno se rozprskne (BURST), chvili neni (GAP) a znovu se narodi (BORN); jiskry leti SPARK_MS.
        private const val POP_BURST_MS = 170L
        private const val POP_GAP_MS = 110L
        private const val POP_BORN_MS = 330L
        // Jiskry vybuchu: kazda ma nahodny smer, rychlost, zpozdeni, dobu letu, zakriveni drahy a velikost (z popSeed).
        private const val POP_SPARKS = 18
        private const val POP_CHUNKS = 7
        private const val POP_CHUNK_MS = 460L
        private const val POP_SPARK_MAX_DELAY_MS = 90
        private const val POP_SPARK_MIN_LIFE_MS = 260
        private const val POP_SPARK_LIFE_SPAN_MS = 480
        private const val POP_SPARK_END_MS = POP_SPARK_MAX_DELAY_MS + POP_SPARK_MIN_LIFE_MS + POP_SPARK_LIFE_SPAN_MS
        private const val POP_TOTAL_MS = POP_SPARK_END_MS.toLong()
        // Horici cara: okno poslednich bodu stopy, doba dohoreni po zvednuti prstu a prodleva plamenu (zmena nahody).
        private const val FIRE_POINTS = 70
        private const val FIRE_OUT_MS = 900f
        private const val FIRE_FLICKER_MS = 70L
        private const val FIRE_HOT = 0xFFFFE070.toInt()
        private const val FIRE_MID = 0xFFFF7A00.toInt()
        private const val FIRE_COLD = 0xFFB3200A.toInt()
        // Styl "rez": po dopadu slovo chvili drzi, pak se kazde pismeno rozrizne (30-70 % vysky, nahodny sklon) a poloviny odpadnou.
        private const val CUT_HOLD_MS = 320L
        private const val CUT_FLY_MS = 420L
        private const val CUT_GAP_MS = 140L
        private const val CUT_BORN_MS = 360L
        private const val CUT_TOTAL_MS = CUT_FLY_MS + CUT_GAP_MS + CUT_BORN_MS
        private const val CUT_FALL_MS = 800L
        // Styl "slovo": pismena vzletnou z klaves, slozi slovo, chvili drzi a vybledou.
        private const val FLY_MS = 450L
        // Po slozeni slovo vyskoci na 1.5x a problikne (POP), pomalu se vrati na 100 % (SETTLE), pak zmizi.
        private const val FLY_POP_MS = 130L
        private const val FLY_SETTLE_MS = 650L
        private const val FLY_HOLD_MS = FLY_POP_MS + FLY_SETTLE_MS
        private const val FLY_FADE_MS = 300L
        private const val FLY_POP_SCALE = 1.5f
        private const val FLY_FLASH = 0xFFFFE600.toInt()
        private const val FLY_FLASH_ALT = 0xFFFF2D55.toInt()
        private const val FILL_IN_MS = 450L
        private const val FILL_HOLD_MS = 150L
        private const val FILL_OUT_MS = 300L
        private const val DEMO_POPUP_MS = 700L
        private const val DEMO_FONT_MS = 1800L
        private const val DEMO_TRAIL_POINTS = 36
        private const val DEMO_SWIPE_MS = 1600L
        /** Jak dlouho banner "slovo ulozeno" projizdi pres mezernik. */
        private const val SAVED_BANNER_MS = 5200L
        private const val DEMO_SWIPE_STEPS = 32
        private const val DEMO_ALTS_MS = 900L
        private const val DEMO_PRESS_STEP_MS = 230L
        private const val SPOT_MAX = 64
        private const val SPOT_MS = 1100L
        const val SPOT_FILL = 0
        const val SPOT_OUTLINE = 1
        const val SPOT_LINE = 2
        const val SPOT_SHADOW = 3
        const val SPOT_BG = 4
        private const val BURST_MS = 450L
        private const val LABEL_BASE_DP = 19f
        private const val HINT_BASE_DP = 8.7f
        private const val ENTER_SPIN_MS = 520L
        private const val WHITE = 0xFFFFFFFF.toInt()
    }

    var listener: Listener? = null

    /** Nahled v nastaveni: nereaguje na dotyk a neovlivni swipe decoder. */
    var preview = false

    /**
     * Zkusebni rezim nahledu: klavesy reaguji vizualne jako na pravde (stisk, neon, bublina,
     * nabidka variant), ale nic se nepise - listener je null, swipe a diktovani jsou vypnute.
     * Rust se vola jen jako u nahledu (`preview` zustava zapnuty).
     */
    var previewTouch = false
        set(value) {
            if (field != value) {
                cancelTouch()
                field = value
                // Zkusebni rezim se swipem: Rust si zapamatuje geometrii nahledu pro dekodovani
                // (po skonceni si ji zase prevezme skutecna klavesnice v reloadLayout()).
                reloadLayout()
                invalidate()
            }
        }

    /** Nahled s vyberem klavesy (obrazovka rozlozeni): klepnuti vola tuto funkci. */
    var onKeyPick: ((Key) -> Unit)? = null

    /** Znakova klavesa s timto puvodnim kodem se vykresli zvyraznene (cekajici prohozeni). */
    var markedSrc = Int.MIN_VALUE
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }
    var swipeAllowed = true
    var desiredHeight = 0
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }
    /** Bubliny nad klavesou lze vypnout (pole pro hesla - nikdo nema videt, co se pise). */
    var popupAllowed = true

    /** O kolik px smi bublina / nabidka variant presahnout nad horni okraj (vyska listy navrhu). */
    var overflowTop = 0
    /** Diktovani podrzenim Enteru (vypnuto v polich pro hesla a v nastaveni). */
    var voiceAllowed = true

    /** Prave se diktuje: Enter ukazuje mikrofon. */
    var dictating = false
        set(value) {
            if (field != value) {
                field = value
                if (!value) {
                    stopVoiceDelete()
                    voicePaused = false
                }
                invalidate()
            }
        }
    var enterAction = ENTER_DEFAULT
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }
    var theme: Theme = Theme.from(RustBridge.settings)
        set(value) {
            field = value
            applyAppearance()
            invalidate()
        }

    // Barvy neonu a zablikani slova: pocitaji se jen pri zmene nastaveni (applyAppearance), ne v onDraw.
    private var neon = Theme.parse(RustBridge.settings.neonColor, 0xFF0080FF.toInt())
    private var neonText = textOn(neon)
    private var glowColor = Theme.parse(RustBridge.settings.glowColor, 0xFF0080FF.toInt())
    private var glowText = textOn(glowColor)

    // Duha v barve pismen po tahu: odstin se pocita bez alokace do `hsv`; `glowHit` = poradi pismene posledniho glowAmount.
    private var glowRainbow = false
    private var glowHit = -1
    private val hsv = FloatArray(3)

    /** Odstin duhy pro f 0..1 (cervena .. fialova), bez alokace. */
    private fun rainbow(f: Float): Int {
        hsv[0] = 280f * f.coerceIn(0f, 1f)
        hsv[1] = 0.85f
        hsv[2] = 1f
        return Color.HSVToColor(hsv)
    }

    private fun textOn(c: Int) = if (Theme.luminance(c) > 0.6f) 0xFF15151A.toInt() else WHITE

    // Barvy bubliny nad klavesou a listy znaku pri podrzeni (pocita applyAppearance, ne onDraw).
    private var popupFill = Theme.autoPopup(theme.key)
    private var popupFg = theme.text
    private var altsFill = Theme.autoPopup(theme.key)
    private var altsFg = theme.text
    private var altsSelFill = theme.accent
    private var altsSelFg = theme.onAccent

    var shiftState = SHIFT_OFF
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }
    var mode = MODE_LETTERS
        private set

    private var keys: List<Key> = emptyList()
    private var unitW = 1f
    private var rowH = 1f
    private val density = resources.displayMetrics.density

    // --- vzhled z nastaveni (cte se jen pri zmene, ne v onDraw)
    private var animate = true
    // Waiting+PIN + nove: sirka klavesy, obrys znaku, cut/slide, delete, shift kopie, font, Enter slide + pulzy, banner ulozeni
    private var deleteFillStart = 0L
    private var deleteKey: Key? = null
    private var charOutlineOn = false

    // --- uprava vysky radku (nahled v nastaveni): tmava klavesnice bez pismen a u kazde rady sipka, kterou jde tahnout nahoru a dolu
    /** Je zapnuta uprava radku. */
    var rowEditMode = false
        set(value) {
            if (field != value) {
                field = value
                dragRow = -1
                reloadLayout()
                invalidate()
            }
        }

    /** Posuny radku nastavovane editorem (od spodni rady); null = ulozene z nastaveni. */
    var editOffsets: FloatArray? = null

    /** Po kazde zmene posunu v editoru: skutecne pouzite (omezene) posuny od spodni rady. */
    var onRowEdited: ((FloatArray) -> Unit)? = null
    private val effOffsets = FloatArray(5)
    private val rowTops = FloatArray(5)
    private var rowCount = 0
    private var dragRow = -1
    private var dragStartY = 0f
    private var dragStartOffset = 0f
    private val handlePath = Path()
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var charOutlineCol = 0
    private var keyWidthDp = 0
    private var cutEffect = false
    private var slideEffect = false
    private var customFont: Typeface? = null
    private var customFontName: String? = null
    private var dashedTrail = false
    private var pixelTrail = false
    private var trailWidthDp = 4
    private var enterSlideStart = 0L
    private var enterSlideKey: Key? = null
    private var enterSlideDir = 1f
    private var savedBannerText = ""
    /** Kde v textu banneru je ulozene slovo (zvyrazni se akcentem). */
    private var savedWordFrom = 0
    private var savedWordTo = 0
    private var savedBannerStart = 0L
    private var savedBannerEndedAt = 0L
    private var drawShadow = true
    private var drawHints = true
    private var drawTrailEnabled = true
    private var showPreview = true
    private var keyRadius = 10f * density
    private val shadowOffset = 1.5f * density
    private var fontScale = 1f
    private var gapDp = 5
    private var rowGapDp = 8
    private var showDivider = true
    private var textPercent = 100
    private var glowFillStyle = false
    private var glitchEffect = false
    private var ghostEffect = false
    private var orbitEffect = false
    private var plasmaEffect = false
    private var plasma: PlasmaFx? = null
    private var popEffect = false
    private var fireTrail = false
    private var glowCutStyle = false
    private var flyCut = false
    private var flySeed = 0
    private val orbitPath = Path()
    private val orbitSeg = Path()
    private val orbitMeasure = android.graphics.PathMeasure()
    private val orbitPos = FloatArray(2)
    private val orbitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var glowFlyStyle = false
    // Efekt "barvy": pevna tabulka obarvenych klaves podle `src` (index barvy, cas obarveni, doba drzeni); jen v pameti.
    private var paintEffect = false
    private val paintCols = IntArray(6)
    private val paintSrc = IntArray(PAINT_SLOTS)
    private val paintIdx = IntArray(PAINT_SLOTS) { -1 }
    private val paintAt = LongArray(PAINT_SLOTS)
    private val paintHold = IntArray(PAINT_SLOTS)
    private var paintNext = 0
    private var effectSig = ""
    private var paintUndoSlot = -1
    private var paintUndoSrc = 0
    private var paintUndoIdx = -1
    private var paintUndoAt = 0L
    private var paintUndoHold = 0
    private var paintUndoNext = 0
    // Barva znaku pri prejeti tahem a po klepnuti (0 = automaticky / vypnuto) a zpozdeny prekres po dobe drzeni.
    private var passedTextCol = 0
    private var tapTextCol = 0
    private var hoverTextCol = 0
    private var hoverKey: Key? = null
    private val wake = Runnable { invalidate() }
    // Barvy cislic, druha barva klaves a pozadi (0 = vypnuto / automaticky); shadery se stavi jen pri zmene.
    private var digitTextCol = 0
    private var digitKeyCol = 0
    private var keyCol2 = 0
    private var bgCol2 = 0
    private var keyRainbow = false
    private var bgRainbow = false
    private var textRainbow = false
    // Druha barva efektu po klepnuti (0 = vypnuto) a prepinac stridani; barva se vybira pri klepnuti, ne v onDraw.
    private var neon2 = 0
    private var neon2Rainbow = false
    // Obrys klaves (0 = bez obrysu / bez zmeny); prechod obrysu se stavi jen pri zmene (updateMetrics).
    private var outlineOn = false
    private var outlineCol = 0
    private var outlineCol2 = 0
    private var outlineRainbow = false
    private var outlineTapCol = 0
    private var outlineTapCol2 = 0
    private var tapTextCol2 = 0
    private var specialOutline = true
    private var glowCol2 = 0
    private var trailCol2 = 0
    private var outlineHoverCol = 0
    private var outlinePassedCol = 0
    private var outlineW = 2f * density
    private var outlineShader: LinearGradient? = null
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var keyShader: LinearGradient? = null
    // Druhe barvy cislic (klavesy, znak, obrys): prechod zleva doprava, stavi se jen pri zmene.
    private var digitKeyCol2 = 0
    private var digitTextCol2 = 0
    private var digitKeyShader: LinearGradient? = null
    private var digitTextShader: LinearGradient? = null
    private var tapTextShader: LinearGradient? = null
    private var tapOutShader: LinearGradient? = null
    private var neonShader: LinearGradient? = null
    // Smer efektu Slide (kosinus a sinus uhlu) a styl stopy "otoceni pismen".
    private var glowFlipStyle = false
    private var labelExtra = 0f
    private var digitExtra = 0f
    private var passedKeyCol = 0
    private var shiftScale = 0.75f
    private var shiftNeedsFrame = false
    // Zacatek prechodu druhe barvy: 0 shora, 1 zdola, 2 zleva, 3 zprava (pozadi, klavesy, cisla, znaky, obrysy).
    private var bgDir = 0
    private var keyDir = 0
    private var digitKeyDir = 0
    private var textDir = 0
    private var digitTextDir = 0
    private var outlineDir = 0
    private var bgShader: LinearGradient? = null
    // Prekryti stisku / neonu / zare pres prechod klavesy: barva a pruhlednost slozena z kroku `tint`.
    private var gradKey = false
    private var ovA = 0f
    private var ovR = 0f
    private var ovG = 0f
    private var ovB = 0f
    // Barva a velikost napovedy (pocita applyAppearance).
    private var hintColor = 0
    private var hintScale = 1f
    // Mezery mezi klavesami a velikost znaku (pocita updateMetrics, ne onDraw).
    private var padX = 2.5f * density
    private var padY = 4f * density
    private var labelSize = 1f
    // Prvni radek je rada cislic: pod ni se kresli deliciska.
    private var numberRow = false
    // Poloha male napovedy: x 0 = vlevo .. 1 = vpravo, y 0 = nahore .. 1 = dole.
    private var hintX = 1f
    private var hintY = 0f
    private var hintOverride = false
    // Poloha hlavniho znaku na klavese: 0..1, 0,5 = uprostred.
    private var labelX = 0.5f
    private var labelY = 0.5f
    private var labelOverride = false

    // Rezim nastavovani napovedy: ukazuje napovedy i kdyz jsou vypnute, klavesy bez napovedy ztlumi.
    private var anyHint = false
    private var hintSample: Key? = null
    var hintFocus = false
        set(value) {
            if (field != value) {
                field = value
                updateHintInfo()
                invalidate()
            }
        }

    /** Ziva poloha napovedy bez ukladani (tazeni v nastaveni). */
    fun setHintOverride(x: Float, y: Float) {
        hintOverride = true
        hintX = x.coerceIn(HINT_MIN, HINT_MAX)
        hintY = y.coerceIn(HINT_MIN, HINT_MAX)
        invalidate()
    }

    /**
     * Rozsah polohy napovedy pro soucasne mezery: out = [xMin, xMax, yMin, yMax] v normalizovanych
     * souradnicich (0..1 = uvnitr klavesy). Krajni hodnota klade napovedu na obrys sousedni klavesy.
     * Vraci false, kdyz neni zadna znakova klavesa.
     */
    fun hintRange(out: FloatArray): Boolean {
        val k = hintSample ?: keys.firstOrNull { it.kind == KIND_CHAR } ?: return false
        val hw = max(1f, k.vw / 2f - padX)
        val hh = max(1f, k.h / 2f - padY)
        val pb = LABEL_BASE_DP * density // poloha napovedy nezavisi na velikosti znaku
        val mx = pb * 0.34f
        val spanX = max(1f, 2f * (hw - mx))
        val yT = -hh + pb * 0.52f
        val yB = hh - pb * 0.22f
        val spanY = max(1f, yB - yT)
        out[0] = (-(2f * padX + mx) / spanX).coerceIn(HINT_MIN, 0f)
        out[1] = (1f + (2f * padX + mx) / spanX).coerceIn(1f, HINT_MAX)
        out[2] = (-(2f * padY + pb * 0.52f) / spanY).coerceIn(HINT_MIN, 0f)
        out[3] = (1f + (2f * padY + pb * 0.22f) / spanY).coerceIn(1f, HINT_MAX)
        return true
    }

    /** Ziva poloha hlavniho znaku bez ukladani (tazeni v nastaveni). */
    fun setLabelOverride(x: Float, y: Float) {
        labelOverride = true
        labelX = x.coerceIn(0f, 1f)
        labelY = y.coerceIn(0f, 1f)
        invalidate()
    }

    fun clearLabelOverride() {
        labelOverride = false
        applyAppearance()
        invalidate()
    }

    fun clearHintOverride() {
        hintOverride = false
        applyAppearance()
        invalidate()
    }

    /** Pocita se pri zmene klaves / rezimu, ne v onDraw. */
    private fun updateHintInfo() {
        anyHint = false
        hintSample = null
        for (k in keys) if (k.hint.isNotEmpty() && k.macro == null && k.holdMacro == null) anyHint = true
        if (hintFocus && !anyHint) hintSample = keys.firstOrNull { it.kind == KIND_CHAR }
    }

    private fun loadKeyFont(name: String, weight: Int): Typeface {
        if (name == "system" || name.isBlank()) return weightFace(weight)
        val cached = customFont
        if (cached != null && customFontName == name) return cached
        return try { val tf = Typeface.createFromAsset(context.assets, "fonts/$name"); customFont = tf; customFontName = name; tf } catch (_: Throwable) { customFont = null; customFontName = null; weightFace(weight) }
    }
    /** Banner "slovo X je ulozeno!" - probehne 1x zprava doleva uvnitr mezerniku. */
    fun showSavedWord(word: String) {
        savedBannerText = context.getString(R.string.word_saved_banner, word)
        savedWordFrom = savedBannerText.indexOf(word).coerceAtLeast(0)
        savedWordTo = if (savedBannerText.indexOf(word) >= 0) savedWordFrom + word.length else 0
        savedBannerStart = SystemClock.uptimeMillis()
        invalidate()
    }
    /** Rotace pismenka po uspesnem tahu (styl "otoceni"): cil 90-180 stupnu na jednu stranu, krok 10 stupnu, vydrz 0,25 s, zpet. */
    private fun flipRotation(k: Key, now: Long): Float {
        if (k.flipAt == 0L) return 0f
        val dt = now - k.flipAt
        if (dt < 0L) return 0f
        val target = k.flipTarget
        val up = kotlin.math.abs(target) / 10f * 30L // 30 ms na 10 stupnu
        val hold = 250L
        return when {
            dt < up -> target * (dt / up.toFloat())
            dt < up + hold -> target
            dt < up + hold + up -> target * (1f - (dt - up - hold) / up.toFloat())
            else -> {
                k.flipAt = 0L
                0f
            }
        }
    }

    /** Kod smeru prechodu: 0 shora, 1 zdola, 2 zleva, 3 zprava. */
    private fun gradDirCode(v: String): Int = when (v) {
        "bottom" -> 1
        "left" -> 2
        "right" -> 3
        else -> 0
    }

    /**
     * Prechod vystredeny na nulu (platno se pri kresleni posune na stred klavesy / znaku): prvni barva je tam, odkud
     * prechod zacina (`dir`: 0 shora, 1 zdola, 2 zleva, 3 zprava), `hw` / `hh` jsou pulrozmery.
     */
    private fun gradShader(c1: Int, c2: Int, rainbow: Boolean, dir: Int, hw: Float, hh: Float): LinearGradient {
        val x0 = when (dir) { 2 -> -hw; 3 -> hw; else -> 0f }
        val x1 = when (dir) { 2 -> hw; 3 -> -hw; else -> 0f }
        val y0 = when (dir) { 0 -> -hh; 1 -> hh; else -> 0f }
        val y1 = when (dir) { 0 -> hh; 1 -> -hh; else -> 0f }
        return if (rainbow) LinearGradient(x0, y0, x1, y1, Theme.rainbowFrom(c1), null, Shader.TileMode.CLAMP)
        else LinearGradient(x0, y0, x1, y1, c1, c2, Shader.TileMode.CLAMP)
    }

    /**
     * Tenky obrys znaku v `charBuf` (Waiting): barva obrysu, pruhlednost znaku (mizeni pri animacich) se zachova
     * a gradient znaku obrys neprebarvi. Po kresleni se pismo vrati do puvodniho stavu.
     */
    private fun drawCharOutline(canvas: Canvas, x: Float, y: Float, n: Int, color: Int) {
        val keepAlpha = textPaint.alpha
        val keepColor = textPaint.color
        val keepShader = textPaint.shader
        val keepStyle = textPaint.style
        textPaint.shader = null
        textPaint.style = Paint.Style.STROKE
        textPaint.strokeWidth = 1.5f * density
        textPaint.color = color
        textPaint.alpha = (Color.alpha(color) * keepAlpha / 255).coerceIn(0, 255)
        canvas.drawText(charBuf, 0, n, x, y, textPaint)
        textPaint.style = keepStyle
        textPaint.shader = keepShader
        textPaint.color = keepColor
        textPaint.alpha = keepAlpha
    }

    /** Barva pismene pri otoceni po uspesnem tahu: barva pismen po tahu (a druha barva se s ni strida po pismenech). */
    private fun flipColor(k: Key): Int = when {
        glowRainbow -> rainbow(k.flipMix)
        glowCol2 != 0 -> if (k.flipIdx % 2 == 0) glowColor else glowCol2
        else -> glowColor
    }

    /**
     * Systemove pismo dane tloustky (100-900): pojmenovane rodiny (thin, light, regular, medium, bold, black); mezi nimi
     * se tlustsi pismo dokresluje tahem (`weightExtra`), takze se meni i na pristrojich s malo tloustkami.
     */
    private fun weightFace(weight: Int): Typeface {
        val family = when {
            weight <= 150 -> "sans-serif-thin"
            weight <= 350 -> "sans-serif-light"
            weight <= 450 -> "sans-serif"
            weight <= 550 -> "sans-serif-medium"
            weight <= 750 -> "sans-serif"
            else -> "sans-serif-black"
        }
        return Typeface.create(family, if (weight in 551..750) Typeface.BOLD else Typeface.NORMAL)
    }

    /** Dokresleni tahem (podil velikosti znaku) pro tloustky nad 500. */
    private fun weightExtra(weight: Int): Float = if (weight <= 500) 0f else (weight - 500) / 400f * 0.05f

    private fun applyAppearance() {
        val s = RustBridge.settings
        animate = s.animations
        drawShadow = s.keyShadow
        drawHints = s.keyHints
        drawTrailEnabled = s.swipeTrail
        trailCol = Theme.parse(s.trailColor, 0) and 0x00FFFFFF
        if (s.trailColor.isEmpty() || trailCol == 0) trailCol = theme.accent and 0x00FFFFFF
        textCol2 = Theme.parse(s.textColor2, 0)
        textRainbow = s.textColor2 == Theme.RAINBOW
        glowEnabled = s.swipeGlow
        neonEnabled = s.keyGlow
        showPreview = s.keyPopup
        keyRadius = s.keyRadiusDp * density
        gapDp = s.keyGapDp
        rowGapDp = s.rowGapDp
        showDivider = s.numberDivider
        textPercent = s.keyTextPercent
        glowFillStyle = s.glowStyle == "paint"
        glowFlyStyle = s.glowStyle == "ghost"
        glitchEffect = s.tapEffect == "glitch"
        ghostEffect = s.tapEffect == "ghost"
        cutEffect = s.tapEffect == "cut"
        slideEffect = s.tapEffect == "slide"
        orbitEffect = s.tapEffect == "orbit"
        // Plazma je shader (Android 13+); na starsim systemu nebo kdyz se shader nepodari prelozit se chova jako Neon.
        plasmaEffect = s.tapEffect == "plasma" && Build.VERSION.SDK_INT >= 33
        if (plasmaEffect && plasma == null) plasma = makePlasma()
        if (plasma == null) plasmaEffect = false
        popEffect = s.tapEffect == "pop"
        glowFlipStyle = s.glowStyle == "flip"
        bgDir = gradDirCode(s.bgGradDir)
        keyDir = gradDirCode(s.keyGradDir)
        digitKeyDir = gradDirCode(s.digitKeyGradDir)
        textDir = gradDirCode(s.textGradDir)
        digitTextDir = gradDirCode(s.digitTextGradDir)
        outlineDir = gradDirCode(s.outlineGradDir)
        charOutlineOn = s.charOutline
        charOutlineCol = Theme.parse(s.charOutlineColor, 0)
        keyWidthDp = s.keyWidthDp
        dashedTrail = s.trailStyle == "dashed"
        pixelTrail = s.trailStyle == "pixel"
        trailWidthDp = s.trailWidthDp
        fireTrail = s.trailStyle == "fire"
        paintEffect = s.tapEffect == "paint" && s.keyGlow
        val sig = s.tapEffect + s.keyGlow
        if (sig != effectSig) {
            effectSig = sig
            java.util.Arrays.fill(paintIdx, -1)
            paintNext = 0
            paintUndoSlot = -1
        }
        passedTextCol = Theme.parse(s.passedTextColor, 0)
        hoverTextCol = Theme.parse(s.hoverTextColor, 0)
        val cf = loadKeyFont(s.keyFont, s.labelWeight)
        labelFace = cf
        digitFace = loadKeyFont(s.keyFont, s.digitWeight)
        labelExtra = weightExtra(s.labelWeight)
        digitExtra = weightExtra(s.digitWeight)
        // Barva projetych klaves je barva stopy (bez vlastni barvy stopy odvozena z akcentu).
        passedKeyCol = Theme.parse(s.trailColor, 0)
        hintFace = cf // napoveda ma stejne pismo jako znaky
        tapTextCol = Theme.parse(s.tapTextColor, 0)
        paintCols[0] = Theme.parse(s.paintColor1, 0xFF0080FF.toInt())
        paintCols[1] = Theme.parse(s.paintColor2, 0xFFFF5100.toInt())
        paintCols[2] = Theme.parse(s.paintColor3, 0xFF6A02E8.toInt())
        paintCols[3] = Theme.parse(s.paintColor4, 0xFF22C55E.toInt())
        paintCols[4] = Theme.parse(s.paintColor5, 0xFFFFD60A.toInt())
        paintCols[5] = Theme.parse(s.paintColor6, 0xFFEC4899.toInt())
        digitTextCol = Theme.parse(s.digitTextColor, 0)
        digitKeyCol = Theme.parse(s.digitKeyColor, 0)
        digitKeyCol2 = Theme.parse(s.digitKeyColor2, 0)
        digitTextCol2 = Theme.parse(s.digitTextColor2, 0)
        keyCol2 = Theme.parse(s.keyColor2, 0)
        bgCol2 = Theme.parse(s.backgroundColor2, 0)
        keyRainbow = s.keyColor2 == Theme.RAINBOW
        bgRainbow = s.backgroundColor2 == Theme.RAINBOW
        hintScale = s.hintPercent / 100f
        val hc = Theme.parse(s.hintColor, 0)
        hintColor = if (s.hintColor.isEmpty() || hc == 0) theme.hint else hc
        fontScale = resources.configuration.fontScale
        if (!hintOverride) {
            hintX = s.keyHintX
            hintY = s.keyHintY
        }
        if (!labelOverride) {
            labelX = s.keyLabelX
            labelY = s.keyLabelY
        }
        neon = Theme.parse(s.neonColor, 0xFF0080FF.toInt())
        neonText = textOn(neon)
        neon2 = Theme.parse(s.neonColor2, 0)
        neon2Rainbow = s.neonColor2 == Theme.RAINBOW
        outlineCol = Theme.parse(s.outlineColor, 0)
        outlineCol2 = Theme.parse(s.outlineColor2, 0)
        outlineRainbow = s.outlineColor2 == Theme.RAINBOW
        outlineTapCol = Theme.parse(s.outlineTapColor, 0)
        outlineTapCol2 = Theme.parse(s.outlineTapColor2, 0)
        tapTextCol2 = Theme.parse(s.tapTextColor2, 0)
        specialOutline = s.specialOutline
        glowCol2 = Theme.parse(s.glowColor2, 0)
        trailCol2 = Theme.parse(s.trailColor2, 0) and 0x00FFFFFF
        outlineHoverCol = Theme.parse(s.outlineHoverColor, 0)
        outlinePassedCol = Theme.parse(s.outlinePassedColor, 0)
        outlineW = s.outlineWidthDp * density
        outlineOn = outlineCol != 0 || outlineTapCol != 0 || outlineHoverCol != 0 || outlinePassedCol != 0 || specialOutline
        glowRainbow = s.glowColor == Theme.RAINBOW
        glowColor = if (glowRainbow) Theme.RAINBOW_COLORS[0] else Theme.parse(s.glowColor, 0xFF0080FF.toInt())
        glowText = textOn(glowColor)
        // Prazdna barva = automaticka (podle motivu), jinak vlastni barva s automatickym kontrastem textu.
        val pc = Theme.parse(s.popupColor, 0)
        if (s.popupColor.isEmpty() || pc == 0) {
            popupFill = Theme.autoPopup(theme.key)
            popupFg = textOn(popupFill)
        } else {
            popupFill = pc
            popupFg = textOn(pc)
        }
        val ac = Theme.parse(s.altsColor, 0)
        if (s.altsColor.isEmpty() || ac == 0) {
            altsFill = Theme.autoPopup(theme.key)
            altsFg = textOn(altsFill)
        } else {
            altsFill = ac
            altsFg = textOn(ac)
        }
        // Zvyrazneni vybrane polozky: accent; kdyz je moc blizko barve listy, kontrastni (barva textu listy).
        if (colorDistance(altsFill, theme.accent) < 90f) {
            altsSelFill = altsFg
            altsSelFg = altsFill
        } else {
            altsSelFill = theme.accent
            altsSelFg = theme.onAccent
        }
        updateMetrics()
    }

    /** Vzdalenost dvou barev v RGB (0..441). */
    private fun colorDistance(a: Int, b: Int): Float {
        val dr = (Color.red(a) - Color.red(b)).toFloat()
        val dg = (Color.green(a) - Color.green(b)).toFloat()
        val db = (Color.blue(a) - Color.blue(b)).toFloat()
        return sqrt(dr * dr + dg * dg + db * db)
    }

    /**
     * Mezery mezi klavesami a velikost znaku. Vodorovna mezera = key_gap_dp, svisla = row_gap_dp.
     * Velikost znaku (key_text_percent) a napovedy (hint_percent) nezavisi na rozmerech klaves: meni se jen tim jednim nastavenim.
     */
    private fun updateMetrics() {
        padX = gapDp * 0.5f * density
        padY = rowGapDp * 0.5f * density
        if (rowH > 1f) padY = min(padY, rowH * 0.3f)
        val effUnitW = if (keyWidthDp > 0) min(keyWidthDp * density, unitW) else unitW
        labelSize = LABEL_BASE_DP * density * textPercent / 100f
        // Prechod klavesy je vystredeny na stred klavesy (onDraw posune platno), pozadi pokryva cely pohled.
        // Prechody jsou vystredene na nulu; kresleni posune platno na stred klavesy (zadna matice, zadny novy shader pri kresleni).
        val kw = max(1f, effUnitW - 2f * padX) / 2f
        val kh = max(1f, rowH - 2f * padY) / 2f
        val tw = max(1f, labelSize * 0.45f)
        val th = max(1f, labelSize * 0.4f)
        keyShader = when {
            keyRainbow -> gradShader(theme.key, 0, true, keyDir, kw, kh)
            keyCol2 != 0 -> gradShader(theme.key, keyCol2, false, keyDir, kw, kh)
            else -> null
        }
        outlineShader = when {
            outlineCol == 0 -> null
            outlineRainbow -> gradShader(outlineCol, 0, true, outlineDir, kw, kh)
            outlineCol2 != 0 -> gradShader(outlineCol, outlineCol2, false, outlineDir, kw, kh)
            else -> null
        }
        textShader = when {
            labelSize <= 0f -> null
            textRainbow -> gradShader(theme.text, 0, true, textDir, tw, th)
            textCol2 != 0 -> gradShader(theme.text, textCol2, false, textDir, tw, th)
            else -> null
        }
        digitKeyShader = if (digitKeyCol2 != 0) gradShader(if (digitKeyCol != 0) digitKeyCol else theme.key, digitKeyCol2, false, digitKeyDir, kw, kh) else null
        digitTextShader = if (digitTextCol2 != 0 && labelSize > 0f) gradShader(if (digitTextCol != 0) digitTextCol else theme.text, digitTextCol2, false, digitTextDir, tw, th) else null
        // Druhe barvy po klepnuti a neonu: prechod uvnitr jedne klavesy (shora dolu).
        tapTextShader = if (tapTextCol2 != 0 && labelSize > 0f) gradShader(if (tapTextCol != 0) tapTextCol else theme.text, tapTextCol2, false, 0, tw, th) else null
        tapOutShader = if (outlineTapCol2 != 0) gradShader(if (outlineTapCol != 0) outlineTapCol else theme.accent, outlineTapCol2, false, 0, kw, kh) else null
        // Prechod uvnitr klavesy jen u obvodu; ostatni efekty druhou barvu strida / losuje (nextNeon).
        neonShader = if (orbitEffect && (neon2 != 0 || neon2Rainbow)) gradShader(neon, neon2, neon2Rainbow, 0, kw, kh) else null
        val bw = width.toFloat()
        val bh = height.toFloat()
        val bx0 = if (bgDir == 3) bw else 0f
        val bx1 = if (bgDir == 2) bw else if (bgDir == 3) 0f else 0f
        val by0 = if (bgDir == 1) bh else 0f
        val by1 = if (bgDir == 0) bh else if (bgDir == 1) 0f else 0f
        bgShader = when {
            width <= 0 || height <= 0 -> null
            bgRainbow -> LinearGradient(bx0, by0, bx1, by1, Theme.rainbowFrom(theme.background), null, Shader.TileMode.CLAMP)
            bgCol2 != 0 -> LinearGradient(bx0, by0, bx1, by1, theme.background, bgCol2, Shader.TileMode.CLAMP)
            else -> null
        }
    }

    /** Smisi barvu `x` v mire `t` do aktualni barvy klavesy; u prechodu navic sklada prekryti (ovA, ovR..B). */
    /** Smichani dvou barev vcetne pruhlednosti; 0 = pruhledna (prechod z / do "zadneho obrysu"). Bez alokace. */
    private fun mixArgb(a: Int, b: Int, t: Float): Int {
        if (t <= 0f) return a
        if (t >= 1f) return b
        val a0 = if (a == 0) b and 0x00FFFFFF else a
        val u = 1f - t
        return Color.argb(
            (Color.alpha(a0) * u + Color.alpha(b) * t).toInt(),
            (Color.red(a0) * u + Color.red(b) * t).toInt(),
            (Color.green(a0) * u + Color.green(b) * t).toInt(),
            (Color.blue(a0) * u + Color.blue(b) * t).toInt(),
        )
    }

    /** Barva dalsiho klepnuti: duha = nahodny odstin, dve barvy = stridani, jinak barva efektu. */
    private fun nextNeon(): Int {
        val has2 = neon2 != 0 || neon2Rainbow
        // Obvod: druha barva tvori prechod uvnitr klavesy (neonShader), barva efektu je jedna.
        if (!has2 || orbitEffect) return neon
        // Glitch losuje barvu pri kazdem zablesku, ostatni efekty se po klepnuti stridaji.
        val second = if (glitchEffect) random.nextBoolean() else { neonToggle = !neonToggle; neonToggle }
        if (!second) return neon
        return if (neon2Rainbow) rainbow(random.nextFloat()) else neon2
    }

    private var neonToggle = false

    /** Druha barva k `first` (barva efektu <-> druha barva); u duhy nahodny odstin. */
    @android.annotation.TargetApi(33)
    private fun makePlasma(): PlasmaFx? = try {
        PlasmaFx()
    } catch (_: Throwable) {
        null
    }

    private fun otherNeon(first: Int): Int {
        if (neon2 == 0 && !neon2Rainbow) return first
        return if (first == neon) (if (neon2Rainbow) rainbow(random.nextFloat()) else neon2) else neon
    }

    private fun tint(cur: Int, x: Int, t: Float): Int {
        if (gradKey && t > 0f) {
            val keep = 1f - t
            val na = 1f - (1f - ovA) * keep
            if (na > 0f) {
                ovR = (ovR * ovA * keep + Color.red(x) * t) / na
                ovG = (ovG * ovA * keep + Color.green(x) * t) / na
                ovB = (ovB * ovA * keep + Color.blue(x) * t) / na
            }
            ovA = na
        }
        return Theme.blend(cur, x, t)
    }

    // --- nabidka po dlouhem stisku ?123
    private val quickLabels = listOf(
        context.getString(R.string.clipboard),
        context.getString(R.string.menu_settings),
    )
    private val quickIcons = intArrayOf(ICON_CLIPBOARD, ICON_GEAR)
    private var menuItems: List<String> = emptyList()
    private var menuIcons: IntArray = IntArray(0)
    private var menuKind = MENU_KIND_QUICK
    private var glowEnabled = true
    private var neonEnabled = true

    // --- postupne rozsviceni pismen slova po swipe (indexy klaves)
    // --- styl "slovo": pismena leti z klaves nad klavesnici, kde se slozi do slova (vse predpocitano pri glowWord)
    private val flyChars = CharArray(GLOW_MAX_LETTERS)
    private val flyKey = IntArray(GLOW_MAX_LETTERS)
    private val flyFromX = FloatArray(GLOW_MAX_LETTERS)
    private val flyFromY = FloatArray(GLOW_MAX_LETTERS)
    private val flyToX = FloatArray(GLOW_MAX_LETTERS)
    private val flyWidths = FloatArray(GLOW_MAX_LETTERS)
    private var flyCount = 0
    private var flyStart = 0L
    private var flyStartSize = 0f
    private var flyEndSize = 0f
    private var flyPopScale = FLY_POP_SCALE
    private var flyFlash = FLY_FLASH
    private val flyFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val flyOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val glowSeq = IntArray(GLOW_MAX_LETTERS)
    private var glowCount = 0
    // Styl "vybarveni": vsechny klavesy slova naraz, strana vybarveni (GLOW_SIDE_*) se losuje pro kazdou
    // klavesu; -1 = klavesa neni ve slove. Pole se alokuje pri nacteni rozlozeni, ne za behu.
    private var glowFill = false
    private var glowSide = IntArray(0)
    private val random = java.util.Random()
    private val demoToken = Any()
    private var glowStart = 0L

    // --- efekt "glitch": pevny zasobnik klepnuti (klavesa, cas, pocet zablesku, nahodne hodnoty), bez alokace za behu
    private val glitchKeys = arrayOfNulls<Key>(GLITCH_SLOTS)
    private val glitchAt = LongArray(GLITCH_SLOTS)
    private val glitchCount = IntArray(GLITCH_SLOTS)
    private val glitchInv = IntArray(GLITCH_SLOTS)
    private val glitchAngle = FloatArray(GLITCH_SLOTS * GLITCH_MAX_FLASHES)
    private val glitchDx = FloatArray(GLITCH_SLOTS * GLITCH_MAX_FLASHES)
    private val glitchDy = FloatArray(GLITCH_SLOTS * GLITCH_MAX_FLASHES)
    private val glitchCol = IntArray(GLITCH_SLOTS * GLITCH_MAX_FLASHES)

    private fun startGlitch(k: Key, at: Long) {
        var slot = -1
        var oldest = 0
        for (i in 0 until GLITCH_SLOTS) {
            if (glitchKeys[i] == null) {
                slot = i
                break
            }
            if (glitchAt[i] < glitchAt[oldest]) oldest = i
        }
        if (slot < 0) slot = oldest
        glitchKeys[slot] = k
        glitchAt[slot] = at
        glitchCount[slot] = 2 + random.nextInt(3)
        // Invertovana barva vyplne klavesy (RGB opacne).
        val fillCol = if (k.isDigit && digitKeyCol != 0) digitKeyCol else theme.key
        glitchInv[slot] = 0xFF000000.toInt() or (fillCol.inv() and 0x00FFFFFF)
        for (j in 0 until GLITCH_MAX_FLASHES) {
            val i = slot * GLITCH_MAX_FLASHES + j
            glitchAngle[i] = (GLITCH_MIN_ANGLE + random.nextFloat() * GLITCH_ANGLE_SPAN) * (if (random.nextBoolean()) 1f else -1f)
            // Nahodna velikost posunu (procento rozmeru klavesy) a nahodny smer; losuje se jednou pri klepnuti.
            val m = GLITCH_MIN_SHIFT + random.nextFloat() * (GLITCH_MAX_SHIFT - GLITCH_MIN_SHIFT)
            val dir = random.nextFloat() * 2f * PI.toFloat()
            glitchDx[i] = cos(dir) * m
            glitchDy[i] = sin(dir) * m
            glitchCol[i] = nextNeon()
        }
    }

    private fun clearGlitch(k: Key? = null) {
        for (i in 0 until GLITCH_SLOTS) if (k == null || glitchKeys[i] === k) glitchKeys[i] = null
    }
    private var menuKey: Key? = null
    private var menuSel = -1
    /** Nabidka zustala otevrena po zvednuti prstu - polozka se vybere dalsim klepnutim. */
    private var menuSticky = false
    private var menuLeft = 0f
    private var menuTop = 0f
    private var menuW = 0f
    private var menuCell = 0f

    // --- bublina nad klavesou a stopa swipe
    private var previewKey: Key? = null
    private var previewStartedAt = 0L
    private var previewEndedAt = 0L
    private var trailCount = 0
    private var trailEndedAt = 0L
    private var trailHeld = false // ukazka stopy v nastaveni: stopa nebledne, dokud se nenakresli cela
    private var trailCol = theme.accent and 0x00FFFFFF
    private var textCol2 = 0
    private var textShader: Shader? = null

    private fun startPreview(k: Key) {
        if (!showPreview || !popupAllowed || k.kind != KIND_CHAR) return
        previewKey = k
        previewStartedAt = SystemClock.uptimeMillis()
        previewEndedAt = 0L
    }

    private fun endPreview() {
        if (previewKey != null && previewEndedAt == 0L) {
            previewEndedAt = SystemClock.uptimeMillis()
            invalidate()
        }
    }

    // --- kresleni (vse predalokovano)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val baseFace: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private var labelFace: Typeface = baseFace
    private var digitFace: Typeface = baseFace
    private var hintFace: Typeface = baseFace
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = baseFace
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val rect = RectF()
    /** Obrazek na klavese maker; dekoduje se jednou (null = nepodarilo se, kresli se blesk). */
    private val macroBitmap: Bitmap? = try {
        BitmapFactory.decodeResource(resources, R.drawable.maskot)
    } catch (_: Throwable) {
        null
    }
    /** Obrazky na klavese s makrem na klik (trol, chocho, dollo, maskot); dekoduji se jednou (null = nepodarilo se, kresli se znak). */
    private val faceBitmaps: Array<Bitmap?> = arrayOf(R.drawable.trol, R.drawable.chocho, R.drawable.dollo, R.drawable.maskot).map { id ->
        try {
            BitmapFactory.decodeResource(resources, id)
        } catch (_: Throwable) {
            null
        }
    }.toTypedArray()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val clip = RectF()
    private val path = Path()
    private val charBuf = CharArray(8)

    // --- dotyk
    private val handler = Handler(Looper.getMainLooper())
    private val samples = FloatArray(3 * MAX_SAMPLES)
    private var sampleCount = 0
    private var touchMode = T_NONE
    private var activeId = -1
    private var downKey: Key? = null
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var moved = false
    private var longPressFired = false
    private var cursorMoved = false
    private var consumedDx = 0f
    private var repeatCount = 0
    private var secondId = -1
    private var secondKey: Key? = null
    private var pinching = false
    private var span0 = 0f
    private var lastSpan = 0f

    // --- nabidka variant (dlouhy stisk)
    private var popupKey: Key? = null
    private var popupAlts: List<String> = emptyList()
    private var popupSel = 0
    private var popupLeft = 0f
    private var popupTop = 0f
    private var popupCell = 0f

    private val longPress = Runnable { onLongPress() }
    private val repeat = object : Runnable {
        override fun run() {
            val k = downKey ?: return
            if (touchMode != T_BACKSPACE) return
            repeatCount++
            listener?.onKeyTap(k)
            // Zrychlujici se mazani: 110 ms -> 25 ms.
            handler.postDelayed(this, max(25L, 110L - repeatCount * 8L))
        }
    }

    init {
        isHapticFeedbackEnabled = false // haptiku ridi sluzba podle nastaveni
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    // ------------------------------------------------------------------ rozlozeni

    fun setMode(newMode: Int) {
        if (mode != newMode) {
            mode = newMode
            reloadLayout()
            if (animate && !preview) {
                // Jemne prolnuti pri prepnuti pismena / symboly.
                alpha = 0.5f
                animate().alpha(1f).setDuration(130).start()
            }
        }
    }

    /** Nacte geometrii klaves z Rustu pro aktualni velikost a rezim. */
    fun reloadLayout() {
        val t0 = Perf.now()
        Perf.count("reloadLayout")
        reloadLayoutInner()
        Perf.end("reloadLayout", t0)
    }

    private fun reloadLayoutInner() {
        applyAppearance()
        if (width <= 0 || height <= 0) return
        cancelTouch()
        previewKey = null
        trailCount = 0
        glowCount = 0
        flyCount = 0
        clearGlitch()
        popupKey = null
        altsDemo = false
        handler.removeCallbacksAndMessages(demoToken)
        closeMenu()
        val parsed = ArrayList<Key>(48)
        numberRow = false
        try {
            // Zkusebni rezim nahledu registruje geometrii pro swipe (preview = false -> Rust si ji zapamatuje).
            val root = JSONObject(RustBridge.layout(width, height, mode, preview && !previewTouch, if (rowEditMode) editOffsets else null))
            numberRow = root.optBoolean("number_row", false)
            root.optJSONArray("row_offsets")?.let { a -> for (i in 0 until min(5, a.length())) effOffsets[i] = a.optDouble(i, 0.0).toFloat() }
            val arr = root.getJSONArray("keys")
            val rows = max(1, root.optInt("rows", 5))
            unitW = width / 10f
            rowH = height / rows.toFloat()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                parsed.add(
                    Key(
                        o.getDouble("x").toFloat(), o.getDouble("y").toFloat(),
                        o.getDouble("w").toFloat(), o.getDouble("h").toFloat(),
                        o.getDouble("vx").toFloat(), o.getDouble("vw").toFloat(),
                        o.getInt("code"), o.getInt("kind"),
                        o.getString("label"), o.getString("alts"), o.getString("desc"),
                        o.optInt("src", o.getInt("code")),
                    )
                )
            }
        } catch (_: Throwable) {
            // Jadro neni k dispozici: klavesnice zustane prazdna, ale nespadne.
        }
        val adjusted = if (keyWidthDp > 0 && parsed.isNotEmpty()) {
            val unit = width / 10f; val fixed = min(keyWidthDp * density, unit)
            parsed.map { k -> val units = if (unit > 0f) k.vw / unit else 1f; val newVw = fixed * units.coerceIn(1f, 5f); val centre = k.vx + k.vw / 2f; val newVx = (centre - newVw / 2f).coerceIn(k.x, maxOf(k.x, k.x + k.w - newVw)); Key(k.x, k.y, k.w, k.h, newVx, newVw, k.code, k.kind, k.label, k.alts, k.desc, k.src) }
        } else parsed
        keys = adjusted
        // Horni okraje radku (od nejvyssiho) pro ovladaci sipky upravy radku.
        rowCount = 0
        for (k in adjusted) {
            var known = false
            for (i in 0 until rowCount) if (kotlin.math.abs(rowTops[i] - k.y) < 1f) known = true
            if (!known && rowCount < 5) rowTops[rowCount++] = k.y
        }
        java.util.Arrays.sort(rowTops, 0, rowCount)
        resolveMacros()
        if (glowSide.size != parsed.size) glowSide = IntArray(parsed.size) { -1 }
        updateMetrics()
        updateHintInfo()
        invalidate()
    }

    /**
     * Prida klavesam makra z `key_macros` (klik, s obrazkem makra z `macro_faces`) a `key_macros_hold` (podrzeni);
     * makro, ktere uz neexistuje, klavesu nechava obycejnou.
     * Vola se jen pri nacteni rozlozeni (to bezi pri kazde zmene nastaveni i pri zobrazeni klavesnice), ne pri
     * kresleni ani klepnuti.
     */
    private fun resolveMacros() {
        val s = RustBridge.settings
        val map = s.keyMacros
        val hold = s.keyMacrosHold
        if (map.size() == 0 && hold.size() == 0) {
            for (k in keys) {
                k.macro = null
                k.holdMacro = null
                k.macroFace = 0
            }
        } else {
            val list = RustBridge.macros()
            val faces = s.macroFaces
            for (k in keys) {
                val id = if (k.kind == KIND_CHAR) map.get(k.src, 0) else 0
                val holdId = if (k.kind == KIND_CHAR) hold.get(k.src, 0) else 0
                k.macro = if (id > 0) list.firstOrNull { it.id == id.toLong() } else null
                k.holdMacro = if (holdId > 0) list.firstOrNull { it.id == holdId.toLong() } else null
                // Obrazek patri makru (zvoleny pri jeho tvorbe), na klik i na podrzeni.
                val shown = k.macro ?: k.holdMacro
                k.macroFace = if (shown == null) 0 else faces.get(shown.id.toInt(), 0).coerceIn(0, faceBitmaps.size - 1)
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = if (desiredHeight > 0) desiredHeight else MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        reloadLayout()
    }

    private fun keyAt(x: Float, y: Float): Key? {
        var best: Key? = null
        var bestD = Float.MAX_VALUE
        for (k in keys) {
            val dx = max(max(k.x - x, 0f), x - (k.x + k.w))
            val dy = max(max(k.y - y, 0f), y - (k.y + k.h))
            val d = dx * dx + dy * dy
            if (d == 0f) return k
            if (d < bestD) {
                bestD = d
                best = k
            }
        }
        return best
    }

    // ------------------------------------------------------------------ kresleni

    private fun pressAmount(k: Key, now: Long): Float {
        if (!animate) return if (k.pressed) 1f else 0f
        // Stisk je rychly (80 ms), uvolneni doznniva pomaleji a mekceji.
        return if (k.pressed) {
            min(1f, (now - k.changedAt) / PRESS_ANIM_MS)
        } else {
            val t = 1f - min(1f, (now - k.changedAt) / RELEASE_ANIM_MS)
            t * t
        }
    }

    override fun onDraw(canvas: Canvas) {
        val t0 = Perf.now()
        drawKeys(canvas)
        if (rowEditMode) drawRowEdit(canvas)
        Perf.end("onDraw", t0)
    }

    /** Sirka ovladaci zony se sipkami u leveho okraje. */
    private fun handleZone() = 40f * density

    /**
     * Uprava radku: klavesnice se ztmavi, pismena zmizi (zustanou jen siluety klaves) a u leveho okraje kazde rady je
     * plna sipka doprava; tahem za ni jde cela rada nahoru / dolu.
     */
    private fun drawRowEdit(canvas: Canvas) {
        dimPaint.style = Paint.Style.FILL
        dimPaint.color = 0xE6101012.toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        dimPaint.color = 0xFF3A3A40.toInt()
        for (k in keys) {
            rect.set(k.vx + padX, k.y + padY, k.vx + k.vw - padX, k.y + k.h - padY)
            canvas.drawRoundRect(rect, keyRadius, keyRadius, dimPaint)
        }
        handlePaint.style = Paint.Style.FILL
        for (i in 0 until rowCount) {
            val j = rowCount - 1 - i
            val cy = rowTops[i] + rowH / 2f
            val h = min(rowH * 0.62f, 30f * density)
            val w = h * 1.1f
            val x0 = 6f * density
            val y0 = cy - h / 2f
            // Plna sipka doprava: telo (obdelnik) a hlava (trojuhelnik).
            handlePath.reset()
            handlePath.moveTo(x0, y0 + h * 0.30f)
            handlePath.lineTo(x0 + w * 0.60f, y0 + h * 0.30f)
            handlePath.lineTo(x0 + w * 0.60f, y0)
            handlePath.lineTo(x0 + w, y0 + h * 0.50f)
            handlePath.lineTo(x0 + w * 0.60f, y0 + h)
            handlePath.lineTo(x0 + w * 0.60f, y0 + h * 0.70f)
            handlePath.lineTo(x0, y0 + h * 0.70f)
            handlePath.close()
            handlePaint.color = if (dragRow == j) theme.accent or 0xFF000000.toInt() else 0xFFF4F4F5.toInt()
            canvas.drawPath(handlePath, handlePaint)
        }
    }

    /** Dotyk v rezimu upravy radku: tah za sipku posouva celou radu; posun omezuje Rust (kazda rada ma sve meze). */
    private fun rowEditTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (e.x > handleZone() + 20f * density) return true
                var best = -1
                var bestD = Float.MAX_VALUE
                for (i in 0 until rowCount) {
                    val d = kotlin.math.abs(e.y - (rowTops[i] + rowH / 2f))
                    if (d < bestD && d <= rowH * 0.6f) {
                        bestD = d
                        best = rowCount - 1 - i
                    }
                }
                if (best >= 0) {
                    dragRow = best
                    dragStartY = e.y
                    dragStartOffset = effOffsets[best]
                    parent?.requestDisallowInterceptTouchEvent(true)
                    invalidate()
                }
            }
            MotionEvent.ACTION_MOVE -> if (dragRow >= 0 && rowH > 0f) {
                val o = editOffsets ?: effOffsets.copyOf()
                val want = dragStartOffset + (e.y - dragStartY) / rowH
                if (kotlin.math.abs(want - o[dragRow]) > 0.002f) {
                    o[dragRow] = want
                    editOffsets = o
                    reloadLayout()
                    onRowEdited?.invoke(effOffsets.copyOf())
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragRow = -1
                invalidate()
            }
        }
        return true
    }

    private fun drawKeys(canvas: Canvas) {
        // Pozadi jen v ramci teto plochy: rodic neorezava (kvuli bublinam), takze drawColor
        // by prekreslil i listu navrhu nad klavesami.
        val bs = bgShader
        if (bs != null) {
            fill.color = WHITE
            fill.shader = bs
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
            fill.shader = null
        } else {
            fill.color = theme.background
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        }
        val now = SystemClock.uptimeMillis()
        var animating = false
        var wakeIn = Long.MAX_VALUE
        var paintLive = false
        if (paintEffect) for (i in 0 until PAINT_SLOTS) if (paintIdx[i] >= 0) paintLive = true
        val radius = keyRadius
        val upper = shiftState != SHIFT_OFF && mode == MODE_LETTERS

        if (numberRow && showDivider) {
            // Deliciska uprostred mezery mezi radou cislic a prvnim radkem pismen.
            fill.color = theme.divider
            canvas.drawRect(0f, rowH - 0.5f * density, width.toFloat(), rowH + 0.5f * density, fill)
        }

        // Ukazka v nastaveni: klavesy mimo vybranou skupinu jsou ztlumene, vybrane blikaji v barve akcentu.
        val spotOn = now < spotUntil
        if (!spotOn && spotCount > 0) spotCount = 0
        if (spotOn) animating = true
        if (spotOn && (spotMode == SPOT_FILL || spotMode == SPOT_OUTLINE)) {
            // Pri zmene rozmeru klaves se blikajici obdelniky prepocitaji z aktualnich klaves (zmizela-li klavesa, zustane stara poloha).
            val pk = spotPick
            if (pk != null) {
                val sn = fillSpotRects(pk)
                if (sn > 0) spotCount = sn
            }
        }
        if (spotOn && spotMode == SPOT_LINE) {
            spotRects[1] = rowH - 1.5f * density
            spotRects[3] = rowH + 1.5f * density
        }
        val spotDims = spotOn && spotCount > 0 && spotMode != SPOT_SHADOW && spotMode != SPOT_BG
        // Stin: pri ukazce se na chvili zvetsi az na 200 % a zabarvi se akcentem.
        var shadowMul = 1f
        var shadowSpot = 0f
        if (spotOn && spotMode == SPOT_SHADOW) {
            shadowSpot = spotAlpha(now)
            shadowMul = 1f + shadowSpot
        }
        if (spotOn && spotMode == SPOT_BG) {
            val a = spotAlpha(now)
            fill.color = (theme.accent and 0x00FFFFFF) or ((150f * a).toInt().coerceIn(0, 255) shl 24)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        }

        var glowActive = false
        if (glowCount > 0) {
            val total = if (glowFill) FILL_IN_MS + FILL_HOLD_MS + FILL_OUT_MS
            else (glowCount - 1) * BLINK_STEP_MS + BLINK_RISE_MS + BLINK_HOLD_MS + BLINK_FADE_MS
            if (now - glowStart >= total) glowCount = 0 else glowActive = true
        }
        if (glowActive) animating = true
        // Styl "vybarveni": postup a pruhlednost jsou pro vsechny klavesy stejne (zacinaji naraz).
        var fillProgress = 0f
        var fillAlpha = 0f
        if (glowActive && glowFill) {
            val t = now - glowStart
            val f = min(1f, t / FILL_IN_MS.toFloat())
            fillProgress = 1f - (1f - f) * (1f - f) // zpomalovani
            fillAlpha = if (t < FILL_IN_MS + FILL_HOLD_MS) 1f else 1f - (t - FILL_IN_MS - FILL_HOLD_MS) / FILL_OUT_MS.toFloat()
        }

        // Styl "slovo": pismena slova mizi z klaves, po dolozeni slova se vraci.
        var flyLabel = 1f
        if (flyCount > 0) {
            val ft = now - flyStart
            val hold = if (flyCut) CUT_HOLD_MS else FLY_HOLD_MS
            val fadeMs = if (flyCut) CUT_FALL_MS else FLY_FADE_MS
            if (ft >= FLY_MS + hold + fadeMs) {
                flyCount = 0
            } else {
                animating = true
                flyLabel = if (ft < FLY_MS + hold) 0f else (ft - FLY_MS - hold) / fadeMs.toFloat()
            }
        }

        for (ki in keys.indices) {
            val k = keys[ki]
            val p = if (k.changedAt == 0L) 0f else pressAmount(k, now)
            if (animate && k.changedAt != 0L && now - k.changedAt < RELEASE_ANIM_MS) animating = true

            val hintText = if (k === hintSample) "1" else k.hint
            val dimKey = (hintFocus && !(k.kind == KIND_CHAR && hintText.isNotEmpty())) ||
                (spotDims && !inSpot(k.vx + k.vw / 2f, k.y + k.h / 2f))
            val marked = k.kind == KIND_CHAR && k.src == markedSrc
            val accentKey = marked || k.kind == KIND_ENTER || (k.kind == KIND_SHIFT && shiftState == SHIFT_CAPS)
            var paintA = 0f
            var pIdx = -1
            if (paintLive && (k.kind == KIND_CHAR || k.kind == KIND_SPACE)) {
                var slot = -1
                for (i in 0 until PAINT_SLOTS) if (paintIdx[i] >= 0 && paintSrc[i] == k.src) {
                    slot = i
                    break
                }
                if (slot >= 0) {
                    val t = now - paintAt[slot]
                    val hold = paintHold[slot]
                    if (t < hold) {
                        paintA = 1f
                        pIdx = paintIdx[slot]
                        wakeIn = min(wakeIn, hold - t)
                    } else {
                        val f = (t - hold) / PAINT_FADE_MS
                        if (f >= 1f) {
                            paintIdx[slot] = -1
                        } else {
                            paintA = 1f - f * f * (3f - 2f * f)
                            pIdx = paintIdx[slot]
                            animating = true
                        }
                    }
                }
            }
            val digitOwn = k.isDigit && (digitKeyCol != 0 || digitKeyCol2 != 0)
            val base = when {
                accentKey -> theme.accent
                digitOwn -> if (digitKeyCol != 0) digitKeyCol else theme.key
                k.kind == KIND_CHAR || k.kind == KIND_SPACE -> theme.key
                else -> theme.keySpecial
            }
            val shader = if (digitOwn) digitKeyShader else keyShader
            gradKey = shader != null && !accentKey && (k.kind == KIND_CHAR || k.kind == KIND_SPACE)
            // Rez: klavesa se rozpuli, pulky odletí a po prodleve se klavesa narodi znovu (cutS = meritko klavesy).
            var cutT = -1L
            var cutS = 1f
            if (k.cutAt != 0L) {
                cutT = now - k.cutAt
                if (cutT >= CUT_TOTAL_MS) {
                    k.cutAt = 0L
                    cutT = -1L
                } else {
                    animating = true
                    if (cutT < 0L) cutT = -1L else cutS = cutScale(cutT)
                }
            }
            ovA = 0f
            ovR = 0f
            ovG = 0f
            ovB = 0f
            val scale = (1f - 0.04f * p) * cutS
            val cx = k.vx + k.vw / 2f
            val cy = k.y + k.h / 2f
            val hw = (k.vw / 2f - padX) * scale
            val hh = (k.h / 2f - padY) * scale

            if ((drawShadow || shadowSpot > 0f) && p < 1f) {
                // Plochy "stin" = tmavsi obrys posunuty o 1.5 dp dolu (zadny blur -> zadna cena navic).
                fill.color = if (shadowSpot > 0f) Theme.blend(theme.keyShadow, theme.accent, shadowSpot * 0.8f) else theme.keyShadow
                rect.set(cx - hw, cy - hh + shadowOffset * shadowMul * (1f - p), cx + hw, cy + hh + shadowOffset * shadowMul * (1f - p))
                canvas.drawRoundRect(rect, radius, radius, fill)
            }
            var keyColor = base
            if (paintA > 0f && !accentKey) keyColor = tint(keyColor, paintCols[pIdx], paintA)
            if (p > 0f) keyColor = tint(keyColor, if (accentKey) theme.text else theme.keyPressed, if (accentKey) 0.25f * p else p)
            if (k.passed) {
                keyColor = if (passedKeyCol != 0) tint(keyColor, passedKeyCol, 1f) else tint(keyColor, theme.accent, 0.55f)
            } else if (passedKeyCol != 0 && k.passedFadeAt != 0L) {
                val pkf = 1f - (now - k.passedFadeAt) / PASSED_FADE_MS
                if (pkf > 0f) keyColor = tint(keyColor, passedKeyCol, pkf)
            }
            val glow = if (glowActive && !glowFill) glowAmount(ki, now) else 0f
            val fillSide = if (glowActive && glowFill) glowSide[ki] else -1
            // Duha: v postupnem stylu podle poradi pismene ve slove, ve vybarveni podle vodorovne polohy klavesy.
            val gCol = when {
                glowRainbow && glow > 0f -> rainbow(if (glowCount > 1) glowHit / (glowCount - 1f) else 0f)
                glowRainbow && fillSide >= 0 -> rainbow(cx / max(1f, width.toFloat()))
                glowRainbow -> glowColor
                // Druha barva: pismena slova se strida (licha = barva 2); ve vybarveni podle sloupce klavesy.
                glowCol2 != 0 && glow > 0f -> if (glowHit % 2 == 0) glowColor else glowCol2
                glowCol2 != 0 && fillSide >= 0 -> if ((cx / max(1f, width / 10f)).toInt() % 2 == 0) glowColor else glowCol2
                else -> glowColor
            }
            val gText = if (glowRainbow || glowCol2 != 0) textOn(gCol) else glowText
            if (glow > 0f) keyColor = tint(keyColor, gCol, glow)
            val neonA = if (k.neonAt != 0L) neonAmount(k, now) else 0f
            val nc = if (k.neonCol != 0) k.neonCol else neon
            var neonGradA = 0f
            if (neonA > 0f) {
                if (k.neonAt > 0L) animating = true
                if (neonShader == null) keyColor = tint(keyColor, nc, neonA) else neonGradA = neonA
                // Svetelny okraj: o 3 dp vetsi obrys pod klavesou, jedna plocha bez blur.
                val g = 3f * density
                fill.color = (nc and 0x00FFFFFF) or ((90f * neonA).toInt().coerceIn(0, 255) shl 24)
                rect.set(cx - hw - g, cy - hh - g, cx + hw + g, cy + hh + g)
                canvas.drawRoundRect(rect, radius + g, radius + g, fill)
            }
            if (k.ghostAt != 0L) {
                // Duch: za klavesou se rozbehnou 3 dozvuky (obrysy), kazdy o kousek pozdeji, rostou a miznou.
                val gt = now - k.ghostAt
                if (gt >= GHOST_MS + (GHOST_ECHOES - 1) * GHOST_STEP_MS) {
                    k.ghostAt = 0L
                } else {
                    animating = true
                    ghostPaint.strokeWidth = 2f * density
                    for (e in 0 until GHOST_ECHOES) {
                        val q = (gt - e * GHOST_STEP_MS) / GHOST_MS.toFloat()
                        if (q <= 0f || q >= 1f) continue
                        val grow = (2.5f + 3.5f * (e + 1)) * density * q
                        val a = (170f * (1f - q) * (1f - q)).toInt().coerceIn(0, 255)
                        if (neonShader != null) {
                            canvas.save()
                            canvas.translate(cx, cy)
                            ghostPaint.shader = neonShader
                            ghostPaint.color = (WHITE and 0x00FFFFFF) or (a shl 24)
                            rect.set(-hw - grow, -hh - grow, hw + grow, hh + grow)
                            canvas.drawRoundRect(rect, radius + grow, radius + grow, ghostPaint)
                            ghostPaint.shader = null
                            canvas.restore()
                        } else {
                            val ec = if (e % 2 == 1 && k.ghostCol2 != 0) k.ghostCol2 else nc
                            ghostPaint.color = (ec and 0x00FFFFFF) or (a shl 24)
                            rect.set(cx - hw - grow, cy - hh - grow, cx + hw + grow, cy + hh + grow)
                            canvas.drawRoundRect(rect, radius + grow, radius + grow, ghostPaint)
                        }
                    }
                }
            }
            if (dimKey) keyColor = tint(keyColor, theme.background, 0.7f)
            if (gradKey) {
                // Prechod z barvy klavesy do druhe barvy: platno se posune na stred klavesy, kde je shader vystredeny.
                canvas.save()
                canvas.translate(cx, cy)
                rect.set(-hw, -hh, hw, hh)
                fill.color = WHITE
                fill.alpha = 255
                fill.shader = shader
                canvas.drawRoundRect(rect, radius, radius, fill)
                fill.shader = null
                if (ovA > 0f) {
                    fill.color = Color.argb((255f * ovA).toInt().coerceIn(0, 255), ovR.toInt(), ovG.toInt(), ovB.toInt())
                    canvas.drawRoundRect(rect, radius, radius, fill)
                }
                canvas.restore()
                rect.set(cx - hw, cy - hh, cx + hw, cy + hh)
            } else {
                fill.color = keyColor
                rect.set(cx - hw, cy - hh, cx + hw, cy + hh)
                canvas.drawRoundRect(rect, radius, radius, fill)
            }
            if (neonGradA > 0f && cutS > 0f) {
                // Neon s druhou barvou: prechod uvnitr klavesy (shora dolu).
                canvas.save()
                canvas.translate(cx, cy)
                rect.set(-hw, -hh, hw, hh)
                fill.color = (WHITE and 0x00FFFFFF) or ((255f * neonGradA).toInt().coerceIn(0, 255) shl 24)
                fill.shader = neonShader
                canvas.drawRoundRect(rect, radius, radius, fill)
                fill.shader = null
                canvas.restore()
                rect.set(cx - hw, cy - hh, cx + hw, cy + hh)
            }
            if (fillSide >= 0 && fillAlpha > 0f) {
                // Vybarveni: klavesa se zleva / zprava / zdola / shora zaplni barvou, orezano na cast klavesy.
                val w = hw * 2f
                val h = hh * 2f
                when (fillSide) {
                    GLOW_SIDE_LTR -> clip.set(rect.left, rect.top, rect.left + w * fillProgress, rect.bottom)
                    GLOW_SIDE_RTL -> clip.set(rect.right - w * fillProgress, rect.top, rect.right, rect.bottom)
                    GLOW_SIDE_BTT -> clip.set(rect.left, rect.bottom - h * fillProgress, rect.right, rect.bottom)
                    GLOW_SIDE_TTB -> clip.set(rect.left, rect.top, rect.right, rect.top + h * fillProgress)
                }
                fill.color = (gCol and 0x00FFFFFF) or ((255f * fillAlpha).toInt().coerceIn(0, 255) shl 24)
                canvas.save()
                canvas.clipRect(clip)
                canvas.drawRoundRect(rect, radius, radius, fill)
                canvas.restore()
            }

            // Doznivani barvy po projeti tahem a po klepnuti: spolecne pro znak i obrys (bez alokace).
            var passedFade = 0f
            if (!k.passed && k.passedFadeAt != 0L) {
                val f = (now - k.passedFadeAt) / PASSED_FADE_MS
                if (f >= 1f) {
                    k.passedFadeAt = 0L
                } else {
                    passedFade = 1f - f
                    animating = true
                }
            }
            var tapF = 0f
            if (k.tapAt != 0L) {
                val t = now - k.tapAt
                if (t < TAP_TEXT_MS) {
                    tapF = 1f
                    wakeIn = min(wakeIn, TAP_TEXT_MS - t)
                } else {
                    val f = (t - TAP_TEXT_MS) / TAP_TEXT_FADE_MS
                    if (f >= 1f) {
                        k.tapAt = 0L
                    } else {
                        tapF = 1f - f
                        animating = true
                    }
                }
            }
            // Barva obrysu znaku (Waiting): stejna jako aktualni obrys klavesy, bez nej barva akcentu.
            var charOc = if (charOutlineCol != 0) charOutlineCol else if (outlineCol != 0) outlineCol else theme.accent
            if (outlineOn) {
                // Obrys: zakladni barva (pripadne prechod), po projeti, po klepnuti, pod prstem (posledni vyhrava).
                val special = specialOutline && (k.kind == KIND_SHIFT || k.kind == KIND_MODE || k.kind == KIND_BACKSPACE || k.kind == KIND_EMOJI || k.kind == KIND_MACRO)
                val baseOc = outlineCol
                var oc = if (special) theme.accent else baseOc
                if (k.passed && outlinePassedCol != 0) {
                    oc = outlinePassedCol
                } else if (passedFade > 0f && outlinePassedCol != 0) {
                    oc = mixArgb(oc, outlinePassedCol, passedFade)
                }
                if (tapF > 0f && outlineTapCol != 0 && tapOutShader == null) oc = mixArgb(oc, if (k.tapOut != 0) k.tapOut else outlineTapCol, tapF)
                val over = (k.pressed && !k.passed) || (k.passed && k === hoverKey)
                if (over && outlineHoverCol != 0) oc = outlineHoverCol
                // Obrys specialnich klaves je pevne v barve akcentu (zadny efekt ho neprebarvuje).
                if (special) oc = theme.accent
                if (oc != 0 && charOutlineCol == 0) charOc = oc
                if (oc != 0 && cutS > 0f) {
                    val ins = outlineW / 2f
                    val r = max(0f, radius - ins)
                    outlinePaint.strokeWidth = outlineW
                    val os = outlineShader
                    if (os != null && oc == baseOc && !special && !dimKey) {
                        // Prechod obrysu: shader je vystredeny na nulu, platno se posune na stred klavesy.
                        canvas.save()
                        canvas.translate(cx, cy)
                        rect.set(-hw + ins, -hh + ins, hw - ins, hh - ins)
                        outlinePaint.color = WHITE
                        outlinePaint.shader = os
                        canvas.drawRoundRect(rect, r, r, outlinePaint)
                        outlinePaint.shader = null
                        canvas.restore()
                    } else {
                        outlinePaint.color = if (dimKey) mixArgb(oc, theme.background, 0.7f) else oc
                        rect.set(cx - hw + ins, cy - hh + ins, cx + hw - ins, cy + hh - ins)
                        canvas.drawRoundRect(rect, r, r, outlinePaint)
                    }
                }
            }

            if (tapF > 0f && tapOutShader != null && cutS > 0f) {
                // Druha barva obrysu po klepnuti: prechod uvnitr jedne klavesy (shora dolu).
                val tIns = outlineW / 2f
                val tr = max(0f, radius - tIns)
                canvas.save()
                canvas.translate(cx, cy)
                rect.set(-hw + tIns, -hh + tIns, hw - tIns, hh - tIns)
                outlinePaint.strokeWidth = outlineW
                outlinePaint.shader = tapOutShader
                outlinePaint.color = (WHITE and 0x00FFFFFF) or ((255f * tapF).toInt().coerceIn(0, 255) shl 24)
                canvas.drawRoundRect(rect, tr, tr, outlinePaint)
                outlinePaint.shader = null
                canvas.restore()
            }

            if (k.orbitAt != 0L) {
                // Obvod: tlustsi obrys probehne po cele klavese od nahodneho bodu (0 az 100 %), pak zhasne.
                val ot = now - k.orbitAt
                if (ot >= ORBIT_TOTAL_MS) {
                    k.orbitAt = 0L
                } else {
                    animating = true
                    val run = min(1f, ot / ORBIT_RUN_MS.toFloat())
                    val prog = 1f - (1f - run) * (1f - run)
                    val fade = if (ot <= ORBIT_RUN_MS + ORBIT_HOLD_MS) 1f else 1f - (ot - ORBIT_RUN_MS - ORBIT_HOLD_MS) / ORBIT_FADE_MS.toFloat()
                    val sw = max(outlineW, density) + 2f * density
                    val ins = sw / 2f
                    val rr = max(0f, radius - ins)
                    orbitPath.reset()
                    orbitPath.addRoundRect(cx - hw + ins, cy - hh + ins, cx + hw - ins, cy + hh - ins, rr, rr, Path.Direction.CW)
                    orbitMeasure.setPath(orbitPath, false)
                    val len = orbitMeasure.length
                    val start = k.orbitStart * len
                    val end = start + len * prog
                    orbitSeg.reset()
                    if (end <= len) {
                        orbitMeasure.getSegment(start, end, orbitSeg, true)
                    } else {
                        orbitMeasure.getSegment(start, len, orbitSeg, true)
                        orbitMeasure.getSegment(0f, end - len, orbitSeg, true)
                    }
                    val oa = (255f * fade).toInt().coerceIn(0, 255) shl 24
                    orbitPaint.strokeWidth = sw
                    if (neonShader != null) {
                        orbitSeg.offset(-cx, -cy)
                        canvas.save()
                        canvas.translate(cx, cy)
                        orbitPaint.shader = neonShader
                        orbitPaint.color = (WHITE and 0x00FFFFFF) or oa
                        canvas.drawPath(orbitSeg, orbitPaint)
                        orbitPaint.shader = null
                        canvas.restore()
                        orbitSeg.offset(cx, cy)
                    } else {
                        orbitPaint.color = (nc and 0x00FFFFFF) or oa
                        canvas.drawPath(orbitSeg, orbitPaint)
                    }
                    // Kolecko v bode startu, odkud obrys vybehl.
                    orbitMeasure.getPosTan(start, orbitPos, null)
                    fill.color = (nc and 0x00FFFFFF) or oa
                    canvas.drawCircle(orbitPos[0], orbitPos[1], sw * 0.9f, fill)
                }
            }

            if (k.plasmaAt != 0L) {
                // Plazma: shader jen pres tuhle klavesu, pak zase nic.
                val pt = now - k.plasmaAt
                val fx = plasma
                if (pt >= PLASMA_MS || fx == null) {
                    k.plasmaAt = 0L
                } else if (pt >= 0L) {
                    animating = true
                    val c2 = if (neon2 != 0 && !neon2Rainbow) (if (nc == neon) neon2 else neon) else Theme.blend(nc, WHITE, 0.55f)
                    if (Build.VERSION.SDK_INT >= 33) fx.draw(canvas, cx - hw, cy - hh, hw * 2f, hh * 2f, radius, pt / PLASMA_MS.toFloat(), k.plasmaX, k.plasmaY, nc, c2)
                }
            }
            if (k.slideAt != 0L) { val st = now - k.slideAt; if (st >= 420L) k.slideAt = 0L else animating = true }
            if (deleteFillStart != 0L && deleteKey === k) {
                val dt = now - deleteFillStart
                if (dt >= 700L) { deleteFillStart = 0L; deleteKey = null } else { animating = true; val fillQ = (dt / 300f).coerceIn(0f, 1f); val h = hh * 2f; fill.color = (0xFFFF3B30.toInt() and 0x00FFFFFF) or ((255f * 0.85f).toInt() shl 24); canvas.save(); canvas.clipRect(cx - hw, cy + hh - h * fillQ, cx + hw, cy + hh); canvas.drawRoundRect(cx - hw, cy - hh, cx + hw, cy + hh,radius, radius, fill); canvas.restore() }
            }
            val baseFg = when {
                accentKey -> theme.onAccent
                k.isDigit && digitTextCol != 0 -> digitTextCol
                else -> theme.text
            }
            var fg = baseFg
            if (paintA > 0f && !accentKey) fg = Theme.blend(fg, textOn(paintCols[pIdx]), paintA)
            if (k.passed) {
                fg = if (passedTextCol != 0) passedTextCol else Theme.blend(fg, theme.onAccent, 0.55f)
                // Klavesa pod prstem ma vlastni barvu (je-li zadana), projete klavesy drzi barvu "po projeti".
                if (hoverTextCol != 0 && k === hoverKey) fg = hoverTextCol
            } else if (passedFade > 0f && passedTextCol != 0) {
                // Po skonceni tahu barva znaku po projeti vybledne zpet.
                fg = Theme.blend(fg, passedTextCol, passedFade)
            }
            if (glow > 0f) fg = Theme.blend(fg, gText, glow)
            if (fillSide >= 0) fg = Theme.blend(fg, gText,(fillProgress * fillAlpha).coerceIn(0f, 1f))
            if (neonA > 0f) fg = Theme.blend(fg, if (k.neonCol != 0) textOn(k.neonCol) else neonText, neonA)
            if (k.flipAt != 0L && now >= k.flipAt) fg = flipColor(k)
            if (tapF > 0f && tapTextCol != 0) {
                val ttc = if (k.tapTxt != 0) k.tapTxt else tapTextCol
                fg = if (tapF >= 1f) ttc else Theme.blend(fg, ttc, tapF)
            }
            if (dimKey) fg = Theme.blend(fg, theme.background, 0.7f)
            // Vybuch: pismeno se zvetsi a zmizi, rozprsknou se jiskry, pak se pismeno znovu narodi (s malym presahem).
            var popTime = -1L
            var popS = 1f
            var popA = 1f
            if (k.popAt != 0L) {
                popTime = now - k.popAt
                if (popTime >= POP_TOTAL_MS) {
                    k.popAt = 0L
                    popTime = -1L
                } else {
                    animating = true
                    if (popTime >= POP_BURST_MS + POP_GAP_MS + POP_BORN_MS) {
                        popS = 1f
                        popA = 1f
                    } else if (popTime < POP_BURST_MS) {
                        val q = popTime / POP_BURST_MS.toFloat()
                        popS = 1f + 0.45f * q
                        popA = 1f - q
                    } else if (popTime < POP_BURST_MS + POP_GAP_MS) {
                        popS = 0f
                        popA = 0f
                    } else {
                        val q = (popTime - POP_BURST_MS - POP_GAP_MS) / POP_BORN_MS.toFloat()
                        val x = q - 1f
                        popS = 1f + 2.70158f * x * x * x + 1.70158f * x * x
                        popA = min(1f, q * 2f)
                    }
                }
            }
            if (cutT >= 0L && cutS < 1f) {
                // Klavesa je rozrezana nebo se jeste rodi: znak se meni spolu s klavesou.
                popTime = 0L
                popS = cutS
                popA = cutS.coerceIn(0f, 1f)
            }
            if (cutT in 0L until CUT_FLY_MS) {
                drawCutHalves(canvas, k, cx, cy, k.vw / 2f - padX, k.h / 2f - padY, radius, cutT / CUT_FLY_MS.toFloat(), keyColor, fg, upper)
            }
            val tapGradA = if (tapF > 0f && tapTextShader != null) tapF else 0f
            val face = if (k.macro != null) faceBitmaps[k.macroFace] else null
            when (k.kind) {
                KIND_CHAR -> if (face != null && (drawHints || hintFocus)) {
                    drawMacroImage(canvas, face, cx, cy, hw, hh, dimKey, 0.85f)
                } else {
                    textPaint.color = fg
                    textPaint.typeface = if (k.isDigit) digitFace else labelFace
                    val wExtra = (if (k.isDigit) digitExtra else labelExtra) * labelSize
                    if (wExtra > 0f) {
                        textPaint.style = Paint.Style.FILL_AND_STROKE
                        textPaint.strokeWidth = wExtra
                    }
                    if (flyCount > 0 && flyLabel < 1f && isFlying(ki)) {
                        textPaint.color = (fg and 0x00FFFFFF) or ((255f * flyLabel).toInt().coerceIn(0, 255) shl 24)
                    }
                    textPaint.textSize = labelSize
                    // Prechod barvy znaku jen kdyz zadny docasny efekt nemeni barvu znaku.
                    val dts = k.isDigit && digitTextShader != null
                    val gShader: Shader? = if (dts) digitTextShader else textShader
                    val grad = gShader != null && fg == baseFg && !accentKey && (dts || !(k.isDigit && digitTextCol != 0)) &&
                        !(flyCount > 0 && flyLabel < 1f && isFlying(ki))
                    val n = labelChars(k.label, upper)
                    val shift = 0f
                    // Poloha znaku: 0,5 / 0,5 = uprostred; jinak se stred pohybuje tam, kde se znak jeste vejde do klavesy.
                    val offX = if (labelX == 0.5f) 0f else (labelX - 0.5f) * 2f * max(0f, hw - labelSize * 0.3f - 2f * density)
                    val offY = if (labelY == 0.5f) 0f else (labelY - 0.5f) * 2f * max(0f, hh - labelSize * 0.36f - 2f * density)
                    var slideDx = 0f
                    var slideDy = 0f
                    if (k.slideAt != 0L) {
                        val st = now - k.slideAt
                        val q = (st / 420f).coerceIn(0f, 1f)
                        // Znak odjede ve zvolenem smeru (po 30 stupnich) a vrati se.
                        val dist = kotlin.math.abs(k.slideC) * hw * 2f + kotlin.math.abs(k.slideS) * hh * 2f + 20f * density
                        val f = if (q < 0.5f) q * 2f else 1f - (q - 0.5f) * 2f
                        slideDx = k.slideC * f * dist
                        slideDy = k.slideS * f * dist
                        textPaint.alpha = ((255f * (1f - q * 0.5f)).toInt()).coerceIn(0, 255)
                    }
                    var deleteDx = 0f
                    if (deleteFillStart != 0L && deleteKey === k) { val dt = now - deleteFillStart; if (dt > 300L) { val q = ((dt - 300L) / 400f).coerceIn(0f, 1f); val dir = if (cx < width / 2f) 1f else -1f; deleteDx = dir * q * (hw * 2f + 20f * density); textPaint.alpha = ((255f * (1f - q)).toInt()).coerceIn(0, 255) } }
                    val totalDx = slideDx + deleteDx
                    val passRot = flipRotation(k, now)
                    if (k.flipAt != 0L) animating = true
                    if (popTime >= 0L) {
                        canvas.save()
                        canvas.scale(popS, popS, cx + offX, cy + offY + shift + slideDy)
                        textPaint.alpha = (textPaint.alpha * popA).toInt().coerceIn(0, 255)
                    }
                    if (grad) {
                        // Shader je vystredeny na nulu: platno se posune na stred znaku.
                        canvas.save()
                        canvas.translate(cx + offX + totalDx, cy + offY + shift + slideDy)
                        textPaint.shader = gShader
                        if (passRot != 0f) canvas.rotate(passRot, 0f, -(textPaint.ascent() + textPaint.descent()) / 2f)
                        if (charOutlineOn) drawCharOutline(canvas, 0f, -(textPaint.ascent() + textPaint.descent()) / 2f, n, charOc)
                        canvas.drawText(charBuf, 0, n, 0f, -(textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
                        if (passRot != 0f) canvas.rotate(-passRot, 0f, -(textPaint.ascent() + textPaint.descent()) / 2f)
                        textPaint.shader = null
                        canvas.restore()
                    } else {
                        if (passRot != 0f) canvas.save().also { canvas.rotate(passRot, cx + offX + totalDx, cy + offY + shift + slideDy - (textPaint.ascent() + textPaint.descent()) / 2f) }
                        if (charOutlineOn) drawCharOutline(canvas, cx + offX + totalDx, cy + offY + shift + slideDy - (textPaint.ascent() + textPaint.descent()) / 2f, n, charOc)
                        canvas.drawText(charBuf, 0, n, cx + offX + totalDx, cy + offY + shift + slideDy - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
                        if (passRot != 0f) canvas.restore()
                    }
                    if (tapGradA > 0f) {
                        // Barva znaku po klepnuti s druhou barvou: prechod uvnitr znaku (shora dolu), pres zakladni vykresleni.
                        canvas.save()
                        canvas.translate(cx + offX + totalDx, cy + offY + shift + slideDy)
                        if (passRot != 0f) canvas.rotate(passRot, 0f, -(textPaint.ascent() + textPaint.descent()) / 2f)
                        textPaint.shader = tapTextShader
                        textPaint.color = WHITE
                        textPaint.alpha = (255f * tapGradA * popA).toInt().coerceIn(0, 255)
                        canvas.drawText(charBuf, 0, n, 0f, -(textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
                        textPaint.shader = null
                        canvas.restore()
                    }
                    if (popTime >= 0L) canvas.restore()
                    textPaint.typeface = baseFace
                    textPaint.style = Paint.Style.FILL
                }
                KIND_SPACE -> {
                    textPaint.color = theme.textDim
                    textPaint.textSize = min(rowH * 0.24f, 13f * fontScale * density)
                    if (popTime >= 0L) {
                        canvas.save()
                        canvas.scale(popS, popS, cx, cy)
                        textPaint.alpha = (textPaint.alpha * popA).toInt().coerceIn(0, 255)
                    }
                    // Behem probihajiciho textu (ulozene slovo) je zakladni popisek mezerniku schovany, potom se kratce objevi.
                    val bannerOn = savedBannerStart != 0L
                    var reveal = 1f
                    if (savedBannerEndedAt != 0L) {
                        reveal = ((now - savedBannerEndedAt) / 350f).coerceIn(0f, 1f)
                        if (reveal >= 1f) savedBannerEndedAt = 0L else animating = true
                    }
                    if (!bannerOn) {
                        canvas.save()
                        canvas.scale(0.8f + 0.2f * reveal, 0.8f + 0.2f * reveal, cx, cy)
                        textPaint.alpha = (textPaint.alpha * reveal).toInt().coerceIn(0, 255)
                        canvas.drawText(k.label, cx, cy - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
                        canvas.restore()
                    }
                    if (popTime >= 0L) canvas.restore()
                }
                KIND_MODE -> {
                    textPaint.color = fg
                    textPaint.textSize = min(rowH * 0.27f, unitW * 0.36f)
                    canvas.drawText(k.label, cx, cy - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
                }
                KIND_MACRO -> if (macroBitmap != null) {
                    drawMacroImage(canvas, macroBitmap, cx, cy, hw, hh, dimKey)
                } else {
                    drawIcon(canvas, k.kind, cx, cy, min(k.vw, k.h) * 0.2f, fg)
                }
                else -> if (k.kind == KIND_BACKSPACE && deleteFillStart != 0L && deleteKey === k) {
                    drawBackspaceBurst(canvas, cx, cy, min(k.vw, k.h) * 0.2f, fg, now - deleteFillStart)
                } else if (k.kind == KIND_ENTER && enterSlideKey === k && enterSlideStart != 0L && now - enterSlideStart < ENTER_SPIN_MS) {
                    val sq = (now - enterSlideStart) / ENTER_SPIN_MS.toFloat()
                    val ease = sq * sq * (3f - 2f * sq)
                    canvas.save()
                    canvas.rotate(360f * ease, cx, cy)
                    drawIcon(canvas, k.kind, cx, cy, min(k.vw, k.h) * 0.2f, fg)
                    canvas.restore()
                } else {
                    drawIcon(canvas, k.kind, cx, cy, min(k.vw, k.h) * 0.2f, fg)
                }
            }
            if (popTime >= 0L && popTime < POP_CHUNK_MS) {
                // Vybuch cele klavesy: vetsi kusy vyplne letí od stredu do stran, otaceji se a miznou.
                val seedC = (k.popSeed * 1000f).toInt()
                val cq = popTime / POP_CHUNK_MS.toFloat()
                val cease = 1f - (1f - cq) * (1f - cq)
                val baseCol = if (k.isDigit && digitKeyCol != 0) digitKeyCol else theme.key
                for (i in 0 until POP_CHUNKS) {
                    val ang = (i + sparkRand(seedC, i, 6)) * (2f * PI.toFloat() / POP_CHUNKS)
                    val reach = hh * (1.4f + 1.6f * sparkRand(seedC, i, 7))
                    val px = cx + cos(ang) * reach * cease
                    val py = cy + sin(ang) * reach * cease + hh * 0.8f * cq * cq
                    val cw = hw * (0.22f + 0.2f * sparkRand(seedC, i, 8))
                    val ch = hh * (0.3f + 0.25f * sparkRand(seedC, i, 9))
                    val calpha = (255f * (1f - cq)).toInt().coerceIn(0, 255)
                    fill.color = (baseCol and 0x00FFFFFF) or (calpha shl 24)
                    canvas.save()
                    canvas.rotate(720f * (sparkRand(seedC, i, 10) - 0.5f) * cq, px, py)
                    rect.set(px - cw, py - ch, px + cw, py + ch)
                    canvas.drawRoundRect(rect, radius * 0.4f, radius * 0.4f, fill)
                    canvas.restore()
                }
            }
            if (popTime >= 0L && popTime < POP_SPARK_END_MS) {
                // Kazda jiskra ma vlastni (nahodny) smer, rychlost, zpozdeni, dobu letu, zakriveni a velikost; nic se neuklada.
                val seed = (k.popSeed * 1000f).toInt()
                val white = Theme.blend(nc, WHITE, 0.6f) and 0x00FFFFFF
                for (i in 0 until POP_SPARKS) {
                    val delay = (sparkRand(seed, i, 0) * POP_SPARK_MAX_DELAY_MS).toLong()
                    val life = POP_SPARK_MIN_LIFE_MS + sparkRand(seed, i, 1) * POP_SPARK_LIFE_SPAN_MS
                    val q = (popTime - delay) / life
                    if (q <= 0f || q >= 1f) continue
                    val ang = sparkRand(seed, i, 2) * 2f * PI.toFloat()
                    val reach = hh * (0.9f + 2.4f * sparkRand(seed, i, 3))
                    val curve = (sparkRand(seed, i, 4) - 0.5f) * 1.6f
                    val ease = 1f - (1f - q) * (1f - q)
                    val d = reach * ease
                    // Zakriveni: smer se behem letu stoci a dolu ji tahne lehka tiaz.
                    val a2 = ang + curve * q
                    val px = cx + cos(a2) * d
                    val py = cy + sin(a2) * d + hh * 0.9f * q * q
                    val alpha = ((255f * (1f - q * q)).toInt()).coerceIn(0, 255)
                    fill.color = (if (i % 3 == 0) white else (nc and 0x00FFFFFF)) or (alpha shl 24)
                    canvas.drawCircle(px, py, (1f + 3.2f * sparkRand(seed, i, 5)) * density * (1f - 0.7f * q), fill)
                }
            }
        }

        // Napovedy ve druhem pruchodu: napoveda za okrajem klavesy nesmi zakryt sousedni klavesa kreslena pozdeji.
        if (drawHints || hintFocus) {
            for (ki in keys.indices) {
                val k = keys[ki]
                if (k.kind != KIND_CHAR || k.macro != null || k.holdMacro != null) continue
                val hintText = if (k === hintSample) "1" else k.hint
                if (hintText.isEmpty()) continue
                if (drawCutHint(canvas, k, hintText, now, radius)) continue
                drawHint(canvas, k, hintText, if (k.changedAt == 0L) 0f else pressAmount(k, now), radius)
            }
        }
        // Makro na podrzeni: obrazek makra na pozici napovedy. Skryje se s napovedami.
        if (drawHints || hintFocus) for (k in keys) {
            if (k.kind != KIND_CHAR || k.macro != null || k.holdMacro == null) continue
            drawHint(canvas, k, "", if (k.changedAt == 0L) 0f else pressAmount(k, now), radius, faceBitmaps[k.macroFace])
        }
        if (spotOn) drawSpot(canvas, now, radius)
        if (drawGlitch(canvas, now)) animating = true
        if (drawTrail(canvas, now)) animating = true
        if (drawPreview(canvas, now)) animating = true
        drawPopup(canvas)
        drawMenu(canvas)
        if (shiftNeedsFrame) {
            animating = true
            shiftNeedsFrame = false
        }
        if (drawEnterSlide(canvas, now)) animating = true
        if (drawSavedBanner(canvas, now)) animating = true
        drawFly(canvas, now)
        if (drawVoice(canvas)) animating = true
        if (animating) {
            postInvalidateOnAnimation()
        } else if (wakeIn != Long.MAX_VALUE) {
            // Barva drzi bez animace: staci jeden zpozdeny prekres na zacatek vybledani.
            handler.removeCallbacks(wake)
            handler.postDelayed(wake, wakeIn + 1L)
        }
    }

    /**
     * Mala napoveda v rohu klavesy. Poloha 0..1 je uvnitr klavesy, mimo ten rozsah se linearne
     * prodlouzi a zastavi nejdal na obrysu sousedni klavesy (okraj klavesy + cela mezera).
     */
    /**
     * Rez: napoveda se rozpuli stejne jako klavesa, pulky spadnou a pak je napoveda skryta, dokud se klavesa nenarodi.
     * Vraci true, kdyz napovedu kreslila (nebo ji skryla); false = nakreslit normalne.
     */
    private fun drawCutHint(canvas: Canvas, k: Key, text: String, now: Long, radius: Float): Boolean {
        if (k.cutAt == 0L) return false
        val t = now - k.cutAt
        if (t < 0L || t >= CUT_FLY_MS + CUT_GAP_MS) return false
        if (t >= CUT_FLY_MS) return true
        val q = t / CUT_FLY_MS.toFloat()
        val ease = q * q
        val cx = k.vx + k.vw / 2f
        val cy = k.y + k.h / 2f
        val hw = k.vw / 2f - padX
        val hh = k.h / 2f - padY
        val rad = Math.toRadians(k.cutTilt.toDouble())
        val nx = kotlin.math.cos(rad).toFloat()
        val ny = kotlin.math.sin(rad).toFloat()
        val reach = hw * 0.9f * q
        val fall = hh * 2.2f * ease
        val big = (hw + hh) * 2f
        for (half in 0..1) {
            val dir = if (half == 0) -1f else 1f
            canvas.save()
            canvas.translate(nx * reach * dir, ny * reach * dir + fall)
            canvas.rotate(dir * 18f * q, cx, cy)
            canvas.rotate(k.cutTilt, cx, cy)
            if (half == 0) canvas.clipRect(cx - big, cy - big, cx, cy + big) else canvas.clipRect(cx, cy - big, cx + big, cy + big)
            canvas.rotate(-k.cutTilt, cx, cy)
            drawHint(canvas, k, text, 0f, radius)
            canvas.restore()
        }
        return true
    }

    private fun drawHint(canvas: Canvas, k: Key, text: String, p: Float, radius: Float, bitmap: Bitmap? = null) {
        val scale = 1f - 0.04f * p
        val cx = k.vx + k.vw / 2f
        val cy = k.y + k.h / 2f
        val hw = (k.vw / 2f - padX) * scale
        val hh = (k.h / 2f - padY) * scale
        textPaint.color = hintColor
        textPaint.textSize = HINT_BASE_DP * density * hintScale
        // Zaobleny roh ukroji d = r * (1 - 1/sqrt2); posun dovnitr jen blizko rohu.
        val fx = min(1f, abs(2f * hintX - 1f))
        val fy = min(1f, abs(2f * hintY - 1f))
        val d = min(radius, min(hw, hh)) * 0.2929f * fx * fy
        // Poloha napovedy se nemeni s velikosti znaku (pevny zaklad), znak se zase nehybe podle napovedy.
        val pb = LABEL_BASE_DP * density
        val xL = cx - (hw - pb * 0.34f - d)
        val xR = cx + (hw - pb * 0.34f - d)
        val yT = cy - hh + pb * 0.52f + d
        val yB = cy + hh - pb * 0.22f - d
        val x = (xL + (xR - xL) * hintX).coerceIn(k.vx - padX, k.vx + k.vw + padX)
        val y = (yT + (yB - yT) * hintY).coerceIn(k.y - padY, k.y + k.h + padY)
        if (text.isEmpty() && bitmap != null) {
            // Makro na podrzeni: maly obrazek makra na miste napovedy (hybe se spolu s ni).
            val bh = HINT_BASE_DP * 1.09f * density * hintScale // stejne velke jako znak napovedy
            val bw = bh * bitmap.width / bitmap.height
            val by = y - bh * 0.35f
            rect.set(x - bw / 2f, by - bh / 2f, x + bw / 2f, by + bh / 2f)
            bitmapPaint.alpha = 255
            canvas.drawBitmap(bitmap, null, rect, bitmapPaint)
            return
        }
        textPaint.typeface = hintFace
        canvas.drawText(text, x, y, textPaint)
        textPaint.typeface = labelFace
    }

    private fun isFlying(keyIndex: Int): Boolean {
        for (j in 0 until flyCount) if (flyKey[j] == keyIndex) return true
        return false
    }

    /**
     * Styl "slovo": pismena leti najednou z klaves (ease-out, rostou) na vodorovny stred, do urovne nad
     * horni radou (listy navrhu / radku nahledu; bez presahu nad pohled na horni okraj), kde ve spravnem
     * poradi sklada slovo. To vyskoci na 1.5x a problikne vyraznou barvou, pomalu se vrati a vybledne. Bez alokace.
     */
    private fun drawFly(canvas: Canvas, now: Long) {
        if (flyCount == 0) return
        val t = now - flyStart
        val hold = if (flyCut) CUT_HOLD_MS else FLY_HOLD_MS
        val fadeMs = if (flyCut) CUT_FALL_MS else FLY_FADE_MS
        if (t >= FLY_MS + hold + fadeMs) return
        val f = min(1f, t / FLY_MS.toFloat())
        val e = 1f - (1f - f) * (1f - f)
        val fade = if (flyCut || t < FLY_MS + hold) 1f else 1f - (t - FLY_MS - hold) / fadeMs.toFloat()
        val alpha = (255f * fade).toInt().coerceIn(0, 255)
        // Faze po dopadu: rychle zvetseni + probliknuti, pak pomaly navrat na 100 %.
        var pop = 0f
        if (t > FLY_MS && !flyCut) {
            val pt = t - FLY_MS
            pop = if (pt < FLY_POP_MS) {
                pt / FLY_POP_MS.toFloat()
            } else {
                val r = min(1f, (pt - FLY_POP_MS) / FLY_SETTLE_MS.toFloat())
                (1f - r) * (1f - r)
            }
        }
        val scale = 1f + (flyPopScale - 1f) * pop
        val size = (flyStartSize + (flyEndSize - flyStartSize) * e) * scale
        if (flyCut && t >= FLY_MS + hold) {
            drawCut(canvas, (t - FLY_MS - hold) / fadeMs.toFloat(), size)
            return
        }
        flyFill.textSize = size
        flyOutline.textSize = size
        flyOutline.strokeWidth = size * 0.14f
        val body = if (pop > 0f) Theme.blend(glowColor, flyFlash, pop) else glowColor
        flyFill.color = (body and 0x00FFFFFF) or (alpha shl 24)
        flyOutline.color = ((alpha * 0.6f).toInt().coerceIn(0, 255) shl 24)
        val landY = if (overflowTop > 0) -overflowTop / 2f else flyEndSize * 0.75f
        val dy = -(flyFill.ascent() + flyFill.descent()) / 2f
        for (j in 0 until flyCount) {
            val tx = width / 2f + (flyToX[j] - width / 2f) * scale
            val x = flyFromX[j] + (tx - flyFromX[j]) * e
            val y = flyFromY[j] + (landY - flyFromY[j]) * e + dy
            if (glowRainbow) {
                // Duha: kazde pismeno slova jinym odstinem.
                val c = rainbow(if (flyCount > 1) j / (flyCount - 1f) else 0f)
                val b = if (pop > 0f) Theme.blend(c, flyFlash, pop) else c
                flyFill.color = (b and 0x00FFFFFF) or (alpha shl 24)
            } else if (glowCol2 != 0) {
                val c = if (j % 2 == 0) glowColor else glowCol2
                val b = if (pop > 0f) Theme.blend(c, flyFlash, pop) else c
                flyFill.color = (b and 0x00FFFFFF) or (alpha shl 24)
            }
            canvas.drawText(flyChars, j, 1, x, y, flyOutline)
            canvas.drawText(flyChars, j, 1, x, y, flyFill)
        }
    }

    /** Pseudonahodne cislo 0..1 z (zrno, index, sul); bez alokace a bez stavu (stejne zrno = stejne hodnoty). */
    private fun sparkRand(seed: Int, i: Int, salt: Int): Float {
        var h = seed * 374761393 + i * 668265263 + salt * 2147483647
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16777216f
    }

    /**
     * Styl "rez" po dopadu slova: kazde pismeno se rozrizne ve 30-70 % vysky (nahodny sklon rezu) a obe poloviny
     * odpadnou od rezu (nahoru/dolu kolmo na rez, k tomu tiaz a nahodne natoceni). `q` = 0..1 postup padani. Bez alokace.
     */
    /** Meritko klavesy po rezu: 0 behem letu pulek a prodlevy, pak se klavesa narodi (s malym presahem). */
    private fun cutScale(t: Long): Float {
        if (t < CUT_FLY_MS + CUT_GAP_MS) return 0f
        val q = ((t - CUT_FLY_MS - CUT_GAP_MS) / CUT_BORN_MS.toFloat()).coerceIn(0f, 1f)
        val x = q - 1f
        return 1f + 2.70158f * x * x * x + 1.70158f * x * x
    }

    /** Dve pulky klavesy (se znakem) se rozletí do stran, padaji a miznou. Bez alokace. */
    private fun drawCutHalves(canvas: Canvas, k: Key, cx: Float, cy: Float, hw: Float, hh: Float, radius: Float, q: Float, color: Int, fg: Int, upper: Boolean) {
        val ease = q * q
        val reach = hw * 0.9f * q
        val fall = hh * 2.2f * ease
        val a = (255f * (1f - ease)).toInt().coerceIn(0, 255)
        val rad = Math.toRadians(k.cutTilt.toDouble())
        val nx = kotlin.math.cos(rad).toFloat()
        val ny = kotlin.math.sin(rad).toFloat()
        val big = (hw + hh) * 2f
        for (half in 0..1) {
            val dir = if (half == 0) -1f else 1f
            canvas.save()
            canvas.translate(nx * reach * dir, ny * reach * dir + fall)
            canvas.rotate(dir * 18f * q, cx, cy)
            canvas.rotate(k.cutTilt, cx, cy)
            if (half == 0) canvas.clipRect(cx - big, cy - big, cx, cy + big) else canvas.clipRect(cx, cy - big, cx + big, cy + big)
            canvas.rotate(-k.cutTilt, cx, cy)
            fill.color = (color and 0x00FFFFFF) or (a shl 24)
            rect.set(cx - hw, cy - hh, cx + hw, cy + hh)
            canvas.drawRoundRect(rect, radius, radius, fill)
            if (k.kind == KIND_CHAR) {
                textPaint.color = (fg and 0x00FFFFFF) or (a shl 24)
                textPaint.typeface = labelFace
                textPaint.textSize = labelSize
                val n = labelChars(k.label, upper)
                canvas.drawText(charBuf, 0, n, cx, cy - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
                textPaint.typeface = baseFace
            }
            canvas.restore()
        }
    }

    private fun drawCut(canvas: Canvas, q: Float, size: Float) {
        val landY = if (overflowTop > 0) -overflowTop / 2f else flyEndSize * 0.75f
        val dy = -(flyFill.ascent() + flyFill.descent()) / 2f
        val baseline = landY + dy
        val alpha = (255f * (1f - q * q)).toInt().coerceIn(0, 255)
        flyOutline.color = ((alpha * 0.6f).toInt().coerceIn(0, 255) shl 24)
        val reach = size * (0.5f * q + 0.7f * q * q)
        val gravity = size * 2.2f * q * q
        val big = size * 3f
        for (j in 0 until flyCount) {
            val c = when {
                glowRainbow -> rainbow(if (flyCount > 1) j / (flyCount - 1f) else 0f)
                glowCol2 != 0 -> if (j % 2 == 0) glowColor else glowCol2
                else -> glowColor
            }
            flyFill.color = (c and 0x00FFFFFF) or (alpha shl 24)
            val x = flyToX[j]
            // Telo pismene: male pismeno saha do 0,5 vysky, ostatni do 0,72 (rez musi vzdy protnout pismeno).
            val top = baseline - size * (if (flyChars[j].isLowerCase()) 0.5f else 0.72f)
            val cutY = top + (baseline + size * 0.02f - top) * (0.3f + 0.4f * sparkRand(flySeed, j, 0))
            val tilt = (sparkRand(flySeed, j, 1) - 0.5f) * 40f
            val rad = Math.toRadians(tilt.toDouble())
            val nx = sin(rad).toFloat()
            val ny = -cos(rad).toFloat()
            val side = if (sparkRand(flySeed, j, 2) < 0.5f) 1f else -1f
            val spin = (8f + 30f * sparkRand(flySeed, j, 3)) * q
            for (half in 0..1) {
                val dir = if (half == 0) 1f else -1f
                canvas.save()
                canvas.translate(nx * reach * dir + side * dir * size * 0.25f * q, ny * reach * dir + gravity)
                canvas.rotate(side * dir * spin, x, cutY)
                canvas.rotate(tilt, x, cutY)
                if (half == 0) canvas.clipRect(x - big, cutY - big, x + big, cutY) else canvas.clipRect(x - big, cutY, x + big, cutY + big)
                canvas.rotate(-tilt, x, cutY)
                canvas.drawText(flyChars, j, 1, x, baseline, flyOutline)
                canvas.drawText(flyChars, j, 1, x, baseline, flyFill)
                canvas.restore()
            }
        }
    }

    /** Pripravi let pismen slova (pozice klaves, sirky pismen, cilove x); vola glowWord. */
    private fun startFly(word: String) {
        var n = 0
        for (ch in word) {
            if (n >= GLOW_MAX_LETTERS) break
            if (!ch.isWhitespace()) flyChars[n++] = ch
        }
        if (n == 0) return
        flyStartSize = labelSize
        var endSize = labelSize * 1.6f
        flyFill.textSize = endSize
        flyFill.getTextWidths(flyChars, 0, n, flyWidths)
        var total = 0f
        for (j in 0 until n) total += flyWidths[j]
        val maxTotal = width * 0.92f
        if (total > maxTotal && total > 0f) {
            val sc = maxTotal / total
            endSize *= sc
            for (j in 0 until n) flyWidths[j] *= sc
            total = maxTotal
        }
        flyEndSize = endSize
        // Zvetsene slovo se musi vejit na sirku; barva probliknuti musi byt jina nez barva slova.
        flyPopScale = if (total > 0f) min(FLY_POP_SCALE, max(1f, width * 0.98f / total)) else FLY_POP_SCALE
        flyFlash = if (Theme.luminance(glowColor) > 0.6f) FLY_FLASH_ALT else FLY_FLASH
        var x = (width - total) / 2f
        for (j in 0 until n) {
            flyToX[j] = x + flyWidths[j] / 2f
            x += flyWidths[j]
            val base = baseCode(flyChars[j])
            var ki = -1
            for (i in keys.indices) {
                if (keys[i].kind == KIND_CHAR && keys[i].code == base) {
                    ki = i
                    break
                }
            }
            flyKey[j] = ki
            if (ki >= 0) {
                flyFromX[j] = keys[ki].vx + keys[ki].vw / 2f
                flyFromY[j] = keys[ki].y + keys[ki].h / 2f
            } else {
                flyFromX[j] = width / 2f
                flyFromY[j] = height / 2f
            }
        }
        flyCount = n
        flyCut = glowCutStyle
        flySeed = random.nextInt()
        flyStart = SystemClock.uptimeMillis()
        glowCount = 0
        invalidate()
    }

    /**
     * Efekt "glitch": pro kazdy zablesk obrys klavesy v invertovane barve (otoceny o nahodny uhel kolem
     * stredu klavesy) a obrys v barve efektu bez otoceni, nahodne posunuty. Vraci true, dokud nektery bezi.
     */
    private fun drawGlitch(canvas: Canvas, now: Long): Boolean {
        var active = false
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = 2f * density
        for (i in 0 until GLITCH_SLOTS) {
            val k = glitchKeys[i] ?: continue
            val count = glitchCount[i]
            val rel = now - glitchAt[i]
            if (rel >= (count - 1) * GLITCH_PERIOD_MS + GLITCH_ON_MS) {
                glitchKeys[i] = null
                continue
            }
            active = true
            if (rel < 0L) continue
            val flash = (rel / GLITCH_PERIOD_MS).toInt()
            if (flash >= count || rel - flash * GLITCH_PERIOD_MS >= GLITCH_ON_MS) continue
            val j = i * GLITCH_MAX_FLASHES + flash
            val cx = k.vx + k.vw / 2f
            val cy = k.y + k.h / 2f
            val hw = k.vw / 2f - padX
            val hh = k.h / 2f - padY
            stroke.color = glitchInv[i]
            canvas.save()
            canvas.rotate(glitchAngle[j], cx, cy)
            rect.set(cx - hw, cy - hh, cx + hw, cy + hh)
            canvas.drawRoundRect(rect, keyRadius, keyRadius, stroke)
            canvas.restore()
            val ox = cx + glitchDx[j] * hw * 2f
            val oy = cy + glitchDy[j] * hh * 2f
            stroke.color = glitchCol[j]
            rect.set(ox - hw, oy - hh, ox + hw, oy + hh)
            canvas.drawRoundRect(rect, keyRadius, keyRadius, stroke)
        }
        return active
    }

    /** Naplni charBuf popiskem (pripadne velkymi pismeny) bez alokace; vraci pocet znaku. */
    private fun labelChars(label: String, upper: Boolean): Int {
        val n = min(label.length, charBuf.size)
        for (i in 0 until n) {
            val c = label[i]
            charBuf[i] = if (upper) Character.toUpperCase(c) else c
        }
        return n
    }

    /** Obrazek na klavese: stred klavesy, pomer stran bitmapy, ~78 % vnitrni vysky (max `maxWidth` vnitrni sirky). */
    private fun drawMacroImage(
        canvas: Canvas, bmp: Bitmap, cx: Float, cy: Float, hw: Float, hh: Float, dimmed: Boolean, maxWidth: Float = 0.8f,
    ) {
        val ratio = bmp.width.toFloat() / bmp.height
        var h = hh * 2f * 0.78f
        var w = h * ratio
        val maxW = hw * 2f * maxWidth
        if (w > maxW) {
            w = maxW
            h = w / ratio
        }
        rect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        bitmapPaint.alpha = if (dimmed) 77 else 255
        canvas.drawBitmap(bmp, null, rect, bitmapPaint)
    }

    private fun drawIcon(canvas: Canvas, kind: Int, cx: Float, cy: Float, s: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = 1.7f * density
        stroke.style = Paint.Style.STROKE
        path.reset()
        when (kind) {
            KIND_SHIFT -> {
                @Suppress("NAME_SHADOWING")
                val shiftTarget = if (shiftState != SHIFT_OFF) 1.3f else 0.75f
                shiftScale += (shiftTarget - shiftScale) * 0.25f
                if (kotlin.math.abs(shiftTarget - shiftScale) > 0.01f) shiftNeedsFrame = true else shiftScale = shiftTarget
                val s = s * shiftScale
                path.moveTo(cx, cy - s * 1.1f)
                path.lineTo(cx + s * 1.1f, cy)
                path.lineTo(cx + s * 0.5f, cy)
                path.lineTo(cx + s * 0.5f, cy + s * 0.9f)
                path.lineTo(cx - s * 0.5f, cy + s * 0.9f)
                path.lineTo(cx - s * 0.5f, cy)
                path.lineTo(cx - s * 1.1f, cy)
                path.close()
                if (shiftState != SHIFT_OFF) {
                    stroke.style = Paint.Style.FILL_AND_STROKE
                    if (shiftState == SHIFT_ONCE) stroke.color = theme.accent
                }
                canvas.drawPath(path, stroke)
                if (shiftState == SHIFT_CAPS) {
                    // Caps Lock: carka pod sipkou.
                    stroke.style = Paint.Style.STROKE
                    canvas.drawLine(cx - s * 0.5f, cy + s * 1.35f, cx + s * 0.5f, cy + s * 1.35f, stroke)
                }
            }
            KIND_BACKSPACE -> drawBackspaceIcon(canvas, cx, cy, s)
            KIND_ENTER -> drawEnterIcon(canvas, cx, cy, s)
            KIND_MACRO -> drawBolt(canvas, cx, cy, s, color)
            KIND_EMOJI -> drawDiamond(canvas, cx, cy, s, theme.accent)
        }
    }

    private val burstDx = floatArrayOf(0.866f, 0.5f, -0.866f, -0.5f)
    private val burstDy = floatArrayOf(-0.5f, 0.866f, 0.5f, -0.866f)

    /** Znak smazani se rozctvrti a ctvrtiny odletí na 2., 5., 8. a 11. hodinu; potom se znak vrati. */
    private fun drawBackspaceBurst(canvas: Canvas, cx: Float, cy: Float, s: Float, color: Int, dt: Long) {
        if (dt >= BURST_MS) {
            val a = ((dt - BURST_MS) / 250f).coerceIn(0f, 1f)
            drawIcon(canvas, KIND_BACKSPACE, cx, cy, s, (color and 0x00FFFFFF) or ((255f * a).toInt() shl 24))
            return
        }
        val q = dt / BURST_MS.toFloat()
        val c = (color and 0x00FFFFFF) or ((255f * (1f - q)).toInt().coerceIn(0, 255) shl 24)
        val reach = s * 2.4f * q
        val big = s * 4f
        for (i in 0..3) {
            canvas.save()
            canvas.translate(burstDx[i] * reach, burstDy[i] * reach)
            val right = i == 0 || i == 1
            val top = i == 0 || i == 3
            canvas.clipRect(if (right) cx else cx - big, if (top) cy - big else cy, if (right) cx + big else cx, if (top) cy else cy + big)
            drawIcon(canvas, KIND_BACKSPACE, cx, cy, s, c)
            canvas.restore()
        }
    }

    /** Ikona smazani (sipka s krizkem); pouziva ji klavesa i tlacitko na obrazovce diktovani. */
    private fun drawBackspaceIcon(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        path.reset()
        path.moveTo(cx - s * 1.3f, cy)
        path.lineTo(cx - s * 0.5f, cy - s * 0.8f)
        path.lineTo(cx + s * 1.1f, cy - s * 0.8f)
        path.lineTo(cx + s * 1.1f, cy + s * 0.8f)
        path.lineTo(cx - s * 0.5f, cy + s * 0.8f)
        path.close()
        path.moveTo(cx - s * 0.1f, cy - s * 0.35f)
        path.lineTo(cx + s * 0.6f, cy + s * 0.35f)
        path.moveTo(cx + s * 0.6f, cy - s * 0.35f)
        path.lineTo(cx - s * 0.1f, cy + s * 0.35f)
        canvas.drawPath(path, stroke)
    }

    /** Ikona Enteru podle akce pole: hledat, odeslat, dalsi, hotovo, jinak novy radek. */
    private fun drawEnterIcon(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        when (enterAction) {
            ENTER_SEARCH -> {
                canvas.drawCircle(cx - s * 0.2f, cy - s * 0.2f, s * 0.7f, stroke)
                canvas.drawLine(cx + s * 0.35f, cy + s * 0.35f, cx + s, cy + s, stroke)
            }
            ENTER_SEND -> {
                path.moveTo(cx - s, cy - s * 0.85f)
                path.lineTo(cx + s * 1.1f, cy)
                path.lineTo(cx - s, cy + s * 0.85f)
                path.lineTo(cx - s * 0.55f, cy)
                path.close()
                path.moveTo(cx - s * 0.55f, cy)
                path.lineTo(cx + s * 0.3f, cy)
                canvas.drawPath(path, stroke)
            }
            ENTER_NEXT -> {
                path.moveTo(cx - s, cy)
                path.lineTo(cx + s, cy)
                path.moveTo(cx + s * 0.35f, cy - s * 0.65f)
                path.lineTo(cx + s, cy)
                path.lineTo(cx + s * 0.35f, cy + s * 0.65f)
                canvas.drawPath(path, stroke)
            }
            ENTER_DONE -> {
                path.moveTo(cx - s * 0.9f, cy + s * 0.05f)
                path.lineTo(cx - s * 0.25f, cy + s * 0.7f)
                path.lineTo(cx + s * 0.95f, cy - s * 0.6f)
                canvas.drawPath(path, stroke)
            }
            else -> {
                path.moveTo(cx + s * 0.9f, cy - s * 0.7f)
                path.lineTo(cx + s * 0.9f, cy + s * 0.25f)
                path.lineTo(cx - s * 0.8f, cy + s * 0.25f)
                path.moveTo(cx - s * 0.3f, cy - s * 0.25f)
                path.lineTo(cx - s * 0.8f, cy + s * 0.25f)
                path.lineTo(cx - s * 0.3f, cy + s * 0.75f)
                canvas.drawPath(path, stroke)
            }
        }
    }

    /**
     * Stopa za prstem: zuzuje se a blednne smerem k ocasu. Po zvednuti prstu jeste kratce
     * dozniva. Vraci true, dokud dobiha animace.
     */
    private fun drawTrail(canvas: Canvas, now: Long): Boolean {
        if (!drawTrailEnabled || trailCount < 2) return false
        if (fireTrail) return drawFire(canvas, now)
        if (pixelTrail) return drawPixelTrail(canvas, now)
        if (dashedTrail) return drawDashedTrail(canvas, now)
        val fade = if (touchMode == T_SWIPE || trailHeld) 1f else {
            if (!animate) return false
            1f - (now - trailEndedAt) / TRAIL_FADE_MS
        }
        if (fade <= 0f) {
            trailCount = 0
            return false
        }
        val first = max(0, trailCount - TRAIL_POINTS)
        val span = (trailCount - first).toFloat()
        val rgb = trailCol
        stroke.style = Paint.Style.STROKE
        var px = samples[first * 3]
        var py = samples[first * 3 + 1]
        for (i in first + 1 until trailCount) {
            val f = (i - first) / span
            val x = samples[i * 3]
            val y = samples[i * 3 + 1]
            val segRgb = if (trailCol2 != 0) Theme.blend(trailCol2, rgb, f) and 0x00FFFFFF else rgb
            stroke.color = segRgb or ((200f * f * fade).toInt().coerceIn(0, 255) shl 24)
            stroke.strokeWidth = (1.5f + 5f * f) * density * (trailWidthDp / 4f)
            canvas.drawLine(px, py, x, y, stroke)
            px = x
            py = y
        }
        fill.color = rgb or ((255f * fade).toInt().coerceIn(0, 255) shl 24)
        canvas.drawCircle(px, py, 4.5f * density * (trailWidthDp / 4f), fill)
        return touchMode != T_SWIPE
    }

    /**
     * Horici cara: za prstem hori plamen, jehoz hlava je nejhorejsi; zpet po draze tahu zustavaji zhavici stopy,
     * ktere hori mene a mene (zlute, oranzove, cervene), a po zvednuti prstu cela cara postupne dohori.
     * Plamen se chveje (nahoda se meni po FIRE_FLICKER_MS). Bez alokace; vraci true, dokud neco hori.
     */
    /** Pixelova linka: ctverecky podel tahu. Sirka z trail_width_dp. */
    private fun drawPixelTrail(canvas: Canvas, now: Long): Boolean {
        val fade = if (touchMode == T_SWIPE || trailHeld) 1f else { if (!animate) return false; 1f - (now - trailEndedAt) / TRAIL_FADE_MS }
        if (fade <= 0f) { trailCount = 0; return false }
        val s = (3f + trailWidthDp * 0.9f) * density
        val first = max(0, trailCount - TRAIL_POINTS)
        for (i in first until trailCount) {
            val f = (i - first) / max(1f, (trailCount - first).toFloat())
            val x = samples[i * 3]; val y = samples[i * 3 + 1]
            val c = if (trailCol2 != 0) Theme.blend(trailCol2, trailCol, f) and 0x00FFFFFF else trailCol
            fill.color = c or ((230f * f * fade).toInt().coerceIn(0, 255) shl 24)
            canvas.drawRect(x - s / 2f, y - s / 2f, x + s / 2f, y + s / 2f, fill)
        }
        return touchMode != T_SWIPE
    }
    /** Carkovana linka: kratsi useky s mezerami. */
    private fun drawDashedTrail(canvas: Canvas, now: Long): Boolean {
        val fade = if (touchMode == T_SWIPE || trailHeld) 1f else { if (!animate) return false; 1f - (now - trailEndedAt) / TRAIL_FADE_MS }
        if (fade <= 0f) { trailCount = 0; return false }
        val first = max(0, trailCount - TRAIL_POINTS)
        val span = (trailCount - first).toFloat()
        stroke.style = Paint.Style.STROKE
        stroke.strokeCap = Paint.Cap.ROUND
        var px = samples[first * 3]; var py = samples[first * 3 + 1]
        for (i in first + 1 until trailCount) {
            val f = (i - first) / span
            val x = samples[i * 3]; val y = samples[i * 3 + 1]
            if (i % 3 != 0) { px = x; py = y; continue }
            val c = if (trailCol2 != 0) Theme.blend(trailCol2, trailCol, f) and 0x00FFFFFF else trailCol
            stroke.color = c or ((200f * f * fade).toInt().coerceIn(0, 255) shl 24)
            stroke.strokeWidth = (1.5f + trailWidthDp * 0.8f) * density
            canvas.drawLine(px, py, x, y, stroke)
            px = x; py = y
        }
        return touchMode != T_SWIPE
    }
    /** Enter / Mazat navzdy: znak jede za klavesu do strany, pak 2 mini pulzy nahoru + 2 dolu. */
    private fun drawEnterSlide(canvas: Canvas, now: Long): Boolean {
        val k = enterSlideKey
        if (enterSlideStart == 0L || k == null) return false
        val dt = now - enterSlideStart
        if (dt >= 720L) {
            enterSlideStart = 0L
            enterSlideKey = null
            return false
        }
        val green = 0xFF22C55E.toInt() and 0x00FFFFFF
        val l = k.vx + padX
        val t = k.y + padY
        val r = k.vx + k.vw - padX
        val b = k.y + k.h - padY
        // Zelena vypln roste zleva doprava z 0 na 100 %, potom vybledne.
        val fq = (dt / 300f).coerceIn(0f, 1f)
        val fa = if (dt < 300L) 0.85f else 0.85f * (1f - (dt - 300L) / 420f)
        fill.color = green or ((255f * fa).toInt().coerceIn(0, 255) shl 24)
        canvas.save()
        canvas.clipRect(l, t, l + (r - l) * fq, b)
        rect.set(l, t, r, b)
        canvas.drawRoundRect(rect, keyRadius, keyRadius, fill)
        canvas.restore()
        // Zeleny pulz vystreli dokola celeho tlacitka.
        if (dt > 250L) {
            val pq = ((dt - 250L) / 470f).coerceIn(0f, 1f)
            val g = 12f * density * pq
            ghostPaint.color = green or ((255f * (1f - pq)).toInt().coerceIn(0, 255) shl 24)
            ghostPaint.strokeWidth = 2.5f * density * (1f - 0.5f * pq)
            rect.set(l - g, t - g, r + g, b + g)
            canvas.drawRoundRect(rect, keyRadius + g, keyRadius + g, ghostPaint)
        }
        return true
    }
    /** Banner ulozeni slova uvnitr mezerniku: 1x zprava doleva, od praveho okraje za levy kraj. */
    private fun drawSavedBanner(canvas: Canvas, now: Long): Boolean {
        if (savedBannerStart == 0L || savedBannerText.isEmpty()) return false
        val dur = SAVED_BANNER_MS; val dt = now - savedBannerStart
        if (dt >= dur) { savedBannerStart = 0L; savedBannerText = ""; savedBannerEndedAt = now; return true }
        val space = keys.firstOrNull { it.kind == KIND_SPACE } ?: return false
        val left = space.vx; val right = space.vx + space.vw; val cy = space.y + space.h / 2f
        textPaint.color = (0xFFFFFF - (theme.key and 0xFFFFFF)) or 0xFF000000.toInt(); textPaint.textSize = min(rowH * 0.32f, 15f * density * fontScale); textPaint.typeface = labelFace
        val tw = textPaint.measureText(savedBannerText)
        val total = (right - left) + tw + 40f * density
        val x = right + 20f * density - total * (dt / dur.toFloat())
        canvas.save(); canvas.clipRect(left, space.y, right, space.y + space.h)
        val baseY = cy - (textPaint.ascent() + textPaint.descent()) / 2f
        val keepAlign = textPaint.textAlign
        textPaint.textAlign = Paint.Align.LEFT
        val from = savedWordFrom.coerceIn(0, savedBannerText.length)
        val to = savedWordTo.coerceIn(from, savedBannerText.length)
        val w1 = textPaint.measureText(savedBannerText, 0, from)
        val w2 = textPaint.measureText(savedBannerText, from, to)
        canvas.drawText(savedBannerText, 0, from, x, baseY, textPaint)
        val keepColor = textPaint.color
        textPaint.color = theme.accent or 0xFF000000.toInt()
        canvas.drawText(savedBannerText, from, to, x + w1, baseY, textPaint)
        textPaint.color = keepColor
        canvas.drawText(savedBannerText, to, savedBannerText.length, x + w1 + w2, baseY, textPaint)
        textPaint.textAlign = keepAlign
        canvas.restore(); textPaint.typeface = baseFace
        return true
    }

    private fun drawFire(canvas: Canvas, now: Long): Boolean {
        // Sirka stopy (trail_width_dp, vychozi 4) meni velikost cele ohnive stopy: tloustku, plamen i zari hlavy.
        val dn = density * (trailWidthDp / 4f)
        var burn = 0f
        if (touchMode != T_SWIPE && !trailHeld) {
            if (!animate) return false
            burn = (now - trailEndedAt) / FIRE_OUT_MS
            if (burn >= 1f) {
                trailCount = 0
                return false
            }
        }
        val first = max(0, trailCount - FIRE_POINTS)
        val span = (trailCount - first).toFloat()
        val bucket = (now / FIRE_FLICKER_MS).toInt()
        val life = 1f - burn
        stroke.style = Paint.Style.STROKE
        var px = samples[first * 3]
        var py = samples[first * 3 + 1]
        for (i in first + 1 until trailCount) {
            val f = (i - first) / span
            // Zhavost: hlava (f = 1) hori naplno, ocas rychle uhasina; po zvednuti prstu klesa vse.
            val heat = f * f * life
            val x = samples[i * 3]
            val y = samples[i * 3 + 1]
            val flick = 0.75f + 0.5f * sparkRand(bucket, i, 7)
            val col = if (heat > 0.5f) Theme.blend(FIRE_MID, FIRE_HOT, (heat - 0.5f) * 2f) else Theme.blend(FIRE_COLD, FIRE_MID, heat * 2f)
            stroke.color = (col and 0x00FFFFFF) or ((230f * heat).toInt().coerceIn(0, 255) shl 24)
            stroke.strokeWidth = (1.2f + 4.5f * heat) * dn * flick
            canvas.drawLine(px, py, x, y, stroke)
            // Jazyk plamene: kulicka stoupa nad drahou, tim vys a vetsi, cim je bod zhavejsi.
            if (heat > 0.12f && i % 2 == 0) {
                val rise = heat * (3f + 11f * sparkRand(bucket, i, 8)) * dn
                val sway = (sparkRand(bucket, i, 9) - 0.5f) * 6f * dn * heat
                fill.color = (col and 0x00FFFFFF) or ((200f * heat).toInt().coerceIn(0, 255) shl 24)
                canvas.drawCircle(x + sway, y - rise, (1f + 3f * heat) * dn * flick, fill)
            }
            px = x
            py = y
        }
        // Hlava plamene: oranzova zare, uvnitr zluta a bily stred, nahore tri zachvevajici se jazyky.
        val hot = life
        fill.color = (FIRE_MID and 0x00FFFFFF) or ((150f * hot).toInt().coerceIn(0, 255) shl 24)
        canvas.drawCircle(px, py, (8f + 2f * sparkRand(bucket, 0, 10)) * dn * (0.5f + 0.5f * hot), fill)
        fill.color = (FIRE_HOT and 0x00FFFFFF) or ((230f * hot).toInt().coerceIn(0, 255) shl 24)
        canvas.drawCircle(px, py, 5f * dn * (0.5f + 0.5f * hot), fill)
        fill.color = (WHITE and 0x00FFFFFF) or ((255f * hot).toInt().coerceIn(0, 255) shl 24)
        canvas.drawCircle(px, py, 2.4f * dn * (0.5f + 0.5f * hot), fill)
        fill.color = (FIRE_MID and 0x00FFFFFF) or ((220f * hot).toInt().coerceIn(0, 255) shl 24)
        for (t in 0 until 3) {
            val h = (7f + 10f * sparkRand(bucket, t, 11)) * dn * hot
            val sx = (sparkRand(bucket, t, 12) - 0.5f) * 10f * dn
            canvas.drawCircle(px + sx, py - h, (1.6f + 1.6f * sparkRand(bucket, t, 13)) * dn * hot, fill)
        }
        return true
    }

    /** Bublina s pismenem nad stisknutou klavesou. Vraci true, dokud bezi animace. */
    private fun drawPreview(canvas: Canvas, now: Long): Boolean {
        val k = previewKey ?: return false
        var a = 1f
        var running = false
        if (animate) {
            if (previewEndedAt == 0L) {
                a = min(1f, (now - previewStartedAt) / PREVIEW_IN_MS)
                running = a < 1f
            } else {
                a = 1f - (now - previewEndedAt) / PREVIEW_OUT_MS
                running = true
            }
        } else if (previewEndedAt != 0L) {
            a = 0f
        }
        if (a <= 0f) {
            previewKey = null
            return false
        }
        val w = max(k.vw * 1.25f, 44f * density)
        val h = rowH * 1.02f
        val cx = (k.vx + k.vw / 2f).coerceIn(w / 2f + density, width - w / 2f - density)
        val bottom = k.y + 2f * density
        val top = max(-overflowTop.toFloat() + 2f * density, bottom - h)
        val grow = 0.85f + 0.15f * a
        val hw = w / 2f * grow
        val mid = (top + bottom) / 2f
        val hh = (bottom - top) / 2f * grow
        val alpha = (255f * a).toInt().coerceIn(0, 255)
        if (drawShadow) {
            fill.color = (theme.keyShadow and 0x00FFFFFF) or (alpha shl 24)
            rect.set(cx - hw, mid - hh + shadowOffset, cx + hw, mid + hh + shadowOffset)
            canvas.drawRoundRect(rect, keyRadius * 1.3f, keyRadius * 1.3f, fill)
        }
        fill.color = (popupFill and 0x00FFFFFF) or (alpha shl 24)
        rect.set(cx - hw, mid - hh, cx + hw, mid + hh)
        canvas.drawRoundRect(rect, keyRadius * 1.3f, keyRadius * 1.3f, fill)
        textPaint.color = (popupFg and 0x00FFFFFF) or (alpha shl 24)
        textPaint.textSize = min(rowH * 0.56f, unitW * 0.72f)
        val n = labelChars(k.label, shiftState != SHIFT_OFF && mode == MODE_LETTERS)
        textPaint.typeface = if (k.isDigit) digitFace else labelFace
        canvas.drawText(charBuf, 0, n, cx, mid - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
        textPaint.typeface = baseFace
        return running
    }

    private fun drawMic(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        rect.set(cx - s * 0.42f, cy - s * 1.15f, cx + s * 0.42f, cy + s * 0.25f)
        stroke.style = Paint.Style.FILL_AND_STROKE
        canvas.drawRoundRect(rect, s * 0.42f, s * 0.42f, stroke)
        stroke.style = Paint.Style.STROKE
        rect.set(cx - s * 0.85f, cy - s * 0.75f, cx + s * 0.85f, cy + s * 0.7f)
        canvas.drawArc(rect, 0f, 180f, false, stroke)
        canvas.drawLine(cx, cy + s * 0.7f, cx, cy + s * 1.15f, stroke)
    }

    /** Kosoctverec (diamant) - jen obrys v accent barve. Tlacitko emoji. */
    private fun drawDiamond(canvas: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        path.reset()
        path.moveTo(cx, cy - s * 1.15f)
        path.lineTo(cx + s * 0.9f, cy)
        path.lineTo(cx, cy + s * 1.15f)
        path.lineTo(cx - s * 0.9f, cy)
        path.close()
        stroke.color = color
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = 2f * density
        canvas.drawPath(path, stroke)
    }

    /** Blesk - tlacitko maker. */
    private fun drawBolt(canvas: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        path.reset()
        path.moveTo(cx + s * 0.25f, cy - s * 1.2f)
        path.lineTo(cx - s * 0.7f, cy + s * 0.15f)
        path.lineTo(cx - s * 0.05f, cy + s * 0.15f)
        path.lineTo(cx - s * 0.25f, cy + s * 1.2f)
        path.lineTo(cx + s * 0.7f, cy - s * 0.15f)
        path.lineTo(cx + s * 0.05f, cy - s * 0.15f)
        path.close()
        stroke.color = color
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = 1.7f * density
        canvas.drawPath(path, stroke)
    }

    private fun drawMenuIcon(canvas: Canvas, icon: Int, cx: Float, cy: Float, s: Float, color: Int, selected: Boolean) {
        stroke.color = color
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = 1.7f * density
        when (icon) {
            ICON_DIAMOND -> drawDiamond(canvas, cx, cy, s, if (selected) color else theme.accent)
            ICON_CLIPBOARD -> {
                rect.set(cx - s * 0.85f, cy - s * 0.9f, cx + s * 0.85f, cy + s * 1.1f)
                canvas.drawRoundRect(rect, s * 0.25f, s * 0.25f, stroke)
                rect.set(cx - s * 0.4f, cy - s * 1.2f, cx + s * 0.4f, cy - s * 0.65f)
                canvas.drawRoundRect(rect, s * 0.15f, s * 0.15f, stroke)
                canvas.drawLine(cx - s * 0.4f, cy + s * 0.05f, cx + s * 0.4f, cy + s * 0.05f, stroke)
                canvas.drawLine(cx - s * 0.4f, cy + s * 0.55f, cx + s * 0.4f, cy + s * 0.55f, stroke)
            }
            ICON_GEAR -> {
                canvas.drawCircle(cx, cy, s * 0.78f, stroke)
                canvas.drawCircle(cx, cy, s * 0.28f, stroke)
                canvas.save()
                repeat(8) {
                    canvas.drawLine(cx, cy - s * 0.78f, cx, cy - s * 1.12f, stroke)
                    canvas.rotate(45f, cx, cy)
                }
                canvas.restore()
            }
            ICON_BOLT -> drawBolt(canvas, cx, cy, s, color)
        }
    }

    /** Nabidka nad klavesou: rychla nabidka (drzeni ?123) nebo seznam maker. */
    private fun drawMenu(canvas: Canvas) {
        if (menuKey == null) return
        val n = menuItems.size
        val radius = keyRadius * 1.3f
        val bottom = menuTop + menuCell * n
        if (drawShadow) {
            fill.color = theme.keyShadow
            rect.set(menuLeft, menuTop + shadowOffset, menuLeft + menuW, bottom + shadowOffset)
            canvas.drawRoundRect(rect, radius, radius, fill)
        }
        fill.color = theme.popup
        rect.set(menuLeft, menuTop, menuLeft + menuW, bottom)
        canvas.drawRoundRect(rect, radius, radius, fill)
        textPaint.textSize = min(menuCell * 0.34f, 15f * density * fontScale)
        textPaint.textAlign = Paint.Align.LEFT
        for (i in 0 until n) {
            val top = menuTop + menuCell * i
            val cy = top + menuCell / 2f
            val selected = i == menuSel
            val fg = if (selected) theme.onAccent else theme.text
            if (selected) {
                fill.color = theme.accent
                rect.set(menuLeft + 3 * density, top + 3 * density, menuLeft + menuW - 3 * density, top + menuCell - 3 * density)
                canvas.drawRoundRect(rect, radius * 0.75f, radius * 0.75f, fill)
            }
            val icon = if (i < menuIcons.size) menuIcons[i] else ICON_NONE
            var textX = menuLeft + 18f * density
            if (icon != ICON_NONE) {
                drawMenuIcon(canvas, icon, menuLeft + 28f * density, cy, min(menuCell, 48f * density) * 0.2f, fg, selected)
                textX = menuLeft + 54f * density
            }
            textPaint.color = fg
            val label = menuItems[i]
            val maxW = menuLeft + menuW - textX - 12f * density
            val count = textPaint.breakText(label, 0, label.length, true, maxW, null)
            canvas.drawText(label, 0, count, textX, cy - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
        }
        textPaint.textAlign = Paint.Align.CENTER
    }

    private fun menuAt(x: Float, y: Float): Int {
        val n = menuItems.size
        if (n == 0 || x < menuLeft || x > menuLeft + menuW || y < menuTop || y >= menuTop + menuCell * n) return -1
        return ((y - menuTop) / menuCell).toInt().coerceIn(0, n - 1)
    }

    /** Pocet polozek naposledy otevrene nabidky (po pripadnem oriznuti). */
    var lastMenuSize = 0
        private set

    private fun closeMenu() {
        if (menuKey != null) {
            menuKey = null
            menuSticky = false
            menuSel = -1
            invalidate()
        }
    }

    private fun openMenu(k: Key, items: List<String>, icons: IntArray, kind: Int, sticky: Boolean) {
        if (items.isEmpty()) return
        // Vyska polozky se prizpusobi mistu nad klavesou (vcetne presahu nad listu navrhu),
        // aby se delsi seznam maker vesel; pod 38 dp uz by se spatne trefovalo.
        val available = k.y + overflowTop - 6f * density
        menuCell = (available / items.size).coerceIn(38f * density, max(rowH * 0.9f, 46f * density))
        val fit = max(1, (available / menuCell).toInt())
        menuItems = if (items.size > fit) items.subList(items.size - fit, items.size) else items
        menuIcons = if (items.size > fit) icons.copyOfRange(minOf(icons.size, items.size - fit), icons.size) else icons
        menuKind = kind
        lastMenuSize = menuItems.size
        menuKey = k
        menuSel = -1
        menuSticky = sticky
        menuW = min(max(unitW * 3.8f, 184f * density), width - 8f * density)
        menuLeft = (k.vx + k.vw / 2f - menuW / 2f).coerceIn(4f * density, max(4f * density, width - menuW - 4f * density))
        menuTop = max(-overflowTop + 2f * density, k.y - menuItems.size * menuCell - 4f * density)
        invalidate()
    }

    /** Otevre nabidku nad klavesou (polozka se vybere klepnutim). Pouziva sluzba pro makra. */
    fun showMenu(anchor: Key, items: List<String>, icons: IntArray, kind: Int) {
        openMenu(anchor, items, icons, kind, sticky = true)
    }

    // ------------------------------------------------------------------ rozsviceni klaves

    /**
     * Postupne rozsviceni pismen slova: prvni pismeno, pak druhe... az posledni, jako by se slovo
     * skladalo po pismenech; pak zhasne cele naraz. Diakritika se prevede na zakladni pismeno (s blika na klavese s).
     */
    fun glowWord(word: String) {
        if (!glowEnabled || word.isEmpty()) return
        if (glowFlipStyle) {
            val now = SystemClock.uptimeMillis()
            var n = 0
            var total = 0
            for (ch in word) if (baseCode(ch) in 'a'.code..'z'.code) total++
            for (ch in word) {
                val code = baseCode(ch)
                if (code !in 'a'.code..'z'.code) continue
                for (k in keys) {
                    if (k.isLetter && k.code == code) {
                        k.flipMix = if (total > 1) n / (total - 1f) else 0f
                        k.flipIdx = n
                        k.flipAt = now + n * 110L
                        k.flipTarget = (if (random.nextBoolean()) 1f else -1f) * (90f + random.nextFloat() * 90f)
                        n++
                        break
                    }
                }
            }
            if (n > 0) invalidate()
            return
        }
        if (glowFlyStyle) {
            startFly(word)
            return
        }
        var n = 0
        for (ch in word) {
            val code = baseCode(ch)
            if (code !in 'a'.code..'z'.code || n >= glowSeq.size) continue
            for (i in keys.indices) {
                val k = keys[i]
                if (k.isLetter && k.code == code) {
                    glowSeq[n++] = i
                    break
                }
            }
        }
        if (n == 0) return
        glowFill = glowFillStyle
        if (glowFill) {
            // Vybarveni: kazde klavese slova se nahodne urci strana, ze ktere se zaplni.
            java.util.Arrays.fill(glowSide, -1)
            for (j in 0 until n) glowSide[glowSeq[j]] = random.nextInt(4)
        }
        glowCount = n
        glowStart = SystemClock.uptimeMillis()
        invalidate()
    }

    private fun demoLetter(c: Char): Key? = keys.firstOrNull { it.isLetter && it.code == c.code }

    /** Klavesa pismene `ch` (diakritika se prevede na zaklad); pro ukazky slov. */
    private fun demoKeyFor(ch: Char): Key? = demoLetter(baseCode(ch).toChar())

    // --- ukazka nastaveni: blikajici skupina klaves, ostatni ztlumene
    private val spotRects = FloatArray(SPOT_MAX * 4)
    private var spotCount = 0
    private var spotAt = 0L
    private var spotUntil = 0L

    /**
     * Zablikne v barve akcentu klavesami, pro ktere `pick` vrati true; ostatni klavesy se na dobu `ms` ztlumi.
     * Poloha se zapamatuje hned (i kdyz se klavesy potom z rozlozeni ztrati, napr. vypnuta rada cislic).
     */
    fun spotKeys(ms: Long = SPOT_MS, mode: Int = SPOT_FILL, pick: (Key) -> Boolean) {
        spotMode = mode
        spotPick = pick
        spotCount = fillSpotRects(pick)
        spotAt = SystemClock.uptimeMillis()
        spotUntil = spotAt + ms
        invalidate()
    }

    private var spotMode = SPOT_FILL
    private var spotPick: ((Key) -> Boolean)? = null

    /** Zapise obdelniky klaves vybranych `pick` z aktualniho rozlozeni; vraci jejich pocet. */
    private fun fillSpotRects(pick: (Key) -> Boolean): Int {
        var n = 0
        for (k in keys) {
            if (n >= SPOT_MAX || !pick(k)) continue
            spotRects[n * 4] = k.vx + padX
            spotRects[n * 4 + 1] = k.y + padY
            spotRects[n * 4 + 2] = k.vx + k.vw - padX
            spotRects[n * 4 + 3] = k.y + k.h - padY
            n++
        }
        return n
    }

    /** Zablikne oddelovac mezi radou cislic a pismeny (tenky pruh pres celou sirku). */
    fun spotDivider(ms: Long = SPOT_MS) {
        spotMode = SPOT_LINE
        spotPick = null
        spotRects[0] = 0f
        spotRects[1] = rowH - 1.5f * density
        spotRects[2] = width.toFloat()
        spotRects[3] = rowH + 1.5f * density
        spotCount = 1
        spotAt = SystemClock.uptimeMillis()
        spotUntil = spotAt + ms
        invalidate()
    }

    /** Zablikne jen stinem kláves (zvetsi se a zabarvi). */
    fun spotShadow(ms: Long = SPOT_MS) {
        spotMode = SPOT_SHADOW
        spotPick = null
        spotCount = 0
        spotAt = SystemClock.uptimeMillis()
        spotUntil = spotAt + ms
        invalidate()
    }

    /** Zablikne jen pozadim (klavesy zustanou). */
    fun spotBackground(ms: Long = SPOT_MS) {
        spotMode = SPOT_BG
        spotPick = null
        spotCount = 0
        spotAt = SystemClock.uptimeMillis()
        spotUntil = spotAt + ms
        invalidate()
    }

    /** Pulz ukazky 0..1 (dvakrat zabliknout, na konci utlumit). */
    private fun spotAlpha(now: Long): Float {
        val span = max(1L, spotUntil - spotAt).toFloat()
        val q = ((now - spotAt) / span).coerceIn(0f, 1f)
        val pulse = 0.5f - 0.5f * kotlin.math.cos(q * 4f * PI.toFloat())
        return pulse * min(1f, (1f - q) / 0.15f)
    }

    private fun inSpot(x: Float, y: Float): Boolean {
        for (i in 0 until spotCount) {
            if (x >= spotRects[i * 4] && x <= spotRects[i * 4 + 2] && y >= spotRects[i * 4 + 1] && y <= spotRects[i * 4 + 3]) return true
        }
        return false
    }

    private fun drawSpot(canvas: Canvas, now: Long, radius: Float) {
        if (spotMode == SPOT_SHADOW || spotMode == SPOT_BG) return
        val a = spotAlpha(now)
        if (a <= 0f) return
        val col = theme.accent and 0x00FFFFFF
        val r = if (spotMode == SPOT_LINE) 1.5f * density else radius
        fill.color = col or ((120f * a).toInt().coerceIn(0, 255) shl 24)
        ghostPaint.color = col or ((255f * a).toInt().coerceIn(0, 255) shl 24)
        ghostPaint.strokeWidth = 2.5f * density
        for (i in 0 until spotCount) {
            rect.set(spotRects[i * 4], spotRects[i * 4 + 1], spotRects[i * 4 + 2], spotRects[i * 4 + 3])
            if (spotMode == SPOT_FILL || spotMode == SPOT_LINE) canvas.drawRoundRect(rect, r, r, fill)
            if (spotMode != SPOT_LINE) canvas.drawRoundRect(rect, r, r, ghostPaint)
        }
    }

    /** Ukazka bubliny nad klavesou (nahled v nastaveni): na ~0,7 s se objevi nad pismenem. */
    fun demoPopup() {
        if (touchMode != T_NONE) return
        val k = demoLetter('g') ?: keys.firstOrNull { it.kind == KIND_CHAR } ?: return
        handler.removeCallbacksAndMessages(demoToken)
        previewKey = null
        startPreview(k)
        if (!popupHeld) handler.postAtTime({ endPreview() }, demoToken, SystemClock.uptimeMillis() + DEMO_POPUP_MS)
        invalidate()
    }

    /** Bublina nad klavesou zustane videt, dokud je `on` (vyber barvy bubliny v nastaveni); po vypnuti zmizi. */
    private var popupHeld = false

    fun holdPopup(on: Boolean) {
        popupHeld = on
        if (on) demoPopup() else endPreview()
    }

    /**
     * Ukazka tahu slova "ahoj" (a - h - o - j; nahled v nastaveni): cara za prstem, prejete klavesy
     * (barva pruchodu, obrys, znak) a po konci jejich doznivani; kazde spusteni tah zopakuje.
     */
    fun demoSwipe() {
        if (touchMode != T_NONE) return
        val pts = ArrayList<Key>()
        for (c in "ahoj") demoKeyFor(c)?.let { pts.add(it) }
        runDemoPath(pts, DEMO_SWIPE_MS, DEMO_SWIPE_STEPS)
    }

    /** Lomena cara pres klavesy `pts`; postupne se oznacuji prejete klavesy a po konci doznivaji. */
    private fun runDemoPath(pts: List<Key>, durationMs: Long, stepCount: Int) {
        if (pts.size < 2) return
        handler.removeCallbacksAndMessages(demoToken)
        for (k in keys) { k.passed = false }
        hoverKey = null
        // Lomena cara pres klavesy slova do bufferu vzorku (stejny jako u skutecneho tahu).
        val segs = pts.size - 1
        for (i in 0 until DEMO_TRAIL_POINTS) {
            val f = i * segs / (DEMO_TRAIL_POINTS - 1f)
            val si = min(segs - 1, f.toInt())
            val t = f - si
            val from = pts[si]
            val to = pts[si + 1]
            val fx = from.vx + from.vw / 2f
            val fy = from.y + from.h / 2f
            samples[i * 3] = fx + (to.vx + to.vw / 2f - fx) * t
            samples[i * 3 + 1] = fy + (to.y + to.h / 2f - fy) * t
            samples[i * 3 + 2] = 0f
        }
        sampleCount = 0
        touchMode = T_NONE
        trailHeld = true
        trailCount = 2
        val start = SystemClock.uptimeMillis()
        for (step in 1..stepCount) {
            val n = max(2, DEMO_TRAIL_POINTS * step / stepCount)
            handler.postAtTime({
                trailCount = n
                if (glowEnabled) {
                    // Klavesa pod hlavou cary je pod prstem, vsechny pred ni jsou prejete.
                    val head = min(pts.size - 1, ((n - 1f) / (DEMO_TRAIL_POINTS - 1f) * (pts.size - 1) + 0.5f).toInt())
                    for (i in 0..head) {
                        val k = pts[i]
                        if (!k.passed) { k.passed = true; k.passAt = SystemClock.uptimeMillis(); k.passTarget = 0f }
                    }
                    hoverKey = pts[head]
                }
                invalidate()
            }, demoToken, start + durationMs * step / stepCount)
        }
        handler.postAtTime({
            trailHeld = false
            val endAt = SystemClock.uptimeMillis()
            trailEndedAt = endAt
            // Po konci tahu prejete klavesy doznivaji, stejne jako po skutecnem tahu.
            val fade = passedTextCol != 0 || outlinePassedCol != 0 || passedKeyCol != 0
            for (k in pts) {
                if (k.passed && fade) k.passedFadeAt = endAt
                k.passed = false
            }
            hoverKey = null
            invalidate()
        }, demoToken, start + durationMs + 1L)
        invalidate()
    }

    /** Uzivatel se dotkl nahledu: zastavit rozehranou ukazku. */
    fun stopDemos() {
        handler.removeCallbacksAndMessages(demoToken)
        if (touchMode == T_NONE) {
            for (k in keys) {
                if (k.pressed) press(k, false)
                k.passed = false
                k.flipAt = 0L
            }
        }
        hoverKey = null
        trailHeld = false
        trailCount = 0
        glowCount = 0
        flyCount = 0
        spotUntil = 0L
        previewKey = null
        hideAltsDemo()
        invalidate()
    }

    /** Zavola se pri kazdem polozeni prstu na nahled v zkusebnim rezimu (nastaveni). */
    var onUserTouch: (() -> Unit)? = null

    /** Ukazka stisku (nahled v nastaveni): za sebou se stisknou a pusti 4 klavesy. */
    fun demoPress(word: String = "ahoj") {
        if (touchMode != T_NONE) return
        handler.removeCallbacksAndMessages(demoToken)
        for (k in keys) press(k, false)
        var step = 0
        for (c in word) {
            val k = demoKeyFor(c) ?: continue
            val at = SystemClock.uptimeMillis() + step * DEMO_PRESS_STEP_MS
            handler.postAtTime({ press(k, true) }, demoToken, at)
            handler.postAtTime({ press(k, false) }, demoToken, at + DEMO_PRESS_STEP_MS * 3 / 5)
            step++
        }
    }

    // --- ukazka nabidky variant dlouheho stisku (editor klavesy v nastaveni)
    private var altsDemo = false

    /**
     * Ukaze nad klavesou nabidku variant presne jako pri dlouhem stisku (staticky, vybrana prvni).
     * Prazdne `alts` nabidku skryje.
     */
    fun showAltsDemo(key: Key, alts: String) {
        if (alts.isEmpty()) {
            hideAltsDemo()
            return
        }
        openPopup(key, alts)
        popupSel = 0
        altsDemo = true
        previewKey = null
        invalidate()
    }

    /** Ukazka listy znaku pri podrzeni (nahled v nastaveni): na ~0,9 s se ukaze nad klavesou s variantami. */
    fun demoAlts() {
        if (touchMode != T_NONE) return
        val k = demoLetter('a')?.takeIf { it.alts.isNotEmpty() } ?: keys.firstOrNull { it.kind == KIND_CHAR && it.alts.isNotEmpty() } ?: return
        handler.removeCallbacksAndMessages(demoToken)
        showAltsDemo(k, k.alts)
        if (!altsHeld) handler.postAtTime({ hideAltsDemo() }, demoToken, SystemClock.uptimeMillis() + DEMO_ALTS_MS)
    }

    /** Lista variant zustane videt, dokud je `on` (vyber barvy listy v nastaveni); po vypnuti zmizi. */
    private var altsHeld = false

    fun holdAlts(on: Boolean) {
        altsHeld = on
        if (on) demoAlts() else hideAltsDemo()
    }

    /**
     * Ukazka pisma (nahled v nastaveni): zaroven bublina nad jednou klavesou a lista variant nad klavesou
     * s napovedou, aby bylo videt pismo klaves, napovedy, bubliny i listy.
     */
    fun demoFont() {
        if (touchMode != T_NONE) return
        val ka = demoLetter('a')?.takeIf { it.alts.isNotEmpty() } ?: keys.firstOrNull { it.kind == KIND_CHAR && it.alts.isNotEmpty() }
        val kb = demoLetter('g') ?: keys.firstOrNull { it.kind == KIND_CHAR && it !== ka }
        handler.removeCallbacksAndMessages(demoToken)
        previewKey = null
        if (ka != null) showAltsDemo(ka, ka.alts)
        if (kb != null) startPreview(kb)
        val end = SystemClock.uptimeMillis() + DEMO_FONT_MS
        handler.postAtTime({ hideAltsDemo(); endPreview() }, demoToken, end)
        invalidate()
    }

    fun hideAltsDemo() {
        if (!altsDemo) return
        altsDemo = false
        popupKey = null
        invalidate()
    }

    /** Je klavesa v nejnizsi rade klaves (spodni rada se mezernikem)? */
    fun isBottomRow(key: Key): Boolean {
        var lowest = 0f
        for (k in keys) if (k.y > lowest) lowest = k.y
        return key.y >= lowest - 0.5f
    }

    /** Horni okraj nabidky variant (souradnice pohledu), kdyz je zobrazena pro `key`; jinak horni okraj klavesy. */
    fun stripTopFor(key: Key): Float = if (altsDemo && popupKey === key) popupTop else key.y

    /** Ukazka neonu po klepnuti (nahled v nastaveni): par pismenovych klaves se rozsviti a doznivaji. */
    fun demoNeon(word: String = "ahoj") {
        val now = SystemClock.uptimeMillis()
        if (tapTextCol != 0 || outlineTapCol != 0) for (c in word) demoKeyFor(c)?.tapAt = now
        if (neonEnabled) {
            if (paintEffect) {
                demoPaint(word)
            } else {
                var n = 0
                for (ch in word) {
                    val k = demoKeyFor(ch)
                    if (k != null && n < 6) {
                        if (glitchEffect) startGlitch(k, now + n * 140L) else if (ghostEffect) { k.neonCol = nextNeon(); k.ghostCol2 = otherNeon(k.neonCol); k.ghostAt = now + n * 140L } else if (orbitEffect) { k.neonCol = nextNeon(); k.orbitStart = random.nextFloat(); k.orbitAt = now + n * 140L } else if (popEffect) { k.neonCol = nextNeon(); k.popSeed = random.nextFloat() * 2f * PI.toFloat(); k.popAt = now + n * 140L } else { k.neonCol = nextNeon(); k.neonAt = now }
                        n++
                    }
                }
            }
        }
        invalidate()
    }

    /**
     * Obarveni klavesy dalsi barvou v poradi na nahodnou dobu 3,0-7,5 s (potom vybledne); klavesa se hleda podle `src`,
     * stejna klavesa se prebarvi. Bez alokace: pevna tabulka, pri zaplneni se prepise nejstarsi polozka.
     */
    private fun paintKey(k: Key, now: Long) {
        var slot = -1
        var free = -1
        var oldest = -1
        for (i in 0 until PAINT_SLOTS) {
            if (paintIdx[i] < 0) {
                if (free < 0) free = i
            } else if (paintSrc[i] == k.src) {
                slot = i
                break
            } else if (oldest < 0 || paintAt[i] < paintAt[oldest]) {
                oldest = i
            }
        }
        if (slot < 0) slot = if (free >= 0) free else oldest
        paintUndoSlot = slot
        paintUndoSrc = paintSrc[slot]
        paintUndoIdx = paintIdx[slot]
        paintUndoAt = paintAt[slot]
        paintUndoHold = paintHold[slot]
        paintUndoNext = paintNext
        paintSrc[slot] = k.src
        paintIdx[slot] = paintNext
        paintAt[slot] = now
        paintHold[slot] = PAINT_MIN_MS + random.nextInt(PAINT_SPAN_MS + 1)
        paintNext = (paintNext + 1) % paintCols.size
    }

    /** Tah misto klepnuti: vrati posledni obarveni. */
    private fun undoPaint(k: Key) {
        val slot = paintUndoSlot
        if (slot < 0 || paintSrc[slot] != k.src || paintIdx[slot] < 0) return
        paintSrc[slot] = paintUndoSrc
        paintIdx[slot] = paintUndoIdx
        paintAt[slot] = paintUndoAt
        paintHold[slot] = paintUndoHold
        paintNext = paintUndoNext
        paintUndoSlot = -1
    }

    /** Ukazka efektu "barvy" (nahled v nastaveni): par pismenovych klaves se obarvi za sebou. */
    fun demoPaint(word: String = "ahoj") {
        if (!paintEffect || (preview && previewTouch)) return
        java.util.Arrays.fill(paintIdx, -1)
        paintNext = 0
        val now = SystemClock.uptimeMillis()
        for (c in word) demoKeyFor(c)?.let { paintKey(it, now) }
        paintUndoSlot = -1
        invalidate()
    }

    /**
     * Intenzita rozsviceni klavesy 0..1 ve stylu "postupne": rozsviti se na prvni pozici pismene ve slove
     * (plynule, zpomalene) a zustane svitit; po prodleve zhasnou vsechna pismena spolecne.
     */
    private fun glowAmount(index: Int, now: Long): Float {
        glowHit = -1
        val elapsed = now - glowStart
        val fadeT = elapsed - ((glowCount - 1) * BLINK_STEP_MS + BLINK_RISE_MS + BLINK_HOLD_MS)
        val fade = if (fadeT > 0L) 1f - fadeT / BLINK_FADE_MS.toFloat() else 1f
        if (fade <= 0f) return 0f
        for (j in 0 until glowCount) {
            if (glowSeq[j] != index) continue
            glowHit = j
            val t = elapsed - j * BLINK_STEP_MS
            if (t <= 0L) return 0f
            val x = min(1f, t / BLINK_RISE_MS.toFloat())
            return (1f - (1f - x) * (1f - x)) * fade
        }
        return 0f
    }

    /** Intenzita neonu po klepnuti 0..1 (a vynulovani po dobehnuti). */
    private fun neonAmount(k: Key, now: Long): Float {
        val at = k.neonAt
        if (at == 0L) return 0f
        if (at == -1L) return 1f
        val a = 1f - (now - at) / NEON_FADE_MS
        if (a <= 0f) {
            k.neonAt = 0L
            return 0f
        }
        return a
    }

    // ------------------------------------------------------------------ diktovani

    /** Hlasitost reci 0..1 - ridi velikost mikrofonu. */
    var voiceLevel = 0f

    /** Text pod mikrofonem ("Posloucham..."). */
    var voiceLabel = ""
        set(value) {
            field = value
            if (dictating) invalidate()
        }
    private var shownLevel = 0f

    /** Diktovani je pozastavene: mikrofon je tlumeny a pod nim je tlacitko Play. */
    var voicePaused = false
        set(value) {
            if (field != value) {
                field = value
                if (dictating) invalidate()
            }
        }

    /** Svisla poloha mikrofonu na obrazovce diktovani (pri pauze vys, aby se pod nej vesel Play). */
    private fun voiceCy(): Float = height * (if (voicePaused) 0.34f else 0.44f)

    private fun voiceBase(): Float = min(width, height) * 0.13f

    private fun playCy(): Float = voiceCy() + voiceBase() * 0.9f * 1.35f + 30f * density

    private fun inPlayButton(x: Float, y: Float): Boolean =
        hypot(x - width / 2f, y - playCy()) <= 30f * density

    /** Velke tlacitko "Znovu mluvit": vpravo uprostred, velke jako tlacitko Odeslat (Enter). */
    private val voiceAgainBox = android.graphics.RectF()
    private fun voiceAgainRect(): android.graphics.RectF {
        val w = 64f * density; val h = 64f * density; val right = width - 6f * density; val cy = height * 0.5f
        voiceAgainBox.set(right - w, cy - h / 2f, right, cy + h / 2f)
        return voiceAgainBox
    }
    private fun inVoiceAgainButton(x: Float, y: Float): Boolean {
        val r = voiceAgainRect()
        return x >= r.left - 8f * density && x <= r.right + 8f * density && y >= r.top - 8f * density && y <= r.bottom + 8f * density
    }

    /** Prekryti behem diktovani: mikrofon v kruhu, ktery roste a zmensuje se s hlasem. */
    private fun drawVoice(canvas: Canvas): Boolean {
        if (!dictating) {
            shownLevel = 0f
            return false
        }
        shownLevel += (voiceLevel.coerceIn(0f, 1f) - shownLevel) * 0.28f
        fill.color = (theme.background and 0x00FFFFFF) or (0xD8 shl 24)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        val cx = width / 2f
        val cy = voiceCy()
        val base = voiceBase()
        val labelY: Float
        if (voicePaused) {
            // Pauza: mikrofon bez pulzovani a ztlumeny, pod nim kulate tlacitko Play.
            val r = base * 0.9f
            fill.color = Theme.blend(theme.accent, theme.background, 0.6f)
            canvas.drawCircle(cx, cy, r, fill)
            stroke.color = Theme.blend(theme.onAccent, theme.background, 0.35f)
            stroke.strokeWidth = 2.2f * density
            drawMic(canvas, cx, cy, r * 0.42f)
            val py = playCy()
            val pr = 26f * density
            fill.color = theme.accent
            canvas.drawCircle(cx, py, pr, fill)
            val s = 13f * density
            path.reset()
            path.moveTo(cx - s * 0.45f, py - s * 0.8f)
            path.lineTo(cx - s * 0.45f, py + s * 0.8f)
            path.lineTo(cx + s * 0.85f, py)
            path.close()
            fill.color = theme.onAccent
            canvas.drawPath(path, fill)
            labelY = py + pr + 24f * density
        } else {
            val r = base * (1f + 0.75f * shownLevel)
            fill.color = (theme.accent and 0x00FFFFFF) or (0x38 shl 24)
            canvas.drawCircle(cx, cy, r * 1.45f, fill)
            fill.color = theme.accent
            canvas.drawCircle(cx, cy, r, fill)
            stroke.color = theme.onAccent
            stroke.strokeWidth = 2.2f * density
            drawMic(canvas, cx, cy, r * 0.42f)
            labelY = cy + base * 2.75f
        }
        if (voiceLabel.isNotEmpty()) {
            textPaint.color = theme.text
            textPaint.textSize = min(rowH * 0.3f, 16f * density * fontScale)
            canvas.drawText(voiceLabel, cx, labelY, textPaint)
        }
        // Tlacitko smazani vlevo nahore.
        val by = 32f * density
        val dx = 32f * density
        fill.color = theme.key
        canvas.drawCircle(dx, by, 20f * density, fill)
        stroke.color = theme.text
        stroke.strokeWidth = 1.7f * density
        stroke.style = Paint.Style.STROKE
        drawBackspaceIcon(canvas, dx + 0.1f * 6f * density, by, 6f * density)
        // Tlacitko zavreni (X) vpravo nahore.
        val bx = width - 32f * density
        fill.color = theme.key
        canvas.drawCircle(bx, by, 20f * density, fill)
        stroke.color = theme.text
        stroke.strokeWidth = 2f * density
        val d = 7f * density
        canvas.drawLine(bx - d, by - d, bx + d, by + d, stroke)
        canvas.drawLine(bx + d, by - d, bx - d, by + d, stroke)
        val ar = voiceAgainRect()
        fill.color = theme.key
        canvas.drawCircle(ar.centerX(), ar.centerY(), ar.width() / 2f, fill)
        // Odeslat: jen zelena sipka doprava (vyplneny trojuhelnik s vykrojem).
        val iconS = 15f * density
        val iconCx = ar.centerX()
        val iconCy = ar.centerY()
        path.reset()
        path.moveTo(iconCx - iconS * 0.8f, iconCy - iconS * 0.8f)
        path.lineTo(iconCx + iconS * 0.9f, iconCy)
        path.lineTo(iconCx - iconS * 0.8f, iconCy + iconS * 0.8f)
        path.lineTo(iconCx - iconS * 0.35f, iconCy)
        path.close()
        fill.color = 0xFF22C55E.toInt()
        canvas.drawPath(path, fill)
        return true
    }

    private fun drawPopup(canvas: Canvas) {
        if (popupKey == null || popupAlts.isEmpty()) return
        val n = popupAlts.size
        val radius = keyRadius * 1.3f
        if (drawShadow) {
            fill.color = theme.keyShadow
            rect.set(popupLeft, popupTop + shadowOffset, popupLeft + popupCell * n, popupTop + rowH + shadowOffset)
            canvas.drawRoundRect(rect, radius, radius, fill)
        }
        rect.set(popupLeft, popupTop, popupLeft + popupCell * n, popupTop + rowH)
        fill.color = altsFill
        canvas.drawRoundRect(rect, radius, radius, fill)
        textPaint.textSize = min(rowH * 0.42f, unitW * 0.55f)
        textPaint.typeface = labelFace
        val baseline = popupTop + rowH / 2f - (textPaint.ascent() + textPaint.descent()) / 2f
        for (i in 0 until n) {
            val cx = popupLeft + popupCell * (i + 0.5f)
            if (i == popupSel) {
                rect.set(popupLeft + popupCell * i + 3 * density, popupTop + 3 * density,
                    popupLeft + popupCell * (i + 1) - 3 * density, popupTop + rowH - 3 * density)
                fill.color = altsSelFill
                canvas.drawRoundRect(rect, radius * 0.75f, radius * 0.75f, fill)
                textPaint.color = altsSelFg
            } else {
                textPaint.color = altsFg
            }
            canvas.drawText(popupAlts[i], cx, baseline, textPaint)
        }
        textPaint.typeface = baseFace
    }

    // ------------------------------------------------------------------ dotyk

    private fun press(k: Key?, down: Boolean) {
        if (k == null || k.pressed == down) return
        k.pressed = down
        val now = SystemClock.uptimeMillis()
        k.changedAt = now
        if (down) {
            // Neon svici po dobu stisku i bez animaci; doznivani po pusteni jen s animacemi.
            if (k.kind == KIND_BACKSPACE) { deleteFillStart = now; deleteKey = k }
            if (k.kind == KIND_ENTER) { enterSlideStart = now; enterSlideKey = k; enterSlideDir = if ((k.vx + k.vw / 2f) < width / 2f) -1f else 1f }
            if (neonEnabled && (k.kind == KIND_CHAR || k.kind == KIND_SPACE)) {
                if (paintEffect) {
                    paintKey(k, now)
                } else if (glitchEffect) {
                    startGlitch(k, now)
                } else if (ghostEffect) {
                    k.neonCol = nextNeon()
                    k.ghostAt = now
                } else if (orbitEffect) {
                    k.neonCol = nextNeon()
                    k.orbitStart = random.nextFloat()
                    k.orbitAt = now
                } else if (popEffect) {
                    k.neonCol = nextNeon()
                    k.popSeed = random.nextFloat() * 2f * PI.toFloat()
                    k.popAt = now
                } else if (plasmaEffect) {
                    k.neonCol = nextNeon()
                    k.plasmaX = 0.15f + random.nextFloat() * 0.7f
                    k.plasmaY = 0.15f + random.nextFloat() * 0.7f
                    k.plasmaAt = now
                } else if (cutEffect) {
                    k.neonCol = nextNeon()
                    k.cutTilt = (random.nextFloat() - 0.5f) * 50f
                    k.cutAt = now
                } else if (slideEffect) {
                    k.neonCol = nextNeon()
                    val slideAng = random.nextFloat() * 2f * PI.toFloat()
                    k.slideC = kotlin.math.cos(slideAng)
                    k.slideS = kotlin.math.sin(slideAng)
                    k.slideAt = now
                } else {
                    k.neonCol = nextNeon()
                    k.neonAt = -1L
                }
            }
            if ((tapTextCol != 0 || outlineTapCol != 0 || tapTextCol2 != 0 || outlineTapCol2 != 0) && (k.kind == KIND_CHAR || k.kind == KIND_SPACE)) {
                // Dve barvy po klepnuti se pri kazdem klepnuti stridaji.
                k.tapTxt = tapTextCol
                k.tapOut = outlineTapCol
                k.tapAt = now
            }
        } else if (k.neonAt == -1L) {
            k.neonAt = if (animate) now else 0L
        }
        invalidate()
    }

    private var voiceDeleting = false
    private val voiceDeleteRepeat = object : Runnable {
        override fun run() {
            if (!dictating || !voiceDeleting) return
            listener?.onVoiceDelete()
            handler.postDelayed(this, 220)
        }
    }

    private fun stopVoiceDelete() {
        voiceDeleting = false
        handler.removeCallbacks(voiceDeleteRepeat)
    }

    private fun inVoiceButton(x: Float, y: Float, cx: Float): Boolean =
        hypot(x - cx, y - 32 * density) <= 34 * density

    private var voiceTouch = false
    private var voicePlayTouch = false
    private var voiceAgainTouch2 = false

    private fun cancelTouch() {
        handler.removeCallbacks(longPress)
        handler.removeCallbacks(repeat)
        stopVoiceDelete()
        endPreview()
        val endAt = SystemClock.uptimeMillis()
        for (k in keys) {
            // Projete klavesy po konci tahu doznivaji (casova znacka je predalokovana v klavese).
            if (k.passed && touchMode == T_SWIPE && (passedTextCol != 0 || outlinePassedCol != 0 || passedKeyCol != 0)) k.passedFadeAt = endAt
            k.passed = false
        }
        hoverKey = null
        if (touchMode == T_SWIPE) {
            trailEndedAt = endAt
            invalidate()
        }
        press(downKey, false)
        press(secondKey, false)
        touchMode = T_NONE
        activeId = -1
        secondId = -1
        downKey = null
        secondKey = null
        pinching = false
        popupKey = null
        sampleCount = 0
    }

    private fun addSample(x: Float, y: Float, t: Long) {
        if (sampleCount >= MAX_SAMPLES) return
        val i = sampleCount * 3
        samples[i] = x
        samples[i + 1] = y
        samples[i + 2] = (t - downTime).toFloat()
        sampleCount++
    }

    @SuppressLint("ClickableViewAccessibility") // klavesnice ma vlastni virtualni uzly pro TalkBack
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (rowEditMode) return rowEditTouch(e)
        if (preview && previewTouch && e.actionMasked == MotionEvent.ACTION_DOWN) {
            stopDemos()
            onUserTouch?.invoke()
        }
        if (preview && !previewTouch) {
            val pick = onKeyPick ?: return false
            if (e.actionMasked == MotionEvent.ACTION_UP) keyAt(e.x, e.y)?.let(pick)
            return true
        }
        if (dictating) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    voiceTouch = true
                    voicePlayTouch = voicePaused && inPlayButton(e.x, e.y)
                    voiceAgainTouch2 = inVoiceAgainButton(e.x, e.y)
                    if (inVoiceButton(e.x, e.y, 32 * density)) {
                        // Smazani: hned jednou, pri drzeni se opakuje.
                        voiceDeleting = true
                        listener?.onVoiceDelete()
                        handler.postDelayed(voiceDeleteRepeat, 450)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    stopVoiceDelete()
                    // prst, ktery diktovani spustil dlouhym stiskem, muze byt stale dole: jen dokoncit gesto
                    if (touchMode != T_NONE) cancelTouch()
                    if (voiceAgainTouch2 && inVoiceAgainButton(e.x, e.y)) listener?.onVoiceSend()
                    else if (voiceTouch && inVoiceButton(e.x, e.y, width - 32 * density)) listener?.onVoiceStop()
                    else if (voicePlayTouch && voicePaused && inPlayButton(e.x, e.y)) listener?.onVoiceResume()
                    voiceTouch = false
                    voicePlayTouch = false
                    voiceAgainTouch2 = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    stopVoiceDelete()
                    cancelTouch()
                    voiceTouch = false
                    voicePlayTouch = false
                    voiceAgainTouch2 = false
                }
            }
            return true
        }
        if (menuKey != null && menuSticky) {
            // Otevrena nabidka: klepnuti na polozku ji spusti, klepnuti mimo nabidku zavre.
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val sel = menuAt(e.x, e.y)
                    if (sel != menuSel) {
                        menuSel = sel
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val sel = menuAt(e.x, e.y)
                    val kind = menuKind
                    closeMenu()
                    if (sel >= 0) listener?.onMenuAction(kind, sel)
                }
                MotionEvent.ACTION_CANCEL -> closeMenu()
            }
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelTouch()
                startPrimary(e.getPointerId(0), e.x, e.y, e.eventTime)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Druhy prst: bud rychle psani dvema palci, nebo pinch.
                if (e.pointerCount == 2 && secondId == -1 && activeId != -1 &&
                    touchMode != T_SWIPE && touchMode != T_POPUP && touchMode != T_CONSUMED) {
                    val i = e.actionIndex
                    handler.removeCallbacks(longPress)
                    handler.removeCallbacks(repeat)
                    secondId = e.getPointerId(i)
                    secondKey = keyAt(e.getX(i), e.getY(i))
                    press(secondKey, true)
                    secondKey?.let { startPreview(it) }
                    secondKey?.let { listener?.onPressFeedback(it) }
                    span0 = span(e)
                    lastSpan = span0
                    pinching = false
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (secondId != -1) {
                    if (e.pointerCount >= 2) {
                        lastSpan = span(e)
                        if (!pinching && RustBridge.settings.gesturePinchResize && span0 > 0f &&
                            abs(lastSpan / span0 - 1f) > 0.18f) {
                            pinching = true
                            press(downKey, false)
                            press(secondKey, false)
                        }
                    }
                } else {
                    val idx = e.findPointerIndex(activeId)
                    if (idx >= 0) movePrimary(e, idx)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                if (secondId != -1 && (id == activeId || id == secondId)) {
                    if (pinching) {
                        finishPinch()
                    } else if (id == activeId) {
                        // Prvni prst nahoru driv: potvrdit jeho klavesu, druhy prst pokracuje jako tap.
                        commitSimple(downKey)
                        press(downKey, false)
                        val idx = e.findPointerIndex(secondId)
                        activeId = secondId
                        downKey = secondKey
                        if (idx >= 0) {
                            downX = e.getX(idx)
                            downY = e.getY(idx)
                        }
                        downTime = e.eventTime
                        sampleCount = 0
                        moved = false
                        longPressFired = false
                        repeatCount = 0
                        touchMode = T_TAP
                        secondId = -1
                        secondKey = null
                    } else {
                        // Druhy prst nahoru driv: zachovat poradi stisku.
                        commitSimple(downKey)
                        commitSimple(secondKey)
                        press(downKey, false)
                        press(secondKey, false)
                        touchMode = T_CONSUMED
                        secondId = -1
                        secondKey = null
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (pinching) finishPinch() else upPrimary(e)
                cancelTouch()
            }
            MotionEvent.ACTION_CANCEL -> cancelTouch()
        }
        return true
    }

    private fun span(e: MotionEvent): Float =
        if (e.pointerCount < 2) 0f else hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))

    private fun finishPinch() {
        if (span0 > 0f && lastSpan > 0f) listener?.onPinchScale(lastSpan / span0)
        pinching = false
        secondId = -1
        press(downKey, false)
        press(secondKey, false)
        touchMode = T_CONSUMED
    }

    /** Obycejne potvrzeni klavesy (pri psani dvema prsty). */
    private fun commitSimple(k: Key?) {
        if (k == null || touchMode == T_CONSUMED) return
        if (touchMode == T_BACKSPACE && repeatCount > 0) return
        listener?.onKeyTap(k)
    }

    private fun startPrimary(id: Int, x: Float, y: Float, time: Long) {
        val k = keyAt(x, y) ?: return
        activeId = id
        downKey = k
        downX = x
        downY = y
        downTime = time
        moved = false
        longPressFired = false
        cursorMoved = false
        consumedDx = 0f
        repeatCount = 0
        sampleCount = 0
        addSample(x, y, time)
        trailCount = 0
        press(k, true)
        startPreview(k)
        listener?.onPressFeedback(k)
        touchMode = when (k.kind) {
            KIND_BACKSPACE -> T_BACKSPACE
            KIND_SPACE -> T_SPACE
            else -> T_TAP
        }
        if (k.kind == KIND_BACKSPACE) handler.postDelayed(repeat, 350)
        val delay = RustBridge.settings.longPressDelayMs.toLong()
        if (k.kind == KIND_CHAR && k.holdMacro != null) {
            handler.postDelayed(longPress, delay)
        } else if (k.alts.isNotEmpty() && k.macro == null) {
            handler.postDelayed(longPress, delay)
        } else if (k.kind == KIND_MODE && (k.code == CODE_SYMBOLS || k.code == CODE_LETTERS)) {
            handler.postDelayed(longPress, maxOf(delay, 300L))
        } else if (k.kind == KIND_ENTER && voiceAllowed && RustBridge.settings.voiceByEnter) {
            handler.postDelayed(longPress, maxOf(delay, 350L))
        } else if (k.kind == KIND_MACRO) {
            // Podrzeni tlacitka maker otevre emoji.
            handler.postDelayed(longPress, maxOf(delay, 300L))
        } else if (k.kind == KIND_SPACE) {
            // Mezernik: delsi prodleva, aby pomalejsi tap neprepnul jazyk omylem.
            handler.postDelayed(longPress, maxOf(2 * delay, 500L))
        }
    }

    private fun movePrimary(e: MotionEvent, idx: Int) {
        val k = downKey ?: return
        // Historicke vzorky: displej 120 Hz + touch 240 Hz -> vyrazne pres 60 vzorku za sekundu.
        for (h in 0 until e.historySize) addSample(e.getHistoricalX(idx, h), e.getHistoricalY(idx, h), e.getHistoricalEventTime(h))
        val x = e.getX(idx)
        val y = e.getY(idx)
        addSample(x, y, e.eventTime)
        val dx = x - downX
        val dy = y - downY
        val slop = 0.4f * unitW
        when (touchMode) {
            T_MENU -> {
                val sel = menuAt(x, y)
                if (sel != menuSel) {
                    menuSel = sel
                    invalidate()
                }
            }
            T_POPUP -> {
                val sel = ((x - popupLeft) / popupCell).toInt().coerceIn(0, popupAlts.size - 1)
                if (sel != popupSel) {
                    popupSel = sel
                    invalidate()
                }
            }
            T_TAP -> if (hypot(dx, dy) > slop) {
                moved = true
                handler.removeCallbacks(longPress)
                if (swipeAllowed && k.isLetter && mode == MODE_LETTERS && RustBridge.settings.swipeEnabled) {
                    touchMode = T_SWIPE
                    k.neonAt = 0L // pri tahu se klavesa nerozsveti
                    clearGlitch(k)
                    k.tapAt = 0L
                    undoPaint(k)
                    endPreview()
                    if (glowEnabled) {
                        k.passed = true; k.passAt = SystemClock.uptimeMillis(); k.passTarget = 0f
                        hoverKey = k
                        keyAt(x, y)?.let { if (it.isLetter) { it.passed = true; it.passAt = SystemClock.uptimeMillis(); it.passTarget = 0f; hoverKey = it } }
                    }
                    trailCount = sampleCount
                    invalidate()
                }
            }
            T_SWIPE -> {
                if (glowEnabled) keyAt(x, y)?.let { if (it.isLetter) { it.passed = true; it.passAt = SystemClock.uptimeMillis(); it.passTarget = 0f; hoverKey = it } }
                trailCount = sampleCount
                invalidate()
            }
            T_SPACE -> if (abs(dx) > slop) {
                moved = true
                handler.removeCallbacks(longPress)
                // Pomaly tah = posun po znacich; rychly svih (do 220 ms) se vyhodnoti az po zvednuti.
                val step = 0.4f * unitW
                if (RustBridge.settings.gestureSpaceCursor && e.eventTime - downTime > 220 && abs(dx - consumedDx) >= step) {
                    val steps = RustBridge.cursorSteps(dx - consumedDx, unitW)
                    if (steps != 0) {
                        consumedDx += steps * step
                        cursorMoved = true
                        listener?.onCursorSteps(steps)
                    }
                }
            }
            T_BACKSPACE -> if (dx < -slop) {
                moved = true
                handler.removeCallbacks(repeat)
            }
        }
    }

    private fun upPrimary(e: MotionEvent) {
        val k = downKey ?: return
        handler.removeCallbacks(longPress)
        handler.removeCallbacks(repeat)
        val idx = max(0, e.findPointerIndex(activeId))
        val dx = e.getX(idx) - downX
        val dy = e.getY(idx) - downY
        val dt = (e.eventTime - downTime).toFloat()
        val l = listener ?: return
        when (touchMode) {
            T_POPUP -> popupAlts.getOrNull(popupSel)?.let { l.onAltText(it) }
            T_MENU -> if (menuSel >= 0) {
                val sel = menuSel
                val kind = menuKind
                closeMenu()
                l.onMenuAction(kind, sel)
            } else {
                menuSticky = true // prst zvednut bez vyberu: nabidka ceka na klepnuti
            }
            T_BACKSPACE -> {
                val g = if (moved) RustBridge.gesture(k.kind, dx, dy, dt, unitW, rowH) else RustBridge.ACTION_NONE
                if (g != RustBridge.ACTION_NONE) l.onGestureAction(g) else if (repeatCount == 0) l.onKeyTap(k)
            }
            T_SPACE -> if (!cursorMoved && !longPressFired) {
                val g = if (moved) RustBridge.gesture(k.kind, dx, dy, dt, unitW, rowH) else RustBridge.ACTION_NONE
                if (g != RustBridge.ACTION_NONE) l.onGestureAction(g) else l.onKeyTap(k)
            }
            T_TAP, T_SWIPE -> {
                val g = if (moved) RustBridge.gesture(k.kind, dx, dy, dt, unitW, rowH) else RustBridge.ACTION_NONE
                if (g != RustBridge.ACTION_NONE) {
                    l.onGestureAction(g)
                } else if (touchMode == T_SWIPE) {
                    l.onBeforeSwipe()
                    val words = RustBridge.swipe(samples, sampleCount * 3, shiftState != SHIFT_OFF, preview)
                    // Prazdny vysledek = prilis kratky / pomaly tah -> bereme jako tap.
                    if (words.isEmpty()) l.onKeyTap(k) else l.onSwipeWords(words)
                } else if (!longPressFired) {
                    l.onKeyTap(k)
                }
            }
        }
    }

    private fun onLongPress() {
        val k = downKey ?: return
        if (touchMode == T_TAP && !moved && k.kind == KIND_ENTER) {
            // Podrzeny Enter = diktovani; poslech trva, dokud je klavesa drzena.
            longPressFired = true
            touchMode = T_VOICE
            listener?.onPressFeedback(k)
            listener?.onVoiceStart()
            return
        }
        if (touchMode == T_SPACE && !moved) {
            longPressFired = true
            touchMode = T_CONSUMED
            press(k, false)
            listener?.onSpaceLongPress()
            return
        }
        if (touchMode == T_TAP && !moved && k.kind == KIND_MODE && (k.code == CODE_SYMBOLS || k.code == CODE_LETTERS)) {
            openMenu(k, quickLabels, quickIcons, MENU_KIND_QUICK, sticky = false)
            longPressFired = true
            touchMode = T_MENU
            previewKey = null
            listener?.onPressFeedback(k)
            invalidate()
            return
        }
        if (touchMode == T_TAP && !moved && k.kind == KIND_MACRO) {
            longPressFired = true
            touchMode = T_CONSUMED
            press(k, false)
            listener?.onPressFeedback(k)
            listener?.onKeyHold(k)
            return
        }
        if (touchMode == T_TAP && !moved && k.kind == KIND_CHAR && k.holdMacro != null) {
            // Makro na podrzeni: misto nabidky variant se vlozi makro.
            longPressFired = true
            touchMode = T_CONSUMED
            press(k, false)
            listener?.onPressFeedback(k)
            listener?.onKeyHold(k)
            return
        }
        if (touchMode != T_TAP || k.alts.isEmpty() || k.macro != null) return
        openPopup(k, k.alts)
        popupSel = 0
        previewKey = null
        longPressFired = true
        touchMode = T_POPUP
        listener?.onPressFeedback(k)
        invalidate()
    }

    /** Pripravi nabidku variant (`alts` = znaky, kazdy jedna varianta) nad klavesou `k`. */
    private fun openPopup(k: Key, altsText: String) {
        val alts = ArrayList<String>(8)
        var i = 0
        val upper = shiftState != SHIFT_OFF && mode == MODE_LETTERS
        while (i < altsText.length) {
            val cp = altsText.codePointAt(i)
            val s = String(Character.toChars(cp))
            alts.add(if (upper) s.uppercase() else s)
            i += Character.charCount(cp)
        }
        popupKey = k
        popupAlts = alts
        popupCell = max(unitW, 44f * density)
        val total = popupCell * alts.size
        popupLeft = (k.vx + k.vw / 2f - total / 2f).coerceIn(0f, max(0f, width - total))
        popupTop = max(-overflowTop.toFloat(), k.y - rowH)
    }

    // ------------------------------------------------------------------ S Pen / hover / TalkBack

    private val accessibility = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    private var hoveredIndex = -1

    /**
     * Hover (S Pen nad displejem, mys) nesmi nic stisknout. Vyjimka je TalkBack
     * (explore-by-touch): hover oznamuje klavesy a zvednuti prstu klavesu potvrdi.
     */
    override fun dispatchHoverEvent(e: MotionEvent): Boolean {
        val am = accessibility
        if (preview || am == null || !am.isEnabled || !am.isTouchExplorationEnabled) return false
        val k = keyAt(e.x, e.y)
        val index = if (k == null) -1 else keys.indexOf(k)
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> if (index != hoveredIndex) {
                if (hoveredIndex >= 0) sendVirtualEvent(hoveredIndex, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
                hoveredIndex = index
                if (index >= 0) sendVirtualEvent(index, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                if (hoveredIndex >= 0) {
                    sendVirtualEvent(hoveredIndex, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
                    keys.getOrNull(hoveredIndex)?.let {
                        listener?.onPressFeedback(it)
                        listener?.onKeyTap(it)
                    }
                }
                hoveredIndex = -1
            }
        }
        return true
    }

    @Suppress("DEPRECATION") // nahrada obtain() existuje az od API 30, minSdk je 24
    private fun sendVirtualEvent(index: Int, type: Int) {
        val k = keys.getOrNull(index) ?: return
        val event = AccessibilityEvent.obtain(type)
        event.packageName = context.packageName
        event.className = "android.widget.Button"
        event.contentDescription = k.desc
        event.setSource(this, index)
        parent?.requestSendAccessibilityEvent(this, event)
    }

    private val nodeProvider = object : AccessibilityNodeProvider() {
        private val tmp = IntArray(2)

        @Suppress("DEPRECATION") // nahrada obtain() existuje az od API 30, minSdk je 24
        override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
            if (virtualViewId == HOST_VIEW_ID) {
                val info = AccessibilityNodeInfo.obtain(this@KeyboardView)
                onInitializeAccessibilityNodeInfo(info)
                for (i in keys.indices) info.addChild(this@KeyboardView, i)
                return info
            }
            val k = keys.getOrNull(virtualViewId) ?: return null
            val info = AccessibilityNodeInfo.obtain()
            info.setSource(this@KeyboardView, virtualViewId)
            info.setParent(this@KeyboardView)
            info.packageName = context.packageName
            info.className = "android.widget.Button"
            info.contentDescription = k.desc
            val r = Rect(k.x.toInt(), k.y.toInt(), (k.x + k.w).toInt(), (k.y + k.h).toInt())
            @Suppress("DEPRECATION")
            info.setBoundsInParent(r)
            getLocationOnScreen(tmp)
            r.offset(tmp[0], tmp[1])
            info.setBoundsInScreen(r)
            info.isEnabled = true
            info.isVisibleToUser = true
            info.isClickable = true
            info.isFocusable = true
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
            return info
        }

        override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            val k = keys.getOrNull(virtualViewId) ?: return false
            return when (action) {
                AccessibilityNodeInfo.ACTION_CLICK -> {
                    listener?.onPressFeedback(k)
                    listener?.onKeyTap(k)
                    true
                }
                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
                    sendVirtualEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
                    true
                }
                else -> false
            }
        }
    }

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = nodeProvider

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(wake)
        cancelTouch()
        // Zadne zbytkove casovace (opakovani mazani, dlouhy stisk) po odpojeni pohledu.
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }
}
