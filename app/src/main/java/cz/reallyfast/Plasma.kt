package cz.reallyfast

import android.annotation.TargetApi
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RuntimeShader

/**
 * Efekt Plazma: shader (AGSL, Android 13+) nakresleny jen pres jednu klavesu po dobu klepnuti.
 * Z nahodneho bodu klavesy se rozbehne zvlnene kruhove svetlo ve dvou barvach a pomalu zhasne.
 * Shader i paint se vytvari jednou (pri zmene nastaveni); pri kresleni se jen meni cisla (uniformy), nic se nealokuje.
 */
@TargetApi(33)
class PlasmaFx {
    private val shader = RuntimeShader(SRC)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        paint.shader = shader
    }

    /** `t` 0..1 je pruchod animace, `sx`/`sy` (0..1) bod, odkud se vlna rozbehne. */
    fun draw(canvas: Canvas, left: Float, top: Float, w: Float, h: Float, radius: Float, t: Float, sx: Float, sy: Float, c1: Int, c2: Int) {
        shader.setFloatUniform("size", w, h)
        shader.setFloatUniform("t", t)
        shader.setFloatUniform("seed", sx, sy)
        shader.setFloatUniform("c1", Color.red(c1) / 255f, Color.green(c1) / 255f, Color.blue(c1) / 255f)
        shader.setFloatUniform("c2", Color.red(c2) / 255f, Color.green(c2) / 255f, Color.blue(c2) / 255f)
        canvas.save()
        canvas.translate(left, top)
        canvas.drawRoundRect(0f, 0f, w, h, radius, radius, paint)
        canvas.restore()
    }

    private companion object {
        const val SRC = """
            uniform float2 size;
            uniform float t;
            uniform float2 seed;
            uniform float3 c1;
            uniform float3 c2;

            half4 main(float2 p) {
                float2 q = p / size;
                float2 d = (q - seed) * float2(size.x / size.y, 1.0);
                float r = length(d);
                float edge = (r - t * 1.7) * 6.0;
                float ring = exp(-edge * edge);
                float wob = 0.5 + 0.5 * sin(r * 20.0 - t * 15.0 + sin(q.x * 9.0 + t * 6.0) * 1.6);
                float m = ring * (0.5 + 0.5 * wob);
                float mixv = clamp(q.x + 0.3 * sin(t * 5.0 + q.y * 6.0), 0.0, 1.0);
                float3 col = mix(c1, c2, mixv);
                float a = clamp(m * (1.0 - t) * 1.4, 0.0, 1.0);
                return half4(half3(col * a), half(a));
            }
        """
    }
}
