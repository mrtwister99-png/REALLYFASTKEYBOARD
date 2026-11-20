package cz.reallyfast

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Test postupu odemykani (cisty automat bez Androidu). */
class LanternTest {
    private class Mem : Lantern.Store {
        override var rank = 0
        override var lit = false
        override var ash: String? = null
    }

    private var now = 10_000L
    private var finished: String? = null
    private var finishedCount = 0
    private val store = Mem()
    private val walk = Lantern.Walk(store, { now }) {
        finished = it
        finishedCount++
    }

    private fun json(sens: Double = 1.0, vib: Int = 10, hold: Int = 300, h: Int = 56, r: Int = 12, t: Int = 100) =
        JSONObject().put("swipe_sensitivity", sens).put("vibration_ms", vib).put("long_press_delay_ms", hold)
            .put("key_height_dp", h).put("key_radius_dp", r).put("key_text_percent", t)

    private fun step(ms: Long = 100) {
        now += ms
    }

    /** Projde nastavenim az ke kroku 3 (stav po tri zmenach nastaveni). */
    private fun toThree(): JSONObject {
        val a = json()
        val b = json(sens = 1.33)
        walk.moved(a, b)
        step()
        val c = json(sens = 1.33, vib = 33)
        walk.moved(b, c)
        step()
        val d = json(sens = 1.33, vib = 33, hold = 333)
        walk.moved(c, d)
        return d
    }

    @Test
    fun fullWalkUnlocksAndHandsBackTheOriginalSettings() {
        val start = json()
        val d = toThree()
        assertEquals(3, store.rank)
        assertEquals(start.toString(), store.ash)

        walk.voiced("33")
        walk.learned(" 33 ")
        walk.written("33", "666", "/33")
        assertEquals(6, store.rank)

        val e = JSONObject(d.toString()).put("key_height_dp", 66).put("key_radius_dp", 11).put("key_text_percent", 133)
        step()
        walk.moved(d, e)
        walk.stored(2, e)
        assertEquals(7, store.rank)

        walk.pasted()
        walk.expanded("/33", "666")
        assertEquals(9, store.rank)
        assertFalse(store.lit)
        step(2000)
        walk.expanded("/33", "666")
        assertTrue(store.lit)
        assertEquals(1, finishedCount)
        assertEquals(start.toString(), finished)
    }

    @Test
    fun baselineIsTheStateBeforeTheDragThatReachedTheFirstValue() {
        val first = json(sens = 1.30)
        walk.moved(json(), first)
        step()
        walk.moved(first, json(sens = 1.31))
        step()
        walk.moved(json(sens = 1.31), json(sens = 1.33))
        assertEquals(1, store.rank)
        assertEquals(json().toString(), store.ash)
    }

    @Test
    fun outOfOrderEventsAreIgnored() {
        walk.voiced("33")
        walk.learned("33")
        walk.written("33", "666", "/33")
        walk.pasted()
        walk.expanded("/33", "666")
        assertEquals(0, store.rank)
        toThree()
        walk.learned("33")
        assertEquals(3, store.rank)
    }

    @Test
    fun wrongValuesDoNotAdvance() {
        toThree()
        walk.voiced("34")
        assertEquals(3, store.rank)
        walk.voiced("33")
        walk.learned("333")
        assertEquals(4, store.rank)
        walk.learned("33")
        walk.written("33", "667", "/33")
        assertEquals(5, store.rank)
    }

    @Test
    fun changingAnEarlierValueRestartsTheWalk() {
        val d = toThree()
        val broken = JSONObject(d.toString()).put("vibration_ms", 20)
        step()
        walk.moved(d, broken)
        assertEquals(0, store.rank)
        assertNull(store.ash)
        walk.voiced("33")
        assertEquals(0, store.rank)
    }

    @Test
    fun secondExpansionMustFollowTheFirstOne() {
        val d = toThree()
        walk.voiced("33")
        walk.learned("33")
        walk.written("33", "666", "/33")
        val e = JSONObject(d.toString()).put("key_height_dp", 66).put("key_radius_dp", 11).put("key_text_percent", 133)
        walk.moved(d, e)
        walk.stored(2, e)
        walk.pasted()
        walk.expanded("/33", "666")
        walk.expanded("/xx", "abc")
        assertEquals(8, store.rank)
        walk.expanded("/33", "666")
        assertEquals(9, store.rank)
        step(31_000)
        walk.expanded("/33", "666")
        assertFalse(store.lit)
        walk.expanded("/33", "666")
        assertTrue(store.lit)
        assertNotNull(finished)
    }

    @Test
    fun savingTheWrongSlotOrLookDoesNotAdvance() {
        val d = toThree()
        walk.voiced("33")
        walk.learned("33")
        walk.written("33", "666", "/33")
        walk.stored(2, d)
        assertEquals(6, store.rank)
        val e = JSONObject(d.toString()).put("key_height_dp", 66).put("key_radius_dp", 11).put("key_text_percent", 133)
        walk.moved(d, e)
        walk.stored(1, e)
        assertEquals(6, store.rank)
        walk.stored(2, e)
        assertEquals(7, store.rank)
    }
}
