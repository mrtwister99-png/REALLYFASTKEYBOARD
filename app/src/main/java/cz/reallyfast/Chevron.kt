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
class ChevronView(
    context: Context,
    private val pointsRight: Boolean,
    outline: Int,
    @Suppress("UNUSED_PARAMETER") hole: Int = 0,
    /** Tvar sipky zpet (obrys sipky s ohnutym ocasem) misto dute sipky. */
    private val backShape: Boolean = false,
) : View(context) {
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
        ringPaint.style = if (backShape) Paint.Style.STROKE else Paint.Style.FILL
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = 0xFFFFD60A.toInt()
        sparkPaint.strokeWidth = 2.2f * d
    }

    /** Sipka zpet podle predlohy (obrys, vevnitr pruhledno): souradnice z obrazku 500x500, vystredene a zmensene na pohled. */
    private fun buildBackShape(w: Int, h: Int) {
        val sc = min(w, h) * 0.84f / 400f
        val cx = w / 2f
        val cy = h / 2f
        fun px(x: Float) = cx + (x - 249f) * sc
        fun py(y: Float) = cy + (y - 241.5f) * sc
        ring.reset()
        ring.moveTo(px(50f), py(243f))
        ring.lineTo(px(167f), py(128f))
        ring.lineTo(px(167f), py(185f))
        ring.lineTo(px(448f), py(185f))
        ring.lineTo(px(448f), py(197f))
        ring.quadTo(px(432f), py(300f), px(345f), py(300f))
        ring.lineTo(px(170f), py(300f))
        ring.lineTo(px(170f), py(355f))
        ring.close()
        ringPaint.strokeWidth = (22f * sc).coerceAtLeast(1.4f * d)
        size = min(w, h) * 0.5f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (backShape) {
            buildBackShape(w, h)
            return
        }
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
        if (backShape) {
            if (progress > 0f) {
                canvas.save()
                canvas.clipRect(0f, 0f, width * progress, height.toFloat())
                canvas.drawPath(ring, fillPaint)
                canvas.restore()
            }
            canvas.drawPath(ring, ringPaint)
            return
        }
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

    /** Kratke zablesknuti vyplne (zelena nebo zluta, nahodne) pri klepnuti na sipku posuvniku. */
    fun flash() {
        running?.cancel()
        fillPaint.color = if (kotlin.random.Random.nextBoolean()) GREEN else YELLOW
        running = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
            duration = 420L
            addUpdateListener { progress = it.animatedValue as Float }
            start()
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
        const val GREEN = 0xFF22C55E.toInt()
        private const val SPARKS = 7
        private const val FILL_MS = 320L
        private const val GO_MS = 280L
        private const val BACK_MS = 220L
    }
}

/** Ikona obnovy (obrazek kruhove sipky): barvi se podle motivu, `spin` ji pomalu protoci o 360 stupnu. */
class RestoreIconView(context: Context, color: Int) : android.widget.ImageView(context) {
    init {
        setImageResource(R.drawable.ic_restore)
        scaleType = ScaleType.FIT_CENTER
        val p = (9f * resources.displayMetrics.density).toInt()
        setPadding(p, p, p, p)
        setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)
    }

    var color: Int = color
        set(value) {
            field = value
            setColorFilter(value, android.graphics.PorterDuff.Mode.SRC_IN)
        }

    /** Pomale otoceni o 360 stupnu (po potvrzeni obnovy). */
    fun spin() {
        animate().cancel()
        rotation = 0f
        animate().rotation(-360f).setDuration(1400L).setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
            .withEndAction { rotation = 0f }.start()
    }
}

/**
 * Vlna kolem tlacitka: tri oblouky po 20 % obvodu se rozbehnou od okraje tlacitka ven, mizi a meni barvu ze zlute do zelene.
 * Pohled lezi pres cele tlacitko (MATCH_PARENT) a rodice nesmeji orezavat potomky (clipChildren = false). onDraw nealokuje.
 */
class RingWaveView(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = android.graphics.RectF()
    private var t = -1f
    private var reach = 28f * d

    /** Vlna sama nema zadnou vlastni velikost: nesmi roztahnout rodice (jinak tlacitko Ulozit zabere celou sirku radku). */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(widthMeasureSpec) else 0
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else 0
        setMeasuredDimension(w, h)
    }

    private val animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
        addUpdateListener {
            t = it.animatedValue as Float
            invalidate()
        }
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                t = -1f
                invalidate()
            }
        })
    }

    init {
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        isClickable = false
    }

    fun fire(reachDp: Float, ms: Long) {
        reach = reachDp * d
        animator.cancel()
        animator.duration = ms
        animator.start()
    }

    override fun onDraw(canvas: Canvas) {
        if (t < 0f) return
        val grow = reach * t
        oval.set(-grow, -grow, width + grow, height + grow)
        paint.color = Theme.blend(ChevronView.YELLOW, ChevronView.GREEN, t)
        paint.alpha = ((1f - t) * 255f).toInt().coerceIn(0, 255)
        paint.strokeWidth = (4f - 2f * t) * d
        val base = 60f * t
        for (i in 0 until 3) canvas.drawArc(oval, base + i * 120f, 72f, false, paint)
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }
}

/**
 * Znacka kroku nastaveni: obrys zatrzitka je videt vzdy, po splneni kroku se pres nej objevi zelena vyplnena a cela znacka
 * se zvetsi. Dva obrazky (stejne platno), obrys se barvi podle motivu.
 */
class StepMark(context: Context, lineColor: Int) : android.widget.FrameLayout(context) {
    private val line = android.widget.ImageView(context)
    private val fill = android.widget.ImageView(context)
    private var done = false

    init {
        clipChildren = false
        line.setImageResource(R.drawable.ic_check_line)
        line.setColorFilter(lineColor, android.graphics.PorterDuff.Mode.SRC_IN)
        fill.setImageResource(R.drawable.ic_check_fill)
        fill.alpha = 0f
        fill.scaleX = 0.4f
        fill.scaleY = 0.4f
        val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        addView(line, lp)
        addView(fill, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Nastavi stav; zmena z "nehotovo" na "hotovo" se animuje (vyplneni pribyde a znacka se zvetsi). */
    fun setDone(value: Boolean, animate: Boolean) {
        if (value == done) return
        done = value
        val target = if (value) 1f else 0f
        val zoom = if (value) 1.35f else 1f
        if (!animate) {
            fill.alpha = target
            fill.scaleX = if (value) 1f else 0.4f
            fill.scaleY = fill.scaleX
            scaleX = zoom
            scaleY = zoom
            return
        }
        fill.animate().cancel()
        fill.animate().alpha(target).scaleX(if (value) 1f else 0.4f).scaleY(if (value) 1f else 0.4f).setDuration(420L)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.6f)).start()
        animate().cancel()
        animate().scaleX(zoom).scaleY(zoom).setDuration(420L).start()
    }
}

/**
 * Ozubene kolo, ktere se po vteri po sobe (jednou za 10 s) pootoci o nahodny uhel z rozsahu `minDeg`..`maxDeg`.
 * Vsechna kola se toci stejnou rychlosti (stupnu za sekundu), proto delsi otoceni trva dele. Bez animaci jen stoji.
 */
class GearView(context: Context, res: Int, tint: Int, private val minDeg: Int, private val maxDeg: Int) : android.widget.ImageView(context) {
    /** Je-li zapnuto, kazde pootoceni zvoli nahodny smer (kola se mohou tocit stejne i kazde jinak). */
    var randomDirection = false

    private val tick = object : Runnable {
        override fun run() {
            val deg = minDeg + kotlin.random.Random.nextInt(maxDeg - minDeg + 1)
            val sign = if (randomDirection && kotlin.random.Random.nextBoolean()) -1f else 1f
            animate().rotationBy(sign * deg.toFloat()).setDuration((deg * 1000L / SPEED_DEG_PER_S)).setInterpolator(android.view.animation.LinearInterpolator()).start()
            postDelayed(this, PERIOD_MS)
        }
    }

    init {
        setImageResource(res)
        scaleType = ScaleType.FIT_CENTER
        setColorFilter(tint, android.graphics.PorterDuff.Mode.SRC_IN)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (RustBridge.settings.animations) postDelayed(tick, 1500L)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        animate().cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val PERIOD_MS = 10000L
        const val SPEED_DEG_PER_S = 45L
    }
}

/**
 * Posuvne tlacitko posuvniku: ctverecek v barve akcentu. Pri tazeni jednou za cas "glitchne" do zlutooranzove hvezdicky
 * (dvakrat vetsi) se dvema kopiemi obrysu, tyrkysovou a cervenou, a po chvili se vrati. Kresleni nealokuje.
 */
class GlitchThumb(private val sizePx: Int, private val color: Int, private val host: View) : android.graphics.drawable.Drawable() {
    private val d = host.resources.displayMetrics.density
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = android.graphics.RectF()
    private val star = Path()
    private var glitching = false
    private val reset = Runnable {
        glitching = false
        invalidateSelf()
        host.invalidate()
    }

    init {
        // Hvezdicka v jednotkovych souradnicich (polomer 0,5 kolem stredu).
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) 0.5f else 0.21f
            val a = -PI / 2 + i * PI / 5
            val x = r * cos(a).toFloat()
            val y = r * sin(a).toFloat()
            if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
        }
        star.close()
        linePaint.style = Paint.Style.STROKE
        linePaint.strokeJoin = Paint.Join.ROUND
    }

    /** Na `ms` zmeni tvar na hvezdicku. */
    fun glitch(ms: Long) {
        glitching = true
        host.removeCallbacks(reset)
        host.postDelayed(reset, ms)
        invalidateSelf()
        host.invalidate()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        if (!glitching) {
            fillPaint.color = color
            rect.set(cx - sizePx / 2f, cy - sizePx / 2f, cx + sizePx / 2f, cy + sizePx / 2f)
            canvas.drawRoundRect(rect, 3f * d, 3f * d, fillPaint)
            return
        }
        val big = sizePx * 2f
        linePaint.strokeWidth = 1.8f * d / big
        for (k in 0 until 3) {
            canvas.save()
            canvas.translate(cx + (if (k == 0) 3f * d else if (k == 1) -3f * d else 0f), cy + (if (k == 0) -2f * d else if (k == 1) 2f * d else 0f))
            canvas.scale(big, big)
            if (k < 2) {
                linePaint.color = if (k == 0) 0xFF22D3EE.toInt() else 0xFFEF4444.toInt()
                canvas.drawPath(star, linePaint)
            } else {
                fillPaint.color = 0xFFFFA800.toInt()
                canvas.drawPath(star, fillPaint)
            }
            canvas.restore()
        }
    }

    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/** Rozprsknuti castic (kratke cary) od stredu pohledu; pohled by mel lezet nad tlacitkem a nesmi orezavat okoli (clipChildren = false). */
class BurstView(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var t = -1f
    private var count = 10
    private var reach = 0f
    private var anim: ValueAnimator? = null

    /** Bez vlastni velikosti: jiskry nesmi roztahnout rodice (jinak zmizi pole vedle tlacitka Pridat). */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(widthMeasureSpec) else 0
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else 0
        setMeasuredDimension(w, h)
    }

    init {
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 2.6f * d
        isClickable = false
        isFocusable = false
    }

    /** Rozprskne `n` cestic barvy `color` do vzdalenosti `reachDp` od stredu; trva `ms`. */
    fun burst(color: Int, n: Int = 10, reachDp: Float = 46f, ms: Long = 520L) {
        anim?.cancel()
        paint.color = color
        count = n
        reach = reachDp * d
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                t = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    t = -1f
                    invalidate()
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (t < 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val a = ((1f - t) * 255f).toInt().coerceIn(0, 255)
        val base = (paint.color and 0x00FFFFFF)
        paint.color = base or (a shl 24)
        for (i in 0 until count) {
            val ang = i * 2.0 * PI / count + 0.35
            val r0 = reach * (0.25f + 0.75f * t)
            val r1 = r0 + reach * 0.3f * (1f - t)
            val c = cos(ang).toFloat()
            val s = sin(ang).toFloat()
            canvas.drawLine(cx + c * r0, cy + s * r0, cx + c * r1, cy + s * r1, paint)
        }
        paint.color = base or 0xFF000000.toInt()
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }
}
