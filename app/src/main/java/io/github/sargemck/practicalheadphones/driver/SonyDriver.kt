package io.github.sargemck.practicalheadphones.driver

import io.github.sargemck.practicalheadphones.bmap.CloseReason
import io.github.sargemck.practicalheadphones.bmap.EqBand
import io.github.sargemck.practicalheadphones.sony.SonyAncMode
import io.github.sargemck.practicalheadphones.sony.SonyAncState
import io.github.sargemck.practicalheadphones.sony.SonyBattery
import io.github.sargemck.practicalheadphones.sony.SonyCloseReason
import io.github.sargemck.practicalheadphones.sony.SonyConnection
import io.github.sargemck.practicalheadphones.sony.SonyConnectionState
import io.github.sargemck.practicalheadphones.sony.SonyEqPreset
import io.github.sargemck.practicalheadphones.sony.SonyEqState
import io.github.sargemck.practicalheadphones.sony.SonyException
import io.github.sargemck.practicalheadphones.sony.SonyFrame
import io.github.sargemck.practicalheadphones.sony.SonyHeadphones
import io.github.sargemck.practicalheadphones.sony.SonyProtocol
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
 * Adapts a Sony MDR v1/v2 [SonyConnection] to [HeadphoneDriver].
 */
class SonyDriver(
    private val connection: SonyConnection,
    scope: CoroutineScope,
    private val deviceName: String,
    private val log: (String) -> Unit = {},
) : HeadphoneDriver {
    private val sony = SonyHeadphones(connection)

    @Volatile private var ancState: SonyAncState? = null
    @Volatile private var eqState: SonyEqState? = null

    override val badge: DeviceBadge = DeviceBadge("Sony", deviceName, experimental = true)

    override val capabilities: Capabilities = Capabilities(
        canRename = false,
        canPair = false,
        canPowerOff = true,
        canEditModes = false,
        editableSlots = IntRange.EMPTY,
        presetSlots = IntRange.EMPTY,
        presetNames = emptyList(),
    )

    override val state: StateFlow<DriverState> = connection.state
        .map {
            when (it) {
                SonyConnectionState.Open -> DriverState.Open
                is SonyConnectionState.Closed -> DriverState.Closed(mapCloseReason(it.reason))
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
                autoPlayPause = Section.Unsupported,
                autoAnswer = Section.Unsupported,
                multipoint = Section.Unsupported,
                eqPresets = SONY_PRESETS,
                eqBandLabels = SonyProtocol.EQ_BAND_LABELS,
            )
        }

        val isV2 = runSony { sony.handshake() }

        val battery = runCatching { sony.getBattery() }.getOrNull()
        apply { s ->
            if (battery != null) {
                s.copy(battery = Section.Loaded(battery.percent), batteryParts = batteryParts(battery))
            } else {
                s.copy(battery = Section.Failed("Couldn't read battery"))
            }
        }

        val anc = runCatching { sony.probeNcAsm(preferV2 = isV2) }.getOrNull()
        ancState = anc
        apply { s ->
            s.copy(discreteAnc = anc?.let { Section.Loaded(toDiscreteAnc(it)) } ?: Section.Unsupported)
        }

        val eq = runCatching { sony.getEq() }.getOrNull()
        eqState = eq
        apply { s ->
            s.copy(eq = eq?.let { Section.Loaded(toEqBands(it)) } ?: Section.Unsupported)
        }

        val stc = runCatching { sony.getSpeakToChat() }.getOrNull()
        apply { s ->
            s.copy(speakToChat = stc?.let { Section.Loaded(it) } ?: Section.Unsupported)
        }
    }

    override suspend fun retryFailed(
        current: () -> HeadphoneState,
        apply: ((HeadphoneState) -> HeadphoneState) -> Unit,
    ) {
        if (current().battery is Section.Failed) {
            val b = runCatching { sony.getBattery() }.getOrNull()
            if (b != null) {
                apply { it.copy(battery = Section.Loaded(b.percent), batteryParts = batteryParts(b)) }
            }
        }
    }

    override suspend fun poll(
        battery: Boolean,
        guarded: suspend (SectionKey, suspend () -> ((HeadphoneState) -> HeadphoneState)?) -> Unit,
    ) {
        if (battery) {
            guarded(SectionKey.Battery) {
                val b = runCatching { sony.getBattery() }.getOrNull() ?: return@guarded null
                { s -> s.copy(battery = Section.Loaded(b.percent), batteryParts = batteryParts(b)) }
            }
        }
    }

    override suspend fun setDiscreteAnc(optionId: String): DiscreteAncState = runSony {
        val current = ancState ?: throw DriverException.Unsupported()
        val mode = SonyAncMode.entries.firstOrNull { it.name.equals(optionId, ignoreCase = true) }
            ?: throw DriverException("Unknown Sony ANC mode $optionId")
        val next = current.copy(mode = mode)
        sony.setNcAsm(current.inquiredType, mode, current.ambientLevel, current.focusOnVoice, current.ncValue ?: SonyProtocol.DEFAULT_NC_VALUE)
        ancState = next
        toDiscreteAnc(next)
    }

    override suspend fun setAmbientLevel(level: Int): DiscreteAncState = runSony {
        val current = ancState ?: throw DriverException.Unsupported()
        val clamped = level.coerceIn(0, SonyProtocol.MAX_AMBIENT_LEVEL)
        val next = current.copy(mode = SonyAncMode.AMBIENT, ambientLevel = clamped)
        sony.setNcAsm(current.inquiredType, SonyAncMode.AMBIENT, clamped, current.focusOnVoice, current.ncValue ?: SonyProtocol.DEFAULT_NC_VALUE)
        ancState = next
        toDiscreteAnc(next)
    }

    override suspend fun setFocusOnVoice(on: Boolean): DiscreteAncState = runSony {
        val current = ancState ?: throw DriverException.Unsupported()
        val next = current.copy(focusOnVoice = on)
        sony.setNcAsm(current.inquiredType, current.mode, current.ambientLevel, on, current.ncValue ?: SonyProtocol.DEFAULT_NC_VALUE)
        ancState = next
        toDiscreteAnc(next)
    }

    override suspend fun setSpeakToChat(on: Boolean): Boolean = runSony {
        sony.setSpeakToChat(on)
        on
    }

    override suspend fun setEqBand(bandId: Int, value: Int): List<EqBand> = runSony {
        val current = eqState ?: throw DriverException.Unsupported()
        val updated = current.bands.mapIndexed { i, v -> if (i == bandId) value.coerceIn(-10, 10) else v }
        val next = SonyEqState(SonyEqPreset.CUSTOM, updated)
        sony.setEq(next.preset, next.bands)
        eqState = next
        toEqBands(next)
    }

    override suspend fun setEqPreset(presetId: String): List<EqBand> = runSony {
        val current = eqState ?: throw DriverException.Unsupported()
        val preset = SonyEqPreset.entries.firstOrNull { it.name.equals(presetId, ignoreCase = true) }
            ?: throw DriverException("Unknown Sony EQ preset $presetId")
        val bands = if (preset == SonyEqPreset.OFF) List(current.bands.size) { 0 } else current.bands
        val next = SonyEqState(preset, bands)
        sony.setEq(next.preset, next.bands)
        eqState = next
        toEqBands(next)
    }

    override suspend fun powerOff() = runSony { sony.powerOff() }

    override fun close() = connection.close()

    private fun mapNotification(frame: SonyFrame): Pair<SectionKey, (HeadphoneState) -> HeadphoneState>? {
        val p = frame.payload
        SonyProtocol.parseNcAsm(p, ancState?.inquiredType)?.let { anc ->
            ancState = anc
            return SectionKey.DiscreteAnc to { s -> s.copy(discreteAnc = Section.Loaded(toDiscreteAnc(anc))) }
        }
        SonyProtocol.parseBattery(p)?.let { b ->
            return SectionKey.Battery to { s ->
                val parts = batteryParts(b).ifEmpty { s.batteryParts }
                s.copy(battery = Section.Loaded(b.percent), batteryParts = parts)
            }
        }
        SonyProtocol.parseEq(p)?.let { eq ->
            eqState = eq
            return SectionKey.Eq to { s -> s.copy(eq = Section.Loaded(toEqBands(eq))) }
        }
        SonyProtocol.parseSpeakToChat(p)?.let { on ->
            return SectionKey.SpeakToChat to { s -> s.copy(speakToChat = Section.Loaded(on)) }
        }
        return null
    }

    private inline fun <T> runSony(block: () -> T): T = try {
        block()
    } catch (e: SonyException.Disconnected) {
        throw DriverException.Disconnected(mapCloseReason(e.reason), e)
    } catch (e: SonyException) {
        log("Sony error: ${e.message}")
        throw DriverException(e.message ?: "Sony protocol error", e)
    }

    private fun mapCloseReason(reason: SonyCloseReason): CloseReason = when (reason) {
        SonyCloseReason.RemoteClosed -> CloseReason.RemoteClosed
        is SonyCloseReason.IoError -> CloseReason.IoError(reason.message)
        SonyCloseReason.Unresponsive -> CloseReason.Unresponsive
        SonyCloseReason.Local -> CloseReason.Local
    }

    companion object {
        val SONY_PRESETS: List<EqPresetChoice> = SonyEqPreset.entries
            .filter { it != SonyEqPreset.CUSTOM }
            .map { EqPresetChoice(id = it.name, label = it.label) }

        fun toDiscreteAnc(state: SonyAncState): DiscreteAncState = DiscreteAncState(
            options = state.available.map { mode ->
                when (mode) {
                    SonyAncMode.ANC -> DiscreteAncOption("ANC", "Noise cancelling", "Blocks outside sound.")
                    SonyAncMode.AMBIENT -> DiscreteAncOption("AMBIENT", "Ambient sound", "Lets outside sound through.")
                    SonyAncMode.OFF -> DiscreteAncOption("OFF", "Off", "Noise control off.")
                }
            },
            selectedId = state.mode.name,
            ambientLevel = if (state.mode == SonyAncMode.AMBIENT) state.ambientLevel else null,
            ambientMax = SonyProtocol.MAX_AMBIENT_LEVEL,
            focusOnVoice = if (state.mode == SonyAncMode.AMBIENT) state.focusOnVoice else null,
        )

        fun toEqBands(eq: SonyEqState): List<EqBand> =
            eq.bands.mapIndexed { i, v -> EqBand(bandId = i, min = -10, max = 10, current = v) }

        fun batteryParts(b: SonyBattery): List<BatteryPart> = buildList {
            b.left?.let { add(BatteryPart("L", it)) }
            b.right?.let { add(BatteryPart("R", it)) }
            b.cradle?.let { add(BatteryPart("Case", it)) }
        }
    }
}
