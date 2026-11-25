package io.github.sargemck.practicalheadphones.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import io.github.sargemck.practicalheadphones.R
import io.github.sargemck.practicalheadphones.bmap.EqBand
import io.github.sargemck.practicalheadphones.bmap.ModeConfig
import io.github.sargemck.practicalheadphones.bmap.Profiles
import io.github.sargemck.practicalheadphones.bmap.QcUltra2
import io.github.sargemck.practicalheadphones.state.HeadphoneState
import io.github.sargemck.practicalheadphones.state.LinkState
import io.github.sargemck.practicalheadphones.state.ModesSection
import io.github.sargemck.practicalheadphones.state.Section
import io.github.sargemck.practicalheadphones.state.valueOrNull
import kotlin.math.roundToInt

private val Pad = Modifier.padding(horizontal = 16.dp)

/** What the hero card says for a link state, and the one thing the user can do about it. */
data class LinkStatus(val title: String, val text: String, val action: Action?) {
    enum class Action(val label: String, val tag: String) {
        Reconnect("Reconnect", "reconnect"),
        OpenBluetooth("Open Bluetooth settings", "open-bluetooth"),
        AllowBluetooth("Allow", "allow-bluetooth"),
        OpenAppSettings("Open settings", "open-app-settings"),
    }
}

fun linkStatus(link: LinkState): LinkStatus = when (link) {
    LinkState.BluetoothOff -> LinkStatus("Bluetooth is off", "Turn on Bluetooth to use your headphones.", LinkStatus.Action.OpenBluetooth)
    LinkState.NotPaired -> LinkStatus("Not paired", "Pair your QC Ultra headphones with this phone in Bluetooth settings.", LinkStatus.Action.OpenBluetooth)
    LinkState.Disconnected -> LinkStatus("Not connected", "Turn on your headphones. This app connects as soon as your phone does.", LinkStatus.Action.OpenBluetooth)
    LinkState.Connecting -> LinkStatus("Connecting…", "Reading your headphones' settings.", null)
    LinkState.Connected -> LinkStatus("Connected", "", null)
    LinkState.AnotherApp -> LinkStatus("In use by another app", "Only one app can control the headphones at a time. Reconnect to take over.", LinkStatus.Action.Reconnect)
    LinkState.HeadphonesOff -> LinkStatus("Turned off", "This app connects again when you turn them on.", null)
    LinkState.PairingMode -> LinkStatus("Pairing mode", "Ready to pair with a new device. This app connects again afterwards.", null)
    LinkState.ConnectFailed -> LinkStatus("Couldn't connect", "The headphones didn't answer. Check they're on and nearby.", LinkStatus.Action.Reconnect)
    LinkState.Lost -> LinkStatus("Connection lost", "The headphones stopped answering.", LinkStatus.Action.Reconnect)
}

/** Always at the top: which headphones, whether they're connected, battery, and what to do if not. */
@Composable
fun HeroCard(
    state: HeadphoneState,
    actions: Actions,
    bluetooth: Permission = Permission.Granted,
    onSwitchDevice: () -> Unit = {},
) {
    val live = state.link == LinkState.Connected
    val status = permissionStatus(bluetooth) ?: linkStatus(state.link)
    val name = state.name.valueOrNull() ?: state.lastKnown?.name?.valueOrNull() ?: "QC Ultra headphones"
    val mode = state.modes.valueOrNull()?.currentMode?.name
        ?: state.discreteAnc.valueOrNull()?.let { anc -> anc.options.firstOrNull { it.id == anc.selectedId }?.label }
    val colors = if (live) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors()
    Card(Modifier.fillMaxWidth().testTag("hero"), colors = colors) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(
                    shape = CircleShape,
                    color = if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(56.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painterResource(R.drawable.ic_headphones),
                            contentDescription = null,
                            tint = if (live) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("header-name"))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            if (live && mode != null) "${status.title} · $mode" else status.title,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f, fill = false).testTag("hero-status"),
                        )
                        if (live) Battery(state.battery)
                    }
                    if (live && state.batteryParts.isNotEmpty()) {
                        Text(
                            state.batteryParts.joinToString(" · ") { "${it.label} ${it.percent}%" },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("header-battery-parts"),
                        )
                    }
                    val badge = state.deviceBadge
                    if (live && badge != null && badge.experimental) {
                        Text(
                            "Experimental support · ${badge.brand} ${badge.model}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("experimental-badge"),
                        )
                    }
                }
            }
            if (!live) {
                Text(status.text, style = MaterialTheme.typography.bodyMedium)
                status.action?.let { action ->
                    val onClick = when (action) {
                        LinkStatus.Action.Reconnect -> actions.reconnect
                        LinkStatus.Action.OpenBluetooth -> actions.openBluetoothSettings
                        LinkStatus.Action.AllowBluetooth -> actions.requestBluetooth
                        LinkStatus.Action.OpenAppSettings -> actions.openAppSettings
                    }
                    FilledTonalButton(onClick = onClick, modifier = Modifier.testTag(action.tag)) { Text(action.label) }
                }
            }
            if (state.availableDevices.size > 1) {
                FilledTonalButton(onClick = onSwitchDevice, modifier = Modifier.testTag("switch-headphones")) {
                    Text("Switch headphones")
                }
            }
            if (state.link == LinkState.Connecting) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Battery(battery: Section<Int>) {
    when (battery) {
        is Section.Loaded -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.testTag("header-battery").clearAndSetSemantics { contentDescription = "Battery ${battery.value}%" },
        ) {
            Icon(painterResource(R.drawable.ic_battery), contentDescription = null, modifier = Modifier.size(16.dp))
            Text("${battery.value}%", style = MaterialTheme.typography.labelLarge)
        }
        Section.Loading -> CircularProgressIndicator(Modifier.size(16.dp).testTag("battery-loading"), strokeWidth = 2.dp)
        else -> Unit
    }
}

/** Shown under the hero while disconnected, above the dimmed last known settings. */
@Composable
fun OfflineNote(hasLastKnown: Boolean) {
    Description(
        if (hasLastKnown) {
            "Last settings read from your headphones. Connect them to make changes."
        } else {
            "Once they connect, you can change:"
        },
        Modifier.padding(horizontal = 16.dp).testTag("offline-note"),
    )
}

/** First run, nothing read yet: what each section will hold, so the screen isn't empty. */
val previewSections = listOf(
    "Modes" to "Switch modes and choose which ones the headphones' button cycles through.",
    "Noise control" to "How much outside sound gets through, Wind Block and spatial audio.",
    "Equaliser" to "Presets, or set bass, mid and treble yourself.",
    "Calls" to "Self Voice and auto answer.",
    "Headphones" to "Auto play/pause, name, pairing mode and power off.",
)

@Composable
fun PreviewCard(title: String, text: String) {
    SettingsCard(title, "preview-${title.lowercase().replace(' ', '-')}") { Description(text, Pad) }
}

/** How many modes the card shows. The rest are one tap away in the All modes sheet. */
const val QUICK_MODES = 4

/**
 * The modes on the card: the current one, then starred ones, then the rest, in the headphones'
 * order, at most [max].
 */
fun quickModes(section: ModesSection, max: Int = QUICK_MODES): List<ModeConfig> {
    val starred = section.favourites?.slots.orEmpty()
    val ranked = section.modes.sortedBy {
        when {
            it.index == section.current -> 0
            it.index in starred -> 1
            else -> 2
        }
    }
    return ranked.take(max).sortedBy { it.index }
}

/** [edit] opens the mode editor: a slot to edit, or null for a new mode. */
@Composable
fun ModesCard(state: HeadphoneState, actions: Actions, edit: (Int?) -> Unit = {}) {
    if (state.modes == Section.Unsupported) return
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    SettingsCard("Modes", "card-modes") {
        SectionContent(state.modes, "Modes", "modes", actions.retry) { modes ->
            ModeTiles(quickModes(modes), modes.current, actions)
            NavRow(null, "All modes", null, "all-modes") { sheetOpen = true }
            if (sheetOpen) {
                AllModesSheet(modes, state.capabilities, actions, close = { sheetOpen = false }) { slot ->
                    sheetOpen = false
                    edit(slot)
                }
            }
        }
    }
}

/** Tiles two per row (one at large font sizes, so names don't break mid-word). */
@Composable
private fun ModeTiles(modes: List<ModeConfig>, current: Int, actions: Actions) {
    val columns = if (LocalDensity.current.fontScale > 1.5f) 1 else 2
    Column(Pad.padding(top = 4.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in modes.chunked(columns)) {
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (mode in row) ModeTile(mode, mode.index == current, actions, Modifier.weight(1f).fillMaxHeight())
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ModeTile(mode: ModeConfig, selected: Boolean, actions: Actions, modifier: Modifier) {
    val live = LocalLive.current
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .selectable(selected = selected, enabled = live, role = Role.RadioButton, onClick = { actions.selectMode(mode.index) })
                .padding(horizontal = 12.dp, vertical = 16.dp)
                .testTag("mode-${mode.index}"),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(mode.name, style = MaterialTheme.typography.titleSmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
private fun AllModesSheet(
    modes: ModesSection,
    capabilities: io.github.sargemck.practicalheadphones.state.Capabilities,
    actions: Actions,
    close: () -> Unit,
    edit: (Int?) -> Unit,
) {
    // Fully open: the list is long and New mode sits at the bottom.
    ModalBottomSheet(
        onDismissRequest = close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("all-modes-sheet"),
    ) {
        // Its own window, so it needs its own testTagsAsResourceId for adb to see the tags.
        Column(Modifier.semantics { testTagsAsResourceId = true }.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Text("All modes", style = MaterialTheme.typography.titleLarge, modifier = Pad)
            Description("Starred modes are the ones the headphones' button cycles through. Bose presets can't be edited.", Pad.padding(top = 4.dp, bottom = 8.dp))
            for (mode in modes.modes) {
                val favourite = modes.favourites?.let { mode.index in it.slots }
                val custom = capabilities.canEditModes && mode.index !in capabilities.presetSlots
                RadioRow(
                    title = mode.name,
                    description = null,
                    selected = mode.index == modes.current,
                    tag = "sheet-mode-${mode.index}",
                    onClick = {
                        actions.selectMode(mode.index)
                        close()
                    },
                    badge = if (custom) null else "Bose preset",
                    trailing = {
                        Row(Modifier.padding(end = 4.dp)) {
                            if (custom) {
                                IconButton(onClick = { edit(mode.index) }, modifier = Modifier.testTag("edit-mode-${mode.index}")) {
                                    Icon(painterResource(R.drawable.ic_edit), contentDescription = "Edit ${mode.name}")
                                }
                            }
                            if (favourite != null) {
                                IconToggleButton(
                                    checked = favourite,
                                    onCheckedChange = { actions.toggleFavourite(mode.index) },
                                    modifier = Modifier.testTag("favourite-${mode.index}"),
                                ) {
                                    Icon(
                                        painterResource(if (favourite) R.drawable.ic_star else R.drawable.ic_star_border),
                                        contentDescription = if (favourite) "${mode.name}, favourite" else "${mode.name}, not a favourite",
                                    )
                                }
                            }
                        }
                    },
                )
            }
            if (capabilities.canEditModes) {
                if (Profiles.findFreeSlot(modes.modes, capabilities.editableSlots) != null) {
                    NavRow(R.drawable.ic_add, "New mode", null, "new-mode") { edit(null) }
                } else {
                    Description(
                        "All ${capabilities.editableSlots.count()} custom modes are in use. Delete one to add another.",
                        Pad.padding(top = 8.dp).testTag("modes-full"),
                    )
                }
            }
            if (LocalPhoneFeatures.current.canAddTile) {
                NavRow(R.drawable.ic_add, "Add to Quick Settings", "Switch to the next starred mode from Quick Settings.", "add-tile", actions.addTile)
            }
        }
    }
}

@Composable
fun NoiseCard(state: HeadphoneState, actions: Actions) {
    if (state.audio == Section.Unsupported) return
    SettingsCard("Noise control", "card-noise") {
        SectionContent(state.audio, "Noise control", "audio", actions.retry) { audio ->
            NoiseSlider(audio.cnc, "noise-slider") { cnc -> actions.setAudio { it.copy(cnc = cnc) } }
            SwitchRow("Wind Block", "Cuts wind noise outdoors.", audio.wind, "wind-block") { on ->
                actions.setAudio { it.copy(wind = on) }
            }
            SwitchRow("Noise cancelling", null, audio.anc, "noise-cancelling") { on ->
                actions.setAudio { it.copy(anc = on) }
            }
            SubHeading("Spatial audio")
            ChoiceChips(spatialOptions.map { it.first to it.second }, audio.spatial, "spatial") { spatial ->
                actions.setAudio { it.copy(spatial = spatial) }
            }
            spatialOptions.first { it.second == audio.spatial }.third?.let { Description(it, Pad) }
        }
    }
}

/** Strength 0..10 shown left to right, sent as the inverted [cnc]. Calls [onChange] only on a new step. */
@Composable
fun NoiseSlider(cnc: Int, tag: String, onChange: (cnc: Int) -> Unit) {
    val strength = strengthFromCnc(cnc)
    val live = LocalLive.current
    Slider(
        value = strength.toFloat(),
        onValueChange = { v ->
            val wanted = v.roundToInt()
            if (live && wanted != strength) onChange(cncFromStrength(wanted))
        },
        valueRange = 0f..10f,
        steps = 9,
        modifier = Pad.fillMaxWidth().offline(live).testTag(tag).semantics {
            contentDescription = "Noise control"
            stateDescription = noiseDescription(strength)
        },
    )
    Row(Pad.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Description("Hear surroundings", Modifier.weight(1f))
        Description("Block noise")
    }
}

@Composable
fun DiscreteNoiseCard(state: HeadphoneState, actions: Actions) {
    if (state.discreteAnc == Section.Unsupported) return
    SettingsCard("Noise control", "card-discrete-noise") {
        SectionContent(state.discreteAnc, "Noise control", "discrete-anc", actions.retry) { anc ->
            ChoiceChips(
                anc.options.map { it.label to it.id },
                anc.selectedId,
                "anc",
                actions.setDiscreteAnc,
            )
            anc.options.firstOrNull { it.id == anc.selectedId }?.description?.let { Description(it, Pad) }
            val ambient = anc.ambientLevel
            if (ambient != null) {
                val live = LocalLive.current
                var dragging by remember(ambient) { mutableStateOf<Float?>(null) }
                val shown = dragging?.roundToInt() ?: ambient
                Column(Pad) {
                    Row(Modifier.fillMaxWidth()) {
                        Text("Ambient sound", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text("$shown / ${anc.ambientMax}", style = MaterialTheme.typography.bodyLarge)
                    }
                    Slider(
                        value = dragging ?: ambient.toFloat(),
                        onValueChange = { if (live) dragging = it },
                        onValueChangeFinished = {
                            val wanted = dragging?.roundToInt()
                            dragging = null
                            if (wanted != null && wanted != ambient) actions.setAmbientLevel(wanted)
                        },
                        valueRange = 0f..anc.ambientMax.toFloat(),
                        steps = (anc.ambientMax - 1).coerceAtLeast(0),
                        modifier = Modifier.fillMaxWidth().offline(live).testTag("ambient-slider").semantics {
                            contentDescription = "Ambient sound"
                            stateDescription = "$shown of ${anc.ambientMax}"
                        },
                    )
                }
            }
            anc.focusOnVoice?.let { focus ->
                SwitchRow("Focus on voice", "Lets voices through while blocking background noise.", focus, "focus-on-voice", actions.setFocusOnVoice)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SoundCard(state: HeadphoneState, actions: Actions) {
    if (state.eq == Section.Unsupported) return
    SettingsCard("Equaliser", "card-sound") {
        SectionContent(state.eq, "Equaliser", "eq", actions.retry) { bands ->
            val live = LocalLive.current
            val customPresets = state.eqPresets
            if (customPresets != null) {
                val sorted = bands.sortedBy { it.bandId }
                val values = sorted.map { it.current }
                val matched = state.selectedEqPreset?.let { id -> customPresets.firstOrNull { it.id == id } }
                    ?: customPresets.firstOrNull { it.values.isNotEmpty() && it.values == values }
                var customOpen by rememberSaveable { mutableStateOf(false) }
                val showSliders = sorted.isNotEmpty() && (matched == null || customOpen)
                FlowRow(Pad, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (p in customPresets) {
                        val selected = !customOpen && matched?.id == p.id
                        FilterChip(
                            selected = selected,
                            onClick = {
                                if (live) {
                                    customOpen = false
                                    actions.setEqPreset(p.id)
                                }
                            },
                            label = { Text(p.label) },
                            leadingIcon = if (selected) ({ ChipCheck() }) else null,
                            modifier = Modifier.offline(live).testTag("eq-preset-${p.id.lowercase()}"),
                        )
                    }
                    if (sorted.isNotEmpty()) {
                        FilterChip(
                            selected = showSliders,
                            onClick = { if (live) customOpen = true },
                            label = { Text("Custom") },
                            leadingIcon = if (showSliders) ({ ChipCheck() }) else null,
                            modifier = Modifier.offline(live).testTag("eq-custom"),
                        )
                    }
                }
                if (showSliders) {
                    sorted.forEachIndexed { i, band ->
                        EqSlider(band, actions, state.eqBandLabels?.getOrNull(i))
                    }
                }
            } else {
                val preset = EqPreset.match(bands)
                var customOpen by rememberSaveable { mutableStateOf(false) }
                val custom = preset == null || customOpen
                FlowRow(Pad, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (p in EqPreset.entries) {
                        FilterChip(
                            selected = !custom && preset == p,
                            onClick = {
                                if (live) {
                                    customOpen = false
                                    actions.setEq(p.bass, p.mid, p.treble)
                                }
                            },
                            label = { Text(p.label) },
                            leadingIcon = if (!custom && preset == p) ({ ChipCheck() }) else null,
                            modifier = Modifier.offline(live).testTag("eq-${p.name.lowercase()}"),
                        )
                    }
                    FilterChip(
                        selected = custom,
                        onClick = { if (live) customOpen = true },
                        label = { Text("Custom") },
                        leadingIcon = if (custom) ({ ChipCheck() }) else null,
                        modifier = Modifier.offline(live).testTag("eq-custom"),
                    )
                }
                if (custom) {
                    bands.sortedBy { it.bandId }.forEachIndexed { i, band ->
                        EqSlider(band, actions, state.eqBandLabels?.getOrNull(i))
                    }
                }
            }
        }
    }
}

@Composable
private fun EqSlider(band: EqBand, actions: Actions, customLabel: String? = null) {
    val name = customLabel ?: when (band.bandId) {
        0 -> "Bass"
        1 -> "Mid"
        2 -> "Treble"
        else -> "Band ${band.bandId}"
    }
    val live = LocalLive.current
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
            onValueChange = { if (live) dragging = it },
            onValueChangeFinished = {
                val wanted = dragging?.roundToInt()
                dragging = null
                if (wanted != null && wanted != band.current) actions.setEqBand(band.bandId, wanted)
            },
            valueRange = band.min.toFloat()..band.max.toFloat(),
            steps = (band.max - band.min - 1).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth().offline(live).testTag("eq-band-${band.bandId}").semantics {
                contentDescription = name
                stateDescription = "$name $signed"
            },
        )
    }
}

@Composable
fun CallsCard(state: HeadphoneState, actions: Actions) {
    if (state.selfVoice == Section.Unsupported && state.autoAnswer == Section.Unsupported && state.speakToChat == Section.Unsupported) return
    SettingsCard("Calls", "card-calls") {
        SectionContent(state.selfVoice, "Self Voice", "self-voice", actions.retry) { selfVoice ->
            SubHeading("Self Voice")
            Description("How much of your own voice you hear on calls.", Pad)
            ChoiceChips(selfVoiceOptions, selfVoice.level, "self-voice", actions.setSelfVoice)
        }
        SectionContent(state.speakToChat, "Speak-to-Chat", "speak-to-chat", actions.retry) { on ->
            SwitchRow("Speak-to-Chat", "Pauses music and lets outside sound in when you speak.", on, "speak-to-chat", actions.setSpeakToChat)
        }
        SectionContent(state.autoAnswer, "Auto answer", "auto-answer", actions.retry) { on ->
            SwitchRow("Auto answer", null, on, "auto-answer", actions.setAutoAnswer)
        }
    }
}

@Composable
fun DeviceCard(state: HeadphoneState, actions: Actions, onRename: () -> Unit, onPairing: () -> Unit, onPowerOff: () -> Unit) {
    val caps = state.capabilities
    val live = LocalLive.current
    val hasLiveActions = live && (caps.canRename || caps.canPair || caps.canPowerOff)
    if (state.autoPlayPause == Section.Unsupported && state.multipoint == Section.Unsupported && !hasLiveActions) return
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
        // Actions on the headphones themselves only make sense while connected.
        if (live) {
            if (caps.canRename) {
                SectionContent(state.name, "Name", "name-row", actions.retry) { name ->
                    NavRow(R.drawable.ic_edit, "Name: $name", "The name other devices see.", "rename", onRename)
                }
            }
            if (caps.canPair) {
                NavRow(R.drawable.ic_bluetooth, "Pairing mode", "Lets a new device pair. The headphones disconnect from this phone.", "pairing-mode", onPairing)
            }
            if (caps.canPowerOff) {
                NavRow(R.drawable.ic_power, "Power off", null, "power-off", onPowerOff)
            }
        }
    }
}

@Composable
fun BoseBanner(actions: Actions, companionAppName: String? = null) {
    val appName = companionAppName ?: "Bose"
    SettingsCard("$appName app installed", "bose-banner") {
        Description("The $appName app can take over the connection. If this app stops responding, force stop the $appName app.", Pad)
        FilledTonalButton(onClick = actions.openBoseAppSettings, modifier = Pad.testTag("open-bose-settings")) {
            Text("Open $appName app settings")
        }
    }
}
