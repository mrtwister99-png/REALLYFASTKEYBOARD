package cz.reallyfast

import android.graphics.Color
import android.util.SparseIntArray
import org.json.JSONObject

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
    val theme: String get() = json.optString("theme", "dark")
    val accentColor: String get() = json.optString("accent_color", "#0080FF")
    val showNumberRow: Boolean get() = json.optBoolean("show_number_row", true)
    val showSuggestions: Boolean get() = json.optBoolean("show_suggestions", true)
    val autoCapitalize: Boolean get() = json.optBoolean("auto_capitalize", true)
    val doubleSpaceToPeriod: Boolean get() = json.optBoolean("double_space_to_period", true)
    val longPressDelayMs: Int get() = json.optInt("long_press_delay_ms", 250)
    val customBackground: String get() = json.optString("custom_background", "#111113")
    val customKey: String get() = json.optString("custom_key", "#26262B")
    val customText: String get() = json.optString("custom_text", "#F4F4F5")
    val gestureSpaceCursor: Boolean get() = json.optBoolean("gesture_space_cursor", true)
    val gesturePinchResize: Boolean get() = json.optBoolean("gesture_pinch_resize", true)
    val clipboardEnabled: Boolean get() = json.optBoolean("clipboard_enabled", true)

    val voiceInput: Boolean get() = json.optBoolean("voice_input", true)

    // Vzhled (Faze 2)
    val keyPopup: Boolean get() = json.optBoolean("key_popup", true)
    val keyHints: Boolean get() = json.optBoolean("key_hints", true)
    val keyShadow: Boolean get() = json.optBoolean("key_shadow", true)
    val keyRadiusDp: Int get() = json.optInt("key_radius_dp", 10)
    val animations: Boolean get() = json.optBoolean("animations", true)
    val swipeTrail: Boolean get() = json.optBoolean("swipe_trail", true)
    val swipeGlow: Boolean get() = json.optBoolean("swipe_glow", true)
    val keyGlow: Boolean get() = json.optBoolean("key_glow", true)
    // Poloha napovedy: 0..1 = uvnitr klavesy, -1..2 = az za okraj (viz KeyboardView.drawHint).
    val keyHintX: Float get() = json.optDouble("key_hint_x", 1.0).toFloat().coerceIn(-1f, 2f)
    val keyHintY: Float get() = json.optDouble("key_hint_y", 0.0).toFloat().coerceIn(-1f, 2f)
    /** Prazdny retezec = automaticka barva napovedy podle motivu. */
    val hintColor: String get() = json.optString("hint_color", "")
    val hintPercent: Int get() = json.optInt("hint_percent", 100).coerceIn(60, 180)
    /** Efekt po klepnuti: "neon", "glitch" nebo "paint". */
    val tapEffect: String get() = json.optString("tap_effect", "neon")
    val neonColor: String get() = json.optString("neon_color", "#0080FF")
    val glowColor: String get() = json.optString("glow_color", "#0080FF")
    val glowStyle: String get() = json.optString("glow_style", "blink")
    val keyGapDp: Int get() = json.optInt("key_gap_dp", 5).coerceIn(1, 10)
    val rowGapDp: Int
        get() {
            val v = json.optInt("row_gap_dp", -1)
            return (if (v < 0) Math.round(keyGapDp * 1.6f) else v).coerceIn(2, 16)
        }
    /** Prazdny retezec = automaticka barva podle motivu. */
    val popupColor: String get() = json.optString("popup_color", "")
    val altsColor: String get() = json.optString("alts_color", "")
    val numberDivider: Boolean get() = json.optBoolean("number_divider", true)
    val keyTextPercent: Int get() = json.optInt("key_text_percent", 100).coerceIn(70, 150)
    /** Barvy cislic a druhe barvy klaves / pozadi: prazdny retezec = automaticky / vypnuto. */
    val digitTextColor: String get() = json.optString("digit_text_color", "")
    val digitKeyColor: String get() = json.optString("digit_key_color", "")
    val keyColor2: String get() = json.optString("key_color2", "")
    val backgroundColor2: String get() = json.optString("background_color2", "")
    val paintColor1: String get() = json.optString("paint_color1", "#0080FF")
    val paintColor2: String get() = json.optString("paint_color2", "#FF5100")
    val paintColor3: String get() = json.optString("paint_color3", "#6A02E8")
    /** Poloha hlavniho znaku na klavese: 0..1, 0,5 = uprostred. */
    val keyLabelX: Float get() = json.optDouble("key_label_x", 0.5).toFloat().coerceIn(0f, 1f)
    val keyLabelY: Float get() = json.optDouble("key_label_y", 0.5).toFloat().coerceIn(0f, 1f)
    /** Prazdny retezec = automaticka barva znaku pri prejeti tahem / vypnuta barva znaku po klepnuti. */
    val passedTextColor: String get() = json.optString("passed_text_color", "")
    val tapTextColor: String get() = json.optString("tap_text_color", "")

    /** Makra prirazena klavesam: puvodni kod pozice (`Key.src`) -> id makra. Sestavi se jednou pro kazde nastaveni. */
    val keyMacros: SparseIntArray by lazy {
        val out = SparseIntArray()
        val o = json.optJSONObject("key_macros")
        if (o != null) {
            for (name in o.keys()) {
                if (name.isEmpty()) continue
                val id = o.optInt(name, 0)
                if (id > 0) out.put(name.codePointAt(0), id)
            }
        }
        out
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
    val byKeys = rows * s.keyHeightDp * dm.density
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

    companion object {
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
            val bg2 = parse(s.backgroundColor2, 0)
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
