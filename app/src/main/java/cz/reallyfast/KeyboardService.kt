package cz.reallyfast

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * fAZT! - InputMethodService. Tenka slupka: zivotni cyklus, InputConnection, haptika, zvuk.
 * Rozhodovani (navrhy, swipe, uceni, gesta, velka pismena...) dela Rust pres RustBridge.
 *
 * Zadne pozadi: zadny polling, zadne wake locky, zadne vlastni sluzby ani sit.
 */
class KeyboardService : InputMethodService(), KeyboardView.Listener,
    SuggestionBar.Callbacks, EmojiPanel.Callbacks, ClipboardPanel.Callbacks {

    private companion object {
        const val BACKEND_ON_DEVICE = 0
        const val BACKEND_GOOGLE = 1
        const val BACKEND_DEFAULT = 2
        const val GOOGLE_APP = "com.google.android.googlequicksearchbox"
        const val GOOGLE_RECOGNIZER = "com.google.android.voicesearch.serviceapi.GoogleRecognitionService"
        const val REATTACH_MAX = 48
        const val REATTACH_GRACE_MS = 400L
    }

    /** Posledni slovo vlozene swipem - lze ho nahradit alternativou nebo smazat jednim backspace. */
    private class SwipeCommit(var text: String, var word: String, val before: String)

    private var root: LinearLayout? = null
    private var bar: SuggestionBar? = null
    private var content: FrameLayout? = null
    private var keyboard: KeyboardView? = null
    private var emojiPanel: EmojiPanel? = null
    private var clipPanel: ClipboardPanel? = null
    private var theme: Theme = Theme.from(RustBridge.settings)

    private val composing = StringBuilder()
    private var lastSwipe: SwipeCommit? = null
    private var autoSpace = false
    private var shift = KeyboardView.SHIFT_OFF
    private var lastShiftTap = 0L
    private var passwordField = false
    private var noLearning = false
    private var useComposing = true

    /** Slovo znovu pripojene k rozepsanemu po smazani zpet do nej (nenauci se podruhe, kdyz se nezmenilo). */
    private var reattached: String? = null
    private var reattachAt = 0L
    private var reattachEnd = -1
    private var searchMode = false
    private var addWordMode = false

    /** Oprava slova ze swipe (dvojklep na slovo v liste): puvodni vlozeni, ktere se prepise. */
    private var editWordMode = false
    private var editTarget: SwipeCommit? = null
    private var lastWordTapAt = 0L
    private var lastWordTapIndex = -1
    private val searchQuery = StringBuilder()

    private val handler = Handler(Looper.getMainLooper())
    private val suggestionUpdate = Runnable { refreshSuggestions() }
    private val settingsListener: () -> Unit = { applySettings() }
    private var clipboard: ClipboardManager? = null
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { captureClipboard() }
    private var vibrator: Vibrator? = null
    private var audio: AudioManager? = null

    private val settings: Settings get() = RustBridge.settings

    // ------------------------------------------------------------------ zivotni cyklus

    override fun onCreate() {
        super.onCreate()
        RustBridge.init(this)
        RustBridge.listeners.add(settingsListener)
        clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.addPrimaryClipChangedListener(clipListener)
        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        }
        audio = getSystemService(AUDIO_SERVICE) as? AudioManager
    }

    override fun onDestroy() {
        try {
            recognizer?.destroy()
        } catch (_: Throwable) {
            // sluzba rozpoznavani uz nemusi existovat
        }
        recognizer = null
        RustBridge.listeners.remove(settingsListener)
        clipboard?.removePrimaryClipChangedListener(clipListener)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private val barHeight: Int get() = (44f * resources.displayMetrics.density + 0.5f).toInt()

    /** Vyska plochy klaves: radky * key_height_dp, nejvyse 50 % obrazovky. */
    private fun keysHeight(): Int = keyboardKeysHeight(resources, settings, barHeight)

    override fun onCreateInputView(): View {
        theme = Theme.from(settings)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(theme.backgroundBottom)
        // Bublina nad klavesou a nabidka variant smi presahnout z plochy klaves nad listu navrhu.
        root.clipChildren = false
        root.clipToPadding = false

        val bar = SuggestionBar(this, theme, this)
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, barHeight))

        val content = FrameLayout(this)
        content.clipChildren = false
        val keyboard = KeyboardView(this)
        keyboard.listener = this
        keyboard.theme = theme
        keyboard.desiredHeight = keysHeight()
        keyboard.shiftState = shift
        content.addView(keyboard, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Edge-to-edge: kdyz system necha okno klavesnice zasahovat pod gesto lista One UI,
        // odsadime obsah o vysku navigacni listy (jinak je inset 0 a nic se nemeni).
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bottom = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
            v.setPadding(0, 0, 0, bottom)
            insets
        }

        this.root = root
        this.bar = bar
        this.content = content
        this.keyboard = keyboard
        emojiPanel = null
        clipPanel = null
        updateBarVisibility()
        return root
    }

    /** Okamzita zmena tematu / velikosti / jazyka bez restartu klavesnice. */
    private fun applySettings() {
        theme = Theme.from(settings)
        root?.setBackgroundColor(theme.backgroundBottom)
        bar?.applyTheme(theme)
        keyboard?.let {
            it.theme = theme
            it.desiredHeight = keysHeight()
            it.reloadLayout()
        }
        // Panely se vytvori znovu s novym tematem pri pristim otevreni.
        showKeyboard()
        emojiPanel?.let { content?.removeView(it) }
        clipPanel?.let { content?.removeView(it) }
        emojiPanel = null
        clipPanel = null
        updateBarVisibility()
        applyWindowColors()
        scheduleSuggestions()
    }

    private fun updateBarVisibility() {
        val s = settings
        val visible = s.showSuggestions || s.clipboardEnabled || searchMode
        bar?.visibility = if (visible) View.VISIBLE else View.GONE
        bar?.setClipboardVisible(s.clipboardEnabled)
        keyboard?.overflowTop = if (visible) barHeight else 0
    }

    private fun applyWindowColors() {
        try {
            val w = window?.window ?: return
            @Suppress("DEPRECATION")
            w.navigationBarColor = theme.backgroundBottom
            // Svetle tema -> tmave ikony v navigacni liste (a naopak).
            if (Build.VERSION.SDK_INT >= 30) {
                val flag = android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                w.insetsController?.setSystemBarsAppearance(if (theme.isLight) flag else 0, flag)
            } else if (Build.VERSION.SDK_INT >= 26) {
                @Suppress("DEPRECATION")
                val flag = View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                @Suppress("DEPRECATION")
                w.decorView.systemUiVisibility =
                    if (theme.isLight) w.decorView.systemUiVisibility or flag else w.decorView.systemUiVisibility and flag.inv()
            }
        } catch (_: Throwable) {
            // jen kosmetika
        }
    }

    /** Krajina: zadny fullscreen "extract" rezim, klavesnice zustava dole. */
    override fun onEvaluateFullscreenMode(): Boolean = false

    @SuppressLint("InlinedApi") // IME_FLAG_NO_PERSONALIZED_LEARNING je konstanta int, na API 24 se jen vlozi hodnota
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val type = info?.inputType ?: 0
        val cls = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        passwordField = (cls == InputType.TYPE_CLASS_TEXT && (
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        // Hesla a pole s "no personalized learning" (inkognito) se nikdy neuci.
        noLearning = passwordField ||
            ((info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        useComposing = cls == InputType.TYPE_CLASS_TEXT && !passwordField

        if (!restarting) {
            composing.setLength(0)
            reattached = null
            lastSwipe = null
            autoSpace = false
            shift = KeyboardView.SHIFT_OFF
        }
        closeSearch(false)
        showKeyboard()
        keyboard?.let {
            it.swipeAllowed = !passwordField
            it.popupAllowed = !passwordField
            it.voiceAllowed = !passwordField
            val options = info?.imeOptions ?: 0
            it.enterAction = if (options and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) KeyboardView.ENTER_DEFAULT else {
                when (options and EditorInfo.IME_MASK_ACTION) {
                    EditorInfo.IME_ACTION_SEARCH -> KeyboardView.ENTER_SEARCH
                    EditorInfo.IME_ACTION_SEND -> KeyboardView.ENTER_SEND
                    EditorInfo.IME_ACTION_NEXT, EditorInfo.IME_ACTION_GO -> KeyboardView.ENTER_NEXT
                    EditorInfo.IME_ACTION_DONE -> KeyboardView.ENTER_DONE
                    else -> KeyboardView.ENTER_DEFAULT
                }
            }
            it.desiredHeight = keysHeight()
            val numeric = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME
            it.setMode(if (numeric) KeyboardView.MODE_SYMBOLS else KeyboardView.MODE_LETTERS)
            it.reloadLayout()
        }
        updateBarVisibility()
        applyWindowColors()
        updateShift()
        scheduleSuggestions()
    }

    /**
     * Pri kazdem zobrazeni znovu zaregistruje geometrii klaves pro swipe decoder (jadro je sdilene
     * s nastavenim; zkusebni rezim nahledu v nastaveni tam muze mit zapamatovanou svoji geometrii).
     */
    override fun onWindowShown() {
        super.onWindowShown()
        keyboard?.reloadLayout()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        cancelDictation()
        flushSwipeLearning()
        composing.setLength(0)
        closeSearch(false)
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // Uzivatel presunul kurzor mimo rozepsane slovo (nebo ho aplikace zrusila): ukoncit skladani.
        // Opozdena zprava po mazani (kandidati jeste nejsou nastaveni) nesmi zrusit prave pripojene slovo.
        val staleAfterReattach = candidatesStart == -1 && newSelStart == reattachEnd && newSelEnd == reattachEnd &&
            SystemClock.uptimeMillis() - reattachAt < REATTACH_GRACE_MS
        if (composing.isNotEmpty() && !staleAfterReattach &&
            (candidatesStart == -1 || newSelStart != candidatesEnd || newSelEnd != candidatesEnd)) {
            composing.setLength(0)
            reattached = null
            currentInputConnection?.finishComposingText()
        }
        if (composing.isEmpty() && SystemClock.uptimeMillis() - lastShiftTap > 600) updateShift()
        scheduleSuggestions()
    }

    // ------------------------------------------------------------------ odezva

    override fun onPressFeedback(key: KeyboardView.Key) {
        val s = settings
        if (s.vibrationMs > 0) vibrate(s.vibrationMs.toLong())
        if (s.soundEnabled) {
            val fx = when (key.kind) {
                KeyboardView.KIND_BACKSPACE -> AudioManager.FX_KEYPRESS_DELETE
                KeyboardView.KIND_SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                KeyboardView.KIND_ENTER -> AudioManager.FX_KEYPRESS_RETURN
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
            try {
                audio?.playSoundEffect(fx, 0.6f)
            } catch (_: Throwable) {
                // zvuk neni kriticky
            }
        }
    }

    /**
     * Haptika pres Vibrator s USAGE_TOUCH: system (One UI) na ni uplatni vlastni nastaveni
     * intenzity dotykove odezvy, vcetne jejiho uplneho vypnuti.
     */
    private fun vibrate(ms: Long) {
        val v = vibrator ?: return
        try {
            when {
                Build.VERSION.SDK_INT >= 33 -> v.vibrate(
                    VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE),
                    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH),
                )
                Build.VERSION.SDK_INT >= 26 -> @Suppress("DEPRECATION") v.vibrate(
                    VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                else -> @Suppress("DEPRECATION") v.vibrate(ms)
            }
        } catch (_: Throwable) {
            // bez vibrace
        }
    }

    /** Potvrzeni ulozeni slova: dve kratke vibrace (i kdyz je vibrace klaves vypnuta). */
    private fun vibrateSaved() {
        val v = vibrator ?: return
        try {
            val pattern = longArrayOf(0, 25, 70, 25)
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION") v.vibrate(pattern, -1)
            }
        } catch (_: Throwable) {
            // bez vibrace
        }
    }

    // ------------------------------------------------------------------ klavesy

    override fun onKeyTap(key: KeyboardView.Key) {
        if (dictating) cancelDictation() // psani behem diktovani poslech ukonci
        when (key.kind) {
            KeyboardView.KIND_CHAR -> {
                val macro = key.macro
                if (macro != null && !searchMode) insertMacro(macro) else typeText(String(Character.toChars(key.code)))
            }
            KeyboardView.KIND_SHIFT -> {
                val now = SystemClock.uptimeMillis()
                setShift(RustBridge.shiftTap(shift, now - lastShiftTap))
                lastShiftTap = now
            }
            KeyboardView.KIND_BACKSPACE -> backspace()
            KeyboardView.KIND_MODE -> keyboard?.setMode(
                when (key.code) {
                    KeyboardView.CODE_SYMBOLS -> KeyboardView.MODE_SYMBOLS
                    KeyboardView.CODE_SYMBOLS2 -> KeyboardView.MODE_SYMBOLS2
                    else -> KeyboardView.MODE_LETTERS
                }
            )
            KeyboardView.KIND_ENTER -> enter()
            KeyboardView.KIND_SPACE -> space()
            KeyboardView.KIND_MACRO -> showMacroMenu(key)
            KeyboardView.KIND_EMOJI -> showEmoji()
        }
    }

    override fun onAltText(text: String) = typeText(text, alreadyCased = true)

    override fun onSpaceLongPress() {
        onPressFeedback(keyboardSpaceKey)
        RustBridge.cycleLanguage()
    }

    private val keyboardSpaceKey = KeyboardView.Key(0f, 0f, 0f, 0f, 0f, 0f, 32, KeyboardView.KIND_SPACE, "", "", "")
    private val keyboardBackspaceKey = KeyboardView.Key(0f, 0f, 0f, 0f, 0f, 0f, -2, KeyboardView.KIND_BACKSPACE, "", "", "")

    private fun setShift(state: Int) {
        shift = state
        keyboard?.shiftState = state
    }

    private fun isWordChar(text: String): Boolean {
        if (text.isEmpty()) return false
        val cp = text.codePointAt(0)
        if (Character.charCount(cp) != text.length) return false
        return Character.isLetter(cp) || (cp == '\''.code && composing.isNotEmpty())
    }

    private fun typeText(text: String, alreadyCased: Boolean = false) {
        if (searchMode) {
            searchQuery.append(if (!alreadyCased && shift != KeyboardView.SHIFT_OFF) text.uppercase() else text)
            if (shift == KeyboardView.SHIFT_ONCE) setShift(KeyboardView.SHIFT_OFF)
            updateSearch()
            return
        }
        val ic = currentInputConnection ?: return
        flushSwipeLearning()
        val out = if (!alreadyCased && shift != KeyboardView.SHIFT_OFF) text.uppercase() else text
        if (useComposing && isWordChar(out)) {
            if (composing.isEmpty()) reattached = null
            composing.append(out)
            ic.setComposingText(composing, 1)
            autoSpace = false
            if (shift == KeyboardView.SHIFT_ONCE) setShift(KeyboardView.SHIFT_OFF)
        } else {
            val punctuation = out.length == 1 && out[0] in ".,!?:;"
            ic.beginBatchEdit()
            commitComposing(ic)
            // Po automaticke mezere (swipe / navrh) patri interpunkce tesne za slovo.
            if (punctuation && autoSpace && ic.getTextBeforeCursor(1, 0)?.toString() == " ") {
                ic.deleteSurroundingText(1, 0)
            }
            ic.commitText(out, 1)
            ic.endBatchEdit()
            autoSpace = false
            if (shift == KeyboardView.SHIFT_ONCE) setShift(KeyboardView.SHIFT_OFF)
            updateShift()
        }
        scheduleSuggestions()
    }

    /** Potvrdi rozepsane slovo (a nauci se ho). */
    private fun commitComposing(ic: InputConnection) {
        if (composing.isEmpty()) return
        val word = composing.toString()
        composing.setLength(0)
        val unchanged = word == reattached
        reattached = null
        val before = ic.getTextBeforeCursor(64, 0)?.toString()?.removeSuffix(word) ?: ""
        ic.finishComposingText()
        if (!unchanged) learn(word, before)
    }

    private fun learn(word: String, textBefore: String) {
        if (noLearning) return
        RustBridge.learn(word, textBefore, false)
    }

    private fun flushSwipeLearning() {
        if (editWordMode) return // slovo se prave opravuje, nebylo to potvrzene
        val ls = lastSwipe ?: return
        lastSwipe = null
        if (!noLearning) RustBridge.learn(ls.word, ls.before, true)
    }

    /** Pred dekodovanim dalsiho swipe: predchozi slovo je potvrzene (a jeho draha jeste v pameti). */
    override fun onBeforeSwipe() = flushSwipeLearning()

    private fun space() {
        if (searchMode) {
            searchQuery.append(' ')
            updateSearch()
            return
        }
        val ic = currentInputConnection ?: return
        flushSwipeLearning()
        if (expandShortcut(ic)) {
            autoSpace = true
            updateShift()
            scheduleSuggestions()
            return
        }
        ic.beginBatchEdit()
        if (composing.isNotEmpty()) {
            commitComposing(ic)
            ic.commitText(" ", 1)
        } else if (autoSpace) {
            // Mezera uz byla vlozena automaticky za slovem ze swipe / navrhu.
        } else {
            val before = ic.getTextBeforeCursor(2, 0)?.toString() ?: ""
            if (settings.doubleSpaceToPeriod && useComposing && RustBridge.doubleSpacePeriod(before)) {
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
            } else {
                ic.commitText(" ", 1)
            }
        }
        ic.endBatchEdit()
        autoSpace = false
        if (keyboard?.mode != KeyboardView.MODE_LETTERS && useComposing) keyboard?.setMode(KeyboardView.MODE_LETTERS)
        updateShift()
        scheduleSuggestions()
    }

    /**
     * Zkratka makra: kdyz posledni "slovo" pred kurzorem (i rozepsane) odpovida zkratce,
     * nahradi se textem makra. Vraci true, kdyz doslo k nahrade.
     */
    private fun expandShortcut(ic: InputConnection): Boolean {
        if (passwordField || autoSpace) return false
        val before = ic.getTextBeforeCursor(24, 0)?.toString() ?: return false
        val token = before.takeLastWhile { !it.isWhitespace() }
        if (token.isEmpty() || token.length == before.length && before.length == 24) return false
        val text = RustBridge.expandShortcut(token)
        if (text.isEmpty()) return false
        ic.beginBatchEdit()
        composing.setLength(0)
        ic.finishComposingText()
        ic.deleteSurroundingText(token.length, 0)
        ic.commitText(fillPlaceholders(text) + " ", 1)
        ic.endBatchEdit()
        RustBridge.macros().firstOrNull { it.shortcut.isNotEmpty() && it.shortcut.equals(token, ignoreCase = true) }?.let {
            if (Lantern.breathe(this, it.name, it.text, it.shortcut)) RustBridge.deleteMacro(it.id)
        }
        return true
    }

    /** {datum} / {date} a {cas} / {time} v textu makra se nahradi aktualnim datem a casem. */
    private fun fillPlaceholders(text: String): String {
        if (text.indexOf('{') < 0) return text
        val now = java.util.Date()
        val date = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(now)
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(now)
        return text.replace("{datum}", date).replace("{date}", date).replace("{cas}", time).replace("{time}", time)
    }

    // ------------------------------------------------------------------ makra

    private var macroMenu: List<RustBridge.Macro> = emptyList()

    /** Tlacitko maker: nabidka ulozenych maker + polozka pro jejich spravu. */
    private fun showMacroMenu(key: KeyboardView.Key) {
        val kv = keyboard ?: return
        macroMenu = RustBridge.macros().take(6)
        val labels = macroMenu.map { it.name } + getString(R.string.macros_edit)
        val icons = IntArray(labels.size) { if (it == labels.size - 1) KeyboardView.ICON_GEAR else KeyboardView.ICON_BOLT }
        kv.showMenu(key, labels, icons, KeyboardView.MENU_KIND_MACRO)
    }

    private fun insertMacro(m: RustBridge.Macro) {
        val ic = currentInputConnection ?: return
        flushSwipeLearning()
        ic.beginBatchEdit()
        commitComposing(ic)
        ic.commitText(fillPlaceholders(m.text), 1)
        ic.endBatchEdit()
        if (Lantern.breathe(this, m.name, m.text, m.shortcut)) RustBridge.deleteMacro(m.id)
        autoSpace = false
        updateShift()
        scheduleSuggestions()
    }

    private fun openScreen(cls: Class<*>) {
        try {
            startActivity(Intent(this, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            requestHideSelf(0)
        } catch (_: Throwable) {
            // obrazovku se nepodarilo otevrit - klavesnice bezi dal
        }
    }

    private fun backspace() {
        if (searchMode) {
            if (searchQuery.isNotEmpty()) searchQuery.setLength(searchQuery.offsetByCodePoints(searchQuery.length, -1))
            updateSearch()
            return
        }
        val ic = currentInputConnection ?: return
        var wholeWord = false
        if (composing.isNotEmpty()) {
            composing.setLength(composing.offsetByCodePoints(composing.length, -1))
            ic.setComposingText(composing, 1)
        } else {
            val ls = lastSwipe
            lastSwipe = null
            if (ls != null && ic.getTextBeforeCursor(ls.text.length, 0)?.toString() == ls.text) {
                wholeWord = true
                // Backspace hned po swipe smaze cele vlozene slovo; klavesnice si zapamatuje,
                // ze tohle slovo nebylo to prave, a priste ho nabidne niz.
                ic.deleteSurroundingText(ls.text.length, 0)
                if (!noLearning) RustBridge.correction(ls.word, "")
            } else {
                if (ls != null && !noLearning) RustBridge.learn(ls.word, ls.before, true)
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            }
        }
        autoSpace = false
        if (!wholeWord) reattachWord(ic)
        updateShift()
        scheduleSuggestions()
    }

    /**
     * Po smazani zpet do slova (kurzor tesne za slovem, za kurzorem uz neni pismeno) se slovo znovu stane
     * rozepsanym: ukazou se k nemu navrhy a psani / mazani v nem pokracuje. Slova jsou posloupnosti pismen
     * s apostrofem uvnitr, nejvyse REATTACH_MAX znaku (delsi se nepripojuji, aby se nerezala v pulce).
     */
    private fun reattachWord(ic: InputConnection) {
        if (!useComposing || passwordField || composing.isNotEmpty()) return
        val before = ic.getTextBeforeCursor(REATTACH_MAX + 1, 0)?.toString() ?: return
        val after = ic.getTextAfterCursor(1, 0)?.toString() ?: return
        if (before.isEmpty() || !Character.isLetter(before.codePointBefore(before.length))) return
        if (after.isNotEmpty() && isWordCodePoint(after.codePointAt(0))) return
        var i = before.length
        while (i > 0) {
            val cp = before.codePointBefore(i)
            if (!isWordCodePoint(cp)) break
            i -= Character.charCount(cp)
        }
        while (i < before.length && before[i] == '\'') i++ // apostrof na zacatku slova k nemu nepatri
        val word = before.substring(i)
        if (word.isEmpty() || word.length > REATTACH_MAX) return
        val et = ic.getExtractedText(reattachRequest, 0) ?: return
        if (et.selectionStart != et.selectionEnd) return // vyber textu
        val end = et.startOffset + et.selectionEnd
        val start = end - word.length
        if (start < 0) return
        ic.beginBatchEdit()
        ic.setComposingRegion(start, end)
        ic.endBatchEdit()
        composing.append(word)
        reattached = word
        reattachAt = SystemClock.uptimeMillis()
        reattachEnd = end
    }

    private fun isWordCodePoint(cp: Int) = Character.isLetter(cp) || cp == '\''.code

    private val reattachRequest = ExtractedTextRequest().apply { hintMaxChars = 1 }

    private fun enter() {
        if (searchMode) {
            val query = searchQuery.toString().trim()
            val target = editTarget
            if (editWordMode && target != null) {
                finishEditWord(target, query)
            } else if (addWordMode && query.isNotEmpty()) {
                val ok = RustBridge.addUserWord(query)
                closeSearch(true)
                showAddWordResult(ok, query)
            } else {
                closeSearch(true)
            }
            return
        }
        val ic = currentInputConnection ?: return
        flushSwipeLearning()
        commitComposing(ic)
        val info = currentInputEditorInfo
        val options = info?.imeOptions ?: 0
        val action = options and EditorInfo.IME_MASK_ACTION
        val multiLine = ((info?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        val hasAction = (options and EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0 &&
            action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED
        when {
            hasAction -> ic.performEditorAction(action)
            multiLine -> ic.commitText("\n", 1)
            else -> sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
        autoSpace = false
        updateShift()
        scheduleSuggestions()
    }

    private fun needsSpaceBefore(ic: InputConnection): Boolean {
        val before = ic.getTextBeforeCursor(1, 0)?.toString() ?: ""
        if (before.isEmpty()) return false
        val c = before[0]
        return !c.isWhitespace() && c !in "([{\"'/-@#_"
    }

    // ------------------------------------------------------------------ swipe

    override fun onSwipeWords(words: List<String>) {
        if (words.isEmpty() || searchMode) return
        val ic = currentInputConnection ?: return
        flushSwipeLearning()
        ic.beginBatchEdit()
        commitComposing(ic)
        val before = ic.getTextBeforeCursor(64, 0)?.toString() ?: ""
        val word = words[0]
        val text = (if (needsSpaceBefore(ic)) " " else "") + word + " "
        ic.commitText(text, 1)
        ic.endBatchEdit()
        // Uceni se odklada: uzivatel muze slovo jeste nahradit alternativou nebo smazat.
        lastSwipe = SwipeCommit(text, word, before)
        keyboard?.glowWord(word)
        autoSpace = true
        if (shift == KeyboardView.SHIFT_ONCE) setShift(KeyboardView.SHIFT_OFF)
        swipeWords = words
        handler.removeCallbacks(suggestionUpdate)
        bar?.setSuggestions(words, 0)
    }

    // ------------------------------------------------------------------ navrhy

    private fun scheduleSuggestions() {
        handler.removeCallbacks(suggestionUpdate)
        handler.post(suggestionUpdate)
    }

    private fun refreshSuggestions() {
        val bar = bar ?: return
        if (searchMode || dictating) return
        if (lastSwipe != null) {
            // Po docasne hlasce (napr. "pridano do slovniku") vratit alternativy posledniho swipe.
            if (voiceStatus) {
                voiceStatus = false
                bar.setSuggestions(swipeWords, 0)
            }
            return
        }
        voiceStatus = false
        if (!settings.showSuggestions || passwordField || !useComposing) {
            bar.setSuggestions(emptyList())
            return
        }
        if (composing.isNotEmpty()) {
            bar.setSuggestions(RustBridge.suggest(composing.toString(), "", false))
        } else {
            val before = currentInputConnection?.getTextBeforeCursor(48, 0)?.toString() ?: ""
            bar.setSuggestions(RustBridge.suggest("", before, shift != KeyboardView.SHIFT_OFF))
        }
    }

    override fun onSuggestionPicked(index: Int, word: String) {
        if (dictating || voiceStatus) return // v liste je stav diktovani, ne navrh
        val ic = currentInputConnection ?: return
        onPressFeedback(keyboardSpaceKey)
        val ls = lastSwipe
        if (ls != null) {
            if (word == ls.word) {
                // Dvojklep na vlozene slovo = oprava slova.
                val now = SystemClock.uptimeMillis()
                if (lastWordTapIndex == index && now - lastWordTapAt <= 350) {
                    lastWordTapAt = 0L
                    startEditWord(ls)
                    return
                }
                lastWordTapAt = now
                lastWordTapIndex = index
            } else {
                lastWordTapAt = 0L
            }
            // Nahrada slova ze swipe jinou alternativou.
            if (word != ls.word && ic.getTextBeforeCursor(ls.text.length, 0)?.toString() == ls.text) {
                val newText = (if (ls.text.startsWith(" ")) " " else "") + word + " "
                ic.beginBatchEdit()
                ic.deleteSurroundingText(ls.text.length, 0)
                ic.commitText(newText, 1)
                ic.endBatchEdit()
                // Uceni z oprav: prvni navrh byl spatne, uzivatel vybral jiny.
                if (!noLearning) RustBridge.correction(ls.word, word)
                ls.text = newText
                ls.word = word
            }
            bar?.setSuggestions(swipeWords, index)
            return
        }
        ic.beginBatchEdit()
        val before: String
        if (composing.isNotEmpty()) {
            before = ic.getTextBeforeCursor(64, 0)?.toString()?.removeSuffix(composing.toString()) ?: ""
            composing.setLength(0)
            ic.commitText("$word ", 1) // nahradi rozepsane slovo
            if (word == reattached) {
                reattached = null
                ic.endBatchEdit()
                autoSpace = true
                if (shift == KeyboardView.SHIFT_ONCE) setShift(KeyboardView.SHIFT_OFF)
                updateShift()
                scheduleSuggestions()
                return // slovo uz je naucene, nezapocitat ho podruhe
            }
            reattached = null
        } else {
            before = ic.getTextBeforeCursor(64, 0)?.toString() ?: ""
            ic.commitText((if (needsSpaceBefore(ic)) " " else "") + word + " ", 1)
        }
        ic.endBatchEdit()
        learn(word, before)
        autoSpace = true
        if (shift == KeyboardView.SHIFT_ONCE) setShift(KeyboardView.SHIFT_OFF)
        updateShift()
        scheduleSuggestions()
    }

    /** Alternativy posledniho swipe (zobrazene v liste, dokud uzivatel nepise dal). */
    private var swipeWords: List<String> = emptyList()

    // ------------------------------------------------------------------ velka pismena

    private fun updateShift() {
        if (shift == KeyboardView.SHIFT_CAPS) return
        var want = KeyboardView.SHIFT_OFF
        val info = currentInputEditorInfo
        if (settings.autoCapitalize && useComposing && info != null && composing.isEmpty()) {
            val type = info.inputType
            val variation = type and InputType.TYPE_MASK_VARIATION
            val excluded = variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_URI
            if (!excluded) {
                val before = currentInputConnection?.getTextBeforeCursor(32, 0)?.toString() ?: ""
                want = when {
                    type and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS != 0 -> KeyboardView.SHIFT_ONCE
                    type and InputType.TYPE_TEXT_FLAG_CAP_WORDS != 0 &&
                        (before.isEmpty() || before.last().isWhitespace()) -> KeyboardView.SHIFT_ONCE
                    type and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES != 0 &&
                        RustBridge.shouldCapitalize(before) -> KeyboardView.SHIFT_ONCE
                    else -> KeyboardView.SHIFT_OFF
                }
            }
        }
        setShift(want)
    }

    // ------------------------------------------------------------------ gesta

    override fun onGestureAction(action: Int) {
        val ic = currentInputConnection ?: return
        when (action) {
            RustBridge.ACTION_DELETE_WORD -> {
                lastSwipe = null
                if (composing.isNotEmpty()) {
                    composing.setLength(0)
                    ic.setComposingText("", 1)
                } else {
                    deleteWordBeforeCursor(ic)
                }
                autoSpace = false
                updateShift()
                scheduleSuggestions()
            }
            RustBridge.ACTION_CAPS_ON -> setShift(KeyboardView.SHIFT_CAPS)
            RustBridge.ACTION_HIDE_KEYBOARD -> requestHideSelf(0)
            RustBridge.ACTION_CURSOR_WORD_LEFT, RustBridge.ACTION_CURSOR_WORD_RIGHT -> {
                flushSwipeLearning()
                commitComposing(ic)
                val forward = action == RustBridge.ACTION_CURSOR_WORD_RIGHT
                val text = (if (forward) ic.getTextAfterCursor(64, 0) else ic.getTextBeforeCursor(64, 0))?.toString() ?: ""
                val n = RustBridge.wordJumpLen(text, forward)
                if (n > 0) moveCursorBy(ic, if (forward) n else -n)
            }
        }
    }

    /** Smaze slovo pred kurzorem (bez rozepsaneho slova). */
    private fun deleteWordBeforeCursor(ic: InputConnection) {
        val n = RustBridge.deleteWordLen(ic.getTextBeforeCursor(64, 0)?.toString() ?: "")
        if (n > 0) ic.deleteSurroundingText(n, 0) else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    /** Pomaly tah po mezerniku: posun kurzoru po znacich. */
    override fun onCursorSteps(steps: Int) {
        val ic = currentInputConnection ?: return
        flushSwipeLearning()
        commitComposing(ic)
        autoSpace = false
        val code = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(min(abs(steps), 40)) { sendDownUpKeyEvents(code) }
    }

    private fun moveCursorBy(ic: InputConnection, deltaUtf16: Int) {
        val et = try {
            ic.getExtractedText(ExtractedTextRequest(), 0)
        } catch (_: Throwable) {
            null
        }
        if (et != null) {
            val pos = max(0, et.startOffset + et.selectionEnd + deltaUtf16)
            ic.setSelection(pos, pos)
        } else {
            val code = if (deltaUtf16 < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
            repeat(min(abs(deltaUtf16), 64)) { sendDownUpKeyEvents(code) }
        }
    }

    override fun onPinchScale(scale: Float) = RustBridge.pinch(scale)

    // ------------------------------------------------------------------ diktovani

    private var recognizer: SpeechRecognizer? = null
    private var recognizerBackend = -1
    private var dictating = false
    /** Relace diktovani bezi (od podrzeni Enteru, dokud uzivatel nezavre panel krizkem nebo se diktovani nezrusi). */
    private var voiceSession = false
    /** V liste navrhu je zobrazena hlaska diktovani (ne slova k vyberu). */
    private var voiceStatus = false
    private var dictationText = ""
    /** Diktovani je pozastavene: promluva skoncila, poslech nebezi a ceka na klepnuti na Play. */
    private var voicePaused = false
    private var busyRetried = false
    private var dictationPrefix = ""
    private var dictationCapitalize = false
    /** Ktera sluzba rozpoznavani se pouzije (index do voiceBackends()); pamatuje se ta, ktera fungovala. */
    private var backendIndex = -1
    private var backendAttempts = 0
    private val voiceTimeout = Runnable {
        if (dictating) {
            cancelDictation()
            showVoiceStatus(getString(R.string.voice_nothing), 1500)
        }
    }

    private val voicePrefs by lazy { getSharedPreferences("reallyfast_voice", MODE_PRIVATE) }

    /**
     * Dostupne sluzby rozpoznavani reci v poradi, v jakem se zkousi:
     * 1) rozpoznavani primo v zarizeni (Android 12+), 2) sluzba aplikace Google, 3) vychozi sluzba systemu.
     * Kdyz jedna selze (napr. nema stazenou cestinu), zkusi se dalsi.
     */
    private fun voiceBackends(): List<Int> {
        val list = ArrayList<Int>(3)
        try {
            if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) list.add(BACKEND_ON_DEVICE)
        } catch (_: Throwable) {
            // nektere systemy tu vyhazuji vyjimku - povazovat za nedostupne
        }
        try {
            packageManager.getPackageInfo(GOOGLE_APP, 0)
            list.add(BACKEND_GOOGLE)
        } catch (_: Throwable) {
            // aplikace Google neni nainstalovana
        }
        if (SpeechRecognizer.isRecognitionAvailable(this)) list.add(BACKEND_DEFAULT)
        return list
    }

    private fun showVoiceStatus(text: String, holdMs: Long = 0) {
        voiceStatus = true
        handler.removeCallbacks(suggestionUpdate)
        bar?.setStatus(text)
        keyboard?.voiceLabel = text
        if (holdMs > 0) handler.postDelayed(suggestionUpdate, holdMs)
    }

    private fun startListening(): Boolean {
        val backends = voiceBackends()
        if (backends.isEmpty()) return false
        if (backendIndex < 0) backendIndex = voicePrefs.getInt("backend", 0)
        if (backendIndex >= backends.size) backendIndex = 0
        val backend = backends[backendIndex]
        return try {
            if (recognizer == null || recognizerBackend != backend) {
                try {
                    recognizer?.destroy()
                } catch (_: Throwable) {
                    // stara sluzba uz nemusi existovat
                }
                val r = when {
                    backend == BACKEND_ON_DEVICE && Build.VERSION.SDK_INT >= 31 -> SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
                    backend == BACKEND_GOOGLE -> SpeechRecognizer.createSpeechRecognizer(this, ComponentName(GOOGLE_APP, GOOGLE_RECOGNIZER))
                    else -> SpeechRecognizer.createSpeechRecognizer(this)
                }
                r.setRecognitionListener(recognitionListener)
                recognizer = r
                recognizerBackend = backend
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (settings.language.startsWith("cs")) "cs-CZ" else "en-US")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            if (backend == BACKEND_ON_DEVICE) intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            recognizer?.startListening(intent)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Podrzeny Enter: prepis reci na text pres sluzbu rozpoznavani reci v telefonu.
     * Aplikace sama nema pristup k internetu; zvuk zpracovava zvolena sluzba systemu.
     */
    override fun onVoiceStart() {
        if (dictating || passwordField || !settings.voiceInput) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // Sluzba klavesnice o opravneni pozadat nemuze - otevre nastaveni, ktere se zepta.
            try {
                startActivity(
                    Intent(this, SettingsActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(EXTRA_REQUEST_MIC, true)
                )
            } catch (_: Throwable) {
                showVoiceStatus(getString(R.string.voice_permission), 2500)
            }
            return
        }
        val ic = currentInputConnection ?: return
        flushSwipeLearning()
        commitComposing(ic)
        closeSearch(false)
        showKeyboard()
        refreshDictationContext(ic)
        dictationText = ""
        backendAttempts = 0
        busyRetried = false
        voicePaused = false
        voiceSession = true
        dictating = true
        keyboard?.voiceLevel = 0f
        keyboard?.dictating = true
        showVoiceStatus(getString(R.string.voice_preparing))
        if (!startListening()) {
            endDictation()
            showVoiceStatus(getString(R.string.voice_unavailable), 3000)
        }
    }

    override fun onVoiceStop() {
        voiceSession = false
        if (!dictating) return
        if (voicePaused) {
            // Poslech nebezi, neni na co cekat: rovnou ukoncit.
            endDictation()
            voiceStatus = false
            updateShift()
            scheduleSuggestions()
            return
        }
        try {
            recognizer?.stopListening()
        } catch (_: Throwable) {
            cancelDictation()
            return
        }
        showVoiceStatus(getString(R.string.voice_processing))
        // Kdyby sluzba neodpovedela, nenechat klavesnici viset v rezimu diktovani.
        handler.removeCallbacks(voiceTimeout)
        handler.postDelayed(voiceTimeout, 7000)
    }

    /** Promluva skoncila: panel zustane otevreny, poslech se nespousti sam (zadne dalsi pipnuti), ceka na Play. */
    private fun pauseSession() {
        handler.removeCallbacks(voiceTimeout)
        dictationText = ""
        voicePaused = true
        keyboard?.voiceLevel = 0f
        keyboard?.voicePaused = true
        showVoiceStatus(getString(R.string.voice_paused))
    }

    /** Klepnuti na Play: znovu spusti poslech ve stejne relaci. */
    override fun onVoiceResume() {
        if (!dictating || !voicePaused) return
        voicePaused = false
        keyboard?.voicePaused = false
        continueSession()
    }

    /** Spusti poslech znovu (po Play, po zahozeni prepisu tlacitkem smazani nebo po jednorazovem BUSY). */
    private fun continueSession() {
        val ic = currentInputConnection
        dictationText = ""
        if (ic != null) refreshDictationContext(ic)
        keyboard?.voiceLevel = 0f
        showVoiceStatus(getString(R.string.voice_preparing))
        if (!startListening()) {
            voiceSession = false
            endDictation()
            showVoiceStatus(getString(R.string.voice_unavailable), 3000)
        }
    }

    /** Mezera pred diktovanym textem a velke pocatecni pismeno podle textu pred kurzorem. */
    private fun refreshDictationContext(ic: InputConnection) {
        val before = ic.getTextBeforeCursor(48, 0)?.toString() ?: ""
        dictationPrefix = if (needsSpaceBefore(ic)) " " else ""
        dictationCapitalize = settings.autoCapitalize && RustBridge.shouldCapitalize(before)
    }

    /**
     * Tlacitko smazani na obrazovce diktovani: je-li videt prubezny prepis, zahodi ho a poslech
     * zacne znovu; jinak smaze posledni slovo pred kurzorem.
     */
    override fun onVoiceDelete() {
        if (!dictating) return
        onPressFeedback(keyboardBackspaceKey)
        val ic = currentInputConnection ?: return
        if (dictationText.isNotBlank()) {
            try {
                recognizer?.cancel()
            } catch (_: Throwable) {
                // ignorovat
            }
            ic.setComposingText("", 1)
            ic.finishComposingText()
            continueSession()
            return
        }
        lastSwipe = null
        deleteWordBeforeCursor(ic)
        autoSpace = false
        refreshDictationContext(ic)
    }

    /** Posledni text vlozeny diktovanim (povel "smazat naposledy nadiktovane"). */
    private var lastDictated = ""

    /**
     * Vlozi nadiktovany text; hlasove povely v nem provede (smazat, novy radek, vlozit text...).
     * Vraci true, kdyz zazel povel "ukoncit diktovani".
     */
    private fun commitDictation(ic: InputConnection, text: String): Boolean {
        val segments = RustBridge.voiceSplit(text)
        if (segments.size <= 1 && segments.firstOrNull()?.action.isNullOrEmpty()) {
            val out = dictated(text) + " "
            ic.commitText(out, 1) // nahradi prubezny prepis
            lastDictated = out
            autoSpace = true
            return false
        }
        var stop = false
        var spaceAfter = false
        ic.beginBatchEdit()
        ic.commitText("", 1) // odstrani rozepsany prubezny prepis
        lastSwipe = null
        for (s in segments) {
            refreshDictationContext(ic)
            spaceAfter = false
            when (s.action) {
                "" -> {
                    val out = dictated(s.text) + " "
                    ic.commitText(out, 1)
                    lastDictated = out
                    spaceAfter = true
                }
                "delete_word" -> deleteWordBeforeCursor(ic)
                "delete_last" -> {
                    val n = lastDictated.length
                    if (n > 0 && ic.getTextBeforeCursor(n, 0)?.toString() == lastDictated) {
                        ic.deleteSurroundingText(n, 0)
                        lastDictated = ""
                    } else {
                        deleteWordBeforeCursor(ic)
                    }
                }
                "newline" -> {
                    if (ic.getTextBeforeCursor(1, 0)?.toString() == " ") ic.deleteSurroundingText(1, 0)
                    ic.commitText("\n", 1)
                }
                "enter" -> enter()
                "text" -> {
                    val t = s.text
                    var out = t
                    if (t.isNotEmpty() && t[0] in ".,!?:;)") {
                        if (ic.getTextBeforeCursor(1, 0)?.toString() == " ") ic.deleteSurroundingText(1, 0)
                    } else if (needsSpaceBefore(ic)) {
                        out = " $t"
                    }
                    ic.commitText("$out ", 1)
                    spaceAfter = true
                }
                "stop" -> stop = true
            }
        }
        ic.endBatchEdit()
        autoSpace = spaceAfter
        return stop
    }

    private fun dictated(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        val cased = if (dictationCapitalize) t.replaceFirstChar { it.uppercase() } else t
        return dictationPrefix + cased
    }

    private fun endDictation() {
        handler.removeCallbacks(voiceTimeout)
        dictating = false
        voicePaused = false
        keyboard?.voicePaused = false
        keyboard?.dictating = false
        keyboard?.voiceLevel = 0f
    }

    /** Okamzite ukonceni bez vysledku (psani, schovani klavesnice). Rozepsany prepis zustane v textu. */
    private fun cancelDictation() {
        if (!dictating) return
        voiceSession = false
        try {
            recognizer?.cancel()
        } catch (_: Throwable) {
            // ignorovat
        }
        currentInputConnection?.finishComposingText()
        endDictation()
        voiceStatus = false
        scheduleSuggestions()
    }

    private fun voiceErrorText(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> getString(R.string.voice_nothing)
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> getString(R.string.voice_permission)
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> getString(R.string.voice_error_network)
        12, 13 -> getString(R.string.voice_error_language) // jazyk neni podporovan / neni stazen
        else -> getString(R.string.voice_failed, error)
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (dictating) showVoiceStatus(getString(R.string.voice_listening))
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {
            // Hlasitost (cca -2 az 10 dB) -> 0..1; ridi velikost mikrofonu na klavesnici.
            keyboard?.voiceLevel = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            if (!dictating || voicePaused) return
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
            if (text.isBlank()) return
            dictationText = text
            currentInputConnection?.setComposingText(dictated(text), 1)
        }

        override fun onResults(results: Bundle?) {
            if (!dictating || voicePaused) return
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                ?.takeIf { it.isNotBlank() } ?: dictationText
            val ic = currentInputConnection
            busyRetried = false
            if (text.isBlank() || ic == null) {
                ic?.finishComposingText()
                if (voiceSession && ic != null) {
                    pauseSession()
                    return
                }
                voiceSession = false
                endDictation()
                showVoiceStatus(getString(R.string.voice_nothing), 1500)
                return
            }
            voicePrefs.edit().putInt("backend", backendIndex).apply() // tahle sluzba funguje
            if (commitDictation(ic, text)) voiceSession = false
            if (voiceSession) {
                pauseSession()
                return
            }
            endDictation()
            voiceStatus = false
            updateShift()
            scheduleSuggestions()
        }

        override fun onError(error: Int) {
            if (!dictating || voicePaused) return
            val ic = currentInputConnection
            if (dictationText.isNotBlank() && ic != null) {
                // Castecny prepis nezahazovat.
                if (commitDictation(ic, dictationText)) voiceSession = false
                if (voiceSession) {
                    pauseSession()
                    return
                }
                endDictation()
                voiceStatus = false
                updateShift()
                scheduleSuggestions()
                return
            }
            val quiet = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if (quiet && voiceSession) {
                ic?.finishComposingText()
                pauseSession()
                return
            }
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && voiceSession && !busyRetried) {
                busyRetried = true
                handler.postDelayed({ if (dictating && voiceSession && !voicePaused) continueSession() }, 300)
                return
            }
            val permission = error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
            val backends = voiceBackends().size
            backendAttempts++
            if (!quiet && !permission && backendAttempts < backends) {
                // Tahle sluzba nefunguje (napr. nema cestinu): zkusit dalsi.
                backendIndex = (backendIndex + 1) % backends
                if (voiceSession) {
                    showVoiceStatus(getString(R.string.voice_preparing))
                    if (startListening()) return
                } else {
                    ic?.finishComposingText()
                    endDictation()
                    showVoiceStatus(getString(R.string.voice_retry), 3000)
                    return
                }
            }
            voiceSession = false
            ic?.finishComposingText()
            endDictation()
            showVoiceStatus(voiceErrorText(error), 3500)
        }
    }

    /** Nabidka nad klavesou: rychla (drzeni ?123) nebo makra. */
    override fun onMenuAction(kind: Int, index: Int) {
        if (kind == KeyboardView.MENU_KIND_MACRO) {
            // Nabidka muze byt oriznuta shora (malo mista): polozky se pocitaji od konce.
            val fromEnd = index - (keyboardMenuSize() - 1)
            val macroIndex = macroMenu.size + fromEnd
            if (fromEnd == 0) openScreen(MacrosActivity::class.java)
            else macroMenu.getOrNull(macroIndex)?.let { insertMacro(it) }
            return
        }
        when (index) {
            KeyboardView.MENU_EMOJI -> showEmoji()
            KeyboardView.MENU_CLIPBOARD -> if (clipPanel?.visibility != View.VISIBLE) onClipboardClicked()
            KeyboardView.MENU_SETTINGS -> {
                Lantern.fan(this)
                openScreen(SettingsActivity::class.java)
            }
        }
    }

    private fun keyboardMenuSize(): Int = keyboard?.lastMenuSize ?: 0

    // ------------------------------------------------------------------ panely

    private fun showKeyboard() {
        val wasHidden = keyboard?.visibility != View.VISIBLE
        keyboard?.visibility = View.VISIBLE
        if (wasHidden) keyboard?.reveal()
        emojiPanel?.visibility = View.GONE
        clipPanel?.visibility = View.GONE
    }

    private fun panelParams() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, keysHeight())

    private fun showEmoji() {
        val content = content ?: return
        currentInputConnection?.let {
            flushSwipeLearning()
            commitComposing(it)
        }
        val panel = emojiPanel ?: EmojiPanel(this, theme, this).also {
            emojiPanel = it
            content.addView(it, panelParams())
        }
        panel.layoutParams = panelParams()
        panel.open()
        keyboard?.visibility = View.GONE
        clipPanel?.visibility = View.GONE
        panel.visibility = View.VISIBLE
        panel.reveal()
    }

    override fun onClipboardClicked() {
        val content = content ?: return
        if (clipPanel?.visibility == View.VISIBLE) {
            showKeyboard()
            return
        }
        currentInputConnection?.let {
            flushSwipeLearning()
            commitComposing(it)
        }
        val panel = clipPanel ?: ClipboardPanel(this, theme, this).also {
            clipPanel = it
            content.addView(it, panelParams())
        }
        panel.layoutParams = panelParams()
        panel.refresh()
        keyboard?.visibility = View.GONE
        emojiPanel?.visibility = View.GONE
        panel.visibility = View.VISIBLE
        panel.reveal()
    }

    override fun onPanelBack() = showKeyboard()

    override fun onPanelBackspace() {
        onPressFeedback(keyboardSpaceKey)
        backspace()
    }

    override fun onEmojiPicked(emoji: String) {
        onPressFeedback(keyboardSpaceKey)
        currentInputConnection?.commitText(emoji, 1)
        RustBridge.emojiUsed(emoji)
    }

    override fun onClipPaste(text: String) {
        val ic = currentInputConnection ?: return
        flushSwipeLearning()
        commitComposing(ic)
        ic.commitText(text, 1)
        autoSpace = false
        showKeyboard()
        updateShift()
        scheduleSuggestions()
    }

    // ------------------------------------------------------------------ hledani emoji

    override fun onEmojiSearch() {
        searchMode = true
        addWordMode = false
        editWordMode = false
        editTarget = null
        searchQuery.setLength(0)
        showKeyboard()
        keyboard?.setMode(KeyboardView.MODE_LETTERS)
        setShift(KeyboardView.SHIFT_OFF)
        updateBarVisibility()
        updateSearch()
    }

    private fun updateSearch() {
        val q = searchQuery.toString()
        if (editWordMode) bar?.showSearch(q, emptyList(), getString(R.string.fix_word_hint))
        else if (addWordMode) bar?.showSearch(q, emptyList(), getString(R.string.add_word_hint))
        else bar?.showSearch(q, RustBridge.emojiSearch(q))
    }

    private fun showAddWordResult(ok: Boolean, word: String) {
        if (ok) vibrateSaved()
        showVoiceStatus(if (ok) getString(R.string.word_added, word) else getString(R.string.dictionary_add_failed), 1800)
    }

    /** Otevre v liste pole pro opravu slova vlozeneho swipem (predvyplnene tim slovem). */
    private fun startEditWord(ls: SwipeCommit) {
        onPressFeedback(keyboardSpaceKey)
        searchMode = true
        addWordMode = true
        editWordMode = true
        editTarget = ls
        searchQuery.setLength(0)
        searchQuery.append(ls.word)
        showKeyboard()
        keyboard?.setMode(KeyboardView.MODE_LETTERS)
        updateBarVisibility()
        updateSearch()
    }

    /** Enter v rezimu opravy: zmenene slovo prepise to ze swipe, nezmenene / prazdne nic nedela. */
    private fun finishEditWord(target: SwipeCommit, query: String) {
        if (query.isEmpty() || query == target.word) {
            closeSearch(true)
            return
        }
        val ic = currentInputConnection ?: return closeSearch(true)
        val replace = ic.getTextBeforeCursor(target.text.length, 0)?.toString() == target.text
        closeSearch(false)
        if (replace) {
            val newText = (if (target.text.startsWith(" ")) " " else "") + query + " "
            ic.beginBatchEdit()
            ic.deleteSurroundingText(target.text.length, 0)
            ic.commitText(newText, 1)
            ic.endBatchEdit()
            if (!noLearning) RustBridge.correction(target.word, query)
            val ok = RustBridge.addUserWord(query)
            lastSwipe = null
            autoSpace = true
            updateShift()
            scheduleSuggestions()
            if (ok) {
                vibrateSaved()
                showVoiceStatus(getString(R.string.word_corrected, query), 1800)
            } else {
                showVoiceStatus(getString(R.string.dictionary_add_failed), 1800)
            }
        } else {
            updateShift()
            scheduleSuggestions()
            showAddWordResult(RustBridge.addUserWord(query), query)
        }
    }

    /** Tlacitko "+" v liste: prida slovo u kurzoru (nebo rozepsane) do osobniho slovniku, jinak necha slovo napsat. */
    override fun onAddWordClicked() {
        onPressFeedback(keyboardSpaceKey)
        val word = if (composing.isNotEmpty()) composing.toString()
        else RustBridge.lastWord(currentInputConnection?.getTextBeforeCursor(48, 0)?.toString() ?: "")
        if (word.isNotBlank()) {
            showAddWordResult(RustBridge.addUserWord(word), word)
        } else {
            searchMode = true
            addWordMode = true
            editWordMode = false
            editTarget = null
            searchQuery.setLength(0)
            showKeyboard()
            keyboard?.setMode(KeyboardView.MODE_LETTERS)
            updateBarVisibility()
            updateSearch()
        }
    }

    override fun onSearchResultPicked(emoji: String) = onEmojiPicked(emoji)

    override fun onSearchClosed() = closeSearch(true)

    private fun closeSearch(refresh: Boolean) {
        if (!searchMode) return
        searchMode = false
        addWordMode = false
        editWordMode = false
        editTarget = null
        searchQuery.setLength(0)
        bar?.hideSearch()
        updateBarVisibility()
        if (refresh) {
            updateShift()
            scheduleSuggestions()
        }
    }

    // ------------------------------------------------------------------ schranka

    /**
     * Zaznamena zkopirovany text do historie. Bezi jen na udalost systemu (zadny polling).
     * Citlive polozky (EXTRA_IS_SENSITIVE) a text, ktery vypada jako heslo, se neukladaji
     * (kontrolu hesla dela Rust). Nic se nikam neodesila.
     */
    private fun captureClipboard() {
        if (!settings.clipboardEnabled) return
        try {
            val clip = clipboard?.primaryClip ?: return
            if (clip.itemCount == 0) return
            val extras = clip.description?.extras
            if (extras != null && extras.getBoolean("android.content.extra.IS_SENSITIVE", false)) return
            val text = clip.getItemAt(0)?.text?.toString() ?: return
            RustBridge.clipAdd(text)
        } catch (_: Throwable) {
            // Schranka nemusi byt dostupna (klavesnice neni aktivni) - ignorovat.
        }
    }
}
