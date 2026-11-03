package dev.anded.xmb

import android.content.Context
import android.content.SharedPreferences

/** Оформление: тема, фон, значки. Общие для лаунчера и живых обоев — они в одном процессе. */
object XmbSettings {
    const val THEME_BY_MONTH = -1
    const val STYLE_PS3 = 0
    const val STYLE_CALM = 1
    const val STYLE_PLAIN = 2
    const val ICONS_SYSTEM = "system"
    const val ICONS_WHITE = "white"
    const val ICONS_COLOR = "color"
    const val ICON_PACK = "pack:"

    /** Названия тем — по цвету, в скобках месяц, к которому тема привязана на плеере. */
    val THEME_NAMES = arrayOf(
        "Серебро (январь)", "Золото (февраль)", "Трава (март)", "Сакура (апрель)", "Изумруд (май)",
        "Аметист (июнь)", "Бирюза (июль)", "Синий (август)", "Сирень (сентябрь)", "Янтарь (октябрь)",
        "Каштан (ноябрь)", "Рубин (декабрь)",
    )
    const val SHAPE_SYSTEM = 0
    val SHAPE_NAMES = arrayOf("Как в системе", "Сквиркл", "Скруглённый квадрат", "Капля")
    val STYLE_NAMES = arrayOf("Волна PS3", "Спокойный", "Только градиент")

    private const val DEFAULT_THEME = 7

    lateinit var prefs: SharedPreferences
        private set

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("xmb", Context.MODE_PRIVATE)
    }

    /** Оформление; читается на каждый кадр (тень текста), поэтому держится в поле. */
    var look: Look = Look.XMB
        get() {
            if (!lookRead) {
                field = Look.entries.getOrNull(prefs.getInt("look", 0)) ?: Look.XMB
                lookRead = true
            }
            return field
        }
        set(v) {
            field = v
            lookRead = true
            prefs.edit().putInt("look", v.ordinal).apply()
        }
    private var lookRead = false

    var theme: Int
        get() = prefs.getInt("theme", DEFAULT_THEME)
        set(v) = prefs.edit().putInt("theme", v).apply()

    var style: Int
        get() = prefs.getInt("bg_style", STYLE_PS3)
        set(v) = prefs.edit().putInt("bg_style", v).apply()

    /** Порядок внутри разделов: false — по алфавиту, true — чаще открываемые выше. */
    var sortByUsage: Boolean
        get() = prefs.getBoolean("sort_usage", false)
        set(v) = prefs.edit().putBoolean("sort_usage", v).apply()

    /** Фон замирает через 30 с покоя и оживает при касании — экономия батареи. */
    var freezeBg: Boolean
        get() = prefs.getBoolean("freeze_bg", true)
        set(v) = prefs.edit().putBoolean("freeze_bg", v).apply()

    /** Форма цветных значков: системная маска (у Nothing OS — круг) или своя, см. IconShapes. */
    var iconShape: Int
        get() = prefs.getInt("icon_shape", SHAPE_SYSTEM).coerceIn(0, SHAPE_NAMES.lastIndex)
        set(v) = prefs.edit().putInt("icon_shape", v).apply()

    /** Док — пять своих приложений снизу (в альбоме — справа); по умолчанию — как в сборке. */
    var dock: Boolean
        get() = prefs.getBoolean("dock_on", Edition.DOCK)
        set(v) = prefs.edit().putBoolean("dock_on", v).apply()

    var icons: String
        get() = prefs.getString("icons", ICONS_SYSTEM) ?: ICONS_SYSTEM
        set(v) = prefs.edit().putString("icons", v).apply()

    /** Пакеты, у которых не рисовать число уведомлений (и точку на разделе). */
    var badgeOff: Set<String> = emptySet()
        get() {
            if (!badgeOffRead) {
                field = prefs.getStringSet("badge_off", null)?.toSet().orEmpty()
                badgeOffRead = true
            }
            return field
        }
        set(v) {
            field = v
            badgeOffRead = true
            prefs.edit().putStringSet("badge_off", v).apply()
        }
    private var badgeOffRead = false

    fun themeName(): String = if (theme == THEME_BY_MONTH) "По месяцу, как на PS3" else THEME_NAMES[theme]
}
