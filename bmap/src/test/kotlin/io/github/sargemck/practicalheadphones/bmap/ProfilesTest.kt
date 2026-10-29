package io.github.sargemck.practicalheadphones.bmap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ProfilesTest {
    /** Modes as captured in M0: presets 0..3, customs 4..7, free slots 8..10. */
    private val modes = Fixtures.load("31.1-START").replyFrames
        .filter { it.address == QcUltra2.modeConfig }
        .map { Parsers.modeConfig(it.payload) }

    private val settings = ProfileSettings(cnc = 4, spatial = Spatial.FIXED_TO_ROOM, wind = false, anc = true)

    @Test
    fun `first free slot is 8`() = assertEquals(8, Profiles.findFreeSlot(modes))

    @Test
    fun `blank name is free too, configured bit ignored`() {
        val blanked = modes.map { if (it.index == 5) it.copy(name = "", configured = true) else it }
        assertEquals(5, Profiles.findFreeSlot(blanked))
    }

    @Test
    fun `no free slot`() {
        val full = modes.map { if (it.index >= 8) it.copy(name = "Mode ${it.index}") else it }
        assertNull(Profiles.findFreeSlot(full))
        assertThrows<ProfileError.NoFreeSlot> { Profiles.create(full, "Hike", settings) }
    }

    @Test
    fun `create uses the free slot`() {
        val write = Profiles.create(modes, "Hike", settings)
        assertEquals(ModeConfigWrite(8, 0, 0, "Hike", 4, Spatial.FIXED_TO_ROOM, wind = false, anc = true), write)
    }

    @Test
    fun `names are trimmed before validating and writing`() {
        assertEquals("Hike", Profiles.create(modes, "  Hike ", settings).name)
        assertEquals("Calm", Profiles.update(modes, "Relax", rename = " Calm ").name)
        Profiles.validateName(" " + "x".repeat(31) + " ", modes)
    }

    @Test
    fun `preset names refused in any case`() {
        for (name in listOf("quiet", "AWARE", " Immersion ", "cinema")) {
            assertThrows<ProfileError.InvalidName>(name) { Profiles.create(modes, name, settings) }
        }
    }

    @Test
    fun `invalid names refused`() {
        for (name in listOf("", "   ", "none", "x".repeat(32))) {
            assertThrows<ProfileError.InvalidName>(name) { Profiles.validateName(name, modes) }
        }
    }

    @Test
    fun `presets can't be updated or deleted`() {
        assertThrows<ProfileError.PresetLocked> { Profiles.update(modes, "Quiet", settings = settings) }
        assertThrows<ProfileError.PresetLocked> { Profiles.delete(modes, "cinema") }
    }

    @Test
    fun `update keeps prompt bytes and unchanged fields`() {
        val write = Profiles.update(modes, "relax", rename = "Calm")
        assertEquals(ModeConfigWrite(5, 0, 14, "Calm", 9, Spatial.OFF, wind = true, anc = true), write)
        val changed = Profiles.update(modes, "Relax", settings = settings)
        assertEquals(ModeConfigWrite(5, 0, 14, "Relax", 4, Spatial.FIXED_TO_ROOM, wind = false, anc = true), changed)
    }

    @Test
    fun `rename validates the new name, but keeping the name is fine`() {
        assertThrows<ProfileError.InvalidName> { Profiles.update(modes, "Walk", rename = "Quiet") }
        Profiles.update(modes, "Walk", rename = "WALK")
    }

    @Test
    fun `unknown profile`() {
        assertThrows<ProfileError.NotFound> { Profiles.update(modes, "Hike", settings = settings) }
    }

    @Test
    fun `custom named like a preset is found before the preset`() {
        val clash = modes.map { if (it.index == 6) it.copy(name = "Quiet") else it }
        assertEquals(6, Profiles.findProfile("quiet", clash)?.index)
    }

    @Test
    fun `delete writes the None sentinel`() {
        assertEquals(
            ModeConfigWrite(7, 0, 0, "None", 0, Spatial.OFF, wind = false, anc = false),
            Profiles.delete(modes, "Run"),
        )
    }
}
