package cz.reallyfast

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Duta sipka (obrys kolem silne cary): vevnitr se da zleva doprava vyplnit barvou (`progress`), na spicce
 * vyleti jiskry a cela sipka umi odjet do strany. Kresleni je bez alokaci, cesta se stavi pri zmene velikosti.
 */
@SuppressLint("ViewConstructor")
class ChevronView(context: Context, private val pointsRight: Boolean, outline: Int, @Suppress("UNUSED_PARAMETER") hole: Int = 0) : View(context) {
    private val d = resources.displayMetrics.density
    private val path = Path()
    private val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Path()
    private val inner = Path()
    private val scratch = Path()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var tipX = 0f
    private var tipY = 0f
    private var size = 0f

    /** Barva obrysu a barva "dirky" (barva pozadi za sipkou). */
    var outline: Int = outline
        set(value) {
            field = value
            ringPaint.color = value
            invalidate()
        }
    /** Vyplneni zleva doprava, 0 az 1. */
    var progress = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    /** Prubeh jisker, -1 = zadne. */
    private var spark = -1f
    private var running: Animator? = null

    init {
        for (p in arrayOf(outerPaint, fillPaint, sparkPaint)) {
            p.style = Paint.Style.STROKE
            p.strokeCap = Paint.Cap.ROUND
            p.strokeJoin = Paint.Join.ROUND
        }
        ringPaint.color = outline
        ringPaint.style = Paint.Style.FILL
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = 0xFFFFD60A.toInt()
        sparkPaint.strokeWidth = 2.2f * d
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        size = min(w, h) * 0.5f
        val cx = w / 2f
        val cy = h / 2f
        val dir = if (pointsRight) 1f else -1f
        val arm = size * 0.62f
        path.reset()
        path.moveTo(cx - dir * size * 0.42f, cy - arm)
        path.lineTo(cx + dir * size * 0.42f, cy)
        path.lineTo(cx - dir * size * 0.42f, cy + arm)
        tipX = cx + dir * size * 0.42f
        tipY = cy
        val outerW = size * 0.5f
        val line = (1.5f * d).coerceAtMost(outerW / 3f)
        // Dutou sipku tvori prstenec = obrys silne cary bez obrysu o neco uzsi cary (uvnitr je pruhledno).
        outerPaint.strokeWidth = outerW
        outerPaint.getFillPath(path, ring)
        outerPaint.strokeWidth = outerW - 2f * line
        outerPaint.getFillPath(path, inner)
        scratch.set(ring)
        scratch.op(inner, Path.Op.DIFFERENCE)
        ring.set(scratch)
    }

    override fun onDraw(canvas: Canvas) {
        if (progress > 0f) {
            canvas.save()
            canvas.clipRect(0f, 0f, width * progress, height.toFloat())
            canvas.drawPath(inner, fillPaint)
            canvas.restore()
        }
        canvas.drawPath(ring, ringPaint)
        if (spark >= 0f) {
            // Osm kratkych car se rozleti od spicky a zhasnou.
            val a = ((1f - spark) * 255f).toInt().coerceIn(0, 255)
            sparkPaint.color = (fillPaint.color and 0x00FFFFFF) or (a shl 24)
            val base = if (pointsRight) 0.0 else PI
            for (i in 0 until SPARKS) {
                val ang = base + (i - (SPARKS - 1) / 2f) * (PI / (SPARKS - 1)) * 0.9
                val r0 = size * (0.35f + 0.9f * spark)
                val r1 = r0 + size * 0.45f * (1f - spark)
                val c = cos(ang).toFloat()
                val s = sin(ang).toFloat()
                canvas.drawLine(tipX + c * r0, tipY + s * r0, tipX + c * r1, tipY + s * r1, sparkPaint)
            }
        }
    }

    /** Zastavi rozehranou animaci a vrati sipku do zakladu. */
    fun reset() {
        running?.cancel()
        running = null
        spark = -1f
        progress = 0f
        translationX = 0f
        isEnabled = true
    }

    /**
     * Vypln barvou `color` zleva doprava; po jejim dobehnuti volitelne jiskry a odjeti o `travel` pixelu (zaporne = doleva),
     * pak se zavola `onDone`. Sipka mezitim nereaguje na dotyky.
     */
    fun play(color: Int, withSparks: Boolean, travel: Float, onDone: () -> Unit) {
        running?.cancel()
        fillPaint.color = color
        isEnabled = false
        val fill = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FILL_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { progress = it.animatedValue as Float }
        }
        val go = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = GO_MS
            interpolator = AccelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                if (withSparks) spark = t
                translationX = travel * t
                invalidate()
            }
        }
        fill.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
            }
            override fun onAnimationEnd(animation: Animator) {
                if (cancelled) return
                running = go
                go.start()
            }
        })
        go.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
            }
            override fun onAnimationEnd(animation: Animator) {
                spark = -1f
                running = null
                if (!cancelled) onDone()
            }
        })
        running = fill
        fill.start()
    }

    /** Vrati sipku plynule zpet (vypln zmizi, poloha se srovna). */
    fun returnHome() {
        running?.cancel()
        isEnabled = true
        val from = translationX
        val p0 = progress
        running = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = BACK_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                translationX = from * t
                progress = p0 * t
            }
            start()
        }
    }

    /** Plynule vyplni (nebo vyprazdni) sipku; pri vyplneni volitelne vyleti jiskry. Pouziti u rozbalovacich karet. */
    fun fillTo(on: Boolean, color: Int, withSparks: Boolean) {
        running?.cancel()
        fillPaint.color = color
        val start = progress
        val end = if (on) 1f else 0f
        running = ValueAnimator.ofFloat(start, end).apply {
            duration = FILL_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { progress = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    running = null
                    if (on && withSparks) sparkBurst()
                }
            })
            start()
        }
    }

    private fun sparkBurst() {
        running = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = GO_MS
            addUpdateListener {
                spark = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    spark = -1f
                    running = null
                    invalidate()
                }
            })
            start()
        }
    }

    override fun onDetachedFromWindow() {
        running?.cancel()
        super.onDetachedFromWindow()
    }

    companion object {
        const val YELLOW = 0xFFFFD60A.toInt()
        const val RED = 0xFFEF4444.toInt()
        private const val SPARKS = 7
        private const val FILL_MS = 320L
        private const val GO_MS = 280L
        private const val BACK_MS = 220L
    }
}

/** Ikona obnovy: nedokonceny kruh, na jehoz konci je sipka (kresleni bez alokaci, tvary se pocitaji pri zmene velikosti). */
class RestoreIconView(context: Context, color: Int) : View(context) {
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = android.graphics.RectF()
    private val head = Path()

    init {
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = 2f * d
        paint.color = color
    }

    var color: Int
        get() = paint.color
        set(value) {
            paint.color = value
            invalidate()
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val r = min(w, h) * 0.28f
        val cx = w / 2f
        val cy = h / 2f
        oval.set(cx - r, cy - r, cx + r, cy + r)
        // Kruh zacina vpravo nahore a konci tesne pred zacatkem (mezera), sipka na konci miri po smeru kresleni.
        val end = Math.toRadians((START + SWEEP).toDouble())
        val ex = cx + r * cos(end).toFloat()
        val ey = cy + r * sin(end).toFloat()
        val tx = -sin(end).toFloat()
        val ty = cos(end).toFloat()
        val len = r * 0.7f
        head.reset()
        for (side in intArrayOf(-1, 1)) {
            val a = Math.toRadians(150.0 * side)
            val dx = tx * cos(a).toFloat() - ty * sin(a).toFloat()
            val dy = tx * sin(a).toFloat() + ty * cos(a).toFloat()
            head.moveTo(ex, ey)
            head.lineTo(ex + dx * len, ey + dy * len)
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawArc(oval, START, SWEEP, false, paint)
        canvas.drawPath(head, paint)
    }

    private companion object {
        const val START = -40f
        const val SWEEP = 280f
    }
}
