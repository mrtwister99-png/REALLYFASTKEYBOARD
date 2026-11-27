package cz.reallyfast

import android.graphics.Color
import android.util.SparseIntArray
import org.json.JSONObject
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Pohled na nastaveni (JSON ze settings.json, ktery vlastni a validuje Rust).
 * Kotlin hodnoty jen cte; zmeny jdou vzdy pres RustBridge.setSettings().
 */
class Settings(val json: JSONObject) {
    val language: String get() = json.optString("language", "cs+en")
    val layout: String get() = json.optString("layout", "qwertz")
    val swipeEnabled: Boolean get() = json.optBoolean("swipe_enabled", true)
    val vibrationMs: Int get() = json.optInt("vibration_ms", 10)
    val soundEnabled: Boolean get() = json.optBoolean("sound_enabled", false)
    val keyHeightDp: Int get() = json.optInt("key_height_dp", 56)
    val keyWidthDp: Int get() = json.optInt("key_width_dp", 0).let { if (it == 0) 0 else it.coerceIn(24, 48) }
    val keyFont: String get() = json.optString("key_font", "system")
    val trailWidthDp: Int get() = json.optInt("trail_width_dp", 4).coerceIn(1, 12)
    val theme: String get() = json.optString("theme", "dark")
    val accentColor: String get() = json.optString("accent_color", "#0080FF")
    val showNumberRow: Boolean get() = json.optBoolean("show_number_row", true)
    val showSuggestions: Boolean get() = json.optBoolean("show_suggestions", true)
    val autoCapitalize: Boolean get() = json.optBoolean("auto_capitalize", true)
    val doubleSpaceToPeriod: Boolean get() = json.optBoolean("double_space_to_period", true)
    val autoDiacritics: Boolean get() = json.optBoolean("auto_diacritics", true)
    val longPressDelayMs: Int get() = json.optInt("long_press_delay_ms", 250)
    val customBackground: String get() = json.optString("custom_background", "#111113")
    val customKey: String get() = json.optString("custom_key", "#26262B")
    val customText: String get() = json.optString("custom_text", "#F4F4F5")
    val gestureSpaceCursor: Boolean get() = json.optBoolean("gesture_space_cursor", true)
    val gesturePinchResize: Boolean get() = json.optBoolean("gesture_pinch_resize", true)
    val clipboardEnabled: Boolean get() = json.optBoolean("clipboard_enabled", true)

    val voiceInput: Boolean get() = json.optBoolean("voice_input", true)

    // Vzhled
    val keyPopup: Boolean get() = json.optBoolean("key_popup", true)
    val keyHints: Boolean get() = json.optBoolean("key_hints", true)
    val keyShadow: Boolean get() = json.optBoolean("key_shadow", true)
    val keyRadiusDp: Int get() = json.optInt("key_radius_dp", 10)
    val animations: Boolean get() = json.optBoolean("animations", true)
    val swipeTrail: Boolean get() = json.optBoolean("swipe_trail", true)
    /** Vzhled stopy: "line" (cara) nebo "fire" (horici cara se stopami, ktere dohori). */
    val trailStyle: String get() = json.optString("trail_style", "line")
    val swipeGlow: Boolean get() = json.optBoolean("swipe_glow", true)
    val keyGlow: Boolean get() = json.optBoolean("key_glow", true)
    // Poloha napovedy: 0..1 = uvnitr klavesy, -1..2 = az za okraj (viz KeyboardView.drawHint).
    val keyHintX: Float get() = json.optDouble("key_hint_x", 1.0).toFloat().coerceIn(-1f, 2f)
    val keyHintY: Float get() = json.optDouble("key_hint_y", 0.0).toFloat().coerceIn(-1f, 2f)
    /** Prazdny retezec = automaticka barva napovedy podle motivu. */
    val hintColor: String get() = json.optString("hint_color", "")
    val hintPercent: Int get() = json.optInt("hint_percent", 100).coerceIn(60, 180)
    /** Efekt po klepnuti: "neon", "glitch", "paint", "ghost", "orbit" nebo "pop". */
    val tapEffect: String get() = json.optString("tap_effect", "neon")
    val neonColor: String get() = json.optString("neon_color", "#0080FF")

    /** Druha barva efektu po klepnuti: prazdny retezec = jen jedna barva, "rainbow" = nahodny odstin. */
    val neonColor2: String get() = json.optString("neon_color2", "")
    val glowColor: String get() = json.optString("glow_color", "#0080FF")
    val glowStyle: String get() = json.optString("glow_style", "neon")
    val keyGapDp: Int get() = json.optInt("key_gap_dp", 5).coerceIn(0, 10)
    val rowGapDp: Int
        get() {
            val v = json.optInt("row_gap_dp", -1)
            return (if (v < 0) (keyGapDp * 1.6f).roundToInt() else v).coerceIn(2, 16)
        }
    /** Prazdny retezec = automaticka barva podle motivu. */
    val popupColor: String get() = json.optString("popup_color", "")
    val altsColor: String get() = json.optString("alts_color", "")
    val numberDivider: Boolean get() = json.optBoolean("number_divider", true)
    val keyTextPercent: Int get() = json.optInt("key_text_percent", 100).coerceIn(70, 200)
    /** Barvy cislic a druhe barvy klaves / pozadi: prazdny retezec = automaticky / vypnuto. */
    val trailColor: String get() = json.optString("trail_color", "")
    val textColor2: String get() = json.optString("text_color2", "")
    val digitTextColor: String get() = json.optString("digit_text_color", "")
    val digitKeyColor: String get() = json.optString("digit_key_color", "")
    val keyColor2: String get() = json.optString("key_color2", "")
    val digitKeyColor2: String get() = json.optString("digit_key_color2", "")
    val digitTextColor2: String get() = json.optString("digit_text_color2", "")
    val bgGradDir: String get() = json.optString("bg_grad_dir", "top")
    val keyGradDir: String get() = json.optString("key_grad_dir", "top")
    val digitKeyGradDir: String get() = json.optString("digit_key_grad_dir", "top")
    val textGradDir: String get() = json.optString("text_grad_dir", "top")
    val digitTextGradDir: String get() = json.optString("digit_text_grad_dir", "top")
    val outlineGradDir: String get() = json.optString("outline_grad_dir", "top")
    val backgroundColor2: String get() = json.optString("background_color2", "")
    val paintColor1: String get() = json.optString("paint_color1", "#0080FF")
    val paintColor2: String get() = json.optString("paint_color2", "#FF5100")
    val paintColor3: String get() = json.optString("paint_color3", "#6A02E8")
    /** Poloha hlavniho znaku na klavese: 0..1, 0,5 = uprostred. */
    val keyLabelX: Float get() = json.optDouble("key_label_x", 0.5).toFloat().coerceIn(0f, 1f)
    val keyLabelY: Float get() = json.optDouble("key_label_y", 0.5).toFloat().coerceIn(0f, 1f)
    /** Prazdny retezec = automaticka barva znaku pri prejeti tahem / vypnuta barva znaku po klepnuti. */
    val passedTextColor: String get() = json.optString("passed_text_color", "")
    val passedKeyColor: String get() = json.optString("passed_key_color", "")
    val tapTextColor: String get() = json.optString("tap_text_color", "")
    val hoverTextColor: String get() = json.optString("hover_text_color", "")
    /** Tloustka pisma znaku a cislic na klavesach: 100..900 po 100 (400 = normalni). */
    val labelWeight: Int get() = (json.optInt("label_weight", 400) / 100 * 100).coerceIn(100, 900)
    val digitWeight: Int get() = (json.optInt("digit_weight", 400) / 100 * 100).coerceIn(100, 900)

    /** Obrys klaves: prazdny retezec = bez obrysu / bez zmeny barvy obrysu; druha barva muze byt "rainbow". */
    val outlineColor: String get() = json.optString("outline_color", "")
    val outlineColor2: String get() = json.optString("outline_color2", "")
    val outlineTapColor: String get() = json.optString("outline_tap_color", "")
    val outlineHoverColor: String get() = json.optString("outline_hover_color", "")
    val outlinePassedColor: String get() = json.optString("outline_passed_color", "")
    val outlineWidthDp: Int get() = json.optInt("outline_width_dp", 2).coerceIn(1, 6)
    val charOutline: Boolean get() = json.optBoolean("char_outline", false)
    val charOutlineColor: String get() = json.optString("char_outline_color", "")

    /** Obrys specialnich klaves (Shift, ?123, Backspace) v barve akcentu. */
    val specialOutline: Boolean get() = json.optBoolean("special_outline", true)

    /** Druhe barvy stavu: konec stopy, konec prechodu pismen po tahu, druha barva znaku / obrysu po klepnuti. */
    val trailColor2: String get() = json.optString("trail_color2", "")
    val glowColor2: String get() = json.optString("glow_color2", "")
    val tapTextColor2: String get() = json.optString("tap_text_color2", "")
    val outlineTapColor2: String get() = json.optString("outline_tap_color2", "")

    /** Makra prirazena klavesam: puvodni kod pozice (`Key.src`) -> id makra. Sestavi se jednou pro kazde nastaveni. */
    val keyMacros: SparseIntArray by lazy { intMap("key_macros") }

    /** Makra na podrzeni klavesy (klavesa si drzi znak i vzhled): stejny tvar jako `keyMacros`. */
    val keyMacrosHold: SparseIntArray by lazy { intMap("key_macros_hold") }

    /** Obrazek makra: id makra -> 0 (trol), 1 (chocho), 2 (dollo) nebo 3 (maskot); chybejici = 0. */
    val macroFaces: SparseIntArray by lazy {
        val out = SparseIntArray()
        val o = json.optJSONObject("macro_faces")
        if (o != null) for (k in o.keys()) k.toIntOrNull()?.let { out.put(it, o.optInt(k, 0)) }
        out
    }

    private fun intMap(key: String, allowZero: Boolean = false): SparseIntArray {
        val out = SparseIntArray()
        val o = json.optJSONObject(key) ?: return out
        for (name in o.keys()) {
            if (name.isEmpty()) continue
            val v = o.optInt(name, 0)
            if (v > 0 || (allowZero && v == 0)) out.put(name.codePointAt(0), v)
        }
        return out
    }

    /** Kopie k uprave (SettingsActivity). */
    fun edit(): JSONObject = JSONObject(json.toString())
}

/**
 * Vyska plochy klaves v px: radky * key_height_dp, nejvyse 50 % obrazovky (na sirku 58 %).
 * Pevny bezpecnostni strop; ulozena hodnota `keyboard_height_percent` se uz nepouziva.
 * Pouziva klavesnice i zivy nahled v nastaveni, aby ukazovaly totez.
 */
fun keyboardKeysHeight(res: android.content.res.Resources, s: Settings, barHeightPx: Int): Int {
    val dm = res.displayMetrics
    val rows = if (s.showNumberRow) 5 else 4
    val landscape = res.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val percent = if (landscape) 58 else 50
    // Radek = vyska klavesy + mezera mezi radami (vztazena k vychozim 8 dp): mensi mezera zmensi celou klavesnici.
    val byKeys = rows * (s.keyHeightDp + s.rowGapDp - 8) * dm.density
    val cap = dm.heightPixels * percent / 100f - barHeightPx
    val minimum = rows * (if (landscape) 32f else 40f) * dm.density
    return maxOf(minOf(byKeys, cap), minimum).toInt()
}

/**
 * Barvy klavesnice odvozene z nastaveni. Zadne gradienty: ploche tony, jedna accent barva,
 * hloubku dela jen jemny tmavsi obrys pod klavesou.
 */
class Theme(
    val background: Int,
    val key: Int,
    val keySpecial: Int,
    val keyPressed: Int,
    val keyShadow: Int,
    val popup: Int,
    val text: Int,
    val textDim: Int,
    val hint: Int,
    val divider: Int,
    val accent: Int,
    val onAccent: Int,
    val isLight: Boolean,
    /** Druha barva pozadi (prechod dolu); 0 = vypnuto. */
    val background2: Int = 0,
) {
    /** Barva pozadi dole (u prechodu druha barva): pod klavesnici a v navigacni liste. */
    val backgroundBottom: Int get() = if (background2 != 0) background2 else background

    /** Text nad klavesnici (lista navrhu, radek nahledu): tmavy nebo svetly podle vetsiho kontrastu s pozadim. */
    val barText: Int get() = if (contrast(BAR_DARK, background) >= contrast(BAR_LIGHT, background)) BAR_DARK else BAR_LIGHT

    /** Tlumeny text a ikony nad klavesnici. */
    val barDim: Int get() = blend(barText, background, 0.4f)

    /** Zvyrazneni nad klavesnici: accent, jen kdyz je na pozadi citelny (kontrast >= 3), jinak barText. */
    val barAccent: Int get() = if (contrast(accent, background) >= 3f) accent else barText

    companion object {
        private const val BAR_DARK = 0xFF111113.toInt()
        private const val BAR_LIGHT = 0xFFF5F5F7.toInt()

        private fun channel(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }

        /** Relativni jas podle WCAG (0..1). */
        fun relLuminance(c: Int): Float =
            (0.2126 * channel(Color.red(c)) + 0.7152 * channel(Color.green(c)) + 0.0722 * channel(Color.blue(c))).toFloat()

        /** Kontrast dvou barev podle WCAG (1..21). */
        fun contrast(a: Int, b: Int): Float {
            val la = relLuminance(a)
            val lb = relLuminance(b)
            return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
        }

        /** Zvlastni hodnota barvy: duha (prechod pres vsechny odstiny) misto jedne barvy. */
        const val RAINBOW = "rainbow"

        /** Odstiny duhy shora dolu (cervena .. fialova). */
        val RAINBOW_COLORS = intArrayOf(
            0xFFFF3B30.toInt(), 0xFFFF9500.toInt(), 0xFFFFCC00.toInt(), 0xFF34C759.toInt(),
            0xFF32ADE6.toInt(), 0xFF007AFF.toInt(), 0xFFAF52DE.toInt(),
        )

        /** Prechod z barvy `first` do duhy (pro shadery; vola se jen pri zmene nastaveni, ne v onDraw). */
        fun rainbowFrom(first: Int): IntArray = IntArray(RAINBOW_COLORS.size + 1) { if (it == 0) first else RAINBOW_COLORS[it - 1] }

        fun parse(hex: String, fallback: Int): Int = try {
            Color.parseColor(hex)
        } catch (_: Throwable) {
            fallback
        }

        fun blend(a: Int, b: Int, t: Float): Int {
            val u = 1f - t
            return Color.argb(
                255,
                (Color.red(a) * u + Color.red(b) * t).toInt(),
                (Color.green(a) * u + Color.green(b) * t).toInt(),
                (Color.blue(a) * u + Color.blue(b) * t).toInt(),
            )
        }

        /** Automaticka barva bubliny a listy znaku: svetlejsi odstin tmave klavesy, tmavsi odstin svetle. */
        fun autoPopup(key: Int): Int =
            if (luminance(key) < 0.5f) blend(key, 0xFFFFFFFF.toInt(), 0.22f) else blend(key, 0xFF000000.toInt(), 0.18f)

        fun luminance(c: Int): Float =
            (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f

        /** Barvy z konkretnich hodnot (pouziva i nahled hotovych stylu v nastaveni). */
        fun of(bg: Int, key: Int, text: Int, accent: Int, special: Int? = null, bg2: Int = 0): Theme {
            val light = luminance(bg) > 0.5f
            val black = 0xFF000000.toInt()
            return Theme(
                background = bg,
                key = key,
                keySpecial = special ?: blend(key, bg, 0.55f),
                keyPressed = blend(key, text, if (light) 0.16f else 0.22f),
                keyShadow = blend(bg, black, if (light) 0.16f else 0.55f),
                popup = blend(key, text, if (light) 0.04f else 0.12f),
                text = text,
                textDim = blend(text, bg, 0.45f),
                hint = blend(text, key, 0.62f),
                divider = blend(bg, text, 0.12f),
                accent = accent,
                onAccent = if (luminance(accent) > 0.55f) 0xFF111113.toInt() else 0xFFFFFFFF.toInt(),
                isLight = light,
                background2 = bg2,
            )
        }

        // Puvodni pojmenovana temata mela rucne ladenou barvu specialnich klaves; vlastni tema se
        // stejnymi barvami ji dostane taky, aby se po prechodu na "vzdy vlastni barvy" nic nezmenilo.
        private fun legacySpecial(bg: Int, key: Int, text: Int): Int? = when {
            bg == 0xFFE6E8EC.toInt() && key == 0xFFFFFFFF.toInt() && text == 0xFF15151A.toInt() -> 0xFFCBCFD8.toInt()
            bg == 0xFF000000.toInt() && key == 0xFF17171A.toInt() && text == 0xFFF5F5F7.toInt() -> 0xFF0D0D0F.toInt()
            bg == 0xFF101012.toInt() && key == 0xFF2A2A30.toInt() && text == 0xFFF5F5F7.toInt() -> 0xFF1C1C21.toInt()
            else -> null
        }

        fun from(s: Settings): Theme {
            val accent = parse(s.accentColor, 0xFF0080FF.toInt())
            // Duha konci fialovou: ta je dole pod klavesnici a v navigacni liste.
            val bg2 = if (s.backgroundColor2 == RAINBOW) RAINBOW_COLORS[RAINBOW_COLORS.size - 1] else parse(s.backgroundColor2, 0)
            return when (s.theme) {
                "light" -> of(0xFFE6E8EC.toInt(), 0xFFFFFFFF.toInt(), 0xFF15151A.toInt(), accent, 0xFFCBCFD8.toInt(), bg2)
                "oled_black" -> of(0xFF000000.toInt(), 0xFF17171A.toInt(), 0xFFF5F5F7.toInt(), accent, 0xFF0D0D0F.toInt(), bg2)
                "custom" -> {
                    val bg = parse(s.customBackground, 0xFF111113.toInt())
                    val key = parse(s.customKey, 0xFF26262B.toInt())
                    val text = parse(s.customText, 0xFFF4F4F5.toInt())
                    of(bg, key, text, accent, legacySpecial(bg, key, text), bg2)
                }
                else -> of(0xFF101012.toInt(), 0xFF2A2A30.toInt(), 0xFFF5F5F7.toInt(), accent, 0xFF1C1C21.toInt(), bg2)
            }
        }
    }
}
