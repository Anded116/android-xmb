package dev.anded.xmb

import android.graphics.RuntimeShader
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Фон XMB как на плеере HiBy R3II (github.com/Anded116/r3ii-xmb: apps/xmb/src/xmb_bg.c, waves/wave_ps3.c):
 * вертикальный градиент цвета месяца с мягким светлым пятном слева сверху и «пучок» PS3 —
 * 7 тонких линий в двух слоях: 4 текут вправо, 3 — влево и вьются чаще, к краям экрана гаснут.
 */
object WaveShader {
    const val BG = """
uniform float2 res;
uniform float3 top;
uniform float3 bottom;

half4 main(float2 p) {
    float2 uv = p / res;
    float3 c = mix(top, bottom, uv.y);
    float2 g = (p / res.x - float2(0.28, 0.375)) / float2(0.81, 0.94);
    c += 0.10 * exp(-dot(g, g) * 2.2);
    // Дизеринг против полос градиента на OLED (interleaved gradient noise).
    float n = fract(52.9829189 * fract(dot(p, float2(0.06711056, 0.00583715))));
    c += (n - 0.5) / 255.0;
    return half4(half3(clamp(c, 0.0, 1.0)), 1.0);
}
"""

    // Линия: y = cy + off + A1·sin(k1·θ + P1) + A2·sin(k2·θ + P2), θ = 2πx/W. Гармоники k·θ получаются
    // умножением комплексных чисел из одного cos/sin θ, фазы P — готовые cos/sin в a_i, (off, A1, A2, яркость) — в b_i.
    // Рисуется с BlendMode.SCREEN: альфа a осветляет фон как lit() плеера — p + (1 − p)·a.
    const val WAVE = """
uniform float width;
uniform float cy;
uniform float thin;
uniform float soft;
uniform float halo;
uniform float4 a0; uniform float4 a1; uniform float4 a2; uniform float4 a3; uniform float4 a4; uniform float4 a5; uniform float4 a6;
uniform float4 b0; uniform float4 b1; uniform float4 b2; uniform float4 b3; uniform float4 b4; uniform float4 b5; uniform float4 b6;

float line(float2 h1, float2 h2, float4 a, float4 b, float y, float sigma, float hw) {
    float d = b.x + b.y * (h1.y * a.x + h1.x * a.y) + b.z * (h2.y * a.z + h2.x * a.w);
    float dist = y - cy - d;
    float v = exp(-dist * dist / (2.0 * sigma * sigma));
    if (hw > 0.0) { v += hw * exp(-dist * dist / (2.0 * halo * halo)); }
    return b.w * v;
}

float2 mul(float2 u, float2 v) { return float2(u.x * v.x - u.y * v.y, u.x * v.y + u.y * v.x); }

half4 main(float2 p) {
    float th = 6.2831853 * p.x / width;
    float2 z1 = float2(cos(th), sin(th));
    float2 z2 = mul(z1, z1);
    float2 z3 = mul(z2, z1);
    float2 z5 = mul(z3, z2);
    float fade = 0.62 + 0.38 * sqrt(max(0.0, (1.0 - z1.x) * 0.5));
    float a = line(z1, z3, a0, b0, p.y, thin, 0.10) + line(z1, z3, a1, b1, p.y, soft, 0.0)
            + line(z1, z3, a2, b2, p.y, thin, 0.0) + line(z1, z3, a3, b3, p.y, thin, 0.0)
            + line(z2, z5, a4, b4, p.y, thin, 0.08) + line(z2, z5, a5, b5, p.y, thin, 0.0)
            + line(z2, z5, a6, b6, p.y, thin, 0.0);
    a = min(a * fade, 1.0);
    return half4(a, a, a, a);
}
"""

    /** Линии плеера: смещение и амплитуда (px экрана 320), волн на ширину, скорость (px/с), фаза, качание фазы (Гц), яркость. */
    private class Strand(val off: Float, val amp: Float, val cyc: Int, val v: Float, val ph: Float, val wd: Float, val alpha: Int)

    private val LINES = arrayOf(
        Strand(-6f, 22f, 1, 17f, 0.00f, 0.060f, 150),
        Strand(-3f, 21f, 1, 17f, 0.18f, -0.050f, 130),
        Strand(0f, 20f, 1, 17f, 0.36f, 0.070f, 120),
        Strand(3f, 19f, 1, 17f, 0.54f, -0.045f, 100),
        Strand(-2f, 13f, 2, -12f, 1.30f, 0.080f, 120),
        Strand(2f, 12f, 2, -12f, 1.52f, -0.070f, 100),
        Strand(6f, 11f, 2, -12f, 1.74f, 0.090f, 80),
    )
    private val A_NAMES = Array(7) { "a$it" }
    private val B_NAMES = Array(7) { "b$it" }

    /** Ширина экрана плеера, к которой привязаны числа линий. */
    private const val PLAYER_W = 320f

    /** На высоком экране телефона пучок плеера выглядит плоским — амплитуда крупнее, толщина как есть. */
    private const val AMP_SCALE = 1.5f

    /** Яркость линий в покое: в плеере 130/256, здесь чуть ярче. */
    private const val BRIGHTNESS = 0.66f

    fun setGeometry(shader: RuntimeShader, width: Float, cy: Float) {
        val s = width / PLAYER_W
        shader.setFloatUniform("width", width)
        shader.setFloatUniform("cy", cy)
        shader.setFloatUniform("thin", 0.55f * s)
        shader.setFloatUniform("soft", 0.8f * s)
        shader.setFloatUniform("halo", 10f * s)
    }

    /** Половина высоты полосы, где волна видна: крайняя линия плюс ореол. */
    fun bandHalf(width: Float): Float {
        val s = width / PLAYER_W
        val ext = LINES.maxOf { abs(it.off) + it.amp * 1.3f * 1.08f }
        return ext * s * AMP_SCALE + 30f * s + 4f
    }

    /** Фазы и амплитуды линий на момент t секунд — как thr_calc() плеера. */
    fun setTime(shader: RuntimeShader, width: Float, t: Float) {
        val s = width / PLAYER_W * AMP_SCALE
        for (i in LINES.indices) {
            val h = LINES[i]
            val sh = -h.v * t / PLAYER_W
            val pd = h.ph / (2 * PI.toFloat()) + 0.04f * sin(2 * PI.toFloat() * h.wd * t)
            val p1 = turns(h.cyc * sh + pd)
            val p2 = turns((2 * h.cyc + 1) * sh - 1.7f * pd + 0.04f * i)
            val a1 = h.amp * (1 + 0.08f * sin(0.45f * t + i * 1.7f))
            shader.setFloatUniform(A_NAMES[i], cos(p1), sin(p1), cos(p2), sin(p2))
            shader.setFloatUniform(B_NAMES[i], h.off * s, a1 * s, a1 * 77f / 256f * s, h.alpha / 255f * BRIGHTNESS)
        }
    }

    private fun turns(x: Float): Float = (x - floor(x)) * 2 * PI.toFloat()

    /** Цвета тем по месяцам из плеера: верх и низ градиента. */
    private val THEMES = intArrayOf(
        0x3a4250, 0x8d97a8, 0x5a4512, 0xb59a3e, 0x2b4a14, 0x7aa83a, 0x5a2742, 0xc77aa0,
        0x0f4a2a, 0x3fae6b, 0x3a1f5c, 0x9a6fd0, 0x0b4a52, 0x35b5c0, 0x0d2a5e, 0x4f86d8,
        0x4a1f5a, 0xa35cc0, 0x5a3a08, 0xc98f2a, 0x4a2a16, 0x9a6844, 0x5a0f14, 0xc4373e,
    )

    /** Ночью фон тише. */
    private val HOUR_BRIGHTNESS = floatArrayOf(
        0.72f, 0.72f, 0.72f, 0.72f, 0.72f, 0.76f, 0.82f, 0.9f, 0.96f, 1f, 1f, 1f,
        1f, 1f, 1f, 1f, 1f, 0.98f, 0.94f, 0.9f, 0.85f, 0.8f, 0.76f, 0.74f,
    )

    fun setTheme(shader: RuntimeShader, month: Int, hour: Int) {
        val k = HOUR_BRIGHTNESS[hour]
        setColor(shader, "top", THEMES[month * 2], k)
        setColor(shader, "bottom", THEMES[month * 2 + 1], k)
    }

    private fun setColor(shader: RuntimeShader, name: String, rgb: Int, k: Float) {
        shader.setFloatUniform(
            name,
            ((rgb shr 16) and 255) / 255f * k,
            ((rgb shr 8) and 255) / 255f * k,
            (rgb and 255) / 255f * k,
        )
    }
}
