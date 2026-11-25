package io.github.sargemck.practicalheadphones.soundcore

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SoundcoreTest {
    @Test
    fun `frame codec encodes verifies checksum and reassembles chunked streams`() {
        val raw = SoundcoreFrameCodec.encodeRx(0x06, 0x01, byteArrayOf(0x00, 0x5F, 0xFF.toByte(), 0x00, 0x00, 0x01))
        val decoded = SoundcoreFrameCodec.decode(raw, expectTx = false)!!
        assertEquals(0x06, decoded.cmdGroup)
        assertEquals(0x01, decoded.cmdId)
        assertEquals(6, decoded.payload.size)

        val bad = raw.copyOf()
        bad[bad.lastIndex] = (bad[bad.lastIndex].toInt() xor 0xFF).toByte()
        assertNull(SoundcoreFrameCodec.decode(bad, expectTx = false))

        val parser = SoundcoreFrameParser(expectTx = false)
        val out = mutableListOf<SoundcoreFrame>()
        for (b in (byteArrayOf(0x11, 0x22) + raw)) out += parser.feed(byteArrayOf(b))
        assertEquals(listOf(decoded), out)

        // Corrupt prefix with bad checksum before a valid frame must resync cleanly
        val falsePrefix = SoundcoreFrameCodec.RX_PREFIX + byteArrayOf(0x06, 0x01, 0x0C, 0x00)
        assertEquals(listOf(decoded), parser.feed(falsePrefix + raw))
    }

    @Test
    fun `space q45 6-byte sound mode battery and 8-band eq`() = runTest {
        val fake = FakeSoundcoreDevice(soundModeWidth = 6)
        val conn = SoundcoreConnection(fake, backgroundScope)
        val sc = SoundcoreHeadphones(conn)

        assertEquals(80, sc.getBattery())
        val initialMode = sc.getSoundMode()!!
        assertEquals(SoundcoreAncMode.ANC, initialMode.mode)
        assertEquals(6, initialMode.rawBytes.size)

        val modeNtfy = async(start = CoroutineStart.UNDISPATCHED) {
            conn.notifications.first { it.cmdGroup == SoundcoreProtocol.GROUP_SOUND_MODE }
        }
        sc.setSoundMode(SoundcoreAncMode.TRANSPARENCY, initialMode.rawBytes)
        val updated = SoundcoreProtocol.parseSoundMode(modeNtfy.await().payload)!!
        assertEquals(SoundcoreAncMode.TRANSPARENCY, updated.mode)
        // Byte 2 should be 0x02 (not 0xFF) on writes
        assertEquals(0x02, updated.rawBytes[2].toInt() and 0xFF)

        val eq = sc.getEq()!!
        assertEquals(List(8) { 0 }, eq.bands)
        val eqNtfy = async(start = CoroutineStart.UNDISPATCHED) {
            conn.notifications.first { it.cmdGroup == SoundcoreProtocol.GROUP_EQ }
        }
        sc.setEq(SoundcoreEqPreset.ACOUSTIC.id, SoundcoreEqPreset.ACOUSTIC.bands)
        assertEquals(SoundcoreEqPreset.ACOUSTIC.bands, SoundcoreProtocol.parseEq(eqNtfy.await().payload)!!.bands)
        conn.close()
    }

    @Test
    fun `life q30 preserves 4-byte sound mode width`() = runTest {
        val fake = FakeSoundcoreDevice(soundModeWidth = 4)
        val conn = SoundcoreConnection(fake, backgroundScope)
        val sc = SoundcoreHeadphones(conn)

        val initial = sc.getSoundMode()!!
        assertEquals(4, initial.rawBytes.size)
        val modeNtfy = async(start = CoroutineStart.UNDISPATCHED) {
            conn.notifications.first { it.cmdGroup == SoundcoreProtocol.GROUP_SOUND_MODE }
        }
        sc.setSoundMode(SoundcoreAncMode.NORMAL, initial.rawBytes)
        val updated = SoundcoreProtocol.parseSoundMode(modeNtfy.await().payload)!!
        assertEquals(SoundcoreAncMode.NORMAL, updated.mode)
        assertEquals(4, updated.rawBytes.size)
        conn.close()
    }
}
