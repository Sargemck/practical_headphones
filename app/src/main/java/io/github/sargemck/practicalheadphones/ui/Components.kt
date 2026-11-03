package io.github.sargemck.practicalheadphones.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.sargemck.practicalheadphones.R
import io.github.sargemck.practicalheadphones.state.Section

/**
 * False while the screen shows the last known values of disconnected headphones. Controls are
 * drawn as usual (the whole section is dimmed evenly and touches are blocked above it), marked
 * disabled for accessibility, and ignore input. Sections that hadn't loaded are left out.
 */
val LocalLive = staticCompositionLocalOf { true }

/** Marks a control disabled for accessibility while offline, without the faded disabled look. */
fun Modifier.offline(live: Boolean): Modifier = if (live) this else semantics { disabled() }

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
    if (!LocalLive.current) {
        if (section is Section.Loaded) content(section.value)
        return
    }
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

/** A whole-row switch: the row is the one clickable node, labelled by its text. [description] only when the title isn't enough. */
@Composable
fun SwitchRow(title: String, description: String?, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    val live = LocalLive.current
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = live, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) Description(description)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * A whole-row radio choice. [badge] is a short label next to the title (e.g. "Bose preset").
 * [trailing] sits outside the clickable area (e.g. the favourite star).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RadioRow(
    title: String,
    description: String?,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
    badge: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val live = LocalLive.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .selectable(selected = selected, enabled = live, role = Role.RadioButton, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag(tag),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(Modifier.weight(1f)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.align(Alignment.CenterVertically))
                    if (badge != null) Badge(badge, Modifier.align(Alignment.CenterVertically))
                }
                if (description != null) Description(description)
            }
        }
        if (trailing != null) trailing()
    }
}

@Composable
fun Badge(text: String, modifier: Modifier = Modifier) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer, modifier = modifier) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** One choice out of a few short options, as chips that wrap at large font sizes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceChips(options: List<Pair<String, T>>, selected: T, tagPrefix: String, onSelect: (T) -> Unit) {
    val live = LocalLive.current
    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((label, value) in options) {
            FilterChip(
                selected = value == selected,
                onClick = { if (live) onSelect(value) },
                label = { Text(label) },
                leadingIcon = if (value == selected) ({ ChipCheck() }) else null,
                modifier = Modifier.offline(live).testTag("$tagPrefix-${label.lowercase()}"),
            )
        }
    }
}

/** The check Material puts in a selected filter chip. */
@Composable
fun ChipCheck() {
    Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
}

/** A setting that opens something: icon, title and explanation, the whole row tappable. */
@Composable
fun NavRow(@DrawableRes icon: Int?, title: String, description: String?, tag: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = LocalLive.current, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (icon != null) Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) Description(description)
        }
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
