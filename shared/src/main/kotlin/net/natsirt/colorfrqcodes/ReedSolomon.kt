package org.example.net.natsirt.colorfrqcodes

class ReedSolomon(private val eccBytes: Int) {
    private val generator = RSGenerator.create(eccBytes)

    fun encode(data: ByteArray): ByteArray {
        require(data.size + eccBytes <= 255) { "A Reed-Solomon block cannot exceed 255 bytes." }
        val work = IntArray(data.size + eccBytes)
        data.forEachIndexed { index, byte -> work[index] = byte.toInt() and 0xFF }

        for (index in data.indices) {
            val coefficient = work[index]
            if (coefficient == 0) continue
            for (generatorIndex in generator.indices) {
                work[index + generatorIndex] = work[index + generatorIndex] xor
                    GF256.multiply(generator[generatorIndex], coefficient)
            }
        }

        return ByteArray(data.size + eccBytes) { index ->
            (if (index < data.size) data[index].toInt() and 0xFF else work[index]).toByte()
        }
    }

    fun decode(codeword: ByteArray, dataLength: Int): ByteArray {
        require(dataLength >= 0 && dataLength + eccBytes == codeword.size) {
            "Codeword length does not match data length and ECC."
        }
        require(codeword.size <= 255) { "A Reed-Solomon block cannot exceed 255 bytes." }
        val corrected = IntArray(codeword.size) { codeword[it].toInt() and 0xFF }
        val syndromes = IntArray(eccBytes) { root ->
            RSPoly.evaluate(corrected, GF256.exp[root])
        }
        if (syndromes.all { it == 0 }) return codeword.copyOf(dataLength)

        var locator = intArrayOf(1)
        var previousLocator = intArrayOf(1)
        var locatorDegree = 0
        var distance = 1
        var previousDiscrepancy = 1

        for (step in 0 until eccBytes) {
            var discrepancy = syndromes[step]
            for (index in 1..locatorDegree) {
                val coefficient = locator.getOrElse(index) { 0 }
                discrepancy = discrepancy xor GF256.multiply(coefficient, syndromes[step - index])
            }

            if (discrepancy == 0) {
                distance++
                continue
            }

            val savedLocator = locator.copyOf()
            val factor = GF256.divide(discrepancy, previousDiscrepancy)
            if (locator.size < previousLocator.size + distance) {
                locator = locator.copyOf(previousLocator.size + distance)
            }
            for (index in previousLocator.indices) {
                locator[index + distance] = locator[index + distance] xor
                    GF256.multiply(factor, previousLocator[index])
            }

            if (2 * locatorDegree <= step) {
                locatorDegree = step + 1 - locatorDegree
                previousLocator = savedLocator
                previousDiscrepancy = discrepancy
                distance = 1
            } else {
                distance++
            }
        }

        locator = locator.copyOf(locatorDegree + 1)
        require(locatorDegree in 1..(eccBytes / 2)) { "Too many Reed-Solomon errors." }

        val positions = mutableListOf<Int>()
        val locations = mutableListOf<Int>()
        for (position in corrected.indices) {
            val location = GF256.exp[(corrected.size - 1 - position) % 255]
            val inverse = GF256.exp[(255 - GF256.logarithm(location)) % 255]
            if (evaluateAscending(locator, inverse) == 0) {
                positions += position
                locations += location
            }
        }
        require(positions.size == locatorDegree) { "Could not locate all corrupted symbols." }

        val matrix = Array(locatorDegree) { row ->
            IntArray(locatorDegree) { column -> GF256.power(locations[column], row) }
        }
        val magnitudes = solve(matrix, syndromes.copyOf(locatorDegree))
        positions.forEachIndexed { index, position -> corrected[position] = corrected[position] xor magnitudes[index] }

        require((0 until eccBytes).all { root ->
            RSPoly.evaluate(corrected, GF256.exp[root]) == 0
        }) { "Reed-Solomon verification failed." }

        return ByteArray(dataLength) { corrected[it].toByte() }
    }

    private fun evaluateAscending(coefficients: IntArray, value: Int): Int {
        var result = 0
        var power = 1
        for (coefficient in coefficients) {
            result = result xor GF256.multiply(coefficient, power)
            power = GF256.multiply(power, value)
        }
        return result
    }

    private fun solve(matrix: Array<IntArray>, vector: IntArray): IntArray {
        val size = vector.size
        val augmented = Array(size) { row -> matrix[row].copyOf(size + 1).also { it[size] = vector[row] } }
        for (column in 0 until size) {
            val pivot = (column until size).firstOrNull { augmented[it][column] != 0 }
                ?: error("Reed-Solomon error magnitudes are singular.")
            val temporary = augmented[column]
            augmented[column] = augmented[pivot]
            augmented[pivot] = temporary
            val divisor = augmented[column][column]
            for (index in column..size) augmented[column][index] = GF256.divide(augmented[column][index], divisor)
            for (row in 0 until size) {
                if (row == column) continue
                val factor = augmented[row][column]
                for (index in column..size) {
                    augmented[row][index] = augmented[row][index] xor GF256.multiply(factor, augmented[column][index])
                }
            }
        }
        return IntArray(size) { augmented[it][size] }
    }
}