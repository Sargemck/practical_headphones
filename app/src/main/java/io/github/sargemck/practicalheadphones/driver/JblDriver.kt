package io.github.sargemck.practicalheadphones.driver

import io.github.sargemck.practicalheadphones.bmap.CloseReason
import io.github.sargemck.practicalheadphones.jbl.JblBattery
import io.github.sargemck.practicalheadphones.jbl.JblBuds
import io.github.sargemck.practicalheadphones.jbl.JblCloseReason
import io.github.sargemck.practicalheadphones.jbl.JblConnection
import io.github.sargemck.practicalheadphones.jbl.JblConnectionState
import io.github.sargemck.practicalheadphones.jbl.JblException
import io.github.sargemck.practicalheadphones.jbl.JblFrame
import io.github.sargemck.practicalheadphones.jbl.JblMode
import io.github.sargemck.practicalheadphones.jbl.JblProtocol
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
 * Adapts a JBL BLE GATT [JblConnection] to [HeadphoneDriver].
 */
class JblDriver(
    private val connection: JblConnection,
    scope: CoroutineScope,
    private val deviceName: String,
    private val log: (String) -> Unit = {},
) : HeadphoneDriver {
    private val buds = JblBuds(connection)

    override val badge: DeviceBadge = DeviceBadge("JBL", deviceName, experimental = true)

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
                JblConnectionState.Open -> DriverState.Open
                is JblConnectionState.Closed -> DriverState.Closed(mapCloseReason(it.reason))
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
                discreteAnc = Section.Loading,
                eq = Section.Unsupported,
                selfVoice = Section.Unsupported,
                speakToChat = Section.Unsupported,
                autoPlayPause = Section.Unsupported,
                autoAnswer = Section.Unsupported,
                multipoint = Section.Unsupported,
            )
        }

        runJbl { buds.enableNotifications() }

        val battery = readOptional { buds.getBattery() }
        apply { s ->
            if (battery != null) {
                s.copy(battery = Section.Loaded(battery.percent), batteryParts = batteryParts(battery))
            } else {
                s.copy(battery = Section.Unsupported)
            }
        }

        val mode = readOptional { buds.getMode() }
        apply { s ->
            s.copy(discreteAnc = mode?.let { Section.Loaded(toDiscreteAnc(it)) } ?: Section.Failed("Couldn't read listening mode"))
        }
    }

    override suspend fun retryFailed(
        current: () -> HeadphoneState,
        apply: ((HeadphoneState) -> HeadphoneState) -> Unit,
    ) {
        if (current().discreteAnc is Section.Failed) {
            readOptional { buds.getMode() }?.let { m ->
                apply { it.copy(discreteAnc = Section.Loaded(toDiscreteAnc(m))) }
            }
        }
    }

    override suspend fun poll(
        battery: Boolean,
        guarded: suspend (SectionKey, suspend () -> ((HeadphoneState) -> HeadphoneState)?) -> Unit,
    ) {
        if (battery) {
            guarded(SectionKey.Battery) {
                val b = readOptional { buds.getBattery() } ?: return@guarded null
                { s -> s.copy(battery = Section.Loaded(b.percent), batteryParts = batteryParts(b)) }
            }
        }
    }

    override suspend fun setDiscreteAnc(optionId: String): DiscreteAncState = runJbl {
        val mode = JblMode.entries.firstOrNull { it.name.equals(optionId, ignoreCase = true) }
            ?: throw DriverException("Unknown JBL mode $optionId")
        val updated = buds.setMode(mode)
        toDiscreteAnc(updated)
    }

    override fun close() = connection.close()

    private fun mapNotification(frame: JblFrame): Pair<SectionKey, (HeadphoneState) -> HeadphoneState>? {
        JblProtocol.parseModeReport(frame)?.let { mode ->
            return SectionKey.DiscreteAnc to { s -> s.copy(discreteAnc = Section.Loaded(toDiscreteAnc(mode))) }
        }
        JblProtocol.parseBattery(frame)?.let { b ->
            return SectionKey.Battery to { s -> s.copy(battery = Section.Loaded(b.percent), batteryParts = batteryParts(b)) }
        }
        return null
    }

    private suspend fun <T> readOptional(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: JblException.Disconnected) {
        throw DriverException.Disconnected(mapCloseReason(e.reason), e)
    } catch (e: JblException) {
        log("JBL optional read failed: ${e.message}")
        null
    }

    private inline fun <T> runJbl(block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: JblException.Disconnected) {
        throw DriverException.Disconnected(mapCloseReason(e.reason), e)
    } catch (e: JblException) {
        log("JBL error: ${e.message}")
        throw DriverException(e.message ?: "JBL protocol error", e)
    }

    private fun mapCloseReason(reason: JblCloseReason): CloseReason = when (reason) {
        JblCloseReason.RemoteClosed -> CloseReason.RemoteClosed
        is JblCloseReason.IoError -> CloseReason.IoError(reason.message)
        JblCloseReason.Unresponsive -> CloseReason.Unresponsive
        JblCloseReason.Local -> CloseReason.Local
    }

    companion object {
        private val OPTIONS = listOf(
            DiscreteAncOption("ANC", "Noise cancelling", "Blocks outside sound."),
            DiscreteAncOption("AMBIENT", "Ambient Aware", "Lets outside surroundings in."),
            DiscreteAncOption("TALKTHRU", "TalkThru", "Lowers audio so you can hold a conversation."),
            DiscreteAncOption("OFF", "Off", "Noise control off."),
        )

        fun toDiscreteAnc(mode: JblMode): DiscreteAncState = DiscreteAncState(
            options = OPTIONS,
            selectedId = mode.name,
        )

        fun batteryParts(b: JblBattery): List<BatteryPart> = buildList {
            b.left?.let { add(BatteryPart("L", it)) }
            b.right?.let { add(BatteryPart("R", it)) }
            b.casePct?.let { add(BatteryPart("Case", it)) }
        }
    }
}
