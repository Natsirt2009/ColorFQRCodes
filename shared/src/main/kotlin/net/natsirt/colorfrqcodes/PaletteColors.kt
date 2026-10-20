package org.example.net.natsirt.colorfrqcodes

import kotlin.math.floor

internal object PaletteColors {
    fun make(bpp: Int): IntArray {
        require(bpp in 1..8) { "bpp must be between 1 and 8." }
        val count = 1 shl bpp
        val colors = IntArray(count)
        colors[0] = argb(20, 20, 24)
        colors[1] = argb(245, 245, 240)
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
            colors[index] = argb(
                ((red + offset) * 255.0).roundLikePython(),
                ((green + offset) * 255.0).roundLikePython(),
                ((blue + offset) * 255.0).roundLikePython()
            )
        }
        return colors
    }

    private fun argb(red: Int, green: Int, blue: Int): Int =
        (0xFF shl 24) or (red shl 16) or (green shl 8) or blue

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
}