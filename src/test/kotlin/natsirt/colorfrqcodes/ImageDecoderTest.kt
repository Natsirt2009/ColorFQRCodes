package org.example.net.natsirt.colorfrqcodes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

class ImageDecoderTest {
    @Test
    fun `round trip every supported palette depth`() {
        val expected = "All palette depths: café ☃"
        for (bpp in 1..8) {
            val image = ColorFQRCode(expected.toByteArray(Charsets.UTF_8), bpp).render(scale = 5)
            assertEquals(expected, ImageDecoder().decode(image), "Failed at $bpp bpp")
        }
    }

    @Test
    fun `decode browser scanner two-bit red and blue palette`() {
        val expected = "Browser camera palette compatibility"
        val image = ColorFQRCode(expected.toByteArray(Charsets.UTF_8), 2).render(scale = 8)
        val pythonPalette = Palette.make(2)
        val browserPalette = listOf(
            java.awt.Color(20, 20, 24),
            java.awt.Color(245, 245, 240),
            java.awt.Color(230, 55, 65),
            java.awt.Color(40, 115, 230)
        )
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val color = java.awt.Color(image.getRGB(x, y))
            val symbol = pythonPalette.indexOfFirst { it.rgb == color.rgb }
            if (symbol >= 2) image.setRGB(x, y, browserPalette[symbol].rgb)
        }

        assertEquals(expected, ImageDecoder().decode(image))
    }

    @Test
    fun `blank camera frame reports no code location`() {
        val blank = java.awt.image.BufferedImage(640, 480, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val error = assertFailsWith<IllegalArgumentException> { ImageDecoder().decodeWithBounds(blank) }

        kotlin.test.assertTrue(error.message.orEmpty().contains("No code-like foreground"))
    }

    @Test
    fun `decode generated still image`() {
        val expected = "Kotlin image decode: caf\u00e9 \u2603"
        val image = ColorFQRCode(expected.toByteArray(Charsets.UTF_8), 4).render(scale = 8)

        val result = ImageDecoder().decodeWithBounds(image)
        assertEquals(expected, result.text)
        assertEquals(4, result.corners?.size, "Camera overlay requires four detected code corners")
    }

    @Test
    fun `decode lossy camera jpeg frame`() {
        val expected = "Phone camera JPEG decode"
        val source = ColorFQRCode(expected.toByteArray(Charsets.UTF_8), 3).render(scale = 12)
        val jpegFile = kotlin.io.path.createTempFile(suffix = ".jpg").toFile()
        try {
            javax.imageio.ImageIO.write(source, "jpg", jpegFile)
            val cameraFrame = javax.imageio.ImageIO.read(jpegFile)
            assertEquals(expected, ImageDecoder().decode(cameraFrame))
        } finally {
            jpegFile.delete()
        }
    }

    @Test
    fun `decode image with camera brightness shift`() {
        val expected = "Exposure shift test"
        val source = ColorFQRCode(expected.toByteArray(Charsets.UTF_8), 4).render(scale = 8)
        val brighter = java.awt.image.BufferedImage(source.width, source.height, source.type)
        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                val rgb = source.getRGB(x, y)
                val red = (((rgb ushr 16) and 0xFF) + 10).coerceAtMost(255)
                val green = (((rgb ushr 8) and 0xFF) + 10).coerceAtMost(255)
                val blue = ((rgb and 0xFF) + 10).coerceAtMost(255)
                brighter.setRGB(x, y, (red shl 16) or (green shl 8) or blue)
            }
        }

        assertEquals(expected, ImageDecoder().decode(brighter))
    }

    @Test
    fun `decode rotated code inside larger camera scene`() {
        val expected = "Camera scene decode"
        val code = ColorFQRCode(expected.toByteArray(Charsets.UTF_8), 4).render(scale = 8)
        val scene = java.awt.image.BufferedImage(720, 540, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val graphics = scene.createGraphics()
        try {
            graphics.color = java.awt.Color(170, 170, 170)
            graphics.fillRect(0, 0, scene.width, scene.height)
            graphics.rotate(Math.PI / 2, scene.width / 2.0, scene.height / 2.0)
            graphics.drawImage(code, (scene.width - code.width) / 2, (scene.height - code.height) / 2, null)
        } finally {
            graphics.dispose()
        }

        val result = ImageDecoder().decodeWithBounds(scene)
        assertEquals(expected, result.text)
        assertEquals(4, result.corners?.size)
    }

    @Test
    fun `decode perspective skewed rotated code in camera scene`() {
        OpenCvRuntime.ensureLoaded()
        val expected = "Perspective camera QR geometry"
        val code = ColorFQRCode(expected.toByteArray(Charsets.UTF_8), 4).render(scale = 9)
        val source = Mat()
        val warped = Mat()
        val input = MatOfPoint2f()
        val output = MatOfPoint2f()
        var transform: Mat? = null
        val codeFile = kotlin.io.path.createTempFile(suffix = ".png").toFile()
        val cameraFile = kotlin.io.path.createTempFile(suffix = ".jpg").toFile()
        try {
            javax.imageio.ImageIO.write(code, "png", codeFile)
            Imgcodecs.imread(codeFile.absolutePath).copyTo(source)
            input.fromArray(Point(0.0, 0.0), Point(code.width - 1.0, 0.0), Point(code.width - 1.0, code.height - 1.0), Point(0.0, code.height - 1.0))
            output.fromArray(Point(175.0, 105.0), Point(810.0, 145.0), Point(755.0, 705.0), Point(120.0, 655.0))
            transform = Imgproc.getPerspectiveTransform(input, output)
            Imgproc.warpPerspective(source, warped, transform, Size(960.0, 800.0), Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar(45.0, 52.0, 50.0))
            check(Imgcodecs.imwrite(cameraFile.absolutePath, warped))

            val result = ImageDecoder().decodeWithBounds(javax.imageio.ImageIO.read(cameraFile))
            assertEquals(expected, result.text)
            assertEquals(4, result.corners?.size)
        } finally {
            source.release()
            warped.release()
            input.release()
            output.release()
            transform?.release()
            codeFile.delete()
            cameraFile.delete()
        }
    }

    @Test
    fun `decode code printed on light sheet against dark background`() {
        val expected = "Printed page camera test"
        val code = ColorFQRCode(expected.toByteArray(Charsets.UTF_8), 4).render(scale = 8)
        val scene = java.awt.image.BufferedImage(1000, 760, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val graphics = scene.createGraphics()
        try {
            graphics.color = java.awt.Color(35, 48, 48)
            graphics.fillRect(0, 0, scene.width, scene.height)
            graphics.color = java.awt.Color(238, 241, 238)
            graphics.fillRect(120, 50, 760, 660)
            graphics.drawImage(code, (scene.width - code.width) / 2, (scene.height - code.height) / 2, null)
        } finally {
            graphics.dispose()
        }

        assertEquals(expected, ImageDecoder().decode(scene))
    }

    @Test
    fun `decode JPEG camera image of code on light sheet`() {
        val expected = "Printed page JPEG camera test"
        val code = ColorFQRCode(expected.toByteArray(Charsets.UTF_8), 4).render(scale = 8)
        val scene = java.awt.image.BufferedImage(1000, 760, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val graphics = scene.createGraphics()
        try {
            graphics.color = java.awt.Color(35, 48, 48)
            graphics.fillRect(0, 0, scene.width, scene.height)
            graphics.color = java.awt.Color(238, 241, 238)
            graphics.fillRect(120, 50, 760, 660)
            graphics.drawImage(code, (scene.width - code.width) / 2, (scene.height - code.height) / 2, null)
        } finally {
            graphics.dispose()
        }

        val jpegFile = kotlin.io.path.createTempFile(suffix = ".jpg").toFile()
        try {
            javax.imageio.ImageIO.write(scene, "jpg", jpegFile)
            val result = ImageDecoder().decodeWithBounds(javax.imageio.ImageIO.read(jpegFile))
            assertEquals(expected, result.text)
            assertEquals(4, result.corners?.size)
        } finally {
            jpegFile.delete()
        }
    }

    @Test
    fun `unskew perspective image before decoding`() {
        OpenCvRuntime.ensureLoaded()
        val expected = "Perspective decode test 123"
        val image = ColorFQRCode(expected.toByteArray(Charsets.UTF_8), 3).render(scale = 10)
        val sourceFile = kotlin.io.path.createTempFile(suffix = ".png").toFile()
        val skewedFile = kotlin.io.path.createTempFile(suffix = ".png").toFile()
        val source = Mat()
        val skewed = Mat()
        val loaded = Mat()
        val inputCorners = MatOfPoint2f()
        val outputCorners = MatOfPoint2f()
        var transform: Mat? = null
        try {
            javax.imageio.ImageIO.write(image, "png", sourceFile)
            Imgcodecs.imread(sourceFile.absolutePath).copyTo(source)
            val width = image.width.toDouble()
            val height = image.height.toDouble()
            inputCorners.fromArray(Point(0.0, 0.0), Point(width - 1, 0.0), Point(width - 1, height - 1), Point(0.0, height - 1))
            outputCorners.fromArray(
                Point(width * 0.13, height * 0.09),
                Point(width * 0.88, height * 0.02),
                Point(width * 0.96, height * 0.91),
                Point(width * 0.04, height * 0.97)
            )
            transform = Imgproc.getPerspectiveTransform(inputCorners, outputCorners)
            Imgproc.warpPerspective(
                source,
                skewed,
                transform,
                Size(width, height),
                Imgproc.INTER_LINEAR,
                Core.BORDER_CONSTANT,
                Scalar(240.0, 245.0, 245.0)
            )
            check(Imgcodecs.imwrite(skewedFile.absolutePath, skewed))

            val skewedImage = javax.imageio.ImageIO.read(skewedFile)
            assertEquals(expected, ImageDecoder().decode(skewedImage))
        } finally {
            source.release()
            skewed.release()
            loaded.release()
            inputCorners.release()
            outputCorners.release()
            transform?.release()
            sourceFile.delete()
            skewedFile.delete()
        }
    }
}