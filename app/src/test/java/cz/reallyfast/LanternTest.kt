package cz.reallyfast

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Test postupu odemykani (cisty automat bez Androidu). */
class LanternTest {
    private class Mem : Lantern.Store {
        override var rank = 0
        override var lit = false
    }

    private var finishedCount = 0
    private val store = Mem()
    private val walk = Lantern.Walk(store, { 10_000L }) { finishedCount++ }

    private fun base() = JSONObject()
        .put("swap_shift_delete", false).put("key_map", JSONObject()).put("trail_style", "line")
        .put("swipe_glow", true).put("glow_style", "neon").put("key_glow", true).put("tap_effect", "neon")
        .put("neon_color", "#0080FF").put("outline_width_dp", 2).put("digit_text_color", "")
        .put("hint_percent", 100).put("macro_key", "left")

    private var cur = base()

    /** Provede jednu zmenu nastaveni. */
    private fun change(key: String, value: Any) {
        val to = JSONObject(cur.toString()).put(key, value)
        walk.moved(cur, to)
        cur = to
    }

    private fun swapSymbols() {
        change("key_map", JSONObject().put("<", ">").put(">", "<"))
    }

    /** Provede kroky 1 az n. */
    private fun steps(n: Int) {
        val all = listOf<() -> Unit>(
            { change("swap_shift_delete", true) },
            { swapSymbols() },
            { change("trail_style", "fire") },
            { change("glow_style", "paint") },
            { change("tap_effect", "slide") },
            { change("neon_color", "#000000") },
            { change("outline_width_dp", 5) },
            { change("digit_text_color", "#FFFFFF") },
            { change("hint_percent", 60) },
            { change("macro_key", "hidden") },
        )
        for (i in 0 until n) all[i]()
    }

    @Test
    fun fullWalkUnlocks() {
        steps(9)
        assertEquals(9, store.rank)
        assertFalse(store.lit)
        steps(10)
        assertTrue(store.lit)
        assertEquals(1, finishedCount)
    }

    @Test
    fun outOfOrderChangesAreIgnored() {
        change("macro_key", "hidden")
        change("hint_percent", 60)
        assertEquals(0, store.rank)
        steps(3)
        assertEquals(3, store.rank)
        change("hint_percent", 60)
        assertEquals(3, store.rank)
    }

    @Test
    fun changingAnEarlierValueRestartsTheWalk() {
        steps(5)
        assertEquals(5, store.rank)
        change("trail_style", "line")
        assertEquals(0, store.rank)
    }

    @Test
    fun wrongValuesDoNotAdvance() {
        steps(5)
        change("neon_color", "#111111")
        assertEquals(5, store.rank)
        change("neon_color", "#000000")
        assertEquals(6, store.rank)
    }

    @Test
    fun restoreReturnsEverything() {
        steps(10)
        Lantern.Walk.restore(cur)
        assertEquals("line", cur.getString("trail_style"))
        assertEquals(100, cur.getInt("hint_percent"))
        assertEquals("left", cur.getString("macro_key"))
        assertFalse(cur.getJSONObject("key_map").has("<"))
        assertFalse(cur.getBoolean("swap_shift_delete"))
    }
}
