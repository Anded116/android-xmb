package dev.anded.xmb

import android.app.ActivityOptions
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Доступ к уведомлениям (выдаёт пользователь): число уведомлений по приложениям — для значков на иконках,
 * и активный плеер — для карточки «Сейчас». Колбэки — в главном потоке.
 */
class XmbNotifications : NotificationListenerService() {

    override fun onListenerConnected() {
        refresh()
        NowPlaying.attach(this)
    }

    override fun onListenerDisconnected() {
        Notices.update(emptyMap())
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = refresh()

    override fun onNotificationRemoved(sbn: StatusBarNotification) = refresh()

    // Считаем то, что пользователь может смахнуть: без постоянных (музыка, загрузки) и без сводок групп.
    private fun refresh() {
        val counts = HashMap<String, Int>()
        for (n in runCatching { activeNotifications }.getOrNull().orEmpty()) {
            if (n.isOngoing || !n.isClearable) continue
            if (n.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) continue
            counts[n.packageName] = (counts[n.packageName] ?: 0) + 1
        }
        Notices.update(counts)
    }
}

/** Число уведомлений по пакетам; кто рисует значки — подписывается. Только главный поток. */
object Notices {
    private var counts: Map<String, Int> = emptyMap()
    private val listeners = ArrayList<() -> Unit>()

    fun count(pkg: String?): Int = if (pkg == null || pkg in XmbSettings.badgeOff) 0 else counts[pkg] ?: 0

    fun update(map: Map<String, Int>) {
        counts = map
        changed()
    }

    fun changed() = listeners.forEach { it() }

    fun listen(l: () -> Unit) {
        listeners += l
    }

    fun unlisten(l: () -> Unit) {
        listeners -= l
    }
}

/** Что играет: заголовок, исполнитель, пауза/воспроизведение и кнопки. Нужен тот же доступ к уведомлениям. */
object NowPlaying {
    var title: String? = null
        private set
    var artist: String? = null
        private set
    var playing = false
        private set
    var pkg: String? = null
        private set

    private var controller: MediaController? = null
    private var manager: MediaSessionManager? = null

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = read()
        override fun onPlaybackStateChanged(state: PlaybackState?) = pick(manager?.let { m -> sessions(m) }.orEmpty())
        override fun onSessionDestroyed() = pick(manager?.let { m -> sessions(m) }.orEmpty())
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { pick(it.orEmpty()) }
    private var component: ComponentName? = null

    fun attach(context: Context) {
        if (manager != null) return
        val m = context.getSystemService(MediaSessionManager::class.java) ?: return
        val cn = ComponentName(context, XmbNotifications::class.java)
        runCatching {
            m.addOnActiveSessionsChangedListener(sessionsListener, cn)
            manager = m
            component = cn
            pick(m.getActiveSessions(cn))
        }
    }

    private fun sessions(m: MediaSessionManager): List<MediaController> =
        runCatching { m.getActiveSessions(component) }.getOrNull().orEmpty()

    /**
     * Играющий, если есть, иначе последний на паузе. Сессии в ошибке и остановленные не в счёт:
     * системные (например, com.nothing.hearthstone) висят «активными» с ERROR и без своего экрана.
     */
    private fun pick(list: List<MediaController>) {
        val live = list.filter { it.playbackState?.state in LIVE_STATES }
        val next = live.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: live.firstOrNull()
        if (next?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = next
            next?.registerCallback(callback)
        }
        read()
    }

    private fun read() {
        val c = controller?.takeIf { it.playbackState?.state in LIVE_STATES }
        val md = c?.metadata
        title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
        artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        playing = c?.playbackState?.state == PlaybackState.STATE_PLAYING
        pkg = c?.packageName
        Notices.changed()
    }

    fun toggle() {
        val t = controller?.transportControls ?: return
        if (playing) t.pause() else t.play()
    }

    fun next() = controller?.transportControls?.skipToNext()

    fun previous() = controller?.transportControls?.skipToPrevious()

    /**
     * Открыть плеер — его экран из сессии, а если сессия его не дала — само приложение.
     * С Android 14 PendingIntent запускает активность от имени отправителя, только если тот это разрешил явно.
     */
    fun open(context: Context) {
        val pi = controller?.sessionActivity
        if (pi != null) {
            val options = ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
            if (runCatching { pi.send(context, 0, null, null, null, null, options) }.isSuccess) return
        }
        val launch = pkg?.let { context.packageManager.getLaunchIntentForPackage(it) } ?: return
        runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private val LIVE_STATES = setOf(
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED, PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_CONNECTING, PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING,
        PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
    )
}
