package io.github.sargemck.practicalheadphones.bmap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Every parser against its M0 fixture (fw 10.12.12, redacted). */
class ParsersTest {
    private fun payload(name: String) = Fixtures.load(name).replyPayload

    @Test
    fun firmware() = assertEquals("10.12.12+ga927c9a", Parsers.firmware(payload("0.5-GET")))

    @Test
    fun productName() = assertEquals("Headphone Name 001", Parsers.productName(payload("1.2-GET")))

    @Test
    fun eq() {
        assertEquals(
            listOf(EqBand(0, -10, 10, 0), EqBand(1, -10, 10, 0), EqBand(2, -10, 10, 0)),
            Parsers.eq(payload("1.7-GET")),
        )
        assertEquals(-3, Parsers.eq(Hex.parse("f6 0a fd 00"))[0].current)
    }

    @Test
    fun buttons() = assertEquals(ButtonMapping(0x80, 0x09, 0x0e), Parsers.buttons(payload("1.9-GET")))

    @Test
    fun multipoint() = assertEquals(Multipoint(enabled = true, raw = 0x07), Parsers.multipoint(payload("1.10-GET")))

    @Test
    fun selfVoice() {
        assertEquals(SelfVoice(persist = true, level = SelfVoiceLevel.MEDIUM, extra = 0x0f), Parsers.selfVoice(payload("1.11-GET")))
        assertNull(Parsers.selfVoice(Hex.parse("01 03")).extra)
    }

    @Test
    fun bools() {
        assertFalse(Parsers.bool(payload("1.24-GET")))
        assertFalse(Parsers.bool(payload("1.27-GET")))
        assertTrue(Parsers.bool(Hex.parse("01")))
    }

    @Test
    fun battery() = assertEquals(80, Parsers.battery(payload("2.2-GET")))

    @Test
    fun pairedDevices() {
        val parsed = Parsers.pairedDevices(payload("4.4-GET"))
        assertEquals(1, parsed.header)
        assertEquals((1..5).map { "02:00:00:00:00:0$it" }, parsed.devices.map { it.toString() })
    }

    @Test
    fun deviceInfo() {
        val parsed = Parsers.deviceInfo(payload("4.5-GET"))
        assertEquals("02:00:00:00:00:01", parsed.mac.toString())
        assertEquals(listOf(3, 2, 3), parsed.flags)
        assertEquals("Paired Dev 1", parsed.name)
    }

    @Test
    fun source() {
        assertEquals(Source(supported = 0x000f, type = SourceType.NONE, mac = null), Parsers.source(payload("5.1-GET")))
        val bt = Parsers.source(Hex.parse("00 0f 01 02 00 00 00 00 07"))
        assertEquals("02:00:00:00:00:07", bt.mac.toString())
    }

    @Test
    fun currentMode() = assertEquals(2, Parsers.currentMode(payload("31.3-GET")))

    @Test
    fun favourites() = assertEquals(Favourites(11, setOf(0, 1, 2, 4, 6, 7)), Parsers.favourites(payload("31.8-GET")))

    @Test
    fun audioSettings() = assertEquals(
        AudioSettings(cnc = 0, autoCnc = false, spatial = Spatial.FIXED_TO_HEAD, wind = false, anc = true),
        Parsers.audioSettings(payload("31.10-GET")),
    )

    @Test
    fun `mode configs from the drain`() {
        val modes = Fixtures.load("31.1-START").replyFrames
            .filter { it.address == QcUltra2.modeConfig }
            .map { Parsers.modeConfig(it.payload) }
        assertEquals(
            listOf("Quiet", "Aware", "Immersion", "Cinema", "Walk", "Relax", "Commute", "Run", "None", "None", "None"),
            modes.map { it.name },
        )
        assertEquals((0..10).toList(), modes.map { it.index })
        assertTrue(modes.take(4).none { it.editable })
        assertTrue(modes.drop(4).all { it.editable })
        assertEquals(
            ModeConfig(
                index = 5, promptB1 = 0, promptB2 = 14, editable = true, configured = true, name = "Relax",
                cnc = 9, autoCnc = false, spatial = Spatial.OFF, wind = true, anc = true,
            ),
            modes[5],
        )
        assertEquals(Spatial.FIXED_TO_ROOM, modes[3].spatial)
        assertFalse(modes[8].configured)
    }

    @Test
    fun `short or out of range payloads throw`() {
        assertThrows<IllegalArgumentException> { Parsers.battery(ByteArray(0)) }
        assertThrows<IllegalArgumentException> { Parsers.battery(Hex.parse("ff")) }
        assertThrows<IllegalArgumentException> { Parsers.eq(Hex.parse("f6 0a 00")) }
        assertThrows<IllegalArgumentException> { Parsers.selfVoice(Hex.parse("01 07")) }
        assertThrows<IllegalArgumentException> { Parsers.audioSettings(Hex.parse("00 00 02 00")) }
        assertThrows<IllegalArgumentException> { Parsers.audioSettings(Hex.parse("00 00 05 00 01")) }
        assertThrows<IllegalArgumentException> { Parsers.modeConfig(ByteArray(40)) }
        assertThrows<IllegalArgumentException> { Parsers.pairedDevices(Hex.parse("01 02 00")) }
        assertThrows<IllegalArgumentException> { Parsers.favourites(Hex.parse("0b 00")) }
    }
}
