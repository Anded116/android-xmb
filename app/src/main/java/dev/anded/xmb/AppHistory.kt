package dev.anded.xmb

import android.content.Context
import android.content.SharedPreferences

/**
 * Последние приложения на переднем плане. Пишет служба кнопки Essential (по событиям окон)
 * и сам лаунчер при запуске; переживает перезапуск — поиск показывает их первыми.
 * Только главный поток.
 */
object AppHistory {
    private const val SIZE = 16
    private const val PREF = "recent"

    private val recent = ArrayList<String>(SIZE)
    private var prefs: SharedPreferences? = null

    /** Пакет на экране сейчас: приложение, наш лаунчер или чужой домашний экран. */
    var foreground: String? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("xmb", Context.MODE_PRIVATE)
        prefs = p
        p.getString(PREF, null)?.split(',')?.filter { it.isNotEmpty() }?.let { recent.addAll(it.take(SIZE)) }
    }

    fun moveToFront(pkg: String) {
        foreground = pkg
        if (recent.firstOrNull() == pkg) return
        recent.remove(pkg)
        recent.add(0, pkg)
        if (recent.size > SIZE) recent.removeAt(recent.lastIndex)
        prefs?.edit()?.putString(PREF, recent.joinToString(","))?.apply()
    }

    /** Место пакета в истории (0 — самый свежий) или -1. */
    fun rank(pkg: String): Int = recent.indexOf(pkg)

    fun all(): List<String> = recent
}
