package io.github.sargemck.practicalheadphones.widget

import io.github.sargemck.practicalheadphones.service.NotificationModel
import io.github.sargemck.practicalheadphones.state.HeadphoneState
import io.github.sargemck.practicalheadphones.state.LinkState
import io.github.sargemck.practicalheadphones.state.nextFavourite
import io.github.sargemck.practicalheadphones.state.valueOrNull

enum class WidgetAction(val label: String) {
    NextMode("Next"),
    AncOn("ANC on"),
    AncOff("ANC off"),
    Reconnect("Reconnect"),
}

/**
 * Pure presentation model for the Material You home screen widget, unit-tested without Android Views.
 */
data class WidgetModel(
    val title: String,
    val subtitle: String,
    val primaryAction: WidgetAction?,
    val secondaryAction: WidgetAction?,
) {
    companion object {
        fun from(state: HeadphoneState?): WidgetModel {
            if (state == null) {
                return WidgetModel(
                    title = "Headphones",
                    subtitle = "Tap to open",
                    primaryAction = null,
                    secondaryAction = null,
                )
            }
            val title = state.name.valueOrNull()
                ?: state.lastKnown?.name?.valueOrNull()
                ?: "Headphones"
            if (state.link != LinkState.Connected) {
                val canReconnect = state.link in setOf(
                    LinkState.AnotherApp,
                    LinkState.ConnectFailed,
                    LinkState.Lost,
                )
                return WidgetModel(
                    title = title,
                    subtitle = NotificationModel.linkText(state.link),
                    primaryAction = if (canReconnect) WidgetAction.Reconnect else null,
                    secondaryAction = null,
                )
            }

            val modes = state.modes.valueOrNull()
            val audio = state.audio.valueOrNull()
            val discreteAnc = state.discreteAnc.valueOrNull()

            val batteryStr = if (state.batteryParts.isNotEmpty()) {
                state.batteryParts.joinToString(" ") { "${it.label}:${it.percent}%" }
            } else {
                state.battery.valueOrNull()?.let { "$it%" }
            }
            val modeLabel = modes?.currentMode?.name
                ?: discreteAnc?.let { anc -> anc.options.firstOrNull { it.id == anc.selectedId }?.label }
            val subtitle = listOfNotNull(batteryStr, modeLabel).joinToString(" · ").ifEmpty { "Connected" }

            val canCycle = (modes != null && nextFavourite(modes) != null) ||
                (modes == null && discreteAnc != null && discreteAnc.options.size >= 2)
            val ancAction = when {
                audio != null -> if (audio.anc) WidgetAction.AncOff else WidgetAction.AncOn
                discreteAnc != null && discreteAnc.options.any { it.id.equals("OFF", ignoreCase = true) } -> {
                    if (discreteAnc.selectedId.equals("OFF", ignoreCase = true)) WidgetAction.AncOn else WidgetAction.AncOff
                }
                else -> null
            }

            return WidgetModel(
                title = title,
                subtitle = subtitle,
                primaryAction = if (canCycle) WidgetAction.NextMode else null,
                secondaryAction = ancAction,
            )
        }
    }
}
