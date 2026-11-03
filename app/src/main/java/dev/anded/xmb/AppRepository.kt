package dev.anded.xmb

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import java.text.Collator
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Список приложений по разделам. Сборка и отрисовка иконок — в фоновом потоке,
 * иконки кэшируются аппаратными Bitmap и переживают перестройку списка.
 */
class AppRepository(private val context: Context, private val iconPx: Int, private val onChanged: () -> Unit) {

    val prefs: SharedPreferences = context.getSharedPreferences("xmb", Context.MODE_PRIVATE)
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "xmb-apps") }
    private val main = Handler(Looper.getMainLooper())
    private val icons = ConcurrentHashMap<String, Bitmap>()
    private val requested = AtomicInteger()

    /** Раздел на экране (id) — его значки рисуются первыми. */
    @Volatile var focusColumn = Categories.TOP

    /** Видимые разделы: пустые скрыты, «Частые» и «Настройки» — всегда; columns — их колонки. */
    var sections: List<Category> = Categories.all.filter { it.always }
        private set

    var columns: List<List<AppEntry>> = List(sections.size) { emptyList() }
        private set

    /** Док — свои пять приложений; пока его не настраивали — самые частые. */
    var dock: List<AppEntry> = emptyList()
        private set

    /** Ярлыки приложений (чаты, действия) — для поиска. Есть, только пока XMB — лаунчер по умолчанию. */
    var shortcuts: List<AppEntry> = emptyList()
        private set

    // Только что установленные пакеты: при следующем показе XMB спросить, в какой раздел положить.
    private val fresh = LinkedHashSet<String>()

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackageAdded(packageName: String, user: UserHandle) {
            fresh += packageName
            changed(packageName)
        }
        override fun onPackageChanged(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
            packageNames.forEach(::changed)
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
            reload()
    }

    fun start() = launcherApps.registerCallback(callback, main)

    fun stop() {
        launcherApps.unregisterCallback(callback)
        worker.shutdownNow()
    }

    fun moveTo(entry: AppEntry, categoryId: String) {
        prefs.edit().putString(PREF_CATEGORY + entry.key, categoryId).apply()
        reload()
    }

    fun inDock(entry: AppEntry): Boolean = dock.any { it.key == entry.key }

    /** В док: в конец, а если он полон — на место [replace]. */
    fun addToDock(entry: AppEntry, replace: AppEntry? = null) {
        val keys = dock.map { it.key }.toMutableList()
        val at = replace?.let { r -> keys.indexOf(r.key) } ?: -1
        if (at >= 0) keys[at] = entry.key else if (keys.size < DOCK_SIZE) keys += entry.key else return
        saveDock(keys)
    }

    fun removeFromDock(entry: AppEntry) = saveDock(dock.map { it.key } - entry.key)

    /** Поставить на место [to] (0 — первое), остальные сдвигаются. */
    fun moveInDock(entry: AppEntry, to: Int) {
        val keys = dock.map { it.key }.toMutableList()
        if (!keys.remove(entry.key)) return
        keys.add(to.coerceIn(0, keys.size), entry.key)
        saveDock(keys)
    }

    private fun saveDock(keys: List<String>) {
        prefs.edit().putString(PREF_DOCK, keys.joinToString("\n")).apply()
        reload()
    }

    fun resetLayout() {
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith(PREF_CATEGORY) }.forEach(edit::remove)
        edit.apply()
        reload()
    }

    private fun changed(pkg: String) {
        icons.keys.removeIf { it.startsWith("$pkg/") }
        reload()
    }

    fun reload() {
        val ticket = requested.incrementAndGet()
        worker.execute {
            if (ticket != requested.get()) return@execute
            val built = build()
            val sc = buildShortcuts()
            main.post {
                val all = withUsage(built)
                val keep = Categories.all.indices.filter { Categories.all[it].always || all[it].isNotEmpty() }
                sections = keep.map { Categories.all[it] }
                columns = keep.map { all[it] }
                shortcuts = sc
                onChanged()
            }
            loadIcons(built, ticket)
            loadShortcutIcons(sc, ticket)
        }
    }

    private fun build(): List<List<AppEntry>> {
        val cols = List(Categories.all.size) { ArrayList<AppEntry>() }
        for (user in userManager.userProfiles) {
            for (info in launcherApps.getActivityList(null, user)) {
                val cn = info.componentName
                if (cn.packageName == context.packageName) continue
                val key = cn.flattenToShortString() + "#" + user.hashCode()
                val label = info.label?.toString()?.trim().orEmpty().ifEmpty { cn.packageName }
                val catId = prefs.getString(PREF_CATEGORY + key, null)
                    ?: Categories.classify(cn.packageName, label, info.applicationInfo.category)
                val entry = AppEntry(key, label, cn, user, info)
                entry.icon = icons[key]
                cols[Categories.indexOf(catId)].add(entry)
            }
        }
        val collator = Collator.getInstance(Locale.forLanguageTag("ru"))
        for (list in cols) list.sortWith { a, b -> collator.compare(a.label, b.label) }
        cols[Categories.SETTINGS].addAll(0, specials())
        return cols
    }

    /**
     * «Частые» и порядок по частоте — в главном потоке: AppHistory пишется там же.
     * Те же AppEntry, что и в разделах: значок и подписи общие.
     */
    private fun withUsage(built: List<List<AppEntry>>): List<List<AppEntry>> {
        val apps = built.flatten().filter { it.special == null }
        val top = apps.map { it to AppHistory.score(it.component!!.packageName) }
            .filter { it.second > 0.05f }
            .sortedByDescending { it.second }
            .distinctBy { it.first.component!!.packageName }
            .map { it.first }
        val saved = prefs.getString(PREF_DOCK, null)
        dock = if (!XmbSettings.dock) {
            emptyList()
        } else if (saved == null) {
            top.take(DOCK_SIZE)
        } else {
            val byKey = apps.associateBy { it.key }
            saved.split('\n').mapNotNull { byKey[it] }
        }
        val docked = dock.mapTo(HashSet()) { it.component!!.packageName }
        val frequent = top.filter { it.component!!.packageName !in docked }.take(Categories.TOP_SIZE)
        return built.mapIndexed { i, list ->
            when {
                i == Categories.TOP_INDEX -> frequent
                XmbSettings.sortByUsage -> {
                    val (special, rest) = list.partition { it.special != null }
                    special + rest.sortedByDescending { AppHistory.score(it.component!!.packageName) }
                }
                else -> list
            }
        }
    }

    /** Первый из только что установленных, который уже есть в списке; спросить о нём один раз. */
    fun takeFresh(): AppEntry? {
        val it = fresh.iterator()
        while (it.hasNext()) {
            val pkg = it.next()
            val entry = columns.asSequence().drop(1).flatten().firstOrNull { e -> e.component?.packageName == pkg }
            if (entry != null) {
                it.remove()
                return entry
            }
        }
        return null
    }

    /** Раздел (id), куда сейчас положено приложение (без «Частых»). */
    fun categoryOf(entry: AppEntry): String =
        columns.indices.drop(1).firstOrNull { entry in columns[it] }?.let { sections[it].id } ?: Categories.OTHER

    /** Ярлыки приложения для меню долгого тапа: сначала динамические (свежие чаты), потом объявленные. */
    fun shortcutsFor(entry: AppEntry): List<ShortcutInfo> {
        val cn = entry.component ?: return emptyList()
        if (!launcherApps.hasShortcutHostPermission()) return emptyList()
        val q = LauncherApps.ShortcutQuery()
            .setPackage(cn.packageName)
            .setActivity(cn)
            .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST)
        return runCatching { launcherApps.getShortcuts(q, entry.user!!) }.getOrNull().orEmpty()
            .filter { it.isEnabled }
            .sortedWith(compareBy({ !it.isDynamic }, { it.rank }))
            .take(MENU_SHORTCUTS)
    }

    fun shortcutIcon(info: ShortcutInfo, sizePx: Int): Bitmap? = runCatching {
        val d = launcherApps.getShortcutIconDrawable(info, context.resources.displayMetrics.densityDpi) ?: return null
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, sizePx, sizePx)
        d.draw(Canvas(bmp))
        bmp
    }.getOrNull()

    private fun buildShortcuts(): List<AppEntry> {
        if (!launcherApps.hasShortcutHostPermission()) return emptyList()
        val q = LauncherApps.ShortcutQuery()
            .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST)
        val pm = context.packageManager
        val appLabels = HashMap<String, String>()
        val out = ArrayList<AppEntry>()
        for (user in userManager.userProfiles) {
            val list = runCatching { launcherApps.getShortcuts(q, user) }.getOrNull() ?: continue
            for (sc in list) {
                if (!sc.isEnabled || sc.`package` == context.packageName) continue
                val label = sc.shortLabel?.toString()?.trim().orEmpty()
                if (label.isEmpty()) continue
                val app = appLabels.getOrPut(sc.`package`) {
                    runCatching { pm.getApplicationLabel(pm.getApplicationInfo(sc.`package`, 0)).toString() }.getOrDefault(sc.`package`)
                }
                val key = "sc:${sc.`package`}/${sc.id}#${user.hashCode()}"
                val entry = AppEntry(key, label, sc.activity, user, null, null, app, sc)
                entry.icon = icons[key]
                out += entry
            }
        }
        return out
    }

    private fun loadShortcutIcons(list: List<AppEntry>, ticket: Int) {
        val size = (iconPx * 0.8f).toInt()
        for (e in list) {
            if (e.icon != null) continue
            if (ticket != requested.get()) return
            val bmp = shortcutIcon(e.shortcut ?: continue, size)?.let(::toHardware) ?: continue
            icons[e.key] = bmp
            e.icon = bmp
        }
        main.post(onChanged)
    }

    private fun specials(): List<AppEntry> {
        val home = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName == context.packageName
        val key = isKeyServiceEnabled(context)
        val wallpaper = isOurWallpaper(context)
        val iconMode = XmbSettings.icons
        val iconName = when {
            iconMode == XmbSettings.ICONS_WHITE -> "Белые силуэты"
            iconMode == XmbSettings.ICONS_COLOR -> "Своей формы"
            iconMode.startsWith(XmbSettings.ICON_PACK) -> IconPack.installed(context)
                .firstOrNull { it.first == iconMode.removePrefix(XmbSettings.ICON_PACK) }?.second ?: "Пакет не найден"
            else -> "Как в системе"
        }
        return listOf(
            special("look", "Оформление", Glyph.LOOK, XmbSettings.look.title),
            special("theme", "Тема", Glyph.THEME, XmbSettings.themeName()),
            special("style", "Фон", Glyph.WAVE, XmbSettings.STYLE_NAMES[XmbSettings.style]),
            special("freeze", "Анимация фона", Glyph.WAVE, if (XmbSettings.freezeBg) "Замирает через 30 с покоя" else "Всегда"),
            special("dock", "Док", Glyph.GRID, if (XmbSettings.dock) "Пять своих приложений внизу" else "Выключен"),
            special("order", "Порядок в разделах", Glyph.GRID, if (XmbSettings.sortByUsage) "По частоте" else "По алфавиту"),
            special("icons", "Значки", Glyph.ICONS, iconName),
            special("shape", "Форма значков", Glyph.ICONS, XmbSettings.SHAPE_NAMES[XmbSettings.iconShape]),
            special(
                "notices", "Значки уведомлений", Glyph.BELL,
                if (noticesEnabled(context)) "Включены — число на значках и «Сейчас играет»" else "Выдать доступ к уведомлениям",
            ),
            special(
                "calendar", "Календарь в карточке", Glyph.CALENDAR,
                if (NextEvent.granted(context)) "Ближайшее событие над разделами" else "Разрешить доступ к календарю",
            ),
            special("hints", "Подсказки", Glyph.HELP, "Как пользоваться XMB"),
            special(
                "wallpaper", "Живые обои", Glyph.WALLPAPER,
                if (wallpaper) "Стоят — фон XMB и в многозадачности" else "Поставить, чтобы фон не сворачивался",
            ),
            special(
                "home", "Домашний экран", Glyph.HOME,
                if (home) "XMB — лаунчер по умолчанию" else "Сделать XMB лаунчером по умолчанию",
            ),
            Edition.keyService(context)?.let {
                special(
                    "key", "Кнопка Essential", Glyph.KEY,
                    if (key) "Нажатие: поиск. Удержание: поворот экрана" else "Выключена — включить в спец. возможностях",
                )
            },
            special("reset", "Сбросить раскладку", Glyph.RESET, "Вернуть приложения в разделы по умолчанию"),
        ).filterNotNull()
    }

    private fun special(id: String, label: String, glyph: Glyph, subtitle: String): AppEntry {
        val key = "special:$id"
        val entry = AppEntry(key, label, null, null, null, id, subtitle)
        entry.icon = icons.getOrPut(key) { renderGlyph(glyph) }
        return entry
    }

    private fun loadIcons(cols: List<List<AppEntry>>, ticket: Int) {
        val focus = Categories.indexOf(focusColumn)
        val order = cols.indices.sortedBy { abs(it - focus) }
        var batch = 0
        for (c in order) for (e in cols[c]) {
            if (e.icon != null) continue
            if (ticket != requested.get()) return
            val info = e.info ?: continue
            val bmp = runCatching { renderIcon(info) }.getOrNull() ?: continue
            icons[e.key] = bmp
            e.icon = bmp
            if (++batch == 8) {
                batch = 0
                main.post(onChanged)
            }
        }
        main.post(onChanged)
    }

    /** Сменить значки: системные, белые силуэты или пакет из Play; кэш значков приложений сбрасывается. */
    fun setIconMode(mode: String) {
        XmbSettings.icons = mode
        worker.execute { pack = null }
        icons.keys.removeIf { !it.startsWith("special:") }
        reload()
    }

    fun setIconShape(shape: Int) {
        XmbSettings.iconShape = shape
        icons.keys.removeIf { !it.startsWith("special:") }
        reload()
    }

    /** Сменить оформление: белые силуэты и значки «Настроек» рисуются заново (плоские или с объёмом). */
    fun setLook(look: Look) {
        XmbSettings.look = look
        icons.clear()
        reload()
    }

    // Только поток worker.
    private var pack: IconPack? = null

    private fun renderIcon(info: LauncherActivityInfo): Bitmap {
        val density = context.resources.displayMetrics.densityDpi
        val mode = XmbSettings.icons
        if (mode == XmbSettings.ICONS_WHITE) {
            // Монохромный слой адаптивного значка (Android 13+, «тематические значки») — родной белый значок XMB;
            // у кого его нет — свой силуэт из AppGlyphs.
            val pad = iconPx / 10
            val mono = (info.getIcon(density) as? AdaptiveIconDrawable)?.monochrome
            if (mono != null) return GlyphArt.styled(context, mono, iconPx - pad * 2, pad, MONO_LAYER_SCALE, normalize = true)
            AppGlyphs.drawable(context, info.componentName.packageName)
                ?.let { return GlyphArt.styled(context, it, iconPx - pad * 2, pad, 1f, normalize = true) }
        } else if (mode == XmbSettings.ICONS_COLOR) {
            val icon = info.getIcon(density)
            val pad = iconPx / 10
            val own = AppGlyphs.drawable(context, info.componentName.packageName)
            if (icon is AdaptiveIconDrawable) {
                GlyphArt.colored(context, icon, iconPx - pad * 2, pad, own)?.let { return it }
            } else {
                // Старый значок обычно сам плитка — свой силуэт его цветом лучше (Xiaomi Home).
                if (own != null) return GlyphArt.painted(context, own, icon, iconPx - pad * 2, pad)
                return GlyphArt.free(context, info.getBadgedIcon(density), iconPx - pad * 2, pad)
            }
        } else if (mode.startsWith(XmbSettings.ICON_PACK)) {
            val p = packFor(mode.removePrefix(XmbSettings.ICON_PACK))
            if (p != null) {
                p.drawable(info.componentName, density)?.let { return toHardware(draw(it)) }
                p.compose(info.getIcon(density), iconPx, density)?.let { return toHardware(it) }
            }
        }
        return toHardware(shaped(info, density))
    }

    /**
     * Цветной значок. Адаптивный (фон и рисунок отдельными слоями по 108 единиц, видимы центральные 72)
     * вырезается своей формой из XmbSettings.iconShape; системная маска Nothing OS — круг.
     */
    private fun shaped(info: LauncherActivityInfo, density: Int): Bitmap {
        val icon = info.getIcon(density)
        val shape = XmbSettings.iconShape
        if (shape == XmbSettings.SHAPE_SYSTEM || icon !is AdaptiveIconDrawable) return draw(info.getBadgedIcon(density))
        val bmp = Bitmap.createBitmap(iconPx, iconPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.clipPath(IconShapes.path(shape, iconPx.toFloat()))
        val grow = iconPx / 4
        for (layer in arrayOf(icon.background, icon.foreground)) {
            layer ?: continue
            layer.setBounds(-grow, -grow, iconPx + grow, iconPx + grow)
            layer.draw(canvas)
        }
        if (info.user == Process.myUserHandle()) return bmp
        return draw(context.packageManager.getUserBadgedIcon(BitmapDrawable(context.resources, bmp), info.user))
    }

    private fun packFor(pkg: String): IconPack? =
        pack?.takeIf { it.pkg == pkg } ?: runCatching { IconPack(context, pkg) }.getOrNull()?.also { pack = it }

    private fun draw(drawable: Drawable): Bitmap {
        val bmp = Bitmap.createBitmap(iconPx, iconPx, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, iconPx, iconPx)
        drawable.draw(Canvas(bmp))
        return bmp
    }

    private fun renderGlyph(glyph: Glyph): Bitmap {
        val pad = iconPx / 10
        return GlyphArt.icon(context, glyph, iconPx - pad * 2, pad)
    }

    // Аппаратный Bitmap сразу лежит в GPU: первый кадр с иконкой не тратит время на загрузку текстуры.
    private fun toHardware(bmp: Bitmap): Bitmap {
        val hw = bmp.copy(Bitmap.Config.HARDWARE, false) ?: return bmp
        bmp.recycle()
        return hw
    }

    companion object {
        const val ICON_DP = 52
        private const val MENU_SHORTCUTS = 5
        const val DOCK_SIZE = 5
        private const val PREF_DOCK = "dock"

        fun noticesEnabled(context: Context): Boolean {
            val me = ComponentName(context, XmbNotifications::class.java)
            val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
        private const val PREF_CATEGORY = "cat:"

        /** Монохромный слой — 108 единиц, рисунок обычно в центральных ~48: ×1.75 — силуэт ростом с значки разделов. */
        private const val MONO_LAYER_SCALE = 1.75f

        fun isOurWallpaper(context: Context): Boolean =
            WallpaperManager.getInstance(context).wallpaperInfo?.packageName == context.packageName

        fun isKeyServiceEnabled(context: Context): Boolean {
            val me = Edition.keyService(context) ?: return false
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
