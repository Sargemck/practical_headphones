package io.github.sargemck.practicalheadphones.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.sargemck.practicalheadphones.R
import io.github.sargemck.practicalheadphones.bmap.EqBand
import io.github.sargemck.practicalheadphones.bmap.QcUltra2
import io.github.sargemck.practicalheadphones.service.NotificationModel
import io.github.sargemck.practicalheadphones.state.HeadphoneState
import io.github.sargemck.practicalheadphones.state.LinkState
import io.github.sargemck.practicalheadphones.state.ModesSection
import io.github.sargemck.practicalheadphones.state.Section
import kotlin.math.roundToInt

private val Pad = Modifier.padding(horizontal = 16.dp)

@Composable
fun HeaderCard(state: HeadphoneState, actions: Actions) {
    SettingsCard(null, "card-header") {
        SectionContent(state.name, "Name", "name", actions.retry) {
            Text(it, style = MaterialTheme.typography.headlineSmall, modifier = Pad.testTag("header-name"))
        }
        Text(linkText(state.link), style = MaterialTheme.typography.bodyLarge, modifier = Pad)
        SectionContent(state.battery, "Battery", "battery", actions.retry) {
            Text("Battery $it%", style = MaterialTheme.typography.bodyLarge, modifier = Pad.testTag("header-battery"))
        }
    }
}

@Composable
fun ModesCard(state: HeadphoneState, actions: Actions) {
    if (state.modes == Section.Unsupported) return
    SettingsCard("Modes", "card-modes") {
        Description("Tap a mode to switch to it. Starred modes are the ones the headphones' button cycles through.", Pad)
        SectionContent(state.modes, "Modes", "modes", actions.retry) { modes -> ModeList(modes, actions) }
    }
}

@Composable
private fun ModeList(modes: ModesSection, actions: Actions) {
    for (mode in modes.modes) {
        val favourite = modes.favourites?.let { mode.index in it.slots }
        RadioRow(
            title = mode.name,
            description = if (mode.index in QcUltra2.presetSlots) "Bose preset, can't be edited." else null,
            selected = mode.index == modes.current,
            tag = "mode-${mode.index}",
            onClick = { actions.selectMode(mode.index) },
            trailing = favourite?.let { on ->
                {
                    IconToggleButton(
                        checked = on,
                        onCheckedChange = { actions.toggleFavourite(mode.index) },
                        modifier = Modifier.padding(end = 4.dp).testTag("favourite-${mode.index}"),
                    ) {
                        Icon(
                            painterResource(if (on) R.drawable.ic_star else R.drawable.ic_star_border),
                            contentDescription = if (on) "${mode.name}, favourite" else "${mode.name}, not a favourite",
                        )
                    }
                }
            },
        )
    }
}

@Composable
fun NoiseCard(state: HeadphoneState, actions: Actions) {
    if (state.audio == Section.Unsupported) return
    SettingsCard("Noise control", "card-noise") {
        SectionContent(state.audio, "Noise control", "audio", actions.retry) { audio ->
            val strength = strengthFromCnc(audio.cnc)
            Description("How much outside sound gets through.", Pad)
            Slider(
                value = strength.toFloat(),
                onValueChange = { v ->
                    val wanted = v.roundToInt()
                    if (wanted != strength) actions.setAudio { it.copy(cnc = cncFromStrength(wanted)) }
                },
                valueRange = 0f..10f,
                steps = 9,
                modifier = Pad.fillMaxWidth().testTag("noise-slider").semantics {
                    contentDescription = "Noise control"
                    stateDescription = noiseDescription(strength)
                },
            )
            Row(Pad.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Description("Hear surroundings", Modifier.weight(1f))
                Description("Block noise")
            }
            SwitchRow("Wind Block", "Cuts wind noise outdoors.", audio.wind, "wind-block") { on ->
                actions.setAudio { it.copy(wind = on) }
            }
            SwitchRow("Noise cancelling", "Turns noise cancelling on or off.", audio.anc, "noise-cancelling") { on ->
                actions.setAudio { it.copy(anc = on) }
            }
            SubHeading("Spatial audio")
            for ((label, spatial, description) in spatialOptions) {
                RadioRow(label, description, audio.spatial == spatial, "spatial-${label.lowercase()}", { actions.setAudio { it.copy(spatial = spatial) } })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SoundCard(state: HeadphoneState, actions: Actions) {
    if (state.eq == Section.Unsupported) return
    SettingsCard("Equaliser", "card-sound") {
        Description("Shapes bass, mids and treble.", Pad)
        SectionContent(state.eq, "Equaliser", "eq", actions.retry) { bands ->
            val preset = EqPreset.match(bands)
            var customOpen by rememberSaveable { mutableStateOf(false) }
            val custom = preset == null || customOpen
            FlowRow(Pad, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (p in EqPreset.entries) {
                    FilterChip(
                        selected = !custom && preset == p,
                        onClick = {
                            customOpen = false
                            actions.setEq(p.bass, p.mid, p.treble)
                        },
                        label = { Text(p.label) },
                        modifier = Modifier.testTag("eq-${p.name.lowercase()}"),
                    )
                }
                FilterChip(selected = custom, onClick = { customOpen = true }, label = { Text("Custom") }, modifier = Modifier.testTag("eq-custom"))
            }
            if (custom) for (band in bands.sortedBy { it.bandId }) EqSlider(band, actions)
        }
    }
}

@Composable
private fun EqSlider(band: EqBand, actions: Actions) {
    val name = when (band.bandId) {
        0 -> "Bass"
        1 -> "Mid"
        2 -> "Treble"
        else -> "Band ${band.bandId}"
    }
    var dragging by remember(band.current) { mutableStateOf<Float?>(null) }
    val shown = dragging?.roundToInt() ?: band.current
    val signed = if (shown > 0) "+$shown" else "$shown"
    Column(Pad) {
        Row(Modifier.fillMaxWidth()) {
            Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(signed, style = MaterialTheme.typography.bodyLarge)
        }
        Slider(
            value = dragging ?: band.current.toFloat(),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                val wanted = dragging?.roundToInt()
                dragging = null
                if (wanted != null && wanted != band.current) actions.setEqBand(band.bandId, wanted)
            },
            valueRange = band.min.toFloat()..band.max.toFloat(),
            steps = (band.max - band.min - 1).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth().testTag("eq-band-${band.bandId}").semantics {
                contentDescription = name
                stateDescription = "$name $signed"
            },
        )
    }
}

@Composable
fun CallsCard(state: HeadphoneState, actions: Actions) {
    if (state.selfVoice == Section.Unsupported && state.autoAnswer == Section.Unsupported) return
    SettingsCard("Calls", "card-calls") {
        SectionContent(state.selfVoice, "Self Voice", "self-voice", actions.retry) { selfVoice ->
            SubHeading("Self Voice")
            Description("How much of your own voice you hear on calls.", Pad)
            for ((label, level) in selfVoiceOptions) {
                RadioRow(label, null, selfVoice.level == level, "self-voice-${label.lowercase()}", { actions.setSelfVoice(level) })
            }
        }
        SectionContent(state.autoAnswer, "Auto answer", "auto-answer", actions.retry) { on ->
            SwitchRow("Auto answer", "Answers incoming calls without a tap.", on, "auto-answer", actions.setAutoAnswer)
        }
    }
}

@Composable
fun DeviceCard(state: HeadphoneState, actions: Actions, onRename: () -> Unit, onPairing: () -> Unit, onPowerOff: () -> Unit) {
    SettingsCard("Headphones", "card-device") {
        SectionContent(state.autoPlayPause, "Auto play/pause", "auto-play-pause", actions.retry) { on ->
            SwitchRow(
                "Auto play/pause",
                "Pauses when you take the headphones off and resumes when you put them on.",
                on,
                "auto-play-pause",
                actions.setAutoPlayPause,
            )
        }
        SectionContent(state.multipoint, "Multipoint", "multipoint", actions.retry) { multipoint ->
            Column(Pad.padding(vertical = 8.dp).testTag("multipoint")) {
                Text("Multipoint: ${if (multipoint.enabled) "On" else "Off"}", style = MaterialTheme.typography.bodyLarge)
                Description("Connected to two devices at once.")
            }
        }
        SectionContent(state.name, "Name", "name-row", actions.retry) { name ->
            ActionRow("Name: $name", "The name other devices see.", "Rename", "rename", onRename)
        }
        ActionRow("Pairing mode", "Lets a new device pair. The headphones disconnect from this phone.", "Start pairing mode", "pairing-mode", onPairing)
        ActionRow("Power off", "Turns the headphones off.", "Power off", "power-off", onPowerOff)
    }
}

@Composable
fun StatusCard(link: LinkState, actions: Actions) {
    val (title, text) = when (link) {
        LinkState.BluetoothOff -> "Bluetooth is off" to "Turn on Bluetooth to use your headphones."
        LinkState.NotPaired -> "Headphones aren't paired" to "Pair your QC Ultra headphones in Bluetooth settings first."
        LinkState.Disconnected -> "Not connected" to "The app connects when your phone connects to the headphones."
        LinkState.Connecting -> "Connecting…" to "Reading the headphones' settings."
        LinkState.Connected -> "Connected" to ""
        LinkState.AnotherApp -> "Another app is using your headphones" to "Only one app can control them at a time. Tap Reconnect to take over."
        LinkState.HeadphonesOff -> "Headphones off" to "The app connects again when they're back on."
        LinkState.PairingMode -> "Pairing mode" to "The headphones are ready to pair with a new device. The app connects again afterwards."
        LinkState.ConnectFailed -> "Couldn't connect" to "The headphones didn't answer."
        LinkState.Lost -> "Connection lost" to "The headphones stopped answering."
    }
    SettingsCard(title, "status-card") {
        Description(text, Pad)
        when (link) {
            LinkState.AnotherApp, LinkState.ConnectFailed, LinkState.Lost ->
                ActionButton("Reconnect", "reconnect", actions.reconnect)
            LinkState.BluetoothOff, LinkState.NotPaired ->
                ActionButton("Open Bluetooth settings", "open-bluetooth", actions.openBluetoothSettings)
            else -> Unit
        }
    }
}

@Composable
fun BoseBanner(actions: Actions) {
    SettingsCard("Bose app installed", "bose-banner") {
        Description("The Bose app can take over the connection. If this app stops responding, force stop the Bose app.", Pad)
        ActionButton("Open Bose app settings", "open-bose-settings", actions.openBoseAppSettings)
    }
}

@Composable
private fun ActionButton(label: String, tag: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.padding(horizontal = 4.dp).testTag(tag)) { Text(label) }
}

fun linkText(link: LinkState): String = NotificationModel.linkText(link)
