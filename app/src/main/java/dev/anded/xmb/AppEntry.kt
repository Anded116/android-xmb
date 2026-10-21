package dev.anded.xmb

import android.content.ComponentName
import android.content.pm.LauncherActivityInfo
import android.graphics.Bitmap
import android.os.UserHandle

/** Пункт колонки: приложение или служебный пункт раздела «Настройки» (special != null). */
class AppEntry(
    val key: String,
    val label: String,
    val component: ComponentName?,
    val user: UserHandle?,
    val info: LauncherActivityInfo?,
    val special: String? = null,
    val subtitle: String? = null,
) {
    @Volatile var icon: Bitmap? = null

    val keys: AppSearch.Keys by lazy { AppSearch.Keys(label, component?.packageName.orEmpty()) }

    // Подписи, обрезанные под ширину экрана; пересчитываются, когда меняется ширина.
    var shown: String = label
    var shownSub: String? = subtitle
    var shownFor = -1f
}
