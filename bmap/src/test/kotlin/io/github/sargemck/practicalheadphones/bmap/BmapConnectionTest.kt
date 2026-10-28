package io.github.sargemck.practicalheadphones.bmap

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class BmapConnectionTest {
    private val battery = QcUltra2.battery.address
    private val autoPlayPause = QcUltra2.autoPlayPause.address

    private fun FakeBmapDevice.count(address: Address) = received.count { it.address == address }

    private fun status(address: Address, hex: String) = BmapFrame(address.fblock, address.func, BmapOp.STATUS, Hex.parse(hex))

    // Matching

    @Test
    fun `GET is answered without any wait`() = runTest {
        val r = rig()
        assertEquals(80, r.headphones.read(QcUltra2.battery))
        assertEquals(0, currentTime)
    }

    @Test
    fun `replies split into single bytes`() = runTest {
        val r = rig { chunkSize = 1 }
        assertEquals(11, r.headphones.allModes().modes.size)
        assertEquals(80, r.headphones.read(QcUltra2.battery))
    }

    @Test
    fun `stray frame in the same chunk goes to unsolicited`() = runTest {
        val stray = status(autoPlayPause, "01")
        val r = rig { beforeReply += stray }
        assertEquals(80, r.headphones.read(QcUltra2.battery))
        assertEquals(listOf(stray), r.unsolicited)
    }

    @Test
    fun `late reply after a request completed goes to unsolicited`() = runTest {
        val r = rig()
        r.headphones.read(QcUltra2.battery)
        r.fake.pushUnsolicited(status(battery, "4f ff ff 00"))
        runCurrent()
        assertEquals(1, r.unsolicited.size)
    }

    @Test
    fun `PROCESSING restarts the reply timer`() = runTest {
        val r = rig { ignoreNext[battery] = 1 }
        val outcome = background { r.headphones.read(QcUltra2.battery) }
        advanceTimeBy(2_000)
        r.fake.pushUnsolicited(BmapFrame(2, 2, BmapOp.PROCESSING))
        advanceTimeBy(2_500) // 4.5 s after sending: past 3 s, but only 2.5 s after PROCESSING
        r.fake.pushUnsolicited(status(battery, "50 ff ff 00"))
        runCurrent()
        assertEquals(80, outcome()?.getOrThrow())
        assertEquals(1, r.fake.count(battery))
    }

    @Test
    fun `endless PROCESSING is capped at 10 s per attempt, then treated as a timeout`() = runTest {
        val r = rig { processingForever += autoPlayPause }
        assertThrows<BmapException.Unsupported> { r.headphones.read(QcUltra2.autoPlayPause) }
        assertEquals(20_000, currentTime)
        assertEquals(ConnectionState.Open, r.connection.state.value)
    }

    @Test
    fun `ERROR reply throws with its code`() = runTest {
        val r = rig()
        val e = assertThrows<BmapException.DeviceError> { r.connection.request(BmapFrame(1, 4, BmapOp.GET)) }
        assertEquals(ErrorCodes.FUNC_NOT_SUPPORTED, e.code)
        assertEquals(Address(1, 4), e.address)
    }

    // Timeouts

    @Test
    fun `timeout then a successful retry`() = runTest {
        val r = rig { ignoreNext[battery] = 1 }
        assertEquals(80, r.headphones.read(QcUltra2.battery))
        assertEquals(3_000, currentTime)
        assertEquals(2, r.fake.count(battery))
    }

    @Test
    fun `silent address while the link answers is unsupported and the connection stays open`() = runTest {
        val r = rig { silent += autoPlayPause }
        assertThrows<BmapException.Unsupported> { r.headphones.read(QcUltra2.autoPlayPause) }
        assertEquals(6_000, currentTime)
        assertEquals(2, r.fake.count(autoPlayPause))
        assertEquals(1, r.fake.count(battery))
        assertEquals(ConnectionState.Open, r.connection.state.value)
        assertEquals(80, r.headphones.read(QcUltra2.battery))
    }

    @Test
    fun `nothing answers, so the connection closes as unresponsive`() = runTest {
        val r = rig { silenceAll = true }
        assertThrows<BmapException.Timeout> { r.headphones.read(QcUltra2.autoPlayPause) }
        assertEquals(9_000, currentTime)
        assertEquals(ConnectionState.Closed(CloseReason.Unresponsive), r.connection.state.value)
        assertThrows<BmapException.Disconnected> { r.headphones.read(QcUltra2.battery) }
    }

    @Test
    fun `the liveness address itself timing out closes without a separate check`() = runTest {
        val r = rig { silent += battery }
        assertThrows<BmapException.Timeout> { r.headphones.read(QcUltra2.battery) }
        assertEquals(6_000, currentTime)
        assertEquals(2, r.fake.count(battery))
        assertEquals(ConnectionState.Closed(CloseReason.Unresponsive), r.connection.state.value)
    }

    // Commands that drop the link

    @Test
    fun `power off succeeds on disconnect and is sent once`() = runTest {
        val r = rig()
        r.headphones.powerOff()
        assertEquals(ConnectionState.Closed(CloseReason.RemoteClosed), r.connection.state.value)
        assertEquals(listOf("00"), r.fake.received.filter { it.address == QcUltra2.powerOff }.map { Hex.format(it.payload) })
    }

    @Test
    fun `pairing mode succeeds on disconnect and is sent once`() = runTest {
        val r = rig()
        r.headphones.pairingMode()
        assertEquals(ConnectionState.Closed(CloseReason.RemoteClosed), r.connection.state.value)
        assertEquals(1, r.fake.count(QcUltra2.pairingMode))
    }

    @Test
    fun `power off with no reply and no disconnect times out without retry or close`() = runTest {
        val r = rig { silent += QcUltra2.powerOff }
        assertThrows<BmapException.Timeout> { r.headphones.powerOff() }
        assertEquals(1, r.fake.count(QcUltra2.powerOff))
        assertEquals(ConnectionState.Open, r.connection.state.value)
    }

    // Drain

    @Test
    fun `drain ends on RESULT without waiting for silence`() = runTest {
        val r = rig()
        val snapshot = r.headphones.allModes()
        assertEquals(0, currentTime)
        assertEquals(11, snapshot.modes.size)
        assertEquals(2, snapshot.currentMode)
        assertEquals(Favourites(11, setOf(0, 1, 2, 4, 6, 7)), snapshot.favourites)
        assertEquals(Spatial.FIXED_TO_HEAD, snapshot.audioSettings?.spatial)
        assertTrue(r.unsolicited.isEmpty())
    }

    @Test
    fun `drain falls back to 500 ms of silence when RESULT is missing`() = runTest {
        val r = rig { dropDrainResult = true }
        assertEquals(11, r.headphones.allModes().modes.size)
        assertEquals(500, currentTime)
    }

    @Test
    fun `repeated address in a drain, the last value wins`() = runTest {
        val walk = Parsers.modeConfig(FakeBmapDevice(backgroundScope).mode(4)!!)
        val renamed = FakeBmapDevice(backgroundScope).mode(4)!!.also { "Hike".toByteArray().copyInto(it, 6) }
        val r = rig {
            drainExtra += status(QcUltra2.audioSettings.address, "00 00 00 00 00")
            drainExtra += BmapFrame(31, 6, BmapOp.STATUS, renamed)
        }
        val snapshot = r.headphones.allModes()
        assertFalse(snapshot.audioSettings!!.anc)
        assertEquals("Walk", walk.name)
        assertEquals("Hike", snapshot.modes.single { it.index == 4 }.name)
        assertEquals(11, snapshot.modes.size)
    }

    @Test
    fun `non-31 frame during a drain goes to unsolicited`() = runTest {
        val stray = status(battery, "4f ff ff 00")
        val r = rig { drainExtra += stray }
        assertEquals(11, r.headphones.allModes().modes.size)
        assertEquals(listOf(stray), r.unsolicited)
    }

    // Link

    @Test
    fun `garbage before a reply is skipped and logged`() = runTest {
        val r = rig()
        r.fake.pushRaw(Hex.parse("ff ff"))
        assertEquals(80, r.headphones.read(QcUltra2.battery))
        assertEquals("ff ff", Hex.format(r.log.filterIsInstance<LogEvent.Skipped>().single().bytes))
    }

    @Test
    fun `disconnect during a request`() = runTest {
        val r = rig { silent += battery }
        val outcome = background { r.headphones.read(QcUltra2.battery) }
        advanceTimeBy(1_000)
        r.fake.disconnect()
        runCurrent()
        assertTrue(outcome()?.exceptionOrNull() is BmapException.Disconnected)
        assertEquals(ConnectionState.Closed(CloseReason.RemoteClosed), r.connection.state.value)
    }

    @Test
    fun `IOException on read closes with IoError`() = runTest {
        val r = rig()
        r.fake.failRead("broken pipe")
        runCurrent()
        assertEquals(ConnectionState.Closed(CloseReason.IoError("broken pipe")), r.connection.state.value)
        assertThrows<BmapException.Disconnected> { r.headphones.read(QcUltra2.battery) }
    }

    @Test
    fun `local close`() = runTest {
        val r = rig()
        r.connection.close()
        assertEquals(ConnectionState.Closed(CloseReason.Local), r.connection.state.value)
        assertThrows<BmapException.Disconnected> { r.headphones.read(QcUltra2.battery) }
    }

    @Test
    fun `SET and the Authentication block are refused and never written`() = runTest {
        val r = rig()
        assertThrows<BmapException.Refused> { r.connection.request(BmapFrame(1, 2, BmapOp.SET, Hex.parse("41"))) }
        assertThrows<BmapException.Refused> { r.connection.request(BmapFrame(18, 1, BmapOp.GET)) }
        assertTrue(r.fake.received.isEmpty())
    }

    @Test
    fun `concurrent requests run one at a time`() = runTest {
        val r = rig { delays[battery] = 50.milliseconds }
        val results = (1..10).map { async { r.headphones.read(QcUltra2.battery) } }.awaitAll()
        assertEquals(List(10) { 80 }, results)
        assertEquals(500, currentTime)
    }

    @Test
    fun `no SET or Authentication block frame reaches the device in a full session`() = runTest {
        val r = rig()
        r.headphones.allModes()
        r.headphones.selectMode(4)
        r.headphones.createProfile("Hike", ProfileSettings(3, Spatial.OFF, wind = false, anc = true))
        r.headphones.deleteProfile("Hike")
        assertTrue(r.fake.received.none { it.op == BmapOp.SET || it.fblock == 18 })
    }
}
