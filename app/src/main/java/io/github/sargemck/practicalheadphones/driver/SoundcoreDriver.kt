package io.github.sargemck.practicalheadphones.driver

import io.github.sargemck.practicalheadphones.bmap.CloseReason
import io.github.sargemck.practicalheadphones.bmap.EqBand
import io.github.sargemck.practicalheadphones.soundcore.SoundcoreAncMode
import io.github.sargemck.practicalheadphones.soundcore.SoundcoreCloseReason
import io.github.sargemck.practicalheadphones.soundcore.SoundcoreConnection
import io.github.sargemck.practicalheadphones.soundcore.SoundcoreConnectionState
import io.github.sargemck.practicalheadphones.soundcore.SoundcoreEqPreset
import io.github.sargemck.practicalheadphones.soundcore.SoundcoreEqState
import io.github.sargemck.practicalheadphones.soundcore.SoundcoreException
import io.github.sargemck.practicalheadphones.soundcore.SoundcoreFrame
import io.github.sargemck.practicalheadphones.soundcore.SoundcoreHeadphones
import io.github.sargemck.practicalheadphones.soundcore.SoundcoreProtocol
import io.github.sargemck.practicalheadphones.soundcore.SoundcoreSoundMode
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
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn

/**
 * Adapts an Anker Soundcore [SoundcoreConnection] to [HeadphoneDriver].
 */
class SoundcoreDriver(
    private val connection: SoundcoreConnection,
    scope: CoroutineScope,
    private val deviceName: String,
    private val vendorUuid: String? = null,
    private val log: (String) -> Unit = {},
) : HeadphoneDriver {
    private val sc = SoundcoreHeadphones(connection)

    @Volatile private var soundMode: SoundcoreSoundMode? = null
    @Volatile private var eqState: SoundcoreEqState? = null

    override val badge: DeviceBadge = DeviceBadge("Soundcore", deviceName, experimental = true)

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
                SoundcoreConnectionState.Open -> DriverState.Open
                is SoundcoreConnectionState.Closed -> DriverState.Closed(mapCloseReason(it.reason))
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
                eqPresets = SOUNDCORE_PRESETS,
                eqBandLabels = SoundcoreProtocol.EQ_BAND_LABELS,
            )
        }

        val snapshot = readOptional { sc.getStateSnapshot(vendorUuid) }
        if (snapshot?.firmware != null) {
            apply { it.copy(firmware = Section.Loaded(snapshot.firmware!!)) }
        }

        val battery = readOptional { sc.getBattery() } ?: snapshot?.batteryPercent
        apply { s ->
            s.copy(battery = battery?.let { Section.Loaded(it) } ?: Section.Failed("Couldn't read battery"))
        }

        val mode = readOptional { sc.getSoundMode() } ?: snapshot?.soundMode
        soundMode = mode
        apply { s ->
            s.copy(discreteAnc = mode?.let { Section.Loaded(toDiscreteAnc(it.mode)) } ?: Section.Unsupported)
        }

        val eq = readOptional { sc.getEq() } ?: snapshot?.eq
        eqState = eq
        apply { s ->
            s.copy(
                eq = eq?.let { Section.Loaded(toEqBands(it)) } ?: Section.Unsupported,
                selectedEqPreset = eq?.let { presetName(it.presetId) },
            )
        }
    }

    override suspend fun retryFailed(
        current: () -> HeadphoneState,
        apply: ((HeadphoneState) -> HeadphoneState) -> Unit,
    ) {
        if (current().battery is Section.Failed) {
            readOptional { sc.getBattery() }?.let { pct ->
                apply { it.copy(battery = Section.Loaded(pct)) }
            }
        }
    }

    override suspend fun poll(
        battery: Boolean,
        guarded: suspend (SectionKey, suspend () -> ((HeadphoneState) -> HeadphoneState)?) -> Unit,
    ) {
        if (battery) {
            guarded(SectionKey.Battery) {
                val pct = readOptional { sc.getBattery() } ?: return@guarded null
                { s -> s.copy(battery = Section.Loaded(pct)) }
            }
        }
    }

    override suspend fun setDiscreteAnc(optionId: String): DiscreteAncState = runSc {
        val mode = SoundcoreAncMode.entries.firstOrNull { it.name.equals(optionId, ignoreCase = true) }
            ?: throw DriverException("Unknown Soundcore mode $optionId")
        val updated = sc.setSoundMode(mode, soundMode?.rawBytes)
        soundMode = updated
        toDiscreteAnc(updated.mode)
    }

    override suspend fun setEqBand(bandId: Int, value: Int): List<EqBand> = runSc {
        val current = eqState ?: SoundcoreEqState(SoundcoreEqPreset.CUSTOM.id, List(8) { 0 })
        val bands = current.bands.mapIndexed { i, v -> if (i == bandId) value.coerceIn(-6, 6) else v }
        val updated = sc.setEq(SoundcoreEqPreset.CUSTOM.id, bands)
        eqState = updated
        toEqBands(updated)
    }

    override suspend fun setEqPreset(presetId: String): List<EqBand> = runSc {
        val preset = SoundcoreEqPreset.entries.firstOrNull { it.name.equals(presetId, ignoreCase = true) }
            ?: throw DriverException("Unknown Soundcore EQ preset $presetId")
        val bands = preset.bands.ifEmpty { List(8) { 0 } }
        val updated = sc.setEq(preset.id, bands)
        eqState = updated
        toEqBands(updated)
    }

    override fun close() = connection.close()

    private fun mapNotification(frame: SoundcoreFrame): Pair<SectionKey, (HeadphoneState) -> HeadphoneState>? {
        return when (frame.cmdGroup to frame.cmdId) {
            SoundcoreProtocol.GROUP_SOUND_MODE to SoundcoreProtocol.CMD_SOUND_MODE_GET -> {
                val parsed = SoundcoreProtocol.parseSoundMode(frame.payload) ?: return null
                soundMode = parsed
                SectionKey.DiscreteAnc to { s -> s.copy(discreteAnc = Section.Loaded(toDiscreteAnc(parsed.mode))) }
            }
            SoundcoreProtocol.GROUP_EQ to SoundcoreProtocol.CMD_EQ_GET -> {
                val parsed = SoundcoreProtocol.parseEq(frame.payload) ?: return null
                eqState = parsed
                SectionKey.Eq to { s ->
                    s.copy(
                        eq = Section.Loaded(toEqBands(parsed)),
                        selectedEqPreset = presetName(parsed.presetId),
                    )
                }
            }
            SoundcoreProtocol.GROUP_STATE to SoundcoreProtocol.CMD_BATTERY -> {
                val pct = SoundcoreProtocol.parseBatteryPercent(frame.payload) ?: return null
                SectionKey.Battery to { s -> s.copy(battery = Section.Loaded(pct)) }
            }
            else -> null
        }
    }

    private suspend fun <T> readOptional(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: SoundcoreException.Disconnected) {
        throw DriverException.Disconnected(mapCloseReason(e.reason), e)
    } catch (e: SoundcoreException) {
        log("Soundcore optional read failed: ${e.message}")
        null
    }

    private inline fun <T> runSc(block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: SoundcoreException.Disconnected) {
        throw DriverException.Disconnected(mapCloseReason(e.reason), e)
    } catch (e: SoundcoreException) {
        log("Soundcore error: ${e.message}")
        throw DriverException(e.message ?: "Soundcore protocol error", e)
    }

    private fun mapCloseReason(reason: SoundcoreCloseReason): CloseReason = when (reason) {
        SoundcoreCloseReason.RemoteClosed -> CloseReason.RemoteClosed
        is SoundcoreCloseReason.IoError -> CloseReason.IoError(reason.message)
        SoundcoreCloseReason.Unresponsive -> CloseReason.Unresponsive
        SoundcoreCloseReason.Local -> CloseReason.Local
    }

    companion object {
        val SOUNDCORE_PRESETS: List<EqPresetChoice> = SoundcoreEqPreset.entries
            .filter { it != SoundcoreEqPreset.CUSTOM }
            .map { EqPresetChoice(id = it.name, label = it.label, values = it.bands) }

        fun toDiscreteAnc(mode: SoundcoreAncMode): DiscreteAncState = DiscreteAncState(
            options = listOf(
                DiscreteAncOption("ANC", "Noise cancelling", "Blocks outside sound."),
                DiscreteAncOption("TRANSPARENCY", "Transparency", "Lets outside sound through."),
                DiscreteAncOption("NORMAL", "Normal", "Noise control off."),
            ),
            selectedId = mode.name,
        )

        fun toEqBands(eq: SoundcoreEqState): List<EqBand> =
            eq.bands.mapIndexed { i, v -> EqBand(bandId = i, min = -6, max = 6, current = v) }

        fun presetName(presetId: Int): String? =
            SoundcoreEqPreset.fromId(presetId)?.takeIf { it != SoundcoreEqPreset.CUSTOM }?.name
    }
}
