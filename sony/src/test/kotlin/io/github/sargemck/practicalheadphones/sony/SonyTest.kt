package io.github.sargemck.practicalheadphones.sony

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SonyTest {
    @Test
    fun `frame codec escapes 3C 3D 3E and verifies checksum`() {
        val payload = byteArrayOf(0x3C, 0x3D, 0x3E, 0x00, 0x7F, 0xFF.toByte())
        val frame = SonyFrame(SonyFrameCodec.DATA_MDR, 1, payload)
        val encoded = SonyFrameCodec.encode(frame)

        // Inner bytes must not contain raw HDR (0x3E) or TRL (0x3C)
        for (i in 1 until encoded.size - 1) {
            val u = encoded[i].toInt() and 0xFF
            assertTrue(u != SonyFrameCodec.HDR && u != SonyFrameCodec.TRL)
        }
        assertEquals(frame, SonyFrameCodec.decode(encoded))

        // Corrupt checksum
        val bad = encoded.copyOf()
        bad[bad.size - 2] = (bad[bad.size - 2].toInt() xor 0x01).toByte()
        assertNull(SonyFrameCodec.decode(bad))
    }

    @Test
    fun `stream parser reassembles single-byte chunks`() {
        val f1 = SonyFrame(SonyFrameCodec.ACK, 1)
        val f2 = SonyFrame(SonyFrameCodec.DATA_MDR, 0, byteArrayOf(0x66, 0x17))
        val wire = SonyFrameCodec.encode(f1) + SonyFrameCodec.encode(f2)

        val parser = SonyFrameParser()
        val out = mutableListOf<SonyFrame>()
        for (b in wire) out += parser.feed(byteArrayOf(b))
        assertEquals(listOf(f1, f2), out)

        // Stray/corrupted 0x3E before a valid frame must not swallow the valid frame
        val noisy = byteArrayOf(SonyFrameCodec.HDR.toByte(), 0x12, 0x34) + SonyFrameCodec.encode(f2)
        assertEquals(listOf(f2), parser.feed(noisy))
    }

    @Test
    fun `wh-1000xm5 v2 handshake battery ncasm eq speak-to-chat and power off`() = runTest {
        val fake = FakeSonyDevice(protocolV2 = true, inquiredType = 0x17)
        val conn = SonyConnection(fake, backgroundScope)
        val sony = SonyHeadphones(conn)

        assertTrue(sony.handshake())
        assertEquals(82, sony.getBattery().percent)

        val anc = sony.probeNcAsm(preferV2 = true)
        assertNotNull(anc)
        assertEquals(0x17, anc!!.inquiredType)
        assertEquals(SonyAncMode.ANC, anc.mode)

        val ntfyDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            conn.notifications.first { SonyProtocol.parseNcAsm(it.payload, 0x17) != null }
        }
        sony.setNcAsm(0x17, SonyAncMode.AMBIENT, ambientLevel = 16, focusOnVoice = true)
        val updated = SonyProtocol.parseNcAsm(ntfyDeferred.await().payload, 0x17)!!
        assertEquals(SonyAncMode.AMBIENT, updated.mode)
        assertEquals(16, updated.ambientLevel)
        assertTrue(updated.focusOnVoice)

        val eq = sony.getEq()!!
        assertEquals(SonyEqPreset.OFF, eq.preset)
        val eqDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            conn.notifications.first { SonyProtocol.parseEq(it.payload) != null }
        }
        sony.setEq(SonyEqPreset.BASS_BOOST, listOf(2, 1, 0, 0, 1, 6))
        assertEquals(listOf(2, 1, 0, 0, 1, 6), SonyProtocol.parseEq(eqDeferred.await().payload)!!.bands)

        assertFalse(sony.getSpeakToChat()!!)
        val stcDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            conn.notifications.first { SonyProtocol.parseSpeakToChat(it.payload) != null }
        }
        sony.setSpeakToChat(true)
        assertTrue(SonyProtocol.parseSpeakToChat(stcDeferred.await().payload)!!)

        sony.powerOff()
        assertTrue(conn.state.first { it is SonyConnectionState.Closed } is SonyConnectionState.Closed)
    }

    @Test
    fun `wh-1000xm6 accepts 0x19 notifications after 0x17 probe`() = runTest {
        val fake = FakeSonyDevice(protocolV2 = true, inquiredType = 0x17, emitXm6Ntfy = true)
        val conn = SonyConnection(fake, backgroundScope)
        val sony = SonyHeadphones(conn)

        assertTrue(sony.handshake())
        val anc = sony.probeNcAsm(preferV2 = true)!!
        assertEquals(0x17, anc.inquiredType)

        val ntfyDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            conn.notifications.first { SonyProtocol.parseNcAsm(it.payload, 0x17) != null }
        }
        sony.setNcAsm(0x17, SonyAncMode.OFF, ambientLevel = 10, focusOnVoice = false)
        assertEquals(SonyAncMode.OFF, SonyProtocol.parseNcAsm(ntfyDeferred.await().payload, 0x17)!!.mode)
        conn.close()
    }

    @Test
    fun `wh-1000xm4 v1 0x02 probe and mode switch`() = runTest {
        val fake = FakeSonyDevice(protocolV2 = false, inquiredType = 0x02)
        val conn = SonyConnection(fake, backgroundScope)
        val sony = SonyHeadphones(conn)

        assertFalse(sony.handshake())
        val anc = sony.probeNcAsm(preferV2 = false)!!
        assertEquals(0x02, anc.inquiredType)
        assertEquals(SonyAncMode.ANC, anc.mode)

        val ntfyDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            conn.notifications.first { SonyProtocol.parseNcAsm(it.payload, 0x02) != null }
        }
        sony.setNcAsm(0x02, SonyAncMode.AMBIENT, ambientLevel = 15, focusOnVoice = true)
        val updated = SonyProtocol.parseNcAsm(ntfyDeferred.await().payload, 0x02)!!
        assertEquals(SonyAncMode.AMBIENT, updated.mode)
        assertEquals(15, updated.ambientLevel)
        assertTrue(updated.focusOnVoice)
        conn.close()
    }

    @Test
    fun `wf-1000xm5 earbuds reports left right and cradle battery`() = runTest {
        val fake = FakeSonyDevice(protocolV2 = true, inquiredType = 0x17, earbudsBattery = true)
        val conn = SonyConnection(fake, backgroundScope)
        val sony = SonyHeadphones(conn)

        val battery = sony.getBattery()
        assertEquals(80, battery.percent)
        assertEquals(85, battery.left)
        assertEquals(80, battery.right)
        assertEquals(90, battery.cradle)
        conn.close()
    }
}
