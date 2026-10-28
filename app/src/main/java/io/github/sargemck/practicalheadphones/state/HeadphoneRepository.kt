package io.github.sargemck.practicalheadphones.state

import io.github.sargemck.practicalheadphones.bmap.AudioSettings
import io.github.sargemck.practicalheadphones.bmap.BmapConnection
import io.github.sargemck.practicalheadphones.bmap.BmapException
import io.github.sargemck.practicalheadphones.bmap.CloseReason
import io.github.sargemck.practicalheadphones.bmap.BmapFrame
import io.github.sargemck.practicalheadphones.bmap.BmapOp
import io.github.sargemck.practicalheadphones.bmap.ConnectionState
import io.github.sargemck.practicalheadphones.bmap.EqBandSetting
import io.github.sargemck.practicalheadphones.bmap.Favourites
import io.github.sargemck.practicalheadphones.bmap.Headphones
import io.github.sargemck.practicalheadphones.bmap.ModeConfig
import io.github.sargemck.practicalheadphones.bmap.QcUltra2
import io.github.sargemck.practicalheadphones.bmap.ReadFeature
import io.github.sargemck.practicalheadphones.bmap.SelfVoiceLevel
import io.github.sargemck.practicalheadphones.bmap.WriteFeature
import io.github.sargemck.practicalheadphones.bmap.WriteReply
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

/** Opens a connection whose first BMAP request was answered. Throws if none could be opened. */
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
 * out: connect, initial load, live updates and optimistic writes.
 *
 * Must run on a single-threaded dispatcher ([scope]); the bookkeeping maps aren't synchronised.
 */
class HeadphoneRepository(
    private val scope: CoroutineScope,
    private val connector: Connector,
    private val clock: () -> Long,
    private val log: (String) -> Unit = {},
    private val live: LiveUpdates = LiveUpdates(),
) {
    private enum class Key { Name, Battery, Modes, Audio, Eq, SelfVoice, AutoPlayPause, AutoAnswer, Multipoint }

    private data class Visibility(val uiVisible: Boolean, val screenOn: Boolean)

    private val _state = MutableStateFlow(HeadphoneState())
    val state: StateFlow<HeadphoneState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<UserMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<UserMessage> = _messages.asSharedFlow()

    private val events = Channel<LinkEvent>(Channel.UNLIMITED)
    private var policy = PolicyState()
    private val visibility = MutableStateFlow(Visibility(uiVisible = false, screenOn = true))

    private var session: Job? = null
    private var headphones: Headphones? = null

    private val writeStarts = mutableMapOf<Key, Int>()
    private val writesActive = mutableMapOf<Key, Int>()

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

    fun setBoseAppInstalled(installed: Boolean) = _state.update { it.copy(boseAppInstalled = installed) }

    fun reconnect() {
        events.trySend(LinkEvent.UserReconnect)
    }

    // Link

    private fun handle(event: LinkEvent) {
        val (next, actions) = LinkPolicy.reduce(policy, event)
        if (next.link != policy.link) log("LINK ${policy.link} -> ${next.link} on $event")
        policy = next
        _state.update { if (next.link == LinkState.Connected) it.copy(link = next.link) else it.cleared().copy(link = next.link) }
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
            val connection = try {
                connector.connect(this)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("CONNECT failed: ${e.message}")
                events.send(LinkEvent.ConnectFailed)
                return@launch
            }
            val h = Headphones(connection)
            headphones = h
            events.send(LinkEvent.ConnectSucceeded)
            launch {
                val closed = connection.state.first { it is ConnectionState.Closed } as ConnectionState.Closed
                headphones = null
                events.send(LinkEvent.SocketClosed(closed.reason, clock()))
            }
            launch { connection.unsolicited.collect { applyFrame(it) } }
            try {
                initialLoad(h)
                log("LOADED in ${clock() - started} ms")
                if (live.polling) poll(h)
            } catch (e: BmapException) {
                log("session ended: ${e.message}")
            }
        }
    }

    private fun endSession() {
        session?.cancel()
        session = null
        headphones = null
        audioWriter = null
        audioTarget = null
        audioConfirmed = null
        writeStarts.clear()
        writesActive.clear()
    }

    // Initial load

    private suspend fun initialLoad(h: Headphones) {
        loadModes(h)
        load({ h.read(QcUltra2.battery) }) { s -> _state.update { it.copy(battery = s) } }
        load({ h.read(QcUltra2.productName) }) { s -> _state.update { it.copy(name = s) } }
        load({ h.read(QcUltra2.firmware) }) { s -> _state.update { it.copy(firmware = s) } }
        load({ h.read(QcUltra2.eq) }) { s -> _state.update { it.copy(eq = s) } }
        load({ h.read(QcUltra2.selfVoice) }) { s -> _state.update { it.copy(selfVoice = s) } }
        load({ h.read(QcUltra2.autoPlayPause) }) { s -> _state.update { it.copy(autoPlayPause = s) } }
        load({ h.read(QcUltra2.autoAnswer) }) { s -> _state.update { it.copy(autoAnswer = s) } }
        load({ h.read(QcUltra2.multipoint) }) { s -> _state.update { it.copy(multipoint = s) } }
    }

    private suspend fun loadModes(h: Headphones) {
        val snapshot = attempt { h.allModes() }
        if (snapshot !is Section.Loaded) {
            _state.update { it.copy(modes = snapshot.cast(), audio = snapshot.cast()) }
            return
        }
        val value = snapshot.value
        val current = value.currentMode?.let { Section.Loaded(it) } ?: attempt { h.read(QcUltra2.currentMode) }
        val favourites = value.favourites ?: attempt { h.read(QcUltra2.favourites) }.valueOrNull()
        val modes = when (current) {
            is Section.Loaded -> Section.Loaded(ModesSection(value.modes.filter(::isConfigured), current.value, favourites))
            else -> current.cast()
        }
        val audio = value.audioSettings?.let { Section.Loaded(it) } ?: attempt { h.read(QcUltra2.audioSettings) }
        audioConfirmed = audio.valueOrNull()
        _state.update { it.copy(modes = modes, audio = audio) }
    }

    private suspend fun <T> load(read: suspend () -> T, set: (Section<T>) -> Unit) = set(attempt(read))

    /** One read, retried once on a device error or malformed reply. Disconnects end the session. */
    private suspend fun <T> attempt(read: suspend () -> T): Section<T> {
        repeat(2) { i ->
            try {
                return Section.Loaded(read())
            } catch (e: BmapException.Unsupported) {
                return Section.Unsupported
            } catch (e: BmapException.Disconnected) {
                throw e
            } catch (e: BmapException.Timeout) {
                throw BmapException.Disconnected(CloseReason.Unresponsive)
            } catch (e: BmapException) {
                log("read failed (attempt ${i + 1}): ${e.message}")
                if (i == 1) return Section.Failed(e.message ?: "read failed")
            }
        }
        error("unreachable")
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> Section<*>.cast(): Section<T> = this as Section<T>

    // Live updates

    private suspend fun poll(h: Headphones) {
        var lastBattery = clock()
        while (true) {
            val v = visibility.value
            if (!v.uiVisible && !v.screenOn) {
                visibility.first { it.uiVisible || it.screenOn }
                pollOnce(h, battery = true)
                lastBattery = clock()
                continue
            }
            val interval = if (v.uiVisible) live.visibleMs else live.hiddenMs
            val changed = withTimeoutOrNull(interval) { visibility.drop(1).first() }
            if (changed != null) continue
            val battery = clock() - lastBattery >= live.batteryMs
            pollOnce(h, battery)
            if (battery) lastBattery = clock()
        }
    }

    private suspend fun pollOnce(h: Headphones, battery: Boolean) {
        guarded(Key.Modes, { h.read(QcUltra2.currentMode) }) { applyCurrentMode(it) }
        guarded(Key.Audio, { h.read(QcUltra2.audioSettings) }) { applyAudio(it) }
        if (battery) guarded(Key.Battery, { h.read(QcUltra2.battery) }) { value -> _state.update { it.copy(battery = Section.Loaded(value)) } }
    }

    /**
     * Reads and applies, unless a write to [key] was pending or in flight when the read was
     * sent, or started since. Stops a queued poll from reverting an optimistic value.
     */
    private suspend fun <T> guarded(key: Key, read: suspend () -> T, apply: (T) -> Unit) {
        val startsBefore = writeStarts[key] ?: 0
        if ((writesActive[key] ?: 0) > 0) return
        val value = try {
            read()
        } catch (e: BmapException.Unsupported) {
            return
        } catch (e: BmapException.DeviceError) {
            log("poll failed: ${e.message}")
            return
        } catch (e: BmapException.Malformed) {
            log("poll failed: ${e.message}")
            return
        }
        if ((writeStarts[key] ?: 0) != startsBefore || (writesActive[key] ?: 0) > 0) {
            log("poll of $key dropped: write in progress")
            return
        }
        apply(value)
    }

    private fun applyCurrentMode(index: Int) {
        val modes = _state.value.modes.valueOrNull() ?: return
        if (modes.current != index) _state.update { it.copy(modes = Section.Loaded(modes.copy(current = index))) }
    }

    private fun applyAudio(value: AudioSettings) {
        audioConfirmed = value
        _state.update { it.copy(audio = Section.Loaded(value)) }
    }

    /** Unsolicited frames go through the read parsers into state, with the same write guard as polls. */
    private fun applyFrame(frame: BmapFrame) {
        if (frame.op != BmapOp.STATUS) return
        fun <T> on(feature: ReadFeature<T>, key: Key, apply: (T) -> Unit) {
            if (frame.address != feature.address) return
            if ((writesActive[key] ?: 0) > 0) return
            runCatching { feature.parse(frame.payload) }
                .onSuccess(apply)
                .onFailure { log("unsolicited ${frame.address} unparsed: ${it.message}") }
        }
        log("unsolicited $frame")
        on(QcUltra2.currentMode, Key.Modes, ::applyCurrentMode)
        on(QcUltra2.audioSettings, Key.Audio, ::applyAudio)
        on(QcUltra2.battery, Key.Battery) { v -> _state.update { it.copy(battery = Section.Loaded(v)) } }
        on(QcUltra2.favourites, Key.Modes) { v ->
            val modes = _state.value.modes.valueOrNull() ?: return@on
            _state.update { it.copy(modes = Section.Loaded(modes.copy(favourites = v))) }
        }
        on(QcUltra2.eq, Key.Eq) { v -> _state.update { it.copy(eq = Section.Loaded(v)) } }
        on(QcUltra2.selfVoice, Key.SelfVoice) { v -> _state.update { it.copy(selfVoice = Section.Loaded(v)) } }
        on(QcUltra2.autoPlayPause, Key.AutoPlayPause) { v -> _state.update { it.copy(autoPlayPause = Section.Loaded(v)) } }
        on(QcUltra2.autoAnswer, Key.AutoAnswer) { v -> _state.update { it.copy(autoAnswer = Section.Loaded(v)) } }
        on(QcUltra2.productName, Key.Name) { v -> _state.update { it.copy(name = Section.Loaded(v)) } }
        on(QcUltra2.multipoint, Key.Multipoint) { v -> _state.update { it.copy(multipoint = Section.Loaded(v)) } }
    }

    // Writes

    /**
     * Live audio settings. Optimistic, built from cached state, and conflated: while a write is
     * in flight only the latest wanted value is kept.
     */
    fun setAudio(change: (AudioSettings) -> AudioSettings) {
        val current = _state.value.audio.valueOrNull() ?: return message("Not connected")
        val h = headphones ?: return message("Not connected")
        val wanted = change(current)
        if (audioConfirmed == null) audioConfirmed = current
        audioTarget = wanted
        started(Key.Audio)
        _state.update { it.copy(audio = Section.Loaded(wanted)) }
        if (audioWriter?.isActive == true) return
        val owner = session ?: return
        audioWriter = scope.launch(owner) {
            active(Key.Audio) {
                while (true) {
                    val target = audioTarget ?: break
                    audioTarget = null
                    try {
                        val reply = h.write(QcUltra2.Write.audioSettings, target)
                        val applied = (reply as? WriteReply.Applied)?.value ?: target
                        audioConfirmed = applied
                        if (audioTarget == null) _state.update { it.copy(audio = Section.Loaded(applied)) }
                    } catch (e: BmapException) {
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

    fun toggleAnc() = setAudio { it.copy(anc = !it.anc) }

    /** Switches to a configured mode, then takes the fresh audio settings the switch produced. */
    fun selectMode(index: Int) {
        val modes = _state.value.modes.valueOrNull() ?: return message("Not connected")
        val h = headphones ?: return message("Not connected")
        if (modes.modes.none { it.index == index }) return message("That mode doesn't exist")
        val previous = modes.current
        started(Key.Modes)
        started(Key.Audio)
        _state.update { it.copy(modes = Section.Loaded(modes.copy(current = index))) }
        launchInSession {
            active(Key.Modes) {
                active(Key.Audio) {
                    try {
                        applyAudio(h.selectMode(index))
                    } catch (e: BmapException) {
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

    fun nextFavouriteMode() {
        val modes = _state.value.modes.valueOrNull() ?: return message("Not connected")
        nextFavourite(modes)?.let(::selectMode) ?: message("No favourite modes")
    }

    fun setEqBand(bandId: Int, value: Int) {
        val bands = _state.value.eq.valueOrNull() ?: return message("Not connected")
        write(
            key = Key.Eq,
            feature = QcUltra2.Write.eqBand,
            value = EqBandSetting(bandId, value),
            optimistic = bands.map { if (it.bandId == bandId) it.copy(current = value) else it },
            read = QcUltra2.eq,
            get = { it.eq },
            set = { s, v -> s.copy(eq = v) },
            failure = "Couldn't change the equaliser",
        )
    }

    fun setSelfVoice(level: SelfVoiceLevel) {
        val current = _state.value.selfVoice.valueOrNull() ?: return message("Not connected")
        write(Key.SelfVoice, QcUltra2.Write.selfVoice, level, current.copy(level = level), QcUltra2.selfVoice, { it.selfVoice }, { s, v -> s.copy(selfVoice = v) }, "Couldn't change Self Voice")
    }

    fun setAutoPlayPause(on: Boolean) =
        write(Key.AutoPlayPause, QcUltra2.Write.autoPlayPause, on, on, QcUltra2.autoPlayPause, { it.autoPlayPause }, { s, v -> s.copy(autoPlayPause = v) }, "Couldn't change auto play/pause")

    fun setAutoAnswer(on: Boolean) =
        write(Key.AutoAnswer, QcUltra2.Write.autoAnswer, on, on, QcUltra2.autoAnswer, { it.autoAnswer }, { s, v -> s.copy(autoAnswer = v) }, "Couldn't change auto answer")

    fun setName(name: String) =
        write(Key.Name, QcUltra2.Write.productName, name, name.trim(), QcUltra2.productName, { it.name }, { s, v -> s.copy(name = v) }, "Couldn't rename the headphones")

    fun setFavourites(favourites: Favourites) =
        write(
            key = Key.Modes,
            feature = QcUltra2.Write.favourites,
            value = favourites,
            optimistic = favourites,
            read = QcUltra2.favourites,
            get = { s -> s.modes.valueOrNull()?.favourites?.let { Section.Loaded(it) } ?: Section.Loading },
            set = { s, v -> s.modes.valueOrNull()?.let { m -> s.copy(modes = Section.Loaded(m.copy(favourites = v.valueOrNull()))) } ?: s },
            failure = "Couldn't change favourites",
        )

    /**
     * Optimistic write: show [optimistic], send, then reconcile. A parsed reply is taken as the
     * truth; a raw (unverified) reply is confirmed with one GET. Failure rolls back and tells the user.
     */
    private fun <T, S> write(
        key: Key,
        feature: WriteFeature<T, S>,
        value: T,
        optimistic: S,
        read: ReadFeature<S>,
        get: (HeadphoneState) -> Section<S>,
        set: (HeadphoneState, Section<S>) -> HeadphoneState,
        failure: String,
    ) {
        val h = headphones ?: return message("Not connected")
        val previous = get(_state.value)
        if (previous !is Section.Loaded) return message("Not connected")
        started(key)
        _state.update { set(it, Section.Loaded(optimistic)) }
        launchInSession {
            active(key) {
                try {
                    val confirmed = when (val reply = h.write(feature, value)) {
                        is WriteReply.Applied -> reply.value
                        is WriteReply.Raw -> {
                            log("write ${feature.address} raw reply ${reply.frame}")
                            h.read(read)
                        }
                    }
                    _state.update { set(it, Section.Loaded(confirmed)) }
                } catch (e: BmapException) {
                    _state.update { set(it, previous) }
                    message(failure)
                    log("write ${feature.address} failed: ${e.message}")
                }
            }
        }
    }

    // Commands that drop the link. Wired for M3; exercised only in H1.

    fun powerOff() = command(Command.PowerOff) { it.powerOff() }

    fun pairingMode() = command(Command.PairingMode) { it.pairingMode() }

    private fun command(command: Command, send: suspend (Headphones) -> Unit) {
        val h = headphones ?: return message("Not connected")
        events.trySend(LinkEvent.CommandSent(command, clock()))
        launchInSession {
            try {
                send(h)
            } catch (e: BmapException) {
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

    private fun started(key: Key) {
        writeStarts[key] = (writeStarts[key] ?: 0) + 1
    }

    private inline fun active(key: Key, block: () -> Unit) {
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

    private fun isConfigured(mode: ModeConfig): Boolean =
        mode.name.isNotBlank() && !mode.name.trim().equals("None", ignoreCase = true)
}
