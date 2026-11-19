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
        // Odmena po chyceni: hlava vyjede cela, chvili stoji, otoci se o 360 stupnu, chvili stoji a zajede zpet.
        const val UP_MS = 700f
        const val STAY1_MS = 900f
        const val SPIN_MS = 1100f
        const val STAY2_MS = 700f
        const val DOWN_MS = 800f
        const val PRIZE_MS = UP_MS + STAY1_MS + SPIN_MS + STAY2_MS + DOWN_MS
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
    private var show = 0f
    private var flip = 1f
    private var from = SHOWN
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
        prize = false
        show = 0f
        flip = 1f
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
        prize = false
        show = 0f
        flip = 1f
        lift = 0f
        spot.setEmpty()
        if (visibility != GONE) visibility = GONE
        restoreStrip()
    }

    /** Pruh nad klavesnici se po odmene vrati na puvodni vysku. */
    private fun restoreStrip() {
        val lp = layoutParams ?: return
        if (lp.height != strip) {
            lp.height = strip
            layoutParams = lp
        }
    }

    /** Chycena hlava: pruh se zvetsi, aby byla cela videt, a pak probehne odmena (nahoru, stat, otocka, stat, dolu). */
    private fun reward() {
        val a = anim ?: return
        anim = null
        a.removeAllListeners()
        a.cancel()
        from = (SHOWN * lift).coerceIn(0f, 1f)
        val lp = layoutParams
        if (lp != null && lp.height < tall) {
            lp.height = tall
            layoutParams = lp
        }
        prize = true
        val p = ValueAnimator.ofFloat(0f, PRIZE_MS)
        p.duration = PRIZE_MS.toLong()
        p.interpolator = LinearInterpolator()
        p.addUpdateListener { prizeStep(it.animatedValue as Float) }
        p.addListener(object : AnimatorListenerAdapter() {
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
        anim = p
        p.start()
    }

    private var prize = false

    private fun prizeStep(ms: Float) {
        val t1 = UP_MS
        val t2 = t1 + STAY1_MS
        val t3 = t2 + SPIN_MS
        val t4 = t3 + STAY2_MS
        flip = 1f
        when {
            ms < t1 -> show = from + (1f - from) * out(ms / UP_MS)
            ms < t2 -> show = 1f
            ms < t3 -> {
                show = 1f
                // Otocka kolem svisle osy: sirka se meni jako cos uhlu, uhel 0 az 360 stupnu.
                flip = cos(2f * PI.toFloat() * inOut((ms - t2) / SPIN_MS))
            }
            ms < t4 -> show = 1f
            else -> show = 1f - inOut((ms - t4) / DOWN_MS)
        }
        place()
    }

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
        if (!prize) show = SHOWN * lift
        val shown = (tall * show).toInt().coerceAtMost(h)
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
        if (flip == 1f) {
            canvas.drawBitmap(p, null, dst, paint)
        } else {
            canvas.save()
            canvas.scale(flip, 1f, spot.left + wave / 2f, spot.top.toFloat())
            canvas.drawBitmap(p, null, dst, paint)
            canvas.restore()
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!active) return false
        if (prize) return spot.contains(e.x.toInt(), e.y.toInt())
        if (e.actionMasked == MotionEvent.ACTION_DOWN && spot.contains(e.x.toInt(), e.y.toInt())) {
            swept = true
            reward()
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
