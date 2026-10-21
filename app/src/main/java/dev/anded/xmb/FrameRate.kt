package dev.anded.xmb

import android.os.Build
import android.view.View
import java.lang.reflect.Method

/**
 * Android 15+ сам выбирает частоту для View по категории анимации, и у Nothing OS «высокая» — это 90 Гц.
 * Прямой голос за максимум экрана, пока идёт касание или анимация; в SDK 34 метода нет — через рефлексию.
 */
object FrameRate {
    private val setRequestedFrameRate: Method? = if (Build.VERSION.SDK_INT >= 35) {
        runCatching { View::class.java.getMethod("setRequestedFrameRate", Float::class.javaPrimitiveType) }.getOrNull()
    } else {
        null
    }

    fun vote(view: View, busy: Boolean) {
        val method = setRequestedFrameRate ?: return
        val rate = if (busy) view.display?.supportedModes?.maxOfOrNull { it.refreshRate } ?: return else Float.NaN
        runCatching { method.invoke(view, rate) }
    }
}
