package io.github.sargemck.practicalheadphones.service

import io.github.sargemck.practicalheadphones.state.LinkState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ServiceLifetimeTest {
    @Test
    fun `stops only when away, unseen and auto-connect is set up`() {
        val away = setOf(LinkState.Disconnected, LinkState.BluetoothOff, LinkState.NotPaired, LinkState.HeadphonesOff, LinkState.PairingMode)
        for (link in LinkState.entries) {
            assertEquals(link in away, ServiceLifetime.shouldStop(link, uiVisible = false, autoConnect = true), link.name)
            assertEquals(false, ServiceLifetime.shouldStop(link, uiVisible = true, autoConnect = true), "$link visible")
            assertEquals(false, ServiceLifetime.shouldStop(link, uiVisible = false, autoConnect = false), "$link no auto-connect")
        }
    }

    @Test
    fun `another app holding the link keeps the service, so Reconnect still works`() {
        for (link in listOf(LinkState.AnotherApp, LinkState.Lost, LinkState.ConnectFailed, LinkState.Connecting, LinkState.Connected)) {
            assertEquals(false, ServiceLifetime.shouldStop(link, uiVisible = false, autoConnect = true), link.name)
        }
    }
}
