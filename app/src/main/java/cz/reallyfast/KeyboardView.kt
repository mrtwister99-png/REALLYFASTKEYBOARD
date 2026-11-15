package cz.reallyfast

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
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
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

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

        /** Zacatek doznivani neonu po klepnuti; 0 = nesviti, -1 = klavesa je drzena (naplno). */
        var neonAt = 0L
        var changedAt = 0L
        val isLetter: Boolean get() = kind == KIND_CHAR && code in 'a'.code..'z'.code

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
        private const val GLOW_RISE_MS = 50L
        private const val GLOW_FALL_MS = 350L
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
                if (!value) stopVoiceDelete()
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
    private var neon = Theme.parse(RustBridge.settings.neonColor, 0xFF59ACFF.toInt())
    private var neonText = textOn(neon)
    private var glowColor = Theme.parse(RustBridge.settings.glowColor, 0xFFFFD60A.toInt())
    private var glowText = textOn(glowColor)

    private fun textOn(c: Int) = if (Theme.luminance(c) > 0.6f) 0xFF15151A.toInt() else WHITE

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
    // Poloha male napovedy: x 0 = vlevo .. 1 = vpravo, y 0 = nahore .. 1 = dole.
    private var hintX = 1f
    private var hintY = 0f
    private var hintOverride = false

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
        hintX = x.coerceIn(0f, 1f)
        hintY = y.coerceIn(0f, 1f)
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
        for (k in keys) if (k.hint.isNotEmpty()) anyHint = true
        if (hintFocus && !anyHint) hintSample = keys.firstOrNull { it.kind == KIND_CHAR }
    }

    private fun applyAppearance() {
        val s = RustBridge.settings
        animate = s.animations
        drawShadow = s.keyShadow
        drawHints = s.keyHints
        drawTrailEnabled = s.swipeTrail
        glowEnabled = s.swipeGlow
        neonEnabled = s.keyGlow
        showPreview = s.keyPopup
        keyRadius = s.keyRadiusDp * density
        fontScale = resources.configuration.fontScale
        if (!hintOverride) {
            hintX = s.keyHintX
            hintY = s.keyHintY
        }
        neon = Theme.parse(s.neonColor, 0xFF59ACFF.toInt())
        neonText = textOn(neon)
        glowColor = Theme.parse(s.glowColor, 0xFFFFD60A.toInt())
        glowText = textOn(glowColor)
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
    private val glowSeq = IntArray(GLOW_MAX_LETTERS)
    private var glowCount = 0
    private var glowStart = 0L
    private var glowStep = 100L
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
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val rect = RectF()
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
        closeMenu()
        val parsed = ArrayList<Key>(48)
        try {
            val root = JSONObject(RustBridge.layout(width, height, mode, preview))
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
        updateHintInfo()
        invalidate()
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
        fill.color = theme.background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        val now = SystemClock.uptimeMillis()
        var animating = false
        val padX = 2.5f * density
        val padY = 4f * density
        val radius = keyRadius
        val upper = shiftState != SHIFT_OFF && mode == MODE_LETTERS
        val labelSize = min(rowH * 0.40f, unitW * 0.52f)

        var glowActive = false
        if (glowCount > 0) {
            if (now - glowStart >= (glowCount - 1) * glowStep + GLOW_RISE_MS + GLOW_FALL_MS) glowCount = 0 else glowActive = true
        }
        if (glowActive) animating = true

        for (ki in keys.indices) {
            val k = keys[ki]
            val p = if (k.changedAt == 0L) 0f else pressAmount(k, now)
            if (animate && k.changedAt != 0L && now - k.changedAt < RELEASE_ANIM_MS) animating = true

            val hintText = if (k === hintSample) "1" else k.hint
            val dimKey = hintFocus && !(k.kind == KIND_CHAR && hintText.isNotEmpty())
            val marked = k.kind == KIND_CHAR && k.src == markedSrc
            val accentKey = marked || k.kind == KIND_ENTER || (k.kind == KIND_SHIFT && shiftState == SHIFT_CAPS)
            val base = when {
                accentKey -> theme.accent
                k.kind == KIND_CHAR || k.kind == KIND_SPACE -> theme.key
                else -> theme.keySpecial
            }
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
            var keyColor = if (p > 0f) Theme.blend(base, if (accentKey) theme.text else theme.keyPressed, if (accentKey) 0.25f * p else p) else base
            if (k.passed) keyColor = Theme.blend(keyColor, theme.accent, 0.55f)
            val glow = if (glowActive) glowAmount(ki, now) else 0f
            if (glow > 0f) keyColor = Theme.blend(keyColor, glowColor, glow)
            val neonA = if (k.neonAt != 0L) neonAmount(k, now) else 0f
            if (neonA > 0f) {
                if (k.neonAt > 0L) animating = true
                keyColor = Theme.blend(keyColor, neon, neonA)
                // Svetelny okraj: o 3 dp vetsi obrys pod klavesou, jedna plocha bez blur.
                val g = 3f * density
                fill.color = (neon and 0x00FFFFFF) or ((90f * neonA).toInt().coerceIn(0, 255) shl 24)
                rect.set(cx - hw - g, cy - hh - g, cx + hw + g, cy + hh + g)
                canvas.drawRoundRect(rect, radius + g, radius + g, fill)
            }
            if (dimKey) keyColor = Theme.blend(keyColor, theme.background, 0.7f)
            fill.color = keyColor
            rect.set(cx - hw, cy - hh, cx + hw, cy + hh)
            canvas.drawRoundRect(rect, radius, radius, fill)

            var fg = if (accentKey) theme.onAccent else theme.text
            if (k.passed) fg = Theme.blend(fg, theme.onAccent, 0.55f)
            if (glow > 0f) fg = Theme.blend(fg, glowText, glow)
            if (neonA > 0f) fg = Theme.blend(fg, neonText, neonA)
            if (dimKey) fg = Theme.blend(fg, theme.background, 0.7f)
            when (k.kind) {
                KIND_CHAR -> {
                    val hinted = (drawHints || hintFocus) && hintText.isNotEmpty()
                    if (hinted) {
                        textPaint.color = theme.hint
                        textPaint.textSize = labelSize * 0.46f
                        // Zaobleny roh ukroji d = r * (1 - 1/sqrt2); posun dovnitr jen blizko rohu.
                        val d = min(radius, min(hw, hh)) * 0.2929f * abs(2f * hintX - 1f) * abs(2f * hintY - 1f)
                        val xL = cx - (hw - labelSize * 0.34f - d)
                        val xR = cx + (hw - labelSize * 0.34f - d)
                        val yT = cy - hh + labelSize * 0.52f + d
                        val yB = cy + hh - labelSize * 0.22f - d
                        canvas.drawText(hintText, xL + (xR - xL) * hintX, yT + (yB - yT) * hintY, textPaint)
                    }
                    textPaint.color = fg
                    textPaint.textSize = labelSize
                    val n = labelChars(k.label, upper)
                    val shift = if (!hinted) 0f else labelSize * 0.06f * (1f - 2f * hintY)
                    canvas.drawText(charBuf, 0, n, cx, cy + shift - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
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
                else -> drawIcon(canvas, k.kind, cx, cy, min(k.vw, k.h) * 0.2f, fg)
            }
        }

        if (drawTrail(canvas, now)) animating = true
        if (drawPreview(canvas, now)) animating = true
        drawPopup(canvas)
        drawMenu(canvas)
        if (drawVoice(canvas)) animating = true
        if (animating) postInvalidateOnAnimation()
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
        val fade = if (touchMode == T_SWIPE) 1f else {
            if (!animate) return false
            1f - (now - trailEndedAt) / TRAIL_FADE_MS
        }
        if (fade <= 0f) {
            trailCount = 0
            return false
        }
        val first = max(0, trailCount - TRAIL_POINTS)
        val span = (trailCount - first).toFloat()
        val rgb = theme.accent and 0x00FFFFFF
        stroke.style = Paint.Style.STROKE
        var px = samples[first * 3]
        var py = samples[first * 3 + 1]
        for (i in first + 1 until trailCount) {
            val f = (i - first) / span
            val x = samples[i * 3]
            val y = samples[i * 3 + 1]
            stroke.color = rgb or ((200f * f * fade).toInt().coerceIn(0, 255) shl 24)
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
        fill.color = (theme.popup and 0x00FFFFFF) or (alpha shl 24)
        rect.set(cx - hw, mid - hh, cx + hw, mid + hh)
        canvas.drawRoundRect(rect, keyRadius * 1.3f, keyRadius * 1.3f, fill)
        textPaint.color = (theme.text and 0x00FFFFFF) or (alpha shl 24)
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
     * Postupne zablikani pismen slova: prvni pismeno, pak druhe... az posledni, jako by se slovo
     * psalo po pismenech. Diakritika se prevede na zakladni pismeno (š bliká na klavese s).
     */
    fun glowWord(word: String) {
        if (!glowEnabled || word.isEmpty()) return
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
        glowCount = n
        glowStep = min(100L, 1200L / n)
        glowStart = SystemClock.uptimeMillis()
        invalidate()
    }

    /** Ukazka neonu po klepnuti (nahled v nastaveni): par pismenovych klaves se rozsviti a doznivaji. */
    fun demoNeon() {
        if (!neonEnabled) return
        val now = SystemClock.uptimeMillis()
        var n = 0
        for (k in keys) {
            if (k.isLetter && n < 4 && k.code.toChar() in "ahoj") {
                k.neonAt = now
                n++
            }
        }
        if (n > 0) invalidate()
    }

    /** Intenzita zablikani klavesy 0..1: nejvyssi ze vsech pozic, na kterych se pismeno ve slove objevi. */
    private fun glowAmount(index: Int, now: Long): Float {
        var best = 0f
        val elapsed = now - glowStart
        for (j in 0 until glowCount) {
            if (glowSeq[j] != index) continue
            val t = elapsed - j * glowStep
            val a = when {
                t < 0L -> 0f
                t < GLOW_RISE_MS -> t / GLOW_RISE_MS.toFloat()
                t < GLOW_RISE_MS + GLOW_FALL_MS -> 1f - (t - GLOW_RISE_MS) / GLOW_FALL_MS.toFloat()
                else -> 0f
            }
            if (a > best) best = a
        }
        return best
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
        val cy = height * 0.44f
        val base = min(width, height) * 0.13f
        val r = base * (1f + 0.75f * shownLevel)
        fill.color = (theme.accent and 0x00FFFFFF) or (0x38 shl 24)
        canvas.drawCircle(cx, cy, r * 1.45f, fill)
        fill.color = theme.accent
        canvas.drawCircle(cx, cy, r, fill)
        stroke.color = theme.onAccent
        stroke.strokeWidth = 2.2f * density
        drawMic(canvas, cx, cy, r * 0.42f)
        if (voiceLabel.isNotEmpty()) {
            textPaint.color = theme.text
            textPaint.textSize = min(rowH * 0.3f, 16f * density * fontScale)
            canvas.drawText(voiceLabel, cx, cy + base * 2.75f, textPaint)
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
        fill.color = theme.popup
        canvas.drawRoundRect(rect, radius, radius, fill)
        textPaint.textSize = min(rowH * 0.42f, unitW * 0.55f)
        val baseline = popupTop + rowH / 2f - (textPaint.ascent() + textPaint.descent()) / 2f
        for (i in 0 until n) {
            val cx = popupLeft + popupCell * (i + 0.5f)
            if (i == popupSel) {
                rect.set(popupLeft + popupCell * i + 3 * density, popupTop + 3 * density,
                    popupLeft + popupCell * (i + 1) - 3 * density, popupTop + rowH - 3 * density)
                fill.color = theme.accent
                canvas.drawRoundRect(rect, radius * 0.75f, radius * 0.75f, fill)
                textPaint.color = theme.onAccent
            } else {
                textPaint.color = theme.text
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
            if (neonEnabled && animate && k.kind == KIND_CHAR) k.neonAt = -1L
        } else if (k.neonAt == -1L) {
            k.neonAt = now
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

    private fun cancelTouch() {
        handler.removeCallbacks(longPress)
        handler.removeCallbacks(repeat)
        stopVoiceDelete()
        endPreview()
        for (k in keys) k.passed = false
        if (touchMode == T_SWIPE) {
            trailEndedAt = SystemClock.uptimeMillis()
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
                    voiceTouch = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    stopVoiceDelete()
                    cancelTouch()
                    voiceTouch = false
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
        if (k.alts.isNotEmpty()) {
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
                    endPreview()
                    if (glowEnabled) {
                        k.passed = true
                        keyAt(x, y)?.let { if (it.isLetter) it.passed = true }
                    }
                    trailCount = sampleCount
                    invalidate()
                }
            }
            T_SWIPE -> {
                if (glowEnabled) keyAt(x, y)?.let { if (it.isLetter) it.passed = true }
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
        if (touchMode != T_TAP || k.alts.isEmpty()) return
        val alts = ArrayList<String>(8)
        var i = 0
        val upper = shiftState != SHIFT_OFF && mode == MODE_LETTERS
        while (i < k.alts.length) {
            val cp = k.alts.codePointAt(i)
            val s = String(Character.toChars(cp))
            alts.add(if (upper) s.uppercase() else s)
            i += Character.charCount(cp)
        }
        popupKey = k
        popupAlts = alts
        popupSel = 0
        popupCell = max(unitW, 44f * density)
        val total = popupCell * alts.size
        popupLeft = (k.vx + k.vw / 2f - total / 2f).coerceIn(0f, max(0f, width - total))
        popupTop = max(-overflowTop.toFloat(), k.y - rowH)
        previewKey = null
        longPressFired = true
        touchMode = T_POPUP
        listener?.onPressFeedback(k)
        invalidate()
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
