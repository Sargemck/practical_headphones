package io.github.sargemck.practicalheadphones

import io.github.sargemck.practicalheadphones.bmap.BmapFrame
import io.github.sargemck.practicalheadphones.bmap.BmapOp
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
}
