package dev.anded.xmb

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.FrameLayout
import android.widget.Toast
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var xmb: XmbView
    private lateinit var search: SearchLayer
    private lateinit var repo: AppRepository
    private lateinit var launcherApps: LauncherApps
    private var fastModeId = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        launcherApps = getSystemService(LauncherApps::class.java)
        fastModeId = findFastMode()

        AppHistory.init(this)

        val iconPx = (AppRepository.ICON_DP * resources.displayMetrics.density).roundToInt()
        xmb = XmbView(this, iconPx)
        search = SearchLayer(this, iconPx)
        repo = AppRepository(this, iconPx) {
            xmb.setColumns(repo.columns)
            search.entries = repo.columns.flatten().filter { it.special == null }
        }

        val start = Categories.indexOf(repo.prefs.getString(PREF_LAST, Categories.all[Categories.DEFAULT].id))
        xmb.setCategory(start, animate = false)
        repo.focusColumn = start
        xmb.onCategory = { repo.focusColumn = it }
        xmb.onLaunch = ::launch
        xmb.onMenu = ::menuFor
        xmb.onBusy = ::setFast
        search.onLaunch = ::launch
        setContentView(FrameLayout(this).apply {
            addView(xmb)
            addView(search)
        })
        repo.start()
        handleKey(intent)
    }

    /** Кнопка Essential: удержание — поиск, нажатие в самом XMB — закрыть поиск и меню. */
    private fun handleKey(intent: Intent?) {
        when (intent?.getStringExtra(EXTRA_KEY)) {
            KEY_SEARCH -> {
                xmb.closeMenu()
                search.fallback = repo.columns[xmb.category]
                search.show()
            }
            KEY_TAP -> {
                search.hide()
                xmb.closeMenu()
            }
        }
        intent?.removeExtra(EXTRA_KEY)
    }

    override fun onResume() {
        super.onResume()
        AppHistory.foreground = packageName
        xmb.setActive(true)
        repo.reload()
    }

    override fun onPause() {
        super.onPause()
        search.hideNow()
        xmb.setActive(false)
        setFast(false)
        repo.prefs.edit().putString(PREF_LAST, Categories.all[xmb.category].id).apply()
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

    @Deprecated("Лаунчер сам обрабатывает «назад»")
    override fun onBackPressed() {
        if (!search.hide()) xmb.closeMenu()
    }

    private fun launch(entry: AppEntry, bounds: Rect) {
        when (entry.special) {
            "home" -> open(Intent(Settings.ACTION_HOME_SETTINGS))
            "key" -> open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            "reset" -> repo.resetLayout()
            else -> {
                val cn = entry.component ?: return
                val opts = ActivityOptions.makeScaleUpAnimation(xmb, bounds.left, bounds.top, bounds.width(), bounds.height())
                try {
                    launcherApps.startMainActivity(cn, entry.user, bounds, opts.toBundle())
                    AppHistory.moveToFront(cn.packageName)
                } catch (e: Exception) {
                    Toast.makeText(this, "Не запускается: ${entry.label}", Toast.LENGTH_SHORT).show()
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

    private fun menuFor(entry: AppEntry, column: Int): List<XmbView.MenuItem> {
        val cn = entry.component ?: return emptyList()
        val items = ArrayList<XmbView.MenuItem>()
        items += XmbView.MenuItem("О приложении") { launcherApps.startAppDetailsActivity(cn, entry.user, null, null) }
        items += XmbView.MenuItem("Удалить") {
            open(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", cn.packageName, null)))
        }
        Categories.all.forEachIndexed { i, c ->
            if (i != column) items += XmbView.MenuItem("→ ${c.title}") { repo.moveTo(entry, c.id) }
        }
        return items
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
        const val EXTRA_KEY = "dev.anded.xmb.KEY"
        const val KEY_SEARCH = "search"
        const val KEY_TAP = "tap"
    }
}
