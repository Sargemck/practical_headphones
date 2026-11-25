package io.github.sargemck.practicalheadphones.state

import io.github.sargemck.practicalheadphones.ui.Samples
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class LastKnownCodecTest {
    @Test
    fun `everything but battery and link round-trips`() {
        val state = Samples.connected
        val expected = state.copy(link = LinkState.Disconnected, battery = Section.Loading)
        assertEquals(expected, LastKnownCodec.decode(LastKnownCodec.encode(state)))
    }

    @Test
    fun `sections that hadn't loaded read back as loading`() {
        val partial = HeadphoneState(name = Section.Loaded("Desk | pair"), eq = Section.Failed("Runtime"))
        assertEquals(HeadphoneState(name = Section.Loaded("Desk | pair")), LastKnownCodec.decode(LastKnownCodec.encode(partial)))
    }

    @Test
    fun `unknown versions and broken files read back as null`() {
        assertNull(LastKnownCodec.decode(""))
        assertNull(LastKnownCodec.decode("v=99\nname=x\n"))
        assertNull(LastKnownCodec.decode("v=2\naudio=not|numbers\n"))
        assertNull(LastKnownCodec.decode("v=3\naudio=not|numbers\n"))
    }

    @Test
    fun `multi-brand unsupported sections capabilities and preset selection round-trip`() {
        val samsung = HeadphoneState(
            deviceBadge = DeviceBadge("Samsung", "Galaxy Buds2 | Pro", experimental = true),
            capabilities = Capabilities(canRename = false, canPair = false, canPowerOff = false, canEditModes = false, editableSlots = IntRange.EMPTY, presetSlots = IntRange.EMPTY, presetNames = emptyList()),
            name = Section.Loaded("Galaxy Buds2 Pro"),
            firmware = Section.Unsupported,
            modes = Section.Unsupported,
            audio = Section.Unsupported,
            discreteAnc = Section.Loaded(DiscreteAncState(listOf(DiscreteAncOption("ANC", "Noise cancelling")), "ANC")),
            eq = Section.Loaded(emptyList()),
            selectedEqPreset = "DYNAMIC",
            eqPresets = listOf(EqPresetChoice("NORMAL", "Normal"), EqPresetChoice("DYNAMIC", "Dynamic")),
            eqBandLabels = emptyList(),
            selfVoice = Section.Unsupported,
            speakToChat = Section.Unsupported,
            autoPlayPause = Section.Unsupported,
            autoAnswer = Section.Unsupported,
            multipoint = Section.Unsupported,
        )
        val decoded = LastKnownCodec.decode(LastKnownCodec.encode(samsung))!!
        assertEquals(samsung.copy(deviceBadge = DeviceBadge("Samsung", "Galaxy Buds2   Pro", experimental = true)), decoded)
    }

    @Test
    fun `the file store saves and loads`() {
        val dir = Files.createTempDirectory("lastknown").toFile()
        val file = File(dir, "last-known.txt")
        FileLastKnownStore(file).apply { assertNull(load()) }.save(Samples.connected)
        // The write runs on the store's own thread; poll until the file has landed.
        val deadline = System.currentTimeMillis() + 5_000
        while (!file.exists() && System.currentTimeMillis() < deadline) Thread.onSpinWait()
        assertEquals(Samples.connected.name, FileLastKnownStore(file).load()?.name)
        dir.deleteRecursively()
    }
}
