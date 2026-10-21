package dev.anded.xmb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import kotlin.math.roundToInt

/**
 * Значки в стиле XMB: белый силуэт с лёгким градиентом сверху вниз и мягкой тенью,
 * отдельно — размытый ореол для выбранного. Рисуются один раз, в кадре — только готовые Bitmap.
 * Формы — Material Icons (Apache 2.0), res/drawable/xmb_*.xml.
 */
object GlyphArt {

    fun res(glyph: Glyph): Int = when (glyph) {
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
    }

    /** Значок size×size с полем pad вокруг под тень; центр значка — центр Bitmap. */
    fun icon(context: Context, glyph: Glyph, size: Int, pad: Int): Bitmap {
        val full = size + pad * 2
        val shape = shape(context, glyph, size, pad)
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

    private const val TOP = 0xFFFFFFFF.toInt()
    private const val BOTTOM = 0xFFC9D1DD.toInt()
}
