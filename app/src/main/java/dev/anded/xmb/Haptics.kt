package dev.anded.xmb

import android.os.Build
import android.view.HapticFeedbackConstants

/**
 * Отклик вибромотора по событиям XMB. С Android 14 есть «сегментные» тики для прокрутки —
 * они мягче и короче старых; на более старых — ближайшие прежние константы.
 */
object Haptics {
    private val U = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /** Пункт колонки прошёл через место выбранного — как щелчок колёсика. */
    val ITEM = if (U) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK

    /** Раздел сменился, буква быстрой прокрутки сменилась. */
    val SECTION = if (U) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK

    /** Кнопка карточки, пункт меню. */
    const val TAP = HapticFeedbackConstants.VIRTUAL_KEY

    /** Запуск приложения. */
    const val LAUNCH = HapticFeedbackConstants.CONFIRM
}
