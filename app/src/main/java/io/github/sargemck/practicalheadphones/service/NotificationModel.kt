package io.github.sargemck.practicalheadphones.service

import io.github.sargemck.practicalheadphones.state.HeadphoneState
import io.github.sargemck.practicalheadphones.state.LinkState
import io.github.sargemck.practicalheadphones.state.nextFavourite
import io.github.sargemck.practicalheadphones.state.valueOrNull

enum class NotificationAction { NextMode, NoiseCancellingOn, NoiseCancellingOff }

/** What the ongoing notification shows. Pure, so it's unit tested. */
data class NotificationModel(val title: String, val text: String, val actions: List<NotificationAction>) {
    companion object {
        fun from(state: HeadphoneState): NotificationModel {
            val title = state.name.valueOrNull() ?: "Headphones"
            if (state.link != LinkState.Connected) return NotificationModel(title, linkText(state.link), emptyList())
            val modes = state.modes.valueOrNull()
            val audio = state.audio.valueOrNull()
            val text = listOfNotNull(
                state.battery.valueOrNull()?.let { "Battery $it%" },
                modes?.currentMode?.name,
            ).joinToString(" · ").ifEmpty { "Loading…" }
            val actions = buildList {
                if (modes != null && nextFavourite(modes) != null) add(NotificationAction.NextMode)
                if (audio != null) add(if (audio.anc) NotificationAction.NoiseCancellingOff else NotificationAction.NoiseCancellingOn)
            }
            return NotificationModel(title, text, actions)
        }

        fun linkText(link: LinkState): String = when (link) {
            LinkState.BluetoothOff -> "Bluetooth is off"
            LinkState.NotPaired -> "Headphones aren't paired"
            LinkState.Disconnected -> "Not connected"
            LinkState.Connecting -> "Connecting…"
            LinkState.Connected -> "Connected"
            LinkState.AnotherApp -> "Another app is using your headphones"
            LinkState.HeadphonesOff -> "Headphones off"
            LinkState.PairingMode -> "Pairing mode"
            LinkState.ConnectFailed -> "Couldn't connect"
            LinkState.Lost -> "Connection lost"
        }

        fun label(action: NotificationAction): String = when (action) {
            NotificationAction.NextMode -> "Next mode"
            NotificationAction.NoiseCancellingOn -> "Noise cancelling on"
            NotificationAction.NoiseCancellingOff -> "Noise cancelling off"
        }
    }
}
