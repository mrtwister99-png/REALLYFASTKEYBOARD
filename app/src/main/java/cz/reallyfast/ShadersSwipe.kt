package cz.reallyfast

import android.annotation.TargetApi
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RuntimeShader

/** Shadery pro swipe (AGSL, Android 13+): vlna pri prejeti klavesy / pulz pri spatnem tahu a rozprsknuti cary na konci tahu. */

@TargetApi(33)
private fun RuntimeShader.setRgb3(name: String, c: Int) {
    setFloatUniform(name, Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f)
}

/**
 * Prstenec pres jednu klavesu: z bodu `cen` (px v klavese) se rozbehne vlna (`pulses` = 1) nebo dve vlny a cervene zablesknuti
 * (`pulses` = 2, spatny tah). `t` 0..1 je pruchod animace. Orezano na zaobleny tvar klavesy.
 */
@TargetApi(33)
class RingFx {
    private val shader = RuntimeShader(SRC)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        paint.shader = shader
    }

    fun draw(canvas: Canvas, left: Float, top: Float, w: Float, h: Float, radius: Float, t: Float, cx: Float, cy: Float, pulses: Float, color: Int) {
        shader.setFloatUniform("size", w, h)
        shader.setFloatUniform("rad", radius)
        shader.setFloatUniform("t", t)
        shader.setFloatUniform("cen", cx, cy)
        shader.setFloatUniform("pulses", pulses)
        shader.setRgb3("c", color)
        canvas.save()
        canvas.translate(left, top)
        canvas.drawRect(0f, 0f, w, h, paint)
        canvas.restore()
    }

    private companion object {
        const val SRC = """
            uniform float2 size;
            uniform float rad;
            uniform float t;
            uniform float2 cen;
            uniform float pulses;
            uniform float3 c;

            half4 main(float2 fc) {
                float2 hs = size * 0.5;
                float2 q = abs(fc - hs) - hs + rad;
                float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - rad;
                float inside = 1.0 - smoothstep(-1.0, 0.5, d);
                float dist = length(fc - cen);
                float maxR = length(size) * 0.8;
                float acc = 0.0;
                for (int i = 0; i < 2; i++) {
                    if (float(i) < pulses) {
                        float tl = (t - float(i) * 0.4) / 0.6;
                        if (tl > 0.0 && tl < 1.0) {
                            float k = (dist - tl * maxR) / (0.12 * maxR);
                            acc += exp(-k * k) * (1.0 - tl) * (1.0 - tl) * 1.6;
                        }
                    }
                }
                float flash = 0.0;
                if (pulses > 1.5) flash = (1.0 - t) * 0.3;
                float al = clamp((acc * 0.9 + flash) * inside, 0.0, 1.0);
                return half4(half3(c * al), half(al));
            }
        """
    }
}

/**
 * Rozprsknuti cary: po konci tahu se z 32 bodu podel cary rozletí jiskry (kazda ma nahodny smer, rychlost, zpozdeni a velikost).
 * Plocha je ohraniceni cary; body `pts` jsou v souradnicich plochy (32 dvojic x, y).
 */
@TargetApi(33)
class BurstFx {
    private val shader = RuntimeShader(SRC)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        paint.shader = shader
    }

    fun draw(canvas: Canvas, left: Float, top: Float, w: Float, h: Float, ms: Float, seed: Float, dens: Float, pts: FloatArray, c1: Int, c2: Int) {
        shader.setFloatUniform("size", w, h)
        shader.setFloatUniform("ms", ms)
        shader.setFloatUniform("seed", seed)
        shader.setFloatUniform("dens", dens)
        shader.setFloatUniform("pts", pts)
        shader.setRgb3("c1", c1)
        shader.setRgb3("c2", c2)
        canvas.save()
        canvas.translate(left, top)
        canvas.drawRect(0f, 0f, w, h, paint)
        canvas.restore()
    }

    private companion object {
        const val SRC = """
            uniform float2 size;
            uniform float ms;
            uniform float seed;
            uniform float dens;
            uniform float2 pts[32];
            uniform float3 c1;
            uniform float3 c2;

            float h(float n) {
                return fract(sin(n * 12.9898 + seed * 0.731) * 43758.5453);
            }

            half4 main(float2 fc) {
                float3 col = float3(0.0);
                float acc = 0.0;
                for (int i = 0; i < 32; i++) {
                    float fi = float(i);
                    float delay = h(fi * 1.7 + 0.1) * 70.0;
                    float life = 320.0 + h(fi * 2.3 + 1.0) * 420.0;
                    float q = (ms - delay) / life;
                    if (q > 0.0 && q < 1.0) {
                        float ang = h(fi * 3.1 + 2.0) * 6.2831853;
                        float reach = (14.0 + 40.0 * h(fi * 7.9 + 3.0)) * dens;
                        float e = 1.0 - (1.0 - q) * (1.0 - q);
                        float2 pos = pts[i] + float2(cos(ang), sin(ang)) * reach * e + float2(0.0, 26.0 * dens * q * q);
                        float r = (1.0 + 2.4 * h(fi * 9.7 + 5.0)) * dens * (1.0 - 0.7 * q);
                        float al = (1.0 - smoothstep(r * 0.55, r, length(fc - pos))) * (1.0 - q * q);
                        float3 sc = mix(c1, c2, fract(fi * 0.37));
                        if (mod(fi, 4.0) < 0.5) sc = mix(sc, float3(1.0), 0.6);
                        col += sc * al;
                        acc += al;
                    }
                }
                float aa = clamp(acc, 0.0, 1.0);
                float3 cc = min(col, float3(aa));
                return half4(half3(cc), half(aa));
            }
        """
    }
}
