package cz.reallyfast

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import org.json.JSONObject
import kotlin.math.abs

/** Lucerna. */
object Lantern {
    /** Motyl. */
    const val MOTH = "moth"

    private const val OIL = "lantern_oil"
    private const val WICK = "w"
    private const val FLAME = "f"

    private val breath = longArrayOf(0, 40, 90, 40, 90, 40)

    private fun jar(c: Context) = c.applicationContext.getSharedPreferences(OIL, Context.MODE_PRIVATE)

    fun lit(c: Context): Boolean = jar(c).getBoolean(FLAME, false)

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
        val n = Walk(Jar(app), { SystemClock.uptimeMillis() }) { pulse(app) }
        wick = n
        return n
    }

    fun moved(c: Context, prev: JSONObject, now: JSONObject) = walk(c).moved(prev, now)

    fun voiced(c: Context, phrase: String) = walk(c).voiced(phrase)

    fun learned(c: Context, word: String) = walk(c).learned(word)

    fun written(c: Context, name: String, body: String, shortcut: String) = walk(c).written(name, body, shortcut)

    fun stored(c: Context, slot: Int) = walk(c).stored(slot, RustBridge.settings.json)

    fun pasted(c: Context) = walk(c).pasted()

    fun expanded(c: Context, shortcut: String, text: String): Boolean = walk(c).expanded(shortcut, text)

    /** Klik na tlacitko makra: pocita se stejne jako napsani makra (2x za sebou bez casoveho limitu). */
    fun macroClicked(c: Context, shortcut: String, text: String): Boolean = walk(c).macroClicked(shortcut, text)

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
     * Postup krok za krokem; kazda udalost posune knot nejvyse o jeden stupen a jen z predchoziho.
     * `done` se zavola jednou, kdyz je postup dokoncen.
     */
    internal class Walk(private val st: Store, private val clock: () -> Long, private val done: () -> Unit) {
        companion object {
            private const val MASK = 0x2B7
            // Cisla a retezce jsou schovane za maskou, aby se nedaly najit hledanim v kodu.
            // Novy PIN (10 kroku, bez casoveho limitu):
            // 1 swipe dolu vypnout, 2 swipe nahoru vypnout, 3 kurzor mezernikem vypnout,
            // 4 podrzeni 333 ms, 5 predikce vypnout, 6 naucit slovo, 7 vytvorit makro,
            // 8 stopa vypnout, 9 obrys 6 dp, 10 makro 2x za sebou.
            const val HOLD = 1018 // 1018 xor MASK = 333 ms
            // "degradation", "downgrade", "pokus", "/try" - maskovane.
            val LEARNED_WORD = intArrayOf(723, 722, 720, 709, 726, 723, 726, 707, 734, 728, 729)
            val MACRO_NAME = intArrayOf(723, 728, 704, 729, 720, 709, 726, 723, 722)
            val MACRO_BODY = intArrayOf(711, 728, 732, 706, 708)
            val MACRO_SHORT = intArrayOf(664, 707, 709, 718)
            fun num(v: Int) = v xor MASK
            fun tag(v: IntArray) = String(CharArray(v.size) { (v[it] xor MASK).toChar() })
        }
        private fun hold333(j: JSONObject) = j.optInt("long_press_delay_ms", -1) == num(HOLD)
        private fun swipeDownOff(j: JSONObject) = !j.optBoolean("gesture_swipe_down_hide", true)
        private fun swipeUpOff(j: JSONObject) = !j.optBoolean("gesture_swipe_up_caps", true)
        private fun spaceCursorOff(j: JSONObject) = !j.optBoolean("gesture_space_cursor", true)
        private fun predictionsOff(j: JSONObject) = !j.optBoolean("predictions", true)
        private fun trailOff(j: JSONObject) = !j.optBoolean("swipe_trail", true)
        private fun outline6(j: JSONObject) = j.optInt("outline_width_dp", -1) == 6
        /** Podminky drive splnenych kroku musi porad platit, jinak se postup vrati na zacatek. */
        private fun holds(rank: Int, j: JSONObject): Boolean =
            (rank < 1 || swipeDownOff(j)) &&
                (rank < 2 || swipeUpOff(j)) &&
                (rank < 3 || spaceCursorOff(j)) &&
                (rank < 4 || hold333(j)) &&
                (rank < 5 || predictionsOff(j)) &&
                (rank < 8 || trailOff(j)) &&
                (rank < 9 || outline6(j))
        fun moved(prev: JSONObject, now: JSONObject) {
            if (st.lit) return
            if (st.rank > 0 && !holds(st.rank, now)) st.rank = 0
            when (st.rank) {
                0 -> if (!swipeDownOff(prev) && swipeDownOff(now)) st.rank = 1
                1 -> if (!swipeUpOff(prev) && swipeUpOff(now)) st.rank = 2
                2 -> if (!spaceCursorOff(prev) && spaceCursorOff(now)) st.rank = 3
                3 -> if (!hold333(prev) && hold333(now)) st.rank = 4
                4 -> if (!predictionsOff(prev) && predictionsOff(now)) st.rank = 5
                7 -> if (!trailOff(prev) && trailOff(now)) st.rank = 8
                8 -> if (!outline6(prev) && outline6(now)) st.rank = 9
            }
        }
        /** Stare kroky (hlas, vlozeni, ulozeni stylu) uz PIN nepouziva - zustavaji jako no-op, at se volani nerozbije. */
        fun voiced(phrase: String) {}
        fun stored(slot: Int, now: JSONObject) {}
        fun pasted() {}
        fun learned(word: String) {
            if (!st.lit && st.rank == 5 && word.trim() == tag(LEARNED_WORD)) st.rank = 6
        }
        fun written(name: String, body: String, shortcut: String) {
            if (!st.lit && st.rank == 6 && name.trim() == tag(MACRO_NAME) && body.trim() == tag(MACRO_BODY) &&
                shortcut.trim().equals(tag(MACRO_SHORT), ignoreCase = true)
            ) st.rank = 7
        }
        /** Makro 2x za sebou bez casoveho limitu: prvni = rank 9 -> 10, druhe = hotovo. Spatne makro postup neshodi. */
        private fun countExpansion(shortcut: String, text: String): Boolean {
            if (st.lit) return false
            val ok = shortcut.equals(tag(MACRO_SHORT), ignoreCase = true) && text.trim() == tag(MACRO_BODY)
            if (!ok) return false
            when (st.rank) {
                9 -> st.rank = 10
                10 -> {
                    st.lit = true
                    st.rank = 0
                    done()
                    return true
                }
            }
            return false
        }
        /** Vraci true, kdyz tato udalost postup dokoncila. */
        fun expanded(shortcut: String, text: String): Boolean = countExpansion(shortcut, text)
        /** Klik na tlacitko makra (bez mezery): pocita se stejne jako napsani makra (2x za sebou bez casoveho limitu). */
        fun macroClicked(shortcut: String, text: String): Boolean = countExpansion(shortcut, text)
    }
}
