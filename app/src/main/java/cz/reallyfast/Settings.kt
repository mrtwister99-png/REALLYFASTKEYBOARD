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
    val accentColor: String get() = json.optString("accent_color", "#F59E0B")
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

    /** Kopie k uprave (SettingsActivity). */
    fun edit(): JSONObject = JSONObject(json.toString())
}

/** Barvy klavesnice odvozene z nastaveni. Faze 1: ploche barvy, jedna accent barva. */
class Theme(
    val background: Int,
    val key: Int,
    val keySpecial: Int,
    val keyPressed: Int,
    val text: Int,
    val textDim: Int,
    val accent: Int,
    val onAccent: Int,
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

        private fun luminance(c: Int): Float =
            (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f

        fun from(s: Settings): Theme {
            val accent = parse(s.accentColor, 0xFFF59E0B.toInt())
            val (bg, key, text) = when (s.theme) {
                "light" -> Triple(0xFFE9EAEE.toInt(), 0xFFFFFFFF.toInt(), 0xFF111113.toInt())
                "oled_black" -> Triple(0xFF000000.toInt(), 0xFF161618.toInt(), 0xFFF4F4F5.toInt())
                "custom" -> Triple(
                    parse(s.customBackground, 0xFF111113.toInt()),
                    parse(s.customKey, 0xFF26262B.toInt()),
                    parse(s.customText, 0xFFF4F4F5.toInt()),
                )
                else -> Triple(0xFF111113.toInt(), 0xFF26262B.toInt(), 0xFFF4F4F5.toInt())
            }
            return Theme(
                background = bg,
                key = key,
                keySpecial = blend(key, bg, 0.55f),
                keyPressed = blend(key, text, 0.22f),
                text = text,
                textDim = blend(text, bg, 0.45f),
                accent = accent,
                onAccent = if (luminance(accent) > 0.55f) 0xFF111113.toInt() else 0xFFFFFFFF.toInt(),
            )
        }
    }
}
