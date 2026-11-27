package cz.reallyfast

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import org.json.JSONObject

/** Lucerna. */
object Lantern {
    private const val OIL = "lantern_oil"
    private const val WICK = "w"
    private const val FLAME = "f"

    private val breath = longArrayOf(0, 40, 90, 40, 90, 40)

    private fun jar(c: Context) = c.applicationContext.getSharedPreferences(OIL, Context.MODE_PRIVATE)

    fun lit(c: Context): Boolean = jar(c).getBoolean(FLAME, false)

    /** Posluchac pro otevrenou obrazovku: zavola se (na hlavnim vlakne) po dokonceni postupu. */
    var onLit: (() -> Unit)? = null

    /** Ulozeni stavu knotu v SharedPreferences. */
    private class Jar(private val c: Context) : Store {
        override var rank: Int
            get() = jar(c).getInt(WICK, 0)
            set(v) {
                jar(c).edit().putInt(WICK, v).apply()
            }
        override var lit: Boolean
            get() = jar(c).getBoolean(FLAME, false)
            set(v) {
                jar(c).edit().putBoolean(FLAME, v).apply()
            }
    }

    private var wick: Walk? = null

    private fun walk(c: Context): Walk {
        val w = wick
        if (w != null) return w
        val app = c.applicationContext
        val n = Walk(Jar(app), { SystemClock.uptimeMillis() }) { finish(app) }
        wick = n
        return n
    }

    fun moved(c: Context, prev: JSONObject, now: JSONObject) = walk(c).moved(prev, now)

    /** Po dokonceni: vsechno, co postup menil, se vrati do puvodniho stavu a otevrena obrazovka dostane zpravu. */
    private fun finish(c: Context) {
        pulse(c)
        val j = RustBridge.settings.edit()
        Walk.restore(j)
        RustBridge.setSettings(j)
        Handler(Looper.getMainLooper()).post { onLit?.invoke() }
    }

    private fun pulse(c: Context) {
        try {
            val v = if (Build.VERSION.SDK_INT >= 31) {
                (c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                c.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createWaveform(breath, -1))
            } else {
                @Suppress("DEPRECATION") v.vibrate(breath, -1)
            }
        } catch (_: Throwable) {
            // ticho
        }
    }

    /** Uloziste stavu (v testech pamet). */
    internal interface Store {
        var rank: Int
        var lit: Boolean
    }

    /**
     * Postup krok za krokem; kazda zmena nastaveni posune knot nejvyse o jeden stupen a jen z predchoziho.
     * Zmeni-li se drive splnena hodnota, postup zacina znovu. `done` se zavola jednou, kdyz je postup dokoncen.
     */
    internal class Walk(private val st: Store, private val clock: () -> Long, private val done: () -> Unit) {
        companion object {
            const val STEPS = 10
            private fun hex(j: JSONObject, k: String) = j.optString(k).trim().removePrefix("#").lowercase()
            private fun swapped(j: JSONObject): Boolean {
                val m = j.optJSONObject("key_map") ?: return false
                return m.optString("<") == ">" && m.optString(">") == "<"
            }

            private fun swapShift(j: JSONObject) = j.optBoolean("swap_shift_delete", false)
            private fun fire(j: JSONObject) = j.optString("trail_style") == "fire"
            private fun glow(j: JSONObject) = j.optBoolean("swipe_glow", true).toString() + j.optString("glow_style")
            private fun slide(j: JSONObject) = j.optBoolean("key_glow", true) && j.optString("tap_effect") == "slide"
            private fun black(j: JSONObject) = hex(j, "neon_color") == "000000"
            private fun outline5(j: JSONObject) = j.optInt("outline_width_dp", -1) == 5
            private fun white(j: JSONObject) = hex(j, "digit_text_color") == "ffffff"
            private fun hint60(j: JSONObject) = j.optInt("hint_percent", -1) == 60
            private fun hidden(j: JSONObject) = j.optString("macro_key") == "hidden"

            /** Vrati do puvodniho stavu vsechno, co postup menil. */
            fun restore(j: JSONObject) {
                j.put("swap_shift_delete", false)
                j.optJSONObject("key_map")?.let { it.remove("<"); it.remove(">") }
                j.put("trail_style", "line")
                j.put("swipe_glow", true)
                j.put("glow_style", "neon")
                j.put("key_glow", true)
                j.put("tap_effect", "neon")
                j.put("neon_color", "#0080FF")
                j.put("outline_width_dp", 2)
                j.put("digit_text_color", "")
                j.put("hint_percent", 100)
                j.put("macro_key", "right")
            }
        }

        /** Podminky, ktere musi po splneni kroku trvale platit; ctvrty krok je jen udalost. */
        private fun holds(rank: Int, j: JSONObject): Boolean =
            (rank < 1 || swapShift(j)) && (rank < 2 || swapped(j)) && (rank < 3 || fire(j)) &&
                (rank < 5 || slide(j)) && (rank < 6 || black(j)) && (rank < 7 || outline5(j)) &&
                (rank < 8 || white(j)) && (rank < 9 || hint60(j))

        fun moved(prev: JSONObject, now: JSONObject) {
            if (st.lit) return
            if (st.rank > 0 && !holds(st.rank, now)) st.rank = 0
            val next = when (st.rank) {
                0 -> !swapShift(prev) && swapShift(now)
                1 -> !swapped(prev) && swapped(now)
                2 -> !fire(prev) && fire(now)
                3 -> glow(prev) != glow(now)
                4 -> !slide(prev) && slide(now)
                5 -> !black(prev) && black(now)
                6 -> !outline5(prev) && outline5(now)
                7 -> !white(prev) && white(now)
                8 -> !hint60(prev) && hint60(now)
                9 -> !hidden(prev) && hidden(now)
                else -> false
            }
            if (!next) return
            st.rank++
            if (st.rank >= STEPS) {
                st.lit = true
                st.rank = 0
                done()
            }
        }
    }
}
