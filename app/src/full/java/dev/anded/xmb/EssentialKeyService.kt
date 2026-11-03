package dev.anded.xmb

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager

/**
 * Кнопка Essential: нажатие — поиск приложений из любого места (повторное — закрыть), удержание — поворот экрана.
 * Заодно служба ведёт историю приложений на экране для поиска.
 * Кнопка приходит в фильтр ключей спец. возможностей как KEYCODE_UNKNOWN.
 */
class EssentialKeyService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var holdFired = false
    private var homes: Set<String> = emptySet()
    private var ignored: Set<String> = emptySet()
    private val launchable = HashMap<String, Boolean>()
    private val rotation by lazy { Rotation(this) }
    private val hold = Runnable {
        holdFired = true
        if (rotation.toggle()) buzz()
    }

    override fun onServiceConnected() {
        AppHistory.init(this)
        // Домашние экраны в историю не пишем. Отрицательный приоритет — запасные вроде FallbackHome «Настроек»:
        // без отсева «Настройки» тоже считались бы лаунчером и пропадали из недавних.
        homes = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_ALL,
        ).filter { it.priority >= 0 }.map { it.activityInfo.packageName }.toSet()
        ignored = setOf("android", "com.android.systemui", "com.nothing.ntessentialspace", "com.nothing.ntessentialrecorder") +
            getSystemService(InputMethodManager::class.java).inputMethodList.map { it.packageName }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        when {
            pkg in homes -> AppHistory.home()
            pkg in ignored -> Unit
            launchable.getOrPut(pkg) { packageManager.getLaunchIntentForPackage(pkg) != null } -> AppHistory.moveToFront(pkg)
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_UNKNOWN) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                Log.i(TAG, "Essential key down, scanCode=${event.scanCode}")
                holdFired = false
                handler.postDelayed(hold, HOLD_MS)
            }
            KeyEvent.ACTION_UP -> {
                handler.removeCallbacks(hold)
                if (!holdFired) search()
            }
        }
        return true
    }

    override fun onInterrupt() = Unit


    private fun search() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_KEY, MainActivity.KEY_SEARCH)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun buzz() {
        getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
    }

    private companion object {
        const val TAG = "XmbKey"
        const val HOLD_MS = 350L
    }
}
