package io.github.sargemck.practicalheadphones.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import io.github.sargemck.practicalheadphones.R
import io.github.sargemck.practicalheadphones.state.HeadphoneState
import io.github.sargemck.practicalheadphones.state.LinkState
import io.github.sargemck.practicalheadphones.state.valueOrNull

/**
 * The whole app UI. A pure function of [state]: everything it does goes through [actions],
 * so screenshot tests render it without a repository.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun HeadphonesApp(
    state: HeadphoneState,
    actions: Actions,
    appVersion: String,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    initialScreen: Screen = Screen.Main,
    initialDialog: DeviceDialog? = null,
    permissions: Permissions = Permissions(),
    initialEditorSlot: Int? = null,
    phone: PhoneFeatures = PhoneFeatures(),
) {
    var screen by rememberSaveable { mutableStateOf(initialScreen) }
    var dialog by rememberSaveable { mutableStateOf(initialDialog) }
    // The mode the editor is open for, or null for a new mode.
    var editorSlot by rememberSaveable { mutableStateOf(initialEditorSlot) }
    BackHandler(enabled = screen != Screen.Main) { screen = Screen.Main }
    // Changes the bar's shade while content scrolls under it.
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    CompositionLocalProvider(LocalPhoneFeatures provides phone) {
        Scaffold(
            modifier = Modifier.nestedScroll(scroll.nestedScrollConnection).semantics { testTagsAsResourceId = true },
            topBar = {
                when (screen) {
                    Screen.Main -> TopAppBar(
                        title = { Text("Practical Headphones") },
                        scrollBehavior = scroll,
                        actions = {
                            IconButton(onClick = { screen = Screen.About }, modifier = Modifier.testTag("about")) {
                                Icon(painterResource(R.drawable.ic_info), contentDescription = "About")
                            }
                        },
                    )
                    Screen.About -> TopAppBar(
                        title = { Text("About") },
                        scrollBehavior = scroll,
                        navigationIcon = { BackIcon { screen = Screen.Main } },
                    )
                    Screen.ModeEditor -> TopAppBar(
                        title = { Text(if (editorSlot == null) "New mode" else "Edit mode") },
                        scrollBehavior = scroll,
                        navigationIcon = { BackIcon { screen = Screen.Main } },
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            when (screen) {
                Screen.Main -> MainContent(
                    state,
                    actions,
                    padding,
                    permissions,
                    openDialog = { dialog = it },
                    edit = { slot ->
                        editorSlot = slot
                        screen = Screen.ModeEditor
                    },
                )
                Screen.About -> AboutContent(state, appVersion, actions, padding)
                Screen.ModeEditor -> ModeEditorContent(state, editorSlot, actions, padding) { screen = Screen.Main }
            }
        }
        DeviceDialogs(dialog, state.name.valueOrNull(), actions) { dialog = null }
    }
}

enum class Screen { Main, About, ModeEditor }

@Composable
private fun MainContent(
    state: HeadphoneState,
    actions: Actions,
    padding: PaddingValues,
    permissions: Permissions,
    openDialog: (DeviceDialog) -> Unit,
    edit: (Int?) -> Unit,
) {
    var notificationsDismissed by rememberSaveable { mutableStateOf(false) }
    val direction = LocalLayoutDirection.current
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("main-list"),
        contentPadding = PaddingValues(
            start = padding.calculateStartPadding(direction) + 16.dp,
            end = padding.calculateEndPadding(direction) + 16.dp,
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { HeroCard(state, actions, permissions.bluetooth) }
        if (permissions.bluetooth == Permission.Granted && permissions.notifications != Permission.Granted && !notificationsDismissed) {
            item { NotificationsBanner(permissions.notifications, actions) { notificationsDismissed = true } }
        }
        if (state.boseAppInstalled) item { BoseBanner(actions) }
        val live = state.link == LinkState.Connected
        // While disconnected, the last known settings show dimmed and disabled.
        val shown = if (live) state else state.lastKnown
        if (!live) item { OfflineNote(hasLastKnown = shown != null) }
        fun section(content: @Composable () -> Unit) = item {
            CompositionLocalProvider(LocalLive provides live) {
                if (live) {
                    content()
                } else {
                    Box {
                        Box(Modifier.alpha(OFFLINE_ALPHA)) { content() }
                        // Takes every touch, so nothing under it reacts or ripples.
                        Box(Modifier.matchParentSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } })
                    }
                }
            }
        }
        if (shown == null) {
            for ((title, text) in previewSections) section { PreviewCard(title, text) }
            return@LazyColumn
        }
        section { ModesCard(shown, actions, edit) }
        section { NoiseCard(shown, actions) }
        section { SoundCard(shown, actions) }
        section { CallsCard(shown, actions) }
        section {
            DeviceCard(
                shown,
                actions,
                onRename = { openDialog(DeviceDialog.Rename) },
                onPairing = { openDialog(DeviceDialog.Pairing) },
                onPowerOff = { openDialog(DeviceDialog.PowerOff) },
            )
        }
    }
}

private const val OFFLINE_ALPHA = 0.5f

@Composable
internal fun BackIcon(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.testTag("back")) {
        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
    }
}
