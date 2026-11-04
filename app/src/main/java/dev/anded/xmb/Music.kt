package dev.anded.xmb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Size
import java.util.concurrent.Executors

/**
 * Трек из MediaStore; number — номер в альбоме (у многодисковых — 1001, 2001…), path — ключ оценок и плейлистов
 * (MusicData): от Music/HiBy, как на карте плеера, или от корня памяти со «/» впереди.
 */
class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val duration: Long,
    val number: Int,
    val path: String,
) {
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
}

/**
 * Свой проигрыватель раздела «Музыка» (LNC-A0011): очередь, MediaPlayer, фокус звука и MediaSession — для наушников,
 * экрана блокировки, шторки и карточки «Сейчас». Живёт в процессе лаунчера; MusicService держит процесс на переднем
 * плане, пока играет. Только главный поток.
 */
object Music {
    var queue: List<Track> = emptyList()
        private set
    var index = -1
        private set
    val current: Track? get() = queue.getOrNull(index)
    var playing = false
        private set
    var art: Bitmap? = null
        private set

    var service: MusicService? = null
    private var app: Context? = null
    private var player: MediaPlayer? = null
    private var prepared = false
    private var session: MediaSession? = null
    private var focus: AudioFocusRequest? = null
    private var resumeOnGain = false
    private var noisyOn = false
    private val listeners = ArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val artLoader = Executors.newSingleThreadExecutor { Thread(it, "xmb-art") }

    // Статистика, как на плеере: прослушан — отыграл половину или 4 минуты; пропущен — ушли раньше 30 с, но не
    // в первые 2 (так листают очередь). Время — реальное, перемотка не в счёт.
    private var heardTrack: Track? = null
    private var heard = 0
    private var heardPlayed = false
    private val tick = object : Runnable {
        override fun run() {
            val t = heardTrack ?: return
            if (!playing || t !== current) return
            heard++
            val need = if (t.duration > 0) minOf(240L, t.duration / 2000) else 240L
            if (!heardPlayed && heard >= need) {
                heardPlayed = true
                MusicData.played(t)
            }
            main.postDelayed(this, 1000)
        }
    }

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    fun listen(l: () -> Unit) {
        listeners += l
    }

    fun unlisten(l: () -> Unit) {
        listeners -= l
    }

    /** Новая очередь с трека start и сразу играть. */
    fun play(context: Context, tracks: List<Track>, start: Int) {
        if (tracks.isEmpty()) return
        val ctx = context.applicationContext
        app = ctx
        queue = tracks
        session().isActive = true
        load(start.coerceIn(0, tracks.lastIndex))
        ctx.startForegroundService(Intent(ctx, MusicService::class.java))
    }

    fun toggle() {
        when {
            current == null -> return
            playing -> pause()
            prepared -> start()
            else -> load(index)
        }
    }

    fun next() = jump(index + 1)

    /** «Играть следующим»: сразу за текущим; ничего не играет — играть сразу. */
    fun playNext(context: Context, tracks: List<Track>) {
        if (current == null) return play(context, tracks, 0)
        queue = queue.toMutableList().apply { addAll(index + 1, tracks) }
        changed()
    }

    /** «В конец очереди»; ничего не играет — играть сразу. */
    fun enqueue(context: Context, tracks: List<Track>) {
        if (current == null) return play(context, tracks, 0)
        queue = queue + tracks
        changed()
    }

    /** В начало трека, а если он только начался — на предыдущий. */
    fun previous() {
        val p = player
        if (p != null && prepared && p.currentPosition > RESTART_MS) {
            p.seekTo(0)
            changed()
        } else {
            jump(index - 1)
        }
    }

    fun jump(i: Int) {
        if (i in queue.indices) load(i)
    }

    /** Остановить и забыть очередь: уведомление уходит, карточка гаснет. */
    fun stop() {
        heardEnd(byEnd = true)   // остановить — не значит пропустить
        release()
        abandonFocus()
        queue = emptyList()
        index = -1
        art = null
        session?.run {
            isActive = false
            release()
        }
        session = null
        service?.stop()
        changed()
    }

    private fun load(i: Int) {
        val ctx = app ?: return
        heardEnd(byEnd = false)
        release()
        index = i
        val track = queue[i]
        heardTrack = track
        heard = 0
        heardPlayed = false
        art = null
        loadArt(ctx, track)
        val p = MediaPlayer()
        player = p
        p.setAudioAttributes(attrs)
        p.setWakeMode(ctx, PowerManager.PARTIAL_WAKE_LOCK)
        p.setOnPreparedListener {
            prepared = true
            start()
        }
        p.setOnCompletionListener {
            heardEnd(byEnd = true)
            if (index < queue.lastIndex) {
                load(index + 1)
            } else {
                playing = false
                p.seekTo(0)
                setNoisy(false)
                changed()
            }
        }
        // Битый файл — дальше по очереди, а не тишина.
        p.setOnErrorListener { _, _, _ ->
            if (index < queue.lastIndex) main.post { load(index + 1) } else main.post { stop() }
            true
        }
        runCatching {
            p.setDataSource(ctx, track.uri)
            p.prepareAsync()
        }.onFailure {
            p.setOnErrorListener(null)
            if (index < queue.lastIndex) main.post { load(index + 1) }
        }
        changed()
    }

    private fun start() {
        val p = player ?: return
        if (!requestFocus()) return
        p.start()
        playing = true
        main.removeCallbacks(tick)
        main.postDelayed(tick, 1000)
        setNoisy(true)
        changed()
    }

    private fun pause(abandon: Boolean = true) {
        val p = player ?: return
        if (prepared && p.isPlaying) p.pause()
        playing = false
        main.removeCallbacks(tick)
        setNoisy(false)
        if (abandon) abandonFocus()
        changed()
    }

    private fun release() {
        player?.run {
            setOnErrorListener(null)
            setOnCompletionListener(null)
            release()
        }
        player = null
        prepared = false
        playing = false
        main.removeCallbacks(tick)
        setNoisy(false)
    }

    private fun heardEnd(byEnd: Boolean) {
        val t = heardTrack ?: return
        if (!byEnd && !heardPlayed && heard in 2 until 30) MusicData.skipped(t)
        heardTrack = null
    }

    private fun requestFocus(): Boolean {
        val am = app?.getSystemService(AudioManager::class.java) ?: return false
        val req = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(::onFocus, main)
            .build()
            .also { focus = it }
        return am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        val req = focus ?: return
        app?.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(req)
        resumeOnGain = false
    }

    // Звонок или навигатор — пауза и продолжение, другой плеер — пауза насовсем; приглушение система делает сама.
    private fun onFocus(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (playing) {
                resumeOnGain = true
                pause(abandon = false)
            }
            AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnGain) {
                resumeOnGain = false
                start()
            }
        }
    }

    // Наушники выдернули — пауза, а не музыка в динамик.
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = pause()
    }

    private fun setNoisy(on: Boolean) {
        val ctx = app ?: return
        if (on == noisyOn) return
        noisyOn = on
        if (on) ctx.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)) else ctx.unregisterReceiver(noisy)
    }

    private fun loadArt(ctx: Context, track: Track) {
        artLoader.execute {
            val bmp = runCatching {
                ctx.contentResolver.loadThumbnail(albumUri(track.albumId), Size(ART_PX, ART_PX), null)
            }.getOrNull() ?: return@execute
            main.post {
                if (current !== track) return@post
                art = bmp
                changed()
            }
        }
    }

    private fun session(): MediaSession {
        session?.let { return it }
        val ctx = app!!
        val open = Intent(ctx, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_KEY, MainActivity.KEY_MUSIC)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return MediaSession(ctx, "xmb").also {
            it.setCallback(callback, main)
            it.setPlaybackToLocal(attrs)
            it.setSessionActivity(
                PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
            session = it
        }
    }

    val token: MediaSession.Token? get() = session?.sessionToken

    /** Пакет лаунчера — для карточки «Сейчас», когда чужие сессии не видны. */
    val pkg: String? get() = app?.packageName

    // Кнопки наушников, экран блокировки, шторка; MediaSession сам переводит медиа-клавиши в эти вызовы.
    private val callback = object : MediaSession.Callback() {
        override fun onPlay() {
            if (!playing) toggle()
        }
        override fun onPause() = pause()
        override fun onSkipToNext() = next()
        override fun onSkipToPrevious() = previous()
        override fun onSkipToQueueItem(id: Long) = jump(id.toInt())
        override fun onStop() = stop()
        override fun onSeekTo(pos: Long) {
            if (!prepared) return
            player?.seekTo(pos.toInt())
            changed()
        }
    }

    private fun changed() {
        publish()
        service?.update()
        NowPlaying.refresh()
        listeners.toList().forEach { it() }
    }

    private fun publish() {
        val s = session ?: return
        val t = current ?: return
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, t.album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, t.duration)
                .apply { art?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
                .build(),
        )
        val position = if (prepared) player?.currentPosition?.toLong() ?: 0L else 0L
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(ACTIONS)
                .setActiveQueueItemId(index.toLong())
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, position, 1f)
                .build(),
        )
    }

    fun albumUri(albumId: Long): Uri = ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, albumId)

    private const val RESTART_MS = 3000
    private const val ART_PX = 512
    private const val ACTIONS = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
        PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP
}
