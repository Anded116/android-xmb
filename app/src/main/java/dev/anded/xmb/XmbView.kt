package dev.anded.xmb

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Весь экран XMB одним View: фон-шейдер, полоса разделов, колонка приложений, меню.
 * Анимации считаются в onDraw по времени кадра, без аллокаций на кадр.
 *
 * Раскладка как в XMB плеера HiBy R3II (github.com/Anded116/r3ii-xmb): колонка держится за «выбранный» пункт под рядом разделов,
 * предыдущие пункты лежат над рядом, слева видна часть предыдущего раздела.
 * Выбранный — только место в списке: тап по любому видимому пункту сразу запускает его.
 */
@SuppressLint("ViewConstructor")
class XmbView(context: Context, private val iconPx: Int) : View(context) {

    class MenuItem(val label: String, val action: () -> Unit)

    var onLaunch: (AppEntry, Rect) -> Unit = { _, _ -> }
    var onMenu: (AppEntry, Int) -> List<MenuItem> = { _, _ -> emptyList() }
    var onCategory: (Int) -> Unit = {}
    var onBusy: (Boolean) -> Unit = {}

    private val metrics = resources.displayMetrics
    private val dp = metrics.density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, metrics)

    private val count = Categories.all.size
    private var columns: List<List<AppEntry>> = List(count) { emptyList() }

    // Позиция колонки — индекс пункта на месте выбранного (дробный во время движения).
    private val pos = FloatArray(count)
    private val posVel = FloatArray(count)
    private val posTarget = IntArray(count)

    private val catBig = 60 * dp
    private val catSmall = 42 * dp
    private val itemH = 74 * dp
    private val menuRowH = 54 * dp
    private var catSpacing = 0f
    private var focusX = 0f
    private var barY = 0f
    private var selY = 0f
    private var aboveY = 0f
    private var textX = 0f
    private var textMaxW = 0f
    private var topInset = 0
    private var bottomInset = 0

    var category = Categories.DEFAULT
        private set
    private var catPos = category.toFloat()
    private var catVel = 0f
    private var tickIndex = category

    private enum class Drag { NONE, PENDING, H, V, MENU }
    private var drag = Drag.NONE
    private var downX = 0f
    private var downY = 0f
    private var dragStartCat = 0f
    private var dragStartPos = 0f
    private var dragCol = 0
    private var pressCol = -1
    private var pressIndex = -1
    private var pressCat = -1
    private var stoppedFling = false
    private var launched = false
    private var velocity: VelocityTracker? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val longPress = Runnable { openMenuForPress() }

    private var menuItems: List<MenuItem> = emptyList()
    private var menuTitle = ""
    private var menuOpen = false
    private var menuT = 0f

    private var active = false
    private var busy = false
    private var lastFrame = 0L
    private var lastTouch = 0L
    private val startNs = System.nanoTime()

    // Градиент меняется раз в час: слой записывается заново только тогда, в кадре — готовая текстура.
    private val bg = RuntimeShader(WaveShader.BG)
    private val bgPaint = Paint().apply { shader = bg }
    private val bgNode = RenderNode("bg").apply { setUseCompositingLayer(true, null) }
    private var themeHour = -1L

    // Волна — только в полосе, где проходят линии, в полном разрешении: линии тонкие, как на плеере.
    private val wave = RuntimeShader(WaveShader.WAVE)
    private val wavePaint = Paint().apply {
        shader = wave
        blendMode = BlendMode.SCREEN
    }
    private var waveY = 0f
    private var waveHalf = 0f

    private val glyphs = HashMap<Glyph, Bitmap>()
    private val glows = HashMap<Glyph, Bitmap>()
    private val catPad = GlyphArt.padFor(catBig)
    private val iconPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val labelPaint = textPaint(18f, Typeface.create("sans-serif", Typeface.NORMAL))
    private val subPaint = textPaint(13f, Typeface.create("sans-serif", Typeface.NORMAL))
    private val catLabelPaint = textPaint(15f, Typeface.create("sans-serif-medium", Typeface.NORMAL)).apply {
        textAlign = Paint.Align.CENTER
    }
    private val menuTitlePaint = textPaint(19f, Typeface.create("sans-serif-medium", Typeface.NORMAL))
    private val menuItemPaint = textPaint(17f, Typeface.create("sans-serif", Typeface.NORMAL))
    private val dimPaint = Paint().apply { color = Color.BLACK }
    private val menuPaint = Paint().apply { color = 0xFF0A0E18.toInt() }
    private val menuEdgePaint = Paint().apply { color = 0x66FFFFFF }
    private val labelCenter = -(labelPaint.fontMetrics.ascent + labelPaint.fontMetrics.descent) / 2f
    private val menuCenter = -(menuItemPaint.fontMetrics.ascent + menuItemPaint.fontMetrics.descent) / 2f

    private val dst = RectF()
    private val launchRect = Rect()

    init {
        setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            topInset = bars.top
            bottomInset = bars.bottom
            insets
        }
    }

    private fun textPaint(sizeSp: Float, face: Typeface) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(sizeSp)
        typeface = face
        color = Color.WHITE
        setShadowLayer(3 * dp, 0f, 1 * dp, 0x99000000.toInt())
    }

    fun setColumns(cols: List<List<AppEntry>>) {
        columns = cols
        if (pressCol >= 0 && pressIndex >= columns[pressCol].size) pressIndex = -1
        for (c in 0 until count) {
            val last = max(0, columns[c].lastIndex)
            posTarget[c] = posTarget[c].coerceIn(0, last)
            if (pos[c] > last) pos[c] = last.toFloat()
        }
        invalidate()
    }

    fun setCategory(index: Int, animate: Boolean = true) {
        val t = index.coerceIn(0, count - 1)
        if (t != category) {
            category = t
            onCategory(t)
        }
        if (!animate) {
            catPos = t.toFloat()
            catVel = 0f
            tickIndex = t
        }
        invalidate()
    }

    fun setActive(on: Boolean) {
        active = on
        removeCallbacks(longPress)
        if (on) {
            lastFrame = System.nanoTime()
            lastTouch = lastFrame
            // Пока лаунчер был в фоне, система могла освободить слой фона — записать заново.
            themeHour = -1
            invalidate()
        } else {
            drag = Drag.NONE
            pressIndex = -1
            pressCat = -1
            launched = false
            // Окно уже отпустило 120 Гц; при возвращении первое касание должно запросить его заново.
            busy = false
            FrameRate.vote(this, false)
        }
    }

    fun closeMenu(): Boolean {
        if (!menuOpen) return false
        menuOpen = false
        invalidate()
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        focusX = w * 0.24f
        catSpacing = w * 0.22f
        barY = h * 0.30f
        selY = barY + 104 * dp
        aboveY = barY - 80 * dp
        textX = focusX + iconPx / 2f + 16 * dp
        textMaxW = w - textX - 20 * dp
        bgNode.setPosition(0, 0, w, h)
        bg.setFloatUniform("res", w.toFloat(), h.toFloat())
        themeHour = -1
        waveY = h * 0.56f
        waveHalf = WaveShader.bandHalf(w.toFloat())
        WaveShader.setGeometry(wave, w.toFloat(), waveY)
    }

    // ---------- кадр ----------

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = ((now - lastFrame) / 1e9f).coerceIn(0f, 0.05f)
        lastFrame = now
        val moving = step(dt)

        updateTheme()
        canvas.drawRenderNode(bgNode)
        WaveShader.setTime(wave, width.toFloat(), ((now - startNs) / 1e9f) % 3600f)
        canvas.drawRect(0f, waveY - waveHalf, width.toFloat(), waveY + waveHalf, wavePaint)

        val c0 = floor(catPos).toInt()
        for (c in c0..c0 + 1) {
            if (c !in 0 until count) continue
            val a = 1f - abs(c - catPos) * 1.3f
            if (a > 0.01f) drawColumn(canvas, c, (c - catPos) * catSpacing, a)
        }
        drawBar(canvas)
        if (menuT > 0.001f) drawMenu(canvas)

        val isBusy = moving || drag != Drag.NONE || now - lastTouch < IDLE_NS
        if (isBusy != busy) {
            busy = isBusy
            post {
                onBusy(isBusy)
                FrameRate.vote(this, isBusy)
            }
        }
        if (!active) return
        if (isBusy) postInvalidateOnAnimation() else postInvalidateDelayed(IDLE_FRAME_MS)
    }

    private fun step(dt: Float): Boolean {
        var moving = false
        if (drag != Drag.H) {
            val diff = category - catPos
            if (abs(diff) > 0.0005f || abs(catVel) > 0.002f) {
                catVel += (STIFFNESS * diff - DAMPING * catVel) * dt
                catPos += catVel * dt
                moving = true
            } else {
                catPos = category.toFloat()
                catVel = 0f
            }
        }
        val near = catPos.roundToInt().coerceIn(0, count - 1)
        if (near != tickIndex) {
            tickIndex = near
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        for (c in 0 until count) {
            if (drag == Drag.V && c == dragCol) continue
            val diff = posTarget[c] - pos[c]
            if (abs(diff) > 0.0005f || abs(posVel[c]) > 0.002f) {
                posVel[c] += (POS_STIFFNESS * diff - POS_DAMPING * posVel[c]) * dt
                pos[c] += posVel[c] * dt
                moving = true
            } else {
                pos[c] = posTarget[c].toFloat()
                posVel[c] = 0f
            }
        }
        val mt = if (menuOpen) 1f else 0f
        if (abs(menuT - mt) > 0.002f) {
            menuT += (mt - menuT) * (1f - exp(-dt * 18f))
            moving = true
        } else {
            menuT = mt
        }
        return moving
    }

    /** Цвет темы — по месяцу, яркость — по часу; слой фона перерисовывается раз в час. */
    private fun updateTheme() {
        val hourStamp = System.currentTimeMillis() / 3_600_000
        if (hourStamp == themeHour) return
        themeHour = hourStamp
        val cal = Calendar.getInstance()
        WaveShader.setTheme(bg, cal.get(Calendar.MONTH), cal.get(Calendar.HOUR_OF_DAY))
        bgNode.beginRecording(width, height).drawPaint(bgPaint)
        bgNode.endRecording()
    }

    /** Центр пункта по удалению d от выбранного: ниже ряда — подряд, выше — над рядом, переход — сквозь ряд. */
    private fun itemY(d: Float): Float = when {
        d >= 0f -> selY + d * itemH
        d > -1f -> aboveY + (d + 1f) * (selY - aboveY)
        else -> aboveY + (d + 1f) * itemH
    }

    /** Видимость пункта: гаснет, пролетая сквозь ряд разделов, под часами и у нижнего края. */
    private fun edgeAlpha(y: Float): Float {
        var a = ((abs(y - barY) - 44 * dp) / (30 * dp)).coerceIn(0f, 1f)
        a *= ((y - topInset - 16 * dp) / (36 * dp)).coerceIn(0f, 1f)
        val bottom = height - bottomInset.toFloat()
        a *= ((bottom - y) / (itemH / 2)).coerceIn(0f, 1f)
        return a
    }

    private fun drawColumn(canvas: Canvas, c: Int, ox: Float, alpha: Float) {
        val list = columns[c]
        if (list.isEmpty()) {
            subPaint.alpha = (alpha * 150).toInt()
            canvas.drawText("Пусто", textX + ox, selY + labelCenter, subPaint)
            return
        }
        if (textMaxW != list[0].shownFor) for (e in list) ellipsize(e)
        val p = pos[c]
        val first = max(0, floor(p).toInt() - ABOVE_ROWS)
        val last = min(list.lastIndex, (p + (height - selY) / itemH).toInt() + 1)
        val cx = focusX + ox
        for (i in first..last) {
            val d = i - p
            val y = itemY(d)
            val near = max(0f, 1f - abs(d))
            val base = if (d < -0.5f) ABOVE_ALPHA else BELOW_ALPHA
            val a = alpha * (base + (1f - base) * near) * edgeAlpha(y)
            if (a <= 0.01f) continue
            val e = list[i]
            val half = iconPx / 2f * (1f + SEL_GROW * near)
            if (c == pressCol && i == pressIndex) {
                pressPaint.alpha = (a * 34).toInt()
                dst.set(cx - half - 12 * dp, y - itemH / 2 + 5 * dp, width - 12 * dp, y + itemH / 2 - 5 * dp)
                canvas.drawRoundRect(dst, 16 * dp, 16 * dp, pressPaint)
            }
            dst.set(cx - half, y - half, cx + half, y + half)
            val icon = e.icon
            if (icon != null) {
                iconPaint.alpha = (a * 255).toInt()
                canvas.drawBitmap(icon, null, dst, iconPaint)
            } else {
                placeholderPaint.alpha = (a * 50).toInt()
                canvas.drawRoundRect(dst, half * 0.4f, half * 0.4f, placeholderPaint)
            }
            labelPaint.alpha = (a * 255).toInt()
            val sub = e.shownSub
            if (sub == null) {
                canvas.drawText(e.shown, textX + ox, y + labelCenter, labelPaint)
            } else {
                canvas.drawText(e.shown, textX + ox, y - 2 * dp, labelPaint)
                subPaint.alpha = (a * 180).toInt()
                canvas.drawText(sub, textX + ox, y + 18 * dp, subPaint)
            }
        }
    }

    private fun ellipsize(e: AppEntry) {
        e.shown = TextUtils.ellipsize(e.label, labelPaint, textMaxW, TextUtils.TruncateAt.END).toString()
        e.shownSub = e.subtitle?.let { TextUtils.ellipsize(it, subPaint, textMaxW, TextUtils.TruncateAt.END).toString() }
        e.shownFor = textMaxW
    }

    private fun drawBar(canvas: Canvas) {
        for (i in 0 until count) {
            val x = focusX + (i - catPos) * catSpacing
            if (x < -catSpacing || x > width + catSpacing) continue
            val d = abs(i - catPos)
            val t = min(d, 1f)
            val size = catBig + (catSmall - catBig) * t
            val glyph = Categories.all[i].glyph
            val half = (catBig / 2 + catPad) * size / catBig
            dst.set(x - half, barY - half, x + half, barY + half)
            if (d < 1f) {
                iconPaint.alpha = ((1f - d) * 150).toInt()
                canvas.drawBitmap(glow(glyph), null, dst, iconPaint)
            }
            iconPaint.alpha = ((1f - 0.45f * t) * 255).toInt()
            canvas.drawBitmap(glyph(glyph), null, dst, iconPaint)
            val la = (1f - d * 1.8f).coerceIn(0f, 1f)
            if (la > 0f) {
                catLabelPaint.alpha = (la * 255).toInt()
                canvas.drawText(Categories.all[i].title, x, barY + catBig / 2 + 26 * dp, catLabelPaint)
            }
        }
    }

    private fun glyph(glyph: Glyph): Bitmap =
        glyphs.getOrPut(glyph) { GlyphArt.icon(context, glyph, catBig.roundToInt(), catPad) }

    private fun glow(glyph: Glyph): Bitmap =
        glows.getOrPut(glyph) { GlyphArt.glow(context, glyph, catBig.roundToInt(), catPad) }

    private fun menuLeft(t: Float): Float {
        val e = 1f - (1f - t) * (1f - t) * (1f - t)
        return width - width * MENU_WIDTH * e
    }

    private fun menuTop() = barY - 4 * dp

    private fun drawMenu(canvas: Canvas) {
        dimPaint.alpha = (120 * menuT).toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        val left = menuLeft(menuT)
        menuPaint.alpha = (238 * menuT).toInt()
        canvas.drawRect(left, 0f, width.toFloat(), height.toFloat(), menuPaint)
        menuEdgePaint.alpha = (110 * menuT).toInt()
        canvas.drawRect(left, 0f, left + 1.5f * dp, height.toFloat(), menuEdgePaint)
        val tx = left + 24 * dp
        menuTitlePaint.alpha = (255 * menuT).toInt()
        canvas.drawText(menuTitle, tx, barY - 34 * dp, menuTitlePaint)
        menuItemPaint.alpha = (255 * menuT).toInt()
        val top = menuTop()
        for (i in menuItems.indices) {
            canvas.drawText(menuItems[i].label, tx, top + i * menuRowH + menuRowH / 2 + menuCenter, menuItemPaint)
        }
    }

    // ---------- касания ----------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        lastTouch = System.nanoTime()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> down(e)
            MotionEvent.ACTION_MOVE -> move(e)
            MotionEvent.ACTION_UP -> up(e)
            MotionEvent.ACTION_CANCEL -> end()
        }
        invalidate()
        return true
    }

    private fun down(e: MotionEvent) {
        velocity?.recycle()
        velocity = VelocityTracker.obtain().also { it.addMovement(e) }
        downX = e.x
        downY = e.y
        launched = false
        if (menuOpen) {
            drag = Drag.MENU
            return
        }
        drag = Drag.PENDING
        dragCol = catPos.roundToInt().coerceIn(0, count - 1)
        // Касание по летящему списку только останавливает его, а не запускает пункт под пальцем.
        stoppedFling = abs(posVel[dragCol]) > 1.5f
        if (stoppedFling) {
            posTarget[dragCol] = pos[dragCol].roundToInt().coerceIn(0, max(0, columns[dragCol].lastIndex))
            posVel[dragCol] = 0f
        }
        pressCat = hitCategory(e.x, e.y)
        pressIndex = if (pressCat < 0 && !stoppedFling) hitItem(dragCol, e.y) else -1
        pressCol = dragCol
        if (pressIndex >= 0) postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
    }

    private fun move(e: MotionEvent) {
        velocity?.addMovement(e)
        if (drag == Drag.PENDING) {
            val dx = e.x - downX
            val dy = e.y - downY
            if (hypot(dx, dy) <= slop) return
            removeCallbacks(longPress)
            pressIndex = -1
            pressCat = -1
            drag = if (abs(dx) > abs(dy)) Drag.H else Drag.V
            downX = e.x
            downY = e.y
            dragStartCat = catPos
            dragStartPos = pos[dragCol]
            posVel[dragCol] = 0f
        }
        when (drag) {
            // За пальцем — не дальше соседнего раздела, дальше «резинка».
            Drag.H -> {
                val p = dragStartCat - (e.x - downX) / catSpacing
                val lo = max(0, category - 1).toFloat()
                val hi = min(count - 1, category + 1).toFloat()
                catPos = when {
                    p < lo -> lo - (lo - p) * RUBBER
                    p > hi -> hi + (p - hi) * RUBBER
                    else -> p
                }
                catVel = 0f
            }
            Drag.V -> {
                val p = dragStartPos - (e.y - downY) / itemH
                val maxP = max(0, columns[dragCol].lastIndex).toFloat()
                pos[dragCol] = when {
                    p < 0f -> p * RUBBER
                    p > maxP -> maxP + (p - maxP) * RUBBER
                    else -> p
                }
            }
            else -> Unit
        }
    }

    private fun up(e: MotionEvent) {
        val v = velocity
        v?.addMovement(e)
        v?.computeCurrentVelocity(1000)
        val vx = v?.xVelocity ?: 0f
        val vy = v?.yVelocity ?: 0f
        when (drag) {
            Drag.PENDING -> tap()
            Drag.H -> {
                // Свайп — ровно на один раздел: быстрый решает направление, медленный — дотянул ли до трети.
                val vc = -vx / catSpacing
                val delta = catPos - category
                val dir = when {
                    abs(vc) > SWIPE_VELOCITY -> sign(vc).toInt()
                    abs(delta) > SWIPE_SHARE -> sign(delta).toInt()
                    else -> 0
                }
                catVel = vc.coerceIn(-6f, 6f)
                setCategory(category + dir)
            }
            Drag.V -> {
                val c = dragCol
                posVel[c] = (-vy / itemH).coerceIn(-40f, 40f)
                posTarget[c] = (pos[c] + posVel[c] * FLING_TIME).roundToInt().coerceIn(0, max(0, columns[c].lastIndex))
            }
            Drag.MENU -> menuTap(e.x, e.y)
            Drag.NONE -> Unit
        }
        end()
    }

    private fun end() {
        removeCallbacks(longPress)
        velocity?.recycle()
        velocity = null
        drag = Drag.NONE
        pressCat = -1
        if (!launched) pressIndex = -1
    }

    private fun tap() {
        if (stoppedFling) return
        if (pressCat >= 0) {
            if (pressCat == category && abs(catPos - category) < 0.01f) {
                posTarget[category] = 0
            } else {
                setCategory(pressCat)
            }
            return
        }
        val entry = columns.getOrNull(pressCol)?.getOrNull(pressIndex) ?: return
        val y = itemY(pressIndex - pos[pressCol]).toInt()
        val half = iconPx / 2
        launchRect.set(focusX.toInt() - half, y - half, focusX.toInt() + half, y + half)
        launched = true
        onLaunch(entry, launchRect)
    }

    private fun openMenuForPress() {
        val entry = columns.getOrNull(pressCol)?.getOrNull(pressIndex) ?: return
        val items = onMenu(entry, pressCol)
        drag = Drag.NONE
        pressIndex = -1
        if (items.isEmpty()) return
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        menuItems = items
        menuTitle = TextUtils.ellipsize(entry.label, menuTitlePaint, width * MENU_WIDTH - 48 * dp, TextUtils.TruncateAt.END).toString()
        menuOpen = true
        invalidate()
    }

    private fun menuTap(x: Float, y: Float) {
        if (hypot(x - downX, y - downY) > slop) return
        if (x < menuLeft(1f)) {
            closeMenu()
            return
        }
        val i = floor((y - menuTop()) / menuRowH).toInt()
        val item = menuItems.getOrNull(i) ?: return
        closeMenu()
        item.action()
    }

    private fun hitCategory(x: Float, y: Float): Int {
        if (y < barY - 40 * dp || y > barY + 60 * dp) return -1
        val i = (catPos + (x - focusX) / catSpacing).roundToInt()
        return if (i in 0 until count) i else -1
    }

    /** Пункт под пальцем: ближайший по центру среди видимых. */
    private fun hitItem(col: Int, y: Float): Int {
        val list = columns[col]
        if (list.isEmpty()) return -1
        val p = pos[col]
        val first = max(0, floor(p).toInt() - ABOVE_ROWS)
        val last = min(list.lastIndex, (p + (height - selY) / itemH).toInt() + 1)
        var best = -1
        var bestDist = itemH / 2
        for (i in first..last) {
            val iy = itemY(i - p)
            val dist = abs(iy - y)
            if (dist < bestDist && edgeAlpha(iy) > 0.3f) {
                best = i
                bestDist = dist
            }
        }
        return best
    }

    private companion object {
        const val STIFFNESS = 240f
        const val DAMPING = 27f
        const val POS_STIFFNESS = 150f
        const val POS_DAMPING = 24f
        const val FLING_TIME = 0.32f
        const val SWIPE_VELOCITY = 1.6f
        const val SWIPE_SHARE = 0.3f
        const val RUBBER = 0.3f
        const val ABOVE_ROWS = 4
        const val ABOVE_ALPHA = 0.55f
        const val BELOW_ALPHA = 0.82f
        const val SEL_GROW = 0.14f
        const val MENU_WIDTH = 0.66f
        const val IDLE_NS = 5_000_000_000L
        const val IDLE_FRAME_MS = 33L
    }
}
