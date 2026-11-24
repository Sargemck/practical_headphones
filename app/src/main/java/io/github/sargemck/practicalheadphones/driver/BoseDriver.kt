package io.github.sargemck.practicalheadphones.driver

import io.github.sargemck.practicalheadphones.bmap.AnrMode
import io.github.sargemck.practicalheadphones.bmap.AudioSettings
import io.github.sargemck.practicalheadphones.bmap.BmapConnection
import io.github.sargemck.practicalheadphones.bmap.BmapException
import io.github.sargemck.practicalheadphones.bmap.BmapFrame
import io.github.sargemck.practicalheadphones.bmap.BmapOp
import io.github.sargemck.practicalheadphones.bmap.BoseCatalog
import io.github.sargemck.practicalheadphones.bmap.BoseProfile
import io.github.sargemck.practicalheadphones.bmap.CloseReason
import io.github.sargemck.practicalheadphones.bmap.ConnectionState
import io.github.sargemck.practicalheadphones.bmap.EqBand
import io.github.sargemck.practicalheadphones.bmap.EqBandSetting
import io.github.sargemck.practicalheadphones.bmap.Favourites
import io.github.sargemck.practicalheadphones.bmap.Headphones
import io.github.sargemck.practicalheadphones.bmap.ModeLayout
import io.github.sargemck.practicalheadphones.bmap.ModesSnapshot
import io.github.sargemck.practicalheadphones.bmap.ProfileError
import io.github.sargemck.practicalheadphones.bmap.ProfileSettings
import io.github.sargemck.practicalheadphones.bmap.Profiles
import io.github.sargemck.practicalheadphones.bmap.QcUltra2
import io.github.sargemck.practicalheadphones.bmap.ReadFeature
import io.github.sargemck.practicalheadphones.bmap.SelfVoice
import io.github.sargemck.practicalheadphones.bmap.SelfVoiceLevel
import io.github.sargemck.practicalheadphones.bmap.WriteFeature
import io.github.sargemck.practicalheadphones.bmap.WriteReply
import io.github.sargemck.practicalheadphones.state.BatteryPart
import io.github.sargemck.practicalheadphones.state.Capabilities
import io.github.sargemck.practicalheadphones.state.DeviceBadge
import io.github.sargemck.practicalheadphones.state.DiscreteAncOption
import io.github.sargemck.practicalheadphones.state.DiscreteAncState
import io.github.sargemck.practicalheadphones.state.HeadphoneState
import io.github.sargemck.practicalheadphones.state.ModesSection
import io.github.sargemck.practicalheadphones.state.Section
import io.github.sargemck.practicalheadphones.state.valueOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn

/**
 * Adapts a Bose BMAP [BmapConnection] + [BoseProfile] to [HeadphoneDriver].
 *
 * For [BoseCatalog.QC_ULTRA_2], [badge] is null and every wire call is identical to v1.0.0.
 */
class BoseDriver(
    private val connection: BmapConnection,
    scope: CoroutineScope,
    private val profile: BoseProfile = BoseCatalog.QC_ULTRA_2,
    private val log: (String) -> Unit = {},
) : HeadphoneDriver {
    private val headphones = Headphones(connection, profile)

    override val badge: DeviceBadge? = if (profile == BoseCatalog.QC_ULTRA_2) {
        null
    } else {
        DeviceBadge(
            brand = "Bose",
            model = profile.displayName.removePrefix("Bose "),
            experimental = profile.experimental,
        )
    }

    override val capabilities: Capabilities = Capabilities(
        canRename = true,
        canPair = profile.supportsPairingMode,
        canPowerOff = profile.supportsPowerOff,
        canEditModes = profile.modeLayout == ModeLayout.V48_40 || profile.modeLayout == ModeLayout.V47_39,
        editableSlots = profile.editableSlots,
        presetSlots = profile.presetSlots,
        presetNames = profile.presetNames,
    )

    override val state: StateFlow<DriverState> = connection.state
        .map {
            when (it) {
                ConnectionState.Open -> DriverState.Open
                is ConnectionState.Closed -> DriverState.Closed(it.reason)
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, DriverState.Open)

    override val updates: Flow<Pair<SectionKey, (HeadphoneState) -> HeadphoneState>> =
        connection.unsolicited.mapNotNull(::mapUnsolicited)

    override suspend fun initialLoad(apply: ((HeadphoneState) -> HeadphoneState) -> Unit) {
        apply { it.copy(deviceBadge = badge, capabilities = capabilities) }

        if (profile.modeLayout == ModeLayout.NONE) {
            apply { it.copy(modes = Section.Unsupported, audio = Section.Unsupported) }
        } else {
            loadModes(apply)
        }

        if (profile.supportsAnr) {
            val anr = attempt { headphones.anr() }
            apply { s ->
                s.copy(
                    discreteAnc = when (anr) {
                        is Section.Loaded -> Section.Loaded(anrState(anr.value))
                        is Section.Failed -> anr
                        Section.Loading -> Section.Loading
                        Section.Unsupported -> Section.Unsupported
                    },
                )
            }
        }

        val battery = attempt { headphones.batteryStatus() }
        apply { s ->
            when (battery) {
                is Section.Loaded -> s.copy(
                    battery = Section.Loaded(battery.value.percent),
                    batteryParts = battery.value.components.map { BatteryPart(it.label, it.percent) },
                )
                is Section.Failed -> s.copy(battery = battery)
                Section.Loading -> s.copy(battery = Section.Loading)
                Section.Unsupported -> s.copy(battery = Section.Unsupported)
            }
        }

        load({ headphones.read(QcUltra2.productName) }) { s -> apply { it.copy(name = s) } }
        load({ headphones.read(QcUltra2.firmware) }) { s -> apply { it.copy(firmware = s) } }

        if (profile.supportsEq) {
            load({ headphones.read(QcUltra2.eq) }) { s -> apply { it.copy(eq = s) } }
        } else {
            apply { it.copy(eq = Section.Unsupported) }
        }

        if (profile.supportsSelfVoice) {
            load({ headphones.read(QcUltra2.selfVoice) }) { s -> apply { it.copy(selfVoice = s) } }
        } else {
            apply { it.copy(selfVoice = Section.Unsupported) }
        }

        if (profile.supportsAutoPlayPause) {
            load({ headphones.read(QcUltra2.autoPlayPause) }) { s -> apply { it.copy(autoPlayPause = s) } }
        } else {
            apply { it.copy(autoPlayPause = Section.Unsupported) }
        }

        if (profile.supportsAutoAnswer) {
            load({ headphones.read(QcUltra2.autoAnswer) }) { s -> apply { it.copy(autoAnswer = s) } }
        } else {
            apply { it.copy(autoAnswer = Section.Unsupported) }
        }

        if (profile.supportsMultipoint) {
            load({ headphones.read(QcUltra2.multipoint) }) { s -> apply { it.copy(multipoint = s) } }
        } else {
            apply { it.copy(multipoint = Section.Unsupported) }
        }
    }

    override suspend fun retryFailed(
        current: () -> HeadphoneState,
        apply: ((HeadphoneState) -> HeadphoneState) -> Unit,
    ) {
        if (current().modes is Section.Failed && profile.modeLayout != ModeLayout.NONE) {
            apply {
                it.copy(
                    modes = Section.Loading,
                    audio = if (profile.supportsLiveAudioSettings) Section.Loading else Section.Unsupported,
                )
            }
            loadModes(apply)
        } else if (profile.supportsLiveAudioSettings) {
            retry(current, apply, { it.audio }, { headphones.read(QcUltra2.audioSettings) }) { s, v -> s.copy(audio = v) }
        }
        if (profile.supportsAnr) {
            retry(current, apply, { it.discreteAnc }, { anrState(headphones.anr()) }) { s, v -> s.copy(discreteAnc = v) }
        }
        if (current().battery is Section.Failed) {
            apply { it.copy(battery = Section.Loading) }
            val result = attempt { headphones.batteryStatus() }
            apply { s ->
                when (result) {
                    is Section.Loaded -> s.copy(
                        battery = Section.Loaded(result.value.percent),
                        batteryParts = result.value.components.map { BatteryPart(it.label, it.percent) },
                    )
                    is Section.Failed -> s.copy(battery = result)
                    Section.Loading -> s.copy(battery = Section.Loading)
                    Section.Unsupported -> s.copy(battery = Section.Unsupported)
                }
            }
        }
        retry(current, apply, { it.name }, { headphones.read(QcUltra2.productName) }) { s, v -> s.copy(name = v) }
        retry(current, apply, { it.firmware }, { headphones.read(QcUltra2.firmware) }) { s, v -> s.copy(firmware = v) }
        if (profile.supportsEq) retry(current, apply, { it.eq }, { headphones.read(QcUltra2.eq) }) { s, v -> s.copy(eq = v) }
        if (profile.supportsSelfVoice) retry(current, apply, { it.selfVoice }, { headphones.read(QcUltra2.selfVoice) }) { s, v -> s.copy(selfVoice = v) }
        if (profile.supportsAutoPlayPause) retry(current, apply, { it.autoPlayPause }, { headphones.read(QcUltra2.autoPlayPause) }) { s, v -> s.copy(autoPlayPause = v) }
        if (profile.supportsAutoAnswer) retry(current, apply, { it.autoAnswer }, { headphones.read(QcUltra2.autoAnswer) }) { s, v -> s.copy(autoAnswer = v) }
        if (profile.supportsMultipoint) retry(current, apply, { it.multipoint }, { headphones.read(QcUltra2.multipoint) }) { s, v -> s.copy(multipoint = v) }
    }

    override suspend fun poll(
        battery: Boolean,
        guarded: suspend (SectionKey, suspend () -> ((HeadphoneState) -> HeadphoneState)?) -> Unit,
    ) {
        if (profile.modeLayout != ModeLayout.NONE) {
            guarded(SectionKey.Modes) {
                val idx = pollRead { headphones.read(QcUltra2.currentMode) } ?: return@guarded null
                { s ->
                    val modes = s.modes.valueOrNull()
                    if (modes != null && modes.current != idx) s.copy(modes = Section.Loaded(modes.copy(current = idx))) else s
                }
            }
        }
        if (profile.supportsLiveAudioSettings) {
            guarded(SectionKey.Audio) {
                val audio = pollRead { headphones.read(QcUltra2.audioSettings) } ?: return@guarded null
                { s -> s.copy(audio = Section.Loaded(audio)) }
            }
        }
        if (profile.supportsAnr) {
            guarded(SectionKey.DiscreteAnc) {
                val anr = pollRead { headphones.anr() } ?: return@guarded null
                { s -> s.copy(discreteAnc = Section.Loaded(anrState(anr))) }
            }
        }
        if (battery) {
            guarded(SectionKey.Battery) {
                val status = pollRead { headphones.batteryStatus() } ?: return@guarded null
                { s ->
                    s.copy(
                        battery = Section.Loaded(status.percent),
                        batteryParts = status.components.map { BatteryPart(it.label, it.percent) },
                    )
                }
            }
        }
    }

    override suspend fun selectMode(index: Int): AudioSettings? = runBmap {
        if (profile.supportsLiveAudioSettings) {
            headphones.selectMode(index)
        } else {
            headphones.selectModeOnly(index)
            null
        }
    }

    override suspend fun setAudio(settings: AudioSettings): AudioSettings = runBmap {
        val reply = headphones.write(QcUltra2.Write.audioSettings, settings)
        (reply as? WriteReply.Applied)?.value ?: settings
    }

    override suspend fun setDiscreteAnc(optionId: String): DiscreteAncState = runBmap {
        val mode = AnrMode.entries.firstOrNull { it.name.equals(optionId, ignoreCase = true) }
            ?: throw DriverException("Unknown ANR mode $optionId")
        anrState(headphones.setAnr(mode))
    }

    override suspend fun setEqBand(bandId: Int, value: Int): List<EqBand> =
        writeAndConfirm(QcUltra2.Write.eqBand, EqBandSetting(bandId, value), QcUltra2.eq)

    override suspend fun setEq(
        bass: Int,
        mid: Int,
        treble: Int,
        current: List<EqBand>,
        onConfirmed: (List<EqBand>) -> Unit,
    ): List<EqBand> = runBmap {
        val wanted = mapOf(0 to bass, 1 to mid, 2 to treble)
        val changes = current.filter { band -> wanted[band.bandId].let { it != null && it != band.current } }
        var confirmed = current
        for (band in changes) {
            val reply = headphones.write(QcUltra2.Write.eqBand, EqBandSetting(band.bandId, wanted.getValue(band.bandId)))
            confirmed = (reply as? WriteReply.Applied)?.value ?: headphones.read(QcUltra2.eq)
            onConfirmed(confirmed)
        }
        confirmed
    }

    override suspend fun setSelfVoice(level: SelfVoiceLevel): SelfVoice =
        writeAndConfirm(QcUltra2.Write.selfVoice, level, QcUltra2.selfVoice)

    override suspend fun setAutoPlayPause(on: Boolean): Boolean =
        writeAndConfirm(QcUltra2.Write.autoPlayPause, on, QcUltra2.autoPlayPause)

    override suspend fun setAutoAnswer(on: Boolean): Boolean =
        writeAndConfirm(QcUltra2.Write.autoAnswer, on, QcUltra2.autoAnswer)

    override suspend fun setName(name: String): String =
        writeAndConfirm(QcUltra2.Write.productName, name, QcUltra2.productName)

    override suspend fun setFavourites(favourites: Favourites): Favourites =
        writeAndConfirm(QcUltra2.Write.favourites, favourites, QcUltra2.favourites)

    override suspend fun createMode(name: String, settings: ProfileSettings) {
        runBmap {
            val slot = headphones.createProfile(name, settings)
            if (profile.supportsLiveAudioSettings) headphones.selectMode(slot) else headphones.selectModeOnly(slot)
        }
    }

    override suspend fun updateMode(index: Int, name: String, settings: ProfileSettings, isCurrent: Boolean) {
        runBmap {
            headphones.updateMode(index, rename = name, settings = settings)
            if (isCurrent) {
                if (profile.supportsLiveAudioSettings) headphones.selectMode(index) else headphones.selectModeOnly(index)
            }
        }
    }

    override suspend fun deleteMode(index: Int, isCurrent: Boolean, updatedFavourites: Favourites?) = runBmap {
        if (isCurrent) {
            if (profile.supportsLiveAudioSettings) headphones.selectMode(QUIET) else headphones.selectModeOnly(QUIET)
        }
        if (updatedFavourites != null) headphones.write(QcUltra2.Write.favourites, updatedFavourites)
        headphones.deleteMode(index)
    }

    override suspend fun reloadModes(apply: ((HeadphoneState) -> HeadphoneState) -> Unit) = runBmap {
        applyModes(headphones.allModes(), apply)
    }

    override suspend fun powerOff() = runBmap { headphones.powerOff() }

    override suspend fun pairingMode() = runBmap { headphones.pairingMode() }

    override fun close() = connection.close()

    private suspend fun loadModes(apply: ((HeadphoneState) -> HeadphoneState) -> Unit) {
        val snapshot = attempt { headphones.allModes() }
        if (snapshot !is Section.Loaded) {
            apply {
                it.copy(
                    modes = snapshot.cast(),
                    audio = if (profile.supportsLiveAudioSettings) snapshot.cast() else Section.Unsupported,
                )
            }
            return
        }
        applyModes(snapshot.value, apply)
    }

    private suspend fun applyModes(value: ModesSnapshot, apply: ((HeadphoneState) -> HeadphoneState) -> Unit) {
        val current = value.currentMode?.let { Section.Loaded(it) } ?: attempt { headphones.read(QcUltra2.currentMode) }
        val favourites = value.favourites ?: attempt { headphones.read(QcUltra2.favourites) }.valueOrNull()
        val modes = when (current) {
            is Section.Loaded -> Section.Loaded(ModesSection(value.modes.filter(Profiles::isConfigured), current.value, favourites))
            else -> current.cast()
        }
        val audio = if (profile.supportsLiveAudioSettings) {
            value.audioSettings?.let { Section.Loaded(it) } ?: attempt { headphones.read(QcUltra2.audioSettings) }
        } else {
            Section.Unsupported
        }
        apply { it.copy(modes = modes, audio = audio) }
    }

    private suspend fun <T> load(read: suspend () -> T, set: (Section<T>) -> Unit) = set(attempt(read))

    private suspend fun <T> retry(
        current: () -> HeadphoneState,
        apply: ((HeadphoneState) -> HeadphoneState) -> Unit,
        get: (HeadphoneState) -> Section<T>,
        read: suspend () -> T,
        set: (HeadphoneState, Section<T>) -> HeadphoneState,
    ) {
        if (get(current()) !is Section.Failed) return
        apply { set(it, Section.Loading) }
        val result = attempt(read)
        apply { set(it, result) }
    }

    private suspend fun <T> attempt(read: suspend () -> T): Section<T> {
        repeat(2) { i ->
            try {
                return Section.Loaded(read())
            } catch (e: BmapException.Unsupported) {
                return Section.Unsupported
            } catch (e: BmapException.Disconnected) {
                throw DriverException.Disconnected(e.reason, e)
            } catch (e: BmapException.Timeout) {
                throw DriverException.Disconnected(CloseReason.Unresponsive, e)
            } catch (e: BmapException) {
                log("read failed (attempt ${i + 1}): ${e.message}")
                if (i == 1) return Section.Failed(e.message ?: "read failed")
            }
        }
        error("unreachable")
    }

    private suspend fun <T> pollRead(read: suspend () -> T): T? = try {
        read()
    } catch (e: BmapException.Unsupported) {
        null
    } catch (e: BmapException.DeviceError) {
        log("poll failed: ${e.message}")
        null
    } catch (e: BmapException.Malformed) {
        log("poll failed: ${e.message}")
        null
    } catch (e: BmapException.Disconnected) {
        throw DriverException.Disconnected(e.reason, e)
    } catch (e: BmapException.Timeout) {
        throw DriverException.Disconnected(CloseReason.Unresponsive, e)
    }

    private suspend fun <T, S> writeAndConfirm(
        feature: WriteFeature<T, S>,
        value: T,
        read: ReadFeature<S>,
    ): S = runBmap {
        when (val reply = headphones.write(feature, value)) {
            is WriteReply.Applied -> reply.value
            is WriteReply.Raw -> {
                log("write ${feature.address} raw reply ${reply.frame}")
                headphones.read(read)
            }
        }
    }

    private inline fun <T> runBmap(block: () -> T): T = try {
        block()
    } catch (e: BmapException.Disconnected) {
        throw DriverException.Disconnected(e.reason, e)
    } catch (e: BmapException.Timeout) {
        throw DriverException.Disconnected(CloseReason.Unresponsive, e)
    } catch (e: BmapException) {
        throw DriverException(e.message ?: "BMAP error", e)
    } catch (e: ProfileError) {
        throw DriverException(e.message ?: "Profile error", e)
    }

    private fun mapUnsolicited(frame: BmapFrame): Pair<SectionKey, (HeadphoneState) -> HeadphoneState>? {
        if (frame.op != BmapOp.STATUS) return null
        log("unsolicited $frame")
        return when (frame.address) {
            QcUltra2.currentMode.address -> parseOrNull(frame, QcUltra2.currentMode) { idx ->
                SectionKey.Modes to { s ->
                    val modes = s.modes.valueOrNull()
                    if (modes != null && modes.current != idx) s.copy(modes = Section.Loaded(modes.copy(current = idx))) else s
                }
            }
            QcUltra2.audioSettings.address -> parseOrNull(frame, QcUltra2.audioSettings) { v ->
                SectionKey.Audio to { s -> s.copy(audio = Section.Loaded(v)) }
            }
            QcUltra2.battery.address -> parseOrNull(frame, BoseCatalog.batteryStatus) { v ->
                SectionKey.Battery to { s ->
                    s.copy(
                        battery = Section.Loaded(v.percent),
                        batteryParts = v.components.map { BatteryPart(it.label, it.percent) },
                    )
                }
            }
            QcUltra2.favourites.address -> parseOrNull(frame, QcUltra2.favourites) { v ->
                SectionKey.Modes to { s ->
                    val modes = s.modes.valueOrNull()
                    if (modes != null) s.copy(modes = Section.Loaded(modes.copy(favourites = v))) else s
                }
            }
            QcUltra2.eq.address -> parseOrNull(frame, QcUltra2.eq) { v ->
                SectionKey.Eq to { s -> s.copy(eq = Section.Loaded(v)) }
            }
            QcUltra2.selfVoice.address -> parseOrNull(frame, QcUltra2.selfVoice) { v ->
                SectionKey.SelfVoice to { s -> s.copy(selfVoice = Section.Loaded(v)) }
            }
            QcUltra2.autoPlayPause.address -> parseOrNull(frame, QcUltra2.autoPlayPause) { v ->
                SectionKey.AutoPlayPause to { s -> s.copy(autoPlayPause = Section.Loaded(v)) }
            }
            QcUltra2.autoAnswer.address -> parseOrNull(frame, QcUltra2.autoAnswer) { v ->
                SectionKey.AutoAnswer to { s -> s.copy(autoAnswer = Section.Loaded(v)) }
            }
            QcUltra2.productName.address -> parseOrNull(frame, QcUltra2.productName) { v ->
                SectionKey.Name to { s -> s.copy(name = Section.Loaded(v)) }
            }
            QcUltra2.multipoint.address -> parseOrNull(frame, QcUltra2.multipoint) { v ->
                SectionKey.Multipoint to { s -> s.copy(multipoint = Section.Loaded(v)) }
            }
            BoseCatalog.anr.address -> parseOrNull(frame, BoseCatalog.anr) { v ->
                SectionKey.DiscreteAnc to { s -> s.copy(discreteAnc = Section.Loaded(anrState(v))) }
            }
            else -> null
        }
    }

    private fun <T> parseOrNull(
        frame: BmapFrame,
        feature: ReadFeature<T>,
        build: (T) -> Pair<SectionKey, (HeadphoneState) -> HeadphoneState>,
    ): Pair<SectionKey, (HeadphoneState) -> HeadphoneState>? =
        runCatching { build(feature.parse(frame.payload)) }
            .onFailure { log("unsolicited ${frame.address} unparsed: ${it.message}") }
            .getOrNull()

    @Suppress("UNCHECKED_CAST")
    private fun <T> Section<*>.cast(): Section<T> = this as Section<T>

    private companion object {
        const val QUIET = 0

        val ANR_OPTIONS = listOf(
            DiscreteAncOption("HIGH", "High", "Full noise cancellation"),
            DiscreteAncOption("LOW", "Low", "Reduced noise cancellation"),
            DiscreteAncOption("WIND", "Wind", "Cuts wind noise outdoors"),
            DiscreteAncOption("OFF", "Off", "Noise cancellation off"),
        )

        fun anrState(mode: AnrMode) = DiscreteAncState(
            options = ANR_OPTIONS,
            selectedId = mode.name,
        )
    }
}
