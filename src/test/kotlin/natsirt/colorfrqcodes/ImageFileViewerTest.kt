package org.example.net.natsirt.colorfrqcodes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImageFileViewerTest {
    @Test
    fun `viewer decode result contains decoded payload`() {
        val text = "Live image result"
        val image = ColorFQRCode(text.toByteArray(Charsets.UTF_8), 3).render(scale = 8)

        assertEquals("Decoded: $text", ImageFileViewer.decodeForDisplay(image))
    }

    @Test
    fun `viewer reports undecodable image instead of crashing`() {
        val image = java.awt.image.BufferedImage(100, 100, java.awt.image.BufferedImage.TYPE_INT_RGB)

        assertTrue(ImageFileViewer.decodeForDisplay(image).startsWith("Decode failed:"))
    }
}