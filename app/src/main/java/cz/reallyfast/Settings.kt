package cz.reallyfast

import android.graphics.Color
import org.json.JSONObject

/**
 * Pohled na nastaveni (JSON ze settings.json, ktery vlastni a validuje Rust).
 * Kotlin hodnoty jen cte; zmeny jdou vzdy pres RustBridge.setSettings().
 */
class Settings(val json: JSONObject) {
    val language: String get() = json.optString("language", "cs+en")
    val layout: String get() = json.optString("layout", "qwertz")
    val swipeEnabled: Boolean get() = json.optBoolean("swipe_enabled", true)
    val swipeSensitivity: Double get() = json.optDouble("swipe_sensitivity", 1.0)
    val autocorrect: Boolean get() = json.optBoolean("autocorrect", true)
    val predictions: Boolean get() = json.optBoolean("predictions", true)
    val learnFromTyping: Boolean get() = json.optBoolean("learn_from_typing", true)
    val vibrationMs: Int get() = json.optInt("vibration_ms", 10)
    val soundEnabled: Boolean get() = json.optBoolean("sound_enabled", false)
    val keyHeightDp: Int get() = json.optInt("key_height_dp", 56)
    val keyboardHeightPercent: Int get() = json.optInt("keyboard_height_percent", 38)
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

    /** Kopie k uprave (SettingsActivity). */
    fun edit(): JSONObject = JSONObject(json.toString())
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
) {
    companion object {
        fun parse(hex: String, fallback: Int): Int = try {
            Color.parseColor(hex)
        } catch (t: Throwable) {
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
        fun of(bg: Int, key: Int, text: Int, accent: Int, special: Int? = null): Theme {
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
            )
        }

        fun from(s: Settings): Theme {
            val accent = parse(s.accentColor, 0xFF0080FF.toInt())
            return when (s.theme) {
                "light" -> of(0xFFE6E8EC.toInt(), 0xFFFFFFFF.toInt(), 0xFF15151A.toInt(), accent, 0xFFCBCFD8.toInt())
                "oled_black" -> of(0xFF000000.toInt(), 0xFF17171A.toInt(), 0xFFF5F5F7.toInt(), accent, 0xFF0D0D0F.toInt())
                "custom" -> of(
                    parse(s.customBackground, 0xFF111113.toInt()),
                    parse(s.customKey, 0xFF26262B.toInt()),
                    parse(s.customText, 0xFFF4F4F5.toInt()),
                    accent,
                )
                else -> of(0xFF101012.toInt(), 0xFF2A2A30.toInt(), 0xFFF5F5F7.toInt(), accent, 0xFF1C1C21.toInt())
            }
        }
    }
}
