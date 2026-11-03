package dev.anded.xmb

import android.graphics.Color
import android.graphics.RuntimeShader
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Фон XMB как на плеере HiBy R3II (github.com/Anded116/r3ii-xmb: apps/xmb/src/xmb_bg.c, waves/wave_ps3.c):
 * вертикальный градиент цвета темы с мягким светлым пятном слева сверху и «пучок» PS3 —
 * 7 тонких линий в двух слоях: 4 текут вправо, 3 — влево и вьются чаще, к краям экрана гаснут.
 * Сверх плеера: полупрозрачная лента между линиями и медленные пятна света по фону.
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

    // Пятна света: (x, y, радиус, сила) в долях ширины экрана; рисуется с BlendMode.SCREEN в слое 1/4 разрешения.
    const val LIGHT = """
uniform float width;
uniform float3 tint;
uniform float4 l0;
uniform float4 l1;
uniform float4 l2;

float blob(float2 u, float4 l) {
    float2 d = u - l.xy;
    return l.w * exp(-dot(d, d) / (l.z * l.z));
}

half4 main(float2 p) {
    float2 u = p / width;
    float a = min(blob(u, l0) + blob(u, l1) + blob(u, l2), 1.0);
    return half4(half3(tint * a), a);
}
"""

    // Аврора: четыре вытянутых по горизонтали цветных сполоха, (x, y, радиус, сила) в долях ширины.
    // Цвет уже умножен на силу; альфа — самый яркий канал, рисуется с BlendMode.SCREEN.
    const val AURORA = """
uniform float width;
uniform float3 c0; uniform float3 c1; uniform float3 c2; uniform float3 c3;
uniform float4 l0; uniform float4 l1; uniform float4 l2; uniform float4 l3;

float blob(float2 u, float4 l) {
    float2 d = (u - l.xy) / float2(l.z, l.z * 0.5);
    return l.w * exp(-dot(d, d));
}

half4 main(float2 p) {
    float2 u = p / width;
    float3 c = min(c0 * blob(u, l0) + c1 * blob(u, l1) + c2 * blob(u, l2) + c3 * blob(u, l3), float3(1.0));
    return half4(half3(c), max(max(c.r, c.g), c.b));
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
uniform float sheet;
uniform float4 a0; uniform float4 a1; uniform float4 a2; uniform float4 a3; uniform float4 a4; uniform float4 a5; uniform float4 a6;
uniform float4 b0; uniform float4 b1; uniform float4 b2; uniform float4 b3; uniform float4 b4; uniform float4 b5; uniform float4 b6;

float offs(float2 h1, float2 h2, float4 a, float4 b) {
    return b.x + b.y * (h1.y * a.x + h1.x * a.y) + b.z * (h2.y * a.z + h2.x * a.w);
}

float line(float d, float4 b, float y, float sigma, float hw) {
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
    float d0 = offs(z1, z3, a0, b0);
    float d3 = offs(z1, z3, a3, b3);
    float a = line(d0, b0, p.y, thin, 0.10) + line(offs(z1, z3, a1, b1), b1, p.y, soft, 0.0)
            + line(offs(z1, z3, a2, b2), b2, p.y, thin, 0.0) + line(d3, b3, p.y, thin, 0.0)
            + line(offs(z2, z5, a4, b4), b4, p.y, thin, 0.08) + line(offs(z2, z5, a5, b5), b5, p.y, thin, 0.0)
            + line(offs(z2, z5, a6, b6), b6, p.y, thin, 0.0);
    // Лента между крайними линиями слоя A: светлее у краёв, как полупрозрачное полотно PS3.
    if (sheet > 0.0) {
        float lo = cy + min(d0, d3);
        float hi = cy + max(d0, d3);
        if (p.y > lo && p.y < hi) {
            float t = (p.y - lo) / max(hi - lo, 1.0);
            a += sheet * (0.45 + 0.55 * abs(2.0 * t - 1.0));
        }
    }
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

    // Последние фазы и амплитуды линии 2 (средняя слоя A) — по ним искры летят вдоль волны.
    private val mid = FloatArray(7)

    /**
     * unit — пикселей экрана на пиксель плеера по короткой стороне: толщина и размах пучка не зависят от
     * ориентации, а число волн на ширину — как у плеера.
     */
    fun unit(width: Float, height: Float): Float = minOf(width, height) / PLAYER_W

    fun setGeometry(shader: RuntimeShader, width: Float, unit: Float, cy: Float, sheet: Float) {
        val s = unit
        shader.setFloatUniform("width", width)
        shader.setFloatUniform("cy", cy)
        shader.setFloatUniform("thin", 0.55f * s)
        shader.setFloatUniform("soft", 0.8f * s)
        shader.setFloatUniform("halo", 10f * s)
        shader.setFloatUniform("sheet", sheet)
    }

    /** Половина высоты полосы, где волна видна: крайняя линия плюс ореол. */
    fun bandHalf(unit: Float): Float {
        val s = unit
        val ext = LINES.maxOf { abs(it.off) + it.amp * 1.3f * 1.08f }
        return ext * s * AMP_SCALE + 30f * s + 4f
    }

    /** Фазы и амплитуды линий на момент t секунд — как thr_calc() плеера. */
    fun setTime(shader: RuntimeShader, unit: Float, t: Float) {
        val s = unit * AMP_SCALE
        for (i in LINES.indices) {
            val h = LINES[i]
            val sh = -h.v * t / PLAYER_W
            val pd = h.ph / (2 * PI.toFloat()) + 0.04f * sin(2 * PI.toFloat() * h.wd * t)
            val p1 = turns(h.cyc * sh + pd)
            val p2 = turns((2 * h.cyc + 1) * sh - 1.7f * pd + 0.04f * i)
            val a1 = h.amp * (1 + 0.08f * sin(0.45f * t + i * 1.7f))
            shader.setFloatUniform(A_NAMES[i], cos(p1), sin(p1), cos(p2), sin(p2))
            shader.setFloatUniform(B_NAMES[i], h.off * s, a1 * s, a1 * 77f / 256f * s, h.alpha / 255f * BRIGHTNESS)
            if (i == 2) {
                mid[0] = p1
                mid[1] = p2
                mid[2] = h.off * s
                mid[3] = a1 * s
                mid[4] = a1 * 77f / 256f * s
            }
        }
    }

    /** Смещение средней линии от центра волны в точке x — для искр. */
    fun midOffset(x: Float, width: Float): Float {
        val th = 2 * PI.toFloat() * x / width
        return mid[2] + mid[3] * sin(th + mid[0]) + mid[4] * sin(3 * th + mid[1])
    }

    private fun turns(x: Float): Float = (x - floor(x)) * 2 * PI.toFloat()

    /** Цвета тем из плеера (по месяцам): верх и низ градиента. */
    private val THEMES = intArrayOf(
        0x3a4250, 0x8d97a8, 0x5a4512, 0xb59a3e, 0x2b4a14, 0x7aa83a, 0x5a2742, 0xc77aa0,
        0x0f4a2a, 0x3fae6b, 0x3a1f5c, 0x9a6fd0, 0x0b4a52, 0x35b5c0, 0x0d2a5e, 0x4f86d8,
        0x4a1f5a, 0xa35cc0, 0x5a3a08, 0xc98f2a, 0x4a2a16, 0x9a6844, 0x5a0f14, 0xc4373e,
    )

    fun themeTop(theme: Int): Int = THEMES[theme * 2]

    fun themeBottom(theme: Int): Int = THEMES[theme * 2 + 1]

    /** Ночью фон чуть тише — без мути: на тёмном фоне сильное затемнение уводит цвет в грязь. */
    private val HOUR_BRIGHTNESS = floatArrayOf(
        0.86f, 0.86f, 0.86f, 0.86f, 0.86f, 0.88f, 0.92f, 0.96f, 1f, 1f, 1f, 1f,
        1f, 1f, 1f, 1f, 1f, 1f, 0.97f, 0.95f, 0.92f, 0.9f, 0.88f, 0.87f,
    )

    fun setTheme(bg: RuntimeShader, light: RuntimeShader, theme: Int, hour: Int) {
        val k = HOUR_BRIGHTNESS[hour]
        setColor(bg, "top", themeTop(theme), k)
        setColor(bg, "bottom", themeBottom(theme), k)
        // Свет — цвет низа градиента, осветлённый наполовину к белому.
        val b = themeBottom(theme)
        light.setFloatUniform(
            "tint",
            (((b shr 16) and 255) / 255f + 1f) / 2f,
            (((b shr 8) and 255) / 255f + 1f) / 2f,
            ((b and 255) / 255f + 1f) / 2f,
        )
    }

    /**
     * Аврора: фон — тот же градиент темы, приглушённый к ночному небу; сполохи — насыщенный цвет темы
     * и два соседних оттенка, сдвинутых по кругу к фиолетовому (янтарь → красный → пурпур, зелёный →
     * бирюза → лазурь): в сторону жёлтого тёплые темы уходят в хаки. Плюс слабый дополнительный.
     */
    fun setAuroraTheme(bg: RuntimeShader, aurora: RuntimeShader, theme: Int, hour: Int) {
        val k = HOUR_BRIGHTNESS[hour]
        setColor(bg, "top", themeTop(theme), k * 0.2f)
        setColor(bg, "bottom", themeBottom(theme), k * 0.3f)
        val hsv = FloatArray(3)
        Color.colorToHSV(themeBottom(theme), hsv)
        val s = if (hsv[1] < 0.15f) 0.12f else maxOf(hsv[1], 0.7f)
        setHsv(aurora, "c0", hsv[0], s, 1f)
        val toViolet = ((VIOLET - hsv[0] + 540f) % 360f) - 180f
        val step = if (toViolet >= 0f) 35f else -35f
        setHsv(aurora, "c1", hsv[0] + step, s, 0.95f)
        setHsv(aurora, "c2", hsv[0] + step * 2, s, 0.9f)
        setHsv(aurora, "c3", hsv[0] + 180f, s * 0.7f, 0.55f)
    }

    /** Сполохи плывут быстрее пятен света: периоды 20–40 с. */
    fun setAurora(aurora: RuntimeShader, width: Float, aspect: Float, t: Float) {
        aurora.setFloatUniform("width", width)
        aurora.setFloatUniform("l0", 0.35f + 0.25f * sin(t * 0.21f), aspect * (0.22f + 0.06f * sin(t * 0.17f + 1f)), 0.75f, 0.55f)
        aurora.setFloatUniform("l1", 0.70f + 0.25f * sin(t * 0.16f + 2f), aspect * (0.42f + 0.08f * sin(t * 0.23f)), 0.65f, 0.45f)
        aurora.setFloatUniform("l2", 0.40f + 0.35f * sin(t * 0.13f + 4f), aspect * (0.70f + 0.06f * sin(t * 0.19f + 3f)), 0.80f, 0.40f)
        aurora.setFloatUniform("l3", 0.60f + 0.30f * sin(t * 0.11f + 5f), aspect * (0.92f + 0.04f * sin(t * 0.29f + 2f)), 0.55f, 0.30f)
    }

    private const val VIOLET = 270f

    private fun setHsv(shader: RuntimeShader, name: String, h: Float, s: Float, v: Float) {
        val c = Color.HSVToColor(floatArrayOf((h % 360f + 360f) % 360f, s.coerceIn(0f, 1f), v))
        setColor(shader, name, c and 0xFFFFFF, 1f)
    }

    /** Пятна света плывут по фигурам Лиссажу с периодами в минуту-полторы. */
    fun setLight(light: RuntimeShader, width: Float, aspect: Float, t: Float, strength: Float) {
        light.setFloatUniform("width", width)
        light.setFloatUniform("l0", 0.30f + 0.18f * sin(t * 0.071f), aspect * (0.30f + 0.10f * sin(t * 0.053f + 1f)), 0.55f, 0.16f * strength)
        light.setFloatUniform("l1", 0.75f + 0.20f * sin(t * 0.047f + 2f), aspect * (0.62f + 0.12f * sin(t * 0.061f)), 0.62f, 0.12f * strength)
        light.setFloatUniform("l2", 0.50f + 0.35f * sin(t * 0.039f + 4f), aspect * (0.90f + 0.06f * sin(t * 0.083f + 3f)), 0.70f, 0.10f * strength)
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
