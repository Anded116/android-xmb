package dev.anded.xmb

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import kotlin.math.min

/**
 * Миниатюры длинных списков (фото, видео): грузятся, только когда пункт показался на экране, свежие запросы —
 * первыми, а пролистанные мимо отбрасываются. Загруженных держится не больше MAX: самые старые возвращают
 * значок-заглушку и при новом показе загрузятся снова. request и выгрузка — в главном потоке.
 */
class Thumbs(private val onLoaded: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<AppEntry>()
    private val loaded = ArrayDeque<AppEntry>()
    private val placeholders = HashMap<AppEntry, Bitmap?>()
    private val lock = Object()

    private val worker = Thread({
        while (true) {
            val e = synchronized(lock) {
                while (queue.isEmpty()) lock.wait()
                queue.removeFirst()
            }
            val bmp = runCatching { e.thumb?.invoke() }.getOrNull()
            main.post { done(e, bmp) }
        }
    }, "xmb-thumbs").apply {
        isDaemon = true
        start()
    }

    fun request(e: AppEntry) {
        synchronized(lock) {
            queue.addFirst(e)
            while (queue.size > QUEUE) queue.removeLast().thumbAsked = false
            lock.notify()
        }
    }

    private fun done(e: AppEntry, bmp: Bitmap?) {
        if (bmp == null) return
        if (e !in placeholders) placeholders[e] = e.icon
        e.icon = bmp
        loaded.addLast(e)
        while (loaded.size > MAX) {
            val old = loaded.removeFirst()
            old.icon = placeholders.remove(old)
            old.thumbAsked = false
        }
        onLoaded()
    }

    companion object {
        private const val QUEUE = 24
        private const val MAX = 120

        /** Квадрат со скруглением по центру картинки, в поле значка — как значки приложений. */
        fun tile(src: Bitmap, iconPx: Int): Bitmap {
            val pad = iconPx / 10f
            val out = Bitmap.createBitmap(iconPx, iconPx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val r = RectF(pad, pad, iconPx - pad, iconPx - pad)
            canvas.clipPath(Path().apply { addRoundRect(r, iconPx * 0.12f, iconPx * 0.12f, Path.Direction.CW) })
            val side = min(src.width, src.height)
            val crop = Rect((src.width - side) / 2, (src.height - side) / 2, (src.width + side) / 2, (src.height + side) / 2)
            canvas.drawBitmap(src, crop, r, Paint(Paint.FILTER_BITMAP_FLAG))
            src.recycle()
            return out.copy(Bitmap.Config.HARDWARE, false)?.also { out.recycle() } ?: out
        }
    }
}
