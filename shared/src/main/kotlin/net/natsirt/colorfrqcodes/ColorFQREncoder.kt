package org.example.net.natsirt.colorfrqcodes

import java.util.zip.CRC32
import kotlin.math.ceil

data class RenderedCode(val width: Int, val height: Int, val pixels: IntArray)

internal class ColorFQREncoder(payload: ByteArray, val bpp: Int) {
    private val payload = payload.copyOf()
    val size: Int

    init {
        require(bpp in 1..8) { "bpp must be between 1 and 8." }
        require(payload.size <= 65535) { "Payload cannot exceed 65535 bytes." }
        size = chooseSize(payload.size)
    }

    fun renderArgb(scale: Int): RenderedCode {
        require(scale >= 1) { "Scale must be at least 1." }
        val (grid, positions) = ColorFQRFormat.buildLayout(size)
        val capacity = ColorFQRFormat.calculateCapacity(positions.size * bpp / 8)
        val symbols = bytesToSymbols(encodeBlocks(framePayload(capacity)))
        require(symbols.size <= positions.size) { "Encoded data exceeds the grid capacity." }
        positions.forEachIndexed { index, (x, y) -> grid[y][x] = symbols.getOrElse(index) { 0 } }

        val dimension = Math.multiplyExact(size + ColorFQRFormat.QUIET_ZONE * 2, scale)
        val pixels = IntArray(Math.multiplyExact(dimension, dimension))
        val palette = PaletteColors.make(bpp)
        pixels.fill(palette[1])
        for (y in grid.indices) {
            val top = (y + ColorFQRFormat.QUIET_ZONE) * scale
            for (x in grid[y].indices) {
                val color = palette[grid[y][x]]
                val left = (x + ColorFQRFormat.QUIET_ZONE) * scale
                for (dy in 0 until scale) {
                    val rowStart = (top + dy) * dimension + left
                    pixels.fill(color, rowStart, rowStart + scale)
                }
            }
        }
        return RenderedCode(dimension, dimension, pixels)
    }

    private fun chooseSize(payloadLength: Int): Int {
        for (candidate in ColorFQRFormat.MIN_SIZE..ColorFQRFormat.MAX_SIZE step ColorFQRFormat.SIZE_STEP) {
            val positions = ColorFQRFormat.buildLayout(candidate).second
            val capacity = ColorFQRFormat.calculateCapacity(positions.size * bpp / 8)
            if (capacity >= payloadLength + 6) return candidate
        }
        throw IllegalArgumentException(
            "Payload is too large for bpp=$bpp and the maximum ${ColorFQRFormat.MAX_SIZE}x${ColorFQRFormat.MAX_SIZE} grid."
        )
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
        for (start in data.indices step ColorFQRFormat.MAX_DATA_PER_BLOCK) {
            val end = minOf(start + ColorFQRFormat.MAX_DATA_PER_BLOCK, data.size)
            result.addAll(ReedSolomon(ColorFQRFormat.ECC_BYTES).encode(data.copyOfRange(start, end)).toList())
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
}