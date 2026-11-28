package cz.reallyfast

import android.annotation.TargetApi
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RuntimeShader

/**
 * Efekty klaves jako shadery (AGSL, Android 13+). Kazdy se vytvori jednou (pri zmene nastaveni), pri kresleni se jen
 * meni cisla (uniformy) a kresli se jeden obdelnik; nic se nealokuje.
 */

/** Soucasti: nastavi barvu jako tri slozky 0..1. */
@TargetApi(33)
private fun RuntimeShader.setColor(name: String, c: Int) {
    setFloatUniform(name, Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f)
}

/**
 * Neon: mekka zare kolem klavesy (vzdalenost od zaobleneho obdelniku), uprostred bile jadro, ven barva efektu.
 * `a` (0..1) je sila zare, ktera po klepnuti doznivá.
 */
@TargetApi(33)
class GlowFx {
    private val shader = RuntimeShader(SRC)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        paint.shader = shader
    }

    /** Plocha `left`, `top`, `w` x `h` je klavesa zvetsena o `pad` na vsech stranach; `radius` je zaobleni klavesy. */
    fun draw(canvas: Canvas, left: Float, top: Float, w: Float, h: Float, radius: Float, pad: Float, a: Float, color: Int) {
        shader.setFloatUniform("size", w, h)
        shader.setFloatUniform("pad", pad)
        shader.setFloatUniform("rad", radius)
        shader.setFloatUniform("a", a)
        shader.setColor("c", color)
        canvas.save()
        canvas.translate(left, top)
        canvas.drawRect(0f, 0f, w, h, paint)
        canvas.restore()
    }

    private companion object {
        const val SRC = """
            uniform float2 size;
            uniform float pad;
            uniform float rad;
            uniform float a;
            uniform float3 c;

            half4 main(float2 p) {
                float2 hs = size * 0.5;
                float2 q = abs(p - hs) - (hs - pad) + rad;
                float dist = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - rad;
                float outside = max(dist, 0.0);
                float falloff = exp(-outside / (pad * 0.42));
                float edge = 1.0 - smoothstep(pad * 0.85, pad, outside);
                float core = exp(-outside / (pad * 0.12));
                float3 col = mix(c, float3(1.0), core * 0.55);
                float al = clamp(falloff * edge * a * 0.95, 0.0, 1.0);
                return half4(half3(col * al), half(al));
            }
        """
    }
}

/**
 * Vybuch: kolem klavesy se rozprsknou jiskry (18, kazda ma nahodny smer, rychlost, zpozdeni, dobu letu, zakriveni a velikost)
 * a vetsi kusy vyplne klavesy (7), ktere se otaceji a miznou. Vse jednim pruchodem shaderu pres ctverec kolem klavesy.
 */
@TargetApi(33)
class PopFx {
    private val shader = RuntimeShader(SRC)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        paint.shader = shader
    }

    /**
     * `box` je strana ctverce se stredem v klavese, `ms` cas od zacatku vybuchu, `seed` zrno nahody, `hw`/`hh` pulrozmery klavesy,
     * `dens` hustota displeje, `sparkColor` barva jisker a `chunkColor` barva kusu (barva klavesy).
     */
    fun draw(canvas: Canvas, left: Float, top: Float, box: Float, ms: Float, seed: Float, hw: Float, hh: Float, dens: Float, radius: Float, sparkColor: Int, chunkColor: Int) {
        shader.setFloatUniform("size", box, box)
        shader.setFloatUniform("ms", ms)
        shader.setFloatUniform("seed", seed)
        shader.setFloatUniform("hw", hw)
        shader.setFloatUniform("hh", hh)
        shader.setFloatUniform("dens", dens)
        shader.setFloatUniform("rad", radius)
        shader.setColor("c1", sparkColor)
        shader.setColor("c2", chunkColor)
        canvas.save()
        canvas.translate(left, top)
        canvas.drawRect(0f, 0f, box, box, paint)
        canvas.restore()
    }

    private companion object {
        const val SRC = """
            uniform float2 size;
            uniform float ms;
            uniform float seed;
            uniform float hw;
            uniform float hh;
            uniform float dens;
            uniform float rad;
            uniform float3 c1;
            uniform float3 c2;

            float h(float n) {
                return fract(sin(n * 12.9898 + seed * 0.731) * 43758.5453);
            }

            float sdBox(float2 p, float2 b, float r) {
                float2 q = abs(p) - b + r;
                return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
            }

            half4 main(float2 p) {
                float2 d0 = p - size * 0.5;
                float3 col = float3(0.0);
                float acc = 0.0;
                for (int i = 0; i < 18; i++) {
                    float fi = float(i);
                    float delay = h(fi * 1.7 + 0.1) * 90.0;
                    float life = 260.0 + h(fi * 2.3 + 1.0) * 480.0;
                    float q = (ms - delay) / life;
                    if (q > 0.0 && q < 1.0) {
                        float ang = h(fi * 3.1 + 2.0) * 6.2831853 + (h(fi * 5.3 + 4.0) - 0.5) * 1.6 * q;
                        float reach = hh * (0.9 + 2.4 * h(fi * 7.9 + 3.0));
                        float e = 1.0 - (1.0 - q) * (1.0 - q);
                        float2 pos = float2(cos(ang), sin(ang)) * reach * e + float2(0.0, hh * 0.9 * q * q);
                        float r = (1.0 + 3.2 * h(fi * 9.7 + 5.0)) * dens * (1.0 - 0.7 * q);
                        float dd = length(d0 - pos);
                        float al = (1.0 - smoothstep(r * 0.55, r, dd)) * (1.0 - q * q);
                        float3 sc = c1;
                        if (mod(fi, 3.0) < 0.5) sc = mix(c1, float3(1.0), 0.6);
                        col += sc * al;
                        acc += al;
                    }
                }
                float cq = ms / 460.0;
                if (cq < 1.0) {
                    float ce = 1.0 - (1.0 - cq) * (1.0 - cq);
                    for (int i = 0; i < 7; i++) {
                        float fi = float(i);
                        float ang = (fi + h(fi * 2.9 + 6.0)) * (6.2831853 / 7.0);
                        float reach = hh * (1.4 + 1.6 * h(fi * 4.1 + 7.0));
                        float2 pos = float2(cos(ang), sin(ang)) * reach * ce + float2(0.0, hh * 0.8 * cq * cq);
                        float rot = 12.566371 * (h(fi * 6.7 + 9.0) - 0.5) * cq;
                        float cs = cos(rot);
                        float sn = sin(rot);
                        float2 lp = d0 - pos;
                        lp = float2(cs * lp.x + sn * lp.y, -sn * lp.x + cs * lp.y);
                        float2 b = float2(hw * (0.22 + 0.2 * h(fi * 8.3 + 8.0)), hh * (0.3 + 0.25 * h(fi * 3.3 + 10.0)));
                        float dist = sdBox(lp, b, rad * 0.4);
                        float al = (1.0 - smoothstep(-0.5 * dens, 0.5 * dens, dist)) * (1.0 - cq);
                        col += c2 * al;
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
