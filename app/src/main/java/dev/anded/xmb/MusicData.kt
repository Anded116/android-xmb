package dev.anded.xmb

import android.content.Context
import android.os.Environment
import java.io.File
import java.util.concurrent.Executors

/** Оценка и статистика трека, как на плеере: звёзды 1–5 (0 — без оценки), прослушан, пропущен, когда играл (unix). */
class Rating(var stars: Int = 0, var plays: Int = 0, var skips: Int = 0, var last: Long = 0) {
    var artist = ""
    var album = ""
    var title = ""
}

/** Плейлист: пути треков (Track.path) по порядку; name — и имя файла без .m3u8. */
class Playlist(var name: String, val paths: MutableList<String>)

/**
 * Оценки, прослушивания и плейлисты «Музыки» в формате плеера HiBy (r3ii-xmb, LNC-A0021, LNC-A0022): ratings.tsv
 * и Playlists/<имя>.m3u8 в files/hiby лаунчера — туда их кладёт tools/hiby_import.py. Пути — от Music/HiBy, как от
 * корня карты плеера; трек вне этой папки — от корня памяти со «/» впереди. Первый плейлист — всегда «Избранное».
 * Только главный поток; запись — в фоне, целым файлом.
 */
object MusicData {
    private var dir: File? = null
    private val ratings = HashMap<String, Rating>()
    val playlists = ArrayList<Playlist>()
    private val io = Executors.newSingleThreadExecutor { Thread(it, "xmb-music-data") }
    private val listeners = ArrayList<() -> Unit>()
    private var favoritesName = "Избранное"

    fun init(context: Context) {
        if (dir != null) return
        favoritesName = context.getString(R.string.music_favorites)
        dir = context.getExternalFilesDir("hiby")
        load()
    }

    fun listen(l: () -> Unit) {
        listeners += l
    }

    fun unlisten(l: () -> Unit) {
        listeners -= l
    }

    fun rating(path: String): Rating? = ratings[path]

    fun stars(path: String): Int = ratings[path]?.stars ?: 0

    fun setStars(t: Track, stars: Int) {
        row(t).stars = stars
        saveRatings()
    }

    /** Прослушан: отыграл половину или 4 минуты. */
    fun played(t: Track) {
        val r = row(t)
        r.plays++
        r.last = System.currentTimeMillis() / 1000
        saveRatings()
    }

    /** Пропущен: ушли раньше 30 с, но не в первые 2. */
    fun skipped(t: Track) {
        row(t).skips++
        saveRatings()
    }

    /** «Давно не слушал»: любимое (4–5 звёзд или 3+ прослушивания), что не играло месяц. */
    fun longAgo(path: String, now: Long): Boolean {
        val r = ratings[path] ?: return false
        return (r.stars >= 4 || r.plays >= 3) && r.last > 0 && now - r.last >= LONG_AGO
    }

    val favorites: Playlist get() = playlists[0]

    fun inFavorites(path: String): Boolean = path in favorites.paths

    fun toggleFavorite(t: Track): Boolean {
        val on = !favorites.paths.remove(t.path)
        if (on) favorites.paths += t.path
        savePlaylist(favorites)
        return on
    }

    fun add(p: Playlist, tracks: List<Track>): Int {
        val fresh = tracks.map { it.path }.distinct().filter { it !in p.paths }
        p.paths += fresh
        savePlaylist(p)
        return fresh.size
    }

    fun remove(p: Playlist, pos: Int) {
        p.paths.removeAt(pos)
        savePlaylist(p)
    }

    /** Трек на одну позицию выше (−1) или ниже (+1). */
    fun move(p: Playlist, pos: Int, dir: Int) {
        val to = pos + dir
        if (to !in p.paths.indices) return
        p.paths.add(to, p.paths.removeAt(pos))
        savePlaylist(p)
    }

    /** Новый пустой «Плейлист N» в конце. */
    fun create(context: Context): Playlist {
        var n = playlists.size
        var name: String
        do {
            name = context.getString(R.string.music_playlist_n, n++)
        } while (playlists.any { it.name.equals(name, ignoreCase = true) })
        return Playlist(name, ArrayList()).also {
            playlists += it
            savePlaylist(it)
        }
    }

    /** «Избранное» не удаляется. */
    fun delete(p: Playlist) {
        if (p === favorites) return
        playlists.remove(p)
        val f = File(dir ?: return, "$PLAYLISTS/${p.name}.m3u8")
        io.execute { f.delete() }
        changed()
    }

    private fun row(t: Track): Rating = ratings.getOrPut(t.path) { Rating() }.also {
        it.artist = t.artist
        it.album = t.album
        it.title = t.title
    }

    private fun changed() = listeners.toList().forEach { it() }

    private fun load() {
        val d = dir ?: return
        runCatching {
            File(d, RATINGS).forEachLine(Charsets.UTF_8) { line ->
                if (line.startsWith("#") || line.isBlank()) return@forEachLine
                val f = line.split('\t')
                if (f.size < 6) return@forEachLine
                ratings[f[0]] = Rating(f[2].toIntOrNull() ?: 0, f[3].toIntOrNull() ?: 0, f[4].toIntOrNull() ?: 0, f[5].toLongOrNull() ?: 0).also {
                    it.artist = f.getOrElse(6) { "" }
                    it.album = f.getOrElse(7) { "" }
                    it.title = f.getOrElse(8) { "" }
                }
            }
        }
        val files = File(d, PLAYLISTS).listFiles { f -> f.name.endsWith(".m3u8") }.orEmpty().sortedBy { it.name.lowercase() }
        for (f in files) {
            val paths = ArrayList<String>()
            runCatching {
                f.forEachLine(Charsets.UTF_8) { raw ->
                    val line = raw.trim().removePrefix("﻿")
                    if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                    paths += when {
                        line.startsWith(STORAGE) -> line.removePrefix(STORAGE)
                        line.startsWith("../") -> line.removePrefix("../")
                        else -> line
                    }
                }
            }
            playlists += Playlist(f.name.removeSuffix(".m3u8"), paths)
        }
        val fav = playlists.indexOfFirst { it.name.equals(favoritesName, ignoreCase = true) || it.name == "Избранное" }
        if (fav > 0) playlists.add(0, playlists.removeAt(fav))
        if (fav < 0) playlists.add(0, Playlist(favoritesName, ArrayList()))
    }

    private fun saveRatings() {
        val d = dir ?: return
        val text = buildString {
            append(RATINGS_HEADER)
            for ((path, r) in ratings.toSortedMap()) {
                append(path).append("\t0.000\t").append(r.stars).append('\t').append(r.plays).append('\t').append(r.skips)
                    .append('\t').append(r.last).append('\t').append(r.artist).append('\t').append(r.album).append('\t')
                    .append(r.title).append('\n')
            }
        }
        io.execute { write(File(d, RATINGS), text) }
        changed()
    }

    private fun savePlaylist(p: Playlist) {
        val d = dir ?: return
        val text = buildString {
            append("#EXTM3U\n")
            for (path in p.paths) append(if (path.startsWith("/")) STORAGE + path else "../$path").append('\n')
        }
        io.execute { write(File(d, "$PLAYLISTS/${p.name}.m3u8"), text) }
        changed()
    }

    // Через временный файл: оборванная запись не портит прежний.
    private fun write(f: File, text: String) {
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text, Charsets.UTF_8)
            tmp.renameTo(f)
        }
    }

    /** Корень путей треков с плеера: Music/HiBy от корня памяти. */
    const val ROOT = "Music/HiBy/"
    private val STORAGE = Environment.getExternalStorageDirectory().path
    private const val RATINGS = "ratings.tsv"
    private const val PLAYLISTS = "Playlists"
    private const val LONG_AGO = 30 * 24 * 3600L
    private const val RATINGS_HEADER =
        "#xmb-ratings 1\n#путь\tначало\tзвёзды\tпрослушан\tпропущен\tпоследний раз\tисполнитель\tальбом\tназвание\n"
}
