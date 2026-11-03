package dev.anded.xmb

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.os.BatteryManager
import android.text.TextUtils
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

    /**
     * Пункт меню. swatch — образец градиента темы слева, icon — значок (ярлыки приложений),
     * checked — галочка у текущего варианта, keepOpen — меню остаётся открытым: вариант применяется сразу.
     */
    class MenuItem(
        val label: String,
        val swatch: IntArray? = null,
        val checked: Boolean = false,
        val keepOpen: Boolean = false,
        val icon: Bitmap? = null,
        val action: () -> Unit,
    ) {
        internal var shader: Shader? = null
        internal var shown = label
    }

    var onLaunch: (AppEntry, Rect) -> Unit = { _, _ -> }
    var onMenu: (AppEntry) -> List<MenuItem> = { emptyList() }
    var onCategory: (Int) -> Unit = {}
    var onBusy: (Boolean) -> Unit = {}
    var onOpenEvent: (NextEvent) -> Unit = {}
    var onSearch: () -> Unit = {}

    /** Ближайшее событие календаря для карточки «Сейчас»; null — нет или нет разрешения. */
    var calendar: NextEvent? = null
        set(v) {
            field = v
            invalidate()
        }

    private val metrics = resources.displayMetrics
    private val dp = metrics.density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, metrics)

    // Видимые разделы приходят из репозитория вместе с колонками; пустые скрыты, число меняется.
    private var sections: List<Category> = Categories.all.filter { it.always }
    private var titles = sections.map { context.getString(it.title) }
    private var count = sections.size
    private val emptyText = context.getString(R.string.column_empty)
    private var columns: List<List<AppEntry>> = List(count) { emptyList() }

    // Раздел, сохранённый с прошлого раза: применяется, как только он появится среди видимых.
    private var pendingSection: String? = null

    // Док — 5 самых частых: снизу в портрете, вертикально у правого края в альбоме.
    private var dock: List<AppEntry> = emptyList()
    private var dockH = 0f
    private var dockW = 0f
    private val dockIcon = 52 * dp
    private var pressDock = -1

    // Разделы, где есть уведомления: точка на значке раздела. Пересчёт — при смене уведомлений или списка.
    private var catBadge = BooleanArray(count)
    private val noticeListener: () -> Unit = {
        refreshBadges()
        invalidate()
    }

    // Карточка «Сейчас» над рядом разделов: музыка и ближайшее событие.
    private val cardRect = RectF()
    private var cardPkg: String? = null
    private var cardApp: AppEntry? = null
    private val cardButtons = arrayOf(RectF(), RectF(), RectF())
    private var pressCard = CARD_NONE

    // Подсказки поверх всего; закрываются тапом.
    private var hintsOpen = false

    // Позиция колонки — индекс пункта на месте выбранного (дробный во время движения).
    private var pos = FloatArray(count)
    private var posVel = FloatArray(count)
    private var posTarget = IntArray(count)

    // Размеры зависят от ориентации (onSizeChanged); значки разделов рисуются один раз в размере catBase.
    private val catBase = 60 * dp
    private var catBig = catBase
    private var catSmall = 42 * dp
    private var itemH = 74 * dp
    private var iconDraw = iconPx.toFloat()
    private var menuRowH = 54 * dp
    private var catSpacing = 0f
    private var focusX = 0f
    private var barY = 0f
    private var selY = 0f
    private var aboveY = 0f
    private var textX = 0f
    private var textMaxW = 0f
    private var topInset = 0
    private var bottomInset = 0

    var category = 0
        private set
    private var catPos = category.toFloat()
    private var catVel = 0f
    private var tickIndex = category
    private var itemTick = 0

    // SCRUB — жест начат на ряду разделов: ряд едет за пальцем на любое число разделов.
    // INDEX — у правого края длинной колонки: высота экрана — вся колонка, всплывает буква.
    private enum class Drag { NONE, PENDING, H, V, MENU, SCRUB, INDEX }
    private var indexItem = -1
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

    // Двойной тап по пустому месту — поиск: время и место первого тапа.
    private var lastEmptyTap = 0L
    private var emptyTapX = 0f
    private var emptyTapY = 0f
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

    // Фон рисует сам View, пока не стоят живые обои XMB; с ними окно прозрачное и фон — это обои.
    private val bg = BgRenderer(context)
    var ownBackground = true
        set(v) {
            field = v
            invalidate()
        }

    private val glyphs = HashMap<Glyph, Bitmap>()
    private val glows = HashMap<Glyph, Bitmap>()
    private val catPad = GlyphArt.padFor(catBase)
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
    private val swatchPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A2236.toInt() }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE5484D.toInt() }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(11f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private var badgeCenter = 0f
    private val dockPaint = Paint()
    private var dockShader: Shader? = null
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val cardTitlePaint = textPaint(16f, Typeface.create("sans-serif-medium", Typeface.NORMAL))
    private val cardSubPaint = textPaint(13.5f, Typeface.create("sans-serif", Typeface.NORMAL))
    private val smallGlyphs = HashMap<Glyph, Bitmap>()

    // Свои часы в альбоме, где строка состояния скрыта: дата, заряд, время. Текст — раз в минуту и при смене заряда.
    private val lineClockPaint = textPaint(13f, Typeface.create("sans-serif-light", Typeface.NORMAL)).apply {
        textAlign = Paint.Align.RIGHT
    }
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val shortDateFormat = SimpleDateFormat("EE, d MMM", Locale.getDefault())
    private var clockMinute = -1L
    private var clockBase = 0f
    private var lineText = ""
    private var batteryText = ""
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onBattery(intent)
    }
    private val bubbleTextPaint = textPaint(30f, Typeface.create("sans-serif-medium", Typeface.NORMAL)).apply {
        textAlign = Paint.Align.CENTER
    }
    private var bubbleCenter = 0f
    private var labelCenter = 0f
    private var menuCenter = 0f

    // Оформление: стекло (Аврора) — плашка выбранного пункта, карточка и док.
    private var look = XmbSettings.look
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val glassEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
    }
    private val dotRulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4A4A4A.toInt()
        strokeCap = Paint.Cap.ROUND
    }

    private val dst = RectF()
    private val launchRect = Rect()

    init {
        applyLook()
        setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            topInset = bars.top
            bottomInset = bars.bottom
            insets
        }
    }

    private fun textPaint(sizeSp: Float, face: Typeface) = ShadowTextPaint(dp).apply {
        textSize = sp(sizeSp)
        typeface = face
        color = Color.WHITE
    }

    /**
     * Шрифты, значки разделов и панели под XmbSettings.look. XMB — Roboto; Nothing — заголовки точечным
     * NDot (крупнее и вразрядку: точки на глаз мельче), подписи Inter; Аврора — Inter.
     */
    fun applyLook() {
        look = XmbSettings.look
        val sans = Typeface.create("sans-serif", Typeface.NORMAL)
        val sansMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val dot = look == Look.NOTHING
        val text = if (look == Look.XMB) sans else Fonts.inter
        val title = when (look) {
            Look.XMB -> sansMedium
            Look.NOTHING -> Fonts.dotTitle
            Look.AURORA -> Fonts.interMedium
        }
        for (pt in arrayOf(labelPaint, subPaint, menuItemPaint, cardSubPaint)) pt.typeface = text
        for (pt in arrayOf(catLabelPaint, menuTitlePaint, bubbleTextPaint)) {
            pt.typeface = title
            pt.letterSpacing = if (dot) 0.08f else 0f
        }
        cardTitlePaint.typeface = if (look == Look.XMB) sansMedium else Fonts.interMedium
        lineClockPaint.typeface = when (look) {
            Look.XMB -> Typeface.create("sans-serif-light", Typeface.NORMAL)
            Look.NOTHING -> Fonts.dotSmall
            Look.AURORA -> Fonts.interLight
        }
        lineClockPaint.letterSpacing = if (dot) 0.06f else 0f
        catLabelPaint.textSize = sp(if (dot) 17f else 15f)
        menuTitlePaint.textSize = sp(if (dot) 21f else 19f)
        lineClockPaint.textSize = sp(if (dot) 14f else 13f)
        badgePaint.color = if (dot) NOTHING_RED else 0xFFE5484D.toInt()
        menuPaint.color = when (look) {
            Look.XMB -> 0xFF0A0E18.toInt()
            Look.NOTHING -> Color.BLACK
            Look.AURORA -> 0xFF0E1226.toInt()
        }
        badgeCenter = -(badgeTextPaint.fontMetrics.ascent + badgeTextPaint.fontMetrics.descent) / 2f
        bubbleCenter = -(bubbleTextPaint.fontMetrics.ascent + bubbleTextPaint.fontMetrics.descent) / 2f
        labelCenter = -(labelPaint.fontMetrics.ascent + labelPaint.fontMetrics.descent) / 2f
        menuCenter = -(menuItemPaint.fontMetrics.ascent + menuItemPaint.fontMetrics.descent) / 2f
        glyphs.clear()
        glows.clear()
        smallGlyphs.clear()
        for (col in columns) for (e in col) e.shownFor = -1f
        if (width > 0) onSizeChanged(width, height, width, height)
        invalidate()
    }

    fun setColumns(cols: List<List<AppEntry>>, secs: List<Category>, dockApps: List<AppEntry>) {
        if (secs.map { it.id } != sections.map { it.id }) resizeSections(secs)
        columns = cols
        val relayout = dock.isEmpty() != dockApps.isEmpty()
        dock = dockApps
        if (relayout && width > 0) onSizeChanged(width, height, width, height)
        refreshBadges()
        if (pressCol >= 0 && pressIndex >= columns[pressCol].size) pressIndex = -1
        for (c in 0 until count) {
            val last = max(0, columns[c].lastIndex)
            posTarget[c] = posTarget[c].coerceIn(0, last)
            if (pos[c] > last) pos[c] = last.toFloat()
        }
        invalidate()
    }

    /** Набор видимых разделов сменился: состояние колонок переносится по id, текущий раздел остаётся тем же. */
    private fun resizeSections(secs: List<Category>) {
        val keep = pendingSection ?: sections.getOrNull(category)?.id
        val oldIds = sections.map { it.id }
        val oldPos = pos
        val oldTarget = posTarget
        sections = secs
        titles = secs.map { context.getString(it.title) }
        count = secs.size
        pos = FloatArray(count)
        posVel = FloatArray(count)
        posTarget = IntArray(count)
        catBadge = BooleanArray(count)
        for (j in 0 until count) {
            val o = oldIds.indexOf(secs[j].id)
            if (o >= 0) {
                pos[j] = oldPos[o]
                posTarget[j] = oldTarget[o]
            }
        }
        val found = secs.indexOfFirst { it.id == keep }
        if (found >= 0 && keep == pendingSection) pendingSection = null
        val t = if (found >= 0) found else category.coerceIn(0, count - 1)
        category = t
        catPos = t.toFloat()
        catVel = 0f
        tickIndex = t
        itemTick = pos[t].roundToInt()
        drag = Drag.NONE
        pressCol = -1
        pressIndex = -1
        pressCat = -1
    }

    /** Вернуть раздел с прошлого раза; если он ещё не загрузился — как только появится. */
    fun restoreSection(id: String) {
        val i = sections.indexOfFirst { it.id == id }
        if (i >= 0) setCategory(i, animate = false) else pendingSection = id
    }

    fun sectionId(): String = sections.getOrNull(category)?.id ?: Categories.TOP

    fun setCategory(index: Int, animate: Boolean = true) {
        val t = index.coerceIn(0, count - 1)
        if (t != category) {
            if (animate) BgBus.kick(if (t > category) 3.5f else -3.5f)
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

    /** Перейти к разделу и прокрутить колонку к пункту — показать, куда легло новое приложение. */
    fun reveal(col: Int, index: Int) {
        setCategory(col)
        if (index >= 0) posTarget[col] = index.coerceIn(0, max(0, columns[col].lastIndex))
        invalidate()
    }

    fun showHints() {
        hintsOpen = true
        lastTouch = System.nanoTime()
        invalidate()
    }

    private fun refreshBadges() {
        for (c in 0 until count) {
            catBadge[c] = sections[c].id != Categories.SETTINGS_ID && columns[c].any { Notices.count(it.component?.packageName) > 0 }
        }
    }

    fun setActive(on: Boolean) {
        active = on
        removeCallbacks(longPress)
        if (on) {
            lastFrame = System.nanoTime()
            lastTouch = lastFrame
            BgBus.touch()
            // Пока лаунчер был в фоне, система могла освободить слой фона — записать заново.
            bg.invalidateLayers()
            invalidate()
        } else {
            drag = Drag.NONE
            pressIndex = -1
            pressCat = -1
            pressDock = -1
            pressCard = CARD_NONE
            launched = false
            // Окно уже отпустило 120 Гц; при возвращении первое касание должно запросить его заново.
            busy = false
            BgBus.busy = false
            FrameRate.vote(this, false)
        }
    }

    fun closeHints(): Boolean {
        if (!hintsOpen) return false
        hintsOpen = false
        invalidate()
        return true
    }

    fun closeMenu(): Boolean {
        if (!menuOpen) return false
        menuOpen = false
        invalidate()
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // В альбоме экран низкий: всё мельче, ряд разделов у верха (строка состояния там скрыта),
        // под ним помещается пять пунктов; шаг разделов — меньшая доля ширины.
        val landscape = w > h
        catBig = (if (landscape) 46 else 60) * dp
        catSmall = (if (landscape) 34 else 42) * dp
        itemH = (if (landscape) 54 else 74) * dp
        iconDraw = if (landscape) 38 * dp else iconPx.toFloat()
        focusX = w * if (landscape) 0.30f else 0.24f
        catSpacing = w * if (landscape) 0.11f else 0.22f
        // Альбом: своя строка состояния в STATUS_GAP от верха, ряд разделов — в том же зазоре под ней
        // (до видимой верхушки выбранного значка).
        val cap = Rect().also { lineClockPaint.getTextBounds("0", 0, 1, it) }.height()
        clockBase = STATUS_GAP * dp + cap
        barY = if (landscape) clockBase + STATUS_GAP * dp + catBig * ROW_TOP else h * 0.30f
        selY = barY + (if (landscape) 82 else 104) * dp
        aboveY = barY - (if (landscape) 64 else 80) * dp
        dockH = if (landscape || dock.isEmpty()) 0f else 96 * dp
        dockW = if (landscape && dock.isNotEmpty()) 76 * dp else 0f
        textX = focusX + iconDraw / 2f + 16 * dp
        textMaxW = w - textX - 20 * dp - dockW
        dockShader = if (landscape || look != Look.XMB) null else LinearGradient(
            0f, h - bottomInset - dockH - 24 * dp, 0f, h.toFloat(), 0, 0x59000000, Shader.TileMode.CLAMP,
        )
        bg.setSize(w, h)
    }

    // ---------- кадр ----------

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = ((now - lastFrame) / 1e9f).coerceIn(0f, 0.05f)
        lastFrame = now
        val moving = step(dt)

        // Под живыми обоями окно прозрачное: без очистки полупрозрачные тени и ореолы копятся от кадра к кадру.
        val frozen = !moving && drag == Drag.NONE && XmbSettings.freezeBg && now - lastTouch > FREEZE_NS
        if (ownBackground) bg.draw(canvas, advance = !frozen) else canvas.drawColor(Color.TRANSPARENT, BlendMode.CLEAR)

        val c0 = floor(catPos).toInt()
        for (c in c0..c0 + 1) {
            if (c !in 0 until count) continue
            val a = 1f - abs(c - catPos) * 1.3f
            if (a > 0.01f) drawColumn(canvas, c, (c - catPos) * catSpacing, a)
        }
        drawBar(canvas)
        drawCard(canvas)
        drawDock(canvas)
        drawClock(canvas)
        if (drag == Drag.INDEX) drawIndexBubble(canvas)
        if (menuT > 0.001f) drawMenu(canvas)
        if (hintsOpen) drawHints(canvas)

        val isBusy = moving || drag != Drag.NONE || now - lastTouch < IDLE_NS
        if (isBusy != busy) {
            busy = isBusy
            BgBus.busy = isBusy
            post {
                onBusy(isBusy)
                FrameRate.vote(this, isBusy)
            }
        }
        if (!active) return
        when {
            isBusy -> postInvalidateOnAnimation()
            // Замер: кадров нет до касания; часы в альбоме — раз в минуту.
            frozen -> postInvalidateDelayed(60_000 - System.currentTimeMillis() % 60_000 + 50)
            else -> postInvalidateDelayed(IDLE_FRAME_MS)
        }
    }

    private fun step(dt: Float): Boolean {
        var moving = false
        if (drag != Drag.H && drag != Drag.SCRUB) {
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
            itemTick = pos[near].roundToInt()
            performHapticFeedback(Haptics.SECTION)
        } else {
            // Щелчок колёсика: пункт колонки прошёл через место выбранного (и пальцем, и по инерции).
            val idx = pos[near].roundToInt()
            if (idx != itemTick) {
                itemTick = idx
                performHapticFeedback(Haptics.ITEM)
            }
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

    /** Центр пункта по удалению d от выбранного: ниже ряда — подряд, выше — над рядом, переход — сквозь ряд. */
    private fun itemY(d: Float): Float = when {
        d >= 0f -> selY + d * itemH
        d > -1f -> aboveY + (d + 1f) * (selY - aboveY)
        else -> aboveY + (d + 1f) * itemH
    }

    /** Видимость пункта: гаснет, пролетая сквозь ряд разделов, под карточкой «Сейчас» и у нижнего края. */
    private fun edgeAlpha(y: Float): Float {
        var a = ((abs(y - barY) - 44 * dp) / (30 * dp)).coerceIn(0f, 1f)
        val top = max(topInset + 16 * dp, cardBottom() - 12 * dp)
        a *= ((y - top) / (36 * dp)).coerceIn(0f, 1f)
        val bottom = height - bottomInset - dockH
        a *= ((bottom - y) / (itemH / 2)).coerceIn(0f, 1f)
        return a
    }

    private fun drawColumn(canvas: Canvas, c: Int, ox: Float, alpha: Float) {
        val list = columns[c]
        if (list.isEmpty()) {
            subPaint.fade((alpha * 150).toInt())
            canvas.drawText(emptyText, textX + ox, selY + labelCenter, subPaint)
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
            val half = iconDraw / 2f * (1f + SEL_GROW * near)
            if (look == Look.AURORA && near > 0.02f) {
                val right = if (width > height) textX + ox + e.shownW + 20 * dp else width - 16 * dp
                dst.set(cx - half - 14 * dp, y - itemH / 2 + 4 * dp, right, y + itemH / 2 - 4 * dp)
                glass(canvas, dst, itemH / 2, a * near)
            }
            if (c == pressCol && i == pressIndex) {
                pressPaint.alpha = (a * 34).toInt()
                val right = if (width > height) textX + ox + e.shownW + 16 * dp else width - 12 * dp
                dst.set(cx - half - 12 * dp, y - itemH / 2 + 5 * dp, right, y + itemH / 2 - 5 * dp)
                canvas.drawRoundRect(dst, 16 * dp, 16 * dp, pressPaint)
            }
            dst.set(cx - half, y - half, cx + half, y + half)
            val icon = e.icon
            if (icon != null) {
                iconPaint.alpha = (a * 255).toInt()
                canvas.drawBitmap(icon, null, dst, iconPaint)
                drawBadge(canvas, Notices.count(e.component?.packageName.takeIf { e.special == null }), a)
            } else {
                placeholderPaint.alpha = (a * 50).toInt()
                canvas.drawRoundRect(dst, half * 0.4f, half * 0.4f, placeholderPaint)
            }
            labelPaint.fade((a * 255).toInt())
            val sub = e.shownSub
            if (sub == null) {
                canvas.drawText(e.shown, textX + ox, y + labelCenter, labelPaint)
            } else {
                canvas.drawText(e.shown, textX + ox, y - 2 * dp, labelPaint)
                subPaint.fade((a * 180).toInt())
                canvas.drawText(sub, textX + ox, y + 18 * dp, subPaint)
            }
        }
    }

    private fun ellipsize(e: AppEntry) {
        e.shown = TextUtils.ellipsize(e.label, labelPaint, textMaxW, TextUtils.TruncateAt.END).toString()
        e.shownSub = e.subtitle?.let { TextUtils.ellipsize(it, subPaint, textMaxW, TextUtils.TruncateAt.END).toString() }
        e.shownFor = textMaxW
        e.shownW = max(labelPaint.measureText(e.shown), e.shownSub?.let(subPaint::measureText) ?: 0f)
    }

    private fun drawBar(canvas: Canvas) {
        for (i in 0 until count) {
            val x = focusX + (i - catPos) * catSpacing
            if (x < -catSpacing || x > width + catSpacing) continue
            val d = abs(i - catPos)
            val t = min(d, 1f)
            val edge = if (dockW > 0f) ((width - dockW - x) / (catSpacing * 0.5f)).coerceIn(0f, 1f) else 1f
            if (edge <= 0f) continue
            val size = catBig + (catSmall - catBig) * t
            val glyph = sections[i].glyph
            val half = (catBase / 2 + catPad) * size / catBase
            dst.set(x - half, barY - half, x + half, barY + half)
            if (d < 1f && look != Look.NOTHING) {
                iconPaint.alpha = ((1f - d) * 150 * edge).toInt()
                canvas.drawBitmap(glow(glyph), null, dst, iconPaint)
            }
            // Nothing: без ореола; невыбранные тусклее — выбранный выделяет яркость.
            val dim = if (look == Look.NOTHING) 0.62f else 0.45f
            iconPaint.alpha = ((1f - dim * t) * 255 * edge).toInt()
            canvas.drawBitmap(glyph(glyph), null, dst, iconPaint)
            if (catBadge[i]) {
                badgePaint.alpha = ((1f - 0.45f * t) * 255 * edge).toInt()
                canvas.drawCircle(x + size * 0.42f, barY - size * 0.42f, 4.5f * dp, badgePaint)
            }
            val la = (1f - d * 1.8f).coerceIn(0f, 1f)
            if (la > 0f) {
                catLabelPaint.fade((la * 255).toInt())
                canvas.drawText(titles[i], x, barY + catBig / 2 + 26 * dp, catLabelPaint)
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Notices.listen(noticeListener)
        context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let(::onBattery)
    }

    override fun onDetachedFromWindow() {
        Notices.unlisten(noticeListener)
        context.unregisterReceiver(batteryReceiver)
        super.onDetachedFromWindow()
    }

    private fun onBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return
        val charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val text = "  ·  " + (level * 100 / scale) + "%" + if (charging) " ↑" else ""
        if (text == batteryText) return
        batteryText = text
        clockMinute = -1
        invalidate()
    }

    /** Число уведомлений в правом верхнем углу значка (dst — прямоугольник значка). */
    private fun drawBadge(canvas: Canvas, n: Int, a: Float) {
        if (n <= 0) return
        val r = 9 * dp
        val cx = dst.right - r * 0.4f
        val cy = dst.top + r * 0.4f
        badgePaint.alpha = (a * 255).toInt()
        canvas.drawCircle(cx, cy, r, badgePaint)
        badgeTextPaint.alpha = (a * 255).toInt()
        canvas.drawText(if (n > 9) "9+" else n.toString(), cx, cy + badgeCenter, badgeTextPaint)
    }

    /** Альбом: пять ячеек дока от строки состояния до низа, поля сверху и снизу равны. */
    private fun dockCell(): Float = (height - bottomInset - 2 * (clockBase + STATUS_GAP * dp)) / DOCK_SIZE

    private fun dockSize(): Float = if (width > height) min(dockIcon, dockCell() - 16 * dp) else dockIcon

    private fun dockCenter(i: Int, out: FloatArray) {
        if (width > height) {
            out[0] = width - dockW / 2
            out[1] = (height - bottomInset) / 2f + (i - (dock.size - 1) / 2f) * dockCell()
        } else {
            val cell = width / DOCK_SIZE.toFloat()
            out[0] = cell * (i + 0.5f)
            out[1] = height - bottomInset - dockH / 2
        }
    }

    private val tmp = FloatArray(2)

    private fun drawDock(canvas: Canvas) {
        if (dock.isEmpty()) return
        val sh = dockShader
        if (sh != null) {
            dockPaint.shader = sh
            canvas.drawRect(0f, height - bottomInset - dockH - 24 * dp, width.toFloat(), height.toFloat(), dockPaint)
        }
        val landscape = width > height
        when (look) {
            // Nothing: точечная линейка над доком (в альбоме — слева от него).
            Look.NOTHING -> {
                dotRulePaint.strokeWidth = 2.2f * dp
                val step = 9 * dp
                if (landscape) {
                    val x = width - dockW
                    var y = barY + catBig
                    while (y < height - bottomInset - 12 * dp) {
                        canvas.drawPoint(x, y, dotRulePaint)
                        y += step
                    }
                } else {
                    val y = height - bottomInset - dockH
                    var x = 20 * dp
                    while (x < width - 18 * dp) {
                        canvas.drawPoint(x, y, dotRulePaint)
                        x += step
                    }
                }
            }
            // Аврора: стеклянная полка под значками.
            Look.AURORA -> {
                if (landscape) {
                    dst.set(width - dockW + 6 * dp, barY + catBig / 2 + 8 * dp, width - 6 * dp, height - bottomInset - 10 * dp)
                } else {
                    dst.set(14 * dp, height - bottomInset - dockH + 8 * dp, width - 14 * dp, height - bottomInset - 8 * dp)
                }
                glass(canvas, dst, 28 * dp, 1f)
            }
            Look.XMB -> Unit
        }
        val half = dockSize() / 2
        for (i in dock.indices) {
            dockCenter(i, tmp)
            val e = dock[i]
            if (i == pressDock) {
                pressPaint.alpha = 40
                canvas.drawCircle(tmp[0], tmp[1], half + 10 * dp, pressPaint)
            }
            dst.set(tmp[0] - half, tmp[1] - half, tmp[0] + half, tmp[1] + half)
            val icon = e.icon
            if (icon != null) {
                iconPaint.alpha = 255
                canvas.drawBitmap(icon, null, dst, iconPaint)
            } else {
                placeholderPaint.alpha = 50
                canvas.drawRoundRect(dst, half * 0.4f, half * 0.4f, placeholderPaint)
            }
            drawBadge(canvas, Notices.count(e.component?.packageName), 1f)
        }
    }

    private fun dockAt(x: Float, y: Float): Int {
        for (i in dock.indices) {
            dockCenter(i, tmp)
            if (abs(x - tmp[0]) < 34 * dp && abs(y - tmp[1]) < 36 * dp) return i
        }
        return -1
    }

    /** Низ карточки «Сейчас»; 0 — карточки нет (альбом или показывать нечего). */
    private fun cardBottom(): Float {
        if (width > height) return 0f
        val h = (if (NowPlaying.title != null) 64 * dp else 0f) + (if (calendar != null) 40 * dp else 0f)
        return if (h == 0f) 0f else topInset + 18 * dp + h
    }

    /** Приложение плеера — для значка и имени в карточке; ищется только при смене плеера. */
    private fun mediaApp(): AppEntry? {
        val pkg = NowPlaying.pkg
        if (pkg != cardPkg) {
            cardPkg = pkg
            cardApp = columns.asSequence().flatten().firstOrNull { it.special == null && it.component?.packageName == pkg }
        }
        return cardApp
    }

    /**
     * «Сейчас»: что играет (с кнопками) и ближайшее событие календаря — в пустом месте над рядом разделов.
     * Только в портрете; пункты колонки, уезжая вверх, гаснут под ней.
     */
    private fun drawCard(canvas: Canvas) {
        cardRect.setEmpty()
        val bottom = cardBottom()
        if (bottom == 0f) return
        val media = NowPlaying.title
        val event = calendar
        val a = 1f - menuT
        if (a <= 0.02f) return
        val left = 20 * dp
        val right = width - 20 * dp
        var y = topInset + 18 * dp
        cardRect.set(left, y, right, bottom)
        when (look) {
            Look.XMB -> {
                cardPaint.color = Color.WHITE
                cardPaint.alpha = (28 * a).toInt()
                canvas.drawRoundRect(cardRect, 18 * dp, 18 * dp, cardPaint)
            }
            Look.NOTHING -> {
                cardPaint.color = 0xFF151515.toInt()
                cardPaint.alpha = (255 * a).toInt()
                canvas.drawRoundRect(cardRect, 24 * dp, 24 * dp, cardPaint)
            }
            Look.AURORA -> glass(canvas, cardRect, 22 * dp, a)
        }
        if (media != null) {
            val btn = 40 * dp
            val cy = y + 32 * dp
            val glyphs = arrayOf(Glyph.MEDIA_PREV, if (NowPlaying.playing) Glyph.MEDIA_PAUSE else Glyph.MEDIA_PLAY, Glyph.MEDIA_NEXT)
            for (k in 0 until 3) {
                val cx = right - 16 * dp - btn / 2 - (2 - k) * btn
                cardButtons[k].set(cx - btn / 2, cy - btn / 2, cx + btn / 2, cy + btn / 2)
                if (pressCard == k) {
                    pressPaint.alpha = (40 * a).toInt()
                    canvas.drawCircle(cx, cy, btn / 2, pressPaint)
                }
                val g = small(glyphs[k])
                dst.set(cx - 13 * dp, cy - 13 * dp, cx + 13 * dp, cy + 13 * dp)
                iconPaint.alpha = (255 * a).toInt()
                canvas.drawBitmap(g, null, dst, iconPaint)
            }
            val textRight = cardButtons[0].left - 8 * dp
            val app = mediaApp()
            val appIcon = app?.icon
            var tx = left + 18 * dp
            if (appIcon != null) {
                dst.set(tx, cy - 16 * dp, tx + 32 * dp, cy + 16 * dp)
                iconPaint.alpha = (255 * a).toInt()
                canvas.drawBitmap(appIcon, null, dst, iconPaint)
                tx += 44 * dp
            }
            cardTitlePaint.fade((255 * a).toInt())
            canvas.drawText(TextUtils.ellipsize(media, cardTitlePaint, textRight - tx, TextUtils.TruncateAt.END).toString(), tx, cy - 3 * dp, cardTitlePaint)
            val sub = listOfNotNull(NowPlaying.artist?.takeIf { it.isNotBlank() }, app?.label).joinToString(" · ")
            if (sub.isNotEmpty()) {
                cardSubPaint.fade((190 * a).toInt())
                canvas.drawText(TextUtils.ellipsize(sub, cardSubPaint, textRight - tx, TextUtils.TruncateAt.END).toString(), tx, cy + 17 * dp, cardSubPaint)
            }
            y += 64 * dp
        } else {
            for (b in cardButtons) b.setEmpty()
        }
        if (event != null) {
            val cy = y + 20 * dp
            val g = small(Glyph.CALENDAR)
            dst.set(left + 18 * dp, cy - 11 * dp, left + 40 * dp, cy + 11 * dp)
            iconPaint.alpha = (230 * a).toInt()
            canvas.drawBitmap(g, null, dst, iconPaint)
            val tx = left + 52 * dp
            cardSubPaint.fade((235 * a).toInt())
            canvas.drawText(
                TextUtils.ellipsize(event.text(context), cardSubPaint, right - 16 * dp - tx, TextUtils.TruncateAt.END).toString(),
                tx, cy + 5 * dp, cardSubPaint,
            )
        }
    }

    private fun cardAt(x: Float, y: Float): Int {
        if (cardRect.isEmpty || !cardRect.contains(x, y)) return CARD_NONE
        for (k in 0 until 3) if (cardButtons[k].contains(x, y)) return k
        val mediaBottom = cardRect.top + if (NowPlaying.title != null) 64 * dp else 0f
        return if (y < mediaBottom) CARD_MEDIA else CARD_EVENT
    }

    /** Матовое стекло Авроры: полупрозрачная заливка и светлая кромка. */
    private fun glass(canvas: Canvas, r: RectF, radius: Float, a: Float) {
        glassPaint.alpha = (24 * a).toInt()
        canvas.drawRoundRect(r, radius, radius, glassPaint)
        glassEdge.strokeWidth = dp
        glassEdge.alpha = (52 * a).toInt()
        canvas.drawRoundRect(r, radius, radius, glassEdge)
    }

    private fun small(glyph: Glyph): Bitmap =
        smallGlyphs.getOrPut(glyph) { GlyphArt.icon(context, glyph, (26 * dp).roundToInt(), (3 * dp).roundToInt()) }

    private fun drawHints(canvas: Canvas) {
        dimPaint.alpha = 215
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        val x = 32 * dp
        val hints = HINTS.filter { (title, _) ->
            when (title) {
                R.string.hint_key_t -> Edition.keyService(context) != null
                R.string.hint_dock_t -> dock.isNotEmpty()
                else -> true
            }
        }
        var y = topInset + max(48 * dp, (height - topInset - hints.size * 80 * dp - 120 * dp) / 2)
        menuTitlePaint.fade(255)
        canvas.drawText(context.getString(R.string.hints_title), x, y, menuTitlePaint)
        y += 44 * dp
        val w = width - x * 2
        for ((title, text) in hints) {
            menuItemPaint.fade(255)
            canvas.drawText(context.getString(title), x, y, menuItemPaint)
            subPaint.fade(190)
            var rest = context.getString(text)
            var line = y + 20 * dp
            while (rest.isNotEmpty()) {
                var n = subPaint.breakText(rest, true, w, null)
                if (n < rest.length) rest.lastIndexOf(' ', n).let { if (it > 0) n = it }
                canvas.drawText(rest, 0, n, x, line, subPaint)
                rest = rest.substring(n).trimStart()
                line += 18 * dp
            }
            y = line + 24 * dp
        }
        subPaint.fade(150)
        canvas.drawText(context.getString(R.string.hints_close), x, y + 12 * dp, subPaint)
    }

    /** Только в альбоме, где строка состояния скрыта: дата, заряд и время одной строкой в углу. */
    private fun drawClock(canvas: Canvas) {
        if (width <= height) return
        val minute = System.currentTimeMillis() / 60_000
        if (minute != clockMinute) {
            clockMinute = minute
            val now = Date()
            lineText = shortDateFormat.format(now) + batteryText + "   " + timeFormat.format(now)
        }
        canvas.drawText(lineText, width - (dockW - dockSize()) / 2, clockBase, lineClockPaint)
    }

    private fun drawIndexBubble(canvas: Canvas) {
        val list = columns[dragCol]
        val letter = list.getOrNull(indexItem)?.label?.firstOrNull()?.uppercaseChar() ?: return
        val top = barY + 60 * dp
        val bottom = height - bottomInset - 24 * dp
        val y = top + (bottom - top) * indexItem / max(1, list.lastIndex)
        val cx = width - 84 * dp
        bubblePaint.alpha = 200
        dst.set(cx - 30 * dp, y - 30 * dp, cx + 30 * dp, y + 30 * dp)
        canvas.drawRoundRect(dst, 14 * dp, 14 * dp, bubblePaint)
        bubbleTextPaint.fade(255)
        canvas.drawText(letter.toString(), cx, y + bubbleCenter, bubbleTextPaint)
    }

    private fun glyph(glyph: Glyph): Bitmap =
        glyphs.getOrPut(glyph) {
            if (look == Look.NOTHING) GlyphArt.dots(context, glyph, catBase.roundToInt(), catPad)
            else GlyphArt.icon(context, glyph, catBase.roundToInt(), catPad)
        }

    private fun glow(glyph: Glyph): Bitmap =
        glows.getOrPut(glyph) { GlyphArt.glow(context, glyph, catBase.roundToInt(), catPad) }

    private fun menuLeft(t: Float): Float {
        val e = 1f - (1f - t) * (1f - t) * (1f - t)
        return width - width * MENU_WIDTH * e
    }

    /** Длинное меню (темы) начинается выше ряда разделов и уплотняется, чтобы влезть до низа экрана. */
    private fun menuTop(): Float {
        val avail = height - bottomInset - 16 * dp
        return min(barY - 4 * dp, avail - menuItems.size * menuRowH).coerceAtLeast(topInset + 72 * dp)
    }

    private fun layoutMenu() {
        val avail = height - bottomInset - 16 * dp - (topInset + 72 * dp)
        menuRowH = min(54 * dp, avail / max(1, menuItems.size))
    }

    /** Открыть меню вариантов (тема, фон, значки) — то же боковое меню, что у долгого тапа. */
    fun showMenu(title: String, items: List<MenuItem>) {
        menuItems = items
        layoutMenu()
        for (item in items) {
            val lead = (if (item.swatch != null) 36 * dp else 0f) + (if (item.icon != null) 40 * dp else 0f)
            val w = width * MENU_WIDTH - 24 * dp - lead - 44 * dp
            item.shown = TextUtils.ellipsize(item.label, menuItemPaint, w, TextUtils.TruncateAt.END).toString()
        }
        menuTitle = TextUtils.ellipsize(title, menuTitlePaint, width * MENU_WIDTH - 48 * dp, TextUtils.TruncateAt.END).toString()
        menuOpen = true
        invalidate()
    }

    private fun drawMenu(canvas: Canvas) {
        dimPaint.alpha = (70 * menuT).toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        val left = menuLeft(menuT)
        menuPaint.alpha = (215 * menuT).toInt()
        canvas.drawRect(left, 0f, width.toFloat(), height.toFloat(), menuPaint)
        menuEdgePaint.alpha = (110 * menuT).toInt()
        canvas.drawRect(left, 0f, left + 1.5f * dp, height.toFloat(), menuEdgePaint)
        val tx = left + 24 * dp
        val top = menuTop()
        menuTitlePaint.fade((255 * menuT).toInt())
        canvas.drawText(menuTitle, tx, top - 30 * dp, menuTitlePaint)
        menuItemPaint.fade((255 * menuT).toInt())
        for (i in menuItems.indices) {
            val item = menuItems[i]
            val cy = top + i * menuRowH + menuRowH / 2
            var x = tx
            val sw = item.swatch
            if (sw != null) {
                val sh = item.shader ?: LinearGradient(0f, -12 * dp, 0f, 12 * dp, sw[0] or OPAQUE, sw[1] or OPAQUE, Shader.TileMode.CLAMP)
                    .also { item.shader = it }
                swatchPaint.shader = sh
                swatchPaint.alpha = (255 * menuT).toInt()
                canvas.save()
                canvas.translate(0f, cy)
                dst.set(x, -12 * dp, x + 24 * dp, 12 * dp)
                canvas.drawRoundRect(dst, 6 * dp, 6 * dp, swatchPaint)
                canvas.restore()
                x += 36 * dp
            }
            val ic = item.icon
            if (ic != null) {
                iconPaint.alpha = (255 * menuT).toInt()
                dst.set(x, cy - 14 * dp, x + 28 * dp, cy + 14 * dp)
                canvas.drawBitmap(ic, null, dst, iconPaint)
                x += 40 * dp
            }
            canvas.drawText(item.shown, x, cy + menuCenter, menuItemPaint)
            if (item.checked) canvas.drawText("✓", width - 28 * dp, cy + menuCenter, menuItemPaint)
        }
    }

    // ---------- касания ----------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        lastTouch = System.nanoTime()
        if (e.actionMasked == MotionEvent.ACTION_DOWN) BgBus.touch()
        if (hintsOpen) {
            if (e.actionMasked == MotionEvent.ACTION_UP) hintsOpen = false
            invalidate()
            return true
        }
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
        pressCard = cardAt(e.x, e.y)
        pressDock = if (pressCard == CARD_NONE) dockAt(e.x, e.y) else -1
        if (pressCard != CARD_NONE || pressDock >= 0) {
            pressCat = -1
            pressIndex = -1
            if (pressDock >= 0) postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            return
        }
        pressCat = hitCategory(e.x, e.y)
        if (pressCat < 0 && inIndexStrip(e.x, e.y)) {
            drag = Drag.INDEX
            indexItem = -1
            indexTo(e.y)
            return
        }
        pressIndex = if (pressCat < 0 && !stoppedFling) hitItem(dragCol, e.x, e.y) else -1
        pressCol = dragCol
        if (pressIndex >= 0) postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
    }

    /** Полоса быстрой прокрутки: правый край под рядом разделов, только у колонок длиннее экрана. */
    private fun inIndexStrip(x: Float, y: Float): Boolean =
        x > width - INDEX_STRIP * dp && y > barY + 60 * dp && columns[dragCol].size > INDEX_MIN

    private fun indexTo(y: Float) {
        val list = columns[dragCol]
        val top = barY + 60 * dp
        val bottom = height - bottomInset - 24 * dp
        val f = ((y - top) / (bottom - top)).coerceIn(0f, 1f)
        val i = (f * list.lastIndex).roundToInt()
        if (i == indexItem) return
        val letter = list.getOrNull(indexItem)?.label?.firstOrNull()?.uppercaseChar()
        indexItem = i
        posTarget[dragCol] = i
        if (list[i].label.firstOrNull()?.uppercaseChar() != letter) performHapticFeedback(Haptics.SECTION)
    }

    private fun move(e: MotionEvent) {
        velocity?.addMovement(e)
        if (drag == Drag.PENDING) {
            val dx = e.x - downX
            val dy = e.y - downY
            if (hypot(dx, dy) <= slop) return
            removeCallbacks(longPress)
            pressIndex = -1
            pressDock = -1
            pressCard = CARD_NONE
            val onBar = pressCat >= 0
            pressCat = -1
            drag = if (abs(dx) > abs(dy)) (if (onBar) Drag.SCRUB else Drag.H) else Drag.V
            downX = e.x
            downY = e.y
            dragStartCat = catPos
            dragStartPos = pos[dragCol]
            posVel[dragCol] = 0f
        }
        when (drag) {
            Drag.SCRUB -> {
                val p = dragStartCat - (e.x - downX) / catSpacing
                val maxP = (count - 1).toFloat()
                catPos = when {
                    p < 0f -> p * RUBBER
                    p > maxP -> maxP + (p - maxP) * RUBBER
                    else -> p
                }
                catVel = 0f
            }
            Drag.INDEX -> indexTo(e.y)
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
            Drag.SCRUB -> {
                catVel = (-vx / catSpacing).coerceIn(-10f, 10f)
                setCategory((catPos + catVel * 0.12f).roundToInt())
            }
            Drag.MENU -> menuTap(e.x, e.y)
            Drag.INDEX, Drag.NONE -> Unit
        }
        end()
    }

    private fun end() {
        removeCallbacks(longPress)
        velocity?.recycle()
        velocity = null
        drag = Drag.NONE
        pressCat = -1
        pressCard = CARD_NONE
        if (!launched) {
            pressIndex = -1
            pressDock = -1
        }
    }

    private fun tap() {
        if (pressCard != CARD_NONE) {
            performHapticFeedback(Haptics.TAP)
            when (pressCard) {
                CARD_PREV -> NowPlaying.previous()
                CARD_TOGGLE -> NowPlaying.toggle()
                CARD_NEXT -> NowPlaying.next()
                CARD_MEDIA -> NowPlaying.open(context)
                CARD_EVENT -> calendar?.let(onOpenEvent)
            }
            return
        }
        val d = dock.getOrNull(pressDock)
        if (d != null) {
            dockCenter(pressDock, tmp)
            val half = (dockSize() / 2).toInt()
            launchRect.set(tmp[0].toInt() - half, tmp[1].toInt() - half, tmp[0].toInt() + half, tmp[1].toInt() + half)
            launched = true
            performHapticFeedback(Haptics.LAUNCH)
            onLaunch(d, launchRect)
            return
        }
        if (stoppedFling) return
        if (pressCat >= 0) {
            if (pressCat == category && abs(catPos - category) < 0.01f) {
                posTarget[category] = 0
            } else {
                setCategory(pressCat)
            }
            return
        }
        val entry = columns.getOrNull(pressCol)?.getOrNull(pressIndex)
        if (entry == null) {
            val now = System.nanoTime()
            if (now - lastEmptyTap < DOUBLE_TAP_NS && hypot(downX - emptyTapX, downY - emptyTapY) < 48 * dp) {
                lastEmptyTap = 0L
                onSearch()
            } else {
                lastEmptyTap = now
                emptyTapX = downX
                emptyTapY = downY
            }
            return
        }
        val y = itemY(pressIndex - pos[pressCol]).toInt()
        val half = (iconDraw / 2).toInt()
        launchRect.set(focusX.toInt() - half, y - half, focusX.toInt() + half, y + half)
        launched = true
        performHapticFeedback(Haptics.LAUNCH)
        onLaunch(entry, launchRect)
    }

    private fun openMenuForPress() {
        val entry = dock.getOrNull(pressDock) ?: columns.getOrNull(pressCol)?.getOrNull(pressIndex) ?: return
        pressDock = -1
        val items = onMenu(entry)
        drag = Drag.NONE
        pressIndex = -1
        if (items.isEmpty()) return
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        showMenu(entry.label, items)
    }

    private fun menuTap(x: Float, y: Float) {
        if (hypot(x - downX, y - downY) > slop) return
        if (x < menuLeft(1f)) {
            closeMenu()
            return
        }
        val i = floor((y - menuTop()) / menuRowH).toInt()
        val item = menuItems.getOrNull(i) ?: return
        performHapticFeedback(Haptics.TAP)
        if (!item.keepOpen) closeMenu()
        item.action()
    }

    private fun hitCategory(x: Float, y: Float): Int {
        if (y < barY - 40 * dp || y > barY + 60 * dp || x > width - dockW) return -1
        val i = (catPos + (x - focusX) / catSpacing).roundToInt()
        return if (i in 0 until count) i else -1
    }

    /** Пункт под пальцем: ближайший по центру среди видимых; в альбоме — только по значку с подписью. */
    private fun hitItem(col: Int, x: Float, y: Float): Int {
        val list = columns[col]
        val narrow = width > height
        if (list.isEmpty() || (narrow && x < focusX - iconDraw / 2 - 16 * dp)) return -1
        val p = pos[col]
        val first = max(0, floor(p).toInt() - ABOVE_ROWS)
        val last = min(list.lastIndex, (p + (height - selY) / itemH).toInt() + 1)
        var best = -1
        var bestDist = itemH / 2
        for (i in first..last) {
            val iy = itemY(i - p)
            val dist = abs(iy - y)
            if (dist < bestDist && edgeAlpha(iy) > 0.3f && (!narrow || x <= textX + list[i].shownW + 24 * dp) &&
                y < height - bottomInset - dockH && x < width - dockW
            ) {
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
        const val OPAQUE = -0x1000000
        const val INDEX_STRIP = 28
        const val INDEX_MIN = 8
        const val STATUS_GAP = 8
        const val ROW_TOP = 0.42f
        const val DOCK_SIZE = 5
        const val FREEZE_NS = BgBus.FREEZE_MS * 1_000_000L
        const val CARD_NONE = -1
        const val CARD_PREV = 0
        const val CARD_TOGGLE = 1
        const val CARD_NEXT = 2
        const val CARD_MEDIA = 3
        const val CARD_EVENT = 4
        const val DOUBLE_TAP_NS = 320_000_000L

        // Подсказки: заголовок и пояснение (строковые ресурсы); кнопка и док — только где они есть.
        val HINTS = listOf(
            R.string.hint_swipe_t to R.string.hint_swipe,
            R.string.hint_double_t to R.string.hint_double,
            R.string.hint_edge_t to R.string.hint_edge,
            R.string.hint_long_t to R.string.hint_long,
            R.string.hint_key_t to R.string.hint_key,
            R.string.hint_dock_t to R.string.hint_dock,
            R.string.hint_settings_t to R.string.hint_settings,
        )
    }
}
