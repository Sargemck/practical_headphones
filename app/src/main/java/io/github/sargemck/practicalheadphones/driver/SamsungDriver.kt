package io.github.sargemck.practicalheadphones.driver

import io.github.sargemck.practicalheadphones.bmap.CloseReason
import io.github.sargemck.practicalheadphones.bmap.EqBand
import io.github.sargemck.practicalheadphones.samsung.SamsungBuds
import io.github.sargemck.practicalheadphones.samsung.SamsungCloseReason
import io.github.sargemck.practicalheadphones.samsung.SamsungConnection
import io.github.sargemck.practicalheadphones.samsung.SamsungConnectionState
import io.github.sargemck.practicalheadphones.samsung.SamsungEqPreset
import io.github.sargemck.practicalheadphones.samsung.SamsungException
import io.github.sargemck.practicalheadphones.samsung.SamsungFrame
import io.github.sargemck.practicalheadphones.samsung.SamsungNoiseMode
import io.github.sargemck.practicalheadphones.samsung.SamsungProtocol
import io.github.sargemck.practicalheadphones.samsung.SamsungStatus
import io.github.sargemck.practicalheadphones.state.BatteryPart
import io.github.sargemck.practicalheadphones.state.Capabilities
import io.github.sargemck.practicalheadphones.state.DeviceBadge
import io.github.sargemck.practicalheadphones.state.DiscreteAncOption
import io.github.sargemck.practicalheadphones.state.DiscreteAncState
import io.github.sargemck.practicalheadphones.state.EqPresetChoice
import io.github.sargemck.practicalheadphones.state.HeadphoneState
import io.github.sargemck.practicalheadphones.state.Section
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn

/**
 * Adapts a Samsung Galaxy Buds [SamsungConnection] to [HeadphoneDriver].
 */
class SamsungDriver(
    private val connection: SamsungConnection,
    scope: CoroutineScope,
    private val deviceName: String,
    private val log: (String) -> Unit = {},
) : HeadphoneDriver {
    private val buds = SamsungBuds(connection)

    override val badge: DeviceBadge = DeviceBadge("Samsung", deviceName, experimental = true)

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
                SamsungConnectionState.Open -> DriverState.Open
                is SamsungConnectionState.Closed -> DriverState.Closed(mapCloseReason(it.reason))
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
                selfVoice = Section.Unsupported,
                speakToChat = Section.Unsupported,
                autoPlayPause = Section.Unsupported,
                autoAnswer = Section.Unsupported,
                multipoint = Section.Unsupported,
                eqPresets = SAMSUNG_PRESETS,
                eqBandLabels = emptyList(),
            )
        }

        val status = runSamsung { buds.fetchExtendedStatus() }
        apply { applyStatus(it, status) }
    }

    override suspend fun retryFailed(
        current: () -> HeadphoneState,
        apply: ((HeadphoneState) -> HeadphoneState) -> Unit,
    ) {
        val status = runCatching { buds.fetchExtendedStatus() }.getOrNull() ?: return
        apply { applyStatus(it, status) }
    }

    override suspend fun poll(
        battery: Boolean,
        guarded: suspend (SectionKey, suspend () -> ((HeadphoneState) -> HeadphoneState)?) -> Unit,
    ) {
        // Galaxy Buds push EXTENDED_STATUS_UPDATED and NOISE_CONTROLS_UPDATE unsolicited.
    }

    override suspend fun setDiscreteAnc(optionId: String): DiscreteAncState = runSamsung {
        val mode = SamsungNoiseMode.entries.firstOrNull { it.name.equals(optionId, ignoreCase = true) }
            ?: throw DriverException("Unknown Samsung noise mode $optionId")
        buds.setNoiseMode(mode)
        toDiscreteAnc(mode)
    }

    override suspend fun setEqPreset(presetId: String): List<EqBand> = runSamsung {
        val preset = SamsungEqPreset.entries.firstOrNull { it.name.equals(presetId, ignoreCase = true) }
            ?: throw DriverException("Unknown Samsung EQ preset $presetId")
        buds.setEqualizer(preset)
        listOf(EqBand(0, 0, 5, preset.code))
    }

    override fun close() = connection.close()

    private fun applyStatus(state: HeadphoneState, status: SamsungStatus): HeadphoneState = state.copy(
        battery = status.aggregateBattery?.let { Section.Loaded(it) } ?: Section.Unsupported,
        batteryParts = buildList {
            status.leftBattery?.let { add(BatteryPart("L", it)) }
            status.rightBattery?.let { add(BatteryPart("R", it)) }
            status.caseBattery?.let { add(BatteryPart("Case", it)) }
        },
        discreteAnc = Section.Loaded(toDiscreteAnc(status.noiseMode)),
        eq = Section.Loaded(listOf(EqBand(0, 0, 5, status.eqPreset.code))),
    )

    private fun mapNotification(frame: SamsungFrame): Pair<SectionKey, (HeadphoneState) -> HeadphoneState>? {
        return when (frame.msgId) {
            SamsungProtocol.MSG_EXTENDED_STATUS_UPDATED -> {
                val status = SamsungProtocol.parseExtendedStatus(frame.payload) ?: return null
                SectionKey.Battery to { applyStatus(it, status) }
            }
            SamsungProtocol.MSG_NOISE_CONTROLS_UPDATE -> {
                val mode = SamsungProtocol.parseNoiseControlUpdate(frame.payload) ?: return null
                SectionKey.DiscreteAnc to { s -> s.copy(discreteAnc = Section.Loaded(toDiscreteAnc(mode))) }
            }
            else -> null
        }
    }

    private inline fun <T> runSamsung(block: () -> T): T = try {
        block()
    } catch (e: SamsungException.Disconnected) {
        throw DriverException.Disconnected(mapCloseReason(e.reason), e)
    } catch (e: SamsungException) {
        log("Samsung error: ${e.message}")
        throw DriverException(e.message ?: "Samsung protocol error", e)
    }

    private fun mapCloseReason(reason: SamsungCloseReason): CloseReason = when (reason) {
        SamsungCloseReason.RemoteClosed -> CloseReason.RemoteClosed
        is SamsungCloseReason.IoError -> CloseReason.IoError(reason.message)
        SamsungCloseReason.Unresponsive -> CloseReason.Unresponsive
        SamsungCloseReason.Local -> CloseReason.Local
    }

    companion object {
        val SAMSUNG_PRESETS: List<EqPresetChoice> = SamsungEqPreset.entries.map {
            EqPresetChoice(id = it.name, label = it.label, values = listOf(it.code))
        }

        fun toDiscreteAnc(mode: SamsungNoiseMode): DiscreteAncState = DiscreteAncState(
            options = listOf(
                DiscreteAncOption("ANC", "Noise cancelling", "Blocks outside sound."),
                DiscreteAncOption("AMBIENT", "Ambient sound", "Lets outside sound through."),
                DiscreteAncOption("OFF", "Off", "Noise control off."),
            ),
            selectedId = mode.name,
        )
    }
}
