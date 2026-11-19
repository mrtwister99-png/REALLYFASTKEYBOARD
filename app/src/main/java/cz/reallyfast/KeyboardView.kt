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
        /** Barva znaku / obrysu tohoto klepnuti (stridani dvou barev); 0 = zakladni barva. Nastavuje se pri stisku. */
        var tapTxt = 0
        var tapOut = 0
        var changedAt = 0L

        /** Cas posledniho klepnuti (pro barvu znaku po klepnuti); 0 = zadne. */
        var tapAt = 0L

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
        /** Polozka nabidky po dlouhem stisku ?123: 0 = emoji, 1 = schranka, 2 = nastaveni. */
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
    }

    companion object {
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

        const val MENU_EMOJI = 0
        const val MENU_CLIPBOARD = 1
        const val MENU_SETTINGS = 2

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
        private const val DEMO_TRAIL_MS = 700L
        private const val DEMO_TRAIL_STEPS = 12
        private const val DEMO_TRAIL_POINTS = 36
        private const val DEMO_ALTS_MS = 900L
        private const val DEMO_PRESS_STEP_MS = 230L
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
    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var glowFlyStyle = false
    // Efekt "barvy": pevna tabulka obarvenych klaves podle `src` (index barvy, cas obarveni, doba drzeni); jen v pameti.
    private var paintEffect = false
    private val paintCols = IntArray(3)
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
    private var neonFlip = false
    // Obrys klaves (0 = bez obrysu / bez zmeny); prechod obrysu se stavi jen pri zmene (updateMetrics).
    private var outlineOn = false
    private var outlineCol = 0
    private var outlineCol2 = 0
    private var outlineRainbow = false
    private var outlineTapCol = 0
    private var outlineTapCol2 = 0
    private var tapTextCol2 = 0
    private var tapSecond = false
    private var specialOutline = true
    private var glowCol2 = 0
    private var trailCol2 = 0
    private var outlineHoverCol = 0
    private var outlinePassedCol = 0
    private var outlineW = 2f * density
    private var outlineShader: LinearGradient? = null
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var keyShader: LinearGradient? = null
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
        val mx = labelSize * 0.34f
        val spanX = max(1f, 2f * (hw - mx))
        val yT = -hh + labelSize * 0.52f
        val yB = hh - labelSize * 0.22f
        val spanY = max(1f, yB - yT)
        out[0] = (-(2f * padX + mx) / spanX).coerceIn(HINT_MIN, 0f)
        out[1] = (1f + (2f * padX + mx) / spanX).coerceIn(1f, HINT_MAX)
        out[2] = (-(2f * padY + labelSize * 0.52f) / spanY).coerceIn(HINT_MIN, 0f)
        out[3] = (1f + (2f * padY + labelSize * 0.22f) / spanY).coerceIn(1f, HINT_MAX)
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

    private fun weightFace(weight: Int): Typeface =
        if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, weight, false)
        else Typeface.create("sans-serif", if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)

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
        glowFillStyle = s.glowStyle == "fill"
        glowFlyStyle = s.glowStyle == "fly"
        glitchEffect = s.tapEffect == "glitch"
        ghostEffect = s.tapEffect == "ghost"
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
        // Dva fonty znaku (pismena / cislice) se staveji jednou tady; v onDraw se jen prepina.
        labelFace = weightFace(s.labelWeight)
        digitFace = weightFace(s.digitWeight)
        tapTextCol = Theme.parse(s.tapTextColor, 0)
        paintCols[0] = Theme.parse(s.paintColor1, 0xFF0080FF.toInt())
        paintCols[1] = Theme.parse(s.paintColor2, 0xFFFF5100.toInt())
        paintCols[2] = Theme.parse(s.paintColor3, 0xFF6A02E8.toInt())
        digitTextCol = Theme.parse(s.digitTextColor, 0)
        digitKeyCol = Theme.parse(s.digitKeyColor, 0)
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
     * Znak se zvetsuje o key_text_percent, ale vzdy zustane uvnitr klavesy.
     */
    private fun updateMetrics() {
        padX = gapDp * 0.5f * density
        padY = rowGapDp * 0.5f * density
        if (rowH > 1f) padY = min(padY, rowH * 0.3f)
        val base = min(rowH * 0.40f, unitW * 0.52f) * textPercent / 100f
        val innerH = max(1f, rowH - 2f * padY)
        val innerW = max(1f, unitW - 2f * padX)
        labelSize = min(base, min(innerH * 0.70f, innerW * 0.85f))
        // Prechod klavesy je vystredeny na stred klavesy (onDraw posune platno), pozadi pokryva cely pohled.
        val gh = max(1f, rowH - 2f * padY)
        keyShader = when {
            keyRainbow -> LinearGradient(0f, -gh / 2f, 0f, gh / 2f, Theme.rainbowFrom(theme.key), null, Shader.TileMode.CLAMP)
            keyCol2 != 0 -> LinearGradient(0f, -gh / 2f, 0f, gh / 2f, theme.key, keyCol2, Shader.TileMode.CLAMP)
            else -> null
        }
        outlineShader = when {
            outlineCol == 0 -> null
            outlineRainbow -> LinearGradient(0f, -gh / 2f, 0f, gh / 2f, Theme.rainbowFrom(outlineCol), null, Shader.TileMode.CLAMP)
            outlineCol2 != 0 -> LinearGradient(0f, -gh / 2f, 0f, gh / 2f, outlineCol, outlineCol2, Shader.TileMode.CLAMP)
            else -> null
        }
        // Prechod znaku: od horniho okraje pismene k dolnimu (vyska ~0,72 velikosti), vystredeny na nulu jako prechod klavesy.
        val gl = labelSize * 0.36f
        textShader = when {
            gl <= 0f -> null
            textRainbow -> LinearGradient(0f, -gl, 0f, gl, Theme.rainbowFrom(theme.text), null, Shader.TileMode.CLAMP)
            textCol2 != 0 -> LinearGradient(0f, -gl, 0f, gl, theme.text, textCol2, Shader.TileMode.CLAMP)
            else -> null
        }
        bgShader = when {
            height <= 0 -> null
            bgRainbow -> LinearGradient(0f, 0f, 0f, height.toFloat(), Theme.rainbowFrom(theme.background), null, Shader.TileMode.CLAMP)
            bgCol2 != 0 -> LinearGradient(0f, 0f, 0f, height.toFloat(), theme.background, bgCol2, Shader.TileMode.CLAMP)
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
        if (neon2Rainbow) return rainbow(random.nextFloat())
        if (neon2 == 0) return neon
        neonFlip = !neonFlip
        return if (neonFlip) neon else neon2
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
        context.getString(R.string.menu_emoji),
        context.getString(R.string.clipboard),
        context.getString(R.string.menu_settings),
    )
    private val quickIcons = intArrayOf(ICON_DIAMOND, ICON_CLIPBOARD, ICON_GEAR)
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
            val root = JSONObject(RustBridge.layout(width, height, mode, preview && !previewTouch))
            numberRow = root.optBoolean("number_row", false)
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
        keys = parsed
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
            if (ft >= FLY_MS + FLY_HOLD_MS + FLY_FADE_MS) {
                flyCount = 0
            } else {
                animating = true
                flyLabel = if (ft < FLY_MS + FLY_HOLD_MS) 0f else (ft - FLY_MS - FLY_HOLD_MS) / FLY_FADE_MS.toFloat()
            }
        }

        for (ki in keys.indices) {
            val k = keys[ki]
            val p = if (k.changedAt == 0L) 0f else pressAmount(k, now)
            if (animate && k.changedAt != 0L && now - k.changedAt < RELEASE_ANIM_MS) animating = true

            val hintText = if (k === hintSample) "1" else k.hint
            val dimKey = hintFocus && !(k.kind == KIND_CHAR && hintText.isNotEmpty())
            val marked = k.kind == KIND_CHAR && k.src == markedSrc
            val accentKey = marked || k.kind == KIND_ENTER || (k.kind == KIND_SHIFT && shiftState == SHIFT_CAPS)
            var paintA = 0f
            var pIdx = -1
            if (paintLive && k.kind == KIND_CHAR) {
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
            val digitOwn = k.isDigit && digitKeyCol != 0
            val base = when {
                accentKey -> theme.accent
                digitOwn -> digitKeyCol
                k.kind == KIND_CHAR || k.kind == KIND_SPACE -> theme.key
                else -> theme.keySpecial
            }
            val shader = keyShader
            gradKey = shader != null && !accentKey && !digitOwn && (k.kind == KIND_CHAR || k.kind == KIND_SPACE)
            ovA = 0f
            ovR = 0f
            ovG = 0f
            ovB = 0f
            val scale = 1f - 0.04f * p
            val cx = k.vx + k.vw / 2f
            val cy = k.y + k.h / 2f
            val hw = (k.vw / 2f - padX) * scale
            val hh = (k.h / 2f - padY) * scale

            if (drawShadow && p < 1f) {
                // Plochy "stin" = tmavsi obrys posunuty o 1.5 dp dolu (zadny blur -> zadna cena navic).
                fill.color = theme.keyShadow
                rect.set(cx - hw, cy - hh + shadowOffset * (1f - p), cx + hw, cy + hh + shadowOffset * (1f - p))
                canvas.drawRoundRect(rect, radius, radius, fill)
            }
            var keyColor = base
            if (paintA > 0f && !accentKey) keyColor = tint(keyColor, paintCols[pIdx], paintA)
            if (p > 0f) keyColor = tint(keyColor, if (accentKey) theme.text else theme.keyPressed, if (accentKey) 0.25f * p else p)
            if (k.passed) keyColor = tint(keyColor, theme.accent, 0.55f)
            val glow = if (glowActive && !glowFill) glowAmount(ki, now) else 0f
            val fillSide = if (glowActive && glowFill) glowSide[ki] else -1
            // Duha: v postupnem stylu podle poradi pismene ve slove, ve vybarveni podle vodorovne polohy klavesy.
            val gCol = when {
                glowRainbow && glow > 0f -> rainbow(if (glowCount > 1) glowHit / (glowCount - 1f) else 0f)
                glowRainbow && fillSide >= 0 -> rainbow(cx / max(1f, width.toFloat()))
                glowRainbow -> glowColor
                // Druha barva: pismena slova plynule prechazeji z barvy 1 do barvy 2 (podle poradi / polohy).
                glowCol2 != 0 && glow > 0f -> Theme.blend(glowColor, glowCol2, if (glowCount > 1) glowHit / (glowCount - 1f) else 0f)
                glowCol2 != 0 && fillSide >= 0 -> Theme.blend(glowColor, glowCol2, (cx / max(1f, width.toFloat())).coerceIn(0f, 1f))
                else -> glowColor
            }
            val gText = if (glowRainbow || glowCol2 != 0) textOn(gCol) else glowText
            if (glow > 0f) keyColor = tint(keyColor, gCol, glow)
            val neonA = if (k.neonAt != 0L) neonAmount(k, now) else 0f
            val nc = if (k.neonCol != 0) k.neonCol else neon
            if (neonA > 0f) {
                if (k.neonAt > 0L) animating = true
                keyColor = tint(keyColor, nc, neonA)
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
                        ghostPaint.color = (nc and 0x00FFFFFF) or (a shl 24)
                        rect.set(cx - hw - grow, cy - hh - grow, cx + hw + grow, cy + hh + grow)
                        canvas.drawRoundRect(rect, radius + grow, radius + grow, ghostPaint)
                    }
                }
            }
            if (dimKey) keyColor = tint(keyColor, theme.background, 0.7f)
            if (gradKey) {
                // Prechod z barvy klavesy do druhe barvy: platno se posune na stred klavesy, kde je shader vystredeny.
                canvas.save()
                canvas.translate(0f, cy)
                rect.set(cx - hw, -hh, cx + hw, hh)
                fill.color = WHITE
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
            if (outlineOn) {
                // Obrys: zakladni barva (pripadne prechod), po projeti, po klepnuti, pod prstem (posledni vyhrava).
                val special = specialOutline && (k.kind == KIND_SHIFT || k.kind == KIND_MODE || k.kind == KIND_BACKSPACE)
                var oc = if (special) theme.accent else outlineCol
                if (k.passed && outlinePassedCol != 0) {
                    oc = outlinePassedCol
                } else if (passedFade > 0f && outlinePassedCol != 0) {
                    oc = mixArgb(oc, outlinePassedCol, passedFade)
                }
                if (tapF > 0f && outlineTapCol != 0) oc = mixArgb(oc, if (k.tapOut != 0) k.tapOut else outlineTapCol, tapF)
                val over = (k.pressed && !k.passed) || (k.passed && k === hoverKey)
                if (over && outlineHoverCol != 0) oc = outlineHoverCol
                if (oc != 0) {
                    val ins = outlineW / 2f
                    val r = max(0f, radius - ins)
                    outlinePaint.strokeWidth = outlineW
                    val os = outlineShader
                    if (os != null && oc == outlineCol && !dimKey) {
                        // Prechod obrysu: shader je vystredeny na nulu, platno se posune na stred klavesy.
                        canvas.save()
                        canvas.translate(0f, cy)
                        rect.set(cx - hw + ins, -hh + ins, cx + hw - ins, hh - ins)
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
            if (tapF > 0f && tapTextCol != 0) {
                val ttc = if (k.tapTxt != 0) k.tapTxt else tapTextCol
                fg = if (tapF >= 1f) ttc else Theme.blend(fg, ttc, tapF)
            }
            if (dimKey) fg = Theme.blend(fg, theme.background, 0.7f)
            val face = if (k.macro != null) faceBitmaps[k.macroFace] else null
            when (k.kind) {
                KIND_CHAR -> if (face != null) {
                    drawMacroImage(canvas, face, cx, cy, hw, hh, dimKey, 0.85f)
                } else {
                    val hinted = (drawHints || hintFocus) && hintText.isNotEmpty()
                    textPaint.color = fg
                    textPaint.typeface = if (k.isDigit) digitFace else labelFace
                    if (flyCount > 0 && flyLabel < 1f && isFlying(ki)) {
                        textPaint.color = (fg and 0x00FFFFFF) or ((255f * flyLabel).toInt().coerceIn(0, 255) shl 24)
                    }
                    textPaint.textSize = labelSize
                    // Prechod barvy znaku jen kdyz zadny docasny efekt nemeni barvu znaku.
                    val grad = textShader != null && fg == baseFg && !accentKey && !(k.isDigit && digitTextCol != 0) &&
                        !(flyCount > 0 && flyLabel < 1f && isFlying(ki))
                    val n = labelChars(k.label, upper)
                    val shift = if (!hinted) 0f else labelSize * 0.06f * (1f - 2f * hintY.coerceIn(0f, 1f))
                    // Poloha znaku: 0,5 / 0,5 = uprostred; jinak se stred pohybuje tam, kde se znak jeste vejde do klavesy.
                    val offX = if (labelX == 0.5f) 0f else (labelX - 0.5f) * 2f * max(0f, hw - labelSize * 0.3f - 2f * density)
                    val offY = if (labelY == 0.5f) 0f else (labelY - 0.5f) * 2f * max(0f, hh - labelSize * 0.36f - 2f * density)
                    if (grad) {
                        // Shader je vystredeny na nulu: platno se posune na stred znaku.
                        canvas.save()
                        canvas.translate(0f, cy + offY + shift)
                        textPaint.shader = textShader
                        canvas.drawText(charBuf, 0, n, cx + offX, -(textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
                        textPaint.shader = null
                        canvas.restore()
                    } else {
                        canvas.drawText(charBuf, 0, n, cx + offX, cy + offY + shift - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
                    }
                    textPaint.typeface = baseFace
                }
                KIND_SPACE -> {
                    textPaint.color = theme.textDim
                    textPaint.textSize = min(rowH * 0.24f, 13f * fontScale * density)
                    canvas.drawText(k.label, cx, cy - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
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
                else -> drawIcon(canvas, k.kind, cx, cy, min(k.vw, k.h) * 0.2f, fg)
            }
        }

        // Napovedy ve druhem pruchodu: napoveda za okrajem klavesy nesmi zakryt sousedni klavesa kreslena pozdeji.
        if (drawHints || hintFocus) {
            for (ki in keys.indices) {
                val k = keys[ki]
                if (k.kind != KIND_CHAR || k.macro != null || k.holdMacro != null) continue
                val hintText = if (k === hintSample) "1" else k.hint
                if (hintText.isEmpty()) continue
                drawHint(canvas, k, hintText, if (k.changedAt == 0L) 0f else pressAmount(k, now), radius)
            }
        }
        // Makro na podrzeni: obrazek makra na pozici napovedy (i kdyz jsou napovedy vypnute).
        for (k in keys) {
            if (k.kind != KIND_CHAR || k.macro != null || k.holdMacro == null) continue
            drawHint(canvas, k, "", if (k.changedAt == 0L) 0f else pressAmount(k, now), radius, faceBitmaps[k.macroFace])
        }
        if (drawGlitch(canvas, now)) animating = true
        if (drawTrail(canvas, now)) animating = true
        if (drawPreview(canvas, now)) animating = true
        drawPopup(canvas)
        drawMenu(canvas)
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
    private fun drawHint(canvas: Canvas, k: Key, text: String, p: Float, radius: Float, bitmap: Bitmap? = null) {
        val scale = 1f - 0.04f * p
        val cx = k.vx + k.vw / 2f
        val cy = k.y + k.h / 2f
        val hw = (k.vw / 2f - padX) * scale
        val hh = (k.h / 2f - padY) * scale
        textPaint.color = hintColor
        textPaint.textSize = labelSize * 0.46f * hintScale
        // Zaobleny roh ukroji d = r * (1 - 1/sqrt2); posun dovnitr jen blizko rohu.
        val fx = min(1f, abs(2f * hintX - 1f))
        val fy = min(1f, abs(2f * hintY - 1f))
        val d = min(radius, min(hw, hh)) * 0.2929f * fx * fy
        val xL = cx - (hw - labelSize * 0.34f - d)
        val xR = cx + (hw - labelSize * 0.34f - d)
        val yT = cy - hh + labelSize * 0.52f + d
        val yB = cy + hh - labelSize * 0.22f - d
        val x = (xL + (xR - xL) * hintX).coerceIn(k.vx - padX, k.vx + k.vw + padX)
        val y = (yT + (yB - yT) * hintY).coerceIn(k.y - padY, k.y + k.h + padY)
        if (text.isEmpty() && bitmap != null) {
            // Makro na podrzeni: maly obrazek makra na miste napovedy (hybe se spolu s ni).
            val bh = labelSize * 0.5f * hintScale // stejne velke jako znak napovedy
            val bw = bh * bitmap.width / bitmap.height
            val by = y - bh * 0.35f
            rect.set(x - bw / 2f, by - bh / 2f, x + bw / 2f, by + bh / 2f)
            bitmapPaint.alpha = 255
            canvas.drawBitmap(bitmap, null, rect, bitmapPaint)
            return
        }
        canvas.drawText(text, x, y, textPaint)
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
        if (t >= FLY_MS + FLY_HOLD_MS + FLY_FADE_MS) return
        val f = min(1f, t / FLY_MS.toFloat())
        val e = 1f - (1f - f) * (1f - f)
        val fade = if (t < FLY_MS + FLY_HOLD_MS) 1f else 1f - (t - FLY_MS - FLY_HOLD_MS) / FLY_FADE_MS.toFloat()
        val alpha = (255f * fade).toInt().coerceIn(0, 255)
        // Faze po dopadu: rychle zvetseni + probliknuti, pak pomaly navrat na 100 %.
        var pop = 0f
        if (t > FLY_MS) {
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
                val c = Theme.blend(glowColor, glowCol2, if (flyCount > 1) j / (flyCount - 1f) else 0f)
                val b = if (pop > 0f) Theme.blend(c, flyFlash, pop) else c
                flyFill.color = (b and 0x00FFFFFF) or (alpha shl 24)
            }
            canvas.drawText(flyChars, j, 1, x, y, flyOutline)
            canvas.drawText(flyChars, j, 1, x, y, flyFill)
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
            val base = java.text.Normalizer.normalize(flyChars[j].toString().lowercase(), java.text.Normalizer.Form.NFD)[0]
            var ki = -1
            for (i in keys.indices) {
                if (keys[i].kind == KIND_CHAR && keys[i].code == base.code) {
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
            stroke.strokeWidth = (1.5f + 5f * f) * density
            canvas.drawLine(px, py, x, y, stroke)
            px = x
            py = y
        }
        fill.color = rgb or ((255f * fade).toInt().coerceIn(0, 255) shl 24)
        canvas.drawCircle(px, py, 4.5f * density, fill)
        return touchMode != T_SWIPE
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
        canvas.drawText(charBuf, 0, n, cx, mid - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
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
     * skladalo po pismenech; pak zhasne cele naraz. Diakritika se prevede na zakladni pismeno (š bliká na klavese s).
     */
    fun glowWord(word: String) {
        if (!glowEnabled || word.isEmpty()) return
        if (glowFlyStyle) {
            startFly(word)
            return
        }
        val base = java.text.Normalizer.normalize(word.lowercase(), java.text.Normalizer.Form.NFD)
        var n = 0
        for (ch in base) {
            if (ch !in 'a'..'z' || n >= glowSeq.size) continue
            for (i in keys.indices) {
                val k = keys[i]
                if (k.isLetter && k.code == ch.code) {
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

    /** Ukazka bubliny nad klavesou (nahled v nastaveni): na ~0,7 s se objevi nad pismenem. */
    fun demoPopup() {
        if (preview && previewTouch) return
        val k = demoLetter('g') ?: keys.firstOrNull { it.kind == KIND_CHAR } ?: return
        handler.removeCallbacksAndMessages(demoToken)
        previewKey = null
        startPreview(k)
        handler.postAtTime({ endPreview() }, demoToken, SystemClock.uptimeMillis() + DEMO_POPUP_MS)
        invalidate()
    }

    /** Ukazka stopy za prstem (nahled v nastaveni): na ~0,7 s se nakresli cara pres par pismen a pak vybledne. */
    fun demoTrail() {
        if (preview && previewTouch) return
        val a = demoLetter('a') ?: return
        val b = demoLetter('h') ?: return
        val c = demoLetter('o') ?: return
        handler.removeCallbacksAndMessages(demoToken)
        // Lomena cara a -> h -> o do bufferu vzorku (stejny jako u skutecneho tahu).
        val half = DEMO_TRAIL_POINTS / 2
        for (i in 0 until DEMO_TRAIL_POINTS) {
            val from = if (i < half) a else b
            val to = if (i < half) b else c
            val t = (if (i < half) i else i - half) / (half - 1f)
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
        for (step in 1..DEMO_TRAIL_STEPS) {
            val n = max(2, DEMO_TRAIL_POINTS * step / DEMO_TRAIL_STEPS)
            handler.postAtTime({ trailCount = n; invalidate() }, demoToken, start + DEMO_TRAIL_MS * step / DEMO_TRAIL_STEPS)
        }
        handler.postAtTime({
            trailHeld = false
            trailEndedAt = SystemClock.uptimeMillis()
            invalidate()
        }, demoToken, start + DEMO_TRAIL_MS + 1L)
        invalidate()
    }

    /** Ukazka stisku (nahled v nastaveni): za sebou se stisknou a pusti 4 klavesy. */
    fun demoPress() {
        if (preview && previewTouch) return
        handler.removeCallbacksAndMessages(demoToken)
        for (k in keys) press(k, false)
        var step = 0
        for (c in "ahoj") {
            val k = demoLetter(c) ?: continue
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
        if (preview && previewTouch) return
        val k = demoLetter('a')?.takeIf { it.alts.isNotEmpty() } ?: keys.firstOrNull { it.kind == KIND_CHAR && it.alts.isNotEmpty() } ?: return
        handler.removeCallbacksAndMessages(demoToken)
        showAltsDemo(k, k.alts)
        handler.postAtTime({ hideAltsDemo() }, demoToken, SystemClock.uptimeMillis() + DEMO_ALTS_MS)
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

    /** Ukazka barvy po projeti tahem (nahled v nastaveni): par pismen vybledne z barvy po projeti. */
    fun demoPassed() {
        val now = SystemClock.uptimeMillis()
        for (c in "aho") demoLetter(c)?.passedFadeAt = now
        invalidate()
    }

    /** Ukazka neonu po klepnuti (nahled v nastaveni): par pismenovych klaves se rozsviti a doznivaji. */
    fun demoNeon() {
        val now = SystemClock.uptimeMillis()
        if (tapTextCol != 0 || outlineTapCol != 0) for (c in "ahoj") demoLetter(c)?.tapAt = now
        if (neonEnabled) {
            if (paintEffect) {
                demoPaint()
            } else {
                var n = 0
                for (k in keys) {
                    if (k.isLetter && n < 4 && k.code.toChar() in "ahoj") {
                        if (glitchEffect) startGlitch(k, now + n * 140L) else if (ghostEffect) { k.neonCol = nextNeon(); k.ghostAt = now + n * 140L } else { k.neonCol = nextNeon(); k.neonAt = now }
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
    fun demoPaint() {
        if (!paintEffect || (preview && previewTouch)) return
        java.util.Arrays.fill(paintIdx, -1)
        paintNext = 0
        val now = SystemClock.uptimeMillis()
        for (c in "ahoj") demoLetter(c)?.let { paintKey(it, now) }
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

    /** Text pod mikrofonem ("Poslouchám…"). */
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
    }

    // ------------------------------------------------------------------ dotyk

    private fun press(k: Key?, down: Boolean) {
        if (k == null || k.pressed == down) return
        k.pressed = down
        val now = SystemClock.uptimeMillis()
        k.changedAt = now
        if (down) {
            // Neon svici po dobu stisku i bez animaci; doznivani po pusteni jen s animacemi.
            if (neonEnabled && k.kind == KIND_CHAR) {
                if (paintEffect) {
                    paintKey(k, now)
                } else if (glitchEffect) {
                    startGlitch(k, now)
                } else if (ghostEffect) {
                    k.neonCol = nextNeon()
                    k.ghostAt = now
                } else {
                    k.neonCol = nextNeon()
                    k.neonAt = -1L
                }
            }
            if ((tapTextCol != 0 || outlineTapCol != 0) && k.kind == KIND_CHAR) {
                // Dve barvy po klepnuti se pri kazdem klepnuti stridaji.
                tapSecond = !tapSecond
                k.tapTxt = if (tapTextCol2 != 0 && tapSecond) tapTextCol2 else tapTextCol
                k.tapOut = if (outlineTapCol2 != 0 && tapSecond) outlineTapCol2 else outlineTapCol
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

    private fun cancelTouch() {
        handler.removeCallbacks(longPress)
        handler.removeCallbacks(repeat)
        stopVoiceDelete()
        endPreview()
        val endAt = SystemClock.uptimeMillis()
        for (k in keys) {
            // Projete klavesy po konci tahu doznivaji (casova znacka je predalokovana v klavese).
            if (k.passed && touchMode == T_SWIPE && (passedTextCol != 0 || outlinePassedCol != 0)) k.passedFadeAt = endAt
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
                    if (voiceTouch && inVoiceButton(e.x, e.y, width - 32 * density)) listener?.onVoiceStop()
                    else if (voicePlayTouch && voicePaused && inPlayButton(e.x, e.y)) listener?.onVoiceResume()
                    voiceTouch = false
                    voicePlayTouch = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    stopVoiceDelete()
                    cancelTouch()
                    voiceTouch = false
                    voicePlayTouch = false
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
        } else if (k.kind == KIND_ENTER && voiceAllowed && RustBridge.settings.voiceInput) {
            handler.postDelayed(longPress, maxOf(delay, 350L))
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
                        k.passed = true
                        hoverKey = k
                        keyAt(x, y)?.let { if (it.isLetter) { it.passed = true; hoverKey = it } }
                    }
                    trailCount = sampleCount
                    invalidate()
                }
            }
            T_SWIPE -> {
                if (glowEnabled) keyAt(x, y)?.let { if (it.isLetter) { it.passed = true; hoverKey = it } }
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
                    val words = RustBridge.swipe(samples, sampleCount * 3, shiftState != SHIFT_OFF)
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
        cancelTouch()
        super.onDetachedFromWindow()
    }
}
