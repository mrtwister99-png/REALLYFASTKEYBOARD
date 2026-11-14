package cz.rustboard

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Test JNI mostu: nacte Rust jadro prelozene pro tento pocitac do JVM a vola ho pres
 * stejne nativni metody jako na telefonu. Overuje tim i rucne psanou JNI vrstvu v jni.rs.
 * Spusteni: `gradlew :app:testDebugUnitTest` (knihovnu prelozi task cargoHostBuild).
 * Bez Rustu na pocitaci se testy preskoci.
 */
class RustBridgeTest {
    companion object {
        private var ready = false

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val lib = File(System.getProperty("rustboard.hostlib") ?: "")
            if (!lib.isFile || !RustBridge.load(lib.absolutePath)) return
            val dir = Files.createTempDirectory("rustboard-test").toFile()
            RustBridge.initCore(dir.absolutePath)
            val dict = File(System.getProperty("rustboard.dictionary") ?: "")
            ready = dict.isFile && RustBridge.loadDictionary(dict.absolutePath)
        }
    }

    private fun requireCore() = assumeTrue("Rust jadro pro hostitele neni k dispozici", ready)

    @Test
    fun defaultSettingsAreCreated() {
        requireCore()
        val s = RustBridge.settings
        assertEquals("cs+en", s.language)
        assertEquals(56, s.keyHeightDp)
        assertTrue(s.swipeEnabled)
    }

    @Test
    fun settingsAreValidatedByRust() {
        requireCore()
        val j = RustBridge.settings.edit()
        j.put("vibration_ms", 999)
        j.put("theme", "light")
        RustBridge.setSettings(j)
        assertEquals(50, RustBridge.settings.vibrationMs)
        assertEquals("light", RustBridge.settings.theme)
        j.put("vibration_ms", 10)
        j.put("theme", "dark")
        RustBridge.setSettings(j)
    }

    @Test
    fun layoutContainsCzechKeys() {
        requireCore()
        val keys = JSONObject(RustBridge.layout(1440, 1050, 0)).getJSONArray("keys")
        assertTrue(keys.length() > 30)
        val labels = (0 until keys.length()).map { keys.getJSONObject(it).getString("label") }
        assertTrue(labels.containsAll(listOf("q", "z", "y", "m")))
    }

    @Test
    fun suggestionsRestoreDiacriticsAndUnicodeSurvivesJni() {
        requireCore()
        val s = RustBridge.suggest("prilis", "", false)
        assertEquals("prilis", s[0])
        assertTrue(s.contains("příliš"))
        assertTrue(RustBridge.suggest("", "dobrý", false).contains("den"))
    }

    @Test
    fun swipeDecodesWordFromKeyCenters() {
        requireCore()
        val keys = JSONObject(RustBridge.layout(1440, 1050, 0)).getJSONArray("keys")
        val centers = HashMap<String, Pair<Float, Float>>()
        for (i in 0 until keys.length()) {
            val k = keys.getJSONObject(i)
            centers[k.getString("label")] = Pair(
                (k.getDouble("vx") + k.getDouble("vw") / 2).toFloat(),
                (k.getDouble("y") + k.getDouble("h") / 2).toFloat(),
            )
        }
        val pts = ArrayList<Float>()
        var t = 0f
        val word = "ahoj"
        for (i in 0 until word.length - 1) {
            val a = centers[word[i].toString()]!!
            val b = centers[word[i + 1].toString()]!!
            for (s in 0 until 12) {
                val f = s / 12f
                pts += a.first + (b.first - a.first) * f
                pts += a.second + (b.second - a.second) * f
                pts += t
                t += 8f
            }
        }
        val last = centers["j"]!!
        pts += last.first; pts += last.second; pts += t
        val arr = pts.toFloatArray()
        assertEquals("ahoj", RustBridge.swipe(arr, arr.size, false)[0])
        assertEquals("Ahoj", RustBridge.swipe(arr, arr.size, true)[0])
        assertTrue(RustBridge.swipe(FloatArray(6), 6, false).isEmpty())
    }

    @Test
    fun learningAndClipboardThroughBridge() {
        requireCore()
        assertTrue(RustBridge.learn("Rustboardík", "", false))
        assertTrue(RustBridge.userWords().any { it.word == "Rustboardík" })
        RustBridge.deleteUserWord("Rustboardík")
        assertFalse(RustBridge.userWords().any { it.word == "Rustboardík" })

        assertTrue(RustBridge.clipAdd("běžný text 😀"))
        assertFalse(RustBridge.clipAdd("Tr0ub4dor&3"))
        assertEquals("běžný text 😀", RustBridge.clipList()[0].text)
        RustBridge.clipClear()
        assertTrue(RustBridge.clipList().isEmpty())
    }

    @Test
    fun editingHelpers() {
        requireCore()
        assertEquals(5, RustBridge.deleteWordLen("ahoj světe"))
        assertTrue(RustBridge.shouldCapitalize("Ahoj. "))
        assertTrue(RustBridge.doubleSpacePeriod("Ahoj "))
        assertEquals("den", RustBridge.lastWord("dobrý den "))
        assertEquals(1, RustBridge.shiftTap(0, 5000))
        assertEquals(2, RustBridge.shiftTap(1, 100))
        assertTrue(RustBridge.emojiSearch("pes").contains("🐶"))
        assertEquals(RustBridge.ACTION_DELETE_WORD, RustBridge.gesture(2, -200f, 0f, 100f, 140f, 150f))
    }
}
