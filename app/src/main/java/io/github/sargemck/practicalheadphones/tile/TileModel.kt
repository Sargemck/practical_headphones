package io.github.sargemck.practicalheadphones.tile

import io.github.sargemck.practicalheadphones.service.NotificationModel
import io.github.sargemck.practicalheadphones.state.HeadphoneState
import io.github.sargemck.practicalheadphones.state.LinkState
import io.github.sargemck.practicalheadphones.state.Section
import io.github.sargemck.practicalheadphones.state.nextFavourite
import io.github.sargemck.practicalheadphones.state.valueOrNull

/** What the Quick Settings tile shows. Pure, so it's unit tested. [next] is the slot/index a tap goes to. */
data class TileModel(val status: Status, val label: String, val subtitle: String, val next: Int?) {
    enum class Status { Active, Inactive, Unavailable }

    companion object {
        private const val TITLE = "Headphones"

        /** [state] is null when the service isn't running. */
        fun from(state: HeadphoneState?): TileModel {
            if (state == null) return TileModel(Status.Unavailable, TITLE, NotificationModel.linkText(LinkState.Disconnected), null)
            if (state.link != LinkState.Connected) return TileModel(Status.Unavailable, TITLE, NotificationModel.linkText(state.link), null)
            if (state.modes is Section.Unsupported) {
                val anc = state.discreteAnc.valueOrNull()
                if (anc != null && anc.options.isNotEmpty()) {
                    val idx = anc.options.indexOfFirst { it.id == anc.selectedId }.coerceAtLeast(0)
                    val currentLabel = anc.options[idx].label
                    return if (anc.options.size >= 2) {
                        val nextIdx = (idx + 1) % anc.options.size
                        TileModel(Status.Active, currentLabel, "Next: ${anc.options[nextIdx].label}", nextIdx)
                    } else {
                        TileModel(Status.Active, currentLabel, "Connected", null)
                    }
                }
                return if (state.discreteAnc is Section.Loading) {
                    TileModel(Status.Inactive, TITLE, "Loading…", null)
                } else {
                    TileModel(Status.Unavailable, TITLE, "No modes", null)
                }
            }
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
