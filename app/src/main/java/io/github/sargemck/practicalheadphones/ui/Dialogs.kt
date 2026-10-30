package io.github.sargemck.practicalheadphones.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

enum class DeviceDialog { Rename, Pairing, PowerOff }

@Composable
fun RenameDialog(current: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(current) }
    val error = nameError(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename headphones") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Name") },
                    isError = error != null,
                    supportingText = { Text(error ?: "The name other devices see.") },
                    modifier = Modifier.testTag("rename-field"),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = error == null, modifier = Modifier.testTag("rename-confirm")) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("dialog-cancel")) { Text("Cancel") }
        },
        modifier = Modifier.dialogTag("rename-dialog"),
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, tag: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("$tag-confirm")) { Text(confirm) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("dialog-cancel")) { Text("Cancel") }
        },
        modifier = Modifier.dialogTag("$tag-dialog"),
    )
}

/** A dialog is its own window, so it needs its own `testTagsAsResourceId` for adb to see the tags. */
private fun Modifier.dialogTag(tag: String) = semantics { testTagsAsResourceId = true }.testTag(tag)

@Composable
fun DeviceDialogs(dialog: DeviceDialog?, name: String?, actions: Actions, close: () -> Unit) {
    when (dialog) {
        DeviceDialog.Rename -> RenameDialog(
            current = name.orEmpty(),
            onConfirm = { actions.setName(it); close() },
            onDismiss = close,
        )
        DeviceDialog.Pairing -> ConfirmDialog(
            title = "Start pairing mode?",
            text = "Lets a new device pair. The headphones disconnect from this phone.",
            confirm = "Start pairing mode",
            tag = "pairing",
            onConfirm = { actions.pairingMode(); close() },
            onDismiss = close,
        )
        DeviceDialog.PowerOff -> ConfirmDialog(
            title = "Power off?",
            text = "Turns the headphones off.",
            confirm = "Power off",
            tag = "power-off",
            onConfirm = { actions.powerOff(); close() },
            onDismiss = close,
        )
        null -> Unit
    }
}
