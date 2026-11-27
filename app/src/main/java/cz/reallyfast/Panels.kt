package cz.reallyfast

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max

private fun View.dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

private fun rounded(color: Int, radius: Float): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

/** Pruhledne pozadi, ktere se pri dotyku jemne zvyrazni (zaoblena "pilulka"). */
private fun pressable(color: Int, radius: Float): Drawable =
    RippleDrawable(ColorStateList.valueOf(color), null, rounded(0xFFFFFFFF.toInt(), radius))

/** Plynule zobrazeni panelu: prolnuti + kratky posun zdola. Respektuje vypnute animace. */
internal fun View.reveal() {
    animate().cancel()
    if (!RustBridge.settings.animations) {
        alpha = 1f
        translationY = 0f
        return
    }
    alpha = 0f
    translationY = 10f * resources.displayMetrics.density
    animate().alpha(1f).translationY(0f).setDuration(150).start()
}

/** Jednoducha carova ikona kreslena na Canvas (zadne bitmapy, zadne zavislosti na fontu). */
@SuppressLint("ViewConstructor")
class IconView(context: Context, private val icon: Int) : View(context) {
    companion object {
        const val CLIPBOARD = 0
        const val SEARCH = 1
        const val CLOSE = 2
        const val TRASH = 3
        const val PIN = 4
        const val BACKSPACE = 5
        const val PLUS = 6
        const val MIC = 7
    }

    var color: Int = 0xFFFFFFFF.toInt()
        set(value) {
            field = value
            invalidate()
        }
    var filled = false
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val s = minOf(width, height) * 0.2f
        paint.color = color
        paint.strokeWidth = 1.7f * resources.displayMetrics.density
        paint.style = if (filled) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
        path.reset()
        when (icon) {
            CLIPBOARD -> {
                rect.set(cx - s * 0.85f, cy - s * 0.9f, cx + s * 0.85f, cy + s * 1.1f)
                canvas.drawRoundRect(rect, s * 0.25f, s * 0.25f, paint)
                rect.set(cx - s * 0.4f, cy - s * 1.2f, cx + s * 0.4f, cy - s * 0.65f)
                canvas.drawRoundRect(rect, s * 0.15f, s * 0.15f, paint)
                canvas.drawLine(cx - s * 0.4f, cy + s * 0.05f, cx + s * 0.4f, cy + s * 0.05f, paint)
                canvas.drawLine(cx - s * 0.4f, cy + s * 0.55f, cx + s * 0.4f, cy + s * 0.55f, paint)
            }
            SEARCH -> {
                canvas.drawCircle(cx - s * 0.2f, cy - s * 0.2f, s * 0.7f, paint)
                canvas.drawLine(cx + s * 0.35f, cy + s * 0.35f, cx + s, cy + s, paint)
            }
            CLOSE -> {
                canvas.drawLine(cx - s * 0.7f, cy - s * 0.7f, cx + s * 0.7f, cy + s * 0.7f, paint)
                canvas.drawLine(cx + s * 0.7f, cy - s * 0.7f, cx - s * 0.7f, cy + s * 0.7f, paint)
            }
            PLUS -> {
                canvas.drawLine(cx - s * 0.8f, cy, cx + s * 0.8f, cy, paint)
                canvas.drawLine(cx, cy - s * 0.8f, cx, cy + s * 0.8f, paint)
            }
            TRASH -> {
                canvas.drawLine(cx - s, cy - s * 0.7f, cx + s, cy - s * 0.7f, paint)
                canvas.drawLine(cx - s * 0.35f, cy - s * 1.05f, cx + s * 0.35f, cy - s * 1.05f, paint)
                path.moveTo(cx - s * 0.75f, cy - s * 0.7f)
                path.lineTo(cx - s * 0.6f, cy + s * 1.05f)
                path.lineTo(cx + s * 0.6f, cy + s * 1.05f)
                path.lineTo(cx + s * 0.75f, cy - s * 0.7f)
                canvas.drawPath(path, paint)
            }
            PIN -> {
                path.moveTo(cx - s * 0.55f, cy - s)
                path.lineTo(cx + s * 0.55f, cy - s)
                path.lineTo(cx + s * 0.4f, cy - s * 0.1f)
                path.lineTo(cx + s * 0.85f, cy + s * 0.35f)
                path.lineTo(cx - s * 0.85f, cy + s * 0.35f)
                path.lineTo(cx - s * 0.4f, cy - s * 0.1f)
                path.close()
                canvas.drawPath(path, paint)
                canvas.drawLine(cx, cy + s * 0.35f, cx, cy + s * 1.15f, paint)
            }
            MIC -> {
                // Mikrofon: kapsle, oblouk pod ni a nozicka.
                rect.set(cx - s * 0.5f, cy - s * 1.15f, cx + s * 0.5f, cy + s * 0.35f)
                canvas.drawRoundRect(rect, s * 0.5f, s * 0.5f, paint)
                rect.set(cx - s * 0.95f, cy - s * 0.55f, cx + s * 0.95f, cy + s * 0.85f)
                canvas.drawArc(rect, 0f, 180f, false, paint)
                canvas.drawLine(cx, cy + s * 0.85f, cx, cy + s * 1.2f, paint)
                canvas.drawLine(cx - s * 0.45f, cy + s * 1.2f, cx + s * 0.45f, cy + s * 1.2f, paint)
            }
            BACKSPACE -> {
                path.moveTo(cx - s * 1.3f, cy)
                path.lineTo(cx - s * 0.5f, cy - s * 0.8f)
                path.lineTo(cx + s * 1.1f, cy - s * 0.8f)
                path.lineTo(cx + s * 1.1f, cy + s * 0.8f)
                path.lineTo(cx - s * 0.5f, cy + s * 0.8f)
                path.close()
                path.moveTo(cx - s * 0.1f, cy - s * 0.35f)
                path.lineTo(cx + s * 0.6f, cy + s * 0.35f)
                path.moveTo(cx + s * 0.6f, cy - s * 0.35f)
                path.lineTo(cx - s * 0.1f, cy + s * 0.35f)
                canvas.drawPath(path, paint)
            }
        }
    }
}

/**
 * Horni lista: 3 navrhy + tlacitko schranky. V rezimu hledani emoji zobrazuje dotaz a vysledky.
 */
@SuppressLint("ViewConstructor")
class SuggestionBar(context: Context, private var theme: Theme, private val callbacks: Callbacks) : FrameLayout(context) {
    interface Callbacks {
        fun onSuggestionPicked(index: Int, word: String)
        /** Podrzeni navrhu ve slotu `slot` (0-2): jeho dvojice dalsich slov (nejvyse 2), ktera se ukazou nad nim. */
        fun onMoreSuggestions(slot: Int): List<String>
        fun onClipboardClicked()
        fun onVoiceClicked()
        fun onAddWordClicked()
        fun onSearchResultPicked(emoji: String)
        fun onSearchClosed()
    }

    companion object {
        /** Index predavany pri vyberu slova z podrzeneho navrhu (mimo tri sloty). */
        const val MORE_INDEX = 3
    }

    private val normalRow = LinearLayout(context)
    private val searchRow = LinearLayout(context)
    private val slots = ArrayList<TextView>(3)
    private val dividers = ArrayList<View>(2)
    private val clipButton = IconView(context, IconView.CLIPBOARD)
    private val micButton = IconView(context, IconView.MIC)
    private val addButton = IconView(context, IconView.PLUS)
    private val queryView = TextView(context)
    private val resultsRow = LinearLayout(context)
    private val closeButton = IconView(context, IconView.CLOSE)
    private var words: List<String> = emptyList()
    private var highlighted = -1
    /** Zobrazeny stavovy text (null = normalni navrhy); pri zmene motivu se vykresli znovu. */
    private var status: String? = null

    private var plusSpin: android.animation.ObjectAnimator? = null

    /** Plus se protoci o 180 stupnu na jednu stranu a zpet, pak na druhou stranu a zpet (bezi-li uz, nezacina znovu). */
    fun spinPlus() {
        if (plusSpin?.isRunning == true) return
        plusSpin = android.animation.ObjectAnimator.ofFloat(addButton, View.ROTATION, 0f, 180f, 0f, -180f, 0f).apply {
            duration = 1100L
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            start()
        }
    }

    init {
        normalRow.orientation = LinearLayout.HORIZONTAL
        normalRow.gravity = Gravity.CENTER_VERTICAL
        addButton.contentDescription = context.getString(R.string.add_word)
        addButton.setOnClickListener { callbacks.onAddWordClicked() }
        normalRow.addView(addButton, LinearLayout.LayoutParams(dp(44f), LayoutParams.MATCH_PARENT))
        for (i in 0 until 3) {
            val tv = TextView(context).apply {
                gravity = Gravity.CENTER
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.MIDDLE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(dp(6f), 0, dp(6f), 0)
                isClickable = true
                setOnClickListener { words.getOrNull(i)?.let { w -> callbacks.onSuggestionPicked(i, w) } }
                setOnLongClickListener {
                    if (status != null || words.getOrNull(i) == null) return@setOnLongClickListener false
                    showMore(this, callbacks.onMoreSuggestions(i))
                    true
                }
            }
            slots.add(tv)
            val slp = LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
            slp.setMargins(dp(3f), dp(5f), dp(3f), dp(5f))
            normalRow.addView(tv, slp)
            if (i < 2) {
                val d = View(context)
                dividers.add(d)
                normalRow.addView(d, LinearLayout.LayoutParams(dp(1f), dp(18f)))
            }
        }
        clipButton.contentDescription = context.getString(R.string.clipboard)
        clipButton.setOnClickListener { callbacks.onClipboardClicked() }
        normalRow.addView(clipButton, LinearLayout.LayoutParams(dp(48f), LayoutParams.MATCH_PARENT))
        micButton.contentDescription = context.getString(R.string.voice_start)
        micButton.setOnClickListener { callbacks.onVoiceClicked() }
        normalRow.addView(micButton, LinearLayout.LayoutParams(dp(40f), LayoutParams.MATCH_PARENT))

        searchRow.orientation = LinearLayout.HORIZONTAL
        searchRow.gravity = Gravity.CENTER_VERTICAL
        searchRow.visibility = GONE
        closeButton.contentDescription = context.getString(R.string.close)
        closeButton.setOnClickListener { callbacks.onSearchClosed() }
        searchRow.addView(closeButton, LinearLayout.LayoutParams(dp(44f), LayoutParams.MATCH_PARENT))
        queryView.isSingleLine = true
        queryView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        queryView.gravity = Gravity.CENTER_VERTICAL
        queryView.setPadding(dp(4f), 0, dp(10f), 0)
        searchRow.addView(queryView, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        val scroll = HorizontalScrollView(context)
        scroll.isHorizontalScrollBarEnabled = false
        resultsRow.orientation = LinearLayout.HORIZONTAL
        scroll.addView(resultsRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        searchRow.addView(scroll, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

        addView(normalRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(searchRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        applyTheme(theme)
    }

    fun applyTheme(t: Theme) {
        theme = t
        setBackgroundColor(t.background)
        for (d in dividers) d.setBackgroundColor(t.divider)
        for (tv in slots) tv.background = pressable(t.keyPressed, dp(10f).toFloat())
        clipButton.background = pressable(t.keyPressed, dp(10f).toFloat())
        micButton.background = pressable(t.keyPressed, dp(10f).toFloat())
        addButton.background = pressable(t.keyPressed, dp(10f).toFloat())
        closeButton.background = pressable(t.keyPressed, dp(10f).toFloat())
        clipButton.color = t.barDim
        micButton.color = t.barDim
        addButton.color = t.barDim
        closeButton.color = t.barDim
        queryView.setTextColor(t.barText)
        status?.let { setStatus(it) } ?: setSuggestions(words, highlighted)
    }

    private var morePopup: android.widget.PopupWindow? = null

    fun hideMore() {
        morePopup?.dismiss()
        morePopup = null
    }

    /** Dalsi navrhy se po podrzeni ukazou svisle nad slotem; klepnuti na slovo ho vybere, klepnuti mimo zavre. */
    private fun showMore(anchor: View, list: List<String>) {
        hideMore()
        if (list.isEmpty()) return
        val col = LinearLayout(context)
        col.orientation = LinearLayout.VERTICAL
        for (w in list) {
            val tv = TextView(context).apply {
                gravity = Gravity.CENTER
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.MIDDLE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setPadding(dp(14f), 0, dp(14f), 0)
                setTextColor(theme.barText)
                text = w
                background = pressable(theme.keyPressed, dp(10f).toFloat())
                setOnClickListener {
                    hideMore()
                    callbacks.onSuggestionPicked(MORE_INDEX, w)
                }
            }
            col.addView(tv, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(44f)))
        }
        val h = dp(44f) * list.size
        val wPx = maxOf(anchor.width, dp(120f))
        val pw = android.widget.PopupWindow(col, wPx, h, false)
        pw.isOutsideTouchable = true
        pw.elevation = dp(8f).toFloat()
        pw.setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(12f).toFloat()
            setColor(theme.background)
            setStroke(dp(1f), theme.divider)
        })
        try {
            // Vuci kotvici (ne vuci obrazovce): okno klavesnice nezacina na vrcholu obrazovky, a souradnice by pak lezely nize.
            // Dolni okraj okna lezi 4 dp nad hornim okrajem slotu, tedy primo nad nabizenym slovem.
            pw.showAsDropDown(anchor, (anchor.width - wPx) / 2, -(anchor.height + h + dp(4f)))
            morePopup = pw
        } catch (_: Throwable) {
            // okno nejde zobrazit (napr. klavesnice se zrovna zavira)
        }
    }

    override fun onDetachedFromWindow() {
        hideMore()
        super.onDetachedFromWindow()
    }

    /** Stavovy text pres celou sirku mezi tlacitky + a schranka; `setSuggestions` vrati normalni stav. */
    fun setStatus(text: String) {
        hideMore()
        status = text
        words = emptyList()
        highlighted = -1
        slots[0].visibility = GONE
        slots[2].visibility = GONE
        for (d in dividers) d.visibility = GONE
        val tv = slots[1]
        tv.ellipsize = TextUtils.TruncateAt.END
        tv.text = text
        tv.alpha = 1f
        tv.isClickable = false
        tv.setTextColor(theme.barAccent)
        tv.contentDescription = text
    }

    /** Zobrazi az 3 navrhy; `highlight` = index zvyrazneneho (accent barva), -1 = zadny. */
    fun setSuggestions(list: List<String>, highlight: Int = -1) {
        hideMore()
        status = null
        words = list
        highlighted = highlight
        for (d in dividers) d.visibility = VISIBLE
        for (i in 0 until 3) {
            val tv = slots[i]
            tv.visibility = VISIBLE
            tv.ellipsize = TextUtils.TruncateAt.MIDDLE
            val w = list.getOrNull(i)
            val changed = (w ?: "") != tv.text.toString()
            tv.text = w ?: ""
            if (changed && w != null && RustBridge.settings.animations) {
                tv.animate().cancel()
                tv.alpha = 0.3f
                tv.animate().alpha(1f).setDuration(110).start()
            } else if (changed) {
                tv.alpha = 1f
            }
            tv.isClickable = w != null
            tv.setTextColor(if (i == highlight) theme.barAccent else theme.barText)
            tv.contentDescription = w
        }
    }

    /** Slova prave zobrazena v liste (bez stavoveho textu). */
    val shownWords: List<String> get() = if (status == null) words else emptyList()

    fun setClipboardVisible(visible: Boolean) {
        clipButton.visibility = if (visible) VISIBLE else GONE
    }

    fun setMicVisible(visible: Boolean) {
        micButton.visibility = if (visible) VISIBLE else GONE
    }

    fun showSearch(query: String, results: List<String>, hint: String = context.getString(R.string.emoji_search_hint)) {
        normalRow.visibility = GONE
        searchRow.visibility = VISIBLE
        queryView.text = if (query.isEmpty()) hint else "$query▏"
        queryView.setTextColor(if (query.isEmpty()) theme.barDim else theme.barText)
        resultsRow.removeAllViews()
        for (e in results) {
            val tv = TextView(context).apply {
                text = e
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setTextColor(theme.barText)
                background = pressable(theme.keyPressed, dp(10f).toFloat())
                setOnClickListener { callbacks.onSearchResultPicked(e) }
            }
            resultsRow.addView(tv, LinearLayout.LayoutParams(dp(46f), LayoutParams.MATCH_PARENT))
        }
    }

    /** Je v liste videt pole hledani / opravy (s krizkem)? */
    val searchShown: Boolean get() = searchRow.visibility == VISIBLE

    fun hideSearch() {
        searchRow.visibility = GONE
        normalRow.visibility = VISIBLE
    }
}

/** Mrizka emoji kreslena na Canvas; rolovani zajistuje rodicovsky ScrollView. */
class EmojiGrid(context: Context) : View(context) {
    var onPick: ((String) -> Unit)? = null
    var items: List<String> = emptyList()
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }
    var textColor: Int = 0xFFFFFFFF.toInt()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private var cols = 8
    private var cell = 1f
    private var downX = 0f
    private var downY = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        cols = max(6, w / dp(46f))
        cell = w / cols.toFloat()
        val rows = (items.size + cols - 1) / cols
        setMeasuredDimension(w, max(dp(48f), (rows * cell).toInt()))
    }

    override fun onDraw(canvas: Canvas) {
        paint.textSize = cell * 0.58f
        paint.color = textColor
        val dy = -(paint.ascent() + paint.descent()) / 2f
        for (i in items.indices) {
            val e = items[i]
            val cx = (i % cols + 0.5f) * cell
            val cy = (i / cols + 0.5f) * cell
            canvas.drawText(e, cx, cy + dy, paint)
        }
    }

    @SuppressLint("ClickableViewAccessibility") // jen vyber bunky, zadna samostatna akce kliknuti
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
            }
            MotionEvent.ACTION_UP -> if (abs(e.x - downX) < slop && abs(e.y - downY) < slop) {
                val index = (e.y / cell).toInt() * cols + (e.x / cell).toInt().coerceIn(0, cols - 1)
                items.getOrNull(index)?.let { onPick?.invoke(it) }
            }
        }
        return true
    }
}

/** Panel emoji: kategorie (nedavne, smajlici, zvirata, jidlo, aktivity, symboly) + hledani. */
@SuppressLint("ViewConstructor", "SetTextI18n") // ABC je popisek tlacitka, ne prekladany text
class EmojiPanel(context: Context, private val theme: Theme, private val callbacks: Callbacks) : LinearLayout(context) {
    interface Callbacks {
        fun onEmojiPicked(emoji: String)
        fun onEmojiSearch()
        fun onPanelBack()
        fun onPanelBackspace()
    }

    private val tabs = ArrayList<TextView>(6)
    private val grid = EmojiGrid(context)
    private val scroll = ScrollView(context)
    private val empty = TextView(context)
    private var category = 1

    init {
        orientation = VERTICAL
        setBackgroundColor(theme.background)
        val header = LinearLayout(context)
        header.orientation = HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val back = TextView(context).apply {
            text = "ABC"
            gravity = Gravity.CENTER
            setTextColor(theme.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            contentDescription = context.getString(R.string.back_to_keyboard)
            setOnClickListener { callbacks.onPanelBack() }
        }
        header.addView(back, LayoutParams(dp(52f), LayoutParams.MATCH_PARENT))
        val icons = listOf("🕘", "😀", "🐶", "🍎", "⚽", "❤️")
        val names = context.resources.getStringArray(R.array.emoji_categories)
        for (i in icons.indices) {
            val tv = TextView(context).apply {
                text = icons[i]
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                contentDescription = names.getOrNull(i)
                setOnClickListener { select(i) }
            }
            tabs.add(tv)
            header.addView(tv, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        val search = IconView(context, IconView.SEARCH).apply {
            color = theme.text
            contentDescription = context.getString(R.string.emoji_search)
            setOnClickListener { callbacks.onEmojiSearch() }
        }
        header.addView(search, LayoutParams(dp(44f), LayoutParams.MATCH_PARENT))
        val del = IconView(context, IconView.BACKSPACE).apply {
            color = theme.text
            contentDescription = context.getString(R.string.delete)
            setOnClickListener { callbacks.onPanelBackspace() }
        }
        header.addView(del, LayoutParams(dp(48f), LayoutParams.MATCH_PARENT))
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, dp(44f)))

        grid.textColor = theme.text
        grid.onPick = { callbacks.onEmojiPicked(it) }
        scroll.addView(grid, ViewGroup.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val body = FrameLayout(context)
        body.addView(scroll, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        empty.text = context.getString(R.string.emoji_recent_empty)
        empty.gravity = Gravity.CENTER
        empty.setTextColor(theme.textDim)
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        body.addView(empty, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /** Otevre panel: nedavne emoji, pokud nejake jsou, jinak smajliky. */
    fun open() {
        select(if (RustBridge.emojiCategory(0).isEmpty()) 1 else 0)
    }

    private fun select(cat: Int) {
        category = cat
        for ((i, t) in tabs.withIndex()) {
            t.background = if (i == cat) rounded(theme.key, dp(12f).toFloat()) else null
            t.alpha = if (i == cat) 1f else 0.6f
        }
        val list = RustBridge.emojiCategory(cat)
        grid.items = list
        empty.visibility = if (list.isEmpty()) VISIBLE else GONE
        scroll.scrollTo(0, 0)
    }
}

/** Panel schranky: historie az 30 polozek, pripnuti, smazani polozky, smazani vseho. */
@SuppressLint("ViewConstructor", "SetTextI18n") // ABC je popisek tlacitka, ne prekladany text
class ClipboardPanel(context: Context, private val theme: Theme, private val callbacks: Callbacks) : LinearLayout(context) {
    interface Callbacks {
        fun onClipPaste(text: String)
        fun onPanelBack()
    }

    private val list = LinearLayout(context)
    private val empty = TextView(context)

    init {
        orientation = VERTICAL
        setBackgroundColor(theme.background)
        val header = LinearLayout(context)
        header.orientation = HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val back = TextView(context).apply {
            text = "ABC"
            gravity = Gravity.CENTER
            setTextColor(theme.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            contentDescription = context.getString(R.string.back_to_keyboard)
            setOnClickListener { callbacks.onPanelBack() }
        }
        header.addView(back, LayoutParams(dp(52f), LayoutParams.MATCH_PARENT))
        val title = TextView(context).apply {
            text = context.getString(R.string.clipboard)
            setTextColor(theme.textDim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(title, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        val clear = TextView(context).apply {
            text = context.getString(R.string.clear_all)
            setTextColor(theme.accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(dp(14f), 0, dp(14f), 0)
            setOnClickListener {
                RustBridge.clipClear()
                refresh()
            }
        }
        header.addView(clear, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, dp(44f)))

        list.orientation = VERTICAL
        list.setPadding(dp(8f), 0, dp(8f), dp(8f))
        val scroll = ScrollView(context)
        scroll.addView(list, ViewGroup.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val body = FrameLayout(context)
        body.addView(scroll, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        empty.text = context.getString(R.string.clipboard_empty)
        empty.gravity = Gravity.CENTER
        empty.setTextColor(theme.textDim)
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        empty.setPadding(dp(24f), 0, dp(24f), 0)
        body.addView(empty, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun refresh() {
        val items = RustBridge.clipList()
        list.removeAllViews()
        empty.visibility = if (items.isEmpty()) VISIBLE else GONE
        for (item in items) {
            val row = LinearLayout(context)
            row.orientation = HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.background = rounded(theme.key, dp(14f).toFloat())
            val text = TextView(context).apply {
                this.text = item.text
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(theme.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(14f), dp(10f), dp(6f), dp(10f))
                setOnClickListener { callbacks.onClipPaste(item.text) }
            }
            row.addView(text, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            val pin = IconView(context, IconView.PIN).apply {
                color = if (item.pinned) theme.accent else theme.textDim
                filled = item.pinned
                contentDescription = context.getString(if (item.pinned) R.string.unpin else R.string.pin)
                setOnClickListener {
                    RustBridge.clipPin(item.id, !item.pinned)
                    refresh()
                }
            }
            row.addView(pin, LayoutParams(dp(44f), dp(48f)))
            val del = IconView(context, IconView.TRASH).apply {
                color = theme.textDim
                contentDescription = context.getString(R.string.delete)
                setOnClickListener {
                    RustBridge.clipDelete(item.id)
                    refresh()
                }
            }
            row.addView(del, LayoutParams(dp(44f), dp(48f)))
            val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(6f)
            list.addView(row, lp)
        }
    }
}
