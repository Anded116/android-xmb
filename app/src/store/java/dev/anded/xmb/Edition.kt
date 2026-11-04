package dev.anded.xmb

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Drawable

/**
 * Сборка для Google Play: без службы спец. возможностей — лаунчеры под исключение для инструментов
 * доступности не попадают. Поиск и недавние — только внутри XMB.
 */
object Edition {
    /** Служба кнопки Essential; null — в этой сборке её нет. */
    @Suppress("UNUSED_PARAMETER")
    fun keyService(context: Context): ComponentName? = null

    /** Док по умолчанию. */
    const val DOCK = false

    /** Свои «Фото» и «Видео»: Play пускает READ_MEDIA_IMAGES/VIDEO только галереям — в разделах одни приложения. */
    const val GALLERY = false

    /** Значки приложений, которых нет в общем наборе AppGlyphs. */
    @Suppress("UNUSED_PARAMETER")
    fun glyph(context: Context, pkg: String): Drawable? = null
}
