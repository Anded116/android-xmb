package dev.anded.xmb

import android.graphics.Path
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/** Свои формы цветных значков, номера — как в XmbSettings.SHAPE_NAMES. */
object IconShapes {

    fun path(shape: Int, size: Float): Path = when (shape) {
        1 -> squircle(size)
        2 -> Path().apply { addRoundRect(0f, 0f, size, size, size * 0.22f, size * 0.22f, Path.Direction.CW) }
        else -> teardrop(size)
    }

    /** Суперэллипс |x|⁵ + |y|⁵ = 1: прямее стороны и мягче углы, чем у скруглённого квадрата. */
    private fun squircle(size: Float): Path {
        val r = size / 2
        val p = Path()
        for (i in 0 until STEPS) {
            val a = 2 * Math.PI * i / STEPS
            val c = cos(a)
            val s = sin(a)
            val x = r + r * sign(c) * abs(c).pow(2.0 / 5).toFloat()
            val y = r + r * sign(s) * abs(s).pow(2.0 / 5).toFloat()
            if (i == 0) p.moveTo(x.toFloat(), y.toFloat()) else p.lineTo(x.toFloat(), y.toFloat())
        }
        p.close()
        return p
    }

    /** Капля: круг с почти острым правым нижним углом, как вариант формы у Pixel. */
    private fun teardrop(size: Float): Path {
        val r = size / 2
        val small = size * 0.12f
        return Path().apply {
            addRoundRect(
                0f, 0f, size, size,
                floatArrayOf(r, r, r, r, small, small, r, r),
                Path.Direction.CW,
            )
        }
    }

    private const val STEPS = 96
}
