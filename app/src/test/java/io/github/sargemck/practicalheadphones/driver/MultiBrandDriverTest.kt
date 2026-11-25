package io.github.sargemck.practicalheadphones.driver

import io.github.sargemck.practicalheadphones.bmap.BmapConnection
import io.github.sargemck.practicalheadphones.bmap.BoseCatalog
import io.github.sargemck.practicalheadphones.bmap.FakeBmapDevice
import io.github.sargemck.practicalheadphones.jbl.FakeJblDevice
import io.github.sargemck.practicalheadphones.jbl.JblConnection
import io.github.sargemck.practicalheadphones.nothing.FakeNothingDevice
import io.github.sargemck.practicalheadphones.nothing.NothingConnection
import io.github.sargemck.practicalheadphones.samsung.FakeSamsungDevice
import io.github.sargemck.practicalheadphones.samsung.SamsungConnection
import io.github.sargemck.practicalheadphones.sony.FakeSonyDevice
import io.github.sargemck.practicalheadphones.sony.SonyConnection
import io.github.sargemck.practicalheadphones.soundcore.FakeSoundcoreDevice
import io.github.sargemck.practicalheadphones.soundcore.SoundcoreConnection
import io.github.sargemck.practicalheadphones.state.HeadphoneRepository
import io.github.sargemck.practicalheadphones.state.LinkState
import io.github.sargemck.practicalheadphones.state.LiveUpdates
import io.github.sargemck.practicalheadphones.state.SystemLink
import io.github.sargemck.practicalheadphones.state.valueOrNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MultiBrandDriverTest {
    private val connected = SystemLink(bluetoothOn = true, paired = true, connected = true)

    @Test
    fun `SonyDriver loads WH-1000XM5 state and switches discrete ANC and Speak-to-Chat`() = runTest {
        val repo = HeadphoneRepository(
            scope = backgroundScope,
            driverConnector = DriverConnector { s ->
                SonyDriver(SonyConnection(FakeSonyDevice(protocolV2 = true), s), s, "WH-1000XM5")
            },
            clock = { testScheduler.currentTime },
            live = LiveUpdates(polling = false),
        )
        repo.onSystemLink(connected)
        runCurrent()

        val state = repo.state.value
        assertEquals(LinkState.Connected, state.link)
        assertEquals("WH-1000XM5", state.name.valueOrNull())
        assertEquals(82, state.battery.valueOrNull())
        assertEquals("ANC", state.discreteAnc.valueOrNull()?.selectedId)
        assertEquals("OFF", state.selectedEqPreset)
        assertTrue(state.deviceBadge?.experimental == true)

        repo.setDiscreteAnc("AMBIENT")
        runCurrent()
        repo.setAmbientLevel(18)
        runCurrent()
        repo.setFocusOnVoice(true)
        runCurrent()
        repo.setSpeakToChat(true)
        runCurrent()
        repo.setEqPreset("BASS_BOOST")
        runCurrent()

        val updated = repo.state.value
        assertEquals("AMBIENT", updated.discreteAnc.valueOrNull()?.selectedId)
        assertEquals(18, updated.discreteAnc.valueOrNull()?.ambientLevel)
        assertEquals(true, updated.discreteAnc.valueOrNull()?.focusOnVoice)
        assertEquals(true, updated.speakToChat.valueOrNull())
        assertEquals("BASS_BOOST", updated.selectedEqPreset)
    }

    @Test
    fun `SoundcoreDriver loads Space Q45 state and switches mode and EQ preset`() = runTest {
        val repo = HeadphoneRepository(
            scope = backgroundScope,
            driverConnector = DriverConnector { s ->
                SoundcoreDriver(SoundcoreConnection(FakeSoundcoreDevice(soundModeWidth = 6), s), s, "Space Q45")
            },
            clock = { testScheduler.currentTime },
            live = LiveUpdates(polling = false),
        )
        repo.onSystemLink(connected)
        runCurrent()

        val state = repo.state.value
        assertEquals(80, state.battery.valueOrNull())
        assertEquals("ANC", state.discreteAnc.valueOrNull()?.selectedId)
        assertEquals(8, state.eq.valueOrNull()?.size)

        repo.setDiscreteAnc("TRANSPARENCY")
        repo.setEqPreset("ACOUSTIC")
        runCurrent()

        assertEquals("TRANSPARENCY", repo.state.value.discreteAnc.valueOrNull()?.selectedId)
        assertEquals(listOf(4, 1, 2, 2, 4, 4, 4, 2), repo.state.value.eq.valueOrNull()?.map { it.current })
    }

    @Test
    fun `SamsungDriver loads Galaxy Buds2 Pro L-R-Case battery and switches ANC`() = runTest {
        val repo = HeadphoneRepository(
            scope = backgroundScope,
            driverConnector = DriverConnector { s ->
                SamsungDriver(SamsungConnection(FakeSamsungDevice(), s), s, "Galaxy Buds2 Pro")
            },
            clock = { testScheduler.currentTime },
            live = LiveUpdates(polling = false),
        )
        repo.onSystemLink(connected)
        runCurrent()

        val state = repo.state.value
        assertEquals(84, state.battery.valueOrNull())
        assertEquals(3, state.batteryParts.size)
        assertEquals("ANC", state.discreteAnc.valueOrNull()?.selectedId)
        assertEquals("NORMAL", state.selectedEqPreset)
        assertTrue(state.eq.valueOrNull()!!.isEmpty())

        repo.setDiscreteAnc("AMBIENT")
        runCurrent()
        repo.setEqPreset("DYNAMIC")
        runCurrent()
        assertEquals("AMBIENT", repo.state.value.discreteAnc.valueOrNull()?.selectedId)
        assertEquals("DYNAMIC", repo.state.value.selectedEqPreset)
    }

    @Test
    fun `BoseDriver loads QC Ultra Earbuds 2 battery parts and experimental badge`() = runTest {
        val repo = HeadphoneRepository(
            scope = backgroundScope,
            driverConnector = DriverConnector { s ->
                BoseDriver(BmapConnection(FakeBmapDevice(s, BoseCatalog.QC_ULTRA_2_EARBUDS), s), s, BoseCatalog.QC_ULTRA_2_EARBUDS)
            },
            clock = { testScheduler.currentTime },
            live = LiveUpdates(polling = false),
        )
        repo.onSystemLink(connected)
        runCurrent()

        val state = repo.state.value
        assertEquals(60, state.battery.valueOrNull())
        assertEquals(3, state.batteryParts.size)
        assertEquals(true, state.deviceBadge?.experimental)
    }

    @Test
    fun `NothingDriver and JblDriver load battery parts and switch discrete ANC modes`() = runTest {
        val nothingRepo = HeadphoneRepository(
            scope = backgroundScope,
            driverConnector = DriverConnector { s ->
                NothingDriver(NothingConnection(FakeNothingDevice(), s), s, "Nothing Ear (a)")
            },
            clock = { testScheduler.currentTime },
            live = LiveUpdates(polling = false),
        )
        nothingRepo.onSystemLink(connected)
        runCurrent()
        assertEquals(80, nothingRepo.state.value.battery.valueOrNull())
        assertEquals("ANC_ADAPTIVE", nothingRepo.state.value.discreteAnc.valueOrNull()?.selectedId)
        nothingRepo.setDiscreteAnc("TRANSPARENCY")
        runCurrent()
        assertEquals("TRANSPARENCY", nothingRepo.state.value.discreteAnc.valueOrNull()?.selectedId)

        val jblRepo = HeadphoneRepository(
            scope = backgroundScope,
            driverConnector = DriverConnector { s ->
                JblDriver(JblConnection(FakeJblDevice(), s), s, "JBL TUNE230NC TWS")
            },
            clock = { testScheduler.currentTime },
            live = LiveUpdates(polling = false),
        )
        jblRepo.onSystemLink(connected)
        runCurrent()
        assertEquals(75, jblRepo.state.value.battery.valueOrNull())
        assertEquals("ANC", jblRepo.state.value.discreteAnc.valueOrNull()?.selectedId)
        jblRepo.setDiscreteAnc("TALKTHRU")
        runCurrent()
        assertEquals("TALKTHRU", jblRepo.state.value.discreteAnc.valueOrNull()?.selectedId)
    }
}
