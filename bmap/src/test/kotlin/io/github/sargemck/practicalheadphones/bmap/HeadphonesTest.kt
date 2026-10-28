package io.github.sargemck.practicalheadphones.bmap

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@OptIn(ExperimentalCoroutinesApi::class)
class HeadphonesTest {
    private val hike = ProfileSettings(cnc = 4, spatial = Spatial.FIXED_TO_ROOM, wind = false, anc = true)

    @Test
    fun `every read feature parses on the fake`() = runTest {
        val h = rig().headphones
        assertEquals("10.12.12+ga927c9a", h.read(QcUltra2.firmware))
        assertEquals("Headphone Name 001", h.read(QcUltra2.productName))
        assertEquals(3, h.read(QcUltra2.eq).size)
        assertEquals(0x80, h.read(QcUltra2.buttons).buttonId)
        assertTrue(h.read(QcUltra2.multipoint).enabled)
        assertEquals(SelfVoiceLevel.MEDIUM, h.read(QcUltra2.selfVoice).level)
        assertEquals(false, h.read(QcUltra2.autoPlayPause))
        assertEquals(false, h.read(QcUltra2.autoAnswer))
        assertEquals(80, h.read(QcUltra2.battery))
        assertEquals(5, h.read(QcUltra2.pairedDevices).devices.size)
        assertEquals(SourceType.NONE, h.read(QcUltra2.source).type)
        assertEquals(2, h.read(QcUltra2.currentMode))
        assertEquals(11, h.read(QcUltra2.favourites).total)
        assertEquals(Spatial.FIXED_TO_HEAD, h.read(QcUltra2.audioSettings).spatial)
        assertEquals("Paired Dev 1", h.deviceInfo(MacAddress(listOf(2, 0, 0, 0, 0, 1))).name)
    }

    @Test
    fun `writes reach the device state`() = runTest {
        val h = rig().headphones
        h.write(QcUltra2.Write.autoAnswer, true)
        assertEquals(true, h.read(QcUltra2.autoAnswer))
        h.write(QcUltra2.Write.autoPlayPause, true)
        assertEquals(true, h.read(QcUltra2.autoPlayPause))
        h.write(QcUltra2.Write.eqBand, EqBandSetting(bandId = 2, value = -4))
        assertEquals(-4, h.read(QcUltra2.eq).single { it.bandId == 2 }.current)
        h.write(QcUltra2.Write.selfVoice, SelfVoiceLevel.OFF)
        assertEquals(SelfVoice(persist = true, level = SelfVoiceLevel.OFF, extra = 0x0f), h.read(QcUltra2.selfVoice))
        h.write(QcUltra2.Write.productName, "Desk")
        assertEquals("Desk", h.read(QcUltra2.productName))
        h.write(QcUltra2.Write.favourites, Favourites(11, setOf(0, 5)))
        assertEquals(setOf(0, 5), h.read(QcUltra2.favourites).slots)
    }

    @Test
    fun `setAudioSettings returns what the device applied`() = runTest {
        val h = rig().headphones
        val wanted = AudioSettings(cnc = 6, autoCnc = false, spatial = Spatial.OFF, wind = true, anc = true)
        assertEquals(wanted, h.setAudioSettings(wanted))
        assertEquals(wanted, h.read(QcUltra2.audioSettings))
    }

    @Test
    fun `selectMode re-reads the audio settings the switch changed`() = runTest {
        val r = rig()
        // M0 E5: from Immersion (spatial 02) to Quiet, 31.10 became 00 00 00 00 01.
        assertEquals(
            AudioSettings(cnc = 0, autoCnc = false, spatial = Spatial.OFF, wind = false, anc = true),
            r.headphones.selectMode(0),
        )
        assertEquals(
            listOf(QcUltra2.selectMode to BmapOp.START, QcUltra2.audioSettings.address to BmapOp.GET),
            r.fake.received.map { it.address to it.op },
        )
        assertEquals(0, r.headphones.read(QcUltra2.currentMode))
    }

    @Test
    fun `create, update and delete a profile`() = runTest {
        val h = rig().headphones
        assertEquals(8, h.createProfile("Hike", hike))
        h.allModes().modes.single { it.index == 8 }.let {
            assertEquals("Hike", it.name)
            assertEquals(4, it.cnc)
            assertEquals(Spatial.FIXED_TO_ROOM, it.spatial)
            assertTrue(it.configured)
        }
        h.updateProfile("hike", rename = "Trail", settings = hike.copy(cnc = 7))
        h.allModes().modes.single { it.index == 8 }.let {
            assertEquals("Trail", it.name)
            assertEquals(7, it.cnc)
        }
        h.deleteProfile("Trail")
        assertEquals("None", h.allModes().modes.single { it.index == 8 }.name)
        assertEquals(8, h.createProfile("Second", hike))
        assertEquals(9, h.createProfile("Third", hike))
    }

    @Test
    fun `presets refused before anything is written`() = runTest {
        val r = rig()
        assertThrows<ProfileError.PresetLocked> { r.headphones.updateProfile("Quiet", settings = hike) }
        assertTrue(r.fake.received.none { it.op == BmapOp.SETGET })
    }

    @Test
    fun `malformed reply`() = runTest {
        val r = rig { ignoreNext[QcUltra2.battery.address] = 1 }
        val outcome = background { r.headphones.read(QcUltra2.battery) }
        testScheduler.runCurrent()
        r.fake.pushUnsolicited(BmapFrame(2, 2, BmapOp.STATUS, Hex.parse("ff")))
        testScheduler.runCurrent()
        assertTrue(outcome()?.exceptionOrNull() is BmapException.Malformed)
    }
}
