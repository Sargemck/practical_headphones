package io.github.sargemck.practicalheadphones.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

enum class Permission {
    Granted,

    /** Not granted, and Android will still show the prompt. */
    Denied,

    /** Not granted, and Android won't show the prompt again: only app settings can grant it. */
    Blocked,
}

/** Runtime permissions the screen needs to know about. Defaults to everything granted (tests, demo). */
data class Permissions(val bluetooth: Permission = Permission.Granted, val notifications: Permission = Permission.Granted)

/** Things the screen offers depending on the phone, not the headphones. [canAddTile]: Android 13+ and the tile isn't added yet. */
data class PhoneFeatures(val canAddTile: Boolean = false)

/** Lets the All modes sheet see [PhoneFeatures] without threading it through every card. */
val LocalPhoneFeatures = staticCompositionLocalOf { PhoneFeatures() }

/** What the hero card says when the Nearby devices permission is missing, instead of the link state. */
fun permissionStatus(bluetooth: Permission): LinkStatus? = when (bluetooth) {
    Permission.Granted -> null
    Permission.Denied -> LinkStatus(
        "Permission needed",
        "Allow Nearby devices so this app can talk to your headphones.",
        LinkStatus.Action.AllowBluetooth,
    )
    Permission.Blocked -> LinkStatus(
        "Permission needed",
        "Turn on Nearby devices in this app's permissions, then come back.",
        LinkStatus.Action.OpenAppSettings,
    )
}

/** Notifications are optional: a banner that says what's missing, with a way to fix it or dismiss it. */
@Composable
fun NotificationsBanner(notifications: Permission, actions: Actions, dismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("notifications-banner")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Notifications are off", style = MaterialTheme.typography.titleMedium)
            Description("Turn them on to see battery and change modes from the notification shade.")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = dismiss, modifier = Modifier.testTag("notifications-dismiss")) { Text("Not now") }
                if (notifications == Permission.Blocked) {
                    FilledTonalButton(onClick = actions.openAppSettings, modifier = Modifier.testTag("notifications-settings")) {
                        Text("Open settings")
                    }
                } else {
                    FilledTonalButton(onClick = actions.requestNotifications, modifier = Modifier.testTag("notifications-allow")) {
                        Text("Turn on")
                    }
                }
            }
        }
    }
}
