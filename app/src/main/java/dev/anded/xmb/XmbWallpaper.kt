package dev.anded.xmb

import android.service.wallpaper.WallpaperService
import android.view.Choreographer
import android.view.SurfaceHolder

/**
 * Живые обои XMB: тот же фон, что у лаунчера. Пока они стоят, окно лаунчера прозрачное — при уходе
 * в многозадачность и при запуске приложений сворачивается только колонка, а фон остаётся на месте.
 */
class XmbWallpaper : WallpaperService() {

    override fun onCreateEngine(): Engine = XmbEngine()

    private inner class XmbEngine : Engine(), Choreographer.FrameCallback {
        private val renderer = BgRenderer(this@XmbWallpaper)
        private val choreographer = Choreographer.getInstance()
        private var visible = false
        private var scheduled = false
        private val waker: () -> Unit = { schedule() }

        // Хотя бы один кадр после появления поверхности — даже замёрзший фон надо нарисовать.
        private var lastFrameDrawn = false

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            XmbSettings.init(this@XmbWallpaper)
            setOffsetNotificationsEnabled(false)
            BgBus.onWake(waker)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            renderer.setSize(width, height)
            renderer.invalidateLayers()
            lastFrameDrawn = false
            schedule()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                renderer.invalidateLayers()
                lastFrameDrawn = false
                schedule()
            }
        }

        override fun onDestroy() {
            BgBus.offWake(waker)
            super.onDestroy()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            choreographer.removeFrameCallback(this)
            scheduled = false
            super.onSurfaceDestroyed(holder)
        }

        private fun schedule() {
            if (scheduled || !visible) return
            // Замер в покое — кадр стоит, пока касание XMB не разбудит (BgBus.onWake).
            if (BgBus.frozen() && lastFrameDrawn) return
            scheduled = true
            // Пока в XMB идёт касание или анимация — каждый кадр, в покое волне хватает ~30 к/с.
            if (BgBus.busy) choreographer.postFrameCallback(this) else choreographer.postFrameCallbackDelayed(this, IDLE_FRAME_MS)
        }

        override fun doFrame(frameTimeNanos: Long) {
            scheduled = false
            if (!visible) return
            val surface = surfaceHolder.surface
            if (surface.isValid) {
                val canvas = runCatching { surface.lockHardwareCanvas() }.getOrNull()
                if (canvas != null) {
                    try {
                        renderer.draw(canvas, advance = !BgBus.frozen())
                        lastFrameDrawn = true
                    } finally {
                        surface.unlockCanvasAndPost(canvas)
                    }
                }
            }
            schedule()
        }
    }

    private companion object {
        const val IDLE_FRAME_MS = 33L
    }
}
