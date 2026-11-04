package dev.anded.xmb

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Поиск по удержанию кнопки Essential: поле прямо над клавиатурой — до него дотягивается большой палец,
 * под ним ряд из 4 иконок (последние приложения или лучшие совпадения, как на iOS) и строки результатов.
 * Если листать результаты, клавиатура прячется, а поле уезжает вверх вместе со списком.
 */
@SuppressLint("ViewConstructor")
class SearchLayer(context: Context, private val iconPx: Int) : FrameLayout(context) {
    private val emptyText = context.getString(R.string.search_empty)

    var onLaunch: (AppEntry, Rect) -> Unit = { _, _ -> }

    var entries: List<AppEntry> = emptyList()
        set(value) {
            field = value
            if (open) update()
        }

    /** Чем добрать ряд без запроса, если недавних меньше, чем мест. */
    var fallback: List<AppEntry> = emptyList()

    private val dp = resources.displayMetrics.density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val margin = 16 * dp
    private val fieldH = 52 * dp
    private val gap = 10 * dp
    private val cellIcon = 56 * dp
    private val iconsH = 96 * dp
    private val rowH = 56 * dp
    private val rowIcon = 42 * dp

    private val field = EditText(context)
    private var icons: List<AppEntry> = emptyList()
    private var rows: List<AppEntry> = emptyList()
    private var iconLabels: List<String> = emptyList()
    private var rowLabels: List<String> = emptyList()

    var open = false
        private set
    private var openT = 0f
    private var lastFrame = 0L
    private var imeInset = 0
    private var navInset = 0
    private var topInset = 0

    // Пока клавиатура открыта, блок стоит на ней; как только начали листать — место поля замирает.
    private var frozenTop = -1f
    private var scroll = 0f
    private val scroller = OverScroller(context)

    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var dragStart = 0f
    private var pressIcon = -1
    private var pressRow = -1
    private var velocity: VelocityTracker? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private val dimPaint = Paint().apply { color = Color.BLACK }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2EFFFFFF }
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val iconPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
    private val cellPaint = textPaint(12.5f).apply { textAlign = Paint.Align.CENTER }
    private val rowPaint = textPaint(17f)
    private val emptyPaint = textPaint(15f).apply { fade(150) }
    private val rowCenter = -(rowPaint.fontMetrics.ascent + rowPaint.fontMetrics.descent) / 2f
    private val glassSize = (22 * dp).roundToInt()
    private val glass: Bitmap = GlyphArt.icon(context, Glyph.SEARCH, glassSize, 0)
    private val dst = RectF()
    private val launchRect = Rect()

    init {
        setWillNotDraw(false)
        visibility = GONE
        field.apply {
            background = null
            setTextColor(Color.WHITE)
            setHintTextColor(0x99FFFFFF.toInt())
            hint = context.getString(R.string.search_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            isSingleLine = true
            gravity = Gravity.CENTER_VERTICAL
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setPadding(0, 0, 0, 0)
            textCursorDrawable = GradientDrawable().apply {
                setColor(Color.WHITE)
                setSize((2 * dp).roundToInt(), 0)
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    scroll = 0f
                    update()
                }
            })
            setOnEditorActionListener { _, _, _ ->
                launchFirst()
                true
            }
            setOnFocusChangeListener { _, has -> if (has) unfreeze() }
            setOnClickListener { unfreeze() }
        }
        addView(field, LayoutParams(LayoutParams.MATCH_PARENT, fieldH.roundToInt()).apply {
            leftMargin = (margin + 16 * dp + glassSize + 10 * dp).roundToInt()
            rightMargin = (margin + 14 * dp).roundToInt()
        })

        setOnApplyWindowInsetsListener { _, insets ->
            readInsets(insets)
            insets
        }
        setWindowInsetsAnimationCallback(object : WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
            override fun onProgress(insets: WindowInsets, running: MutableList<WindowInsetsAnimation>): WindowInsets {
                readInsets(insets)
                return insets
            }
        })
    }

    private fun textPaint(sizeSp: Float) = ShadowTextPaint(dp).apply {
        textSize = sp(sizeSp)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        color = Color.WHITE
    }

    private fun readInsets(insets: WindowInsets) {
        imeInset = insets.getInsets(WindowInsets.Type.ime()).bottom
        val bars = insets.getInsets(WindowInsets.Type.systemBars())
        navInset = bars.bottom
        topInset = bars.top
        placeField()
        invalidate()
    }

    /** Первый результат: «Ввод» на клавиатуре, A на геймпаде. */
    fun launchFirst() {
        (icons.firstOrNull() ?: rows.firstOrNull())?.let { launch(it, cellRect(0)) }
    }

    fun show() {
        if (open) return
        open = true
        visibility = VISIBLE
        frozenTop = -1f
        scroll = 0f
        field.setText("")
        update()
        lastFrame = System.nanoTime()
        FrameRate.vote(this, true)
        field.requestFocus()
        field.post { showKeyboard() }
        invalidate()
    }

    /** Закрыть с анимацией; false — поиск и так закрыт. */
    fun hide(): Boolean {
        if (!open) return false
        open = false
        hideKeyboard()
        field.clearFocus()
        FrameRate.vote(this, false)
        lastFrame = System.nanoTime()
        invalidate()
        return true
    }

    /** Мгновенно, без анимации: приложение уже запущено, лаунчер ушёл с экрана. */
    fun hideNow() {
        if (open) hide()
        openT = 0f
        visibility = GONE
    }

    /**
     * Открыли поверх приложения — окно лаунчера ещё без фокуса, и showSoftInput молча отклоняется.
     * Контроллер вставок дожидается фокуса сам; на всякий случай — ещё раз, когда фокус придёт.
     */
    private fun showKeyboard() {
        if (!open) return
        field.requestFocus()
        val controller = field.windowInsetsController
        if (controller != null) {
            controller.show(WindowInsets.Type.ime())
        } else {
            context.getSystemService(InputMethodManager::class.java)?.showSoftInput(field, 0)
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus && open && frozenTop < 0) showKeyboard()
    }

    private fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
    }

    private fun unfreeze() {
        if (frozenTop < 0 && scroll == 0f) return
        frozenTop = -1f
        scroller.forceFinished(true)
        scroll = 0f
        showKeyboard()
        placeField()
        invalidate()
    }

    private fun update() {
        val q = field.text?.toString().orEmpty().trim()
        val n = iconCount()
        if (q.isEmpty()) {
            // Пока история короткая, добираем приложениями текущего раздела XMB.
            val recent = (AppSearch.recent(entries, n + RECENT_ROWS) + fallback.filter { it.special == null })
                .distinctBy { it.key }.take(n + RECENT_ROWS)
            icons = recent.take(n)
            rows = recent.drop(n)
        } else {
            val hits = AppSearch.search(entries, q)
            icons = hits.take(n)
            rows = hits.drop(n).take(MAX_ROWS)
        }
        val cw = cellW() - 8 * dp
        iconLabels = icons.map { TextUtils.ellipsize(it.label, cellPaint, cw, TextUtils.TruncateAt.END).toString() }
        val tw = rowW() - (12 * dp + rowIcon + 16 * dp) - 12 * dp
        rowLabels = rows.map {
            val text = if (it.shortcut != null) "${it.label} · ${it.subtitle}" else it.label
            TextUtils.ellipsize(text, rowPaint, tw, TextUtils.TruncateAt.END).toString()
        }
        invalidate()
    }

    // ---------- раскладка ----------
    // Портрет: блок на всю ширину стоит на клавиатуре. Альбом: панель слева (справа — место плавающей
    // клавиатуре), поле сверху, 5 иконок в ряд, строки результатов в две колонки.

    private fun landscape() = width > height

    private fun panelLeft() = margin

    private fun panelRight() = if (landscape()) min(width * 0.55f, margin + 560 * dp) else width - margin

    private fun iconCount() = if (landscape()) 5 else 4

    private fun rowCols() = if (landscape()) 2 else 1

    private fun cellW() = (panelRight() - panelLeft()) / iconCount()

    /** Место иконки в ряду: в портрете справа налево — самое свежее справа, под большим пальцем. Обратима сама себе. */
    private fun slot(i: Int) = if (landscape()) i else iconCount() - 1 - i

    private fun rowW() = (panelRight() - panelLeft()) / rowCols()

    private fun rowLeft(i: Int) = panelLeft() + (i % rowCols()) * rowW()

    private fun rowCy(i: Int) = rowsTop() + (i / rowCols()) * rowH + rowH / 2

    /**
     * Верх клавиатуры. Блок держится на её самой высокой отметке, даже когда она спрятана: иначе без клавиатуры
     * поиск съезжал вниз, а он и так у пальца. Отметка своя у портрета и альбома, переживает закрытие поиска.
     */
    private fun keyboardTop(): Float {
        if (imeInset > 0) {
            if (landscape()) imeLand = max(imeLand, imeInset) else imePort = max(imePort, imeInset)
        }
        val seen = if (landscape()) imeLand else imePort
        return height - max(max(imeInset, seen), navInset).toFloat()
    }

    private var imePort = 0
    private var imeLand = 0

    /** Где кончается видимое сейчас: для попадания по строкам и предела прокрутки. */
    private fun visibleBottom(): Float = height - max(imeInset, navInset).toFloat()

    /** Верх поля без прокрутки: блок «поле + иконки + 3 строки» стоит на клавиатуре; в альбоме — без строк. */
    private fun baseTop(): Float {
        if (frozenTop >= 0) return frozenTop
        if (landscape()) return topInset + 14 * dp
        val block = fieldH + gap + iconsH + VISIBLE_ROWS * rowH
        // Блок заходит на клавиатуру на полвысоты поля: последняя строка уходит под неё, поле — ниже, к пальцу.
        return max(topInset + 16 * dp, keyboardTop() - 8 * dp - block + fieldH / 2)
    }

    private fun fieldTop(): Float = baseTop() - scroll + (1f - ease(openT)) * 40 * dp

    private fun iconsTop() = fieldTop() + fieldH + gap

    private fun rowsTop() = iconsTop() + iconsH

    private fun rowTextX(i: Int) = rowLeft(i) + 12 * dp + rowIcon + 16 * dp

    private fun maxScroll(): Float {
        val lines = (rows.size + rowCols() - 1) / rowCols()
        val bottom = baseTop() + fieldH + gap + iconsH + lines * rowH
        return max(0f, bottom - (visibleBottom() - 8 * dp))
    }

    private fun cellRect(i: Int): Rect {
        val cx = panelLeft() + cellW() * (slot(i) + 0.5f)
        val cy = iconsTop() + cellIcon / 2 + 4 * dp
        launchRect.set((cx - cellIcon / 2).toInt(), (cy - cellIcon / 2).toInt(), (cx + cellIcon / 2).toInt(), (cy + cellIcon / 2).toInt())
        return launchRect
    }

    private fun rowRect(i: Int): Rect {
        val cx = rowLeft(i) + 12 * dp + rowIcon / 2
        val cy = rowCy(i)
        launchRect.set((cx - rowIcon / 2).toInt(), (cy - rowIcon / 2).toInt(), (cx + rowIcon / 2).toInt(), (cy + rowIcon / 2).toInt())
        return launchRect
    }

    private fun placeField() {
        field.translationY = fieldTop()
        field.alpha = openT
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // Поле — в пределах панели; менять размеры посреди раскладки нельзя, поэтому на следующем шаге.
        post {
            val lp = field.layoutParams as LayoutParams
            lp.leftMargin = (panelLeft() + 16 * dp + glassSize + 10 * dp).roundToInt()
            lp.rightMargin = (width - panelRight() + 14 * dp).roundToInt()
            field.layoutParams = lp
        }
        placeField()
        if (open) update()
    }

    // ---------- кадр ----------

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = ((now - lastFrame) / 1e9f).coerceIn(0f, 0.05f)
        lastFrame = now
        var moving = false
        val target = if (open) 1f else 0f
        if (abs(openT - target) > 0.002f) {
            openT += (target - openT) * (1f - exp(-dt * 16f))
            moving = true
        } else {
            openT = target
            if (!open) {
                visibility = GONE
                return
            }
        }
        if (scroller.computeScrollOffset()) {
            scroll = scroller.currY.toFloat()
            moving = true
        }
        placeField()

        dimPaint.alpha = (225 * openT).toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

        val a = openT
        val ft = fieldTop()
        pillPaint.alpha = (0x2E * a).toInt()
        dst.set(panelLeft(), ft, panelRight(), ft + fieldH)
        canvas.drawRoundRect(dst, fieldH / 2, fieldH / 2, pillPaint)
        iconPaint.alpha = (220 * a).toInt()
        canvas.drawBitmap(glass, panelLeft() + 16 * dp, ft + (fieldH - glassSize) / 2, iconPaint)

        if (icons.isEmpty() && rows.isEmpty() && field.text.isNotEmpty()) {
            emptyPaint.fade((150 * a).toInt())
            canvas.drawText(emptyText, panelLeft() + 8 * dp, iconsTop() + 28 * dp, emptyPaint)
        }
        val cellW = cellW()
        for (i in icons.indices) {
            val cx = panelLeft() + cellW * (slot(i) + 0.5f)
            val top = iconsTop() + 4 * dp
            if (i == pressIcon) {
                pressPaint.alpha = (34 * a).toInt()
                dst.set(cx - cellW / 2 + 4 * dp, top - 4 * dp, cx + cellW / 2 - 4 * dp, top + iconsH - 10 * dp)
                canvas.drawRoundRect(dst, 16 * dp, 16 * dp, pressPaint)
            }
            dst.set(cx - cellIcon / 2, top, cx + cellIcon / 2, top + cellIcon)
            drawIcon(canvas, icons[i].icon, a)
            cellPaint.fade((235 * a).toInt())
            canvas.drawText(iconLabels.getOrElse(i) { "" }, cx, top + cellIcon + 22 * dp, cellPaint)
        }
        val bottom = height.toFloat()
        for (i in rows.indices) {
            val cy = rowCy(i)
            if (cy - rowH > bottom) break
            if (cy + rowH < 0) continue
            val left = rowLeft(i)
            if (i == pressRow) {
                pressPaint.alpha = (34 * a).toInt()
                dst.set(left, cy - rowH / 2 + 3 * dp, left + rowW() - 6 * dp, cy + rowH / 2 - 3 * dp)
                canvas.drawRoundRect(dst, 16 * dp, 16 * dp, pressPaint)
            }
            val cx = left + 12 * dp + rowIcon / 2
            dst.set(cx - rowIcon / 2, cy - rowIcon / 2, cx + rowIcon / 2, cy + rowIcon / 2)
            drawIcon(canvas, rows[i].icon, a)
            rowPaint.fade((255 * a).toInt())
            canvas.drawText(rowLabels.getOrElse(i) { "" }, rowTextX(i), cy + rowCenter, rowPaint)
        }
        if (moving) postInvalidateOnAnimation()
    }

    private fun drawIcon(canvas: Canvas, icon: Bitmap?, a: Float) {
        if (icon != null) {
            iconPaint.alpha = (255 * a).toInt()
            canvas.drawBitmap(icon, null, dst, iconPaint)
        } else {
            placeholderPaint.alpha = (50 * a).toInt()
            canvas.drawRoundRect(dst, dst.width() * 0.2f, dst.width() * 0.2f, placeholderPaint)
        }
    }

    private fun ease(t: Float) = 1f - (1f - t) * (1f - t) * (1f - t)

    // ---------- касания ----------

    /** Касание поля отдаём EditText, пока это не протяжка; всё остальное — себе. */
    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (!open) return false
        val inField = e.y >= fieldTop() && e.y <= fieldTop() + fieldH && e.x <= panelRight()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                beginTouch(e)
                return !inField
            }
            MotionEvent.ACTION_MOVE -> if (abs(e.y - downY) > slop) {
                velocity?.addMovement(e)
                startDrag(e)
                return true
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!open) return false
        velocity?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> beginTouch(e)
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && hypot(e.x - downX, e.y - downY) > slop) startDrag(e)
                if (dragging) {
                    val s = dragStart - (e.y - downY)
                    val maxS = maxScroll()
                    scroll = when {
                        s < 0f -> s * 0.3f
                        s > maxS -> maxS + (s - maxS) * 0.3f
                        else -> s
                    }
                    placeField()
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    velocity?.computeCurrentVelocity(1000)
                    val vy = velocity?.yVelocity ?: 0f
                    val s = scroll.roundToInt()
                    val maxS = maxScroll().roundToInt()
                    if (s < 0 || s > maxS) scroller.springBack(0, s, 0, 0, 0, maxS)
                    else scroller.fling(0, s, 0, (-vy).roundToInt(), 0, 0, 0, maxS, 0, (40 * dp).roundToInt())
                } else {
                    tap(e.x, e.y)
                }
                endTouch()
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> endTouch()
        }
        return true
    }

    private fun beginTouch(e: MotionEvent) {
        velocity?.recycle()
        velocity = VelocityTracker.obtain().also { it.addMovement(e) }
        downX = e.x
        downY = e.y
        dragging = false
        scroller.forceFinished(true)
        pressIcon = hitIcon(e.x, e.y)
        pressRow = if (pressIcon < 0) hitRow(e.x, e.y) else -1
        invalidate()
    }

    /** Начали листать: клавиатура уходит, место поля замирает — дальше поле едет вместе со списком. */
    private fun startDrag(e: MotionEvent) {
        if (dragging) return
        dragging = true
        pressIcon = -1
        pressRow = -1
        if (frozenTop < 0) frozenTop = baseTop()
        hideKeyboard()
        field.clearFocus()
        downY = e.y
        dragStart = scroll
    }

    private fun endTouch() {
        velocity?.recycle()
        velocity = null
        dragging = false
        pressIcon = -1
        pressRow = -1
    }

    private fun tap(x: Float, y: Float) {
        val i = hitIcon(x, y)
        if (i >= 0) {
            launch(icons[i], cellRect(i))
            return
        }
        val r = hitRow(x, y)
        if (r >= 0) {
            launch(rows[r], rowRect(r))
            return
        }
        // Тап мимо: выше поля или правее панели (в альбоме там пусто или плавающая клавиатура).
        if (y < fieldTop() - 8 * dp || x > panelRight() + 8 * dp) hide()
    }

    private fun launch(entry: AppEntry, rect: Rect) {
        performHapticFeedback(Haptics.LAUNCH)
        hideKeyboard()
        onLaunch(entry, Rect(rect))
    }

    private fun hitIcon(x: Float, y: Float): Int {
        val top = iconsTop()
        if (y < top || y > top + iconsH) return -1
        val i = slot(((x - panelLeft()) / cellW()).toInt())
        return if (x >= panelLeft() && x <= panelRight() && i in icons.indices) i else -1
    }

    private fun hitRow(x: Float, y: Float): Int {
        val top = rowsTop()
        if (y < top || y >= visibleBottom() || x < panelLeft() || x > panelRight()) return -1
        val col = ((x - panelLeft()) / rowW()).toInt().coerceIn(0, rowCols() - 1)
        val i = ((y - top) / rowH).toInt() * rowCols() + col
        return if (i in rows.indices) i else -1
    }

    private companion object {
        const val VISIBLE_ROWS = 3
        const val RECENT_ROWS = 8
        const val MAX_ROWS = 30
    }
}
