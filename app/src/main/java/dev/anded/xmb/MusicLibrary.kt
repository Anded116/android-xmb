package dev.anded.xmb

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.provider.MediaStore
import android.util.Size
import java.text.Collator
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Музыка на устройстве в разделе «Музыка» (LNC-A0011): «Сейчас играет», «Перемешать всё», «Все треки»,
 * «Альбомы», «Исполнители» — пункты-папки колонки. Списки строятся при входе, обложки альбомов — в фоне.
 * roots() зовётся из потока сборки списка, остальное — из главного.
 */
class MusicLibrary(
    private val context: Context,
    private val iconPx: Int,
    private val onArt: () -> Unit,
    private val onAsk: () -> Unit,
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
        Music.current?.let { t -> out += folder(NOW, str(R.string.music_now), t.title, Glyph.QUEUE, ::nowItems) }
        out += item("music:shuffle", str(R.string.music_shuffle_all), plural(R.plurals.music_n_tracks, all.size), Glyph.SHUFFLE) {
            Music.play(context, tracks().shuffled(), 0)
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

    /** «Перемешать» и треки; тап — играть список с этого трека. */
    private fun trackItems(list: List<Track>, byAlbum: Boolean): List<AppEntry> {
        val out = ArrayList<AppEntry>(list.size + 1)
        if (list.size > 1) out += item("music:shuffle:${list.size}", str(R.string.music_shuffle), null, Glyph.SHUFFLE) {
            Music.play(context, list.shuffled(), 0)
        }
        list.forEachIndexed { i, t ->
            val sub = if (byAlbum) t.artist else "${t.artist} · ${t.album}"
            out += item("track:${t.id}", t.title, sub, Glyph.MUSIC) { Music.play(context, list, i) }
        }
        return out
    }

    private fun albumItems(all: List<Track>): List<AppEntry> {
        val groups = all.groupBy { it.albumId }.values.sortedWith { a, b -> collator.compare(a[0].album, b[0].album) }
        val out = groups.map { g ->
            val first = g[0]
            val artist = g.map { it.artist }.distinct().singleOrNull() ?: str(R.string.music_various)
            val tracks = g.sortedWith(compareBy<Track> { it.number }.thenBy { it.title })
            folder("album:${first.albumId}", first.album, "$artist · ${plural(R.plurals.music_n_tracks, g.size)}", Glyph.ALBUM) {
                trackItems(tracks, byAlbum = true)
            }.also { it.icon = covers[first.albumId] ?: it.icon }
        }
        loadCovers(out.zip(groups.map { it[0].albumId }))
        return out
    }

    private fun artistItems(all: List<Track>): List<AppEntry> =
        all.groupBy { it.artist }.entries.sortedWith { a, b -> collator.compare(a.key, b.key) }.map { (artist, g) ->
            val tracks = g.sortedWith(compareBy<Track>({ it.album }, { it.number }, { it.title }))
            folder("artist:$artist", artist, plural(R.plurals.music_n_tracks, g.size), Glyph.ARTIST) {
                trackItems(tracks, byAlbum = false)
            }
        }

    /** «Сейчас играет»: кнопки проигрывателя (для геймпада), затем очередь — тап переходит к треку. */
    fun nowItems(): List<AppEntry> {
        val t = Music.current ?: return emptyList()
        val out = ArrayList<AppEntry>(Music.queue.size + 4)
        out += if (Music.playing) {
            item("music:toggle", str(R.string.music_pause), t.title, Glyph.MEDIA_PAUSE) { Music.toggle() }
        } else {
            item("music:toggle", str(R.string.music_play), t.title, Glyph.MEDIA_PLAY) { Music.toggle() }
        }
        out += item("music:next", str(R.string.music_next), Music.queue.getOrNull(Music.index + 1)?.title, Glyph.MEDIA_NEXT) { Music.next() }
        out += item("music:prev", str(R.string.music_prev), null, Glyph.MEDIA_PREV) { Music.previous() }
        out += item("music:stop", str(R.string.music_stop), null, Glyph.STOP) { Music.stop() }
        Music.queue.forEachIndexed { i, q ->
            val sub = if (i == Music.index) str(R.string.music_playing) + " · " + q.artist else q.artist
            out += item("queue:$i", q.title, sub, if (i == Music.index) Glyph.WAVE else Glyph.MUSIC) { Music.jump(i) }
        }
        return out
    }

    private fun tracks(): List<Track> {
        val cols = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
        )
        val unknown = str(R.string.music_unknown)
        val out = ArrayList<Track>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val artist = c.getString(2)?.takeIf { it.isNotBlank() && it != MediaStore.UNKNOWN_STRING } ?: unknown
                    out += Track(
                        c.getLong(0), c.getString(1)?.takeIf { it.isNotBlank() } ?: "—", artist,
                        c.getString(3)?.takeIf { it.isNotBlank() } ?: unknown, c.getLong(4), c.getLong(5), c.getInt(6),
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

    /** Обложка квадратом со скруглением, в поле значка — как у значков приложений. */
    private fun cover(albumId: Long): Bitmap? = runCatching {
        val src = context.contentResolver.loadThumbnail(Music.albumUri(albumId), Size(iconPx, iconPx), null)
        val pad = iconPx / 10f
        val out = Bitmap.createBitmap(iconPx, iconPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val r = RectF(pad, pad, iconPx - pad, iconPx - pad)
        canvas.clipPath(Path().apply { addRoundRect(r, iconPx * 0.12f, iconPx * 0.12f, Path.Direction.CW) })
        canvas.drawBitmap(src, null, r, Paint(Paint.FILTER_BITMAP_FLAG))
        src.recycle()
        out.copy(Bitmap.Config.HARDWARE, false)?.also { out.recycle() } ?: out
    }.getOrNull()

    private fun folder(key: String, label: String, sub: String?, glyph: Glyph, open: () -> List<AppEntry>): AppEntry =
        AppEntry(key, label, null, null, null, SPECIAL, sub, children = open).also { it.icon = glyph(glyph) }

    private fun item(key: String, label: String, sub: String?, glyph: Glyph, act: (() -> Unit)?): AppEntry =
        AppEntry(key, label, null, null, null, SPECIAL, sub, action = act ?: {}).also { it.icon = glyph(glyph) }

    // Значки зависят от оформления (в Nothing — плоские): ключ с ним.
    private fun glyph(glyph: Glyph): Bitmap = glyphs.getOrPut("${XmbSettings.look}/$glyph") {
        val pad = iconPx / 10
        GlyphArt.icon(context, glyph, iconPx - pad * 2, pad)
    }

    private fun str(id: Int): String = context.getString(id)

    private fun plural(id: Int, n: Int): String = context.resources.getQuantityString(id, n, n)

    companion object {
        /** Ключ пункта «Сейчас играет» — его уровень пересобирается при смене трека. */
        const val NOW = "music:now"
        /** special у всех пунктов музыки: не приложения — без меню, поиска и «Частых». */
        const val SPECIAL = "music"
    }
}
