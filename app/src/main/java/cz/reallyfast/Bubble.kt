package cz.reallyfast

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.min

/**
 * Plovouci zaoblena bublina v obsahu aktivity (ne dialog ani PopupWindow). Stoji primo nad
 * zadanou kotvou (obdelnik v souradnicich okna), vyjizdi zdola a zase se zasune.
 * Animace v nastaveni bezi vzdy, nezavisle na nastaveni animaci klavesnice.
 * V jedne aktivite je nejvyse jedna bublina: nova starou hned zavre.
 *
 * `passThrough` = klepnuti mimo bublinu ji zavre a dotyk projde dal (napoveda);
 * jinak se dotyk spotrebuje (editor klavesy). `autoHideMs` > 0 = samo se zavre.
 * `widthDp` > 0 = pevna sirka (nejvyse 300 dp / sirka obrazovky - 24 dp).
 */
@SuppressLint("ClickableViewAccessibility")
class Bubble(
    private val activity: Activity,
    content: View,
    private val passThrough: Boolean,
    private val autoHideMs: Long = 0L,
    private val widthDp: Int = 0,
) {
    var onHidden: (() -> Unit)? = null

    /** Je zobrazena (i behem zasouvani, dokud se nesmazou pohledy). */
    var isShowing = false
        private set

    /** Cas zacatku posledniho zavirani; napoveda podle nej pozna "klepnuti na otaznik zavira". */
    var hideStartedAt = 0L
        private set

    private val d = activity.resources.displayMetrics.density
    private val frame: FrameLayout = activity.findViewById(android.R.id.content)
    private val catcher = View(activity)
    private val holder = FrameLayout(activity)
    private val box = FrameLayout(activity)
    private var hiding = false
    private val autoHide = Runnable { hide() }
    private val loc0 = IntArray(2)
    private val loc1 = IntArray(2)
    private val vis = Rect()
    private var width = 0
    private var height = 0

    private fun dp(v: Float) = (v * d + 0.5f).toInt()

    init {
        box.background = GradientDrawable().apply {
            setColor(0xFF1B1B1F.toInt())
            setStroke(dp(1f), 0x33FFFFFF)
            cornerRadius = dp(12f).toFloat()
        }
        box.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        holder.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // Klepnuti do bubliny nesmi propadnout na lapac: napovedu zavre, editor ho jen spotrebuje.
        box.setOnClickListener { if (passThrough) hide() }
        catcher.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) hide()
            !passThrough
        }
    }

    /** Souradnice pohledu v okne jako kotva bubliny. */
    fun anchorOf(v: View): Rect {
        v.getLocationInWindow(loc0)
        return Rect(loc0[0], loc0[1], loc0[0] + v.width, loc0[1] + v.height)
    }

    /** Zobrazi bublinu nad kotvou (souradnice okna). */
    fun show(anchor: Rect) {
        if (isShowing) return
        (activity as? BaseScreen)?.let {
            it.currentBubble?.hide(immediate = true)
            it.currentBubble = this
        }
        val maxW = min(dp(300f), frame.width - dp(24f))
        val w = if (widthDp > 0) min(dp(widthDp.toFloat()), maxW) else 0
        val wSpec = if (w > 0) View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY) else View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST)
        holder.measure(wSpec, View.MeasureSpec.makeMeasureSpec(max(frame.height, dp(200f)), View.MeasureSpec.AT_MOST))
        width = holder.measuredWidth
        height = holder.measuredHeight
        frame.addView(catcher, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(holder, FrameLayout.LayoutParams(width, height, Gravity.TOP or Gravity.START))
        place(anchor, animate = false)
        isShowing = true
        hiding = false
        box.animate().cancel()
        box.alpha = 0f
        box.translationY = 8f * d
        box.animate().alpha(1f).translationY(0f).setDuration(DURATION).start()
        if (autoHideMs > 0) frame.postDelayed(autoHide, autoHideMs)
    }

    /** Presune bublinu nad novou kotvu (jen kdyz je zobrazena). */
    fun moveTo(anchor: Rect, animate: Boolean) {
        if (!isShowing || hiding) return
        place(anchor, animate)
    }

    private fun place(anchor: Rect, animate: Boolean) {
        frame.getLocationInWindow(loc0)
        frame.getLocationOnScreen(loc1)
        frame.getWindowVisibleDisplayFrame(vis)
        // Viditelna oblast (bez klavesnice a systemovych listy) v souradnicich obsahu.
        val left = max(0, vis.left - loc1[0]) + dp(12f)
        val right = min(frame.width, vis.right - loc1[0]) - dp(12f)
        val top = max(0, vis.top - loc1[1]) + dp(4f)
        val bottom = min(frame.height, vis.bottom - loc1[1]) - dp(4f)
        val cx = (anchor.left + anchor.right) / 2f - loc0[0]
        val x = (cx - width / 2f).coerceIn(left.toFloat(), max(left, right - width).toFloat())
        val aTop = (anchor.top - loc0[1]).toFloat()
        val y = (aTop - dp(6f) - height).coerceIn(top.toFloat(), max(top, bottom - height).toFloat())
        holder.animate().cancel()
        if (animate) {
            holder.animate().translationX(x).translationY(y).setDuration(DURATION).start()
        } else {
            holder.translationX = x
            holder.translationY = y
        }
    }

    /** Zasune bublinu (po animaci odstrani pohledy); `immediate` = hned bez animace. */
    fun hide(immediate: Boolean = false) {
        if (!isShowing) return
        if (immediate) {
            hideStartedAt = SystemClock.uptimeMillis()
            finish()
            return
        }
        if (hiding) return
        hiding = true
        hideStartedAt = SystemClock.uptimeMillis()
        frame.removeCallbacks(autoHide)
        box.animate().cancel()
        box.animate().alpha(0f).translationY(8f * d).setDuration(DURATION).withEndAction { finish() }.start()
    }

    private fun finish() {
        if (!isShowing) return
        isShowing = false
        hiding = false
        frame.removeCallbacks(autoHide)
        box.animate().cancel()
        holder.animate().cancel()
        frame.removeView(catcher)
        frame.removeView(holder)
        (activity as? BaseScreen)?.let { if (it.currentBubble === this) it.currentBubble = null }
        onHidden?.invoke()
    }

    private companion object {
        const val DURATION = 150L
    }
}
