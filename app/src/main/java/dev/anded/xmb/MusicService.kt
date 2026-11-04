package dev.anded.xmb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Держит проигрыватель «Музыки» на переднем плане, пока играет: уведомление с MediaStyle — те же кнопки в шторке
 * и на экране блокировки. На паузе уведомление остаётся, но его можно смахнуть — это «Остановить».
 */
class MusicService : Service() {

    private lateinit var nm: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.music_channel), NotificationManager.IMPORTANCE_LOW),
        )
        Music.service = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Music.stop()
            return START_NOT_STICKY
        }
        // startForegroundService обязывает уйти на передний план сразу, даже если трек ещё готовится.
        val n = notification()
        if (n == null) {
            stop()
            return START_NOT_STICKY
        }
        runCatching { startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) }
        update()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (Music.service === this) Music.service = null
        super.onDestroy()
    }

    fun update() {
        val n = notification() ?: return
        if (Music.playing) {
            runCatching { startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) }
        } else {
            stopForeground(STOP_FOREGROUND_DETACH)
            nm.notify(ID, n)
        }
    }

    fun stop() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        nm.cancel(ID)
        stopSelf()
    }

    private fun notification(): Notification? {
        val t = Music.current ?: return null
        val token = Music.token ?: return null
        val open = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_KEY, MainActivity.KEY_MUSIC)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val stop = Intent(this, MusicService::class.java).setAction(ACTION_STOP)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.xmb_music)
            .setContentTitle(t.title)
            .setContentText(t.artist)
            .setLargeIcon(Music.art)
            .setStyle(Notification.MediaStyle().setMediaSession(token))
            .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setDeleteIntent(PendingIntent.getService(this, 0, stop, PendingIntent.FLAG_IMMUTABLE))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(Music.playing)
            .build()
    }

    private companion object {
        const val CHANNEL = "music"
        const val ID = 1
        const val ACTION_STOP = "dev.anded.xmb.music.STOP"
    }
}
