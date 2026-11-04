package dev.anded.xmb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Значки в стиле XMB: белый силуэт с лёгким градиентом сверху вниз и мягкой тенью,
 * отдельно — размытый ореол для выбранного. Рисуются один раз, в кадре — только готовые Bitmap.
 * Формы — Material Icons (Apache 2.0), res/drawable/xmb_*.xml.
 */
object GlyphArt {

    fun res(glyph: Glyph): Int = when (glyph) {
        Glyph.STAR -> R.drawable.xmb_star
        Glyph.SETTINGS -> R.drawable.xmb_settings
        Glyph.CHAT -> R.drawable.xmb_chat
        Glyph.BANK -> R.drawable.xmb_bank
        Glyph.MAP -> R.drawable.xmb_map
        Glyph.PLAY -> R.drawable.xmb_play
        Glyph.BAG -> R.drawable.xmb_bag
        Glyph.FOOD -> R.drawable.xmb_food
        Glyph.GRID -> R.drawable.xmb_grid
        Glyph.HOME -> R.drawable.xmb_home
        Glyph.KEY -> R.drawable.xmb_key
        Glyph.RESET -> R.drawable.xmb_reset
        Glyph.SEARCH -> R.drawable.xmb_search
        Glyph.THEME -> R.drawable.xmb_theme
        Glyph.WAVE -> R.drawable.xmb_wave
        Glyph.ICONS -> R.drawable.xmb_icons
        Glyph.WALLPAPER -> R.drawable.xmb_wallpaper
        Glyph.MEDIA_PLAY -> R.drawable.xmb_media_play
        Glyph.MEDIA_PAUSE -> R.drawable.xmb_media_pause
        Glyph.MEDIA_NEXT -> R.drawable.xmb_media_next
        Glyph.MEDIA_PREV -> R.drawable.xmb_media_prev
        Glyph.CALENDAR -> R.drawable.xmb_calendar
        Glyph.BELL -> R.drawable.xmb_bell
        Glyph.HELP -> R.drawable.xmb_help
        Glyph.LOOK -> R.drawable.xmb_look
        Glyph.GAMES -> R.drawable.xmb_games
        Glyph.WORK -> R.drawable.xmb_work
        Glyph.TOOLS -> R.drawable.xmb_tools
        Glyph.STATUS -> R.drawable.xmb_status
    }

    /** Значок size×size с полем pad вокруг под тень; центр значка — центр Bitmap. */
    fun icon(context: Context, glyph: Glyph, size: Int, pad: Int): Bitmap =
        styled(context, context.getDrawable(res(glyph))!!.mutate(), size, pad, 1f)

    /**
     * Любой силуэт в стиле XMB — важна только его альфа. layerScale > 1 — рисунок больше поля и обрезается:
     * так монохромный слой адаптивного значка (108 единиц, значимое — в центральных 66) заполняет поле.
     * normalize — силуэт приложения: его рамка подгоняется к общей мере (см. fit), по центру поля.
     */
    fun styled(context: Context, drawable: Drawable, size: Int, pad: Int, layerScale: Float, normalize: Boolean = false): Bitmap {
        val full = size + pad * 2
        val shape = Bitmap.createBitmap(full, full, Bitmap.Config.ARGB_8888)
        val grow = ((layerScale - 1f) * size / 2).roundToInt()
        drawable.setBounds(pad - grow, pad - grow, pad + size + grow, pad + size + grow)
        Canvas(shape).run {
            clipRect(pad, pad, pad + size, pad + size)
            drawable.draw(this)
        }
        if (normalize) fit(shape, drawable, size, pad)
        // Nothing: чистый белый силуэт, без градиента и тени.
        if (XmbSettings.look.flatIcons) {
            val flat = shape.extractAlpha()
            val out = Bitmap.createBitmap(full, full, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(flat, 0f, 0f, Paint().apply { color = TOP })
            shape.recycle()
            flat.recycle()
            return hardware(out)
        }
        val out = Bitmap.createBitmap(full, full, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val dp = context.resources.displayMetrics.density
        // Размытая маска больше исходной: offset — где её левый верхний угол относительно исходной.
        val off = IntArray(2)
        val shadow = shape.extractAlpha(Paint().apply { maskFilter = BlurMaskFilter(2.2f * dp, BlurMaskFilter.Blur.NORMAL) }, off)
        canvas.drawBitmap(shadow, off[0].toFloat(), off[1] + 1.6f * dp, Paint().apply { color = 0x66000000 })
        val layer = Bitmap.createBitmap(full, full, Bitmap.Config.ARGB_8888)
        val lc = Canvas(layer)
        lc.drawBitmap(shape, 0f, 0f, null)
        lc.drawRect(0f, 0f, full.toFloat(), full.toFloat(), Paint().apply {
            shader = LinearGradient(0f, pad.toFloat(), 0f, (pad + size).toFloat(), TOP, BOTTOM, Shader.TileMode.CLAMP)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        })
        canvas.drawBitmap(layer, 0f, 0f, null)
        shape.recycle()
        shadow.recycle()
        layer.recycle()
        return hardware(out)
    }

    /**
     * Цветной значок своей формы, как на старом Android: без подложки, с выравненным размером.
     * Решение — по замерам слоёв адаптивного значка (фон и рисунок по 108 единиц, видимы центральные 72):
     * - рисунок почти пуст (весь значок картинкой в слое фона — Chrome, FBReader) — оба слоя, обрезанные
     *   по силуэту; силуэта нет — знак мелкий на сплошной подложке (Диктофон), и значок нужен целиком: null;
     * - есть монохромный силуэт и рисунок внутри него цветной (Карты, Госуслуги, Google Play) — рисунок,
     *   обрезанный по силуэту: так уходит белая плашка или круг, нарисованные прямо в рисунке;
     * - круг в рисунке, а внутри силуэта белый знак (Flud) — силуэт главным цветом рисунка;
     * - круг в рисунке и силуэта нет — свой силуэт [own] цветом рисунка (AliExpress), а нет и его — рисунок
     *   как есть (Chrome, FBReader: Android завернул старый значок целиком в слой рисунка);
     * - иначе рисунок как есть, белый знак на насыщенной подложке средней яркости (Альфа, Discord) —
     *   цветом подложки; на светлой (Telegram) и тёмной (Steam) подложке знак остаётся своим, белым.
     * Тёмный знак на чёрном фоне XMB не виден — тогда цвет насыщенной подложки (Яндекс Go — жёлтый), иначе белый;
     * чёрный вперемешку с белым (Nothing X) — null: такому знаку нужна его светлая подложка; чёрные части
     * цветного знака (Диктофон) — белые.
     */
    fun colored(context: Context, icon: AdaptiveIconDrawable, size: Int, pad: Int, own: Drawable?): Bitmap? {
        val fg = icon.foreground ?: return null
        val bg = icon.background
        val mono = icon.monochrome
        val full = size + pad * 2
        val field = size.toFloat() * size
        val grow = size / 4
        val base = Rect(pad - grow, pad - grow, pad + size + grow, pad + size + grow)
        val drawn = render(full, pad, size, fg, base)
        val all = Stats(drawn)
        var art: Drawable = fg
        var mask: Drawable? = null
        var color: Int? = null
        var probe = all
        if (all.opaque < field * TINY && bg != null) {
            drawn.recycle()
            // Рисунок почти пуст: знак — мелкая деталь на подложке (Диктофон) или весь значок лежит картинкой
            // в слое фона (Chrome, FBReader). Есть силуэт — значок целиком, обрезанный по нему; нет —
            // подложка с прозрачными краями и есть знак, а сплошная нужна мелкому знаку целиком (null).
            val whole = LayerDrawable(arrayOf(bg, fg))
            if (mono == null) {
                val back = render(full, pad, size, bg, base)
                val cover = Stats(back).opaque
                back.recycle()
                return if (cover < field * SOLID) free(context, whole, size, pad, grow) else null
            }
            art = whole
            mask = mono
            probe = Stats(render(full, pad, size, whole, base).also { dstIn(it, render(full, pad, size, mono, base)) })
        } else {
            if (mono != null) {
                val cut = render(full, pad, size, mono, base)
                val inner = render(full, pad, size, fg, base).also { dstIn(it, cut) }
                val st = Stats(inner)
                inner.recycle()
                cut.recycle()
                when {
                    // Силуэт срезает только белую плашку — цветное почти всё внутри (у Google Диска силуэт —
                    // контур, по нему знак выкусывается: тогда рисунок целиком).
                    st.opaque > 0 && st.vivid >= st.opaque * VIVID && st.vivid >= all.vivid * KEEP -> {
                        mask = mono
                        probe = st
                    }
                    all.opaque > field * PLATE -> {
                        art = mono
                        color = mainColor(drawn) ?: tintFor(average(bg)) ?: TOP
                    }
                }
            } else if (all.opaque > field * PLATE || (own != null && all.opaque > field * OWN_PLATE)) {
                // Плитка в рисунке; свой силуэт есть — он цветом значка и при плитке поменьше (Xiaomi Home).
                drawn.recycle()
                return if (own != null) painted(context, own, icon, size, pad) else free(context, fg, size, pad, grow)
            }
            if (color == null && mask == null && all.vivid < all.opaque * VIVID) color = tintFor(average(bg))
            drawn.recycle()
        }
        if (color == null && probe.mixedBlack()) return null
        if (color == null && probe.dark()) color = vivid(average(bg)) ?: TOP
        // Чёрные части цветного знака (волна Диктофона рядом с красной чертой) на чёрном фоне пропали бы — белые.
        val whiten = color == null && probe.black > probe.opaque * 0.2f
        val compose = { b: Rect ->
            val out = render(full, pad, size, art, b)
            dropShadows(out)
            mask?.let { m ->
                val cut = render(full, pad, size, m, b)
                dstIn(out, cut)
                cut.recycle()
            }
            color?.let { tint(out, it) }
            if (whiten) whitenBlack(out)
            out
        }
        val first = compose(base)
        val t = measure(first, size) ?: return withShadow(context, first)
        first.recycle()
        return withShadow(context, compose(scaled(base, t, full)))
    }

    /** Значок старого образца (без слоёв) — уже своей формы: только выравнивается размер. grow — слой 108 единиц. */
    fun free(context: Context, icon: Drawable, size: Int, pad: Int, grow: Int = 0): Bitmap {
        val full = size + pad * 2
        val base = Rect(pad - grow, pad - grow, pad + size + grow, pad + size + grow)
        val first = render(full, pad, size, icon, base).also(::dropShadows)
        val t = measure(first, size) ?: return withShadow(context, first)
        first.recycle()
        return withShadow(context, render(full, pad, size, icon, scaled(base, t, full)).also(::dropShadows))
    }

    /**
     * Свой силуэт приложения (AppGlyphs), залитый главным цветом его значка — для тех, у кого нет ни
     * монохромного слоя, ни рисунка без подложки (AliExpress, Epic Games).
     */
    fun painted(context: Context, glyph: Drawable, icon: Drawable, size: Int, pad: Int): Bitmap {
        val probe = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, 48, 48)
        icon.draw(Canvas(probe))
        val color = mainColor(probe) ?: TOP
        probe.recycle()
        val full = size + pad * 2
        val base = Rect(pad, pad, pad + size, pad + size)
        val first = render(full, pad, size, glyph, base)
        val t = measure(first, size)
        val out = if (t == null) first else render(full, pad, size, glyph, scaled(base, t, full)).also { first.recycle() }
        tint(out, color)
        return withShadow(context, out)
    }

    private fun render(full: Int, pad: Int, size: Int, d: Drawable, bounds: Rect): Bitmap {
        val bmp = Bitmap.createBitmap(full, full, Bitmap.Config.ARGB_8888)
        d.bounds = bounds
        Canvas(bmp).run {
            clipRect(pad, pad, pad + size, pad + size)
            d.draw(this)
        }
        return bmp
    }

    /**
     * Почти прозрачная дымка и подсветка в рисунке (у Files — светлый квадрат с альфой 6 %) на чёрном фоне XMB
     * читается грязной плашкой. Всё плотнее — часть знака, даже тёмное (волна Диктофона).
     */
    private fun dropShadows(bmp: Bitmap) {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) if ((px[i] ushr 24) < HAZE) px[i] = 0
        bmp.setPixels(px, 0, w, 0, 0, w, h)
    }

    private fun whitenBlack(bmp: Bitmap) {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val p = px[i]
            val a = p ushr 24
            if (a == 0) continue
            // Премультиплицированный цвет: тёмный — если и после деления на альфу тёмный; белый того же покрытия.
            if (maxOf((p shr 16) and 255, (p shr 8) and 255, p and 255) * 255 / a < 60) px[i] = (a shl 24) or (a shl 16) or (a shl 8) or a
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
    }

    private fun dstIn(bmp: Bitmap, mask: Bitmap) {
        Canvas(bmp).drawBitmap(mask, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
    }

    private fun tint(shape: Bitmap, color: Int) {
        val paint = Paint().apply {
            this.color = color
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        }
        Canvas(shape).drawRect(0f, 0f, shape.width.toFloat(), shape.height.toFloat(), paint)
    }

    /** Непрозрачные пиксели рисунка: сколько их, сколько насыщенных, средняя светлота. */
    private class Stats(bmp: Bitmap) {
        var opaque = 0
        var vivid = 0
        var black = 0
        private var light = 0f

        init {
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            for (p in px) {
                if ((p ushr 24) < 128) continue
                opaque++
                val r = (p shr 16) and 255
                val g = (p shr 8) and 255
                val b = p and 255
                val hi = maxOf(r, g, b)
                // Очень тёмный оттенок (тёмно-синий DeepL) на чёрном фоне — не цвет, а темнота.
                if (hi > 100 && (hi - minOf(r, g, b)) > hi * 0.3f) vivid++
                if (hi < 60) black++
                light += (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
            }
        }

        fun dark(): Boolean = opaque > 0 && light / opaque < 0.22f && vivid < opaque * 0.25f

        /** Чёрное вперемешку со светлым и без цвета (Nothing X): ни как есть, ни белым на чёрном не читается. */
        fun mixedBlack(): Boolean = !dark() && black > opaque * 0.35f && vivid < opaque * 0.1f
    }

    /** Главный цвет рисунка: самый частый оттенок среди насыщенных пикселей (12 корзин по кругу), осветлённый. */
    private fun mainColor(bmp: Bitmap): Int? {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val n = IntArray(12)
        val sr = IntArray(12)
        val sg = IntArray(12)
        val sb = IntArray(12)
        val hsv = FloatArray(3)
        for (p in px) {
            if ((p ushr 24) < 128) continue
            val r = (p shr 16) and 255
            val g = (p shr 8) and 255
            val b = p and 255
            Color.RGBToHSV(r, g, b, hsv)
            if (hsv[1] < 0.3f || hsv[2] < 0.25f) continue
            val k = (hsv[0] / 30f).toInt() % 12
            n[k]++
            sr[k] += r
            sg[k] += g
            sb[k] += b
        }
        val best = n.indices.maxBy { n[it] }
        if (n[best] == 0) return null
        Color.RGBToHSV(sr[best] / n[best], sg[best] / n[best], sb[best] / n[best], hsv)
        hsv[2] = maxOf(hsv[2], 0.75f)
        return Color.HSVToColor(hsv)
    }

    /** Средний цвет подложки; null — подложки нет. */
    private fun average(bg: Drawable?): Int? {
        bg ?: return null
        val probe = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888)
        bg.setBounds(0, 0, 12, 12)
        bg.draw(Canvas(probe))
        val px = IntArray(144)
        probe.getPixels(px, 0, 12, 0, 0, 12, 12)
        probe.recycle()
        var r = 0
        var g = 0
        var b = 0
        for (p in px) {
            r += (p shr 16) and 255
            g += (p shr 8) and 255
            b += p and 255
        }
        return Color.rgb(r / 144, g / 144, b / 144)
    }

    /** Насыщенная подложка, поднятая до заметной на тёмном фоне яркости; нейтральная — null. */
    private fun vivid(plate: Int?): Int? {
        plate ?: return null
        val hsv = FloatArray(3)
        Color.colorToHSV(plate, hsv)
        if (hsv[1] < 0.2f) return null
        hsv[2] = maxOf(hsv[2], 0.75f)
        return Color.HSVToColor(hsv)
    }

    /**
     * Цвет, в который перекрасить белый знак: подложка насыщенная и средней яркости — её цвет, поднятый
     * до заметного на тёмном фоне; нейтральная, светлая или тёмная подложка — null, знак остаётся своим.
     */
    private fun tintFor(plate: Int?): Int? {
        plate ?: return null
        val hsv = FloatArray(3)
        Color.colorToHSV(plate, hsv)
        if (hsv[2] < 0.4f) return null
        val lum = (0.2126f * Color.red(plate) + 0.7152f * Color.green(plate) + 0.0722f * Color.blue(plate)) / 255f
        if (lum > 0.5f) return null
        return vivid(plate)
    }

    /** Тень под значком, как у белых силуэтов; Nothing — без неё. */
    private fun withShadow(context: Context, shape: Bitmap): Bitmap {
        if (XmbSettings.look.flatIcons) return hardware(shape)
        val full = shape.width
        val out = Bitmap.createBitmap(full, full, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val dp = context.resources.displayMetrics.density
        val off = IntArray(2)
        val shadow = shape.extractAlpha(Paint().apply { maskFilter = BlurMaskFilter(2.2f * dp, BlurMaskFilter.Blur.NORMAL) }, off)
        canvas.drawBitmap(shadow, off[0].toFloat(), off[1] + 1.6f * dp, Paint().apply { color = 0x66000000 })
        canvas.drawBitmap(shape, 0f, 0f, null)
        shape.recycle()
        shadow.recycle()
        return hardware(out)
    }

    /**
     * Приложения заполняют монохромный слой кто как: у Firefox рисунок почти во весь слой, у Telegram —
     * небольшой самолёт. Рамка рисунка масштабируется к общей мере: среднее геометрическое сторон —
     * FIT_GEO поля (круг и самолёт выглядят одного веса), длинная сторона не больше FIT_MAX (вытянутые
     * не раздуваются), закрашенная площадь не больше FIT_AREA² поля (сплошные плитки не давят).
     * Рисунок перерисовывается заново — вектор остаётся чётким.
     */
    private fun fit(shape: Bitmap, drawable: Drawable, size: Int, pad: Int) {
        val t = measure(shape, size) ?: return
        val full = shape.width
        drawable.bounds = scaled(drawable.copyBounds(), t, full)
        shape.eraseColor(0)
        Canvas(shape).run {
            clipRect(pad, pad, pad + size, pad + size)
            drawable.draw(this)
        }
    }

    /** Масштаб и центр рамки рисунка: (k, cx, cy); null — рисунок пустой. */
    private fun measure(shape: Bitmap, size: Int): FloatArray? {
        val full = shape.width
        val px = IntArray(full * full)
        shape.getPixels(px, 0, full, 0, 0, full, full)
        var l = full
        var t = full
        var r = -1
        var b = -1
        var area = 0
        for (y in 0 until full) for (x in 0 until full) {
            if ((px[y * full + x] ushr 24) > 80) {
                area++
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                if (y > b) b = y
            }
        }
        if (r < l || b < t) return null
        val bw = (r - l + 1f) / size
        val bh = (b - t + 1f) / size
        val k = minOf(FIT_GEO / sqrt(bw * bh), FIT_MAX / maxOf(bw, bh), FIT_AREA / sqrt(area / (size.toFloat() * size)))
        return floatArrayOf(k, (l + r + 1) / 2f, (t + b + 1) / 2f)
    }

    private fun scaled(old: Rect, t: FloatArray, full: Int): Rect {
        val (k, cx, cy) = t
        val c = full / 2f
        return Rect(
            (c + (old.left - cx) * k).roundToInt(), (c + (old.top - cy) * k).roundToInt(),
            (c + (old.right - cx) * k).roundToInt(), (c + (old.bottom - cy) * k).roundToInt(),
        )
    }

    /**
     * Силуэт из точек, как матрица Glyph у Nothing: поле size делится на DOTS×DOTS клеток, в клетке, которую
     * фигура закрывает больше чем наполовину, — круглая точка.
     */
    fun dots(context: Context, glyph: Glyph, size: Int, pad: Int): Bitmap {
        val shape = shape(context, glyph, size, pad)
        val full = shape.width
        val px = IntArray(full * full)
        shape.getPixels(px, 0, full, 0, 0, full, full)
        shape.recycle()
        val out = Bitmap.createBitmap(full, full, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TOP }
        val cell = size / DOTS.toFloat()
        for (j in 0 until DOTS) for (i in 0 until DOTS) {
            val x0 = pad + i * cell
            val y0 = pad + j * cell
            var cover = 0
            for (sy in 0 until 3) for (sx in 0 until 3) {
                val x = (x0 + cell * (sx + 0.5f) / 3).toInt().coerceIn(0, full - 1)
                val y = (y0 + cell * (sy + 0.5f) / 3).toInt().coerceIn(0, full - 1)
                if ((px[y * full + x] ushr 24) > 127) cover++
            }
            if (cover >= 5) canvas.drawCircle(x0 + cell / 2, y0 + cell / 2, cell * 0.36f, paint)
        }
        return hardware(out)
    }

    /** Ореол: размытый белый силуэт чуть крупнее значка. */
    fun glow(context: Context, glyph: Glyph, size: Int, pad: Int): Bitmap {
        val shape = shape(context, glyph, size, pad)
        val dp = context.resources.displayMetrics.density
        val off = IntArray(2)
        val blur = shape.extractAlpha(Paint().apply { maskFilter = BlurMaskFilter(9 * dp, BlurMaskFilter.Blur.NORMAL) }, off)
        val out = Bitmap.createBitmap(shape.width, shape.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(blur, off[0].toFloat(), off[1].toFloat(), Paint().apply { color = 0xFFFFFFFF.toInt() })
        shape.recycle()
        blur.recycle()
        return hardware(out)
    }

    private fun shape(context: Context, glyph: Glyph, size: Int, pad: Int): Bitmap {
        val full = size + pad * 2
        val bmp = Bitmap.createBitmap(full, full, Bitmap.Config.ARGB_8888)
        val d = context.getDrawable(res(glyph))!!.mutate()
        d.setBounds(pad, pad, pad + size, pad + size)
        d.draw(Canvas(bmp))
        return bmp
    }

    private fun hardware(bmp: Bitmap): Bitmap {
        val hw = bmp.copy(Bitmap.Config.HARDWARE, false) ?: return bmp
        bmp.recycle()
        return hw
    }

    fun padFor(size: Float): Int = (size * 0.3f).roundToInt()

    private const val DOTS = 13
    private const val FIT_GEO = 0.58f
    private const val FIT_MAX = 0.8f
    private const val FIT_AREA = 0.46f
    private const val PLATE = 0.7f
    private const val OWN_PLATE = 0.4f
    private const val TINY = 0.05f
    private const val SOLID = 0.95f
    private const val KEEP = 0.85f
    private const val HAZE = 48
    private const val VIVID = 0.25f
    private const val TOP = 0xFFFFFFFF.toInt()
    private const val BOTTOM = 0xFFC9D1DD.toInt()
}
