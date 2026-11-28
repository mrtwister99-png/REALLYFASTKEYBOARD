package cz.reallyfast

import android.annotation.TargetApi
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RuntimeShader

/** Dalsi efekty klaves jako shadery (AGSL, Android 13+); stejna pravidla jako v Shaders.kt: vse se vytvori jednou, v onDraw se nealokuje. */

@TargetApi(33)
private fun RuntimeShader.setRgb(name: String, c: Int) {
    setFloatUniform(name, Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f)
}

/**
 * Obvod: zarici obrys, ktery z nahodneho bodu (`start`, 0..1 kolem klavesy) nabehne do `prog` (0..1 dokola) a pak zhasne (`a`).
 * Barva se po obrysu plynule meni z `c1` do `c2`, hlava (konec) obrysu je bila; kolem je mekka zare.
 */
@TargetApi(33)
class OrbitFx {
    private val shader = RuntimeShader(SRC)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        paint.shader = shader
    }

    /** Plocha je klavesa zvetsena o `pad`; `sw` je tloustka obrysu v px. */
    fun draw(canvas: Canvas, left: Float, top: Float, w: Float, h: Float, radius: Float, pad: Float, sw: Float, a: Float, start: Float, prog: Float, c1: Int, c2: Int) {
        shader.setFloatUniform("size", w, h)
        shader.setFloatUniform("pad", pad)
        shader.setFloatUniform("rad", radius)
        shader.setFloatUniform("sw", sw)
        shader.setFloatUniform("a", a)
        shader.setFloatUniform("start", start)
        shader.setFloatUniform("prog", prog)
        shader.setRgb("c1", c1)
        shader.setRgb("c2", c2)
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
            uniform float sw;
            uniform float a;
            uniform float start;
            uniform float prog;
            uniform float3 c1;
            uniform float3 c2;

            half4 main(float2 fc) {
                float2 hs = size * 0.5;
                float2 q0 = fc - hs;
                float2 q = abs(q0) - (hs - pad) + rad;
                float dd = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - rad;
                float dl = abs(dd + sw * 0.5) - sw * 0.5;
                float line = (1.0 - smoothstep(-0.5, 0.8, dl)) * (1.0 - smoothstep(-0.5, 0.5, dd + 0.0));
                float inside = 1.0 - smoothstep(-0.5, 0.5, dd);
                float glow = exp(-max(dl, 0.0) / (sw * 2.2)) * 0.5 * inside;
                float u = atan(q0.y, q0.x) / 6.2831853 + 0.5;
                float s = fract(u - start);
                float inArc = 1.0;
                if (prog < 0.999) inArc = smoothstep(prog + 0.004, prog - 0.004, s);
                float t = clamp(s / max(prog, 0.001), 0.0, 1.0);
                float3 col = mix(c1, c2, t);
                col = mix(col, float3(1.0), smoothstep(0.82, 1.0, t) * 0.6);
                float al = clamp((line + glow) * inArc * a, 0.0, 1.0);
                return half4(half3(col * al), half(al));
            }
        """
    }
}

/**
 * Duch: tri dozvuky (obrysy) za klavesou, kazdy o `step` ms pozdeji, rostou a miznou; licha ma barvu `c2`, suda `c1`.
 * Vse jednim pruchodem shaderu pres plochu kolem klavesy.
 */
@TargetApi(33)
class GhostFx {
    private val shader = RuntimeShader(SRC)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        paint.shader = shader
    }

    /** `gt` je cas od klepnuti v ms, `ms` doba jednoho dozvuku, `step` zpozdeni dalsiho; plocha = klavesa zvetsena o `pad`. */
    fun draw(canvas: Canvas, left: Float, top: Float, w: Float, h: Float, radius: Float, pad: Float, gt: Float, ms: Float, step: Float, c1: Int, c2: Int, dens: Float) {
        shader.setFloatUniform("size", w, h)
        shader.setFloatUniform("pad", pad)
        shader.setFloatUniform("rad", radius)
        shader.setFloatUniform("gt", gt)
        shader.setFloatUniform("ms", ms)
        shader.setFloatUniform("stp", step)
        shader.setFloatUniform("dens", dens)
        shader.setRgb("c1", c1)
        shader.setRgb("c2", c2)
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
            uniform float gt;
            uniform float ms;
            uniform float stp;
            uniform float dens;
            uniform float3 c1;
            uniform float3 c2;

            half4 main(float2 fc) {
                float2 hs = size * 0.5;
                float2 q0 = fc - hs;
                float3 col = float3(0.0);
                float acc = 0.0;
                for (int i = 0; i < 3; i++) {
                    float e = float(i);
                    float q = (gt - e * stp) / ms;
                    if (q > 0.0 && q < 1.0) {
                        float grow = (2.5 + 3.5 * (e + 1.0)) * dens * q;
                        float2 kh = hs - pad + grow;
                        float r = rad + grow;
                        float2 b = abs(q0) - kh + r;
                        float d = length(max(b, 0.0)) + min(max(b.x, b.y), 0.0) - r;
                        float hw = dens;
                        float line = 1.0 - smoothstep(hw - 0.7, hw + 0.7, abs(d));
                        float glow = exp(-abs(d) / (3.0 * dens)) * 0.3;
                        float fade = (1.0 - q) * (1.0 - q) * 0.75;
                        float al = clamp((line + glow) * fade, 0.0, 1.0);
                        float3 ec = c1;
                        if (mod(e, 2.0) > 0.5) ec = c2;
                        col += ec * al;
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

/**
 * Barvy: klavesa se "vybarvi" vlnou, ktera se rozleje od nahodneho mista na okraji (`p` 0..1), barva plynule prechazi z `c1`
 * do `c2` a na fronte vlny je jemny lesk; `a` je celkova pruhlednost (vybarveni po case zase bledne).
 * Kresli se pres klavesu, orezano na jeji zaobleny tvar.
 */
@TargetApi(33)
class PaintFx {
    private val shader = RuntimeShader(SRC)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        paint.shader = shader
    }

    fun draw(canvas: Canvas, left: Float, top: Float, w: Float, h: Float, radius: Float, p: Float, a: Float, seed: Float, c1: Int, c2: Int) {
        shader.setFloatUniform("size", w, h)
        shader.setFloatUniform("rad", radius)
        shader.setFloatUniform("p", p)
        shader.setFloatUniform("a", a)
        shader.setFloatUniform("seed", seed)
        shader.setRgb("c1", c1)
        shader.setRgb("c2", c2)
        canvas.save()
        canvas.translate(left, top)
        canvas.drawRect(0f, 0f, w, h, paint)
        canvas.restore()
    }

    private companion object {
        const val SRC = """
            uniform float2 size;
            uniform float rad;
            uniform float p;
            uniform float a;
            uniform float seed;
            uniform float3 c1;
            uniform float3 c2;

            half4 main(float2 fc) {
                float2 hs = size * 0.5;
                float2 q = abs(fc - hs) - hs + rad;
                float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - rad;
                float inside = 1.0 - smoothstep(-1.0, 0.5, d);
                float ang = seed * 6.2831853;
                float2 o = hs + float2(cos(ang), sin(ang)) * hs * 0.9;
                float reach = length(size) * 1.05;
                float2 v = fc - o;
                float dist = length(v);
                float wob = sin(atan(v.y, v.x) * 5.0 + seed * 20.0) * 0.04 * reach;
                float e = p * p * (3.0 - 2.0 * p);
                float front = e * reach * 1.15;
                float cover = 1.0 - smoothstep(front - 0.1 * reach + wob, front + wob, dist);
                float3 col = mix(c1, c2, clamp(dist / reach, 0.0, 1.0) * 0.45);
                float k = (dist - front + wob) / (0.07 * reach);
                float sheen = exp(-k * k) * (1.0 - p);
                col = mix(col, float3(1.0), sheen * 0.5);
                float al = inside * cover * a;
                return half4(half3(col * al), half(al));
            }
        """
    }
}
