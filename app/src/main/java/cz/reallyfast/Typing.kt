package cz.reallyfast

import android.os.Bundle
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.Toast
import java.util.Locale

/** Psani: opravy a navrhy, swipe, odezva (vibrace, zvuk, dlouhy stisk) a gesta. */
class TypingActivity : SettingsScreen() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(bg)
        scroll.fitsSystemWindows = true
        val container = contentColumn()
        scroll.addView(container, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)
        title(container, getString(R.string.typing_open))

        // Psani
        var box = section(container, getString(R.string.sec_typing))
        switchRow(box, R.string.autocorrect, "autocorrect")
        switchRow(box, R.string.predictions, "predictions")
        switchRow(box, R.string.show_suggestions, "show_suggestions")
        switchRow(box, R.string.auto_capitalize, "auto_capitalize")
        switchRow(box, R.string.double_space_to_period, "double_space_to_period")
        switchRow(box, R.string.learn_from_typing, "learn_from_typing")

        // Swipe
        box = section(container, getString(R.string.sec_swipe))
        switchRow(box, R.string.swipe_enabled, "swipe_enabled")
        sliderRow(box, R.string.swipe_sensitivity, "swipe_sensitivity", 50, 150, 100) { String.format(Locale.getDefault(), "%.2f", it / 100f) }
        switchRow(box, R.string.swipe_adapt, "swipe_adapt")
        buttonRow(box, getString(R.string.swipe_adapt_reset), danger) {
            confirmDanger(getString(R.string.swipe_adapt_reset_confirm), getString(R.string.swipe_adapt_reset_do)) {
                RustBridge.resetCalibration()
                Toast.makeText(this, R.string.swipe_adapt_reset_done, Toast.LENGTH_SHORT).show()
            }
        }

        // Odezva
        box = section(container, getString(R.string.sec_feedback))
        sliderRow(box, R.string.vibration, "vibration_ms", 0, 50, 1) {
            if (it == 0) getString(R.string.unit_off) else getString(R.string.unit_ms, it)
        }
        switchRow(box, R.string.sound_enabled, "sound_enabled")
        sliderRow(box, R.string.long_press_delay, "long_press_delay_ms", 150, 500, 1) { getString(R.string.unit_ms, it) }

        // Gesta
        box = section(container, getString(R.string.sec_gestures))
        switchRow(box, R.string.gesture_delete_word, "gesture_delete_word")
        switchRow(box, R.string.gesture_space_cursor, "gesture_space_cursor")
        switchRow(box, R.string.gesture_swipe_up_caps, "gesture_swipe_up_caps")
        switchRow(box, R.string.gesture_swipe_down_hide, "gesture_swipe_down_hide")
        switchRow(box, R.string.gesture_pinch_resize, "gesture_pinch_resize")
    }
}
