package dev.anded.xmb

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Drawable

/**
 * Личная сборка: кнопка Essential через службу спец. возможностей (поиск, поворот экрана, недавние) и свои
 * значки. В Google Play такая служба у лаунчера не проходит, поэтому в сборке store её нет.
 */
object Edition {
    /** Служба кнопки Essential; null — в этой сборке её нет. */
    fun keyService(context: Context): ComponentName? = ComponentName(context, EssentialKeyService::class.java)

    /** Док по умолчанию. */
    const val DOCK = true

    /** Значки приложений, которых нет в общем наборе AppGlyphs. */
    fun glyph(context: Context, pkg: String): Drawable? = when (pkg) {
        "com.tredir.app" -> context.getDrawable(R.drawable.app_tredir)
        "com.happproxy" -> AppGlyphs.text("Happ")
        "com.mediacube.garna" -> AppGlyphs.text("Garna")
        else -> null
    }
}
