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
        for (index in coefficients.indices.reversed()) {
            result = GF256.multiply(result, value) xor coefficients[index]
        }
        return result
    }

    private fun solve(matrix: Array<IntArray>, values: IntArray): IntArray {
        val size = values.size
        val augmented = Array(size) { row -> matrix[row].copyOf(size + 1).also { it[size] = values[row] } }
        for (column in 0 until size) {
            var pivot = column
            while (pivot < size && augmented[pivot][column] == 0) pivot++
            require(pivot < size) { "Uncorrectable Reed-Solomon block." }
            val swap = augmented[column]
            augmented[column] = augmented[pivot]
            augmented[pivot] = swap

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
}/*package org.example.net.natsirt.colorfrqcodes

class ReedSolomon(private val eccBytes: Int) {
    private val generator = RSGenerator.create(eccBytes)

    fun encode(data: ByteArray): ByteArray {
        val message = IntArray(data.size + eccBytes)
        for (i in data.indices) message[i] = data[i].toInt() and 0xFF
        for (i in data.indices) {
            val coef = message[i]
            if (coef == 0) continue
            for (j in generator.indices)
                message[i + j] = message[i + j] xor GF256.mul(generator[j], coef)
        }
        val result = ByteArray(data.size + eccBytes)
        data.copyInto(result)
        for (i in 0 until eccBytes)
            result[data.size + i] = message[data.size + i].toByte()
        return result
    }

    fun decode(codeword: ByteArray, dataLength: Int): ByteArray {
        require(eccBytes in 1..254) { "ECC size must be between 1 and 254 bytes." }
        require(dataLength in 0..(255 - eccBytes)) { "Invalid RS data block length." }
        require(codeword.size == dataLength + eccBytes) { "Invalid RS codeword length." }

        val received = IntArray(codeword.size) { codeword[it].toInt() and 0xFF }
        val syndromes = IntArray(eccBytes) { index ->
            RSPoly.evaluate(received, GF256.exp[index])
        }
        if (syndromes.all { it == 0 }) return codeword.copyOfRange(0, dataLength)

        val locator = findErrorLocator(syndromes)
        val errorPositions = findErrorPositions(locator, received.size)
        require(errorPositions.isNotEmpty() && errorPositions.size == locator.size - 1) {
            "Could not locate Reed-Solomon errors."
        }
        correctErrors(received, syndromes, errorPositions)
        require((0 until eccBytes).all { RSPoly.evaluate(received, GF256.exp[it]) == 0 }) {
            "Reed-Solomon correction failed."
        }
        return ByteArray(dataLength) { received[it].toByte() }
    }

    private fun findErrorLocator(syndromes: IntArray): IntArray {
        var locator = IntArray(eccBytes + 1)
        var previous = IntArray(eccBytes + 1)
        locator[0] = 1
        previous[0] = 1
        var degree = 0
        var shift = 1
        var previousDiscrepancy = 1

        for (index in syndromes.indices) {
            var discrepancy = syndromes[index]
            for (i in 1..degree) {
                discrepancy = discrepancy xor GF256.mul(locator[i], syndromes[index - i])
            }
            if (discrepancy == 0) {
                shift++
                continue
            }

            val oldLocator = locator.copyOf()
            val scale = GF256.div(discrepancy, previousDiscrepancy)
            for (i in previous.indices) {
                if (previous[i] != 0 && i + shift < locator.size) {
                    locator[i + shift] = locator[i + shift] xor GF256.mul(scale, previous[i])
                }
            }
            if (2 * degree <= index) {
                degree = index + 1 - degree
                previous = oldLocator
                previousDiscrepancy = discrepancy
                shift = 1
            } else {
                shift++
            }
        }
        return locator.copyOf(degree + 1)
    }

    private fun findErrorPositions(locator: IntArray, codewordLength: Int): IntArray {
        val positions = mutableListOf<Int>()
        for (position in 0 until codewordLength) {
            val location = GF256.exp[codewordLength - 1 - position]
            val inverse = GF256.inverse(location)
            var value = locator.last()
            for (index in locator.lastIndex - 1 downTo 0) {
                value = GF256.mul(value, inverse) xor locator[index]
            }
            if (value == 0) positions += position
        }
        return positions.toIntArray()
    }

    private fun correctErrors(received: IntArray, syndromes: IntArray, positions: IntArray) {
        val count = positions.size
        val matrix = Array(count) { IntArray(count + 1) }
        for (row in 0 until count) {
            for (column in 0 until count) {
                val location = GF256.exp[received.size - 1 - positions[column]]
                matrix[row][column] = GF256.pow(location, row)
            }
            matrix[row][count] = syndromes[row]
        }

        for (column in 0 until count) {
            var pivot = column
            while (pivot < count && matrix[pivot][column] == 0) pivot++
            require(pivot < count) { "Singular Reed-Solomon error system." }
            val swap = matrix[column]
            matrix[column] = matrix[pivot]
            matrix[pivot] = swap

            val inverse = GF256.inverse(matrix[column][column])
            for (entry in column..count) matrix[column][entry] = GF256.mul(matrix[column][entry], inverse)
            for (row in 0 until count) {
                if (row == column) continue
                val factor = matrix[row][column]
                if (factor == 0) continue
                for (entry in column..count) {
                    matrix[row][entry] = matrix[row][entry] xor GF256.mul(factor, matrix[column][entry])
                }
            }
        }

        for (index in positions.indices) received[positions[index]] = received[positions[index]] xor matrix[index][count]
    }
}*/