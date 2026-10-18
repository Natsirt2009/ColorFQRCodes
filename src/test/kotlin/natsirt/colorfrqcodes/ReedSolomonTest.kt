package org.example.net.natsirt.colorfrqcodes

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ReedSolomonTest {
    @Test
    fun `encoding preserves data and has zero syndromes`() {
        val data = ByteArray(239) { ((it * 73 + 19) and 0xFF).toByte() }
        val codeword = ReedSolomon(16).encode(data)

        assertEquals(data.size + 16, codeword.size)
        assertContentEquals(data, codeword.copyOfRange(0, data.size))

        val polynomial = IntArray(codeword.size) { codeword[it].toInt() and 0xFF }
        for (root in 0 until 16) {
            assertEquals(0, RSPoly.evaluate(polynomial, GF256.exp[root]), "Syndrome at root $root")
        }
    }

    @Test
    fun `decoding corrects up to half the ECC bytes`() {
        val data = ByteArray(128) { ((it * 37 + 11) and 0xFF).toByte() }
        val corrupted = ReedSolomon(16).encode(data)
        for (index in 0 until 8) corrupted[index * 17] = (corrupted[index * 17].toInt() xor (index + 1)).toByte()

        assertContentEquals(data, ReedSolomon(16).decode(corrupted, data.size))
    }
}