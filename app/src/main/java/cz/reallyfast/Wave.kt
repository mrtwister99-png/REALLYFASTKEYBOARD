package cz.reallyfast

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View

/**
 * Tlacitko s textem, ktery po `play` probehne vlnou: kopecek se prevali zleva doprava pres vsechna pismena
 * a pismena po nem zase spadnou na zakladni radku. onDraw nealokuje (pozice pismen se pocitaji pri zmene textu).
 */
class WaveTextView(context: Context, text: String, private val color: Int, sizeSp: Float) : View(context) {

    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var label = text
    private var advances = FloatArray(0)
    private var textWidth = 0f
    private var phase = -1f
    private val amp = 11f * d
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000L
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        paint.color = color
        paint.textSize = sizeSp * resources.displayMetrics.scaledDensity
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        measureText()
        isClickable = true
        minimumHeight = (46f * d).toInt()
    }

    private fun measureText() {
        advances = FloatArray(label.length)
        paint.getTextWidths(label, advances)
        var w = 0f
        for (a in advances) w += a
        textWidth = w
    }

    /** Spusti vlnu; po jejim dobehnuti zavola `done`. */
    fun play(done: () -> Unit) {
        if (animator.isRunning) return
        animator.removeAllListeners()
        animator.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                phase = -1f
                invalidate()
                if (!cancelled) done()
            }
        })
        animator.start()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (46f * d).toInt() + amp.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val n = label.length
        if (n == 0) return
        val fm = paint.fontMetrics
        val base = height / 2f - (fm.ascent + fm.descent) / 2f + amp / 2f
        var x = (width - textWidth) / 2f
        // Kopecek je siroky 4 pismena; vlna zacina pred prvnim a konci za poslednim pismenem.
        val wide = 4f
        val p = if (phase < 0f) -100f else phase * (n + 2f * wide) - wide
        for (i in 0 until n) {
            val rel = (i - p) / wide
            val lift = if (rel > -1f && rel < 1f) {
                val c = Math.cos(rel * Math.PI / 2.0).toFloat()
                amp * c * c
            } else 0f
            canvas.drawText(label, i, i + 1, x, base - lift, paint)
            x += advances[i]
        }
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }
}
