package cz.reallyfast

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tenky JNI wrapper nad Rust jadrem (crates/keyboard-core).
 *
 * Veskera logika (rozlozeni, swipe decode, predikce, autocorrect, uceni, nastaveni, schranka,
 * emoji, gesta) je v Rustu. Retezce se predavaji jako UTF-8 ByteArray, aby se nepoškodila emoji.
 * Kdyz se nativni knihovna nepodari nacist, vsechny funkce vraci prazdny vysledek (zadny crash).
 */
object RustBridge {
    // Kody operaci - musi odpovidat konstantam OP_* v crates/keyboard-core/src/lib.rs.
    private const val OP_INIT = 1
    private const val OP_LOAD_DICT = 2
    private const val OP_GET_SETTINGS = 3
    private const val OP_SET_SETTINGS = 4
    private const val OP_LAYOUT = 5
    private const val OP_SUGGEST = 6
    private const val OP_LEARN = 7
    private const val OP_USER_WORDS = 8
    private const val OP_USER_DELETE = 9
    private const val OP_USER_EXPORT = 10
    private const val OP_USER_IMPORT = 11
    private const val OP_USER_CLEAR = 12
    private const val OP_CLIP_ADD = 13
    private const val OP_CLIP_LIST = 14
    private const val OP_CLIP_PIN = 15
    private const val OP_CLIP_DELETE = 16
    private const val OP_CLIP_CLEAR = 17
    private const val OP_EMOJI_CATEGORY = 18
    private const val OP_EMOJI_SEARCH = 19
    private const val OP_EMOJI_USED = 20
    private const val OP_GESTURE = 21
    private const val OP_DELETE_WORD_LEN = 22
    private const val OP_WORD_JUMP_LEN = 23
    private const val OP_SHOULD_CAPITALIZE = 24
    private const val OP_DOUBLE_SPACE = 25
    private const val OP_PINCH = 26
    private const val OP_CYCLE_LANGUAGE = 28
    private const val OP_SHIFT_TAP = 29
    private const val OP_LAST_WORD = 30
    private const val OP_CURSOR_STEPS = 31
    private const val OP_INFO = 32
    private const val OP_USER_ADD = 33
    private const val OP_MACRO_LIST = 34
    private const val OP_MACRO_SAVE = 35
    private const val OP_MACRO_DELETE = 36
    private const val OP_SHORTCUT = 37
    private const val OP_CORRECTION = 38
    private const val OP_CALIBRATION_RESET = 39
    private const val OP_SWIPE_LOG_EXPORT = 40
    private const val OP_SWIPE_LOG_CLEAR = 41
    private const val OP_SWIPE_LOG_COUNT = 42
    private const val OP_BACKUP_EXPORT = 43
    private const val OP_BACKUP_IMPORT = 44
    private const val OP_VOICE_LIST = 45
    private const val OP_VOICE_SAVE = 46
    private const val OP_VOICE_DELETE = 47
    private const val OP_VOICE_SPLIT = 48

    const val FLAG_CAPITALIZE = 1
    const val FLAG_FROM_SWIPE = 4

    // Akce gest (gestures.rs)
    const val ACTION_NONE = 0
    const val ACTION_DELETE_WORD = 1
    const val ACTION_CAPS_ON = 2
    const val ACTION_HIDE_KEYBOARD = 3
    const val ACTION_CURSOR_WORD_LEFT = 4
    const val ACTION_CURSOR_WORD_RIGHT = 5

    private const val PREFS = "reallyfast_backup"
    private const val PREF_SETTINGS = "settings_json"
    private const val PREF_DICT_STAMP = "dictionary_stamp"
    private const val DICT_ASSET = "dictionary.bin"

    @Volatile
    private var loaded = false
    @Volatile
    private var initialized = false
    @Volatile
    private var dictLoading = false
    private var appContext: Context? = null
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** Aktualni nastaveni (kopie stavu v Rustu). */
    @Volatile
    var settings: Settings = Settings(JSONObject())
        private set

    /** Volano na hlavnim vlakne po kazde zmene nastaveni a po nacteni slovniku. */
    val listeners = CopyOnWriteArrayList<() -> Unit>()

    @JvmStatic
    private external fun nativeCall(op: Int, a: ByteArray?, b: ByteArray?, num: Long): ByteArray?

    @JvmStatic
    private external fun nativeSwipe(points: FloatArray, count: Int, flags: Int): ByteArray?

    /** Nacte nativni knihovnu. `hostLibPath` se pouziva jen v JVM testech na pocitaci. */
    @Synchronized
    @SuppressLint("UnsafeDynamicallyLoadedCode") // System.load jen pro JVM test na pocitaci
    fun load(hostLibPath: String? = null): Boolean {
        if (loaded) return true
        loaded = try {
            if (hostLibPath != null) System.load(hostLibPath) else System.loadLibrary("keyboard_core")
            true
        } catch (_: Throwable) {
            false
        }
        return loaded
    }

    val isLoaded: Boolean get() = loaded

    private fun call(op: Int, a: String? = null, b: String? = null, num: Long = 0): String {
        if (!loaded) return ""
        return try {
            nativeCall(op, a?.toByteArray(Charsets.UTF_8), b?.toByteArray(Charsets.UTF_8), num)
                ?.toString(Charsets.UTF_8) ?: ""
        } catch (_: Throwable) {
            ""
        }
    }

    private fun lines(s: String): List<String> = if (s.isEmpty()) emptyList() else s.split('\n')

    // ------------------------------------------------------------------ inicializace

    /** Inicializace jadra v adresari `filesDir`. Vraci true, kdyz settings.json uz existoval. */
    fun initCore(filesDir: String): Boolean {
        val existed = call(OP_INIT, filesDir) == "1"
        refreshSettings()
        return existed
    }

    /**
     * Idempotentni start: nacte knihovnu, inicializuje jadro, obnovi nastaveni ze zalohy
     * v SharedPreferences (kdyz settings.json chybi) a na pozadi nacte slovnik.
     */
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        appContext = app
        if (!load()) return
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existed = initCore(app.filesDir.absolutePath)
        if (!existed) {
            prefs.getString(PREF_SETTINGS, null)?.let { call(OP_SET_SETTINGS, it) }
            refreshSettings()
        }
        prefs.edit().putString(PREF_SETTINGS, settings.json.toString()).apply()
        initialized = true
        loadDictionaryAsync(app)
    }

    /** Slovnik se kopiruje z assets a nacita mimo UI vlakno - psani funguje hned. */
    private fun loadDictionaryAsync(app: Context) {
        if (dictLoading) return
        dictLoading = true
        Thread({
            try {
                val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val target = File(app.filesDir, DICT_ASSET)
                val stamp = try {
                    app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime.toString()
                } catch (_: Throwable) {
                    "0"
                }
                if (!target.isFile || prefs.getString(PREF_DICT_STAMP, null) != stamp) {
                    val tmp = File(app.filesDir, "$DICT_ASSET.tmp")
                    app.assets.open(DICT_ASSET).use { input ->
                        tmp.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
                    }
                    if (target.exists()) target.delete()
                    tmp.renameTo(target)
                    prefs.edit().putString(PREF_DICT_STAMP, stamp).apply()
                }
                loadDictionary(target.absolutePath)
            } catch (_: Throwable) {
                // Bez slovniku funguje tap psani, jen bez navrhu a swipe.
            } finally {
                dictLoading = false
                mainHandler.post { notifyListeners() }
            }
        }, "reallyfast-dictionary").start()
    }

    fun loadDictionary(path: String): Boolean = call(OP_LOAD_DICT, path) == "1"

    private fun notifyListeners() {
        for (l in listeners) l()
    }

    // ------------------------------------------------------------------ nastaveni

    private fun refreshSettings() {
        val json = call(OP_GET_SETTINGS)
        settings = Settings(try {
            JSONObject(json)
        } catch (_: Throwable) {
            JSONObject()
        })
    }

    private fun settingsChanged(json: String) {
        if (json.isEmpty()) return
        settings = Settings(try {
            JSONObject(json)
        } catch (_: Throwable) {
            return
        })
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putString(PREF_SETTINGS, json)?.apply()
        if (Looper.myLooper() == Looper.getMainLooper()) notifyListeners() else mainHandler.post { notifyListeners() }
    }

    /** Zapise nastaveni do settings.json (pres Rust, vcetne validace) + zaloha do SharedPreferences. */
    fun setSettings(json: JSONObject) = settingsChanged(call(OP_SET_SETTINGS, json.toString()))

    fun cycleLanguage() = settingsChanged(call(OP_CYCLE_LANGUAGE))

    fun pinch(scale: Float) = settingsChanged(call(OP_PINCH, scale.toString()))

    // ------------------------------------------------------------------ rozlozeni a psani

    /** Geometrie klaves jako JSON. `preview` = nahled v nastaveni (neovlivni swipe decoder). */
    fun layout(width: Int, height: Int, mode: Int, preview: Boolean = false): String =
        call(OP_LAYOUT, "$width,$height,$mode", null, if (preview) 1 else 0)

    /** Swipe decode: `points` = trojice (x, y, t), `count` = pocet platnych floatu. Prazdne = tap. */
    fun swipe(points: FloatArray, count: Int, capitalize: Boolean): List<String> {
        if (!loaded) return emptyList()
        return try {
            lines(nativeSwipe(points, count, if (capitalize) FLAG_CAPITALIZE else 0)?.toString(Charsets.UTF_8) ?: "")
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * Navrhy: pro rozepsane slovo doplneni/opravy, pro prazdne `composing` predikce dalsiho slova.
     * `textBefore` = text pred kurzorem (bez rozepsaneho slova); Rust z nej vezme kontext dvou slov.
     */
    fun suggest(composing: String, textBefore: String, capitalize: Boolean): List<String> =
        lines(call(OP_SUGGEST, composing, textBefore, if (capitalize) FLAG_CAPITALIZE.toLong() else 0))

    /**
     * Nauci se potvrzene slovo. `textBefore` = text pred slovem (kontext pro bigramy a trigramy),
     * `fromSwipe` = slovo vzniklo poslednim swipem (pouzije se k prizpusobeni swipe).
     */
    fun learn(word: String, textBefore: String, fromSwipe: Boolean): Boolean =
        call(OP_LEARN, word, textBefore, if (fromSwipe) FLAG_FROM_SWIPE.toLong() else 0) == "1"

    /** Uceni z oprav: po swipe bylo `bad` nahrazeno slovem `good`, nebo smazano (`good` prazdne). */
    fun correction(bad: String, good: String) {
        call(OP_CORRECTION, bad, good)
    }

    /** Zapomene prizpusobeni swipe (posuny stredu klaves). */
    fun resetCalibration() {
        call(OP_CALIBRATION_RESET)
    }

    // ------------------------------------------------------------------ makra

    data class Macro(val id: Long, val name: String, val text: String, val shortcut: String)

    fun macros(): List<Macro> = try {
        val arr = JSONArray(call(OP_MACRO_LIST))
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Macro(o.getLong("id"), o.getString("name"), o.getString("text"), o.getString("shortcut"))
        }
    } catch (_: Throwable) {
        emptyList()
    }

    /** Ulozi makro (id 0 = nove). Vraci false, kdyz je text prazdny nebo je maker prilis mnoho. */
    fun saveMacro(id: Long, name: String, text: String, shortcut: String): Boolean {
        val o = JSONObject()
        o.put("id", id)
        o.put("name", name)
        o.put("text", text)
        o.put("shortcut", shortcut)
        return call(OP_MACRO_SAVE, o.toString()) == "1"
    }

    fun deleteMacro(id: Long) {
        call(OP_MACRO_DELETE, null, null, id)
    }

    /** Text makra pro napsanou zkratku; prazdny retezec, kdyz zadna nesedi. */
    fun expandShortcut(token: String): String = call(OP_SHORTCUT, token)

    // ------------------------------------------------------------------ hlasove povely

    data class VoiceCommand(val id: Long, val phrase: String, val action: String, val text: String)

    /** Usek nadiktovaneho textu: `action` prazdna = obycejny text, jinak povel. */
    data class VoiceSegment(val action: String, val text: String)

    fun voiceCommands(): List<VoiceCommand> = try {
        val arr = JSONArray(call(OP_VOICE_LIST))
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            VoiceCommand(o.getLong("id"), o.getString("phrase"), o.getString("action"), o.getString("text"))
        }
    } catch (_: Throwable) {
        emptyList()
    }

    /** Ulozi povel (id 0 = novy). Vraci false pri neplatne fraze / akci nebo prilis mnoha povelech. */
    fun saveVoiceCommand(id: Long, phrase: String, action: String, text: String): Boolean {
        val o = JSONObject()
        o.put("id", id)
        o.put("phrase", phrase)
        o.put("action", action)
        o.put("text", text)
        return call(OP_VOICE_SAVE, o.toString()) == "1"
    }

    fun deleteVoiceCommand(id: Long) {
        call(OP_VOICE_DELETE, null, null, id)
    }

    /** Rozdeli nadiktovany text na obycejne useky a povely; pri selhani vrati cely text jako jeden usek. */
    fun voiceSplit(spoken: String): List<VoiceSegment> = try {
        val arr = JSONArray(call(OP_VOICE_SPLIT, spoken))
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            VoiceSegment(o.getString("action"), o.getString("text"))
        }
    } catch (_: Throwable) {
        listOf(VoiceSegment("", spoken))
    }

    fun gesture(startKind: Int, dx: Float, dy: Float, dtMs: Float, keyW: Float, keyH: Float): Int =
        call(OP_GESTURE, "$startKind,$dx,$dy,$dtMs,$keyW,$keyH").toIntOrNull() ?: ACTION_NONE

    fun cursorSteps(dx: Float, keyW: Float): Int = call(OP_CURSOR_STEPS, "$dx,$keyW").toIntOrNull() ?: 0

    fun deleteWordLen(before: String): Int = call(OP_DELETE_WORD_LEN, before).toIntOrNull() ?: 0

    fun wordJumpLen(text: String, forward: Boolean): Int =
        call(OP_WORD_JUMP_LEN, text, null, if (forward) 1 else 0).toIntOrNull() ?: 0

    fun shouldCapitalize(before: String): Boolean = call(OP_SHOULD_CAPITALIZE, before) == "1"

    fun doubleSpacePeriod(before: String): Boolean = call(OP_DOUBLE_SPACE, before) == "1"

    fun shiftTap(state: Int, sinceLastTapMs: Long): Int =
        call(OP_SHIFT_TAP, state.toString(), null, sinceLastTapMs).toIntOrNull() ?: 0

    fun lastWord(before: String): String = call(OP_LAST_WORD, before)

    // ------------------------------------------------------------------ osobni slovnik

    data class UserWord(val word: String, val count: Int, val lastUsed: Long)

    fun userWords(): List<UserWord> = try {
        val arr = JSONArray(call(OP_USER_WORDS))
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            UserWord(o.getString("word"), o.getInt("count"), o.getLong("last_used"))
        }
    } catch (_: Throwable) {
        emptyList()
    }

    /** Rucni pridani slova do osobniho slovniku. */
    fun addUserWord(word: String): Boolean = call(OP_USER_ADD, word) == "1"

    fun deleteUserWord(word: String) {
        call(OP_USER_DELETE, word)
    }

    fun exportUserDictionary(): String = call(OP_USER_EXPORT)

    /** Vraci pocet importovanych slov, -1 pri neplatnem souboru. */
    fun importUserDictionary(json: String): Int = call(OP_USER_IMPORT, json).toIntOrNull() ?: -1

    fun clearUserDictionary() {
        call(OP_USER_CLEAR)
    }

    // ------------------------------------------------------------------ zaloha a zaznam tahu

    /** Uplna zaloha (nastaveni, slovnik, opravy, makra, hlasove povely, prizpusobeni swipe) jako JSON text. */
    fun exportBackup(): String = call(OP_BACKUP_EXPORT)

    /**
     * Obnova ze zalohy. Vraci pocet prijatych slov slovniku, -1 pri neplatnem souboru.
     * Po uspechu se zmena nastaveni oznami stejne jako po setSettings.
     */
    fun importBackup(json: String): Int {
        val n = call(OP_BACKUP_IMPORT, json).toIntOrNull() ?: -1
        if (n >= 0) settingsChanged(call(OP_GET_SETTINGS))
        return n
    }

    /** Zaznamenane tahy (jen kdyz je zapnuty swipe_log) jako JSON pro nastroj tune. */
    fun exportSwipeLog(): String = call(OP_SWIPE_LOG_EXPORT)

    fun clearSwipeLog() {
        call(OP_SWIPE_LOG_CLEAR)
    }

    fun swipeLogCount(): Int = call(OP_SWIPE_LOG_COUNT).toIntOrNull() ?: 0

    // ------------------------------------------------------------------ schranka

    data class ClipItem(val id: Long, val text: String, val created: Long, val pinned: Boolean)

    /** Vraci false, kdyz se text neulozil (vypada jako heslo, je prazdny, historie je vypnuta). */
    fun clipAdd(text: String): Boolean = call(OP_CLIP_ADD, text) == "1"

    fun clipList(): List<ClipItem> = try {
        val arr = JSONArray(call(OP_CLIP_LIST))
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            ClipItem(o.getLong("id"), o.getString("text"), o.getLong("created"), o.getBoolean("pinned"))
        }
    } catch (_: Throwable) {
        emptyList()
    }

    fun clipPin(id: Long, pinned: Boolean) {
        call(OP_CLIP_PIN, if (pinned) "1" else "0", null, id)
    }

    fun clipDelete(id: Long) {
        call(OP_CLIP_DELETE, null, null, id)
    }

    fun clipClear() {
        call(OP_CLIP_CLEAR)
    }

    // ------------------------------------------------------------------ emoji

    /** Kategorie: 0 nedavne, 1 smajlici, 2 zvirata, 3 jidlo, 4 aktivity, 5 symboly. */
    fun emojiCategory(category: Int): List<String> = lines(call(OP_EMOJI_CATEGORY, null, null, category.toLong()))

    fun emojiSearch(query: String): List<String> = lines(call(OP_EMOJI_SEARCH, query))

    fun emojiUsed(emoji: String) {
        call(OP_EMOJI_USED, emoji)
    }

    fun info(): String = call(OP_INFO)
}
