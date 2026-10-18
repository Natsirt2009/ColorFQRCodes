package org.example.net.natsirt.colorfrqcodes

import java.awt.Color
import kotlin.math.floor

object Palette {
    fun make(bpp: Int): List<Color> {
        require(bpp in 1..8) { "bpp must be between 1 and 8." }
        val count = 1 shl bpp
        val colors = ArrayList<Color>(count)
        colors += Color(20, 20, 24)
        colors += Color(245, 245, 240)
        for (index in 2 until count) {
            val hue = (index - 2).toDouble() / maxOf(1, count - 2)
            val saturation = 0.78
            val value = 0.92
            val sector = hue * 6.0
            val chroma = value * saturation
            val secondary = chroma * (1.0 - kotlin.math.abs(sector % 2.0 - 1.0))
            val offset = value - chroma
            val (red, green, blue) = when (floor(sector).toInt()) {
                0 -> Triple(chroma, secondary, 0.0)
                1 -> Triple(secondary, chroma, 0.0)
                2 -> Triple(0.0, chroma, secondary)
                3 -> Triple(0.0, secondary, chroma)
                4 -> Triple(secondary, 0.0, chroma)
                else -> Triple(chroma, 0.0, secondary)
            }
            colors += Color(
                ((red + offset) * 255.0).roundLikePython(),
                ((green + offset) * 255.0).roundLikePython(),
                ((blue + offset) * 255.0).roundLikePython()
            )
        }
        return colors
    }

    private fun Double.roundLikePython(): Int {
        val lower = floor(this)
        val fraction = this - lower
        return when {
            fraction < 0.5 -> lower.toInt()
            fraction > 0.5 -> lower.toInt() + 1
            lower.toInt() % 2 == 0 -> lower.toInt()
            else -> lower.toInt() + 1
        }
    }
}/*package org.example.net.natsirt.colorfrqcodes

import java.awt.Color

class Palette(val colors: Array<Color>) {
    companion object {
        fun forBpp(bpp: Int): Palette {
            require(bpp in 1..8) { "bpp must be between 1 and 8" }
            val count = 1 shl bpp
            val colors = Array(1 shl bpp) { i ->
                when (i) {
                    0 -> Color(20, 20, 24)
                    1 -> Color(245, 245, 240)
                    else -> {
                        val hue = (i - 2).toDouble() / maxOf(1, count - 2)
                        val saturation = 0.78
                        val value = 0.92
                        HSVToRGB(hue, saturation, value)
                    }
                }
            }
            return Palette(colors)
        }
    }
}

private fun HSVToRGB(hue: Double, saturation: Double, value: Double): Color {
    val h = ((hue % 1.0) * 6.0)
    val sector = h.toInt()
    val fraction = h - sector
    val p = value * (1.0 - saturation)
    val q = value * (1.0 - saturation * fraction)
    val t = value * (1.0 - saturation * (1.0 - fraction))
    val (r, g, b) = when (sector % 6) {
        0 -> Triple(value, t, p)
        1 -> Triple(q, value, p)
        2 -> Triple(p, value, t)
        3 -> Triple(p, q, value)
        4 -> Triple(t, p, value)
        else -> Triple(value, p, q)
    }
    return Color(
        Math.rint(r * 255).toInt(),
        Math.rint(g * 255).toInt(),
        Math.rint(b * 255).toInt()
    )
}*/