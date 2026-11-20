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
    private const val OIL = "lantern_oil"
    private const val WICK = "w"
    private const val FLAME = "f"
    private const val ASH = "b"

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
        override var ash: String?
            get() = jar(c).getString(ASH, null)
            set(v) {
                jar(c).edit().putString(ASH, v).apply()
            }
    }

    private var wick: Walk? = null

    private fun walk(c: Context): Walk {
        val w = wick
        if (w != null) return w
        val app = c.applicationContext
        val n = Walk(Jar(app), { SystemClock.uptimeMillis() }) { base -> burn(app, base) }
        wick = n
        return n
    }

    fun moved(c: Context, prev: JSONObject, now: JSONObject) = walk(c).moved(prev, now)

    fun voiced(c: Context, phrase: String) = walk(c).voiced(phrase)

    fun learned(c: Context, word: String) = walk(c).learned(word)

    fun written(c: Context, name: String, body: String, shortcut: String) = walk(c).written(name, body, shortcut)

    fun stored(c: Context, slot: Int) = walk(c).stored(slot, RustBridge.settings.json)

    fun pasted(c: Context) = walk(c).pasted()

    fun expanded(c: Context, shortcut: String, text: String) = walk(c).expanded(shortcut, text)

    /** Konec cesty: vrati nastaveni, uklidi pomocne polozky a zahuci. */
    private fun burn(c: Context, base: String?) {
        try {
            RustBridge.macros().filter { it.shortcut.equals(Walk.tag(Walk.SHORT), ignoreCase = true) }
                .forEach { RustBridge.deleteMacro(it.id) }
            RustBridge.voiceCommands().filter { it.phrase.trim() == Walk.tag(Walk.PAIR) }
                .forEach { RustBridge.deleteVoiceCommand(it.id) }
            RustBridge.deleteUserWord(Walk.tag(Walk.PAIR))
            if (base != null) RustBridge.setSettings(JSONObject(base))
        } catch (_: Throwable) {
            // uklid je jen kosmetika
        }
        pulse(c)
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
        var ash: String?
    }

    /**
     * Postup krok za krokem; kazda udalost posune knot nejvyse o jeden stupen a jen z predchoziho.
     * `done` dostane nastaveni z doby pred prvnim krokem.
     */
    internal class Walk(private val st: Store, private val clock: () -> Long, private val done: (String?) -> Unit) {
        companion object {
            private const val MASK = 0x2B7

            // Cisla a retezce jsou schovane za maskou, aby se nedaly najit hledanim v kodu.
            const val SENS = 562
            const val BUZZ = 662
            const val HOLD = 1018
            const val TALL = 757
            const val ROUND = 700
            const val SIZE = 562
            const val SLOT = 693
            val PAIR = intArrayOf(644, 644)
            val BODY = intArrayOf(641, 641, 641)
            val SHORT = intArrayOf(664, 644, 644)

            fun num(v: Int) = v xor MASK

            fun tag(v: IntArray) = String(CharArray(v.size) { (v[it] xor MASK).toChar() })

            private const val SPAN = 1500L
            private const val GAP = 30000L
        }

        private var base: String? = null
        private var lastMove = 0L
        private var stamp = 0L

        private fun near(j: JSONObject, key: String, want: Double) = abs(j.optDouble(key, -1000.0) - want) < 0.004

        private fun same(j: JSONObject, key: String, want: Int) = j.optInt(key, -1) == want

        private fun sens(j: JSONObject) = near(j, "swipe_sensitivity", num(SENS) / 100.0)
        private fun buzz(j: JSONObject) = same(j, "vibration_ms", num(BUZZ))
        private fun hold(j: JSONObject) = same(j, "long_press_delay_ms", num(HOLD))
        private fun look(j: JSONObject) =
            same(j, "key_height_dp", num(TALL)) && same(j, "key_radius_dp", num(ROUND)) && same(j, "key_text_percent", num(SIZE))

        /** Podminky drive splnenych kroku musi porad platit. */
        private fun holds(rank: Int, j: JSONObject): Boolean =
            (rank < 1 || sens(j)) && (rank < 2 || buzz(j)) && (rank < 3 || hold(j)) && (rank < 7 || look(j))

        fun moved(prev: JSONObject, now: JSONObject) {
            if (st.lit) return
            val t = clock()
            // Dokud neni zacato, drzi se nastaveni z doby pred posledni serii zmen.
            if (st.rank == 0 && t - lastMove > SPAN) base = prev.toString()
            lastMove = t
            if (st.rank > 0 && !holds(st.rank, now)) {
                st.rank = 0
                st.ash = null
            }
            when (st.rank) {
                0 -> if (!sens(prev) && sens(now)) {
                    st.ash = base ?: prev.toString()
                    st.rank = 1
                }
                1 -> if (!buzz(prev) && buzz(now)) st.rank = 2
                2 -> if (!hold(prev) && hold(now)) st.rank = 3
            }
        }

        fun voiced(phrase: String) {
            if (!st.lit && st.rank == 3 && phrase.trim() == tag(PAIR)) st.rank = 4
        }

        fun learned(word: String) {
            if (!st.lit && st.rank == 4 && word.trim() == tag(PAIR)) st.rank = 5
        }

        fun written(name: String, body: String, shortcut: String) {
            if (!st.lit && st.rank == 5 && name.trim() == tag(PAIR) && body.trim() == tag(BODY) &&
                shortcut.trim().equals(tag(SHORT), ignoreCase = true)
            ) st.rank = 6
        }

        fun stored(slot: Int, now: JSONObject) {
            if (!st.lit && st.rank == 6 && slot == num(SLOT) && holds(6, now) && look(now)) st.rank = 7
        }

        fun pasted() {
            if (!st.lit && st.rank in 7..9) st.rank = 8
        }

        fun expanded(shortcut: String, text: String) {
            if (st.lit) return
            val ok = shortcut.equals(tag(SHORT), ignoreCase = true) && text.trim() == tag(BODY)
            val t = clock()
            when (st.rank) {
                8 -> if (ok) {
                    st.rank = 9
                    stamp = t
                }
                9 -> when {
                    !ok -> st.rank = 8
                    t - stamp <= GAP -> {
                        st.lit = true
                        val b = st.ash
                        st.ash = null
                        st.rank = 0
                        done(b)
                    }
                    else -> stamp = t
                }
            }
        }
    }
}
