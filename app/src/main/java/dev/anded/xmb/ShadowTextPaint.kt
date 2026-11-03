package dev.anded.xmb

import android.graphics.Paint
import android.text.TextPaint

/**
 * Белый текст с мягкой тенью, которая гаснет вместе с ним. Альфа краски на цвет тени не действует:
 * при угасании белый уходил, а чёрная тень оставалась и на миг читалась как чёрный текст.
 */
class ShadowTextPaint(dp: Float) : TextPaint(Paint.ANTI_ALIAS_FLAG) {
    private val radius = 3 * dp
    private val dy = 1 * dp

    init {
        fade(255)
    }

    fun fade(a: Int) {
        alpha = a
        if (XmbSettings.look.textShadow) setShadowLayer(radius, 0f, dy, (SHADOW_ALPHA * a / 255) shl 24) else clearShadowLayer()
    }

    private companion object {
        const val SHADOW_ALPHA = 0x99
    }
}
