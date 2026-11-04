package io.github.sargemck.practicalheadphones.service

import io.github.sargemck.practicalheadphones.state.LinkState

/** When [HeadphoneService] stops itself (PLAN M6). Pure, so it's unit tested. */
object ServiceLifetime {
    /** How long the stop condition must hold first, so a reconnect in progress isn't cut off. */
    const val STOP_AFTER_MS = 5_000L

    /** The headphones aren't there: nothing to talk to until the next presence event. */
    private val AWAY = setOf(LinkState.Disconnected, LinkState.BluetoothOff, LinkState.NotPaired, LinkState.HeadphonesOff, LinkState.PairingMode)

    /**
     * Stop when the headphones are away and no screen of ours is visible, but only if auto-connect
     * is set up: otherwise nothing would start the service again when they come back. AnotherApp,
     * Lost and ConnectFailed keep it: the headphones are still there and the user may reconnect.
     */
    fun shouldStop(link: LinkState, uiVisible: Boolean, autoConnect: Boolean): Boolean =
        autoConnect && !uiVisible && link in AWAY
}
