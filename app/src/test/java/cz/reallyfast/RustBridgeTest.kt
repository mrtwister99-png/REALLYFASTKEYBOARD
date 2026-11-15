package cz.reallyfast

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
            val lib = File(System.getProperty("reallyfast.hostlib") ?: "")
            if (!lib.isFile || !RustBridge.load(lib.absolutePath)) return
            val dir = Files.createTempDirectory("reallyfast-test").toFile()
            RustBridge.initCore(dir.absolutePath)
            val dict = File(System.getProperty("reallyfast.dictionary") ?: "")
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
        assertTrue(RustBridge.learn("Rustboardík", "pan ", false)) // uprostred vety -> jak napsano
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
    fun macrosAndShortcutsThroughBridge() {
        requireCore()
        assertTrue(RustBridge.saveMacro(0, "Pozdrav", "S pozdravem 😀", "mfg"))
        assertFalse(RustBridge.saveMacro(0, "Prázdné", "   ", ""))
        val m = RustBridge.macros().first { it.name == "Pozdrav" }
        assertEquals("S pozdravem 😀", m.text)
        assertEquals("S pozdravem 😀", RustBridge.expandShortcut("MFG"))
        assertEquals("", RustBridge.expandShortcut("ahoj"))
        assertEquals("S pozdravem 😀", RustBridge.suggest("mfg", "", false)[0])
        RustBridge.deleteMacro(m.id)
        assertEquals("", RustBridge.expandShortcut("mfg"))
    }

    @Test
    fun voiceCommandsThroughBridge() {
        requireCore()
        assertTrue(RustBridge.saveVoiceCommand(0, "Nový řádek", "newline", ""))
        assertTrue(RustBridge.saveVoiceCommand(0, "tečka", "text", "."))
        assertFalse(RustBridge.saveVoiceCommand(0, "x", "text", "  "))
        assertFalse(RustBridge.saveVoiceCommand(0, "x", "neznama", ""))
        val cmds = RustBridge.voiceCommands()
        assertEquals(2, cmds.size)
        val seg = RustBridge.voiceSplit("ahoj novy radek konec tečka")
        assertEquals(listOf("", "newline", "", "text"), seg.map { it.action })
        assertEquals("ahoj", seg[0].text)
        assertEquals(".", seg[3].text)
        for (c in cmds) RustBridge.deleteVoiceCommand(c.id)
        assertTrue(RustBridge.voiceCommands().isEmpty())
        assertEquals(listOf(RustBridge.VoiceSegment("", "novy radek")), RustBridge.voiceSplit("novy radek"))
    }

    @Test
    fun keyMapChangesLayoutJson() {
        requireCore()
        val j = RustBridge.settings.edit()
        j.put("key_map", JSONObject().put("q", "w").put("w", "q"))
        RustBridge.setSettings(j)
        try {
            val keys = JSONObject(RustBridge.layout(1440, 1050, 0, true)).getJSONArray("keys")
            val all = (0 until keys.length()).map { keys.getJSONObject(it) }
            val q = all.first { it.getInt("src") == 'q'.code }
            assertEquals("w", q.getString("label"))
            assertEquals('w'.code, q.getInt("code"))
        } finally {
            j.put("key_map", JSONObject())
            RustBridge.setSettings(j)
        }
        val keys = JSONObject(RustBridge.layout(1440, 1050, 0, true)).getJSONArray("keys")
        assertTrue((0 until keys.length()).all { keys.getJSONObject(it).let { k -> k.getInt("src") == k.getInt("code") } })
        assertTrue(RustBridge.settings.keyGlow)
    }

    @Test
    fun keyAltsChangeLayoutJson() {
        requireCore()
        val j = RustBridge.settings.edit()
        j.put("key_alts", JSONObject().put("a", "xy").put("e", ""))
        RustBridge.setSettings(j)
        try {
            val keys = JSONObject(RustBridge.layout(1440, 1050, 0, true)).getJSONArray("keys")
            val all = (0 until keys.length()).map { keys.getJSONObject(it) }
            assertEquals("xy", all.first { it.getInt("src") == 'a'.code }.getString("alts"))
            assertEquals("", all.first { it.getInt("src") == 'e'.code }.getString("alts"))
        } finally {
            j.put("key_alts", JSONObject())
            RustBridge.setSettings(j)
        }
        val keys = JSONObject(RustBridge.layout(1440, 1050, 0, true)).getJSONArray("keys")
        val a = (0 until keys.length()).map { keys.getJSONObject(it) }.first { it.getInt("src") == 'a'.code }
        assertEquals("áä", a.getString("alts"))
    }

    @Test
    fun twoWordContextAndManualWords() {
        requireCore()
        // vestaveny trigram z korpusu: "na konci" -> "roku"
        assertTrue(RustBridge.suggest("", "Bylo to na konci ", false).contains("roku"))
        assertTrue(RustBridge.addUserWord("Krakonošík"))
        assertFalse(RustBridge.addUserWord("abc123"))
        assertTrue(RustBridge.userWords().any { it.word == "Krakonošík" })
        RustBridge.deleteUserWord("Krakonošík")
        RustBridge.correction("pes", "")   // nesmi spadnout
        RustBridge.resetCalibration()
    }

    @Test
    fun backupExportImportAndSwipeLogThroughBridge() {
        requireCore()
        val json = RustBridge.exportBackup()
        assertTrue(json.contains("\"app\""))
        assertTrue(RustBridge.importBackup(json) >= 0)
        assertEquals(-1, RustBridge.importBackup("nonsense"))
        assertEquals(0, RustBridge.swipeLogCount())
        assertTrue(RustBridge.exportSwipeLog().contains("\"swipes\""))
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
