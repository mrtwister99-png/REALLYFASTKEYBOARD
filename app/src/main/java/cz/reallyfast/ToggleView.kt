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
 * Vlastni prepinac (nahrazuje android.widget.Switch), kapsle 52 x 30 dp: obrys kapsle a kulicka s odleskem.
 * Kulicka ma barvu akcentu: vypnuta je vybledla, zapnuta plna. Pri jizde dvakrat bliknutim (glitch) zmeni
 * kulicku na obrys ctverecku.
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

    /** Barva akcentu (kulicka); vybledla verze se pocita pri zmene. */
    var accent: Int = ON_THUMB
        set(value) {
            field = value
            faded = Theme.blend(value, FADE_TO, 0.62f)
            invalidate()
        }
    private var faded = Theme.blend(ON_THUMB, FADE_TO, 0.62f)
    private var glitch = 0

    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val hl = RectF()
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
            glitch = 0
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
                val raw = (t - PHASE_A) / (PHASE_B - PHASE_A)
                // Dvakrat cestou: kulicka na chvili zmeni obrys na ctverecek (a poposkoci do strany).
                glitch = when {
                    raw in 0.22f..0.36f -> 1
                    raw in 0.58f..0.72f -> 2
                    else -> 0
                }
                val k = decel.getInterpolation(raw)
                squash = SQUASH_MIN
                pos = posFrom + travel * 0.95f * k
                size = sizeFrom + (sizeTo - sizeFrom) * k
            }
            t < PHASE_C -> {
                glitch = 0
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
        val sw = 2f * d
        // Kapsle: slaba vypln a obrys, ktery se pri zapnuti zbarvi do akcentu.
        paint.style = Paint.Style.FILL
        paint.color = Theme.blend(OFF_FILL, ON_FILL, t)
        rect.set(left + sw / 2f, top + sw / 2f, left + trackW - sw / 2f, top + trackH - sw / 2f)
        canvas.drawRoundRect(rect, trackH / 2f, trackH / 2f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = sw
        paint.color = Theme.blend(OFF_STROKE, accent, t)
        canvas.drawRoundRect(rect, trackH / 2f, trackH / 2f, paint)
        val rw = 10f * d * size
        val rh = rw * squash
        // Stred kulicky: od vnitrniho okraje vlevo po vnitrni okraj vpravo.
        val inset = INSET * d
        val minX = left + inset + rw
        val maxX = left + trackW - inset - rw
        var cx = if (maxX > minX) (minX + (maxX - minX) * pos).coerceIn(minX, maxX) else (minX + maxX) / 2f
        val color = Theme.blend(faded, accent, t)
        if (glitch != 0) {
            // Glitch: obrys ctverecku, lehce posunuty ve smeru jizdy.
            cx += (if (glitch == 1) 2.5f else -2.5f) * d
            paint.color = accent
            paint.strokeWidth = sw
            rect.set(cx - rw * 0.9f, h / 2f - rh * 0.9f, cx + rw * 0.9f, h / 2f + rh * 0.9f)
            canvas.drawRoundRect(rect, 2f * d, 2f * d, paint)
            return
        }
        paint.style = Paint.Style.FILL
        paint.color = color
        rect.set(cx - rw, h / 2f - rh, cx + rw, h / 2f + rh)
        canvas.drawOval(rect, paint)
        // Tenky obrys kulicky (0,75 dp): cerny na svetle, bily na tmave kulicce.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.75f * d
        paint.color = if (Theme.luminance(color) > 0.5f) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        canvas.drawOval(rect, paint)
        if (squash > 0.6f) {
            // Odlesk: kratky oblouk vlevo nahore a tecka.
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = 1.6f * d
            paint.color = 0xB3FFFFFF.toInt()
            val q = rw * 0.62f
            hl.set(cx - q, h / 2f - q, cx + q, h / 2f + q)
            canvas.drawArc(hl, 195f, 55f, false, paint)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(cx - q * 0.1f, h / 2f - q, 1.3f * d, paint)
            paint.strokeCap = Paint.Cap.BUTT
        }
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
        const val OFF_FILL = 0xFF1E1E22.toInt()
        const val ON_FILL = 0xFF2A2A30.toInt()
        const val OFF_STROKE = 0xFF6A6A72.toInt()
        const val FADE_TO = 0xFF5A5A62.toInt()
        const val ON_THUMB = 0xFF0080FF.toInt()
    }
}
