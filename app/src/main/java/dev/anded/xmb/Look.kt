package dev.anded.xmb

import android.graphics.Typeface
import java.io.File

/**
 * Оформление целиком: фон, обработка значков, шрифты, выделение, панели. Цвет темы и «Фон» из настроек
 * действуют в «Классике» и «Авроре»; точечное (по мотивам Nothing OS) — всегда чёрный с точечной сеткой.
 * title — строковый ресурс: в интерфейсе нет чужих марок («XMB», «PS3», «Nothing»).
 */
enum class Look(val title: Int) {
    XMB(R.string.look_classic),
    NOTHING(R.string.look_dots),
    AURORA(R.string.look_aurora),
    ;

    /** Белые силуэты без градиента и тени — плоские, как значки Nothing OS. */
    val flatIcons: Boolean get() = this == NOTHING

    /** Тень под текстом: на чёрном фоне она не нужна и только мылит буквы. */
    val textShadow: Boolean get() = this != NOTHING
}

/** Красный Nothing: точка на фоне и счётчики уведомлений. */
const val NOTHING_RED = 0xFFD71921.toInt()

/**
 * Шрифты оформлений. Nothing OS кладёт свои в /system/fonts; кириллица есть только у точечных NDot*Caps,
 * поэтому подписи приложений — Inter (тоже системный у Nothing). Нет файла — обычный sans-serif.
 */
object Fonts {
    val dotTitle: Typeface by lazy { load("NDot57Caps.otf", "sans-serif-medium") }
    val dotSmall: Typeface by lazy { load("NDot55Caps.otf", "sans-serif") }
    val inter: Typeface by lazy { load("Inter-Regular.ttf", "sans-serif") }
    val interMedium: Typeface by lazy { load("Inter-Medium.otf", "sans-serif-medium") }
    val interLight: Typeface by lazy { load("Inter-Light.otf", "sans-serif-light") }

    private fun load(file: String, fallback: String): Typeface =
        runCatching { Typeface.createFromFile(File("/system/fonts", file)) }.getOrNull()
            ?: Typeface.create(fallback, Typeface.NORMAL)
}
