package io.github.sargemck.practicalheadphones.driver

import io.github.sargemck.practicalheadphones.bmap.CloseReason
import io.github.sargemck.practicalheadphones.bmap.EqBand
import io.github.sargemck.practicalheadphones.samsung.SamsungBuds
import io.github.sargemck.practicalheadphones.samsung.SamsungCloseReason
import io.github.sargemck.practicalheadphones.samsung.SamsungConnection
import io.github.sargemck.practicalheadphones.samsung.SamsungConnectionState
import io.github.sargemck.practicalheadphones.samsung.SamsungEqPreset
import io.github.sargemck.practicalheadphones.samsung.SamsungException
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform

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
        connection.notifications.transform { frame ->
            when (frame.msgId) {
                SamsungProtocol.MSG_EXTENDED_STATUS_UPDATED -> {
                    val status = SamsungProtocol.parseExtendedStatus(frame.payload) ?: return@transform
                    emit(SectionKey.Battery to { applyBattery(it, status) })
                    emit(SectionKey.DiscreteAnc to { it.copy(discreteAnc = Section.Loaded(toDiscreteAnc(status.noiseMode))) })
                    emit(SectionKey.Eq to { it.copy(eq = Section.Loaded(emptyList()), selectedEqPreset = status.eqPreset.name) })
                }
                SamsungProtocol.MSG_NOISE_CONTROLS_UPDATE -> {
                    val mode = SamsungProtocol.parseNoiseControlUpdate(frame.payload) ?: return@transform
                    emit(SectionKey.DiscreteAnc to { s -> s.copy(discreteAnc = Section.Loaded(toDiscreteAnc(mode))) })
                }
            }
        }

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
                selfVoice = Section.Unsupported,
                speakToChat = Section.Unsupported,
                autoPlayPause = Section.Unsupported,
                autoAnswer = Section.Unsupported,
                multipoint = Section.Unsupported,
                eqPresets = SAMSUNG_PRESETS,
                eqBandLabels = emptyList(),
            )
        }

        val status = readOptional { buds.fetchExtendedStatus() }
        if (status != null) {
            apply { applyStatus(it, status) }
        } else {
            val failed = Section.Failed("Couldn't read earbuds status")
            apply { it.copy(battery = failed, discreteAnc = failed, eq = failed) }
        }
    }

    override suspend fun retryFailed(
        current: () -> HeadphoneState,
        apply: ((HeadphoneState) -> HeadphoneState) -> Unit,
    ) {
        val status = readOptional { buds.fetchExtendedStatus() } ?: return
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
        emptyList()
    }

    override fun close() = connection.close()

    private fun applyBattery(state: HeadphoneState, status: SamsungStatus): HeadphoneState = state.copy(
        battery = status.aggregateBattery?.let { Section.Loaded(it) } ?: Section.Unsupported,
        batteryParts = buildList {
            status.leftBattery?.let { add(BatteryPart("L", it)) }
            status.rightBattery?.let { add(BatteryPart("R", it)) }
            status.caseBattery?.let { add(BatteryPart("Case", it)) }
        },
    )

    private fun applyStatus(state: HeadphoneState, status: SamsungStatus): HeadphoneState =
        applyBattery(state, status).copy(
            discreteAnc = Section.Loaded(toDiscreteAnc(status.noiseMode)),
            eq = Section.Loaded(emptyList()),
            selectedEqPreset = status.eqPreset.name,
        )

    private suspend fun <T> readOptional(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: SamsungException.Disconnected) {
        throw DriverException.Disconnected(mapCloseReason(e.reason), e)
    } catch (e: SamsungException) {
        log("Samsung optional read failed: ${e.message}")
        null
    }

    private inline fun <T> runSamsung(block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
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
            EqPresetChoice(id = it.name, label = it.label)
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
