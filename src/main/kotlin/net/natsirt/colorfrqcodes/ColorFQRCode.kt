package org.example.net.natsirt.colorfrqcodes

import java.awt.image.BufferedImage
import java.nio.file.Path
import java.util.zip.CRC32
import javax.imageio.ImageIO
import kotlin.math.ceil

class ColorFQRCode(payload: ByteArray, val bpp: Int = 2) {
    private val payload = payload.copyOf()
    val size: Int

    init {
        require(bpp in 1..8) { "bpp must be between 1 and 8." }
        require(payload.size <= 65535) { "Payload cannot exceed 65535 bytes." }
        size = chooseSize(payload.size)
    }

    fun render(scale: Int = 12): BufferedImage {
        val rendered = renderArgb(scale)
        val image = BufferedImage(rendered.width, rendered.height, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, rendered.width, rendered.height, rendered.pixels, 0, rendered.width)
        return image
    }

    fun renderArgb(scale: Int = 12): RenderedCode = ColorFQREncoder(payload, bpp).renderArgb(scale)

    fun writePng(path: Path, scale: Int = 12) {
        require(ImageIO.write(render(scale), "png", path.toFile())) { "PNG writer is unavailable." }
    }

    private fun chooseSize(payloadLength: Int): Int {
        for (size in MIN_SIZE..MAX_SIZE step SIZE_STEP) {
            val capacity = calculateCapacity(buildLayout(size).second.size * bpp / 8)
            if (capacity >= payloadLength + FRAME_BYTES) return size
        }
        throw IllegalArgumentException("Payload is too large for bpp=$bpp and the maximum ${MAX_SIZE}x$MAX_SIZE grid.")
    }

    private fun framePayload(capacity: Int): ByteArray {
        val frame = ByteArray(capacity)
        frame[0] = (payload.size ushr 8).toByte()
        frame[1] = payload.size.toByte()
        payload.copyInto(frame, 2)
        val crc = CRC32().apply { update(payload) }.value
        for (index in 0 until 4) frame[2 + payload.size + index] = (crc ushr (24 - index * 8)).toByte()
        return frame
    }

    private fun encodeBlocks(data: ByteArray): ByteArray {
        val result = ArrayList<Byte>()
        for (start in data.indices step MAX_DATA_PER_BLOCK) {
            val end = minOf(start + MAX_DATA_PER_BLOCK, data.size)
            result.addAll(ReedSolomon(ECC_BYTES).encode(data.copyOfRange(start, end)).toList())
        }
        return result.toByteArray()
    }

    private fun bytesToSymbols(data: ByteArray): MutableList<Int> {
        val mask = (1 shl bpp) - 1
        var accumulator = 0
        var bitCount = 0
        val symbols = ArrayList<Int>(ceil(data.size * 8.0 / bpp).toInt())
        for (byte in data) {
            accumulator = (accumulator shl 8) or (byte.toInt() and 0xFF)
            bitCount += 8
            while (bitCount >= bpp) {
                bitCount -= bpp
                symbols += (accumulator ushr bitCount) and mask
                accumulator = accumulator and ((1 shl bitCount) - 1)
            }
        }
        if (bitCount > 0) symbols += (accumulator shl (bpp - bitCount)) and mask
        return symbols
    }

    companion object {
        const val MIN_SIZE = 21
        const val MAX_SIZE = 177
        const val SIZE_STEP = 4
        const val QUIET_ZONE = 4
        const val ECC_BYTES = 16
        const val MAX_DATA_PER_BLOCK = 255 - ECC_BYTES
        private const val FRAME_BYTES = 6

        internal fun calculateCapacity(totalBytes: Int): Int {
            for (dataBytes in totalBytes downTo 0) {
                val blockCount = maxOf(1, ceil(dataBytes.toDouble() / MAX_DATA_PER_BLOCK).toInt())
                if (dataBytes + blockCount * ECC_BYTES <= totalBytes) return dataBytes
            }
            return 0
        }

        internal fun buildLayout(size: Int): Pair<Array<IntArray>, List<Pair<Int, Int>>> {
            require(size in MIN_SIZE..MAX_SIZE && (size - MIN_SIZE) % SIZE_STEP == 0)
            val grid = Array(size) { IntArray(size) }
            val reserved = Array(size) { BooleanArray(size) }
            fun put(x: Int, y: Int, color: Int) {
                if (x in 0 until size && y in 0 until size) {
                    grid[y][x] = color
                    reserved[y][x] = true
                }
            }

            for ((originX, originY) in listOf(0 to 0, size - 7 to 0, 0 to size - 7)) {
                for (dy in -1..7) for (dx in -1..7) {
                    val x = originX + dx
                    val y = originY + dy
                    if (x !in 0 until size || y !in 0 until size) continue
                    val color = if (dx in 0..6 && dy in 0..6) {
                        val outer = dx == 0 || dx == 6 || dy == 0 || dy == 6
                        val center = dx in 2..4 && dy in 2..4
                        if (outer || center) 0 else 1
                    } else 1
                    put(x, y, color)
                }
            }

            for (index in 8 until size - 8) {
                put(index, 8, if (index % 2 == 0) 0 else 1)
                put(8, index, if (index % 2 == 0) 0 else 1)
            }

            val positions = ArrayList<Pair<Int, Int>>()
            var right = size - 1
            var upward = true
            while (right >= 0) {
                val columns = if (right > 0) listOf(right, right - 1) else listOf(right)
                val rows = if (upward) (size - 1 downTo 0) else (0 until size)
                for (y in rows) for (x in columns) if (!reserved[y][x]) positions += x to y
                upward = !upward
                right -= 2
            }
            return grid to positions
        }
    }
}/*package org.example.net.natsirt.colorfrqcodes

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.zip.CRC32
import javax.imageio.ImageIO

internal const val MIN_SIZE = 21
internal const val MAX_SIZE = 177
internal const val SIZE_STEP = 4
internal const val QUIET_ZONE = 4
private const val DEFAULT_SCALE = 12
internal const val ECC_BYTES = 16
internal const val MAX_DATA_PER_BLOCK = 255 - ECC_BYTES

data class Position(
    val x: Int,
    val y: Int
)

data class Layout(
    val grid: Array<IntArray>,
    val positions: List<Position>
)

class ColorFQRCode(val data: ByteArray, val bpp: Int, private val palette: Palette) {
    val size: Int

    init {
        require(bpp in 1..8) { "bpp must be between 1 and 8" }
        require(data.size <= 65535) { "Payload cannot exceed 65535 bytes." }
        size = chooseSize(data.size, bpp)
    }

    constructor(data: ByteArray, bpp: Int) : this(data, bpp, Palette.forBpp(bpp))

    fun render(scale: Int = DEFAULT_SCALE): BufferedImage {
        require(scale >= 1) { "Scale must be at least 1." }
        val totalModules = size + 2 * QUIET_ZONE
        val image = BufferedImage(totalModules * scale, totalModules * scale, BufferedImage.TYPE_INT_RGB)
        val background = palette.colors[1].rgb
        for (y in 0 until image.height) {
            for (x in 0 until image.width) image.setRGB(x, y, background)
        }

        val (grid, positions) = buildLayout(size)
        val capacity = calculateCapacity(positions.size * bpp / 8)
        val codeword = encodeBlocks(framePayload(data, capacity))
        val symbols = bytesToSymbols(codeword, bpp).toMutableList()
        check(symbols.size <= positions.size) { "Encoded data exceeds the grid capacity." }
        symbols.addAll(List(positions.size - symbols.size) { 0 })

        for ((position, symbol) in positions.zip(symbols)) grid[position.y][position.x] = symbol

        for (y in grid.indices) {
            for (x in grid[y].indices) {
                val color = palette.colors[grid[y][x]].rgb
                val left = (x + QUIET_ZONE) * scale
                val top = (y + QUIET_ZONE) * scale
                for (dy in 0 until scale) {
                    for (dx in 0 until scale) image.setRGB(left + dx, top + dy, color)
                }
            }
        }
        return image
    }

    fun writePng(path: Path, scale: Int = DEFAULT_SCALE) {
        require(ImageIO.write(render(scale), "png", path.toFile())) { "PNG writer is unavailable." }
    }

    private fun chooseSize(dataSize: Int, bpp: Int): Int {
        val requiredDataBytes = dataSize + 6
        for (size in MIN_SIZE..MAX_SIZE step SIZE_STEP) {
            val layout = buildLayout(size)
            val totalBytes = layout.positions.size * bpp / 8
            if (calculateCapacity(totalBytes) >= requiredDataBytes) return size
        }
        throw IllegalArgumentException("Too much data for bpp=$bpp and MAX_SIZE=$MAX_SIZE")
    }

    internal fun calculateCapacity(totalBytes: Int): Int {
        for (dataBytes in totalBytes downTo 0) {
            val blocks = maxOf(1, (dataBytes + MAX_DATA_PER_BLOCK - 1) / MAX_DATA_PER_BLOCK)
            if (dataBytes + blocks * ECC_BYTES <= totalBytes) return dataBytes
        }
        return 0
    }

    private fun framePayload(payload: ByteArray, capacity: Int): ByteArray {
        val crc = CRC32().apply { update(payload) }.value
        val framed = ByteArrayOutputStream(capacity)
        framed.write((payload.size ushr 8) and 0xFF)
        framed.write(payload.size and 0xFF)
        framed.write(payload)
        for (shift in 24 downTo 0 step 8) framed.write(((crc ushr shift) and 0xFF).toInt())
        require(framed.size() <= capacity) { "Payload does not fit." }
        return framed.toByteArray().copyOf(capacity)
    }

    private fun encodeBlocks(data: ByteArray): ByteArray {
        val blockCount = maxOf(1, (data.size + MAX_DATA_PER_BLOCK - 1) / MAX_DATA_PER_BLOCK)
        val result = ByteArray(data.size + blockCount * ECC_BYTES)
        var sourceOffset = 0
        var resultOffset = 0
        while (sourceOffset < data.size) {
            val end = minOf(sourceOffset + MAX_DATA_PER_BLOCK, data.size)
            val encoded = ReedSolomon(ECC_BYTES).encode(data.copyOfRange(sourceOffset, end))
            encoded.copyInto(result, resultOffset)
            sourceOffset = end
            resultOffset += encoded.size
        }
        return result
    }

    private fun bytesToSymbols(bytes: ByteArray, bitsPerSymbol: Int): List<Int> {
        val mask = (1 shl bitsPerSymbol) - 1
        var accumulator = 0
        var bitCount = 0
        val symbols = mutableListOf<Int>()
        for (byte in bytes) {
            accumulator = (accumulator shl 8) or (byte.toInt() and 0xFF)
            bitCount += 8
            while (bitCount >= bitsPerSymbol) {
                bitCount -= bitsPerSymbol
                symbols += (accumulator ushr bitCount) and mask
                accumulator = accumulator and ((1 shl bitCount) - 1)
            }
        }
        if (bitCount > 0) symbols += (accumulator shl (bitsPerSymbol - bitCount)) and mask
        return symbols
    }

    internal fun buildLayout(size: Int): Layout {
        val grid = Array(size) { IntArray(size) }
        val reserved = Array(size) { BooleanArray(size) }

        fun put(x: Int, y: Int, color: Int) {
            if (x in 0 until size &&
                y in 0 until size
            ) {
                grid[y][x] = color
                reserved[y][x] = true
            }
        }

        // Finder patterns
        for ((ox, oy) in listOf(0 to 0, size - 7 to 0, 0 to size - 7)) {
            for (dy in -1..7) {
                for (dx in -1..7) {
                    val x = ox + dx
                    val y = oy + dy
                    if (x !in 0 until size ||
                        y !in 0 until size
                    ) continue
                    val color =
                        if (dx in 0..6 && dy in 0..6) {
                            val outer = dx == 0 ||
                                        dx == 6 ||
                                        dy == 0 ||
                                        dy == 6
                            val center = dx in 2..4 &&
                                         dy in 2..4
                            if (outer || center) 0 else 1
                        } else 1
                    put(x, y, color)
                }
            }
        }

        // Timing guides
        for (i in 8 until size - 8) {
            val color = if (i % 2 == 0) 0 else 1
            put(i, 8, color)
            put(8, i, color)
        }

        // Data traversal
        val positions = mutableListOf<Position>()
        var right = size - 1
        var upward = true
        while (right >= 0) {
            val columns = if (right > 0) intArrayOf(right, right - 1) else intArrayOf(right)
            val rows: IntProgression = if (upward) (size - 1 downTo 0) else (0 until size)
            for (y in rows) {
                for (x in columns) {
                    if (!reserved[y][x]) {
                        positions += Position(x = x, y = y)
                    }
                }
            }
            upward = !upward
            right -= 2
        }
        return Layout(grid = grid, positions = positions)
    }
}*/