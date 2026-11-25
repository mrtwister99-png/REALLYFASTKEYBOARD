package cz.reallyfast

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View

/**
 * Vyber barvy ctvercem: vlevo ctverec (sytost doprava, jas nahoru) pro zvoleny odstin, vpravo svisly pruh odstinu.
 * Shadery se stavi jen pri zmene velikosti a odstinu (ne pri kresleni). `onPicking` se vola behem tazeni,
 * `onCommit` az po zvednuti prstu.
 */
@SuppressLint("ViewConstructor")
class ColorPlaneView(context: Context) : View(context) {
    var onPicking: ((Int) -> Unit)? = null
    var onCommit: ((Int) -> Unit)? = null

    var hue = 0f
        private set
    var sat = 0f
        private set
    var value = 1f
        private set

    private val d = resources.displayMetrics.density
    private val hsv = FloatArray(3)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val plane = RectF()
    private val bar = RectF()
    private var svShader: LinearGradient? = null
    private var valShader: LinearGradient? = null
    private var hueShader: LinearGradient? = null
    private var mode = MODE_NONE
    private val hueColors = intArrayOf(
        0xFFFF0000.toInt(), 0xFFFFFF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FFFF.toInt(),
        0xFF0000FF.toInt(), 0xFFFF00FF.toInt(), 0xFFFF0000.toInt(),
    )

    /** Nastavi vyber podle barvy (bez volani posluchacu). */
    fun setColor(color: Int) {
        Color.colorToHSV(color, hsv)
        hue = hsv[0]
        sat = hsv[1]
        value = hsv[2]
        rebuildHue()
        invalidate()
    }

    fun currentColor(): Int {
        hsv[0] = hue
        hsv[1] = sat
        hsv[2] = value
        return Color.HSVToColor(hsv)
    }

    private fun rebuildHue() {
        if (plane.width() <= 0f) return
        hsv[0] = hue
        hsv[1] = 1f
        hsv[2] = 1f
        svShader = LinearGradient(plane.left, 0f, plane.right, 0f, Color.WHITE, Color.HSVToColor(hsv), Shader.TileMode.CLAMP)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val barW = 34f * d
        val gap = 12f * d
        plane.set(0f, 0f, w - barW - gap, h.toFloat())
        bar.set(w - barW, 0f, w.toFloat(), h.toFloat())
        valShader = LinearGradient(0f, plane.top, 0f, plane.bottom, 0x00000000, Color.BLACK, Shader.TileMode.CLAMP)
        hueShader = LinearGradient(0f, bar.top, 0f, bar.bottom, hueColors, null, Shader.TileMode.CLAMP)
        rebuildHue()
    }

    override fun onDraw(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.shader = svShader
        canvas.drawRect(plane, paint)
        paint.shader = valShader
        canvas.drawRect(plane, paint)
        paint.shader = hueShader
        canvas.drawRect(bar, paint)
        paint.shader = null
        // Zamerovac ve ctverci: tenke cary a krouzek.
        val mx = plane.left + sat * plane.width()
        val my = plane.top + (1f - value) * plane.height()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * d
        paint.color = 0xCCFFFFFF.toInt()
        canvas.drawLine(plane.left, my, plane.right, my, paint)
        canvas.drawLine(mx, plane.top, mx, plane.bottom, paint)
        paint.strokeWidth = 3f * d
        paint.color = 0x99000000.toInt()
        canvas.drawCircle(mx, my, 9f * d, paint)
        paint.strokeWidth = 2f * d
        paint.color = Color.WHITE
        canvas.drawCircle(mx, my, 9f * d, paint)
        // Znacka odstinu v pruhu.
        val hy = bar.top + hue / 360f * bar.height()
        paint.strokeWidth = 4f * d
        paint.color = 0x99000000.toInt()
        canvas.drawLine(bar.left, hy, bar.right, hy, paint)
        paint.strokeWidth = 2f * d
        paint.color = Color.WHITE
        canvas.drawLine(bar.left, hy, bar.right, hy, paint)
    }

    private fun pick(x: Float, y: Float) {
        if (mode == MODE_PLANE) {
            sat = ((x - plane.left) / plane.width()).coerceIn(0f, 1f)
            value = 1f - ((y - plane.top) / plane.height()).coerceIn(0f, 1f)
        } else if (mode == MODE_BAR) {
            hue = (((y - bar.top) / bar.height()).coerceIn(0f, 1f) * 360f).coerceAtMost(359.9f)
            rebuildHue()
        }
        invalidate()
        onPicking?.invoke(currentColor())
    }

    @SuppressLint("ClickableViewAccessibility") // barvu lze zadat i hex kodem v dialogu
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                mode = if (e.x >= bar.left - 6f * d) MODE_BAR else MODE_PLANE
                pick(e.x, e.y)
            }
            MotionEvent.ACTION_MOVE -> pick(e.x, e.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                mode = MODE_NONE
                onCommit?.invoke(currentColor())
            }
        }
        return true
    }

    private companion object {
        const val MODE_NONE = 0
        const val MODE_PLANE = 1
        const val MODE_BAR = 2
    }
}
