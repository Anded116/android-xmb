package dev.anded.xmb

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.util.Size
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Снимок или ролик из MediaStore; date — мс, duration — мс (у фото 0). */
class Shot(val uri: Uri, val name: String, val bucketId: Long, val bucket: String, val date: Long, val duration: Long, val mime: String)

/**
 * Фото и видео на устройстве в разделах «Фото» и «Видео» (LNC-A0012, LNC-A0013): «Все фото» и папки, как их
 * раскладывает система (Камера, Скриншоты, …), свежие сверху. Фото открываются своим просмотром, видео — системным
 * плеером. Только в сборке full (Edition.GALLERY): Play пускает READ_MEDIA_IMAGES/VIDEO только галереям.
 * roots() зовётся из потока сборки списка, остальное — из главного.
 */
class Gallery(
    private val context: Context,
    private val iconPx: Int,
    private val video: Boolean,
    private val onAsk: () -> Unit,
    private val onOpen: (List<Shot>, Int) -> Unit,
) {
    private val glyph = if (video) Glyph.VIDEO else Glyph.PHOTO
    private val glyphs = ConcurrentHashMap<String, Bitmap>()
    private val dateFormat = SimpleDateFormat(DateFormat.getBestDateTimePattern(Locale.getDefault(), "d MMMM yyyy HH:mm"), Locale.getDefault())

    fun roots(): List<AppEntry> {
        if (!Edition.GALLERY) return emptyList()
        if (!granted(context)) {
            val label = str(if (video) R.string.video_library else R.string.photo_library)
            return listOf(item("$kind:ask", label, str(R.string.gallery_allow), onAsk))
        }
        val all = query()
        if (all.isEmpty()) {
            return listOf(item("$kind:none", str(if (video) R.string.video_none else R.string.photo_none), str(R.string.gallery_none_sub), null))
        }
        val out = ArrayList<AppEntry>()
        out += folder("$kind:all", str(if (video) R.string.video_all else R.string.photo_all), count(all.size), all[0]) { items(query()) }
        // Папки — по свежести последнего снимка: Камера и Скриншоты обычно первые.
        for (g in all.groupBy { it.bucketId }.values) {
            val id = g[0].bucketId
            out += folder("$kind:bucket:$id", g[0].bucket, count(g.size), g[0]) { items(query().filter { it.bucketId == id }) }
        }
        return out
    }

    private fun items(list: List<Shot>): List<AppEntry> = list.mapIndexed { i, s ->
        val sub = if (video) DateUtils.formatElapsedTime(s.duration / 1000) + " · " + s.name else s.name
        AppEntry("$kind:${s.uri}", dateFormat.format(Date(s.date)), null, null, null, SPECIAL, sub,
            action = { onOpen(list, i) }, thumb = { thumb(s) }).also { it.icon = placeholder() }
    }

    private fun query(): List<Shot> {
        val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val cols = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME, MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DURATION, MediaStore.MediaColumns.MIME_TYPE,
        )
        val out = ArrayList<Shot>()
        runCatching {
            context.contentResolver.query(base, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val taken = if (c.isNull(4)) 0L else c.getLong(4)
                    out += Shot(
                        ContentUris.withAppendedId(base, c.getLong(0)), c.getString(1).orEmpty(), c.getLong(2),
                        c.getString(3)?.takeIf { it.isNotBlank() } ?: "—", if (taken > 0) taken else c.getLong(5) * 1000,
                        if (c.isNull(6)) 0L else c.getLong(6), c.getString(7) ?: if (video) "video/*" else "image/*",
                    )
                }
            }
        }
        out.sortByDescending { it.date }
        return out
    }

    private fun thumb(s: Shot): Bitmap = Thumbs.tile(context.contentResolver.loadThumbnail(s.uri, Size(iconPx, iconPx), null), iconPx)

    private fun folder(key: String, label: String, sub: String, cover: Shot, open: () -> List<AppEntry>): AppEntry =
        AppEntry(key, label, null, null, null, SPECIAL, sub, children = open, thumb = { thumb(cover) })
            .also { it.icon = placeholder() }

    private fun item(key: String, label: String, sub: String, act: (() -> Unit)?): AppEntry =
        AppEntry(key, label, null, null, null, SPECIAL, sub, action = act ?: {}).also { it.icon = placeholder() }

    // Значок раздела, пока миниатюра не загрузилась; в Nothing — плоский, поэтому ключ с оформлением.
    private fun placeholder(): Bitmap = glyphs.getOrPut("${XmbSettings.look}/$glyph") {
        val pad = iconPx / 10
        GlyphArt.icon(context, glyph, iconPx - pad * 2, pad)
    }

    private val kind get() = if (video) "video" else "photo"

    private fun count(n: Int): String =
        context.resources.getQuantityString(if (video) R.plurals.video_n else R.plurals.photo_n, n, n)

    private fun str(id: Int): String = context.getString(id)

    companion object {
        const val SPECIAL = "gallery"
        val PERMISSIONS = arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)

        fun granted(context: Context): Boolean =
            PERMISSIONS.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }
}
