package io.github.sargemck.practicalheadphones.bmap

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BmapFrameTest {
    @Test
    fun `encodes GET battery like bosectl`() {
        assertArrayEquals(Hex.parse("02 02 01 00"), BmapFrame(2, 2, BmapOp.GET).encode())
    }

    @Test
    fun `encodes payload and length`() {
        val frame = BmapFrame(31, 10, BmapOp.SETGET, Hex.parse("05 00 00 00 01"))
        assertArrayEquals(Hex.parse("1f 0a 02 05 05 00 00 00 01"), frame.encode())
    }

    @Test
    fun `flags carry device id and port`() {
        val frame = BmapFrame(1, 7, BmapOp.STATUS, deviceId = 2, port = 1)
        assertEquals(0x93, frame.flags)
        assertEquals(0x93.toByte(), frame.encode()[2])
    }

    @Test
    fun `round trips through the parser`() {
        val frame = BmapFrame(4, 4, BmapOp.STATUS, Hex.parse("01 02 03"), deviceId = 1, port = 3)
        val events = FrameParser().feed(frame.encode())
        assertEquals(listOf(FrameEvent.Frame(frame)), events)
    }

    @Test
    fun `rejects oversized payload and out of range fields`() {
        assertThrows<IllegalArgumentException> { BmapFrame(0, 0, BmapOp.GET, ByteArray(256)) }
        assertThrows<IllegalArgumentException> { BmapFrame(256, 0, BmapOp.GET) }
        assertThrows<IllegalArgumentException> { BmapFrame(0, 0, BmapOp.GET, port = 4) }
    }

    @Test
    fun `op codes map both ways`() {
        BmapOp.entries.forEach { assertEquals(it, BmapOp.fromCode(it.code)) }
        assertNull(BmapOp.fromCode(8))
    }
}
