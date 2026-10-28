package io.github.sargemck.practicalheadphones.bmap

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FrameParserTest {
    private val battery = BmapFrame(2, 2, BmapOp.STATUS, Hex.parse("50 ff ff 00"))
    private val firmware = BmapFrame(0, 5, BmapOp.STATUS, "8.2.20".toByteArray())
    private val empty = BmapFrame(31, 3, BmapOp.RESULT)

    private fun frames(events: List<FrameEvent>) = events.filterIsInstance<FrameEvent.Frame>().map { it.frame }

    @Test
    fun `one complete frame`() {
        assertEquals(listOf(battery), frames(FrameParser().feed(battery.encode())))
    }

    @Test
    fun `several frames in one chunk`() {
        val chunk = battery.encode() + firmware.encode() + empty.encode()
        val parser = FrameParser()
        assertEquals(listOf(battery, firmware, empty), frames(parser.feed(chunk)))
        assertEquals(0, parser.buffered)
    }

    @Test
    fun `frame split across chunks at every position`() {
        val bytes = firmware.encode()
        for (cut in 1 until bytes.size) {
            val parser = FrameParser()
            assertTrue(parser.feed(bytes.copyOfRange(0, cut)).isEmpty(), "cut at $cut")
            assertEquals(listOf(firmware), frames(parser.feed(bytes.copyOfRange(cut, bytes.size))), "cut at $cut")
        }
    }

    @Test
    fun `frame split across four chunks`() {
        val bytes = battery.encode() + firmware.encode()
        val parser = FrameParser()
        val out = listOf(0..2, 3..5, 6..9, 10 until bytes.size)
            .flatMap { frames(parser.feed(bytes.sliceArray(it))) }
        assertEquals(listOf(battery, firmware), out)
    }

    @Test
    fun `byte by byte feed`() {
        val bytes = battery.encode() + empty.encode() + firmware.encode()
        val parser = FrameParser()
        val out = bytes.flatMap { frames(parser.feed(byteArrayOf(it))) }
        assertEquals(listOf(battery, empty, firmware), out)
        assertEquals(0, parser.buffered)
    }

    @Test
    fun `zero length payload`() {
        val events = FrameParser().feed(empty.encode())
        assertEquals(listOf(empty), frames(events))
        assertEquals(0, frames(events).single().payload.size)
    }

    @Test
    fun `maximum payload of 255 bytes`() {
        val big = BmapFrame(31, 6, BmapOp.STATUS, ByteArray(255) { it.toByte() })
        assertEquals(listOf(big), frames(FrameParser().feed(big.encode())))
    }

    @Test
    fun `trailing partial frame is kept for the next feed`() {
        val parser = FrameParser()
        val bytes = battery.encode() + firmware.encode()
        assertEquals(listOf(battery), frames(parser.feed(bytes.copyOfRange(0, battery.encode().size + 5))))
        assertEquals(5, parser.buffered)
        assertEquals(listOf(firmware), frames(parser.feed(bytes.copyOfRange(battery.encode().size + 5, bytes.size))))
    }

    @Test
    fun `resyncs past bytes with an invalid op nibble`() {
        // fblock 31 with flags 0xff is op 15: not a frame start. The following bytes are
        // unknown fblocks, so no offset inside the junk looks like a header.
        val junk = Hex.parse("1f 0a ff ff")
        val events = FrameParser().feed(junk + battery.encode())
        assertEquals(listOf(battery), frames(events))
        val skipped = events.filterIsInstance<FrameEvent.Skipped>().single()
        assertArrayEquals(junk, skipped.bytes)
        assertTrue(events.first() is FrameEvent.Skipped, "skipped bytes are reported before the frame")
    }

    @Test
    fun `resyncs past bytes with an unknown fblock`() {
        val junk = Hex.parse("ff 55 aa")
        val events = FrameParser().feed(junk + empty.encode())
        assertEquals(listOf(empty), frames(events))
        assertArrayEquals(junk, events.filterIsInstance<FrameEvent.Skipped>().single().bytes)
    }

    @Test
    fun `junk that looks like a header swallows following bytes (known limit)`() {
        // At offset 1, "02 0f 02 02" reads as fblock 2, func 15, SETGET, length 2.
        val events = FrameParser().feed(Hex.parse("02 02 0f 02 02 aa bb") + battery.encode())
        assertEquals(BmapFrame(2, 15, BmapOp.SETGET, Hex.parse("aa bb")), frames(events).first())
    }

    @Test
    fun `fblock filter is configurable`() {
        val other = BmapFrame(40, 1, BmapOp.STATUS)
        assertEquals(listOf(other), frames(FrameParser(knownFblocks = setOf(40)).feed(other.encode())))
        assertTrue(frames(FrameParser().feed(other.encode())).isEmpty())
    }
}
