package dev.anded.xmb

import android.Manifest
import android.app.Activity
import android.app.ActivityOptions
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.content.Context
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.util.Calendar
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var xmb: XmbView
    private lateinit var search: SearchLayer
    private lateinit var repo: AppRepository
    private lateinit var launcherApps: LauncherApps
    private var fastModeId = 0
    // Лаунчер уходил с экрана (onStop) и ещё не вернулся (onResume). Пауза — не признак: перед onNewIntent Android
    // ставит на паузу и видимую активность, а остановленную до onNewIntent успевает запустить (onStart).
    private var wasStopped = true

    /** Приложение, поверх которого кнопкой открыли поиск: закрытие поиска возвращает в него. */
    private var searchFrom: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, back)
        launcherApps = getSystemService(LauncherApps::class.java)
        fastModeId = findFastMode()

        XmbSettings.init(this)
        AppHistory.init(this)

        val ui = uiContext()
        val iconPx = (AppRepository.ICON_DP * ui.resources.displayMetrics.density).roundToInt()
        xmb = XmbView(ui, iconPx)
        search = SearchLayer(ui, iconPx)
        repo = AppRepository(this, iconPx) {
            xmb.setColumns(repo.columns, repo.sections, repo.dock)
            // «Частые» повторяют приложения из разделов — в поиске по одному разу; плюс ярлыки приложений.
            search.entries = repo.columns.flatten().filter { it.special == null }.distinctBy { it.key } + repo.shortcuts
            if (!wasStopped) askFresh()
        }

        val start = repo.prefs.getString(PREF_LAST, Categories.TOP) ?: Categories.TOP
        xmb.restoreSection(start)
        repo.focusColumn = start
        xmb.onCategory = { repo.focusColumn = xmb.sectionId() }
        xmb.onLaunch = ::launch
        xmb.onMenu = ::menuFor
        xmb.onBusy = ::setFast
        search.onLaunch = ::launch
        xmb.onOpenEvent = { open(it.openIntent()) }
        xmb.onSearch = ::toggleSearch
        setContentView(FrameLayout(this).apply {
            addView(xmb)
            addView(search)
        })
        repo.start()
        handleKey(intent)
        if (!repo.prefs.getBoolean(PREF_HINTS, false)) {
            repo.prefs.edit().putBoolean(PREF_HINTS, true).apply()
            xmb.showHints()
        }
    }

    /**
     * Нажатие кнопки Essential: открыть поиск, а если он уже открыт — закрыть. Открыли поверх приложения —
     * закрытие (кнопкой или «назад») возвращает в него.
     */
    private fun handleKey(intent: Intent?) {
        if (intent?.getStringExtra(EXTRA_KEY) == KEY_SEARCH) toggleSearch()
        intent?.removeExtra(EXTRA_KEY)
    }

    private fun toggleSearch() {
        xmb.closeMenu()
        if (search.open) {
            closeSearch()
        } else {
            searchFrom = if (wasStopped) AppHistory.all().firstOrNull() else null
            // Пока «Частые» не набрались (свежая установка) — все приложения, а не пустой ряд.
            search.fallback = repo.columns[xmb.category].takeIf { col -> col.any { it.special == null } }
                ?: repo.columns.flatten().filter { it.special == null }.distinctBy { it.key }
            search.show()
        }
    }

    private fun closeSearch() {
        search.hide()
        val pkg = searchFrom ?: return
        searchFrom = null
        packageManager.getLaunchIntentForPackage(pkg)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            runCatching { startActivity(it) }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyStatusBar(newConfig.orientation)
    }

    // После поворота, шторки и возврата из приложения система может вернуть строку — скрыть снова.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyStatusBar()
    }

    /**
     * В альбоме строка состояния скрыта — XMB и так еле помещается, там свои маленькие часы; шторка — свайпом от верха.
     * В портрете строка обычная: в ней уведомления.
     */
    private fun applyStatusBar(orientation: Int = resources.configuration.orientation) {
        val bars = window.decorView.windowInsetsController ?: return
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            bars.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsets.Type.statusBars())
        } else {
            bars.show(WindowInsets.Type.statusBars())
        }
    }

    override fun onResume() {
        super.onResume()
        wasStopped = false
        AppHistory.home()
        applyStatusBar()
        applyWallpaperMode()
        xmb.setActive(true)
        repo.reload()
        if (AppRepository.noticesEnabled(this)) NowPlaying.attach(this)
        refreshCalendar()
    }

    /**
     * Стоят живые обои XMB — окно прозрачное, фон рисуют обои: при уходе в многозадачность сворачивается
     * только колонка. Нет — фон рисует сам XmbView.
     */
    private fun applyWallpaperMode() {
        val ours = AppRepository.isOurWallpaper(this)
        if (ours) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            window.setBackgroundDrawableResource(R.color.xmb_background)
        }
        xmb.ownBackground = !ours
    }

    override fun onPause() {
        super.onPause()
        xmb.setActive(false)
        setFast(false)
        repo.prefs.edit().putString(PREF_LAST, xmb.sectionId()).apply()
    }

    override fun onStop() {
        super.onStop()
        wasStopped = true
        searchFrom = null
        // Не на паузе: перед onNewIntent (повторное нажатие кнопки) Android ставит на паузу и видимую активность.
        search.hideNow()
    }

    override fun onDestroy() {
        repo.stop()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        xmb.closeMenu()
        handleKey(intent)
    }

    // С целевым API 36 «назад» приходит только сюда (onBackPressed не вызывается). Лаунчер — домашний экран:
    // «назад» всегда наш — закрыть поиск, подсказки или меню, иначе ничего.
    private val back = OnBackInvokedCallback {
        if (search.open) closeSearch() else if (!xmb.closeHints()) xmb.closeMenu()
    }

    /**
     * Планшеты и ТВ (короткая сторона больше 480 dp) и маленькие приставки (меньше 360 dp) получают тот же XMB
     * в масштабе: вместо пересчёта каждого размера для его видов меняется плотность — dp и sp растут вместе.
     * Телефоны (360–480 dp) — как есть.
     */
    private fun uiContext(): Context {
        val sw = resources.configuration.smallestScreenWidthDp
        val s = when {
            sw < 360 -> sw / 360f
            sw > 480 -> min(1.5f, sw / 480f)
            else -> return this
        }
        val dpi = (resources.configuration.densityDpi * s).roundToInt()
        return ContextThemeWrapper(this, R.style.Theme_Xmb).apply {
            applyOverrideConfiguration(Configuration().apply { densityDpi = dpi })
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (search.open) {
            if (event.keyCode == KeyEvent.KEYCODE_BUTTON_Y) {
                if (event.action == KeyEvent.ACTION_DOWN) toggleSearch()
                return true
            }
        } else if (xmb.onKey(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Ближайшее событие — запрос к провайдеру календаря в фоне, результат — в карточку «Сейчас». */
    private fun refreshCalendar() {
        if (!NextEvent.granted(this)) {
            xmb.calendar = null
            return
        }
        val app = applicationContext
        Thread {
            val e = NextEvent.query(app)
            runOnUiThread { xmb.calendar = e }
        }.start()
    }

    @Deprecated("Activity API")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshCalendar()
        repo.reload()
    }

    private fun launch(entry: AppEntry, bounds: Rect) {
        when (entry.special) {
            "home" -> open(Intent(Settings.ACTION_HOME_SETTINGS))
            "key" -> open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            "reset" -> repo.resetLayout()
            "look" -> lookMenu()
            "theme" -> themeMenu()
            "style" -> styleMenu()
            "icons" -> iconsMenu()
            "shape" -> shapeMenu()
            "order" -> orderMenu()
            "freeze" -> freezeMenu()
            "dock" -> dockMenu()
            "notices" -> open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            "calendar" -> if (NextEvent.granted(this)) refreshCalendar() else requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 1)
            "hints" -> xmb.showHints()
            "wallpaper" -> open(
                Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                    .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(this, XmbWallpaper::class.java)),
            )
            else -> {
                val cn = entry.component ?: return
                val opts = ActivityOptions.makeScaleUpAnimation(xmb, bounds.left, bounds.top, bounds.width(), bounds.height())
                try {
                    val sc = entry.shortcut
                    if (sc != null) {
                        launcherApps.startShortcut(sc, bounds, opts.toBundle())
                    } else {
                        launcherApps.startMainActivity(cn, entry.user, bounds, opts.toBundle())
                    }
                    AppHistory.moveToFront(cn.packageName)
                } catch (e: Exception) {
                    Toast.makeText(this, getString(R.string.launch_failed, entry.label), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    // Меню вариантов не закрываются: выбор применяется сразу, как предпросмотр на плеере; закрыть — тап мимо.

    private fun lookMenu() {
        val items = Look.entries.map { look ->
            XmbView.MenuItem(getString(look.title), checked = XmbSettings.look == look, keepOpen = true) {
                if (XmbSettings.look != look) {
                    repo.setLook(look)
                    xmb.applyLook()
                    BgBus.touch()
                }
                lookMenu()
            }
        }
        xmb.showMenu(getString(R.string.set_look), items)
    }

    private fun themeMenu() {
        val month = Calendar.getInstance().get(Calendar.MONTH)
        val cur = XmbSettings.theme
        val items = ArrayList<XmbView.MenuItem>()
        items += XmbView.MenuItem(getString(R.string.theme_by_month), swatch(month), cur == XmbSettings.THEME_BY_MONTH, keepOpen = true) {
            pickTheme(XmbSettings.THEME_BY_MONTH)
        }
        resources.getStringArray(R.array.theme_names).forEachIndexed { i, name ->
            items += XmbView.MenuItem(name, swatch(i), cur == i, keepOpen = true) { pickTheme(i) }
        }
        xmb.showMenu(getString(R.string.set_theme), items)
    }

    private fun swatch(theme: Int) = intArrayOf(WaveShader.themeTop(theme), WaveShader.themeBottom(theme))

    private fun pickTheme(theme: Int) {
        XmbSettings.theme = theme
        themeMenu()
        repo.reload()
    }

    private fun styleMenu() {
        val items = resources.getStringArray(R.array.style_names).mapIndexed { i, name ->
            XmbView.MenuItem(name, checked = XmbSettings.style == i, keepOpen = true) {
                XmbSettings.style = i
                styleMenu()
                repo.reload()
            }
        }
        xmb.showMenu(getString(R.string.set_style), items)
    }

    private fun iconsMenu() {
        val cur = XmbSettings.icons
        val options = ArrayList<Pair<String, String>>()
        options += XmbSettings.ICONS_SYSTEM to getString(R.string.icons_system)
        options += XmbSettings.ICONS_WHITE to getString(R.string.icons_white)
        options += XmbSettings.ICONS_COLOR to getString(R.string.icons_color)
        IconPack.installed(this).forEach { (pkg, label) -> options += XmbSettings.ICON_PACK + pkg to label }
        val items = options.map { (mode, label) ->
            XmbView.MenuItem(label, checked = cur == mode, keepOpen = true) {
                repo.setIconMode(mode)
                iconsMenu()
            }
        }
        xmb.showMenu(getString(R.string.set_icons), items)
    }

    private fun shapeMenu() {
        val items = resources.getStringArray(R.array.shape_names).mapIndexed { i, name ->
            XmbView.MenuItem(name, checked = XmbSettings.iconShape == i, keepOpen = true) {
                repo.setIconShape(i)
                shapeMenu()
            }
        }
        xmb.showMenu(getString(R.string.set_shape), items)
    }

    private fun dockMenu() {
        val items = listOf(true to getString(R.string.dock_show), false to getString(R.string.dock_hide)).map { (on, label) ->
            XmbView.MenuItem(label, checked = XmbSettings.dock == on, keepOpen = true) {
                XmbSettings.dock = on
                dockMenu()
                repo.reload()
            }
        }
        xmb.showMenu(getString(R.string.set_dock), items)
    }

    private fun freezeMenu() {
        val items = listOf(true to getString(R.string.freeze_menu_on), false to getString(R.string.freeze_always)).map { (freeze, label) ->
            XmbView.MenuItem(label, checked = XmbSettings.freezeBg == freeze, keepOpen = true) {
                XmbSettings.freezeBg = freeze
                freezeMenu()
                repo.reload()
            }
        }
        xmb.showMenu(getString(R.string.set_freeze), items)
    }

    private fun orderMenu() {
        val items = listOf(false to getString(R.string.order_abc), true to getString(R.string.order_usage)).map { (byUsage, label) ->
            XmbView.MenuItem(label, checked = XmbSettings.sortByUsage == byUsage, keepOpen = true) {
                XmbSettings.sortByUsage = byUsage
                orderMenu()
                repo.reload()
            }
        }
        xmb.showMenu(getString(R.string.set_order), items)
    }

    /**
     * Долгий тап: сначала ярлыки приложения (свежие чаты, «Перевести», «Новое письмо») — до действия один тап,
     * потом перенос в раздел и служебное.
     */
    private fun menuFor(entry: AppEntry): List<XmbView.MenuItem> {
        val cn = entry.component ?: return emptyList()
        val items = ArrayList<XmbView.MenuItem>()
        val iconSize = (28 * resources.displayMetrics.density).roundToInt()
        for (sc in repo.shortcutsFor(entry)) {
            val label = sc.shortLabel?.toString() ?: continue
            items += XmbView.MenuItem(label, icon = repo.shortcutIcon(sc, iconSize)) {
                runCatching { launcherApps.startShortcut(sc, null, null) }
                AppHistory.moveToFront(cn.packageName)
            }
        }
        if (repo.inDock(entry)) {
            items += XmbView.MenuItem(getString(R.string.dock_place)) { xmb.post { dockPlaceMenu(entry) } }
            items += XmbView.MenuItem(getString(R.string.dock_remove)) { repo.removeFromDock(entry) }
        } else if (repo.dock.size < AppRepository.DOCK_SIZE) {
            items += XmbView.MenuItem(getString(R.string.dock_add)) { repo.addToDock(entry) }
        } else {
            items += XmbView.MenuItem(getString(R.string.dock_replace)) { xmb.post { dockReplaceMenu(entry) } }
        }
        if (AppRepository.noticesEnabled(this)) {
            val on = cn.packageName !in XmbSettings.badgeOff
            items += XmbView.MenuItem(getString(R.string.badge_toggle), checked = on) {
                XmbSettings.badgeOff = if (on) XmbSettings.badgeOff + cn.packageName else XmbSettings.badgeOff - cn.packageName
                Notices.changed()
            }
        }
        items += XmbView.MenuItem(getString(R.string.move_to)) { xmb.post { moveMenu(entry, getString(R.string.move_title, entry.label)) } }
        items += XmbView.MenuItem(getString(R.string.app_info)) { launcherApps.startAppDetailsActivity(cn, entry.user, null, null) }
        items += XmbView.MenuItem(getString(R.string.uninstall)) {
            open(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", cn.packageName, null)))
        }
        return items
    }

    /** Док полон: какое приложение заменить новым. */
    private fun dockReplaceMenu(entry: AppEntry) {
        val items = repo.dock.map { old -> XmbView.MenuItem(old.label, icon = old.icon) { repo.addToDock(entry, replace = old) } }
        xmb.showMenu(getString(R.string.replace_title, entry.label), items)
    }

    /** Место в доке по порядку, текущее отмечено. */
    private fun dockPlaceMenu(entry: AppEntry) {
        val current = repo.dock.indexOfFirst { it.key == entry.key }
        val items = repo.dock.mapIndexed { i, e ->
            XmbView.MenuItem("${i + 1}. ${e.label}", checked = i == current, icon = e.icon) {
                if (i != current) repo.moveInDock(entry, i)
            }
        }
        xmb.showMenu(getString(R.string.place_title, entry.label), items)
    }

    /** Выбор раздела для приложения; текущий отмечен. В «Частые» не кладут — они собираются сами. */
    private fun moveMenu(entry: AppEntry, title: String) {
        val current = repo.categoryOf(entry)
        val items = Categories.all.filter { it.id != Categories.TOP }.map { c ->
            XmbView.MenuItem(getString(c.title), checked = c.id == current) {
                if (c.id != current) repo.moveTo(entry, c.id)
            }
        }
        xmb.showMenu(title, items)
    }

    /** Только что установленное приложение: показать, куда оно легло, и спросить, туда ли. */
    private fun askFresh() {
        val entry = repo.takeFresh() ?: return
        val col = repo.sections.indexOfFirst { it.id == repo.categoryOf(entry) }
        if (col >= 0) xmb.reveal(col, repo.columns[col].indexOf(entry))
        xmb.post { moveMenu(entry, getString(R.string.fresh_title, entry.label)) }
    }

    /** Пока идёт касание или анимация — просим самый быстрый режим экрана, в покое отдаём выбор системе. */
    private fun setFast(on: Boolean) {
        val id = if (on) fastModeId else 0
        val lp = window.attributes
        if (lp.preferredDisplayModeId == id) return
        lp.preferredDisplayModeId = id
        window.attributes = lp
    }

    private fun findFastMode(): Int {
        val d = display ?: return 0
        val cur = d.mode
        return d.supportedModes
            .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .maxByOrNull { it.refreshRate }?.modeId ?: 0
    }

    companion object {
        private const val PREF_LAST = "last_category"
        private const val PREF_HINTS = "hints_shown"
        const val EXTRA_KEY = "dev.anded.xmb.KEY"
        const val KEY_SEARCH = "search"
    }
}
