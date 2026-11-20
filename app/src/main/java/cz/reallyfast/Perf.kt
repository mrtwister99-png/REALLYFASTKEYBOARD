package cz.reallyfast

import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log

/**
 * Mereni vykonu jen pro debug build (logcat, znacka "fazt-perf"). V release buildu je `enabled` false
 * a cela cena je jedno porovnani. Po kazdych WINDOW vzorcich jednoho druhu vypise pocet, median, p90 a max.
 */
object Perf {
    const val TAG = "fazt-perf"
    private const val WINDOW = 8

    @JvmField
    @Volatile
    var enabled = false

    private class Series(val name: String) {
        val values = LongArray(WINDOW)
        var n = 0
    }

    private val series = HashMap<String, Series>()
    private var drawBatches = 0

    fun init(appInfo: ApplicationInfo) {
        enabled = (appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (enabled) Log.i(TAG, "mereni zapnuto (debug build)")
    }

    fun now(): Long = if (enabled) SystemClock.elapsedRealtimeNanos() else 0L

    /** Zaznamena trvani od `start` (z `now()`); prazdne, kdyz mereni neni zapnuto. */
    fun end(name: String, start: Long) {
        if (!enabled) return
        record(name, SystemClock.elapsedRealtimeNanos() - start)
    }

    /** Pocitadlo udalosti bez casu (napr. kolikrat se prestaveloa geometrie). */
    fun count(name: String) {
        if (!enabled) return
        Log.i(TAG, "$name +1")
    }

    @Synchronized
    private fun record(name: String, nanos: Long) {
        val s = series.getOrPut(name) { Series(name) }
        s.values[s.n++] = nanos
        if (s.n < WINDOW) return
        if (name == "onDraw" && ++drawBatches % 40 != 0) {
            // Kresleni bezi porad (animace), vypis jen vzacne.
            s.n = 0
            return
        }
        val sorted = s.values.copyOf()
        sorted.sort()
        Log.i(
            TAG,
            "$name n=$WINDOW med=${fmt(sorted[WINDOW / 2])} p90=${fmt(sorted[WINDOW * 9 / 10])} max=${fmt(sorted[WINDOW - 1])}"
        )
        s.n = 0
    }

    private fun fmt(nanos: Long): String = String.format(java.util.Locale.ROOT, "%.2fms", nanos / 1_000_000.0)
}
