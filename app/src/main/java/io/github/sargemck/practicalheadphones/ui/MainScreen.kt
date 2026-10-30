package io.github.sargemck.practicalheadphones.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
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
) {
    var screen by rememberSaveable { mutableStateOf(initialScreen) }
    var dialog by rememberSaveable { mutableStateOf(initialDialog) }
    BackHandler(enabled = screen == Screen.About) { screen = Screen.Main }
    Scaffold(
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        topBar = {
            when (screen) {
                Screen.Main -> TopAppBar(
                    title = { Text("Practical Headphones") },
                    actions = {
                        IconButton(onClick = { screen = Screen.About }, modifier = Modifier.testTag("about")) {
                            Icon(painterResource(R.drawable.ic_info), contentDescription = "About")
                        }
                    },
                )
                Screen.About -> TopAppBar(
                    title = { Text("About") },
                    navigationIcon = { BackIcon { screen = Screen.Main } },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when (screen) {
            Screen.Main -> MainContent(state, actions, padding) { dialog = it }
            Screen.About -> AboutContent(state, appVersion, actions, padding)
        }
    }
    DeviceDialogs(dialog, state.name.valueOrNull(), actions) { dialog = null }
}

enum class Screen { Main, About }

@Composable
private fun MainContent(state: HeadphoneState, actions: Actions, padding: PaddingValues, openDialog: (DeviceDialog) -> Unit) {
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
        if (state.boseAppInstalled) item { BoseBanner(actions) }
        if (state.link != LinkState.Connected) {
            item { StatusCard(state.link, actions) }
            return@LazyColumn
        }
        item { HeaderCard(state, actions) }
        item { ModesCard(state, actions) }
        item { NoiseCard(state, actions) }
        item { SoundCard(state, actions) }
        item { CallsCard(state, actions) }
        item {
            DeviceCard(
                state,
                actions,
                onRename = { openDialog(DeviceDialog.Rename) },
                onPairing = { openDialog(DeviceDialog.Pairing) },
                onPowerOff = { openDialog(DeviceDialog.PowerOff) },
            )
        }
    }
}

@Composable
internal fun BackIcon(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.testTag("back")) {
        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
    }
}
