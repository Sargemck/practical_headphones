package io.github.sargemck.practicalheadphones.nothing

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NothingTest {
    @Test
    fun `frame codec validates modbus crc16 and resyncs stream`() {
        val raw = NothingFrameCodec.encode(NothingProtocol.CMD_ANC_GET, NothingProtocol.DIR_GET)
        val decoded = NothingFrameCodec.decode(raw)!!
        assertEquals(NothingProtocol.CMD_ANC_GET, decoded.command)
        assertEquals(NothingProtocol.DIR_GET, decoded.direction)

        val bad = raw.copyOf()
        bad[bad.lastIndex] = (bad[bad.lastIndex].toInt() xor 0xFF).toByte()
        assertNull(NothingFrameCodec.decode(bad))

        val parser = NothingFrameParser()
        val noisy = byteArrayOf(0x55, 0x60, 0x01, 0x1E, 0xC0.toByte(), 0x02, 0x00, 0x01) + raw
        assertEquals(listOf(decoded), parser.feed(noisy))
    }

    @Test
    fun `nothing ear battery and anc modes round-trip`() = runTest {
        val fake = FakeNothingDevice()
        val conn = NothingConnection(fake, backgroundScope)
        val hp = NothingHeadphones(conn)

        hp.initSession()
        val battery = hp.getBattery()
        assertEquals(80, battery.percent)
        assertEquals(85, battery.left)
        assertEquals(80, battery.right)
        assertEquals(75, battery.casePct)

        assertEquals(NothingAncMode.ANC_ADAPTIVE, hp.getAnc())
        hp.setAnc(NothingAncMode.TRANSPARENCY)
        assertEquals(NothingAncMode.TRANSPARENCY, hp.getAnc())
        conn.close()
    }
}
