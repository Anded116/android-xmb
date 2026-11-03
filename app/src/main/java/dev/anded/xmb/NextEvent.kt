package dev.anded.xmb

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Ближайшее событие календаря на сутки вперёд — для карточки «Сейчас». Нужно разрешение «Календарь». */
class NextEvent(val title: String, val begin: Long, val allDay: Boolean, private val eventId: Long) {

    /** «Сегодня 14:00 · Созвон», «Завтра 09:30 · …», «Весь день · …». */
    fun text(context: Context): String {
        if (allDay) return context.getString(R.string.event_all_day, title)
        val cal = Calendar.getInstance().apply { timeInMillis = begin }
        val today = Calendar.getInstance()
        val day = context.getString(
            if (cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)) R.string.event_today else R.string.event_tomorrow,
        )
        return "$day ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(cal.time)} · $title"
    }

    fun openIntent(): Intent = Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    companion object {
        fun granted(context: Context) =
            context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

        /** Не в главном потоке: запрос к провайдеру календаря. */
        fun query(context: Context): NextEvent? {
            if (!granted(context)) return null
            val now = System.currentTimeMillis()
            val projection = arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.EVENT_ID,
            )
            val cursor = runCatching {
                CalendarContract.Instances.query(context.contentResolver, projection, now, now + DAY_MS)
            }.getOrNull() ?: return null
            cursor.use { c ->
                var best: NextEvent? = null
                while (c.moveToNext()) {
                    val title = c.getString(0)?.takeIf { it.isNotBlank() } ?: continue
                    val begin = c.getLong(1)
                    val end = c.getLong(2)
                    val allDay = c.getInt(3) != 0
                    if (end <= now) continue
                    val e = NextEvent(title, begin, allDay, c.getLong(4))
                    // Событие со временем важнее событий на весь день; среди них — самое раннее.
                    best = when {
                        best == null -> e
                        best.allDay && !allDay -> e
                        !best.allDay && allDay -> best
                        begin < best.begin -> e
                        else -> best
                    }
                }
                return best
            }
        }

        private const val DAY_MS = 24 * 3600 * 1000L
    }
}
