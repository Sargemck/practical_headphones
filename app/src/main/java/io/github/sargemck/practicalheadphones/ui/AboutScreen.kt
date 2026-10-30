package io.github.sargemck.practicalheadphones.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.sargemck.practicalheadphones.state.HeadphoneState
import io.github.sargemck.practicalheadphones.state.valueOrNull

@Composable
fun AboutContent(state: HeadphoneState, appVersion: String, actions: Actions, padding: PaddingValues) {
    val direction = LocalLayoutDirection.current
    val pad = Modifier.padding(horizontal = 16.dp)
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("about-list"),
        contentPadding = PaddingValues(
            start = padding.calculateStartPadding(direction) + 16.dp,
            end = padding.calculateEndPadding(direction) + 16.dp,
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SettingsCard("Practical Headphones", "about-app") {
                Text("Version $appVersion", style = MaterialTheme.typography.bodyLarge, modifier = pad)
                Text(
                    "Headphone firmware: ${state.firmware.valueOrNull() ?: "not connected"}",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = pad,
                )
                Description("Controls Bose QC Ultra Headphones (2nd Gen) over Bluetooth. No account, no internet.", pad)
                Description("Not affiliated with Bose.", pad)
            }
        }
        item {
            SettingsCard("Credits", "about-credits") {
                Description("Protocol knowledge from bosectl, MIT License, Copyright (c) 2026 Aaron Bockelie.", pad)
                TextButton(onClick = { actions.openUrl(Licences.BOSECTL_URL) }, modifier = Modifier.padding(horizontal = 4.dp).testTag("bosectl-link")) {
                    Text("Open bosectl on GitHub")
                }
                Description("Icons: Material Icons by Google, Apache License 2.0.", pad)
            }
        }
        item {
            SettingsCard("Licence", "about-licence") {
                Text(Licences.LICENSE.trim(), style = MaterialTheme.typography.bodySmall, modifier = pad)
            }
        }
    }
}
