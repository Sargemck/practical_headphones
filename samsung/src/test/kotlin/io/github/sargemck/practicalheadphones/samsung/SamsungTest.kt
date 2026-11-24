package io.github.sargemck.practicalheadphones.samsung

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SamsungTest {
    @Test
    fun `frame codec validates crc16 and reassembles chunked frames`() {
        val raw = SamsungFrameCodec.encode(SamsungProtocol.MSG_NOISE_CONTROLS, byteArrayOf(0x01), request = true)
        val decoded = SamsungFrameCodec.decode(raw)!!
        assertEquals(SamsungProtocol.MSG_NOISE_CONTROLS, decoded.msgId)
        assertEquals(1, decoded.payload.size)
        assertEquals(0x01, decoded.payload[0].toInt() and 0xFF)

        val bad = raw.copyOf()
        bad[bad.size - 2] = (bad[bad.size - 2].toInt() xor 0xFF).toByte()
        assertNull(SamsungFrameCodec.decode(bad))

        val parser = SamsungFrameParser()
        val out = mutableListOf<SamsungFrame>()
        for (b in (byteArrayOf(0x00, 0x11) + raw)) out += parser.feed(byteArrayOf(b))
        assertEquals(listOf(decoded), out)
    }

    @Test
    fun `galaxy buds extended status noise controls and equalizer`() = runTest {
        val fake = FakeSamsungDevice()
        val conn = SamsungConnection(fake, backgroundScope)
        val buds = SamsungBuds(conn)

        val status = buds.fetchExtendedStatus()
        assertEquals(88, status.leftBattery)
        assertEquals(84, status.rightBattery)
        assertEquals(65, status.caseBattery)
        assertEquals(84, status.aggregateBattery)
        assertEquals(SamsungNoiseMode.ANC, status.noiseMode)
        assertEquals(SamsungEqPreset.NORMAL, status.eqPreset)

        val noiseNtfy = async(start = CoroutineStart.UNDISPATCHED) {
            conn.notifications.first { it.msgId == SamsungProtocol.MSG_NOISE_CONTROLS_UPDATE }
        }
        buds.setNoiseMode(SamsungNoiseMode.AMBIENT)
        assertEquals(SamsungNoiseMode.AMBIENT, SamsungProtocol.parseNoiseControlUpdate(noiseNtfy.await().payload))

        val eqNtfy = async(start = CoroutineStart.UNDISPATCHED) {
            conn.notifications.first { it.msgId == SamsungProtocol.MSG_EXTENDED_STATUS_UPDATED }
        }
        buds.setEqualizer(SamsungEqPreset.DYNAMIC)
        assertEquals(SamsungEqPreset.DYNAMIC, SamsungProtocol.parseExtendedStatus(eqNtfy.await().payload)!!.eqPreset)
        conn.close()
    }
}
