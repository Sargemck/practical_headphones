package io.github.sargemck.practicalheadphones.bmap

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MultiBoseTest {
    @Test
    fun `inferFromName matches all supported Bose model families`() {
        assertEquals(BoseCatalog.QC_ULTRA_2, BoseCatalog.inferFromName("Bose QC Ultra Headphones"))
        assertEquals(BoseCatalog.QC_ULTRA_2_EARBUDS, BoseCatalog.inferFromName("Bose QC Ultra Earbuds"))
        assertEquals(BoseCatalog.QC_45, BoseCatalog.inferFromName("Bose QC45"))
        assertEquals(BoseCatalog.QC_35, BoseCatalog.inferFromName("Bose QC35 II"))
        assertEquals(BoseCatalog.QC_PRINCE, BoseCatalog.inferFromName("Bose QuietComfort"))
        assertEquals(BoseCatalog.ULTRA_OPEN, BoseCatalog.inferFromName("Bose Ultra Open Earbuds"))
        assertFalse(BoseCatalog.QC_ULTRA_2.experimental)
        assertTrue(BoseCatalog.QC_45.experimental)
    }

    @Test
    fun `refineFromWire distinguishes models by wire capabilities`() {
        assertEquals(
            BoseCatalog.QC_ULTRA_2_EARBUDS,
            BoseCatalog.refineFromWire(initial = null, batteryPayloadLength = 16),
        )
        assertEquals(
            BoseCatalog.QC_35,
            BoseCatalog.refineFromWire(initial = null, supportsModes = false, supportsAnr = true),
        )
        assertEquals(
            BoseCatalog.QC_45,
            BoseCatalog.refineFromWire(initial = null, modeConfigSize = 47, supportsLiveAudio = false, usedInitPacket = true),
        )
        assertEquals(
            BoseCatalog.QC_PRINCE,
            BoseCatalog.refineFromWire(initial = null, modeConfigSize = 47, supportsLiveAudio = false, usedInitPacket = false),
        )
        assertEquals(
            BoseCatalog.ULTRA_OPEN,
            BoseCatalog.refineFromWire(initial = null, modeConfigSize = 48, supportsLiveAudio = false),
        )
    }

    @Test
    fun `batteryStatus parses earbuds fixture and single-battery payload`() {
        val earbuds = Parsers.batteryStatus(Hex.parse("3cffff013cffff023cffff0450ffff03"))
        assertEquals(60, earbuds.percent)
        assertEquals(
            listOf(BatteryComponent("L", 60), BatteryComponent("R", 60), BatteryComponent("Case", 80)),
            earbuds.components,
        )

        val noCombined = Parsers.batteryStatus(Hex.parse("46ffff0150ffff0264ffff03"))
        assertEquals(70, noCombined.percent)
        assertEquals(3, noCombined.components.size)

        val single = Parsers.batteryStatus(byteArrayOf(85))
        assertEquals(85, single.percent)
        assertTrue(single.components.isEmpty())
    }

    @Test
    fun `qc ultra earbuds 2 reads multi-component battery`() = runTest {
        val fake = FakeBmapDevice(backgroundScope, BoseCatalog.QC_ULTRA_2_EARBUDS)
        val conn = BmapConnection(fake, backgroundScope)
        val hp = Headphones(conn, BoseCatalog.QC_ULTRA_2_EARBUDS)

        val battery = hp.batteryStatus()
        assertEquals(60, battery.percent)
        assertEquals(listOf(BatteryComponent("L", 60), BatteryComponent("R", 60), BatteryComponent("Case", 80)), battery.components)
        conn.close()
    }

    @Test
    fun `qc prince handles 47-byte modes 39-byte writes and PROCESSING+STATUS mode switch`() = runTest {
        val fake = FakeBmapDevice(backgroundScope, BoseCatalog.QC_PRINCE)
        val conn = BmapConnection(fake, backgroundScope)
        val hp = Headphones(conn, BoseCatalog.QC_PRINCE)

        val snapshot = hp.allModes()
        assertEquals(4, snapshot.modes.size)
        assertEquals("Commute", snapshot.modes[2].name)
        assertTrue(snapshot.modes[2].wind)

        hp.selectModeOnly(2)
        assertEquals(2, hp.read(QcUltra2.currentMode))

        val slot = hp.createProfile("Focus", ProfileSettings(cnc = 1, spatial = Spatial.OFF, wind = false, anc = true))
        assertEquals(3, slot)
        val afterCreate = hp.allModes()
        assertEquals("Focus", afterCreate.modes[3].name)
        assertEquals(1, afterCreate.modes[3].cnc)

        hp.deleteMode(3)
        val afterDelete = hp.allModes()
        assertEquals("None", afterDelete.modes[3].name)
        conn.close()
    }

    @Test
    fun `qc45 requires init packet before answering and writes 39-byte mode config`() = runTest {
        val fake = FakeBmapDevice(backgroundScope, BoseCatalog.QC_45)
        val conn = BmapConnection(fake, backgroundScope)
        val hp = Headphones(conn, BoseCatalog.QC_45)

        hp.initSession()
        assertEquals("Bose QC45", hp.read(QcUltra2.productName))
        val slot = hp.createProfile("Office", ProfileSettings(cnc = 2, spatial = Spatial.OFF, wind = true, anc = true))
        assertEquals(3, slot)
        val updated = hp.allModes().modes.first { it.index == 3 }
        assertEquals("Office", updated.name)
        assertTrue(updated.wind)
        conn.close()
    }

    @Test
    fun `qc35 uses init packet and ANR 1_6 without block 31`() = runTest {
        val fake = FakeBmapDevice(backgroundScope, BoseCatalog.QC_35)
        val conn = BmapConnection(fake, backgroundScope)
        val hp = Headphones(conn, BoseCatalog.QC_35)

        hp.initSession()
        assertEquals("Bose QC35 II", hp.read(QcUltra2.productName))
        assertEquals(AnrMode.HIGH, hp.anr())
        assertEquals(AnrMode.LOW, hp.setAnr(AnrMode.LOW))
        assertEquals(AnrMode.OFF, hp.setAnr(AnrMode.OFF))
        assertEquals(AnrMode.OFF, hp.anr())
        conn.close()
    }

    @Test
    fun `ultra open switches read-only modes and reports earbuds battery`() = runTest {
        val fake = FakeBmapDevice(backgroundScope, BoseCatalog.ULTRA_OPEN)
        val conn = BmapConnection(fake, backgroundScope)
        val hp = Headphones(conn, BoseCatalog.ULTRA_OPEN)

        val battery = hp.batteryStatus()
        assertEquals(85, battery.percent)
        assertEquals(3, battery.components.size)
        val modes = hp.allModes().modes
        assertEquals(listOf("Stereo", "Immersion"), modes.map { it.name })
        hp.selectModeOnly(1)
        assertEquals(1, hp.read(QcUltra2.currentMode))
        conn.close()
    }

    @Test
    fun `anr parser rejects unknown codes`() {
        assertThrows(IllegalArgumentException::class.java) {
            Parsers.anr(byteArrayOf(99))
        }
    }
}
