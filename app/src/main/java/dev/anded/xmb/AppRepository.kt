package dev.anded.xmb

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
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

    @Volatile var focusColumn = Categories.DEFAULT

    var columns: List<List<AppEntry>> = List(Categories.all.size) { emptyList() }
        private set

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackageAdded(packageName: String, user: UserHandle) = changed(packageName)
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
            main.post {
                columns = built
                onChanged()
            }
            loadIcons(built, ticket)
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

    private fun specials(): List<AppEntry> {
        val home = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName == context.packageName
        val key = isKeyServiceEnabled(context)
        return listOf(
            special(
                "home", "Домашний экран", Glyph.HOME,
                if (home) "XMB — лаунчер по умолчанию" else "Сделать XMB лаунчером по умолчанию",
            ),
            special(
                "key", "Кнопка Essential", Glyph.KEY,
                if (key) "Нажатие: XMB. Удержание: поиск" else "Выключена — включить в спец. возможностях",
            ),
            special("reset", "Сбросить раскладку", Glyph.RESET, "Вернуть приложения в разделы по умолчанию"),
        )
    }

    private fun special(id: String, label: String, glyph: Glyph, subtitle: String): AppEntry {
        val key = "special:$id"
        val entry = AppEntry(key, label, null, null, null, id, subtitle)
        entry.icon = icons.getOrPut(key) { renderGlyph(glyph) }
        return entry
    }

    private fun loadIcons(cols: List<List<AppEntry>>, ticket: Int) {
        val order = cols.indices.sortedBy { abs(it - focusColumn) }
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

    private fun renderIcon(info: LauncherActivityInfo): Bitmap {
        val drawable = info.getBadgedIcon(context.resources.displayMetrics.densityDpi)
        val bmp = Bitmap.createBitmap(iconPx, iconPx, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, iconPx, iconPx)
        drawable.draw(Canvas(bmp))
        return toHardware(bmp)
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
        private const val PREF_CATEGORY = "cat:"

        fun isKeyServiceEnabled(context: Context): Boolean {
            val me = ComponentName(context, EssentialKeyService::class.java)
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
