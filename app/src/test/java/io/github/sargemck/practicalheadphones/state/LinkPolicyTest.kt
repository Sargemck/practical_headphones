package io.github.sargemck.practicalheadphones.state

import io.github.sargemck.practicalheadphones.bmap.CloseReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LinkPolicyTest {
    private val on = SystemLink(bluetoothOn = true, paired = true, connected = true)
    private val away = on.copy(connected = false)

    private fun run(vararg events: LinkEvent, from: PolicyState = PolicyState()): Pair<PolicyState, List<LinkAction>> {
        var state = from
        val actions = mutableListOf<LinkAction>()
        for (e in events) {
            val (next, a) = LinkPolicy.reduce(state, e)
            state = next
            actions += a
        }
        return state to actions
    }

    private val connected = run(LinkEvent.System(on), LinkEvent.ConnectSucceeded).first

    @Test
    fun `first report while connected connects`() {
        val (state, actions) = run(LinkEvent.System(on))
        assertEquals(LinkState.Connecting, state.link)
        assertEquals(listOf(LinkAction.Connect), actions)
        assertEquals(LinkState.Connected, LinkPolicy.reduce(state, LinkEvent.ConnectSucceeded).first.link)
    }

    @Test
    fun `no connect without the system reporting connected`() {
        assertEquals(LinkState.Disconnected to emptyList<LinkAction>(), run(LinkEvent.System(away)).let { it.first.link to it.second })
        assertEquals(LinkState.BluetoothOff, run(LinkEvent.System(SystemLink.UNKNOWN)).first.link)
        assertEquals(LinkState.NotPaired, run(LinkEvent.System(on.copy(paired = false))).first.link)
        assertEquals(emptyList<LinkAction>(), run(LinkEvent.System(away), LinkEvent.UserReconnect).second)
    }

    @Test
    fun `a repeated connected report doesn't connect again`() {
        assertEquals(emptyList<LinkAction>(), LinkPolicy.reduce(connected, LinkEvent.System(on)).second)
    }

    @Test
    fun `fresh connected event after a disconnect connects once`() {
        val (state, actions) = run(LinkEvent.System(away), LinkEvent.System(on), LinkEvent.System(on), from = connected)
        assertEquals(LinkState.Connecting, state.link)
        assertEquals(listOf(LinkAction.CloseSocket, LinkAction.Connect), actions)
    }

    @Test
    fun `remote close then a system disconnect within the grace is a quiet disconnect`() {
        val (state, actions) = run(
            LinkEvent.SocketClosed(CloseReason.RemoteClosed, 1_000),
            LinkEvent.System(away),
            LinkEvent.GraceExpired(1),
            from = connected,
        )
        assertEquals(LinkState.Disconnected, state.link)
        assertEquals(LinkAction.StartGrace(1), actions.first())
        assertEquals(listOf(LinkAction.StartGrace(1), LinkAction.CloseSocket), actions)
    }

    @Test
    fun `remote close with the system still connected is another app, and nothing reconnects`() {
        val (state, actions) = run(
            LinkEvent.SocketClosed(CloseReason.RemoteClosed, 1_000),
            LinkEvent.GraceExpired(1),
            LinkEvent.System(on),
            from = connected,
        )
        assertEquals(LinkState.AnotherApp, state.link)
        assertEquals(listOf<LinkAction>(LinkAction.StartGrace(1)), actions)
    }

    @Test
    fun `remote close within 5 s of power off or pairing mode is expected`() {
        val off = run(
            LinkEvent.CommandSent(Command.PowerOff, 10_000),
            LinkEvent.SocketClosed(CloseReason.RemoteClosed, 14_000),
            LinkEvent.GraceExpired(1),
            from = connected,
        ).first
        assertEquals(LinkState.HeadphonesOff, off.link)
        val pairing = run(
            LinkEvent.CommandSent(Command.PairingMode, 10_000),
            LinkEvent.SocketClosed(CloseReason.RemoteClosed, 10_300),
            LinkEvent.GraceExpired(1),
            from = connected,
        ).first
        assertEquals(LinkState.PairingMode, pairing.link)
        val late = run(
            LinkEvent.CommandSent(Command.PowerOff, 10_000),
            LinkEvent.SocketClosed(CloseReason.RemoteClosed, 15_001),
            LinkEvent.GraceExpired(1),
            from = connected,
        ).first
        assertEquals(LinkState.AnotherApp, late.link)
    }

    @Test
    fun `a stale grace timer is ignored`() {
        val state = run(LinkEvent.SocketClosed(CloseReason.RemoteClosed, 1_000), from = connected).first
        assertEquals(state, LinkPolicy.reduce(state, LinkEvent.GraceExpired(7)).first)
    }

    @Test
    fun `unresponsive or broken link is lost, with no reconnect`() {
        val (lost, actions) = run(LinkEvent.SocketClosed(CloseReason.Unresponsive, 1), from = connected)
        assertEquals(LinkState.Lost, lost.link)
        assertEquals(emptyList<LinkAction>(), actions)
        assertEquals(LinkState.Lost, run(LinkEvent.SocketClosed(CloseReason.IoError("x"), 1), from = connected).first.link)
    }

    @Test
    fun `local close is ignored`() {
        assertEquals(LinkState.Connected, run(LinkEvent.SocketClosed(CloseReason.Local, 1), from = connected).first.link)
    }

    @Test
    fun `reconnect only from a stopped state with the system connected`() {
        for (stuck in listOf(LinkState.AnotherApp, LinkState.Lost, LinkState.ConnectFailed, LinkState.HeadphonesOff)) {
            val (state, actions) = run(LinkEvent.UserReconnect, from = connected.copy(link = stuck))
            assertEquals(LinkState.Connecting, state.link, "$stuck")
            assertEquals(listOf(LinkAction.Connect), actions)
        }
        assertEquals(emptyList<LinkAction>(), run(LinkEvent.UserReconnect, from = connected).second)
    }

    @Test
    fun `connect failure stops`() {
        val (state, actions) = run(LinkEvent.System(on), LinkEvent.ConnectFailed)
        assertEquals(LinkState.ConnectFailed, state.link)
        assertEquals(listOf(LinkAction.Connect), actions)
    }

    @Test
    fun `bluetooth off while connected closes the socket`() {
        val (state, actions) = run(LinkEvent.System(SystemLink.UNKNOWN), from = connected)
        assertEquals(LinkState.BluetoothOff, state.link)
        assertEquals(listOf(LinkAction.CloseSocket), actions)
    }
}
