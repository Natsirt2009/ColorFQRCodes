package org.example.net.natsirt.colorfrqcodes

internal object RSPoly {
    fun evaluate(coefficients: IntArray, value: Int): Int =
        coefficients.fold(0) { result, coefficient ->
            GF256.multiply(result, value) xor coefficient
        }

    fun multiply(left: IntArray, right: IntArray): IntArray {
        val result = IntArray(left.size + right.size - 1)
        for (leftIndex in left.indices) {
            for (rightIndex in right.indices) {
                result[leftIndex + rightIndex] = result[leftIndex + rightIndex] xor
                    GF256.multiply(left[leftIndex], right[rightIndex])
            }
        }
        return result
    }
}