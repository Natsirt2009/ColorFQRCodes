package net.natsirt.colorfqrcodes

import android.graphics.Bitmap
import org.example.net.natsirt.colorfrqcodes.ColorFQREncoder

class AndroidColorFQRCode(payload: ByteArray, val bpp: Int = 2) {
    private val encoder = ColorFQREncoder(payload, bpp)
    val size: Int = encoder.size

    fun render(scale: Int = 12): Bitmap {
        val rendered = encoder.renderArgb(scale)
        return Bitmap.createBitmap(
            rendered.pixels,
            0,
            rendered.width,
            rendered.width,
            rendered.height,
            Bitmap.Config.ARGB_8888
        )
    }
}