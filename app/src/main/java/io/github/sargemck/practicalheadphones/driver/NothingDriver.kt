package io.github.sargemck.practicalheadphones.driver

import io.github.sargemck.practicalheadphones.bmap.CloseReason
import io.github.sargemck.practicalheadphones.nothing.NothingAncMode
import io.github.sargemck.practicalheadphones.nothing.NothingBattery
import io.github.sargemck.practicalheadphones.nothing.NothingCloseReason
import io.github.sargemck.practicalheadphones.nothing.NothingConnection
import io.github.sargemck.practicalheadphones.nothing.NothingConnectionState
import io.github.sargemck.practicalheadphones.nothing.NothingException
import io.github.sargemck.practicalheadphones.nothing.NothingFrame
import io.github.sargemck.practicalheadphones.nothing.NothingHeadphones
import io.github.sargemck.practicalheadphones.nothing.NothingProtocol
import io.github.sargemck.practicalheadphones.state.BatteryPart
import io.github.sargemck.practicalheadphones.state.Capabilities
import io.github.sargemck.practicalheadphones.state.DeviceBadge
import io.github.sargemck.practicalheadphones.state.DiscreteAncOption
import io.github.sargemck.practicalheadphones.state.DiscreteAncState
import io.github.sargemck.practicalheadphones.state.HeadphoneState
import io.github.sargemck.practicalheadphones.state.Section
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn

/**
 * Adapts a Nothing / CMF [NothingConnection] (NT Link RFCOMM) to [HeadphoneDriver].
 */
class NothingDriver(
    private val connection: NothingConnection,
    scope: CoroutineScope,
    private val deviceName: String,
    private val log: (String) -> Unit = {},
) : HeadphoneDriver {
    private val hp = NothingHeadphones(connection)

    override val badge: DeviceBadge = DeviceBadge("Nothing", deviceName, experimental = true)

    override val capabilities: Capabilities = Capabilities(
        canRename = false,
        canPair = false,
        canPowerOff = false,
        canEditModes = false,
        editableSlots = IntRange.EMPTY,
        presetSlots = IntRange.EMPTY,
        presetNames = emptyList(),
    )

    override val state: StateFlow<DriverState> = connection.state
        .map {
            when (it) {
                NothingConnectionState.Open -> DriverState.Open
                is NothingConnectionState.Closed -> DriverState.Closed(mapCloseReason(it.reason))
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, DriverState.Open)

    override val updates: Flow<Pair<SectionKey, (HeadphoneState) -> HeadphoneState>> =
        connection.notifications.mapNotNull(::mapNotification)

    override suspend fun initialLoad(apply: ((HeadphoneState) -> HeadphoneState) -> Unit) {
        apply {
            it.copy(
                deviceBadge = badge,
                capabilities = capabilities,
                name = Section.Loaded(deviceName),
                firmware = Section.Unsupported,
                modes = Section.Unsupported,
                audio = Section.Unsupported,
                eq = Section.Unsupported,
                selfVoice = Section.Unsupported,
                speakToChat = Section.Unsupported,
                autoPlayPause = Section.Unsupported,
                autoAnswer = Section.Unsupported,
                multipoint = Section.Unsupported,
            )
        }

        runNothing { hp.initSession() }

        val battery = readOptional { hp.getBattery() }
        apply { s ->
            if (battery != null) {
                s.copy(battery = Section.Loaded(battery.percent), batteryParts = batteryParts(battery))
            } else {
                s.copy(battery = Section.Failed("Couldn't read battery"))
            }
        }

        val anc = readOptional { hp.getAnc() }
        apply { s ->
            s.copy(discreteAnc = anc?.let { Section.Loaded(toDiscreteAnc(it)) } ?: Section.Unsupported)
        }
    }

    override suspend fun retryFailed(
        current: () -> HeadphoneState,
        apply: ((HeadphoneState) -> HeadphoneState) -> Unit,
    ) {
        if (current().battery is Section.Failed) {
            readOptional { hp.getBattery() }?.let { b ->
                apply { it.copy(battery = Section.Loaded(b.percent), batteryParts = batteryParts(b)) }
            }
        }
    }

    override suspend fun poll(
        battery: Boolean,
        guarded: suspend (SectionKey, suspend () -> ((HeadphoneState) -> HeadphoneState)?) -> Unit,
    ) {
        guarded(SectionKey.DiscreteAnc) {
            val anc = readOptional { hp.getAnc() } ?: return@guarded null
            { s -> s.copy(discreteAnc = Section.Loaded(toDiscreteAnc(anc))) }
        }
        if (battery) {
            guarded(SectionKey.Battery) {
                val b = readOptional { hp.getBattery() } ?: return@guarded null
                { s -> s.copy(battery = Section.Loaded(b.percent), batteryParts = batteryParts(b)) }
            }
        }
    }

    override suspend fun setDiscreteAnc(optionId: String): DiscreteAncState = runNothing {
        val mode = NothingAncMode.entries.firstOrNull { it.name.equals(optionId, ignoreCase = true) }
            ?: throw DriverException("Unknown Nothing ANC mode $optionId")
        val updated = hp.setAnc(mode)
        toDiscreteAnc(updated)
    }

    override fun close() = connection.close()

    private fun mapNotification(frame: NothingFrame): Pair<SectionKey, (HeadphoneState) -> HeadphoneState>? {
        return when (frame.command) {
            NothingProtocol.CMD_ANC_GET, NothingProtocol.CMD_ANC_SET -> {
                val mode = NothingProtocol.parseAnc(frame.payload) ?: return null
                SectionKey.DiscreteAnc to { s -> s.copy(discreteAnc = Section.Loaded(toDiscreteAnc(mode))) }
            }
            NothingProtocol.CMD_BATTERY -> {
                val b = NothingProtocol.parseBattery(frame.payload) ?: return null
                SectionKey.Battery to { s -> s.copy(battery = Section.Loaded(b.percent), batteryParts = batteryParts(b)) }
            }
            else -> null
        }
    }

    private suspend fun <T> readOptional(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: NothingException.Disconnected) {
        throw DriverException.Disconnected(mapCloseReason(e.reason), e)
    } catch (e: NothingException) {
        log("Nothing optional read failed: ${e.message}")
        null
    }

    private inline fun <T> runNothing(block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: NothingException.Disconnected) {
        throw DriverException.Disconnected(mapCloseReason(e.reason), e)
    } catch (e: NothingException) {
        log("Nothing error: ${e.message}")
        throw DriverException(e.message ?: "Nothing protocol error", e)
    }

    private fun mapCloseReason(reason: NothingCloseReason): CloseReason = when (reason) {
        NothingCloseReason.RemoteClosed -> CloseReason.RemoteClosed
        is NothingCloseReason.IoError -> CloseReason.IoError(reason.message)
        NothingCloseReason.Unresponsive -> CloseReason.Unresponsive
        NothingCloseReason.Local -> CloseReason.Local
    }

    companion object {
        private val OPTIONS = NothingAncMode.entries.map { mode ->
            val desc = when (mode) {
                NothingAncMode.ANC_ADAPTIVE -> "Adjusts noise cancelling to your surroundings."
                NothingAncMode.ANC_HIGH -> "Maximum noise cancelling."
                NothingAncMode.ANC_MID -> "Balanced noise cancelling."
                NothingAncMode.ANC_LOW -> "Light noise cancelling."
                NothingAncMode.TRANSPARENCY -> "Lets outside sound through."
                NothingAncMode.OFF -> "Noise control off."
            }
            DiscreteAncOption(mode.name, mode.label, desc)
        }

        fun toDiscreteAnc(mode: NothingAncMode): DiscreteAncState = DiscreteAncState(
            options = OPTIONS,
            selectedId = mode.name,
        )

        fun batteryParts(b: NothingBattery): List<BatteryPart> = buildList {
            b.left?.let { add(BatteryPart("L", it)) }
            b.right?.let { add(BatteryPart("R", it)) }
            b.casePct?.let { add(BatteryPart("Case", it)) }
        }
    }
}
