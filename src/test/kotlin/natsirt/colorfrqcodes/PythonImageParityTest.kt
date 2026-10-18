package org.example.net.natsirt.colorfrqcodes

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue

class PythonImageParityTest {
    @Test
    fun `Kotlin output matches Python pixels across palettes and RS blocks`() {
        val script = Paths.get("anybpp.py").toAbsolutePath()
        assumeTrue(Files.isRegularFile(script), "Run this test from the project root to find anybpp.py")
        val cases = listOf(
            Triple("Color parity: caf\u00e9 \u2603", 2, 3),
            Triple("Reed-Solomon block check: " + "abcXYZ012".repeat(35), 4, 2),
            Triple("Eight-bit palette check", 8, 1)
        )

        for ((text, bpp, scale) in cases) {
            val kotlinPath = Files.createTempFile("colorfq-kotlin-", ".png")
            val pythonPath = Files.createTempFile("colorfq-python-", ".png")
            try {
                ColorFQRCode(text.toByteArray(Charsets.UTF_8), bpp).writePng(kotlinPath, scale)
                runPythonReference(script, text, bpp, scale, pythonPath)

                val kotlinImage = ImageIO.read(kotlinPath.toFile())
                val pythonImage = ImageIO.read(pythonPath.toFile())
                assertEquals(pythonImage.width, kotlinImage.width)
                assertEquals(pythonImage.height, kotlinImage.height)
                for (y in 0 until kotlinImage.height) {
                    for (x in 0 until kotlinImage.width) {
                        assertEquals(pythonImage.getRGB(x, y), kotlinImage.getRGB(x, y), "Pixel ($x, $y), bpp=$bpp")
                    }
                }
            } finally {
                Files.deleteIfExists(kotlinPath)
                Files.deleteIfExists(pythonPath)
            }
        }
    }

    private fun runPythonReference(script: java.nio.file.Path, text: String, bpp: Int, scale: Int, output: java.nio.file.Path) {
        val commands = listOf(
            listOf("python", script.toString(), "--encode", text, "--bpp", bpp.toString(), "--scale", scale.toString(), "-o", output.toString()),
            listOf("py", "-3", script.toString(), "--encode", text, "--bpp", bpp.toString(), "--scale", scale.toString(), "-o", output.toString())
        )
        var unavailable: IOException? = null
        for (command in commands) {
            val process = try {
                ProcessBuilder(command).redirectErrorStream(true).start()
            } catch (exception: IOException) {
                unavailable = exception
                continue
            }
            val outputText = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            assertEquals(0, exitCode, "Python reference failed: $outputText")
            return
        }
        assumeTrue(false, "Python is unavailable; install Python with Pillow and reedsolo to run image parity tests (${unavailable?.message})")
    }
}