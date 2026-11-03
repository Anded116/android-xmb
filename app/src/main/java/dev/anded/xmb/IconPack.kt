package dev.anded.xmb

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.Drawable
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Пакет значков из Play в формате ADW/Nova: appfilter.xml сопоставляет компоненту приложения картинку,
 * для остальных — подложка, маска и рамка (iconback/iconmask/iconupon) с масштабом исходного значка.
 */
class IconPack(context: Context, val pkg: String) {
    private val res: Resources = context.packageManager.getResourcesForApplication(pkg)
    private val map = HashMap<String, Int>()
    private var back = 0
    private var mask = 0
    private var upon = 0
    private var scale = 1f

    init {
        runCatching { parse() }
    }

    fun drawable(cn: ComponentName, density: Int): Drawable? {
        val id = map[cn.flattenToString()] ?: return null
        return runCatching { res.getDrawableForDensity(id, density, null) }.getOrNull()
    }

    /** Значок не из пакета, вписанный в его подложку; null — у пакета нет подложки. */
    fun compose(original: Drawable, size: Int, density: Int): Bitmap? {
        if (back == 0) return null
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        res.getDrawableForDensity(back, density, null)?.apply { setBounds(0, 0, size, size) }?.draw(canvas)
        val layer = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val lc = Canvas(layer)
        val inset = ((1 - scale) * size / 2).toInt()
        original.setBounds(inset, inset, size - inset, size - inset)
        original.draw(lc)
        if (mask != 0) {
            res.getDrawableForDensity(mask, density, null)?.let { m ->
                val mb = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                m.setBounds(0, 0, size, size)
                m.draw(Canvas(mb))
                lc.drawBitmap(mb, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
                mb.recycle()
            }
        }
        canvas.drawBitmap(layer, 0f, 0f, null)
        layer.recycle()
        if (upon != 0) res.getDrawableForDensity(upon, density, null)?.apply { setBounds(0, 0, size, size) }?.draw(canvas)
        return out
    }

    private fun parse() {
        val id = res.getIdentifier("appfilter", "xml", pkg)
        val parser: XmlPullParser = if (id != 0) {
            res.getXml(id)
        } else {
            XmlPullParserFactory.newInstance().newPullParser().apply { setInput(res.assets.open("appfilter.xml"), "utf-8") }
        }
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "item" -> {
                        val comp = parser.getAttributeValue(null, "component")
                            ?.removePrefix("ComponentInfo{")?.removeSuffix("}")
                            ?.let(ComponentName::unflattenFromString)?.flattenToString()
                        val d = drawableId(parser.getAttributeValue(null, "drawable"))
                        if (comp != null && d != 0) map[comp] = d
                    }
                    "iconback" -> back = drawableId(parser.getAttributeValue(null, "img1"))
                    "iconmask" -> mask = drawableId(parser.getAttributeValue(null, "img1"))
                    "iconupon" -> upon = drawableId(parser.getAttributeValue(null, "img1"))
                    "scale" -> scale = parser.getAttributeValue(null, "factor")?.toFloatOrNull() ?: 1f
                }
            }
            parser.next()
        }
    }

    private fun drawableId(name: String?): Int =
        if (name.isNullOrEmpty()) 0 else res.getIdentifier(name, "drawable", pkg)

    companion object {
        private val ACTIONS = arrayOf("org.adw.launcher.THEMES", "com.novalauncher.THEME")

        /** Установленные пакеты значков: пакет → название. */
        fun installed(context: Context): List<Pair<String, String>> {
            val pm = context.packageManager
            return ACTIONS.flatMap { pm.queryIntentActivities(Intent(it), 0) }
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .distinctBy { it.first }
                .sortedBy { it.second }
        }
    }
}
