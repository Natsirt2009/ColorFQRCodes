package org.example.net.natsirt.colorfrqcodes

import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO

object ImageFileViewer {
    fun decodeForDisplay(image: BufferedImage): String = try {
        "Decoded: ${ImageDecoder().decode(image)}"
    } catch (exception: Exception) {
        "Decode failed: ${exception.message ?: exception.javaClass.simpleName}"
    }

    fun decode(path: Path): String = decodeForDisplay(
        requireNotNull(ImageIO.read(path.toFile())) { "Unsupported or unreadable image." }
    )
}