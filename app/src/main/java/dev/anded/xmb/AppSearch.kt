package dev.anded.xmb

import kotlin.math.min

/**
 * Поиск приложений «как на iOS»: без запроса — последние открытые, с запросом — по смыслу совпадения.
 * Понимает латиницу вместо кириллицы и наоборот («sber» → «СберБанк»), набор не в той раскладке
 * («ыиук» → «sber»), первые буквы слов («тб» → «Т-Банк»), слова из имени пакета и одну опечатку.
 * Среди равных выше то, что открывали недавно.
 */
object AppSearch {

    class Keys(label: String, pkg: String) {
        val words: List<String> = splitWords(label)
        val name: String = words.joinToString("")
        val lat: String = latin(name)
        val latWords: List<String> = words.map(::latin)
        val initials: String = words.mapNotNull { it.firstOrNull() }.joinToString("")
        val latInitials: String = latWords.mapNotNull { it.firstOrNull() }.joinToString("")
        val pkgWords: List<String> = pkg.lowercase().split('.', '_').filter { it.length > 2 && it !in PKG_NOISE }
    }

    /** Без запроса: последние открытые приложения по порядку. */
    fun recent(entries: List<AppEntry>, limit: Int): List<AppEntry> {
        val out = ArrayList<AppEntry>(limit)
        for (pkg in AppHistory.all()) {
            val e = entries.firstOrNull { it.component?.packageName == pkg } ?: continue
            out += e
            if (out.size == limit) break
        }
        return out
    }

    fun search(entries: List<AppEntry>, query: String): List<AppEntry> {
        val q = splitWords(query).joinToString("")
        if (q.isEmpty()) return emptyList()
        val swapped = swapLayout(q)
        val scored = ArrayList<Pair<AppEntry, Float>>()
        for (e in entries) {
            val k = e.keys
            var best = score(k, q)
            if (swapped != q) best = maxOf(best, score(k, swapped) - 30)
            if (best <= 0) continue
            val rank = e.component?.packageName?.let(AppHistory::rank) ?: -1
            val bonus = if (rank >= 0) 40f - rank * 2 else 0f
            scored += e to best + bonus - k.name.length * 0.3f
        }
        scored.sortByDescending { it.second }
        return scored.map { it.first }
    }

    private fun score(k: Keys, q: String): Int {
        val ql = latin(q)
        return maxOf(match(q, k.name, k.words, k.initials), match(ql, k.lat, k.latWords, k.latInitials) - 10, pkgMatch(ql, k))
    }

    private fun match(q: String, name: String, words: List<String>, initials: String): Int = when {
        name == q -> 1000
        name.startsWith(q) -> 900
        words.any { it.startsWith(q) } -> 800
        q.length >= 2 && initials.startsWith(q) -> 700
        name.contains(q) -> 600
        q.length >= 4 && name.length >= q.length && distance(name.substring(0, q.length), q) <= 1 -> 350
        q.length >= 3 && words.any { it.startsWith(q[0]) } -> subsequence(q, name)
        else -> 0
    }

    private fun pkgMatch(q: String, k: Keys): Int =
        if (q.length >= 3 && k.pkgWords.any { it.startsWith(q) }) 450 else 0

    /** Буквы запроса по порядку внутри названия; чем плотнее, тем лучше. */
    private fun subsequence(q: String, name: String): Int {
        var i = 0
        var first = -1
        var last = -1
        for ((j, c) in name.withIndex()) {
            if (i < q.length && c == q[i]) {
                if (first < 0) first = j
                last = j
                i++
            }
        }
        if (i < q.length) return 0
        val gaps = (last - first + 1) - q.length
        return (300 - gaps * 12 - first * 4).coerceAtLeast(0)
    }

    /** Расстояние Дамерау — Левенштейна для коротких строк. */
    private fun distance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = min(min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = min(d[i][j], d[i - 2][j - 2] + 1)
        }
        return d[a.length][b.length]
    }

    /** Слова названия в нижнем регистре: по небуквенным символам и по стыку «маленькая-Большая» (GoogleKeep). */
    private fun splitWords(s: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var prev = ' '
        for (c in s) {
            val letter = c.isLetterOrDigit()
            if (!letter || (c.isUpperCase() && prev.isLowerCase())) {
                if (cur.isNotEmpty()) out += cur.toString()
                cur.setLength(0)
            }
            if (letter) cur.append(if (c == 'ё' || c == 'Ё') 'е' else c.lowercaseChar())
            prev = c
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    /** Кириллица → латиница по звучанию; x → ks, чтобы «yandex» и «яндекс» сходились. */
    fun latin(s: String): String {
        val sb = StringBuilder(s.length + 4)
        for (c in s) {
            val i = CYR.indexOf(c)
            if (i >= 0) sb.append(LAT[i]) else if (c == 'x') sb.append("ks") else sb.append(c)
        }
        return sb.toString()
    }

    private fun swapLayout(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            val en = EN_KEYS.indexOf(c)
            val ru = RU_KEYS.indexOf(c)
            sb.append(if (en >= 0) RU_KEYS[en] else if (ru >= 0) EN_KEYS[ru] else c)
        }
        return sb.toString()
    }

    private const val CYR = "абвгдежзийклмнопрстуфхцчшщъыьэюя"
    private val LAT = arrayOf(
        "a", "b", "v", "g", "d", "e", "zh", "z", "i", "i", "k", "l", "m", "n", "o", "p",
        "r", "s", "t", "u", "f", "h", "c", "ch", "sh", "sch", "", "y", "", "e", "yu", "ya",
    )
    private const val EN_KEYS = "qwertyuiop[]asdfghjkl;'zxcvbnm,."
    private const val RU_KEYS = "йцукенгшщзхъфывапролджэячсмитьбю"
    private val PKG_NOISE = setOf("com", "org", "net", "app", "apps", "android", "mobile", "client", "google")
}
