package dev.anded.xmb

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView

/**
 * Свой простой плеер для роликов из системного выбора (store, LNC-A0019): чужие плееры адреса выбора понимают
 * не всегда — Google Фото показывает чёрный экран. VideoView и системная панель MediaController (тап — показать);
 * A — пауза, ←/→ — на 10 с, «назад» и B — закрыть.
 */
class VideoPlayer(context: Context) : FrameLayout(context) {

    var open = false
        private set

    private val video = VideoView(context)
    // Панель забирает «назад», пока видна, и только прячется сама; здесь «назад» сразу закрывает плеер.
    private val controls = object : MediaController(context) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
            if (event.action == KeyEvent.ACTION_UP) close()
            return true
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        visibility = GONE
        addView(video, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        video.setMediaController(controls)
        controls.setAnchorView(this)
        video.setOnPreparedListener {
            video.start()
            controls.show()
        }
        video.setOnCompletionListener { controls.show(0) }
        video.setOnErrorListener { _, _, _ ->
            close()
            Toast.makeText(context, R.string.video_no_player, Toast.LENGTH_SHORT).show()
            true
        }
    }

    fun show(uri: Uri) {
        open = true
        visibility = VISIBLE
        video.setVideoURI(uri)
    }

    fun close() {
        if (!open) return
        open = false
        controls.hide()
        video.stopPlayback()
        visibility = GONE
    }

    /** Клавиши, пока плеер открыт; B не перехватывается — система превращает её в «назад». */
    fun onKey(e: KeyEvent): Boolean {
        if (!open) return false
        val code = e.keyCode
        if (code !in KEYS) return false
        if (e.action != KeyEvent.ACTION_DOWN) return true
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> video.seekTo((video.currentPosition - SEEK_MS).coerceAtLeast(0))
            KeyEvent.KEYCODE_DPAD_RIGHT -> video.seekTo((video.currentPosition + SEEK_MS).coerceAtMost(video.duration))
            else -> if (video.isPlaying) video.pause() else video.start()
        }
        controls.show()
        return true
    }

    private companion object {
        const val SEEK_MS = 10_000
        val KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        )
    }
}
