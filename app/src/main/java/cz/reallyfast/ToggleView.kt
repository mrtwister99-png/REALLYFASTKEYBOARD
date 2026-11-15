package cz.reallyfast

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.OvershootInterpolator

/**
 * Vlastni prepinac (nahrazuje android.widget.Switch). Palec se pohybuje s mirnym preskocenim
 * a behem pohybu se zmensi a zase zvetsi; v klidu je vypnuty 70 % a zapnuty 105 % zakladniho
 * polomeru. Jedna animace 420 ms na kazde prepnuti; obraceni uprostred navazuje z aktualni
 * hodnoty. onDraw nealokuje.
 */
class ToggleView(context: Context) : View(context) {

    /** Zavola se jen pri prepnuti uzivatelem (ne pri setChecked). */
    var onChange: ((Boolean) -> Unit)? = null

    var accentColor: Int = 0xFF0080FF.toInt()
        set(value) {
            field = value
            invalidate()
        }

    var isChecked = false
        private set

    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val overshoot = OvershootInterpolator(1.6f)
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = DURATION
        addUpdateListener { step(it.animatedFraction) }
    }

    // Aktualni stav vykresleni: poloha palce 0..1 (muze lehce presahnout) a velikost 0.6..1.05.
    private var pos = 0f
    private var size = SIZE_OFF
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
            invalidate()
        }
    }

    private fun step(t: Float) {
        pos = posFrom + (posTo - posFrom) * overshoot.getInterpolation(t)
        size = if (t < SIZE_MID_AT) {
            sizeFrom + (SIZE_MIN - sizeFrom) * (t / SIZE_MID_AT)
        } else {
            SIZE_MIN + (sizeTo - SIZE_MIN) * ((t - SIZE_MID_AT) / (1f - SIZE_MID_AT))
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((56f * d + 0.5f).toInt(), (30f * d + 0.5f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val trackW = 44f * d
        val trackH = 24f * d
        val left = (w - trackW) / 2f
        val top = (h - trackH) / 2f
        paint.color = Theme.blend(OFF_COLOR, accentColor, pos.coerceIn(0f, 1f))
        rect.set(left, top, left + trackW, top + trackH)
        canvas.drawRoundRect(rect, trackH / 2f, trackH / 2f, paint)
        val r = 10f * d * size
        val cx = left + trackH / 2f + 24f * d * pos
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(cx, h / 2f, r, paint)
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
        const val DURATION = 420L
        const val SIZE_OFF = 0.70f
        const val SIZE_ON = 1.05f
        const val SIZE_MIN = 0.60f
        const val SIZE_MID_AT = 0.35f
        val OFF_COLOR = 0xFF3A3A40.toInt()
    }
}
