package cz.reallyfast

import android.content.Context
import kotlin.random.Random

/** Priliv. */
object Tide {
    private const val POOL = "tide_pool"
    private const val EBB = "e"
    private const val SHELLS = "s"

    private const val LOW = (4 * 60 + 55) * 60_000L
    private const val HIGH = (7 * 60 + 30) * 60_000L

    private fun pool(c: Context) = c.applicationContext.getSharedPreferences(POOL, Context.MODE_PRIVATE)

    private fun span(): Long = LOW + (Random.nextDouble() * (HIGH - LOW)).toLong()

    private fun turn(c: Context, now: Long) {
        pool(c).edit().putLong(EBB, now + span()).apply()
    }

    /** Pripliv: true jednou za usek, pak se hned nastavi dalsi. */
    fun swell(c: Context, now: Long = System.currentTimeMillis()): Boolean {
        val at = pool(c).getLong(EBB, 0L)
        if (at <= 0L || at - now > HIGH) {
            turn(c, now)
            return false
        }
        if (now < at) return false
        turn(c, now)
        return true
    }

    /** Mesic. */
    fun moon(c: Context) {
        pool(c).edit().putLong(EBB, 1L).apply()
    }

    /** Musle. */
    fun shells(c: Context): Int = pool(c).getInt(SHELLS, 0)

    fun gather(c: Context): Int {
        val n = shells(c) + 1
        pool(c).edit().putInt(SHELLS, n).apply()
        return n
    }

    fun tally(c: Context): String = "${shells(c)} / x"
}
