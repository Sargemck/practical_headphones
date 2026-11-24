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
