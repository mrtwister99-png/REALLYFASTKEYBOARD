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

    private val word = Lantern.Walk.tag(Lantern.Walk.LEARNED_WORD)
    private val name = Lantern.Walk.tag(Lantern.Walk.MACRO_NAME)
    private val body = Lantern.Walk.tag(Lantern.Walk.MACRO_BODY)
    private val short = Lantern.Walk.tag(Lantern.Walk.MACRO_SHORT)

    private fun base() = JSONObject()
        .put("gesture_swipe_down_hide", true).put("gesture_swipe_up_caps", true).put("gesture_space_cursor", true)
        .put("long_press_delay_ms", 300).put("predictions", true).put("swipe_trail", true).put("outline_width_dp", 2)

    /** Provede jednu zmenu nastaveni a vrati novy stav. */
    private fun change(from: JSONObject, key: String, value: Any): JSONObject {
        val to = JSONObject(from.toString()).put(key, value)
        walk.moved(from, to)
        return to
    }

    /** Projde zmenami nastaveni az do stavu po peti krocich. */
    private fun toFive(): JSONObject {
        var j = base()
        j = change(j, "gesture_swipe_down_hide", false)
        j = change(j, "gesture_swipe_up_caps", false)
        j = change(j, "gesture_space_cursor", false)
        j = change(j, "long_press_delay_ms", Lantern.Walk.num(Lantern.Walk.HOLD))
        j = change(j, "predictions", false)
        return j
    }

    /** Projde postupem az do stavu po deviti krocich (zbyvaji dve pouziti makra). */
    private fun toNine(): JSONObject {
        var j = toFive()
        walk.learned(word)
        walk.written(name, body, short)
        j = change(j, "swipe_trail", false)
        j = change(j, "outline_width_dp", 6)
        return j
    }

    @Test
    fun fullWalkUnlocks() {
        toFive()
        assertEquals(5, store.rank)
        walk.learned(word)
        assertEquals(6, store.rank)
        walk.written(name, body, short)
        assertEquals(7, store.rank)
        toNineTail()
        assertEquals(9, store.rank)
        assertFalse(store.lit)
        walk.expanded(short, body)
        assertEquals(10, store.rank)
        assertTrue(walk.macroClicked(short, body))
        assertTrue(store.lit)
        assertEquals(1, finishedCount)
    }

    private fun toNineTail() {
        var j = base()
        j = j.put("gesture_swipe_down_hide", false).put("gesture_swipe_up_caps", false).put("gesture_space_cursor", false)
            .put("long_press_delay_ms", Lantern.Walk.num(Lantern.Walk.HOLD)).put("predictions", false)
        j = change(j, "swipe_trail", false)
        change(j, "outline_width_dp", 6)
    }

    @Test
    fun outOfOrderEventsAreIgnored() {
        walk.learned(word)
        walk.written(name, body, short)
        walk.expanded(short, body)
        assertEquals(0, store.rank)
        toFive()
        walk.written(name, body, short)
        assertEquals(5, store.rank)
    }

    @Test
    fun wrongValuesDoNotAdvance() {
        toFive()
        walk.learned("jine slovo")
        assertEquals(5, store.rank)
        walk.learned(word)
        walk.written(name, "jiny text", short)
        assertEquals(6, store.rank)
        walk.written(name, body, short)
        assertEquals(7, store.rank)
    }

    @Test
    fun changingAnEarlierValueRestartsTheWalk() {
        val j = toFive()
        change(j, "gesture_swipe_down_hide", true)
        assertEquals(0, store.rank)
        walk.learned(word)
        assertEquals(0, store.rank)
    }

    @Test
    fun wrongMacroDoesNotResetTheCount() {
        toNine()
        assertEquals(9, store.rank)
        walk.expanded(short, body)
        walk.expanded("/xx", "abc")
        assertEquals(10, store.rank)
        assertFalse(store.lit)
        walk.expanded(short, body)
        assertTrue(store.lit)
        assertEquals(1, finishedCount)
    }
}
