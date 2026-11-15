package cz.reallyfast

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Barevne kolo: odstin = uhel, sytost = vzdalenost od stredu. Kolo se vykresli jednou do bitmapy pri
 * zmene velikosti (ne za behu); jas (`value`) se dokresluje pruhledne cernou vrstvou. Kulaty ukazatel
 * znaci vyber. `onPicking` se vola behem tazeni, `onCommit` az po zvednuti prstu.
 */
@SuppressLint("ViewConstructor")
class ColorWheelView(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val hsv = FloatArray(3)
    private val pixelHsv = floatArrayOf(0f, 0f, 1f)
    private var hue = 0f
    private var sat = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var wheel: Bitmap? = null
    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    private val markerR = 9f * d

    var onPicking: ((Int) -> Unit)? = null
    var onCommit: ((Int) -> Unit)? = null

    /** Jas 0..1 (nezavisly na kole). */
    var value = 1f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }

    /** Nastavi vyber podle barvy (bez volani posluchacu). */
    fun setColor(color: Int) {
        Color.colorToHSV(color, hsv)
        hue = hsv[0]
        sat = hsv[1]
        value = hsv[2]
        invalidate()
    }

    fun currentColor(): Int {
        hsv[0] = hue
        hsv[1] = sat
        hsv[2] = value
        return Color.HSVToColor(hsv)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f
        radius = min(w, h) / 2f - markerR - d
        wheel?.recycle()
        wheel = null
        val size = (radius * 2f).toInt()
        if (size < 4) return
        val px = IntArray(size * size)
        val r = size / 2f
        for (y in 0 until size) {
            for (x in 0 until size) {
                val dx = x + 0.5f - r
                val dy = y + 0.5f - r
                val dist = hypot(dx, dy)
                if (dist > r) continue
                pixelHsv[0] = ((Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()) + 360f) % 360f
                pixelHsv[1] = dist / r
                px[y * size + x] = Color.HSVToColor(pixelHsv)
            }
        }
        wheel = Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = wheel ?: return
        canvas.drawBitmap(bmp, cx - bmp.width / 2f, cy - bmp.height / 2f, bitmapPaint)
        // Jas: cerna vrstva s pruhlednosti podle jasu.
        paint.style = Paint.Style.FILL
        paint.color = ((255f * (1f - value)).toInt().coerceIn(0, 255) shl 24)
        canvas.drawCircle(cx, cy, radius, paint)
        val ang = Math.toRadians(hue.toDouble())
        val mx = cx + (cos(ang) * sat * radius).toFloat()
        val my = cy + (sin(ang) * sat * radius).toFloat()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3.5f * d
        paint.color = 0x99000000.toInt()
        canvas.drawCircle(mx, my, markerR, paint)
        paint.strokeWidth = 2f * d
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(mx, my, markerR, paint)
    }

    private fun pick(x: Float, y: Float) {
        val dx = x - cx
        val dy = y - cy
        val dist = hypot(dx, dy)
        sat = if (radius > 0f) min(1f, dist / radius) else 0f
        if (dist > 0.5f) hue = (Math.toDegrees(atan2(dy, dx).toDouble()).toFloat() + 360f) % 360f
        invalidate()
        onPicking?.invoke(currentColor())
    }

    @SuppressLint("ClickableViewAccessibility") // barvu lze zadat jen prstem; hodnotu vidi i TalkBack u vzorku
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                pick(e.x, e.y)
            }
            MotionEvent.ACTION_MOVE -> pick(e.x, e.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                onCommit?.invoke(currentColor())
            }
        }
        return true
    }
}
