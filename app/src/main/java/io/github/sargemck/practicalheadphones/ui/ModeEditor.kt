package io.github.sargemck.practicalheadphones.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.sargemck.practicalheadphones.bmap.NameProblem
import io.github.sargemck.practicalheadphones.bmap.ProfileSettings
import io.github.sargemck.practicalheadphones.bmap.Profiles
import io.github.sargemck.practicalheadphones.bmap.Spatial
import io.github.sargemck.practicalheadphones.state.HeadphoneState
import io.github.sargemck.practicalheadphones.state.LinkState
import io.github.sargemck.practicalheadphones.state.valueOrNull

private val Pad = Modifier.padding(horizontal = 16.dp)

/**
 * Creates a mode ([slot] null) or edits the custom mode in [slot]. Nothing is sent until Save:
 * the controls here change the draft, not the headphones. A new mode starts from the current
 * noise settings, so saving what you hear now is a name and one tap.
 */
@Composable
fun ModeEditorContent(state: HeadphoneState, slot: Int?, actions: Actions, padding: PaddingValues, close: () -> Unit) {
    val live = state.link == LinkState.Connected
    val modes = state.modes.valueOrNull()
    val mode = slot?.let { s -> modes?.modes?.firstOrNull { it.index == s } }
    val start = mode?.let { ProfileSettings(it.cnc, it.spatial, it.wind, it.anc) }
        ?: state.audio.valueOrNull()?.let { ProfileSettings(it.cnc, it.spatial, it.wind, it.anc) }
        ?: ProfileSettings(cnc = 0, spatial = Spatial.OFF, wind = false, anc = true)
    var name by rememberSaveable { mutableStateOf(mode?.name?.trim().orEmpty()) }
    var cnc by rememberSaveable { mutableIntStateOf(start.cnc) }
    var spatial by rememberSaveable { mutableStateOf(start.spatial) }
    var wind by rememberSaveable { mutableStateOf(start.wind) }
    var anc by rememberSaveable { mutableStateOf(start.anc) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    // The mode went away while open (deleted, or the list reloaded without it).
    if (slot != null && modes != null && mode == null) LaunchedEffect(Unit) { close() }

    val settings = ProfileSettings(cnc, spatial, wind, anc)
    val problem = if (modes == null) {
        NameProblem.Blank
    } else {
        Profiles.nameProblem(name, modes.modes, except = slot, presetNames = state.capabilities.presetNames)
    }
    val error = modeNameError(problem)
    val changed = mode == null || name.trim() != mode.name.trim() || settings != start
    val current = mode != null && modes?.current == mode.index

    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .consumeWindowInsets(padding)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp)
            .testTag("mode-editor"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            modifier = Pad.fillMaxWidth().testTag("mode-name"),
        )
        when {
            mode == null -> "Starts from what you hear now. Creating it switches to it."
            current -> "This is the mode you're using. Saving applies the changes now."
            else -> null
        }?.let { Description(it, Pad.padding(bottom = 4.dp)) }
        SubHeading("Noise control")
        NoiseSlider(cnc, "editor-noise-slider") { cnc = it }
        SwitchRow("Wind Block", "Cuts wind noise outdoors.", wind, "editor-wind-block") { wind = it }
        if (state.capabilities.supportsModeAncToggle) {
            SwitchRow("Noise cancelling", null, anc, "editor-noise-cancelling") { anc = it }
        }
        SubHeading("Spatial audio")
        ChoiceChips(spatialOptions.map { it.first to it.second }, spatial, "editor-spatial") { spatial = it }
        spatialOptions.first { it.second == spatial }.third?.let { Description(it, Pad) }
        if (!live) Description("Connect your headphones to save.", Pad.padding(top = 8.dp).testTag("editor-offline"))
        Button(
            onClick = {
                if (slot == null) actions.createMode(name, settings) else actions.updateMode(slot, name, settings)
                close()
            },
            enabled = live && problem == null && changed,
            modifier = Pad.padding(top = 16.dp).fillMaxWidth().testTag("mode-save"),
        ) {
            Text(if (slot == null) "Create mode" else "Save")
        }
        if (mode != null) {
            TextButton(
                onClick = { confirmDelete = true },
                enabled = live,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Pad.fillMaxWidth().testTag("mode-delete"),
            ) {
                Text("Delete mode")
            }
        }
    }
    if (confirmDelete && mode != null) {
        ConfirmDialog(
            title = "Delete ${mode.name.trim()}?",
            text = if (current) "The headphones switch to Quiet." else null,
            confirm = "Delete",
            tag = "delete-mode",
            onConfirm = {
                confirmDelete = false
                actions.deleteMode(mode.index)
                close()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}
