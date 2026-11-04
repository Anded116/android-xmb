package dev.anded.xmb

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Просмотр фото на весь экран (LNC-A0012): листание свайпом и ←/→ (L1/R1), масштаб щипком, двойным тапом и ↑/↓,
 * закрытие свайпом вниз и «назад» (B). Тап прячет подпись. Снимки декодируются в фоне под размер экрана,
 * соседние — заранее; в памяти только текущий и два соседа.
 */
@SuppressLint("ViewConstructor")
class PhotoViewer(context: Context) : View(context) {

    var open = false
        private set

    private var shots: List<Shot> = emptyList()
    private var index = 0
    private val bitmaps = HashMap<Int, Bitmap>()
    private val pending = HashSet<Int>()
    private val decoder = Executors.newSingleThreadExecutor { Thread(it, "xmb-photo") }
    // Поколение показа: результаты декодирования от прошлого открытия выбрасываются.
    private var gen = 0

    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var slide = 0f
    private var drop = 0f
    private var shown = 0f
    private var caption = true
    private var anim: ValueAnimator? = null

    // Жест одним пальцем без масштаба: PAGE — листание, DROP — вниз/вверх, закрыть.
    private var mode = NONE
    private var flingX = 0f
    private var flingY = 0f

    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()
    private val textPaint = ShadowTextPaint(dp).apply {
        color = Color.WHITE
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val dateFormat = SimpleDateFormat(DateFormat.getBestDateTimePattern(Locale.getDefault(), "d MMMM yyyy HH:mm"), Locale.getDefault())

    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomBy(d.scaleFactor, d.focusX, d.focusY)
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            anim?.cancel()
            flingX = 0f
            flingY = 0f
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            caption = !caption
            invalidate()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            zoomTo(if (zoom > 1.01f) 1f else DOUBLE_TAP_ZOOM, e.x, e.y)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scale.isInProgress || e2.pointerCount > 1) return true
            if (zoom > 1.01f) {
                panX -= dx
                panY -= dy
                clampPan()
            } else {
                if (mode == NONE) mode = if (abs(dx) >= abs(dy)) PAGE else DROP
                if (mode == PAGE) {
                    val edge = (index == 0 && slide - dx > 0) || (index == shots.lastIndex && slide - dx < 0)
                    slide -= if (edge) dx * RUBBER else dx
                } else {
                    drop -= dy
                }
            }
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            flingX = vx
            flingY = vy
            return true
        }
    })

    init {
        visibility = GONE
    }

    fun show(list: List<Shot>, at: Int) {
        if (list.isEmpty()) return
        gen++
        shots = list
        index = at.coerceIn(0, list.lastIndex)
        bitmaps.clear()
        pending.clear()
        zoom = 1f
        panX = 0f
        panY = 0f
        slide = 0f
        drop = 0f
        caption = true
        open = true
        visibility = VISIBLE
        preload()
        animate(shown, 1f, FADE_MS) { shown = it }
    }

    fun close() {
        if (!open) return
        open = false
        val from = drop
        animate(shown, 0f, FADE_MS, done = ::hideNow) {
            shown = it
            drop = from * (2f - it)
        }
    }

    fun hideNow() {
        anim?.cancel()
        open = false
        shown = 0f
        gen++
        bitmaps.clear()
        pending.clear()
        visibility = GONE
    }

    /** Клавиши, пока просмотр открыт; B не перехватывается — система превращает её в «назад». */
    fun onKey(e: KeyEvent): Boolean {
        if (!open) return false
        val code = e.keyCode
        if (code !in KEYS) return false
        if (e.action != KeyEvent.ACTION_DOWN) return true
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BUTTON_L1 -> go(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BUTTON_R1 -> go(1)
            KeyEvent.KEYCODE_DPAD_UP -> zoomTo(min(zoom * KEY_ZOOM, MAX_ZOOM), width / 2f, height / 2f)
            KeyEvent.KEYCODE_DPAD_DOWN -> zoomTo(max(zoom / KEY_ZOOM, 1f), width / 2f, height / 2f)
            else -> {
                caption = !caption
                invalidate()
            }
        }
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!open) return false
        scale.onTouchEvent(e)
        gestures.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) release()
        return true
    }

    private fun release() {
        when (mode) {
            PAGE -> when {
                slide < -width * PAGE_SHARE || flingX < -FLING -> go(1)
                slide > width * PAGE_SHARE || flingX > FLING -> go(-1)
                else -> animate(slide, 0f, SLIDE_MS) { slide = it }
            }
            DROP -> if (abs(drop) > height * DROP_SHARE || abs(flingY) > FLING) close() else animate(drop, 0f, SLIDE_MS) { drop = it }
        }
        mode = NONE
    }

    /** На соседний снимок: текущий уезжает, сосед въезжает; у края — назад на место. */
    private fun go(d: Int) {
        val next = index + d
        if (next !in shots.indices) {
            animate(slide, 0f, SLIDE_MS) { slide = it }
            return
        }
        performHapticFeedback(Haptics.ITEM)
        animate(slide, -d * (width + GAP * dp), SLIDE_MS, done = {
            index = next
            slide = 0f
            zoom = 1f
            panX = 0f
            panY = 0f
            preload()
        }) { slide = it }
    }

    private fun zoomBy(f: Float, fx: Float, fy: Float) {
        val nz = (zoom * f).coerceIn(1f, MAX_ZOOM)
        val k = nz / zoom
        // Точка под пальцами остаётся на месте.
        panX = (fx - width / 2f) - (fx - width / 2f - panX) * k
        panY = (fy - height / 2f) - (fy - height / 2f - panY) * k
        zoom = nz
        clampPan()
        invalidate()
    }

    private fun zoomTo(target: Float, fx: Float, fy: Float) {
        animate(zoom, target, SLIDE_MS) { zoomBy(it / zoom, fx, fy) }
    }

    private fun clampPan() {
        val b = bitmaps[index] ?: return
        val s = fit(b) * zoom
        val mx = max(0f, (b.width * s - width) / 2)
        val my = max(0f, (b.height * s - height) / 2)
        panX = panX.coerceIn(-mx, mx)
        panY = panY.coerceIn(-my, my)
    }

    private fun fit(b: Bitmap): Float = min(width / b.width.toFloat(), height / b.height.toFloat())

    private fun animate(from: Float, to: Float, ms: Long, done: (() -> Unit)? = null, set: (Float) -> Unit) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(from, to).apply {
            duration = ms
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                set(it.animatedValue as Float)
                invalidate()
            }
            if (done != null) addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }
                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) done()
                    invalidate()
                }
            })
            start()
        }
    }

    private fun preload() {
        bitmaps.keys.retainAll(index - 1..index + 1)
        load(index)
        load(index + 1)
        load(index - 1)
    }

    private fun load(i: Int) {
        if (i !in shots.indices || i in bitmaps || i in pending) return
        pending += i
        val g = gen
        val uri = shots[i].uri
        val m = resources.displayMetrics
        val limit = max(m.widthPixels, m.heightPixels) * DECODE_SCALE
        decoder.execute {
            val bmp = runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
                    val f = min(1f, limit / max(info.size.width, info.size.height))
                    if (f < 1f) d.setTargetSize((info.size.width * f).roundToInt().coerceAtLeast(1), (info.size.height * f).roundToInt().coerceAtLeast(1))
                }
            }.getOrNull()
            post {
                if (g != gen) return@post
                pending -= i
                if (bmp != null && i in index - 1..index + 1) bitmaps[i] = bmp
                invalidate()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (shown <= 0f) return
        val back = 1f - min(1f, abs(drop) / height) * 0.7f
        canvas.drawColor(Color.argb((255 * shown * back).roundToInt(), 0, 0, 0))
        paint.alpha = (255 * shown).roundToInt()
        for (d in -1..1) {
            val b = bitmaps[index + d] ?: continue
            val z = if (d == 0) zoom else 1f
            val s = fit(b) * z
            val cx = width / 2f + slide + d * (width + GAP * dp) + if (d == 0) panX else 0f
            val cy = height / 2f + drop + if (d == 0) panY else 0f
            dst.set(cx - b.width * s / 2, cy - b.height * s / 2, cx + b.width * s / 2, cy + b.height * s / 2)
            canvas.drawBitmap(b, null, dst, paint)
        }
        val shot = shots.getOrNull(index) ?: return
        if (!caption || drop != 0f) return
        val top = (rootWindowInsets?.getInsets(WindowInsets.Type.systemBars())?.top ?: 0) + 28 * dp
        textPaint.fade((255 * shown).roundToInt())
        canvas.drawText("${index + 1} / ${shots.size}   ${dateFormat.format(Date(shot.date))}", 20 * dp, top, textPaint)
    }

    private companion object {
        const val NONE = 0
        const val PAGE = 1
        const val DROP = 2
        const val GAP = 24
        const val RUBBER = 0.3f
        const val PAGE_SHARE = 0.25f
        const val DROP_SHARE = 0.18f
        const val FLING = 1800f
        const val MAX_ZOOM = 6f
        const val DOUBLE_TAP_ZOOM = 2.5f
        const val KEY_ZOOM = 1.6f
        const val DECODE_SCALE = 1.25f
        const val FADE_MS = 180L
        const val SLIDE_MS = 220L
        val KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_BUTTON_A,
        )
    }
}
