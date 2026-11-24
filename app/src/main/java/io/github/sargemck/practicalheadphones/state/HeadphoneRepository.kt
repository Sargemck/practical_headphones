package io.github.sargemck.practicalheadphones.state

import io.github.sargemck.practicalheadphones.bmap.AudioSettings
import io.github.sargemck.practicalheadphones.bmap.BmapConnection
import io.github.sargemck.practicalheadphones.bmap.Favourites
import io.github.sargemck.practicalheadphones.bmap.ModeConfig
import io.github.sargemck.practicalheadphones.bmap.ProfileSettings
import io.github.sargemck.practicalheadphones.bmap.Profiles
import io.github.sargemck.practicalheadphones.bmap.SelfVoiceLevel
import io.github.sargemck.practicalheadphones.driver.BoseDriver
import io.github.sargemck.practicalheadphones.driver.DriverConnector
import io.github.sargemck.practicalheadphones.driver.DriverException
import io.github.sargemck.practicalheadphones.driver.DriverState
import io.github.sargemck.practicalheadphones.driver.HeadphoneDriver
import io.github.sargemck.practicalheadphones.driver.SectionKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Opens a BMAP connection whose first request was answered. Throws if none could be opened. */
fun interface Connector {
    suspend fun connect(scope: CoroutineScope): BmapConnection
}

/** Live update settings (PLAN M2). Polling may be dialled down or switched off after H1. */
data class LiveUpdates(
    val polling: Boolean = true,
    val visibleMs: Long = 2_000,
    val hiddenMs: Long = 60_000,
    val batteryMs: Long = 60_000,
)

/**
 * Owns the headphones' state. Link decisions come from [LinkPolicy]; this class carries them
 * out: connect, initial load, live updates and optimistic writes via any [HeadphoneDriver].
 *
 * Must run on a single-threaded dispatcher ([scope]); the bookkeeping maps aren't synchronised.
 */
class HeadphoneRepository(
    private val scope: CoroutineScope,
    private val driverConnector: DriverConnector,
    private val clock: () -> Long,
    private val log: (String) -> Unit = {},
    private val live: LiveUpdates = LiveUpdates(),
    private val lastKnownStore: LastKnownStore = LastKnownStore.None,
    private val onSelectDevice: (String) -> Unit = {},
) : HeadphoneController {
    /** Compatibility constructor for Bose-only [Connector] callers and tests. */
    constructor(
        scope: CoroutineScope,
        connector: Connector,
        clock: () -> Long,
        log: (String) -> Unit = {},
        live: LiveUpdates = LiveUpdates(),
        lastKnownStore: LastKnownStore = LastKnownStore.None,
    ) : this(
        scope = scope,
        driverConnector = DriverConnector { sessionScope ->
            BoseDriver(connector.connect(sessionScope), sessionScope, log = log)
        },
        clock = clock,
        log = log,
        live = live,
        lastKnownStore = lastKnownStore,
    )

    private data class Visibility(val uiVisible: Boolean, val screenOn: Boolean)

    private val _state = MutableStateFlow(HeadphoneState(lastKnown = lastKnownStore.load()))
    override val state: StateFlow<HeadphoneState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<UserMessage>(extraBufferCapacity = 8)
    override val messages: SharedFlow<UserMessage> = _messages.asSharedFlow()

    private val events = Channel<LinkEvent>(Channel.UNLIMITED)
    private var policy = PolicyState()
    private val visibility = MutableStateFlow(Visibility(uiVisible = false, screenOn = true))

    private var session: Job? = null
    private var driver: HeadphoneDriver? = null

    private val writeStarts = mutableMapOf<SectionKey, Int>()
    private val writesActive = mutableMapOf<SectionKey, Int>()

    private var audioTarget: AudioSettings? = null
    private var audioConfirmed: AudioSettings? = null
    private var audioWriter: Job? = null

    init {
        scope.launch { for (event in events) handle(event) }
    }

    fun onSystemLink(link: SystemLink) {
        events.trySend(LinkEvent.System(link))
    }

    fun onUiVisible(visible: Boolean) = visibility.update { it.copy(uiVisible = visible) }

    fun onScreen(on: Boolean) = visibility.update { it.copy(screenOn = on) }

    fun setBoseAppInstalled(installed: Boolean, companionName: String? = null) =
        _state.update { it.copy(boseAppInstalled = installed, companionAppName = companionName) }

    fun setAvailableDevices(devices: List<PairedHeadphoneChoice>) =
        _state.update { it.copy(availableDevices = devices) }

    override fun selectDevice(address: String) {
        onSelectDevice(address)
    }

    override fun reconnect() {
        events.trySend(LinkEvent.UserReconnect)
    }

    // Link

    private fun handle(event: LinkEvent) {
        val (next, actions) = LinkPolicy.reduce(policy, event)
        if (next.link != policy.link) log("LINK ${policy.link} -> ${next.link} on $event")
        policy = next
        val before = _state.value.lastKnown
        _state.update { if (next.link == LinkState.Connected) it.copy(link = next.link) else it.cleared().copy(link = next.link) }
        _state.value.lastKnown?.takeIf { it !== before }?.let(lastKnownStore::save)
        for (action in actions) {
            when (action) {
                LinkAction.Connect -> startSession()
                LinkAction.CloseSocket -> endSession()
                is LinkAction.StartGrace -> scope.launch {
                    delay(LinkPolicy.GRACE_MS)
                    events.send(LinkEvent.GraceExpired(action.dropId))
                }
            }
        }
    }

    private fun startSession() {
        endSession()
        session = scope.launch {
            val started = clock()
            val d = try {
                driverConnector.connect(this)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("CONNECT failed: ${e.message}")
                events.send(LinkEvent.ConnectFailed)
                return@launch
            }
            driver = d
            events.send(LinkEvent.ConnectSucceeded)
            launch {
                val closed = d.state.first { it is DriverState.Closed } as DriverState.Closed
                driver = null
                events.send(LinkEvent.SocketClosed(closed.reason, clock()))
            }
            launch {
                d.updates.collect { (key, mutate) ->
                    if ((writesActive[key] ?: 0) == 0) {
                        _state.update { s ->
                            val updated = mutate(s)
                            if (key == SectionKey.Audio) {
                                updated.audio.valueOrNull()?.let { audioConfirmed = it }
                            }
                            updated
                        }
                    }
                }
            }
            try {
                d.initialLoad { mutate ->
                    _state.update { s ->
                        val updated = mutate(s)
                        updated.audio.valueOrNull()?.let { audioConfirmed = it }
                        updated
                    }
                }
                log("LOADED in ${clock() - started} ms")
                lastKnownStore.save(_state.value.copy(lastKnown = null))
                if (live.polling) poll(d)
            } catch (e: DriverException) {
                log("session ended: ${e.message}")
            }
        }
    }

    private fun endSession() {
        session?.cancel()
        session = null
        driver?.close()
        driver = null
        audioWriter = null
        audioTarget = null
        audioConfirmed = null
        writeStarts.clear()
        writesActive.clear()
    }

    /** Re-reads every section that failed to load, each with the usual single retry. */
    override fun retryFailed() {
        val d = driver ?: return message("Not connected")
        launchInSession {
            try {
                d.retryFailed({ _state.value }) { mutate ->
                    _state.update { s ->
                        val updated = mutate(s)
                        updated.audio.valueOrNull()?.let { audioConfirmed = it }
                        updated
                    }
                }
            } catch (e: DriverException) {
                log("retry ended: ${e.message}")
            }
        }
    }

    // Live updates

    private suspend fun poll(d: HeadphoneDriver) {
        var lastBattery = clock()
        while (true) {
            val v = visibility.value
            if (!v.uiVisible && !v.screenOn) {
                visibility.first { it.uiVisible || it.screenOn }
                pollOnce(d, battery = true)
                lastBattery = clock()
                continue
            }
            val interval = if (v.uiVisible) live.visibleMs else live.hiddenMs
            val changed = withTimeoutOrNull(interval) { visibility.drop(1).first() }
            if (changed != null) continue
            val battery = clock() - lastBattery >= live.batteryMs
            pollOnce(d, battery)
            if (battery) lastBattery = clock()
        }
    }

    private suspend fun pollOnce(d: HeadphoneDriver, battery: Boolean) {
        d.poll(battery) { key, read ->
            val startsBefore = writeStarts[key] ?: 0
            if ((writesActive[key] ?: 0) > 0) return@poll
            val mutate = read() ?: return@poll
            if ((writeStarts[key] ?: 0) != startsBefore || (writesActive[key] ?: 0) > 0) {
                log("poll of $key dropped: write in progress")
                return@poll
            }
            _state.update { s ->
                val updated = mutate(s)
                if (key == SectionKey.Audio) {
                    updated.audio.valueOrNull()?.let { audioConfirmed = it }
                }
                updated
            }
        }
    }

    private fun applyAudio(value: AudioSettings) {
        audioConfirmed = value
        _state.update { it.copy(audio = Section.Loaded(value)) }
    }

    // Writes

    /**
     * Live audio settings. Optimistic, built from cached state, and conflated: while a write is
     * in flight only the latest wanted value is kept.
     */
    override fun setAudio(change: (AudioSettings) -> AudioSettings) {
        val current = _state.value.audio.valueOrNull() ?: return message("Not connected")
        val d = driver ?: return message("Not connected")
        val wanted = change(current)
        if (audioConfirmed == null) audioConfirmed = current
        audioTarget = wanted
        started(SectionKey.Audio)
        _state.update { it.copy(audio = Section.Loaded(wanted)) }
        if (audioWriter?.isActive == true) return
        val owner = session ?: return
        audioWriter = scope.launch(owner) {
            active(SectionKey.Audio) {
                while (true) {
                    val target = audioTarget ?: break
                    audioTarget = null
                    try {
                        val applied = d.setAudio(target)
                        audioConfirmed = applied
                        if (audioTarget == null) _state.update { it.copy(audio = Section.Loaded(applied)) }
                    } catch (e: DriverException) {
                        audioTarget = null
                        val confirmed = audioConfirmed
                        if (confirmed != null) _state.update { it.copy(audio = Section.Loaded(confirmed)) }
                        message("Couldn't change noise control")
                        log("audio write failed: ${e.message}")
                        break
                    }
                }
            }
        }
    }

    fun toggleAnc() {
        val state = _state.value
        if (state.audio is Section.Loaded) {
            setAudio { it.copy(anc = !it.anc) }
        } else if (state.discreteAnc is Section.Loaded) {
            val anc = state.discreteAnc.value
            val off = anc.options.firstOrNull { it.id.equals("OFF", ignoreCase = true) }?.id
            val on = anc.options.firstOrNull { !it.id.equals("OFF", ignoreCase = true) }?.id
            if (off != null && on != null) {
                setDiscreteAnc(if (anc.selectedId.equals(off, ignoreCase = true)) on else off)
            }
        } else {
            message("Not connected")
        }
    }

    override fun setDiscreteAnc(optionId: String) {
        val current = _state.value.discreteAnc.valueOrNull() ?: return message("Not connected")
        writeSection(
            key = SectionKey.DiscreteAnc,
            optimistic = current.copy(selectedId = optionId),
            get = { it.discreteAnc },
            set = { s, v -> s.copy(discreteAnc = v) },
            failure = "Couldn't change noise control",
        ) { it.setDiscreteAnc(optionId) }
    }

    override fun setAmbientLevel(level: Int) {
        val current = _state.value.discreteAnc.valueOrNull() ?: return message("Not connected")
        writeSection(
            key = SectionKey.DiscreteAnc,
            optimistic = current.copy(ambientLevel = level),
            get = { it.discreteAnc },
            set = { s, v -> s.copy(discreteAnc = v) },
            failure = "Couldn't change ambient sound",
        ) { it.setAmbientLevel(level) }
    }

    override fun setFocusOnVoice(on: Boolean) {
        val current = _state.value.discreteAnc.valueOrNull() ?: return message("Not connected")
        writeSection(
            key = SectionKey.DiscreteAnc,
            optimistic = current.copy(focusOnVoice = on),
            get = { it.discreteAnc },
            set = { s, v -> s.copy(discreteAnc = v) },
            failure = "Couldn't change focus on voice",
        ) { it.setFocusOnVoice(on) }
    }

    override fun setSpeakToChat(on: Boolean) {
        writeSection(
            key = SectionKey.SpeakToChat,
            optimistic = on,
            get = { it.speakToChat },
            set = { s, v -> s.copy(speakToChat = v) },
            failure = "Couldn't change Speak-to-Chat",
        ) { it.setSpeakToChat(on) }
    }

    /** Switches to a configured mode, then takes the fresh audio settings the switch produced. */
    override fun selectMode(index: Int) {
        val modes = _state.value.modes.valueOrNull() ?: return message("Not connected")
        val d = driver ?: return message("Not connected")
        if (modes.modes.none { it.index == index }) return message("That mode doesn't exist")
        val previous = modes.current
        started(SectionKey.Modes)
        started(SectionKey.Audio)
        _state.update { it.copy(modes = Section.Loaded(modes.copy(current = index))) }
        launchInSession {
            active(SectionKey.Modes) {
                active(SectionKey.Audio) {
                    try {
                        d.selectMode(index)?.let(::applyAudio)
                    } catch (e: DriverException) {
                        _state.value.modes.valueOrNull()?.let { m ->
                            _state.update { it.copy(modes = Section.Loaded(m.copy(current = previous))) }
                        }
                        message("Couldn't switch mode")
                        log("mode switch failed: ${e.message}")
                    }
                }
            }
        }
    }

    override fun nextFavouriteMode() {
        val modes = _state.value.modes.valueOrNull() ?: return message("Not connected")
        nextFavourite(modes)?.let(::selectMode) ?: message("No favourite modes")
    }

    override fun setEqBand(bandId: Int, value: Int) {
        val bands = _state.value.eq.valueOrNull() ?: return message("Not connected")
        writeSection(
            key = SectionKey.Eq,
            optimistic = bands.map { if (it.bandId == bandId) it.copy(current = value) else it },
            get = { it.eq },
            set = { s, v -> s.copy(eq = v) },
            failure = "Couldn't change the equaliser",
        ) { it.setEqBand(bandId, value) }
    }

    override fun setEq(bass: Int, mid: Int, treble: Int) {
        val d = driver ?: return message("Not connected")
        val previous = _state.value.eq.valueOrNull() ?: return message("Not connected")
        val wanted = mapOf(0 to bass, 1 to mid, 2 to treble)
        val changes = previous.filter { band -> wanted[band.bandId].let { it != null && it != band.current } }
        if (changes.isEmpty()) return
        started(SectionKey.Eq)
        _state.update { it.copy(eq = Section.Loaded(previous.map { b -> b.copy(current = wanted[b.bandId] ?: b.current) })) }
        launchInSession {
            active(SectionKey.Eq) {
                var confirmed = previous
                try {
                    confirmed = d.setEq(bass, mid, treble, previous) { confirmed = it }
                    _state.update { it.copy(eq = Section.Loaded(confirmed)) }
                } catch (e: DriverException) {
                    _state.update { it.copy(eq = Section.Loaded(confirmed)) }
                    message("Couldn't change the equaliser")
                    log("eq write failed: ${e.message}")
                }
            }
        }
    }

    override fun setEqPreset(presetId: String) {
        val bands = _state.value.eq.valueOrNull() ?: return message("Not connected")
        val preset = _state.value.eqPresets?.firstOrNull { it.id == presetId }
        val optimistic = if (preset != null && preset.values.size == bands.size) {
            bands.sortedBy { it.bandId }.mapIndexed { i, b -> b.copy(current = preset.values[i]) }
        } else {
            bands
        }
        writeSection(
            key = SectionKey.Eq,
            optimistic = optimistic,
            get = { it.eq },
            set = { s, v -> s.copy(eq = v) },
            failure = "Couldn't change the equaliser",
        ) { it.setEqPreset(presetId) }
    }

    override fun setSelfVoice(level: SelfVoiceLevel) {
        val current = _state.value.selfVoice.valueOrNull() ?: return message("Not connected")
        writeSection(
            key = SectionKey.SelfVoice,
            optimistic = current.copy(level = level),
            get = { it.selfVoice },
            set = { s, v -> s.copy(selfVoice = v) },
            failure = "Couldn't change Self Voice",
        ) { it.setSelfVoice(level) }
    }

    override fun setAutoPlayPause(on: Boolean) =
        writeSection(SectionKey.AutoPlayPause, on, { it.autoPlayPause }, { s, v -> s.copy(autoPlayPause = v) }, "Couldn't change auto play/pause") {
            it.setAutoPlayPause(on)
        }

    override fun setAutoAnswer(on: Boolean) =
        writeSection(SectionKey.AutoAnswer, on, { it.autoAnswer }, { s, v -> s.copy(autoAnswer = v) }, "Couldn't change auto answer") {
            it.setAutoAnswer(on)
        }

    override fun setName(name: String) =
        writeSection(SectionKey.Name, name.trim(), { it.name }, { s, v -> s.copy(name = v) }, "Couldn't rename the headphones") {
            it.setName(name)
        }

    /** Adds or removes [index] from the favourites, built from cached state. */
    override fun toggleFavourite(index: Int) {
        val favourites = _state.value.modes.valueOrNull()?.favourites ?: return message("Not connected")
        val slots = if (index in favourites.slots) favourites.slots - index else favourites.slots + index
        setFavourites(favourites.copy(slots = slots))
    }

    fun setFavourites(favourites: Favourites) =
        writeSection(
            key = SectionKey.Modes,
            optimistic = favourites,
            get = { s -> s.modes.valueOrNull()?.favourites?.let { Section.Loaded(it) } ?: Section.Loading },
            set = { s, v -> s.modes.valueOrNull()?.let { m -> s.copy(modes = Section.Loaded(m.copy(favourites = v.valueOrNull()))) } ?: s },
            failure = "Couldn't change favourites",
        ) { it.setFavourites(favourites) }

    private fun <S> writeSection(
        key: SectionKey,
        optimistic: S,
        get: (HeadphoneState) -> Section<S>,
        set: (HeadphoneState, Section<S>) -> HeadphoneState,
        failure: String,
        send: suspend (HeadphoneDriver) -> S,
    ) {
        val d = driver ?: return message("Not connected")
        val previous = get(_state.value)
        if (previous !is Section.Loaded) return message("Not connected")
        started(key)
        _state.update { set(it, Section.Loaded(optimistic)) }
        launchInSession {
            active(key) {
                try {
                    val confirmed = send(d)
                    _state.update { set(it, Section.Loaded(confirmed)) }
                } catch (e: DriverException) {
                    _state.update { set(it, previous) }
                    message(failure)
                    log("write $key failed: ${e.message}")
                }
            }
        }
    }

    // Custom modes

    /** Creates a mode in the first free slot from [settings], then switches to it. */
    override fun createMode(name: String, settings: ProfileSettings) {
        val modes = _state.value.modes.valueOrNull() ?: return message("Not connected")
        val caps = _state.value.capabilities
        val slot = Profiles.findFreeSlot(modes.modes, caps.editableSlots) ?: return message("All custom modes are in use")
        if (Profiles.nameProblem(name, modes.modes, presetNames = caps.presetNames) != null) return message("That name can't be used")
        val mode = ModeConfig(slot, 0, 0, editable = true, configured = true, name.trim(), settings.cnc, false, settings.spatial, settings.wind, settings.anc)
        val optimistic = modes.copy(modes = (modes.modes + mode).sortedBy { it.index }, current = slot)
        changeModes(optimistic, if (_state.value.audio is Section.Loaded) audioOf(mode) else null, "Couldn't create the mode") { d ->
            d.createMode(name, settings)
        }
    }

    override fun updateMode(index: Int, name: String, settings: ProfileSettings) {
        val modes = _state.value.modes.valueOrNull() ?: return message("Not connected")
        val caps = _state.value.capabilities
        val old = modes.modes.firstOrNull { it.index == index } ?: return message("That mode doesn't exist")
        if (index in caps.presetSlots) return message("Bose presets can't be changed")
        if (Profiles.nameProblem(name, modes.modes, except = index, presetNames = caps.presetNames) != null) return message("That name can't be used")
        val mode = old.copy(name = name.trim(), cnc = settings.cnc, spatial = settings.spatial, wind = settings.wind, anc = settings.anc)
        val current = modes.current == index
        val optimistic = modes.copy(modes = modes.modes.map { if (it.index == index) mode else it })
        changeModes(optimistic, if (current && _state.value.audio is Section.Loaded) audioOf(mode) else null, "Couldn't save the mode") { d ->
            d.updateMode(index, name, settings, isCurrent = current)
        }
    }

    override fun deleteMode(index: Int) {
        val modes = _state.value.modes.valueOrNull() ?: return message("Not connected")
        val caps = _state.value.capabilities
        if (modes.modes.none { it.index == index }) return message("That mode doesn't exist")
        if (index in caps.presetSlots) return message("Bose presets can't be deleted")
        val current = modes.current == index
        val fallback = modes.modes.firstOrNull { it.index == QUIET }
        val favourites = modes.favourites?.takeIf { index in it.slots }?.let { it.copy(slots = it.slots - index) }
        val optimistic = modes.copy(
            modes = modes.modes.filter { it.index != index },
            current = if (current) QUIET else modes.current,
            favourites = favourites ?: modes.favourites,
        )
        changeModes(
            optimistic,
            if (current && _state.value.audio is Section.Loaded) fallback?.let(::audioOf) else null,
            "Couldn't delete the mode",
        ) { d ->
            d.deleteMode(index, isCurrent = current, updatedFavourites = favourites)
        }
    }

    private fun changeModes(
        optimistic: ModesSection,
        audio: AudioSettings?,
        failure: String,
        work: suspend (HeadphoneDriver) -> Unit,
    ) {
        val d = driver ?: return message("Not connected")
        val previous = _state.value
        started(SectionKey.Modes)
        started(SectionKey.Audio)
        _state.update { s -> s.copy(modes = Section.Loaded(optimistic), audio = audio?.let { Section.Loaded(it) } ?: s.audio) }
        launchInSession {
            active(SectionKey.Modes) {
                active(SectionKey.Audio) {
                    try {
                        work(d)
                    } catch (e: DriverException) {
                        rollBack(previous, failure, e)
                    }
                    try {
                        d.reloadModes { mutate ->
                            _state.update { s ->
                                val updated = mutate(s)
                                updated.audio.valueOrNull()?.let { audioConfirmed = it }
                                updated
                            }
                        }
                    } catch (e: DriverException) {
                        log("modes reload failed: ${e.message}")
                    }
                }
            }
        }
    }

    private fun rollBack(previous: HeadphoneState, failure: String, e: Exception) {
        _state.update { it.copy(modes = previous.modes, audio = previous.audio) }
        message(failure)
        log("mode change failed: ${e.message}")
    }

    private fun audioOf(mode: ModeConfig) = AudioSettings(mode.cnc, autoCnc = false, mode.spatial, mode.wind, mode.anc)

    // Commands that drop the link.

    override fun powerOff() = command(Command.PowerOff) { it.powerOff() }

    override fun pairingMode() = command(Command.PairingMode) { it.pairingMode() }

    private fun command(command: Command, send: suspend (HeadphoneDriver) -> Unit) {
        val d = driver ?: return message("Not connected")
        events.trySend(LinkEvent.CommandSent(command, clock()))
        launchInSession {
            try {
                send(d)
            } catch (e: DriverException) {
                log("$command failed: ${e.message}")
                message("The headphones didn't respond")
            }
        }
    }

    // Helpers

    private fun launchInSession(block: suspend CoroutineScope.() -> Unit) {
        val owner = session ?: return
        scope.launch(owner, block = block)
    }

    private fun started(key: SectionKey) {
        writeStarts[key] = (writeStarts[key] ?: 0) + 1
    }

    private inline fun active(key: SectionKey, block: () -> Unit) {
        writesActive[key] = (writesActive[key] ?: 0) + 1
        try {
            block()
        } finally {
            writesActive[key] = (writesActive[key] ?: 1) - 1
        }
    }

    private fun message(text: String) {
        _messages.tryEmit(UserMessage(text))
    }

    private companion object {
        /** Slot 0, the Quiet preset: where deleting the current mode leaves the headphones. */
        const val QUIET = 0
    }
}
