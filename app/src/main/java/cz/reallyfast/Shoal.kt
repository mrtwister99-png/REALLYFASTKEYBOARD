package cz.reallyfast

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos

/** Melcina. */
class Shoal(context: Context, private val onNet: () -> Unit) : View(context) {

    private companion object {
        const val RISE = 1300f
        const val GLIDE = 2600f
        const val HOLD = 300f
        const val SINK = 800f
        const val TOTAL = RISE + GLIDE + HOLD + SINK
        const val SHOWN = 0.2f
        const val DROP_MS = 250L
    }

    private val density = resources.displayMetrics.density
    private val wave = (96f * density + 0.5f).toInt()
    private var pearl: Bitmap? = null
    private var tall = 0
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = Rect()

    /** Vyplav v souradnicich pohledu. */
    val spot = Rect()
    private var lift = 0f
    private var drift = 0f
    private var fromRight = false
    private var swept = false
    private var anim: ValueAnimator? = null

    /** Vyska pruhu. */
    val strip: Int get() = (24f * density + 0.5f).toInt()

    val active: Boolean get() = anim != null

    private fun load() {
        if (pearl != null) return
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(resources, R.drawable.guest, o)
        if (o.outWidth <= 0) return
        var sample = 1
        while (o.outWidth / (sample * 2) >= wave) sample *= 2
        val raw = BitmapFactory.decodeResource(resources, R.drawable.guest, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return
        tall = (wave.toLong() * raw.height / raw.width).toInt()
        pearl = if (raw.width == wave) raw else Bitmap.createScaledBitmap(raw, wave, tall, true).also { if (it !== raw) raw.recycle() }
    }

    /** Jedna vlna. */
    fun ebb() {
        stop()
        load()
        if (pearl == null) return
        fromRight = Math.random() < 0.5
        swept = false
        dropping = false
        lift = 0f
        drift = 0f
        spot.setEmpty()
        visibility = VISIBLE
        val a = ValueAnimator.ofFloat(0f, TOTAL)
        a.duration = TOTAL.toLong()
        a.interpolator = LinearInterpolator()
        a.addUpdateListener { step(it.animatedValue as Float) }
        a.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                if (!cancelled) stop()
            }
        })
        anim = a
        a.start()
    }

    /** Okamzity konec bez animace. */
    fun stop() {
        val a = anim
        anim = null
        a?.removeAllListeners()
        a?.cancel()
        dropping = false
        lift = 0f
        spot.setEmpty()
        if (visibility != GONE) visibility = GONE
    }

    private fun drop() {
        val a = anim ?: return
        anim = null
        a.removeAllListeners()
        a.cancel()
        val d = ValueAnimator.ofFloat(lift, 0f)
        d.duration = DROP_MS
        d.interpolator = LinearInterpolator()
        d.addUpdateListener {
            lift = it.animatedValue as Float
            place()
        }
        d.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                if (!cancelled) {
                    anim = null
                    stop()
                }
            }
        })
        anim = d
        dropping = true
        d.start()
    }

    private var dropping = false

    private fun out(x: Float) = 1f - (1f - x) * (1f - x)
    private fun inOut(x: Float) = 0.5f - 0.5f * cos(PI.toFloat() * x)

    private fun step(ms: Float) {
        when {
            ms < RISE -> {
                lift = out(ms / RISE)
                drift = 0f
            }
            ms < RISE + GLIDE -> {
                lift = 1f
                drift = inOut((ms - RISE) / GLIDE)
            }
            ms < RISE + GLIDE + HOLD -> {
                lift = 1f
                drift = 1f
            }
            else -> {
                lift = 1f - inOut((ms - RISE - GLIDE - HOLD) / SINK)
                drift = 1f
            }
        }
        place()
    }

    /** Spocita obdelnik; kazdy snimek vynuti nove pocitani okraju okna. */
    private fun place() {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        val shown = (tall * SHOWN * lift).toInt()
        if (shown <= 0) {
            spot.setEmpty()
        } else {
            val span = (w - wave).coerceAtLeast(0)
            val t = if (fromRight) 1f - drift else drift
            val x = (span * t).toInt()
            spot.set(x, h - shown, x + wave, h)
        }
        invalidate()
        requestLayout()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (active) place()
    }

    override fun onDraw(canvas: Canvas) {
        val p = pearl ?: return
        if (spot.isEmpty) return
        dst.set(spot.left, spot.top, spot.left + wave, spot.top + tall)
        canvas.drawBitmap(p, null, dst, paint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!active || dropping) return false
        if (e.actionMasked == MotionEvent.ACTION_DOWN && spot.contains(e.x.toInt(), e.y.toInt())) {
            swept = true
            drop()
            onNet()
            return true
        }
        return swept
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }
}
