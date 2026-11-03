package dev.anded.xmb

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.Surface

/**
 * Поворот экрана по кнопке: портрет ⇄ альбом. Автоповорот выключается и экран встаёт в альбом
 * левой гранью вниз (USER_ROTATION = ROTATION_90). Обратно — портрет и автоповорот, если он был включён.
 * Нужно разрешение «Изменение системных настроек». Приложения, закреплённые в портрете, не повернутся.
 */
class Rotation(private val context: Context) {
    private val prefs = context.getSharedPreferences("xmb", Context.MODE_PRIVATE)

    /** false — разрешения нет, открыта страница, где его выдают. */
    fun toggle(): Boolean {
        if (!Settings.System.canWrite(context)) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }
        val cr = context.contentResolver
        val auto = Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
        val rot = Settings.System.getInt(cr, Settings.System.USER_ROTATION, Surface.ROTATION_0)
        if (!auto && (rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270)) {
            Settings.System.putInt(cr, Settings.System.USER_ROTATION, Surface.ROTATION_0)
            if (prefs.getBoolean(PREF_AUTO, false)) Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1)
        } else {
            prefs.edit().putBoolean(PREF_AUTO, auto).apply()
            Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0)
            Settings.System.putInt(cr, Settings.System.USER_ROTATION, Surface.ROTATION_90)
        }
        return true
    }

    private companion object {
        const val PREF_AUTO = "rotation_restore_auto"
    }
}
