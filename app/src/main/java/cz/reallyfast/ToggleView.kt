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
 * Vlastni prepinac (nahrazuje android.widget.Switch), kapsle 52 x 30 dp. Vypnuty: tmave seda kapsle
 * a svetle sedy palec; zapnuty: svetle seda kapsle a modry palec (pevna modra, ne accent barva).
 * Palec se pohybuje s mirnym preskocenim a behem pohybu se zmensi a zase zvetsi (vypnuty 70 %,
 * zapnuty 105 % zakladniho polomeru 10 dp); jeho stred je ale vzdy omezen tak, aby kruh nikdy
 * nepresahl vnitrni okraj kapsle (odsazeni 2 dp). Jedna animace 420 ms na kazde prepnuti;
 * obraceni uprostred navazuje z aktualni hodnoty. onDraw nealokuje.
 */
class ToggleView(context: Context) : View(context) {

    /** Zavola se jen pri prepnuti uzivatelem (ne pri setChecked). */
    var onChange: ((Boolean) -> Unit)? = null

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
        val r = 10f * d * size
        // Stred palce: od vnitrniho okraje vlevo po vnitrni okraj vpravo, preskok se orizne.
        val inset = INSET * d
        val minX = left + inset + r
        val maxX = left + trackW - inset - r
        val cx = (minX + (maxX - minX) * pos).coerceIn(minX, maxX)
        paint.color = Theme.blend(OFF_THUMB, ON_THUMB, t)
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
        const val TRACK_W = 52f
        const val TRACK_H = 30f
        const val INSET = 2f
        const val OFF_TRACK = 0xFF3A3A40.toInt()
        const val ON_TRACK = 0xFFD4D4D8.toInt()
        const val OFF_THUMB = 0xFFB4B4BC.toInt()
        const val ON_THUMB = 0xFF0080FF.toInt()
    }
}
