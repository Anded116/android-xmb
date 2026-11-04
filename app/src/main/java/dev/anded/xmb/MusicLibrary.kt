package dev.anded.xmb

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.provider.MediaStore
import android.util.Size
import android.widget.Toast
import java.text.Collator
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Музыка на устройстве в разделе «Музыка» (LNC-A0011): «Сейчас играет», «Перемешать всё», «Плейлисты», «Все треки»,
 * «Альбомы», «Исполнители» — пункты-папки колонки. Списки строятся при входе, обложки альбомов — в фоне.
 * Плейлисты, оценки и меню трека — как на плеере HiBy (LNC-A0021…A0024), данные — MusicData.
 * roots() зовётся из потока сборки списка, остальное — из главного.
 */
class MusicLibrary(
    private val context: Context,
    private val iconPx: Int,
    private val onArt: () -> Unit,
    private val onAsk: () -> Unit,
    private val showMenu: (String, List<XmbView.MenuItem>) -> Unit,
) {
    private val glyphs = ConcurrentHashMap<String, Bitmap>()
    private val covers = ConcurrentHashMap<Long, Bitmap>()
    private val loader = Executors.newSingleThreadExecutor { Thread(it, "xmb-covers") }
    private val collator = Collator.getInstance(Locale.forLanguageTag("ru"))

    fun granted(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun roots(): List<AppEntry> {
        if (!granted()) return listOf(item("music:ask", str(R.string.music_library), str(R.string.music_allow), Glyph.MUSIC, onAsk))
        val all = tracks()
        if (all.isEmpty()) return listOf(item("music:none", str(R.string.music_none), str(R.string.music_none_sub), Glyph.MUSIC, null))
        val out = ArrayList<AppEntry>()
        Music.current?.let { t -> out += folder(NOW, str(R.string.music_now), t.title, Glyph.QUEUE, open = ::nowItems) }
        out += item("music:shuffle", str(R.string.music_shuffle_all), plural(R.plurals.music_n_tracks, all.size), Glyph.SHUFFLE) {
            Music.play(context, tracks().shuffled(), 0)
        }
        out += folder(PLAYLISTS, str(R.string.music_playlists), plural(R.plurals.music_n_playlists, MusicData.playlists.size), Glyph.HEART) {
            playlistItems()
        }
        out += folder("music:tracks", str(R.string.music_tracks), plural(R.plurals.music_n_tracks, all.size), Glyph.TRACKS) {
            trackItems(tracks(), byAlbum = false)
        }
        val albums = all.distinctBy { it.albumId }.size
        out += folder("music:albums", str(R.string.music_albums), plural(R.plurals.music_n_albums, albums), Glyph.ALBUM) {
            albumItems(tracks())
        }
        val artists = all.distinctBy { it.artist }.size
        out += folder("music:artists", str(R.string.music_artists), plural(R.plurals.music_n_artists, artists), Glyph.ARTIST) {
            artistItems(tracks())
        }
        return out
    }

    /** Как категория «Плейлисты» плеера: «Избранное» и свои, «Лучшее», «Давно не слушал», «Новый плейлист». */
    private fun playlistItems(): List<AppEntry> {
        val all = tracks()
        val byPath = all.associateBy { it.path }
        val out = ArrayList<AppEntry>()
        for (p in MusicData.playlists) {
            val fav = p === MusicData.favorites
            val list = p.paths.mapNotNull { byPath[it] }
            out += folder("pl:${p.name}", p.name, plural(R.plurals.music_n_tracks, list.size), if (fav) Glyph.HEART else Glyph.TRACKS,
                menu = { listMenu(list) + if (fav) emptyList() else listOf(menuItem(R.string.music_pl_delete) { MusicData.delete(p) }) },
            ) { trackItems(p.paths.mapNotNull { byPath[it] }, byAlbum = false, playlist = p) }
        }
        // Умные списки считаются заново при каждом входе и обновлении: оценки меняются, пока список открыт.
        fun best() = tracks().let { t -> (5 downTo 4).flatMap { st -> t.filter { MusicData.stars(it.path) == st } } }
        fun old(): List<Track> {
            val now = System.currentTimeMillis() / 1000
            return tracks().filter { MusicData.longAgo(it.path, now) }.sortedBy { MusicData.rating(it.path)?.last ?: 0 }
        }
        val nBest = (5 downTo 4).sumOf { st -> all.count { MusicData.stars(it.path) == st } }
        out += folder("music:best", str(R.string.music_best), plural(R.plurals.music_n_tracks, nBest), Glyph.STAR,
            menu = { listMenu(best()) }) { trackItems(best(), byAlbum = false) }
        val now = System.currentTimeMillis() / 1000
        val nOld = all.count { MusicData.longAgo(it.path, now) }
        out += folder("music:old", str(R.string.music_long_ago), plural(R.plurals.music_n_tracks, nOld), Glyph.RESET,
            menu = { listMenu(old()) }) { trackItems(old(), byAlbum = false) }
        out += item("music:newpl", str(R.string.music_new_playlist), null, Glyph.TRACKS) { MusicData.create(context) }
        return out
    }

    /** «Перемешать» и треки; тап — играть список с этого трека. В плейлисте у трека в меню — порядок и «Убрать». */
    private fun trackItems(list: List<Track>, byAlbum: Boolean, playlist: Playlist? = null): List<AppEntry> {
        val out = ArrayList<AppEntry>(list.size + 1)
        if (list.size > 1) out += item("music:shuffle:${list.size}", str(R.string.music_shuffle), null, Glyph.SHUFFLE) {
            Music.play(context, list.shuffled(), 0)
        }
        list.forEachIndexed { i, t ->
            val who = if (byAlbum) t.artist else "${t.artist} · ${t.album}"
            out += item("track:${t.id}", t.title, starred(t, who), Glyph.MUSIC, menu = { trackMenu(t, playlist, i) }) {
                Music.play(context, list, i)
            }
        }
        return out
    }

    private fun albumItems(all: List<Track>): List<AppEntry> {
        val groups = all.groupBy { it.albumId }.values.sortedWith { a, b -> collator.compare(a[0].album, b[0].album) }
        val out = groups.map { g ->
            val first = g[0]
            val artist = g.map { it.artist }.distinct().singleOrNull() ?: str(R.string.music_various)
            val tracks = g.sortedWith(compareBy<Track> { it.number }.thenBy { it.title })
            folder("album:${first.albumId}", first.album, "$artist · ${plural(R.plurals.music_n_tracks, g.size)}", Glyph.ALBUM,
                menu = { listMenu(tracks) }) { trackItems(tracks, byAlbum = true) }
                .also { it.icon = covers[first.albumId] ?: it.icon }
        }
        loadCovers(out.zip(groups.map { it[0].albumId }))
        return out
    }

    private fun artistItems(all: List<Track>): List<AppEntry> =
        all.groupBy { it.artist }.entries.sortedWith { a, b -> collator.compare(a.key, b.key) }.map { (artist, g) ->
            val tracks = g.sortedWith(compareBy<Track>({ it.album }, { it.number }, { it.title }))
            folder("artist:$artist", artist, plural(R.plurals.music_n_tracks, g.size), Glyph.ARTIST, menu = { listMenu(tracks) }) {
                trackItems(tracks, byAlbum = false)
            }
        }

    /** «Сейчас играет»: кнопки проигрывателя (для геймпада), оценка и избранное трека, затем очередь. */
    fun nowItems(): List<AppEntry> {
        val t = Music.current ?: return emptyList()
        val out = ArrayList<AppEntry>(Music.queue.size + 6)
        out += if (Music.playing) {
            item("music:toggle", str(R.string.music_pause), t.title, Glyph.MEDIA_PAUSE) { Music.toggle() }
        } else {
            item("music:toggle", str(R.string.music_play), t.title, Glyph.MEDIA_PLAY) { Music.toggle() }
        }
        out += item("music:next", str(R.string.music_next), Music.queue.getOrNull(Music.index + 1)?.title, Glyph.MEDIA_NEXT) { Music.next() }
        out += item("music:prev", str(R.string.music_prev), null, Glyph.MEDIA_PREV) { Music.previous() }
        val stars = MusicData.stars(t.path)
        out += item("music:rate", if (stars > 0) str(R.string.music_rating, STARS[stars]) else str(R.string.music_rate), t.title, Glyph.STAR) {
            rateMenu(t)
        }
        val fav = MusicData.inFavorites(t.path)
        out += item("music:fav", str(if (fav) R.string.music_fav_remove else R.string.music_fav_add), t.title, Glyph.HEART) {
            favorite(t)
        }
        out += item("music:stop", str(R.string.music_stop), null, Glyph.STOP) { Music.stop() }
        Music.queue.forEachIndexed { i, q ->
            val sub = if (i == Music.index) str(R.string.music_playing) + " · " + q.artist else q.artist
            out += item("queue:$i", q.title, starred(q, sub), if (i == Music.index) Glyph.WAVE else Glyph.MUSIC,
                menu = { trackMenu(q, null, i) }) { Music.jump(i) }
        }
        return out
    }

    // ---------- меню (долгий тап, X на геймпаде) ----------

    private fun trackMenu(t: Track, playlist: Playlist?, pos: Int): List<XmbView.MenuItem> {
        val stars = MusicData.stars(t.path)
        val items = arrayListOf(
            XmbView.MenuItem(if (stars > 0) str(R.string.music_rating, STARS[stars]) else str(R.string.music_rate)) { rateMenu(t) },
            menuItem(if (MusicData.inFavorites(t.path)) R.string.music_fav_remove else R.string.music_fav_add) { favorite(t) },
            menuItem(R.string.music_to_playlist) { pickPlaylist(t.title, listOf(t)) },
            menuItem(R.string.music_play_next) {
                Music.playNext(context, listOf(t))
                toast(str(R.string.music_next_toast, t.title))
            },
            menuItem(R.string.music_enqueue) {
                Music.enqueue(context, listOf(t))
                toast(str(R.string.music_queued_toast, t.title))
            },
        )
        if (playlist != null && playlist.paths.getOrNull(pos) == t.path) {
            items += menuItem(R.string.music_pl_remove) {
                MusicData.remove(playlist, pos)
                toast(str(R.string.music_removed, playlist.name))
            }
            if (pos > 0) items += menuItem(R.string.music_up) { MusicData.move(playlist, pos, -1) }
            if (pos < playlist.paths.lastIndex) items += menuItem(R.string.music_down) { MusicData.move(playlist, pos, 1) }
        }
        return items
    }

    /** Для папки (альбом, исполнитель, плейлист): все её треки — следующими, в очередь, в плейлист. */
    private fun listMenu(tracks: List<Track>): List<XmbView.MenuItem> {
        if (tracks.isEmpty()) return emptyList()
        return listOf(
            menuItem(R.string.music_play_next) { Music.playNext(context, tracks) },
            menuItem(R.string.music_enqueue) { Music.enqueue(context, tracks) },
            menuItem(R.string.music_all_to_playlist) { pickPlaylist(str(R.string.music_to_playlist), tracks) },
        )
    }

    private fun rateMenu(t: Track) {
        val cur = MusicData.stars(t.path)
        val items = (5 downTo 1).map { n -> XmbView.MenuItem(STARS[n], checked = n == cur) { MusicData.setStars(t, n) } } +
            XmbView.MenuItem(str(R.string.music_no_rating), checked = cur == 0) { MusicData.setStars(t, 0) }
        showMenu(t.title, items)
    }

    private fun favorite(t: Track) {
        val name = MusicData.favorites.name
        toast(str(if (MusicData.toggleFavorite(t)) R.string.music_added else R.string.music_removed, name))
    }

    /** Выбор плейлиста (кроме «Избранного» — для него своя кнопка) или новый. */
    private fun pickPlaylist(title: String, tracks: List<Track>) {
        val items = MusicData.playlists.drop(1).map { p -> XmbView.MenuItem(p.name) { addTo(p, tracks) } } +
            menuItem(R.string.music_new_playlist) { addTo(MusicData.create(context), tracks) }
        showMenu(title, items)
    }

    private fun addTo(p: Playlist, tracks: List<Track>) {
        val n = MusicData.add(p, tracks)
        toast(if (n == 0) str(R.string.music_already, p.name) else str(R.string.music_added, p.name))
    }

    private fun menuItem(label: Int, action: () -> Unit) = XmbView.MenuItem(str(label), action = action)

    private fun toast(s: String) = Toast.makeText(context, s, Toast.LENGTH_SHORT).show()

    /** Подпись трека: звёзды оценки перед исполнителем. */
    private fun starred(t: Track, sub: String): String {
        val n = MusicData.stars(t.path)
        return if (n > 0) "${STARS[n]}  $sub" else sub
    }

    private fun tracks(): List<Track> {
        val cols = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK, MediaStore.Audio.Media.RELATIVE_PATH, MediaStore.Audio.Media.DISPLAY_NAME,
        )
        val unknown = str(R.string.music_unknown)
        val out = ArrayList<Track>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val artist = c.getString(2)?.takeIf { it.isNotBlank() && it != MediaStore.UNKNOWN_STRING } ?: unknown
                    val file = c.getString(7).orEmpty() + c.getString(8).orEmpty()
                    val path = if (file.startsWith(MusicData.ROOT)) file.removePrefix(MusicData.ROOT) else "/$file"
                    out += Track(
                        c.getLong(0), c.getString(1)?.takeIf { it.isNotBlank() } ?: "—", artist,
                        c.getString(3)?.takeIf { it.isNotBlank() } ?: unknown, c.getLong(4), c.getLong(5), c.getInt(6), path,
                    )
                }
            }
        }
        out.sortWith { a, b -> collator.compare(a.title, b.title) }
        return out
    }

    private fun loadCovers(list: List<Pair<AppEntry, Long>>) {
        loader.execute {
            var n = 0
            for ((e, id) in list) {
                val bmp = covers[id] ?: cover(id)?.also { covers[id] = it } ?: continue
                e.icon = bmp
                if (++n % 6 == 0) onArt()
            }
            onArt()
        }
    }

    private fun cover(albumId: Long): Bitmap? = runCatching {
        Thumbs.tile(context.contentResolver.loadThumbnail(Music.albumUri(albumId), Size(iconPx, iconPx), null), iconPx)
    }.getOrNull()

    private fun folder(
        key: String,
        label: String,
        sub: String?,
        glyph: Glyph,
        menu: (() -> List<XmbView.MenuItem>)? = null,
        open: () -> List<AppEntry>,
    ): AppEntry = AppEntry(key, label, null, null, null, SPECIAL, sub, children = open, menu = menu).also { it.icon = glyph(glyph) }

    private fun item(
        key: String,
        label: String,
        sub: String?,
        glyph: Glyph,
        act: (() -> Unit)?,
    ): AppEntry = item(key, label, sub, glyph, null, act)

    private fun item(
        key: String,
        label: String,
        sub: String?,
        glyph: Glyph,
        menu: (() -> List<XmbView.MenuItem>)?,
        act: (() -> Unit)?,
    ): AppEntry = AppEntry(key, label, null, null, null, SPECIAL, sub, action = act ?: {}, menu = menu).also { it.icon = glyph(glyph) }

    // Значки зависят от оформления (в Nothing — плоские): ключ с ним.
    private fun glyph(glyph: Glyph): Bitmap = glyphs.getOrPut("${XmbSettings.look}/$glyph") {
        val pad = iconPx / 10
        GlyphArt.icon(context, glyph, iconPx - pad * 2, pad)
    }

    private fun str(id: Int): String = context.getString(id)

    private fun str(id: Int, arg: String): String = context.getString(id, arg)

    private fun plural(id: Int, n: Int): String = context.resources.getQuantityString(id, n, n)

    companion object {
        /** Ключ пункта «Сейчас играет» — его уровень пересобирается при смене трека. */
        const val NOW = "music:now"
        const val PLAYLISTS = "music:playlists"
        /** special у всех пунктов музыки: не приложения — без поиска и «Частых». */
        const val SPECIAL = "music"
        private val STARS = arrayOf("", "★", "★★", "★★★", "★★★★", "★★★★★")
    }
}
