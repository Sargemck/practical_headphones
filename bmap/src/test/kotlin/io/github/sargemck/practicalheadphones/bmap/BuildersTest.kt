package io.github.sargemck.practicalheadphones.bmap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BuildersTest {
    @Test
    fun `audio settings always send autoCnc 0`() {
        val settings = AudioSettings(cnc = 7, autoCnc = true, spatial = Spatial.FIXED_TO_ROOM, wind = true, anc = false)
        assertEquals("07 00 01 01 00", Hex.format(Builders.audioSettings(settings)))
    }

    @Test
    fun `audio settings round trip the fixture`() {
        val fixture = Fixtures.load("31.10-GET").replyPayload
        assertEquals(Hex.format(fixture), Hex.format(Builders.audioSettings(Parsers.audioSettings(fixture))))
    }

    @Test
    fun `mode config is 40 bytes with a NUL padded name`() {
        val bytes = Builders.modeConfig(
            ModeConfigWrite(
                index = 8, promptB1 = 0, promptB2 = 21, name = "Hike",
                cnc = 3, spatial = Spatial.FIXED_TO_HEAD, wind = true, anc = true,
            ),
        )
        assertEquals(40, bytes.size)
        assertEquals("08 00 15", Hex.format(bytes.copyOfRange(0, 3)))
        assertEquals("Hike", bytes.copyOfRange(3, 7).toString(Charsets.UTF_8))
        assertEquals(List(28) { 0.toByte() }, bytes.copyOfRange(7, 35).toList())
        assertEquals("03 00 02 01 01", Hex.format(bytes.copyOfRange(35, 40)))
    }

    @Test
    fun `names up to 31 UTF-8 bytes`() {
        val ok = "é".repeat(15) + "a" // 31 bytes
        Builders.productName(ok)
        Builders.modeConfig(ModeConfigWrite(8, 0, 0, ok, 0, Spatial.OFF, wind = false, anc = false))
        val tooLong = "é".repeat(16) // 32 bytes
        assertThrows<IllegalArgumentException> { Builders.productName(tooLong) }
        assertThrows<IllegalArgumentException> {
            Builders.modeConfig(ModeConfigWrite(8, 0, 0, tooLong, 0, Spatial.OFF, wind = false, anc = false))
        }
        assertThrows<IllegalArgumentException> { Builders.productName("") }
        assertThrows<IllegalArgumentException> { Builders.productName("   ") }
        assertEquals("Desk", Builders.productName(" Desk  ").toString(Charsets.UTF_8))
    }

    @Test
    fun `eq band is signed value then band`() {
        assertEquals("fd 01", Hex.format(Builders.eqBand(EqBandSetting(bandId = 1, value = -3))))
        assertThrows<IllegalArgumentException> { Builders.eqBand(EqBandSetting(bandId = 0, value = 11)) }
        assertThrows<IllegalArgumentException> { Builders.eqBand(EqBandSetting(bandId = 3, value = 0)) }
    }

    @Test
    fun `self voice persists`() = assertEquals("01 03", Hex.format(Builders.selfVoice(SelfVoiceLevel.LOW)))

    @Test
    fun `favourites round trip the fixture`() {
        val fixture = Fixtures.load("31.8-GET").replyPayload
        assertEquals(Hex.format(fixture), Hex.format(Builders.favourites(Parsers.favourites(fixture))))
        assertEquals("0b 01 00", Hex.format(Builders.favourites(Favourites(11, setOf(8)))))
    }

    @Test
    fun `select mode has no voice prompt`() = assertEquals("04 00", Hex.format(Builders.selectMode(4)))
}
