package org.example.net.natsirt.colorfrqcodes

import kotlin.math.ceil

internal object ColorFQRFormat {
    const val MIN_SIZE = 21
    const val MAX_SIZE = 177
    const val SIZE_STEP = 4
    const val QUIET_ZONE = 4
    const val ECC_BYTES = 16
    const val MAX_DATA_PER_BLOCK = 255 - ECC_BYTES

    fun calculateCapacity(totalBytes: Int): Int {
        for (dataBytes in totalBytes downTo 0) {
            val blockCount = maxOf(1, ceil(dataBytes.toDouble() / MAX_DATA_PER_BLOCK).toInt())
            if (dataBytes + blockCount * ECC_BYTES <= totalBytes) return dataBytes
        }
        return 0
    }

    fun buildLayout(size: Int): Pair<Array<IntArray>, List<Pair<Int, Int>>> {
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