package dev.anded.xmb

import android.content.Context
import android.content.res.Configuration
import android.view.InputDevice
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

    // Названия тем (по цвету, в скобках месяц, к которому тема привязана на плеере), фонов и форм значков —
    // строковые массивы theme_names, style_names, shape_names; номера — их индексы.
    const val THEMES = 12
    const val STATUS_AUTO = 0
    const val STATUS_SHOW = 1
    const val STATUS_HIDE = 2
    const val SHAPE_SYSTEM = 0
    const val SHAPES = 4

    private const val DEFAULT_THEME = 7

    lateinit var prefs: SharedPreferences
        private set

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("xmb", Context.MODE_PRIVATE)
        mediaDefault = handheld(context)
    }

    /** Медиаразделы (Фото, Музыка, Видео вместо Развлечений); по умолчанию — на приставках и ТВ (LNC-A0008). */
    var media: Boolean
        get() = prefs.getBoolean("media", mediaDefault)
        set(v) = prefs.edit().putBoolean("media", v).apply()
    private var mediaDefault = false

    /** Приставка — встроенный (не подключённый) геймпад; ТВ — по режиму интерфейса. */
    private fun handheld(context: Context): Boolean {
        val ui = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        if (ui == Configuration.UI_MODE_TYPE_TELEVISION) return true
        return InputDevice.getDeviceIds().any { id ->
            val d = InputDevice.getDevice(id)
            d != null && !d.isExternal && d.supportsSource(InputDevice.SOURCE_GAMEPAD)
        }
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
        get() = prefs.getInt("icon_shape", SHAPE_SYSTEM).coerceIn(0, SHAPES - 1)
        set(v) = prefs.edit().putInt("icon_shape", v).apply()

    /** Строка состояния: STATUS_AUTO — скрыта в альбоме, STATUS_SHOW — всегда, STATUS_HIDE — никогда (свои часы). */
    var statusBar: Int
        get() = prefs.getInt("status_bar", STATUS_AUTO).coerceIn(STATUS_AUTO, STATUS_HIDE)
        set(v) = prefs.edit().putInt("status_bar", v).apply()

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

    fun themeName(context: Context): String =
        if (theme == THEME_BY_MONTH) context.getString(R.string.theme_by_month)
        else context.resources.getStringArray(R.array.theme_names)[theme]
}
