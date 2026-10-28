package io.github.sargemck.practicalheadphones.state

import io.github.sargemck.practicalheadphones.bmap.CloseReason

/** What Android reports about Bluetooth and the headphones. */
data class SystemLink(val bluetoothOn: Boolean, val paired: Boolean, val connected: Boolean) {
    companion object {
        val UNKNOWN = SystemLink(bluetoothOn = false, paired = false, connected = false)
    }
}

enum class Command { PowerOff, PairingMode }

sealed interface LinkEvent {
    data class System(val link: SystemLink) : LinkEvent

    data object ConnectSucceeded : LinkEvent

    data object ConnectFailed : LinkEvent

    data class SocketClosed(val reason: CloseReason, val atMs: Long) : LinkEvent

    data class GraceExpired(val dropId: Int) : LinkEvent

    data object UserReconnect : LinkEvent

    data class CommandSent(val command: Command, val atMs: Long) : LinkEvent
}

sealed interface LinkAction {
    data object Connect : LinkAction

    data object CloseSocket : LinkAction

    /** Send [LinkEvent.GraceExpired] with [dropId] after [LinkPolicy.GRACE_MS]. */
    data class StartGrace(val dropId: Int) : LinkAction
}

data class PolicyState(
    val link: LinkState = LinkState.Disconnected,
    val system: SystemLink = SystemLink.UNKNOWN,
    /** Set while waiting out the grace period after a remote close. */
    val pendingDrop: PendingDrop? = null,
    val lastCommand: Command? = null,
    val lastCommandAtMs: Long = 0,
    val dropCounter: Int = 0,
)

data class PendingDrop(val id: Int, val atMs: Long)

/**
 * The connect and contention rules (CLAUDE.md Android rules), as a pure reducer.
 *
 * - Connect only while the system reports the headphones connected: on the first report, on a
 *   fresh connected event, or on the user's Reconnect. Never retry on its own.
 * - A remote close while the system still reports connected waits [GRACE_MS] for a system
 *   disconnect (sleep, Bluetooth off). If none comes: within [COMMAND_WINDOW_MS] of our power off or
 *   pairing mode, that's expected; otherwise another app took the link. No reconnect either way.
 * - An unresponsive or broken link is [LinkState.Lost], no reconnect.
 */
object LinkPolicy {
    const val GRACE_MS = 2_000L
    const val COMMAND_WINDOW_MS = 5_000L

    private val active = setOf(LinkState.Connecting, LinkState.Connected)

    fun reduce(state: PolicyState, event: LinkEvent): Pair<PolicyState, List<LinkAction>> = when (event) {
        is LinkEvent.System -> system(state, event.link)
        LinkEvent.ConnectSucceeded ->
            if (state.link == LinkState.Connecting) state.copy(link = LinkState.Connected) to emptyList() else state to emptyList()
        LinkEvent.ConnectFailed ->
            if (state.link == LinkState.Connecting) state.copy(link = LinkState.ConnectFailed) to emptyList() else state to emptyList()
        is LinkEvent.SocketClosed -> socketClosed(state, event)
        is LinkEvent.GraceExpired -> graceExpired(state, event.dropId)
        LinkEvent.UserReconnect ->
            if (state.system.connected && state.link !in active && state.pendingDrop == null) {
                state.copy(link = LinkState.Connecting) to listOf(LinkAction.Connect)
            } else {
                state to emptyList()
            }
        is LinkEvent.CommandSent -> state.copy(lastCommand = event.command, lastCommandAtMs = event.atMs) to emptyList()
    }

    private fun system(state: PolicyState, link: SystemLink): Pair<PolicyState, List<LinkAction>> {
        val wasConnected = state.system.connected
        val base = state.copy(system = link)
        val close = if (state.link in active) listOf(LinkAction.CloseSocket) else emptyList()
        return when {
            !link.bluetoothOn -> base.copy(link = LinkState.BluetoothOff, pendingDrop = null) to close
            !link.paired -> base.copy(link = LinkState.NotPaired, pendingDrop = null) to close
            !link.connected -> base.copy(link = LinkState.Disconnected, pendingDrop = null) to close
            !wasConnected -> base.copy(link = LinkState.Connecting, pendingDrop = null) to listOf(LinkAction.Connect)
            else -> base to emptyList()
        }
    }

    private fun socketClosed(state: PolicyState, event: LinkEvent.SocketClosed): Pair<PolicyState, List<LinkAction>> {
        if (state.link !in active) return state to emptyList()
        return when (event.reason) {
            CloseReason.Local -> state to emptyList()
            CloseReason.RemoteClosed ->
                if (state.system.connected) {
                    val id = state.dropCounter + 1
                    state.copy(dropCounter = id, pendingDrop = PendingDrop(id, event.atMs)) to listOf(LinkAction.StartGrace(id))
                } else {
                    state.copy(link = LinkState.Disconnected) to emptyList()
                }
            CloseReason.Unresponsive, is CloseReason.IoError ->
                state.copy(link = if (state.system.connected) LinkState.Lost else LinkState.Disconnected) to emptyList()
        }
    }

    private fun graceExpired(state: PolicyState, dropId: Int): Pair<PolicyState, List<LinkAction>> {
        val drop = state.pendingDrop
        if (drop == null || drop.id != dropId) return state to emptyList()
        val sinceCommand = drop.atMs - state.lastCommandAtMs
        val link = when {
            state.lastCommand != null && sinceCommand in 0..COMMAND_WINDOW_MS -> when (state.lastCommand) {
                Command.PowerOff -> LinkState.HeadphonesOff
                Command.PairingMode -> LinkState.PairingMode
            }
            else -> LinkState.AnotherApp
        }
        return state.copy(link = link, pendingDrop = null) to emptyList()
    }
}
