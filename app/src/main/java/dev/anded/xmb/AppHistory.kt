package dev.anded.xmb

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.pow

/**
 * Последние и частые приложения. Пишет служба кнопки Essential (по событиям окон) и сам лаунчер при запуске;
 * переживает перезапуск. Частота — число открытий с затуханием: за неделю вес старых открытий вдвое меньше,
 * так «Частые» подстраиваются под то, чем пользуются сейчас. Только главный поток.
 */
object AppHistory {
    private const val SIZE = 16
    private const val USAGE_SIZE = 200
    private const val HALF_LIFE_MS = 7 * 24 * 3600 * 1000.0
    private const val PREF = "recent"
    private const val PREF_USAGE = "usage"

    private val recent = ArrayList<String>(SIZE)
    private val score = HashMap<String, Float>()
    private val stamp = HashMap<String, Long>()
    private var prefs: SharedPreferences? = null

    // Что на экране сейчас: то же приложение после смены окна — не новое открытие, после дома — новое.
    private var onScreen: String? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("xmb", Context.MODE_PRIVATE)
        prefs = p
        p.getString(PREF, null)?.split(',')?.filter { it.isNotEmpty() }?.let { recent.addAll(it.take(SIZE)) }
        p.getString(PREF_USAGE, null)?.split(';')?.forEach { rec ->
            val f = rec.split('|')
            if (f.size == 3) {
                score[f[0]] = f[1].toFloatOrNull() ?: return@forEach
                stamp[f[0]] = f[2].toLongOrNull() ?: return@forEach
            }
        }
        // Счёта ещё нет — засеять из истории последних, чтобы «Частые» не были пустыми с первого дня.
        if (score.isEmpty()) {
            val now = System.currentTimeMillis()
            recent.forEachIndexed { i, pkg ->
                score[pkg] = 1f - i * 0.05f
                stamp[pkg] = now
            }
        }
    }

    /** Приложение вышло на экран: открытие, если до этого на экране было другое или домашний экран. */
    fun moveToFront(pkg: String) {
        if (onScreen == pkg) return
        onScreen = pkg
        val now = System.currentTimeMillis()
        score[pkg] = scoreAt(pkg, now) + 1f
        stamp[pkg] = now
        if (recent.firstOrNull() != pkg) {
            recent.remove(pkg)
            recent.add(0, pkg)
            if (recent.size > SIZE) recent.removeAt(recent.lastIndex)
        }
        save()
    }

    /** На экране домашний экран — следующее появление любого приложения считается открытием. */
    fun home() {
        onScreen = null
    }

    /** Место пакета в истории (0 — самый свежий) или -1. */
    fun rank(pkg: String): Int = recent.indexOf(pkg)

    fun all(): List<String> = recent

    /** Частота на сейчас — с затуханием. */
    fun score(pkg: String): Float = scoreAt(pkg, System.currentTimeMillis())

    private fun scoreAt(pkg: String, now: Long): Float {
        val s = score[pkg] ?: return 0f
        val t = stamp[pkg] ?: return 0f
        return (s * 0.5.pow((now - t) / HALF_LIFE_MS)).toFloat()
    }

    private fun save() {
        val now = System.currentTimeMillis()
        val keep = score.keys.sortedByDescending { scoreAt(it, now) }.take(USAGE_SIZE)
        prefs?.edit()
            ?.putString(PREF, recent.joinToString(","))
            ?.putString(PREF_USAGE, keep.joinToString(";") { "$it|${score[it]}|${stamp[it]}" })
            ?.apply()
    }
}
