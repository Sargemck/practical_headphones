package io.github.sargemck.practicalheadphones.tile

import io.github.sargemck.practicalheadphones.service.NotificationModel
import io.github.sargemck.practicalheadphones.state.HeadphoneState
import io.github.sargemck.practicalheadphones.state.LinkState
import io.github.sargemck.practicalheadphones.state.nextFavourite
import io.github.sargemck.practicalheadphones.state.valueOrNull

/** What the Quick Settings tile shows. Pure, so it's unit tested. [next] is the slot a tap goes to. */
data class TileModel(val status: Status, val label: String, val subtitle: String, val next: Int?) {
    enum class Status { Active, Inactive, Unavailable }

    companion object {
        private const val TITLE = "Headphones"

        /** [state] is null when the service isn't running. */
        fun from(state: HeadphoneState?): TileModel {
            if (state == null) return TileModel(Status.Unavailable, TITLE, NotificationModel.linkText(LinkState.Disconnected), null)
            if (state.link != LinkState.Connected) return TileModel(Status.Unavailable, TITLE, NotificationModel.linkText(state.link), null)
            val modes = state.modes.valueOrNull() ?: return TileModel(Status.Inactive, TITLE, "Loading…", null)
            val label = modes.currentMode?.name?.trim() ?: TITLE
            val next = nextFavourite(modes)?.takeIf { it != modes.current }
            val nextName = next?.let { n -> modes.modes.firstOrNull { it.index == n }?.name?.trim() }
            return if (next != null && nextName != null) {
                TileModel(Status.Active, label, "Next: $nextName", next)
            } else {
                TileModel(Status.Active, label, "Star modes to cycle", null)
            }
        }
    }
}
