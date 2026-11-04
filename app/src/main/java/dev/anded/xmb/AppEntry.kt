package dev.anded.xmb

import android.content.ComponentName
import android.content.pm.LauncherActivityInfo
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.os.UserHandle

/** Пункт колонки: приложение, служебный пункт раздела «Настройки» (special != null) или ярлык для поиска. */
class AppEntry(
    val key: String,
    val label: String,
    val component: ComponentName?,
    val user: UserHandle?,
    val info: LauncherActivityInfo?,
    val special: String? = null,
    val subtitle: String? = null,
    /** Ярлык приложения (чат, действие); subtitle тогда — название приложения. */
    val shortcut: ShortcutInfo? = null,
    /** Папка: пункт открывает вложенный список (альбом → треки), список строится при входе. */
    val children: (() -> List<AppEntry>)? = null,
    /** Своё действие вместо запуска приложения: трек, кнопка проигрывателя. */
    val action: (() -> Unit)? = null,
    /** Миниатюра (фото, видео): грузится в фоне, когда пункт показался на экране, — см. Thumbs. */
    val thumb: (() -> Bitmap?)? = null,
) {
    @Volatile var icon: Bitmap? = null
    var thumbAsked = false

    val keys: AppSearch.Keys by lazy { AppSearch.Keys(label, component?.packageName.orEmpty()) }

    // Подписи, обрезанные под ширину экрана; пересчитываются, когда меняется ширина.
    var shown: String = label
    var shownSub: String? = subtitle
    var shownFor = -1f
    var shownW = 0f
}
