package io.github.sargemck.practicalheadphones

import io.github.sargemck.practicalheadphones.bmap.BmapFrame
import io.github.sargemck.practicalheadphones.bmap.BmapOp
import io.github.sargemck.practicalheadphones.bmap.MacAddress
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpikeGuardTest {
    private fun allowed(fblock: Int, func: Int, op: BmapOp) = SpikeGuard.refusal(BmapFrame(fblock, func, op)) == null

    @Test
    fun `GET is allowed anywhere except the auth block`() {
        listOf(0 to 5, 1 to 2, 1 to 4, 2 to 2, 4 to 4, 4 to 5, 7 to 4, 31 to 10).forEach { (b, f) ->
            assertNull(SpikeGuard.refusal(BmapFrame(b, f, BmapOp.GET)), "GET $b.$f")
        }
        assertNotNull(SpikeGuard.refusal(BmapFrame(18, 1, BmapOp.GET)))
    }

    @Test
    fun `START only lists modes and switches mode`() {
        assertTrue(allowed(31, 1, BmapOp.START))
        assertTrue(allowed(31, 3, BmapOp.START))
        listOf(4 to 8, 7 to 4, 4 to 12, 31 to 6, 31 to 8).forEach { (b, f) ->
            assertNotNull(SpikeGuard.refusal(BmapFrame(b, f, BmapOp.START)), "START $b.$f")
        }
    }

    @Test
    fun `SETGET only on live audio settings`() {
        assertTrue(allowed(31, 10, BmapOp.SETGET))
        listOf(1 to 2, 1 to 7, 31 to 6, 31 to 8, 1 to 10).forEach { (b, f) ->
            assertNotNull(SpikeGuard.refusal(BmapFrame(b, f, BmapOp.SETGET)), "SETGET $b.$f")
        }
    }

    @Test
    fun `SET and reply ops are never sent`() {
        listOf(BmapOp.SET, BmapOp.STATUS, BmapOp.ERROR, BmapOp.RESULT, BmapOp.PROCESSING).forEach { op ->
            assertNotNull(SpikeGuard.refusal(BmapFrame(31, 10, op)), "$op 31.10")
        }
    }

    // S1. Made-up MACs; real ones never appear in code.
    private val pc = MacAddress(listOf(0x02, 0x00, 0x00, 0x00, 0x00, 0x01))
    private val phone = MacAddress(listOf(0x02, 0x00, 0x00, 0x00, 0x00, 0x02))
    private val other = MacAddress(listOf(0x02, 0x00, 0x00, 0x00, 0x00, 0x03))

    private fun start(func: Int, payload: ByteArray) = SpikeGuard.refusal(BmapFrame(4, func, BmapOp.START, payload))

    private fun session() {
        SpikeGuard.routeMacs = setOf(pc, phone)
        SpikeGuard.linkMacs = setOf(pc)
    }

    @AfterEach
    fun clear() {
        SpikeGuard.routeMacs = emptySet()
        SpikeGuard.linkMacs = emptySet()
    }

    @Test
    fun `unpairing and unknown block 4 functions are refused with any op`() {
        session()
        for (f in listOf(3, 7, 10, 11)) for (op in listOf(BmapOp.GET, BmapOp.SETGET, BmapOp.START)) {
            assertNotNull(SpikeGuard.refusal(BmapFrame(4, f, op, pc.toBytes())), "$op 4.$f")
        }
    }

    @Test
    fun `routing only to allowed MACs, only as 0x82 plus MAC`() {
        assertNotNull(start(12, byteArrayOf(0x82.toByte()) + pc.toBytes()), "nothing allowed before the session is set")
        session()
        assertNull(start(12, byteArrayOf(0x82.toByte()) + pc.toBytes()))
        assertNull(start(12, byteArrayOf(0x82.toByte()) + phone.toBytes()))
        assertNotNull(start(12, byteArrayOf(0x82.toByte()) + other.toBytes()))
        assertNotNull(start(12, byteArrayOf(0x02) + pc.toBytes()))
        assertNotNull(start(12, pc.toBytes()))
        assertNotNull(SpikeGuard.refusal(BmapFrame(4, 12, BmapOp.SETGET, byteArrayOf(0x82.toByte()) + pc.toBytes())))
    }

    @Test
    fun `connect and disconnect only the PC, only as MAC or 0x00 plus MAC`() {
        session()
        assertNull(start(1, pc.toBytes()))
        assertNull(start(2, pc.toBytes()))
        assertNotNull(start(2, phone.toBytes()), "never disconnect the phone")
        assertNotNull(start(1, other.toBytes()))
        assertNull(start(2, byteArrayOf(0) + pc.toBytes()))
        assertNotNull(start(2, byteArrayOf(1) + pc.toBytes()))
        assertNotNull(start(2, pc.toBytes() + byteArrayOf(0)))
        assertNotNull(start(2, ByteArray(0)))
        assertNotNull(SpikeGuard.refusal(BmapFrame(4, 2, BmapOp.SET, pc.toBytes())))
    }
}
