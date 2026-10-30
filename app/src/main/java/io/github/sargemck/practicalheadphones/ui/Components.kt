package io.github.sargemck.practicalheadphones.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.sargemck.practicalheadphones.state.Section

/** A titled card. Nothing is fixed-size, so text wraps at any font scale. */
@Composable
fun SettingsCard(title: String?, tag: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
            }
            content()
        }
    }
}

/** Renders a section: a placeholder while loading, a retry when failed, nothing when unsupported. */
@Composable
fun <T> SectionContent(section: Section<T>, label: String, tag: String, retry: () -> Unit, content: @Composable (T) -> Unit) {
    when (section) {
        Section.Loading -> Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag("$tag-loading"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("$label: loading…", style = MaterialTheme.typography.bodyMedium)
        }
        is Section.Failed -> Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp).testTag("$tag-failed"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$label: couldn't read this", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = retry, modifier = Modifier.testTag("$tag-retry")) { Text("Try again") }
        }
        Section.Unsupported -> Unit
        is Section.Loaded -> content(section.value)
    }
}

@Composable
fun Description(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
fun SubHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
}

/** A whole-row switch: the row is the one clickable node, labelled by its text. */
@Composable
fun SwitchRow(title: String, description: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Description(description)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A whole-row radio choice. [trailing] sits outside the clickable area (e.g. the favourite star). */
@Composable
fun RadioRow(
    title: String,
    description: String?,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag(tag),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (description != null) Description(description)
            }
        }
        if (trailing != null) trailing()
    }
}

/** A setting that opens something: title and explanation, with a button that wraps its label. */
@Composable
fun ActionRow(title: String, description: String, button: String, tag: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Description(description)
        TextButton(onClick = onClick, modifier = Modifier.testTag(tag)) { Text(button) }
    }
}
