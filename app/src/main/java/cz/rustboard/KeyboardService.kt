package cz.rustboard

import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
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
 * RustBoard - InputMethodService. Tenka slupka: zivotni cyklus, InputConnection, haptika, zvuk.
 * Rozhodovani (navrhy, swipe, uceni, gesta, velka pismena...) dela Rust pres RustBridge.
 *
 * Zadne pozadi: zadny polling, zadne wake locky, zadne vlastni sluzby ani sit.
 */
class KeyboardService : InputMethodService(), KeyboardView.Listener,
    SuggestionBar.Callbacks, EmojiPanel.Callbacks, ClipboardPanel.Callbacks {

    /** Posledni slovo vlozene swipem - lze ho nahradit alternativou nebo smazat jednim backspace. */
    private class SwipeCommit(var text: String, var word: String, val prev: String, val sentenceStart: Boolean)

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
    private var searchMode = false
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
        clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.addPrimaryClipChangedListener(clipListener)
        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        audio = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    override fun onDestroy() {
        RustBridge.listeners.remove(settingsListener)
        clipboard?.removePrimaryClipChangedListener(clipListener)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private val barHeight: Int get() = dp(44f)

    /** Vyska plochy klaves: radky * key_height_dp, nejvyse keyboard_height_percent obrazovky. */
    private fun keysHeight(): Int {
        val dm = resources.displayMetrics
        val s = settings
        val rows = if (s.showNumberRow) 5 else 4
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val percent = if (landscape) min(s.keyboardHeightPercent + 14, 58) else s.keyboardHeightPercent
        val byKeys = rows * s.keyHeightDp * dm.density
        val cap = dm.heightPixels * percent / 100f - barHeight
        val minimum = rows * (if (landscape) 32f else 40f) * dm.density
        return max(min(byKeys, cap), minimum).toInt()
    }

    override fun onCreateInputView(): View {
        theme = Theme.from(settings)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(theme.background)

        val bar = SuggestionBar(this, theme, this)
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, barHeight))

        val content = FrameLayout(this)
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
        root?.setBackgroundColor(theme.background)
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
        bar?.visibility = if (s.showSuggestions || s.clipboardEnabled || searchMode) View.VISIBLE else View.GONE
        bar?.setClipboardVisible(s.clipboardEnabled)
    }

    private fun applyWindowColors() {
        try {
            @Suppress("DEPRECATION")
            window?.window?.navigationBarColor = theme.background
        } catch (t: Throwable) {
            // jen kosmetika
        }
    }

    /** Krajina: zadny fullscreen "extract" rezim, klavesnice zustava dole. */
    override fun onEvaluateFullscreenMode(): Boolean = false

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
            lastSwipe = null
            autoSpace = false
            shift = KeyboardView.SHIFT_OFF
        }
        closeSearch(false)
        showKeyboard()
        keyboard?.let {
            it.swipeAllowed = !passwordField
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

    override fun onFinishInputView(finishingInput: Boolean) {
        flushSwipeLearning()
        composing.setLength(0)
        closeSearch(false)
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // Uzivatel presunul kurzor mimo rozepsane slovo (nebo ho aplikace zrusila): ukoncit skladani.
        if (composing.isNotEmpty() && (candidatesStart == -1 || newSelStart != candidatesEnd || newSelEnd != candidatesEnd)) {
            composing.setLength(0)
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
            } catch (t: Throwable) {
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
        } catch (t: Throwable) {
            // bez vibrace
        }
    }

    // ------------------------------------------------------------------ klavesy

    override fun onKeyTap(key: KeyboardView.Key) {
        when (key.kind) {
            KeyboardView.KIND_CHAR -> typeText(String(Character.toChars(key.code)))
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
            KeyboardView.KIND_GLOBE -> RustBridge.cycleLanguage()
            KeyboardView.KIND_EMOJI -> showEmoji()
        }
    }

    override fun onAltText(text: String) = typeText(text, alreadyCased = true)

    override fun onSpaceLongPress() {
        onPressFeedback(keyboardSpaceKey)
        RustBridge.cycleLanguage()
    }

    private val keyboardSpaceKey = KeyboardView.Key(0f, 0f, 0f, 0f, 0f, 0f, 32, KeyboardView.KIND_SPACE, "", "", "")

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
            searchQuery.append(text)
            updateSearch()
            return
        }
        val ic = currentInputConnection ?: return
        flushSwipeLearning()
        val out = if (!alreadyCased && shift != KeyboardView.SHIFT_OFF) text.uppercase() else text
        if (useComposing && isWordChar(out)) {
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
        val before = ic.getTextBeforeCursor(64, 0)?.toString()?.removeSuffix(word) ?: ""
        ic.finishComposingText()
        learn(word, before)
    }

    private fun learn(word: String, textBefore: String) {
        if (noLearning) return
        RustBridge.learn(word, RustBridge.lastWord(textBefore), RustBridge.shouldCapitalize(textBefore))
    }

    private fun flushSwipeLearning() {
        val ls = lastSwipe ?: return
        lastSwipe = null
        if (!noLearning) RustBridge.learn(ls.word, ls.prev, ls.sentenceStart)
    }

    private fun space() {
        if (searchMode) {
            searchQuery.append(' ')
            updateSearch()
            return
        }
        val ic = currentInputConnection ?: return
        flushSwipeLearning()
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

    private fun backspace() {
        if (searchMode) {
            if (searchQuery.isNotEmpty()) searchQuery.setLength(searchQuery.offsetByCodePoints(searchQuery.length, -1))
            updateSearch()
            return
        }
        val ic = currentInputConnection ?: return
        if (composing.isNotEmpty()) {
            composing.setLength(composing.offsetByCodePoints(composing.length, -1))
            ic.setComposingText(composing, 1)
        } else {
            val ls = lastSwipe
            lastSwipe = null
            if (ls != null && ic.getTextBeforeCursor(ls.text.length, 0)?.toString() == ls.text) {
                // Backspace hned po swipe smaze cele vlozene slovo (a nic se nenauci).
                ic.deleteSurroundingText(ls.text.length, 0)
            } else {
                if (ls != null && !noLearning) RustBridge.learn(ls.word, ls.prev, ls.sentenceStart)
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            }
        }
        autoSpace = false
        updateShift()
        scheduleSuggestions()
    }

    private fun enter() {
        if (searchMode) {
            closeSearch(true)
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
        lastSwipe = SwipeCommit(text, word, RustBridge.lastWord(before), RustBridge.shouldCapitalize(before))
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
        if (searchMode || lastSwipe != null) return
        if (!settings.showSuggestions || passwordField || !useComposing) {
            bar.setSuggestions(emptyList())
            return
        }
        if (composing.isNotEmpty()) {
            bar.setSuggestions(RustBridge.suggest(composing.toString(), "", false))
        } else {
            val before = currentInputConnection?.getTextBeforeCursor(48, 0)?.toString() ?: ""
            bar.setSuggestions(RustBridge.suggest("", RustBridge.lastWord(before), shift != KeyboardView.SHIFT_OFF))
        }
    }

    override fun onSuggestionPicked(index: Int, word: String) {
        val ic = currentInputConnection ?: return
        onPressFeedback(keyboardSpaceKey)
        val ls = lastSwipe
        if (ls != null) {
            // Nahrada slova ze swipe jinou alternativou.
            if (word != ls.word && ic.getTextBeforeCursor(ls.text.length, 0)?.toString() == ls.text) {
                val newText = (if (ls.text.startsWith(" ")) " " else "") + word + " "
                ic.beginBatchEdit()
                ic.deleteSurroundingText(ls.text.length, 0)
                ic.commitText(newText, 1)
                ic.endBatchEdit()
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
                    val n = RustBridge.deleteWordLen(ic.getTextBeforeCursor(64, 0)?.toString() ?: "")
                    if (n > 0) ic.deleteSurroundingText(n, 0) else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
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
        } catch (t: Throwable) {
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

    // ------------------------------------------------------------------ panely

    private fun showKeyboard() {
        keyboard?.visibility = View.VISIBLE
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
        searchQuery.setLength(0)
        showKeyboard()
        keyboard?.setMode(KeyboardView.MODE_LETTERS)
        setShift(KeyboardView.SHIFT_OFF)
        updateBarVisibility()
        updateSearch()
    }

    private fun updateSearch() {
        val q = searchQuery.toString()
        bar?.showSearch(q, RustBridge.emojiSearch(q))
    }

    override fun onSearchResultPicked(emoji: String) = onEmojiPicked(emoji)

    override fun onSearchClosed() = closeSearch(true)

    private fun closeSearch(refresh: Boolean) {
        if (!searchMode) return
        searchMode = false
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
        } catch (t: Throwable) {
            // Schranka nemusi byt dostupna (klavesnice neni aktivni) - ignorovat.
        }
    }
}
