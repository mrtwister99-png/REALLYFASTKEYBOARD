package cz.reallyfast

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Lucerna. */
object Lantern {
    /** Motyl. */
    const val MOTH = "moth"

    private const val OIL = "lantern_oil"
    private const val WICK = "w"
    private const val FLAME = "f"

    // Stupne knotu.
    private const val COLD = 0
    private const val SPARK = 1
    private const val EMBER = 2
    private const val COAL = 3
    private const val BLAZE = 4

    // Popel, saze, kour.
    private const val ASH = "1"
    private const val SOOT = "test."
    private const val SMOKE = "downgrade"

    private val breath = longArrayOf(0, 40, 90, 40, 90, 40)

    private fun jar(c: Context) = c.applicationContext.getSharedPreferences(OIL, Context.MODE_PRIVATE)

    private fun rank(c: Context) = jar(c).getInt(WICK, COLD)

    private fun raise(c: Context, to: Int) {
        jar(c).edit().putInt(WICK, to).apply()
    }

    private fun fits(a: String, b: String, d: String) =
        a.trim() == ASH && b.trim() == SOOT && d.trim().equals(SMOKE, ignoreCase = true)

    fun lit(c: Context): Boolean = jar(c).getBoolean(FLAME, false)

    fun kindle(c: Context, a: String, b: String, d: String) {
        if (lit(c) || rank(c) >= SPARK || !fits(a, b, d)) return
        raise(c, SPARK)
    }

    fun breathe(c: Context, a: String, b: String, d: String): Boolean {
        if (lit(c) || rank(c) < SPARK || !fits(a, b, d)) return false
        raise(c, maxOf(rank(c), EMBER))
        pulse(c)
        return true
    }

    fun fan(c: Context) {
        if (!lit(c) && rank(c) == EMBER) raise(c, COAL)
    }

    fun flicker(c: Context) {
        if (!lit(c) && rank(c) == COAL) raise(c, BLAZE)
    }

    fun gleam(c: Context): Boolean {
        if (lit(c) || rank(c) != BLAZE) return false
        jar(c).edit().putBoolean(FLAME, true).apply()
        return true
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
}
