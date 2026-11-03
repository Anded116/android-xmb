package dev.anded.xmb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import java.util.Calendar
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Фон XMB: градиент темы, пятна света, волна PS3 с лентой и искры вдоль неё.
 * Рисует и лаунчер (пока не стоят живые обои), и сами живые обои — один код, один вид.
 */
class BgRenderer(private val context: Context) {

    private val bg = RuntimeShader(WaveShader.BG)
    private val bgPaint = Paint().apply { shader = bg }
    private val bgNode = RenderNode("bg").apply { setUseCompositingLayer(true, null) }

    // Свет плавный, ему хватает четверти разрешения — слой растягивается с фильтрацией.
    private val light = RuntimeShader(WaveShader.LIGHT)
    private val lightPaint = Paint().apply { shader = light }
    private val lightNode = RenderNode("light").apply {
        setUseCompositingLayer(true, Paint(Paint.FILTER_BITMAP_FLAG).apply { blendMode = BlendMode.SCREEN })
    }

    private val aurora = RuntimeShader(WaveShader.AURORA)
    private val auroraPaint = Paint().apply { shader = aurora }

    // Nothing: сетка тусклых точек — в слое фона, точки волны — каждый кадр, по трём уровням яркости.
    // Волна идёт за подписями — даже самые яркие точки не в полную силу.
    private val dotPaints = intArrayOf(-0x3a000001, 0x80FFFFFF.toInt(), 0x44FFFFFF).map { c ->
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = c
            strokeCap = Paint.Cap.ROUND
        }
    }
    private val redDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = NOTHING_RED
        strokeCap = Paint.Cap.ROUND
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF262626.toInt()
        strokeCap = Paint.Cap.ROUND
    }
    private var dotBuf = Array(3) { FloatArray(0) }
    private val dotN = IntArray(3)
    private var pitch = 0f
    private var look = Look.XMB

    private val wave = RuntimeShader(WaveShader.WAVE)
    private val wavePaint = Paint().apply {
        shader = wave
        blendMode = BlendMode.SCREEN
    }

    private val sparkPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { blendMode = BlendMode.SCREEN }
    private val spark: Bitmap
    private val sparkSeeds = FloatArray(SPARKS * 4)
    private val dst = RectF()

    private var w = 0
    private var h = 0
    private var waveY = 0f
    private var waveHalf = 0f
    private var unit = 1f
    private var themeKey = -1L
    private var style = -1
    // Своё время анимации: в покое оно стоит, и после пробуждения волна идёт дальше с того же места.
    private var animT = 0f
    private var lastNs = 0L

    init {
        val dp = context.resources.displayMetrics.density
        val s = (12 * dp).roundToInt()
        spark = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawCircle(s / 2f, s / 2f, s / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(s / 2f, s / 2f, s / 2f, intArrayOf(-0x1, 0x66FFFFFF, 0), floatArrayOf(0f, 0.25f, 1f), Shader.TileMode.CLAMP)
            })
        }
        val rnd = java.util.Random(7)
        for (i in 0 until SPARKS) {
            sparkSeeds[i * 4] = rnd.nextFloat()                 // место по x
            sparkSeeds[i * 4 + 1] = 0.004f + rnd.nextFloat() * 0.01f   // скорость, ширин/с
            sparkSeeds[i * 4 + 2] = rnd.nextFloat() * 2 - 1      // разброс от линии
            sparkSeeds[i * 4 + 3] = rnd.nextFloat() * 6.28f      // фаза мерцания
        }
    }

    fun setSize(width: Int, height: Int) {
        if (width == w && height == h) return
        w = width
        h = height
        bgNode.setPosition(0, 0, w, h)
        bg.setFloatUniform("res", w.toFloat(), h.toFloat())
        lightNode.setPosition(0, 0, max(1, w / 4), max(1, h / 4))
        unit = WaveShader.unit(w.toFloat(), h.toFloat())
        waveY = h * if (w > h) 0.6f else 0.56f
        waveHalf = WaveShader.bandHalf(unit)
        themeKey = -1
        style = -1
        pitch = 0f
    }

    /** Слой градиента могли освободить, пока окно было скрыто, — записать заново на следующем кадре. */
    fun invalidateLayers() {
        themeKey = -1
    }

    /** advance = false — тот же кадр, что в прошлый раз (фон замер в покое). */
    fun draw(canvas: Canvas, advance: Boolean = true) {
        if (w == 0) return
        val now = System.nanoTime()
        val lk = XmbSettings.look
        if (lk != look) {
            look = lk
            themeKey = -1
        }
        if (advance && lastNs != 0L) animT += ((now - lastNs) / 1e9f).coerceIn(0f, 0.1f)
        lastNs = now
        val st = XmbSettings.style
        when (lk) {
            Look.NOTHING -> return drawNothing(canvas, animT, BgBus.flow(now), st)
            Look.AURORA -> return drawAurora(canvas, animT, st)
            Look.XMB -> Unit
        }
        updateTheme()
        canvas.drawRenderNode(bgNode)

        if (st != style) {
            style = st
            WaveShader.setGeometry(wave, w.toFloat(), unit, waveY, if (st == XmbSettings.STYLE_PS3) 0.07f else 0f)
        }
        if (st == XmbSettings.STYLE_PLAIN) return
        val t = animT
        val flow = BgBus.flow(now)

        WaveShader.setLight(light, w / 4f, h / w.toFloat(), t % 10_000f, if (st == XmbSettings.STYLE_CALM) 1.3f else 1f)
        lightNode.beginRecording().drawPaint(lightPaint)
        lightNode.endRecording()
        val save = canvas.save()
        canvas.scale(4f, 4f)
        canvas.drawRenderNode(lightNode)
        canvas.restoreToCount(save)
        if (st != XmbSettings.STYLE_PS3) return

        val wt = (t + flow) % 3600f
        WaveShader.setTime(wave, unit, wt)
        canvas.drawRect(0f, waveY - waveHalf, w.toFloat(), waveY + waveHalf, wavePaint)
        drawSparks(canvas, t, flow)
    }

    /**
     * Nothing: чёрный фон, сетка точек и волна, бегущая по ним: точки рядом с кривой зажигаются
     * тем ярче, чем ближе; по волне идёт одна красная точка. «Только градиент» — одна сетка.
     */
    private fun drawNothing(canvas: Canvas, t: Float, flow: Float, st: Int) {
        val cols = (w / pitchPx()).toInt() + 1
        val rows = (h / pitch).toInt() + 1
        val ox = (w - (cols - 1) * pitch) / 2
        val oy = (h - (rows - 1) * pitch) / 2
        if (themeKey != NOTHING_KEY) {
            themeKey = NOTHING_KEY
            val grid = FloatArray(cols * rows * 2)
            for (j in 0 until rows) for (i in 0 until cols) {
                grid[(j * cols + i) * 2] = ox + i * pitch
                grid[(j * cols + i) * 2 + 1] = oy + j * pitch
            }
            gridPaint.strokeWidth = pitch * 0.13f
            val rec = bgNode.beginRecording(w, h)
            rec.drawColor(0xFF000000.toInt())
            rec.drawPoints(grid, gridPaint)
            bgNode.endRecording()
            dotBuf = Array(3) { FloatArray(cols * 2 * 8) }
            dotPaints[0].strokeWidth = pitch * 0.24f
            dotPaints[1].strokeWidth = pitch * 0.2f
            dotPaints[2].strokeWidth = pitch * 0.17f
            redDot.strokeWidth = pitch * 0.3f
        }
        canvas.drawRenderNode(bgNode)
        if (st == XmbSettings.STYLE_PLAIN) return
        val amp = if (st == XmbSettings.STYLE_CALM) 0.5f else 1f
        val tt = t * amp + flow * 0.5f
        dotN.fill(0)
        for (i in 0 until cols) {
            val x = ox + i * pitch
            val yw = dotWave(x, amp, tt)
            val edge = 0.4f + 0.6f * sin(x / w * Math.PI.toFloat())
            val j0 = ((yw - oy) / pitch).toInt() - 3
            for (j in j0..j0 + 7) {
                if (j !in 0 until rows) continue
                val y = oy + j * pitch
                val d = (y - yw) / (pitch * 1.15f)
                val b = exp(-d * d) * edge
                val k = when {
                    b > 0.72f -> 0
                    b > 0.42f -> 1
                    b > 0.18f -> 2
                    else -> continue
                }
                val buf = dotBuf[k]
                if (dotN[k] + 2 > buf.size) continue
                buf[dotN[k]++] = x
                buf[dotN[k]++] = y
            }
        }
        for (k in 0 until 3) if (dotN[k] > 0) canvas.drawPoints(dotBuf[k], 0, dotN[k], dotPaints[k])
        // Красная точка — ближайшая к волне в своём столбце, проходит экран слева направо за 40 с.
        val rx = ox + (frac(t / 40f) * cols).toInt().coerceIn(0, cols - 1) * pitch
        val rj = ((dotWave(rx, amp, tt) - oy) / pitch).roundToInt().coerceIn(0, rows - 1)
        canvas.drawPoint(rx, oy + rj * pitch, redDot)
    }

    private fun pitchPx(): Float {
        if (pitch == 0f) pitch = 13 * context.resources.displayMetrics.density
        return pitch
    }

    /** Кривая волны Nothing в точке x: две синусоиды, бегущие навстречу. */
    private fun dotWave(x: Float, amp: Float, t: Float): Float {
        val u = x / w
        return waveY + amp * (pitch * 2.6f * sin(6.2832f * u + t * 0.7f) + pitch * 1.2f * sin(6.2832f * u * 2.3f - t * 1.1f + 1.3f))
    }

    /** Аврора: приглушённый градиент темы и цветные сполохи в слое 1/4 разрешения. */
    private fun drawAurora(canvas: Canvas, t: Float, st: Int) {
        val chosen = XmbSettings.theme
        val key = System.currentTimeMillis() / 60_000 * 100 + chosen + 1 + AURORA_KEY
        if (key != themeKey) {
            themeKey = key
            val cal = Calendar.getInstance()
            val theme = if (chosen == XmbSettings.THEME_BY_MONTH) cal.get(Calendar.MONTH) else chosen
            WaveShader.setAuroraTheme(bg, aurora, theme, cal.get(Calendar.HOUR_OF_DAY))
            bgNode.beginRecording(w, h).drawPaint(bgPaint)
            bgNode.endRecording()
        }
        canvas.drawRenderNode(bgNode)
        if (st == XmbSettings.STYLE_PLAIN) return
        WaveShader.setAurora(aurora, w / 4f, h / w.toFloat(), (t * if (st == XmbSettings.STYLE_CALM) 0.5f else 1f) % 10_000f)
        lightNode.beginRecording().drawPaint(auroraPaint)
        lightNode.endRecording()
        val save = canvas.save()
        canvas.scale(4f, 4f)
        canvas.drawRenderNode(lightNode)
        canvas.restoreToCount(save)
    }

    /** Искры дрейфуют вдоль средней линии пучка, мерцают и гаснут к краям экрана. */
    private fun drawSparks(canvas: Canvas, t: Float, flow: Float) {
        val half = spark.width / 2f
        for (i in 0 until SPARKS) {
            val u = frac(sparkSeeds[i * 4] + (t + flow * 0.6f) * sparkSeeds[i * 4 + 1])
            val x = u * w
            val y = waveY + WaveShader.midOffset(x, w.toFloat()) + sparkSeeds[i * 4 + 2] * waveHalf * 0.35f
            val tw = 0.5f + 0.5f * sin(t * (1.3f + sparkSeeds[i * 4 + 1] * 60f) + sparkSeeds[i * 4 + 3])
            val edge = sin(u * Math.PI.toFloat())
            val a = tw * tw * edge
            if (a < 0.03f) continue
            val k = 0.6f + 0.6f * tw
            sparkPaint.alpha = (a * 200).toInt()
            dst.set(x - half * k, y - half * k, x + half * k, y + half * k)
            canvas.drawBitmap(spark, null, dst, sparkPaint)
        }
    }

    /** Цвет темы — выбранный или по месяцу, яркость — по часу; слой градиента пишется только при смене. */
    private fun updateTheme() {
        val chosen = XmbSettings.theme
        val key = System.currentTimeMillis() / 60_000 * 100 + chosen + 1
        if (key == themeKey) return
        themeKey = key
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val theme = if (chosen == XmbSettings.THEME_BY_MONTH) cal.get(Calendar.MONTH) else chosen
        WaveShader.setTheme(bg, light, theme, hour)
        bgNode.beginRecording(w, h).drawPaint(bgPaint)
        bgNode.endRecording()
    }

    private fun frac(x: Float) = x - kotlin.math.floor(x)

    private companion object {
        const val SPARKS = 28
        const val NOTHING_KEY = -2L
        const val AURORA_KEY = 1L shl 50
    }
}

/**
 * Связь лаунчера с фоном (тот же процесс, главный поток): смена раздела подгоняет течение волны,
 * и оно плавно успокаивается; busy — идёт касание или анимация, фону стоит рисовать каждый кадр.
 */
object BgBus {
    /** Идёт касание или анимация в XMB; когда становится true — будим живые обои. */
    @Volatile var busy = false
        set(v) {
            field = v
            if (v) wake()
        }

    /** Последнее касание XMB: фон замирает через FREEZE_MS после него, если так выбрано в настройках. */
    @Volatile var lastTouch = 0L

    const val FREEZE_MS = 30_000L

    private val wakers = ArrayList<() -> Unit>()

    fun touch() {
        lastTouch = System.currentTimeMillis()
        wake()
    }

    fun frozen(): Boolean = XmbSettings.freezeBg && !busy && System.currentTimeMillis() - lastTouch > FREEZE_MS

    fun onWake(l: () -> Unit) {
        wakers += l
    }

    fun offWake(l: () -> Unit) {
        wakers -= l
    }

    private fun wake() = wakers.forEach { it() }

    private var vel = 0f
    private var shift = 0f
    private var last = 0L

    /** Толчок течения: секунд «лишнего» хода волны в секунду; знак — направление. */
    fun kick(v: Float) {
        vel += v
    }

    fun flow(now: Long): Float {
        if (last != 0L) {
            val dt = ((now - last) / 1e9f).coerceIn(0f, 0.1f)
            shift += vel * dt
            vel *= exp(-dt * 2.2f)
        }
        last = now
        return shift
    }
}
