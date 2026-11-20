package cz.reallyfast

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * Tenke carove ikony pro radky nastaveni (kresleno cestami v souradnicich 24x24, zadne obrazky).
 * Cesty se pripravuji jednou pri zmene velikosti, `draw` nealokuje.
 */
class LineIcon(private val kind: Int, color: Int, private val size: Int, strokeDp: Float) : Drawable() {
    companion object {
        const val PALETTE = 0
        const val BOOK = 1
        const val MIC = 2
        const val KEYBOARD = 3
        const val INFO = 4
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
        strokeWidth = strokeDp
    }
    private val src = Path()
    private val dst = Path()
    private val matrix = Matrix()
    private val oval = RectF()

    init {
        build()
    }

    /** Bod: nulove dlouha cara se zaoblenym koncem se vykresli jako tecka. */
    private fun dot(x: Float, y: Float) {
        src.moveTo(x, y)
        src.lineTo(x + 0.01f, y)
    }

    private fun build() {
        val p = src
        when (kind) {
            PALETTE -> {
                // paleta: oblouk s vyrezem a tri tecky barev
                oval.set(3f, 3f, 21f, 21f)
                p.addArc(oval, -60f, 330f)
                p.lineTo(18.5f, 15.5f)
                p.cubicTo(17f, 14.5f, 15f, 15.5f, 15.5f, 17.5f)
                p.cubicTo(16f, 19.5f, 15f, 21f, 13f, 21f)
                dot(8f, 11f)
                dot(11f, 7.5f)
                dot(15.5f, 8.5f)
            }
            BOOK -> {
                // otevrena kniha
                p.moveTo(12f, 6.5f)
                p.cubicTo(10f, 4.8f, 6.5f, 4.6f, 3.5f, 5.6f)
                p.lineTo(3.5f, 18.6f)
                p.cubicTo(6.5f, 17.6f, 10f, 17.8f, 12f, 19.5f)
                p.cubicTo(14f, 17.8f, 17.5f, 17.6f, 20.5f, 18.6f)
                p.lineTo(20.5f, 5.6f)
                p.cubicTo(17.5f, 4.6f, 14f, 4.8f, 12f, 6.5f)
                p.moveTo(12f, 6.5f)
                p.lineTo(12f, 19.5f)
            }
            MIC -> {
                // mikrofon: kapsle, oblouk stojanu a noha
                oval.set(9f, 3f, 15f, 15f)
                p.addRoundRect(oval, 3f, 3f, Path.Direction.CW)
                oval.set(5.5f, 7f, 18.5f, 18f)
                p.addArc(oval, 0f, 180f)
                p.moveTo(12f, 18.5f)
                p.lineTo(12f, 21f)
                p.moveTo(9f, 21f)
                p.lineTo(15f, 21f)
            }
            KEYBOARD -> {
                // klavesnice: ram, dva radky tecek a mezernik
                oval.set(2.5f, 6f, 21.5f, 18f)
                p.addRoundRect(oval, 2.5f, 2.5f, Path.Direction.CW)
                var x = 6.5f
                while (x <= 17.6f) {
                    dot(x, 10f)
                    dot(x, 13f)
                    x += 3.7f
                }
                p.moveTo(8.5f, 15.6f)
                p.lineTo(15.5f, 15.6f)
            }
            INFO -> {
                // informace: kruh, svisla cara a tecka
                oval.set(3f, 3f, 21f, 21f)
                p.addOval(oval, Path.Direction.CW)
                p.moveTo(12f, 11f)
                p.lineTo(12f, 16.5f)
                dot(12f, 7.8f)
            }
        }
    }

    override fun onBoundsChange(bounds: Rect) {
        matrix.setScale(bounds.width() / 24f, bounds.height() / 24f)
        matrix.postTranslate(bounds.left.toFloat(), bounds.top.toFloat())
        src.transform(matrix, dst)
    }

    override fun draw(canvas: Canvas) {
        canvas.drawPath(dst, paint)
    }

    override fun getIntrinsicWidth() = size

    override fun getIntrinsicHeight() = size

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
