package dev.anded.xmb

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import kotlin.math.min

/**
 * Свои белые силуэты для приложений, у которых нет монохромного слоя значка: в режиме «Белые силуэты»
 * они иначе остались бы цветными. Формы — в сетке 24×24 с полем 3, как у монохромных значков рядом;
 * логотипы-надписи — текстом (векторные буквы на 40 dp не читаются).
 */
object AppGlyphs {

    fun drawable(context: Context, pkg: String): Drawable? = when (pkg) {
        "ru.alfabank.mobile.android" -> context.getDrawable(R.drawable.app_alfabank)
        "com.wallet.crypto.trustapp" -> context.getDrawable(R.drawable.app_trustwallet)
        "com.mapswithme.maps.pro" -> context.getDrawable(R.drawable.app_mapsme)
        "com.apegroup.mcdonaldsrussia" -> context.getDrawable(R.drawable.app_vkusno)
        "com.azure.authenticator" -> context.getDrawable(R.drawable.app_authenticator)
        "com.google.android.apps.translate" -> context.getDrawable(R.drawable.app_translate)
        "com.monospace.notes" -> context.getDrawable(R.drawable.app_notes)
        "com.xiaomi.smarthome" -> context.getDrawable(R.drawable.app_xiaomihome)
        "org.mozilla.focus" -> context.getDrawable(R.drawable.app_focus)
        "ru.uec.strelka" -> context.getDrawable(R.drawable.app_strelka)
        "com.bybit.app" -> TextGlyph("BYBIT")
        "com.alibaba.aliexpresshd" -> TextGlyph("Ali")
        "com.epicgames.portal" -> TextGlyph("EPIC")
        "com.wildberries.ru" -> TextGlyph("WB")
        "ru.ozon.app.android" -> TextGlyph("OZON")
        else -> Edition.glyph(context, pkg)
    }

    /** Логотип-надпись — для значков, которые проще набрать, чем нарисовать. */
    fun text(s: String): Drawable = TextGlyph(s)

    /** Надпись по центру, сжатая по ширине: не шире 20 единиц из 24, заглавные — не выше 10. */
    private class TextGlyph(private val text: String) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textScaleX = 0.8f
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val unit = b.width() / 24f
            paint.textSize = 100f
            val fit = min(20 * unit / paint.measureText(text), 10 * unit / (paint.textSize * CAP))
            paint.textSize = 100f * fit
            canvas.drawText(text, b.exactCenterX(), b.exactCenterY() + paint.textSize * CAP / 2, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Drawable API")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    // Высота заглавных Roboto в долях кегля.
    private const val CAP = 0.711f
}
