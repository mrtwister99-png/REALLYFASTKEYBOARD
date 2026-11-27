package cz.reallyfast

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator

/**
 * Vlastni prepinac (nahrazuje android.widget.Switch), kapsle 52 x 30 dp. Vypnuty: tmave seda kapsle
 * a svetle sedy palec; zapnuty: svetle seda kapsle a modry palec (pevna modra, ne accent barva).
 * Klidovy palec je o tretinu vetsi nez driv (vypnuty 93 %, zapnuty 140 % zakladniho polomeru 10 dp).
 * Prepnuti (520 ms): palec se nejdriv sploste na 5 % vysky, v tomto stavu dojede do 95 % cesty,
 * pak se nafoukne na 125 % klidove velikosti a nakonec se usadi. Stred palce je vzdy omezen tak,
 * aby kruh nepresahl vnitrni okraj kapsle vodorovne. onDraw nealokuje.
 */
class ToggleView(context: Context) : View(context) {

    /** Zavola se jen pri prepnuti uzivatelem (ne pri setChecked). */
    var onChange: ((Boolean) -> Unit)? = null

    var isChecked = false
        private set

    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val decel = DecelerateInterpolator(1.4f)
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = DURATION
        addUpdateListener { step(it.animatedFraction) }
    }

    // Aktualni stav vykresleni: poloha palce 0..1, sirka palce (nasobek polomeru 10 dp) a vyska vuci sirce (0,05..1).
    private var pos = 0f
    private var size = SIZE_OFF
    private var squash = 1f
    private var posFrom = 0f
    private var posTo = 0f
    private var sizeFrom = SIZE_OFF
    private var sizeTo = SIZE_OFF

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener { toggle() }
    }

    /** Prepne s animaci a ohlasi zmenu (uzivatelske prepnuti). */
    fun toggle() {
        setChecked(!isChecked, true)
        onChange?.invoke(isChecked)
    }

    fun setChecked(value: Boolean, animate: Boolean) {
        if (value == isChecked) return
        isChecked = value
        posFrom = pos
        posTo = if (value) 1f else 0f
        sizeFrom = size
        sizeTo = if (value) SIZE_ON else SIZE_OFF
        animator.cancel()
        if (animate) {
            animator.start()
        } else {
            pos = posTo
            size = sizeTo
            squash = 1f
            invalidate()
        }
    }

    /** Casovy prubeh: A splosteni, B jizda v ploskem stavu do 95 %, C dojezd a nafouknuti na 125 %, D usazeni. */
    private fun step(t: Float) {
        val travel = posTo - posFrom
        when {
            t < PHASE_A -> {
                val k = t / PHASE_A
                squash = 1f - (1f - SQUASH_MIN) * k
                pos = posFrom
                size = sizeFrom
            }
            t < PHASE_B -> {
                val k = decel.getInterpolation((t - PHASE_A) / (PHASE_B - PHASE_A))
                squash = SQUASH_MIN
                pos = posFrom + travel * 0.95f * k
                size = sizeFrom + (sizeTo - sizeFrom) * k
            }
            t < PHASE_C -> {
                val k = (t - PHASE_B) / (PHASE_C - PHASE_B)
                pos = posFrom + travel * (0.95f + 0.05f * k)
                squash = SQUASH_MIN + (1f - SQUASH_MIN) * k
                size = sizeTo * (1f + (POP - 1f) * k)
            }
            else -> {
                val k = (t - PHASE_C) / (1f - PHASE_C)
                pos = posTo
                squash = 1f
                size = sizeTo * (POP - (POP - 1f) * k)
            }
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((TRACK_W * d + 0.5f).toInt(), (TRACK_H * d + 0.5f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val trackW = TRACK_W * d
        val trackH = TRACK_H * d
        val left = (w - trackW) / 2f
        val top = (h - trackH) / 2f
        val t = pos.coerceIn(0f, 1f)
        paint.color = Theme.blend(OFF_TRACK, ON_TRACK, t)
        rect.set(left, top, left + trackW, top + trackH)
        canvas.drawRoundRect(rect, trackH / 2f, trackH / 2f, paint)
        val rw = 10f * d * size
        val rh = rw * squash
        // Stred palce: od vnitrniho okraje vlevo po vnitrni okraj vpravo.
        val inset = INSET * d
        val minX = left + inset + rw
        val maxX = left + trackW - inset - rw
        val cx = if (maxX > minX) (minX + (maxX - minX) * pos).coerceIn(minX, maxX) else (minX + maxX) / 2f
        paint.color = Theme.blend(OFF_THUMB, ON_THUMB, t)
        rect.set(cx - rw, h / 2f - rh, cx + rw, h / 2f + rh)
        canvas.drawOval(rect, paint)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Switch"
        info.isCheckable = true
        info.isChecked = isChecked
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val DURATION = 520L
        const val SIZE_OFF = 0.70f * 4f / 3f
        const val SIZE_ON = 1.05f * 4f / 3f
        const val SQUASH_MIN = 0.05f
        const val POP = 1.25f
        const val PHASE_A = 0.18f
        const val PHASE_B = 0.62f
        const val PHASE_C = 0.80f
        const val TRACK_W = 52f
        const val TRACK_H = 30f
        const val INSET = 2f
        const val OFF_TRACK = 0xFF3A3A40.toInt()
        const val ON_TRACK = 0xFFD4D4D8.toInt()
        const val OFF_THUMB = 0xFFB4B4BC.toInt()
        const val ON_THUMB = 0xFF0080FF.toInt()
    }
}
