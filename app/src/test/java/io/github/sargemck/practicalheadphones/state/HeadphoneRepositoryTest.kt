package io.github.sargemck.practicalheadphones.state

import io.github.sargemck.practicalheadphones.bmap.Address
import io.github.sargemck.practicalheadphones.bmap.AudioSettings
import io.github.sargemck.practicalheadphones.bmap.BmapConnection
import io.github.sargemck.practicalheadphones.bmap.BmapFrame
import io.github.sargemck.practicalheadphones.bmap.BmapOp
import io.github.sargemck.practicalheadphones.bmap.ErrorCodes
import io.github.sargemck.practicalheadphones.bmap.FakeBmapDevice
import io.github.sargemck.practicalheadphones.bmap.Headphones
import io.github.sargemck.practicalheadphones.bmap.Hex
import io.github.sargemck.practicalheadphones.bmap.QcUltra2
import io.github.sargemck.practicalheadphones.bmap.Spatial
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class HeadphoneRepositoryTest {
    private val on = SystemLink(bluetoothOn = true, paired = true, connected = true)
    private val away = on.copy(connected = false)

    private class Harness(
        val repo: HeadphoneRepository,
        val fakes: List<FakeBmapDevice>,
        val messages: List<UserMessage>,
        val states: List<HeadphoneState>,
        val control: Control,
    ) {
        val fake get() = fakes.last()
        val state get() = repo.state.value
    }

    private class Control {
        var failConnect = false
    }

    private fun TestScope.harness(
        live: LiveUpdates = LiveUpdates(polling = false),
        configure: FakeBmapDevice.() -> Unit = {},
    ): Harness {
        val fakes = mutableListOf<FakeBmapDevice>()
        val control = Control()
        val connector = Connector { scope ->
            if (control.failConnect) throw IOException("no answer")
            val fake = FakeBmapDevice(backgroundScope).apply(configure)
            fakes += fake
            BmapConnection(fake, scope).also { Headphones(it).read(QcUltra2.battery) }
        }
        val repo = HeadphoneRepository(backgroundScope, connector, clock = { testScheduler.currentTime }, live = live)
        val messages = mutableListOf<UserMessage>()
        val states = mutableListOf<HeadphoneState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repo.messages.toList(messages) }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repo.state.toList(states) }
        return Harness(repo, fakes, messages, states, control)
    }

    private fun TestScope.connected(
        live: LiveUpdates = LiveUpdates(polling = false),
        configure: FakeBmapDevice.() -> Unit = {},
    ): Harness = harness(live, configure).also {
        it.repo.onSystemLink(on)
        runCurrent()
    }

    private fun FakeBmapDevice.count(address: Address, op: BmapOp) = received.count { it.address == address && it.op == op }

    // Initial load

    @Test
    fun `initial load fills every section`() = runTest {
        val h = connected()
        val s = h.state
        assertEquals(LinkState.Connected, s.link)
        assertTrue(s.fullyLoaded, s.summary())
        assertEquals(80, s.battery.valueOrNull())
        assertEquals("Headphone Name 001", s.name.valueOrNull())
        assertEquals("10.12.12+ga927c9a", s.firmware.valueOrNull())
        assertEquals(Spatial.FIXED_TO_HEAD, s.audio.valueOrNull()?.spatial)
        assertEquals(3, s.eq.valueOrNull()?.size)
        assertTrue(s.multipoint.valueOrNull()!!.enabled)
    }

    @Test
    fun `only configured modes are offered`() = runTest {
        val modes = connected().state.modes.valueOrNull()!!
        assertEquals(listOf("Quiet", "Aware", "Immersion", "Cinema", "Walk", "Relax", "Commute", "Run"), modes.modes.map { it.name })
        assertEquals(2, modes.current)
    }

    @Test
    fun `no connect without the system reporting connected`() = runTest {
        val h = harness()
        h.repo.onSystemLink(away)
        runCurrent()
        assertEquals(LinkState.Disconnected, h.state.link)
        assertTrue(h.fakes.isEmpty())
    }

    @Test
    fun `silent address loads as unsupported, the rest load`() = runTest {
        val h = connected { silent += QcUltra2.autoPlayPause.address }
        advanceTimeBy(10.seconds)
        assertEquals(Section.Unsupported, h.state.autoPlayPause)
        assertTrue(h.state.fullyLoaded)
        assertEquals(LinkState.Connected, h.state.link)
    }

    @Test
    fun `an ERROR read retries once, then the section fails`() = runTest {
        val h = connected { forceError[QcUltra2.eq.address to BmapOp.GET] = ErrorCodes.RUNTIME }
        assertTrue(h.state.eq is Section.Failed)
        assertEquals(2, h.fake.count(QcUltra2.eq.address, BmapOp.GET))
    }

    @Test
    fun `sections stay loading until their read lands`() = runTest {
        val h = connected { delays[QcUltra2.eq.address] = 1.seconds }
        assertEquals(Section.Loading, h.state.eq)
        assertTrue(h.state.modes is Section.Loaded)
        advanceTimeBy(2.seconds)
        assertTrue(h.state.eq is Section.Loaded)
    }

    // Writes

    @Test
    fun `audio write is optimistic and reconciled with the reply`() = runTest {
        val h = connected()
        h.repo.setAudio { it.copy(cnc = 5) }
        assertEquals(5, h.state.audio.valueOrNull()?.cnc)
        runCurrent()
        assertEquals(5, h.state.audio.valueOrNull()?.cnc)
        assertEquals("05 00 02 00 01", Hex.format(h.fake.payload(QcUltra2.audioSettings.address)!!))
    }

    @Test
    fun `rapid audio writes are conflated and the last value wins`() = runTest {
        val h = connected()
        for (cnc in 1..10) h.repo.setAudio { it.copy(cnc = cnc) }
        runCurrent()
        assertTrue(h.fake.count(QcUltra2.audioSettings.address, BmapOp.SETGET) <= 2)
        assertEquals(10, h.state.audio.valueOrNull()?.cnc)
        assertEquals(10, h.fake.payload(QcUltra2.audioSettings.address)!![0].toInt())
    }

    @Test
    fun `audio write failure rolls back and tells the user`() = runTest {
        val h = connected { forceError[QcUltra2.audioSettings.address to BmapOp.SETGET] = ErrorCodes.RUNTIME }
        h.repo.toggleAnc()
        assertFalse(h.state.audio.valueOrNull()!!.anc)
        runCurrent()
        assertTrue(h.state.audio.valueOrNull()!!.anc)
        assertEquals(listOf(UserMessage("Couldn't change noise control")), h.messages)
    }

    @Test
    fun `write reply is parsed, with no extra GET`() = runTest {
        val h = connected()
        h.repo.setAutoAnswer(true)
        runCurrent()
        assertEquals(true, h.state.autoAnswer.valueOrNull())
        assertEquals(QcUltra2.autoAnswer.address to BmapOp.SETGET, h.fake.received.last().let { it.address to it.op })
    }

    @Test
    fun `write ERROR rolls back`() = runTest {
        val h = connected { forceError[QcUltra2.autoAnswer.address to BmapOp.SETGET] = ErrorCodes.RUNTIME }
        h.repo.setAutoAnswer(true)
        assertEquals(true, h.state.autoAnswer.valueOrNull())
        runCurrent()
        assertEquals(false, h.state.autoAnswer.valueOrNull())
        assertEquals(1, h.messages.size)
    }

    @Test
    fun `mode switch takes the audio settings it produced`() = runTest {
        val h = connected()
        h.repo.selectMode(0)
        assertEquals(0, h.state.modes.valueOrNull()?.current)
        runCurrent()
        assertEquals(
            AudioSettings(cnc = 0, autoCnc = false, spatial = Spatial.OFF, wind = false, anc = true),
            h.state.audio.valueOrNull(),
        )
    }

    @Test
    fun `switching to an empty slot is refused`() = runTest {
        val h = connected()
        h.repo.selectMode(8)
        runCurrent()
        assertEquals(0, h.fake.count(QcUltra2.selectMode, BmapOp.START))
        assertEquals(listOf(UserMessage("That mode doesn't exist")), h.messages)
    }

    @Test
    fun `next favourite mode`() = runTest {
        val h = connected()
        h.repo.nextFavouriteMode()
        runCurrent()
        assertEquals(4, h.state.modes.valueOrNull()?.current)
        assertEquals("04 00", h.fake.received.last { it.address == QcUltra2.selectMode }.payload.let(Hex::format))
    }

    @Test
    fun `EQ preset writes only the bands that differ, in order, as one optimistic change`() = runTest {
        val h = connected()
        h.repo.setEq(-2, 0, 2)
        assertEquals(listOf(-2, 0, 2), h.state.eq.valueOrNull()!!.map { it.current })
        runCurrent()
        val writes = h.fake.received.filter { it.address == QcUltra2.eq.address && it.op == BmapOp.SETGET }
        assertEquals(listOf("fe 00", "02 02"), writes.map { Hex.format(it.payload) })
        assertEquals(listOf(-2, 0, 2), h.state.eq.valueOrNull()!!.map { it.current })
        assertTrue(h.messages.isEmpty())
    }

    @Test
    fun `EQ preset failure rolls back as one`() = runTest {
        val h = connected { forceError[QcUltra2.eq.address to BmapOp.SETGET] = ErrorCodes.RUNTIME }
        h.repo.setEq(6, 0, 6)
        runCurrent()
        assertEquals(listOf(0, 0, 0), h.state.eq.valueOrNull()!!.map { it.current })
        assertEquals(listOf(UserMessage("Couldn't change the equaliser")), h.messages)
    }

    @Test
    fun `EQ preset failing part way shows what the device confirmed`() = runTest {
        val h = connected()
        h.fake.delays[QcUltra2.eq.address] = 100.milliseconds
        h.repo.setEq(-2, 3, 2)
        advanceTimeBy(50.milliseconds) // the bass write is received; its reply lands at 100 ms
        h.fake.forceError[QcUltra2.eq.address to BmapOp.SETGET] = ErrorCodes.RUNTIME
        advanceTimeBy(1.seconds)
        assertEquals(listOf(-2, 0, 0), h.state.eq.valueOrNull()!!.map { it.current })
        assertEquals(1, h.messages.size)
    }

    @Test
    fun `retry re-reads only the failed sections`() = runTest {
        val h = connected { forceError[QcUltra2.eq.address to BmapOp.GET] = ErrorCodes.RUNTIME }
        assertTrue(h.state.eq is Section.Failed)
        h.fake.forceError.clear()
        val batteryReads = h.fake.count(QcUltra2.battery.address, BmapOp.GET)
        h.repo.retryFailed()
        runCurrent()
        assertTrue(h.state.eq is Section.Loaded)
        assertEquals(3, h.fake.count(QcUltra2.eq.address, BmapOp.GET))
        assertEquals(batteryReads, h.fake.count(QcUltra2.battery.address, BmapOp.GET))
    }

    @Test
    fun `retry of failed modes reloads modes and audio`() = runTest {
        val h = connected { forceError[QcUltra2.allModes to BmapOp.START] = ErrorCodes.RUNTIME }
        assertTrue(h.state.modes is Section.Failed)
        h.fake.forceError.clear()
        h.repo.retryFailed()
        runCurrent()
        assertEquals(8, h.state.modes.valueOrNull()?.modes?.size)
        assertTrue(h.state.audio is Section.Loaded)
    }

    @Test
    fun `star toggles a favourite from cached state`() = runTest {
        val h = connected()
        h.repo.toggleFavourite(5)
        assertEquals(setOf(0, 1, 2, 4, 5, 6, 7), h.state.modes.valueOrNull()?.favourites?.slots)
        runCurrent()
        h.repo.toggleFavourite(0)
        runCurrent()
        assertEquals(setOf(1, 2, 4, 5, 6, 7), h.state.modes.valueOrNull()?.favourites?.slots)
        assertEquals(setOf(1, 2, 4, 5, 6, 7), QcUltra2.favourites.parse(h.fake.payload(QcUltra2.favourites.address)!!).slots)
    }

    // Live updates

    @Test
    fun `unsolicited frames update state`() = runTest {
        val h = connected()
        h.fake.pushUnsolicited(BmapFrame(31, 10, BmapOp.STATUS, Hex.parse("03 00 00 01 01")))
        h.fake.pushUnsolicited(BmapFrame(2, 2, BmapOp.STATUS, Hex.parse("4b ff ff 00")))
        runCurrent()
        assertEquals(3, h.state.audio.valueOrNull()?.cnc)
        assertEquals(75, h.state.battery.valueOrNull())
    }

    @Test
    fun `polling cadence follows UI visibility and the screen`() = runTest {
        val h = connected(LiveUpdates())
        val mode = QcUltra2.currentMode.address
        h.repo.onUiVisible(true)
        runCurrent()
        advanceTimeBy(10_100.milliseconds)
        assertEquals(5, h.fake.count(mode, BmapOp.GET))

        h.repo.onUiVisible(false)
        runCurrent()
        advanceTimeBy(120_100.milliseconds)
        assertEquals(7, h.fake.count(mode, BmapOp.GET))

        h.repo.onScreen(false)
        runCurrent()
        advanceTimeBy(600.seconds)
        assertEquals(7, h.fake.count(mode, BmapOp.GET))

        h.repo.onScreen(true)
        runCurrent()
        assertEquals(8, h.fake.count(mode, BmapOp.GET))
    }

    @Test
    fun `polling off sends nothing`() = runTest {
        val h = connected()
        h.repo.onUiVisible(true)
        advanceTimeBy(60.seconds)
        assertEquals(0, h.fake.count(QcUltra2.currentMode.address, BmapOp.GET))
    }

    @Test
    fun `a poll queued before a write doesn't revert the optimistic value`() = runTest {
        val h = connected(LiveUpdates()) { delays[QcUltra2.audioSettings.address] = 100.milliseconds }
        h.repo.onUiVisible(true)
        runCurrent()
        advanceTimeBy(2_050.milliseconds) // the poll's GET 31.10 is in flight until 2 100 ms
        h.repo.setAudio { it.copy(cnc = 7) }
        val from = h.states.size - 1
        advanceTimeBy(500.milliseconds)
        val cncs = h.states.drop(from).mapNotNull { it.audio.valueOrNull()?.cnc }
        assertTrue(cncs.all { it == 7 }, "audio went back during the write: $cncs")
        assertEquals(7, h.state.audio.valueOrNull()?.cnc)
    }

    // Link

    @Test
    fun `another app takes the link, nothing reconnects`() = runTest {
        val h = connected()
        h.fake.disconnect()
        runCurrent()
        advanceTimeBy(2_100.milliseconds)
        assertEquals(LinkState.AnotherApp, h.state.link)
        assertEquals(Section.Loading, h.state.battery)
        advanceTimeBy(60.seconds)
        assertEquals(1, h.fakes.size)
    }

    @Test
    fun `sleep is a quiet disconnect`() = runTest {
        val h = connected()
        h.fake.disconnect()
        runCurrent()
        advanceTimeBy(800.milliseconds)
        h.repo.onSystemLink(away)
        advanceTimeBy(60.seconds)
        assertEquals(LinkState.Disconnected, h.state.link)
        assertEquals(1, h.fakes.size)
    }

    @Test
    fun `disconnect during load leaves nothing shown as loaded`() = runTest {
        val h = connected { delays[QcUltra2.eq.address] = 1.seconds }
        h.fake.disconnect()
        runCurrent()
        advanceTimeBy(3.seconds)
        assertEquals(LinkState.AnotherApp, h.state.link)
        assertEquals(HeadphoneState(link = LinkState.AnotherApp), h.state)
    }

    @Test
    fun `power off drop is expected`() = runTest {
        val h = connected()
        h.repo.powerOff()
        runCurrent()
        advanceTimeBy(2_100.milliseconds)
        assertEquals(LinkState.HeadphonesOff, h.state.link)
    }

    @Test
    fun `fresh connected event reconnects`() = runTest {
        val h = connected()
        h.repo.onSystemLink(away)
        runCurrent()
        h.repo.onSystemLink(on)
        runCurrent()
        assertEquals(LinkState.Connected, h.state.link)
        assertEquals(2, h.fakes.size)
        assertTrue(h.state.fullyLoaded)
    }

    @Test
    fun `connect failure waits for the user`() = runTest {
        val h = harness()
        h.control.failConnect = true
        h.repo.onSystemLink(on)
        runCurrent()
        assertEquals(LinkState.ConnectFailed, h.state.link)
        h.control.failConnect = false
        h.repo.reconnect()
        runCurrent()
        assertEquals(LinkState.Connected, h.state.link)
    }

    @Test
    fun `writes while not connected tell the user`() = runTest {
        val h = harness()
        h.repo.toggleAnc()
        h.repo.selectMode(0)
        assertEquals(2, h.messages.size)
    }
}
