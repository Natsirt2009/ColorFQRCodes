package org.example.net.natsirt.colorfrqcodes

internal object RSGenerator {
    fun create(eccBytes: Int): IntArray {
        require(eccBytes in 1..254) { "ECC bytes must be between 1 and 254." }
        var generator = intArrayOf(1)
        for (index in 0 until eccBytes) {
            generator = RSPoly.multiply(generator, intArrayOf(1, GF256.exp[index]))
        }
        return generator
    }
}