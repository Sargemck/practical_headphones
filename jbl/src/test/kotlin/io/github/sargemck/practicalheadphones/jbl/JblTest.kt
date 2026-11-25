package io.github.sargemck.practicalheadphones.jbl

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JblTest {
    @Test
    fun `gatt frame parser handles coalesced notifications and fast pair ble address`() {
        val f1 = JblFrameCodec.encode(JblProtocol.CMD_NOTIFY_ENABLE, byteArrayOf(0x02, 0x01, 0x00))
        val f2 = JblProtocol.buildModeGet()
        val parser = JblFrameParser()
        val parsed = parser.feed(byteArrayOf(0x00, 0xFF.toByte()) + f1 + f2)
        assertEquals(2, parsed.size)

        val fastPairStream = byteArrayOf(
            0x03, 0x01, 0x00, 0x03, 0x71, 0xF2.toByte(), 0x0A,
            0x03, 0x02, 0x00, 0x06, 0x4A, 0x11, 0x22, 0x33, 0x44, 0x55,
        )
        assertEquals("4A:11:22:33:44:55", JblProtocol.parseFastPairBleAddress(fastPairStream))
    }

    @Test
    fun `jbl ble gatt battery and anc ambient talkthru modes`() = runTest {
        val fake = FakeJblDevice()
        val conn = JblConnection(fake, backgroundScope)
        val buds = JblBuds(conn)

        buds.enableNotifications()
        val battery = buds.getBattery()!!
        assertEquals(75, battery.percent)
        assertEquals(80, battery.left)
        assertEquals(75, battery.right)
        assertEquals(90, battery.casePct)

        assertEquals(JblMode.ANC, buds.getMode())
        val ntfy = async(start = CoroutineStart.UNDISPATCHED) {
            conn.notifications.first { JblProtocol.parseModeReport(it) != null }
        }
        buds.setMode(JblMode.TALKTHRU)
        assertEquals(JblMode.TALKTHRU, JblProtocol.parseModeReport(ntfy.await()))
        conn.close()
    }
}
