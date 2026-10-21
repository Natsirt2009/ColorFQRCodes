package net.natsirt.colorfqrcodes

import android.graphics.Bitmap
import org.example.net.natsirt.colorfrqcodes.ColorFQRFormat
import org.example.net.natsirt.colorfrqcodes.PaletteColors
import org.example.net.natsirt.colorfrqcodes.ReedSolomon
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.QRCodeDetector
import java.nio.ByteBuffer
import java.util.zip.CRC32

data class DecodedImage(val text: String, val corners: List<Point>?)

object OpenCvRuntime {
    @Volatile private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        if (!loaded) {
            System.loadLibrary("opencv_java4")
            loaded = true
        }
    }
}

class ImageDecoder {
    fun decode(image: Bitmap): String = decodeWithBounds(image).text

    fun decodeFrame(frame: Bitmap): DecodedImage? = try {
        decodeWithBounds(frame)
    } catch (_: Exception) {
        null
    }

    fun decodeWithBounds(image: Bitmap): DecodedImage {
        require(!image.isRecycled && image.width > 0 && image.height > 0) { "Image is empty." }
        OpenCvRuntime.ensureLoaded()
        val source = Mat()
        val gray = Mat()
        val detector = QRCodeDetector()
        val points = Mat()
        val normalizedBitmap = if (image.config == Bitmap.Config.ARGB_8888) image else
            image.copy(Bitmap.Config.ARGB_8888, false) ?: throw IllegalArgumentException("Could not read image pixels.")
        try {
            Utils.bitmapToMat(normalizedBitmap, source)
            Imgproc.cvtColor(source, gray, Imgproc.COLOR_RGBA2GRAY)
            require(detector.detect(gray, points) && !points.empty()) {
                "No code-like foreground found."
            }
            val detected = (0 until 4).map { index ->
                Point(points.get(0, index)[0], points.get(0, index)[1])
            }
            val ordered = orderCorners(detected)
            val orderedMat = MatOfPoint2f(*ordered.toTypedArray())
            try {
                val failures = mutableListOf<String>()
                var emptyPayloadCandidate: DecodedImage? = null
                for (size in ColorFQRFormat.MIN_SIZE..ColorFQRFormat.MAX_SIZE step ColorFQRFormat.SIZE_STEP) {
                    val total = size + ColorFQRFormat.QUIET_ZONE * 2
                    val sampleScale = 8
                    val outputSize = total * sampleScale
                    val inset = ColorFQRFormat.QUIET_ZONE * sampleScale.toDouble()
                    val end = (ColorFQRFormat.QUIET_ZONE + size) * sampleScale - 1.0
                    val targetMat = MatOfPoint2f(
                        Point(inset, inset), Point(end, inset), Point(end, end), Point(inset, end)
                    )
                    try {
                        for (rotation in 0 until 4) {
                            val rotatedCorners = ordered.indices.map { ordered[(it + rotation) % 4] }
                            val rotatedMat = MatOfPoint2f(*rotatedCorners.toTypedArray())
                            val transform = Imgproc.getPerspectiveTransform(rotatedMat, targetMat)
                            val rectified = Mat()
                            try {
                                Imgproc.warpPerspective(
                                    source,
                                    rectified,
                                    transform,
                                    Size(outputSize.toDouble(), outputSize.toDouble()),
                                    Imgproc.INTER_LINEAR,
                                    Core.BORDER_CONSTANT,
                                    Scalar(240.0, 245.0, 240.0, 255.0)
                                )
                                tryDecodeRectified(rectified, size, failures)?.let { decodedText ->
                                    val candidate = DecodedImage(decodedText, rotatedCorners)
                                    if (decodedText.isNotEmpty()) return candidate
                                    emptyPayloadCandidate = candidate
                                }
                            } finally {
                                rotatedMat.release()
                                transform.release()
                                rectified.release()
                            }
                        }
                    } finally {
                        targetMat.release()
                    }
                }
                emptyPayloadCandidate?.let { return it }
                throw IllegalArgumentException(
                    "Could not decode image. " + failures.takeLast(4).joinToString("; ")
                )
            } finally {
                orderedMat.release()
            }
        } finally {
            source.release()
            gray.release()
            points.release()
            if (normalizedBitmap !== image) normalizedBitmap.recycle()
        }
    }

    private fun tryDecodeRectified(image: Mat, size: Int, failures: MutableList<String>): String? {
        if (!hasValidFinderPatterns(image, size)) return null
        val positions = ColorFQRFormat.buildLayout(size).second
        var emptyPayloadCandidate: String? = null
        for (bpp in 1..8) {
            try {
                val totalBytes = positions.size * bpp / 8
                val capacity = ColorFQRFormat.calculateCapacity(totalBytes)
                val blockCount = maxOf(1, (capacity + ColorFQRFormat.MAX_DATA_PER_BLOCK - 1) / ColorFQRFormat.MAX_DATA_PER_BLOCK)
                val codewordLength = capacity + blockCount * ColorFQRFormat.ECC_BYTES
                val symbolCount = (codewordLength * 8 + bpp - 1) / bpp
                if (symbolCount > positions.size) continue

                val palette = PaletteColors.make(bpp)
                val scale = image.cols().toDouble() / (size + 2 * ColorFQRFormat.QUIET_ZONE)
                var accumulator = 0
                var bitCount = 0
                val output = ByteArray(codewordLength)
                var outputIndex = 0
                for (index in 0 until symbolCount) {
                    val (x, y) = positions[index]
                    val px = ((x + ColorFQRFormat.QUIET_ZONE) * scale + scale / 2.0).toInt()
                    val py = ((y + ColorFQRFormat.QUIET_ZONE) * scale + scale / 2.0).toInt()
                    val pixel = image.get(py, px) ?: continue
                    val symbol = nearestColor(pixel, palette)
                    accumulator = (accumulator shl bpp) or symbol
                    bitCount += bpp
                    while (bitCount >= 8 && outputIndex < output.size) {
                        bitCount -= 8
                        output[outputIndex++] = ((accumulator ushr bitCount) and 0xFF).toByte()
                        accumulator = accumulator and ((1 shl bitCount) - 1)
                    }
                }
                if (outputIndex != output.size) continue
                val decoded = decodeBlocks(output, capacity)
                if (decoded.size < 6) continue
                val payloadLength = ((decoded[0].toInt() and 0xFF) shl 8) or (decoded[1].toInt() and 0xFF)
                val end = payloadLength + 2
                if (end + 4 > decoded.size) continue
                val payload = decoded.copyOfRange(2, end)
                val storedCrc = ((decoded[end].toLong() and 0xFF) shl 24) or
                    ((decoded[end + 1].toLong() and 0xFF) shl 16) or
                    ((decoded[end + 2].toLong() and 0xFF) shl 8) or
                    (decoded[end + 3].toLong() and 0xFF)
                val actualCrc = CRC32().apply { update(payload) }.value
                if (storedCrc != actualCrc) continue
                val text = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload)).toString()
                if (text.isEmpty()) emptyPayloadCandidate = text else return text
            } catch (exception: Exception) {
                if (failures.size < 12) failures += "$size/$bpp bpp: ${exception.message}"
            }
        }
        return emptyPayloadCandidate
    }

    private fun hasValidFinderPatterns(image: Mat, size: Int): Boolean {
        val scale = image.cols().toDouble() / (size + 2 * ColorFQRFormat.QUIET_ZONE)
        val origins = listOf(0 to 0, size - 7 to 0, 0 to size - 7)
        var mismatches = 0
        var samples = 0
        for ((originX, originY) in origins) {
            for (dy in 0..6) for (dx in 0..6) {
                val expectedDark = dx == 0 || dx == 6 || dy == 0 || dy == 6 ||
                    (dx in 2..4 && dy in 2..4)
                val px = ((originX + dx + ColorFQRFormat.QUIET_ZONE) * scale + scale / 2).toInt()
                val py = ((originY + dy + ColorFQRFormat.QUIET_ZONE) * scale + scale / 2).toInt()
                val pixel = image.get(py, px) ?: return false
                val actualDark = pixel[0] + pixel[1] + pixel[2] < 3.0 * 132.0
                if (actualDark != expectedDark) mismatches++
                samples++
            }
        }
        if (mismatches > samples / 12) return false

        var timingMismatches = 0
        var timingSamples = 0
        for (index in 8 until size - 8) {
            val expectedDark = index % 2 == 0
            for ((x, y) in listOf(index to 8, 8 to index)) {
                val px = ((x + ColorFQRFormat.QUIET_ZONE) * scale + scale / 2).toInt()
                val py = ((y + ColorFQRFormat.QUIET_ZONE) * scale + scale / 2).toInt()
                val pixel = image.get(py, px) ?: return false
                val actualDark = pixel[0] + pixel[1] + pixel[2] < 3.0 * 132.0
                if (actualDark != expectedDark) timingMismatches++
                timingSamples++
            }
        }
        return timingSamples > 0 && timingMismatches <= timingSamples / 10
    }

    private fun decodeBlocks(codeword: ByteArray, dataLength: Int): ByteArray {
        val output = ByteArray(dataLength)
        var sourceOffset = 0
        var outputOffset = 0
        var remaining = dataLength
        while (remaining > 0) {
            val dataCount = minOf(remaining, ColorFQRFormat.MAX_DATA_PER_BLOCK)
            val blockLength = dataCount + ColorFQRFormat.ECC_BYTES
            val block = codeword.copyOfRange(sourceOffset, sourceOffset + blockLength)
            ReedSolomon(ColorFQRFormat.ECC_BYTES).decode(block, dataCount).copyInto(output, outputOffset)
            sourceOffset += blockLength
            outputOffset += dataCount
            remaining -= dataCount
        }
        return output
    }

    private fun nearestColor(pixel: DoubleArray, palette: IntArray): Int {
        val red = pixel[0]
        val green = pixel[1]
        val blue = pixel[2]
        return palette.indices.minBy { index ->
            val color = palette[index]
            val paletteRed = (color ushr 16) and 0xFF
            val paletteGreen = (color ushr 8) and 0xFF
            val paletteBlue = color and 0xFF
            (red - paletteRed) * (red - paletteRed) +
                (green - paletteGreen) * (green - paletteGreen) +
                (blue - paletteBlue) * (blue - paletteBlue)
        }
    }

    private fun orderCorners(points: List<Point>): List<Point> {
        val topLeft = points.minBy { it.x + it.y }
        val bottomRight = points.maxBy { it.x + it.y }
        val topRight = points.minBy { it.y - it.x }
        val bottomLeft = points.maxBy { it.y - it.x }
        return listOf(topLeft, topRight, bottomRight, bottomLeft)
    }
}