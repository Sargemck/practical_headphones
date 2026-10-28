package io.github.sargemck.practicalheadphones.bmap

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class HexTest {
    @Test
    fun `formats lowercase and spaced`() {
        assertEquals("00 7f 80 ff", Hex.format(byteArrayOf(0, 0x7F, 0x80.toByte(), 0xFF.toByte())))
        assertEquals("", Hex.format(ByteArray(0)))
    }

    @Test
    fun `parses with and without separators`() {
        val expected = byteArrayOf(0x1F, 0x0A, 0xAB.toByte())
        assertArrayEquals(expected, Hex.parse("1f 0a ab"))
        assertArrayEquals(expected, Hex.parse("1F0AAB"))
        assertArrayEquals(expected, Hex.parse("1f:0a-ab"))
    }

    @Test
    fun `rejects odd length and non hex`() {
        assertThrows<IllegalArgumentException> { Hex.parse("abc") }
        assertThrows<IllegalArgumentException> { Hex.parse("zz") }
    }
}
