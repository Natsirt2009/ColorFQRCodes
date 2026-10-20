package org.example.net.natsirt.colorfrqcodes

internal object GF256 {
    val exp = IntArray(512)
    private val log = IntArray(256)

    init {
        var value = 1
        for (index in 0 until 255) {
            exp[index] = value
            log[value] = index
            value = value shl 1
            if (value and 0x100 != 0) value = value xor 0x11D
        }
        for (index in 255 until exp.size) exp[index] = exp[index - 255]
    }

    fun multiply(left: Int, right: Int): Int =
        if (left == 0 || right == 0) 0 else exp[log[left] + log[right]]

    fun divide(numerator: Int, denominator: Int): Int {
        require(denominator != 0) { "Division by zero in GF(256)." }
        if (numerator == 0) return 0
        return exp[(log[numerator] - log[denominator] + 255) % 255]
    }

    fun power(value: Int, exponent: Int): Int {
        if (exponent == 0) return 1
        if (value == 0) return 0
        return exp[((log[value] * exponent) % 255 + 255) % 255]
    }

    fun logarithm(value: Int): Int {
        require(value in 1..255) { "Logarithm input must be a nonzero byte." }
        return log[value]
    }
}