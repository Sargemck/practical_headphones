package io.github.sargemck.practicalheadphones.ui

import io.github.sargemck.practicalheadphones.bmap.AudioSettings
import io.github.sargemck.practicalheadphones.bmap.ProfileSettings
import io.github.sargemck.practicalheadphones.bmap.SelfVoiceLevel
import io.github.sargemck.practicalheadphones.state.HeadphoneController

/** Everything the screen can ask for. Defaults do nothing, so tests set only what they check. */
data class Actions(
    val reconnect: () -> Unit = {},
    val retry: () -> Unit = {},
    val selectMode: (Int) -> Unit = {},
    val toggleFavourite: (Int) -> Unit = {},
    val setAudio: ((AudioSettings) -> AudioSettings) -> Unit = {},
    val setEq: (bass: Int, mid: Int, treble: Int) -> Unit = { _, _, _ -> },
    val setEqBand: (bandId: Int, value: Int) -> Unit = { _, _ -> },
    val setSelfVoice: (SelfVoiceLevel) -> Unit = {},
    val setAutoPlayPause: (Boolean) -> Unit = {},
    val setAutoAnswer: (Boolean) -> Unit = {},
    val setName: (String) -> Unit = {},
    val createMode: (name: String, settings: ProfileSettings) -> Unit = { _, _ -> },
    val updateMode: (index: Int, name: String, settings: ProfileSettings) -> Unit = { _, _, _ -> },
    val deleteMode: (Int) -> Unit = {},
    val powerOff: () -> Unit = {},
    val pairingMode: () -> Unit = {},
    val openBluetoothSettings: () -> Unit = {},
    val openBoseAppSettings: () -> Unit = {},
    val openUrl: (String) -> Unit = {},
    val requestBluetooth: () -> Unit = {},
    val requestNotifications: () -> Unit = {},
    val openAppSettings: () -> Unit = {},
    val addTile: () -> Unit = {},
    val setUpAutoConnect: () -> Unit = {},
    val setDiscreteAnc: (String) -> Unit = {},
    val setAmbientLevel: (Int) -> Unit = {},
    val setFocusOnVoice: (Boolean) -> Unit = {},
    val setSpeakToChat: (Boolean) -> Unit = {},
    val setEqPreset: (String) -> Unit = {},
    val selectDevice: (String) -> Unit = {},
    val copyDiagnostics: () -> Unit = {},
) {
    companion object {
        /** Headphone actions from [controller]; the Android ones come from the activity. */
        fun of(controller: HeadphoneController, android: Actions): Actions = android.copy(
            reconnect = controller::reconnect,
            retry = controller::retryFailed,
            selectMode = controller::selectMode,
            toggleFavourite = controller::toggleFavourite,
            setAudio = controller::setAudio,
            setEq = controller::setEq,
            setEqBand = controller::setEqBand,
            setSelfVoice = controller::setSelfVoice,
            setAutoPlayPause = controller::setAutoPlayPause,
            setAutoAnswer = controller::setAutoAnswer,
            setName = controller::setName,
            createMode = controller::createMode,
            updateMode = controller::updateMode,
            deleteMode = controller::deleteMode,
            powerOff = controller::powerOff,
            pairingMode = controller::pairingMode,
            setDiscreteAnc = controller::setDiscreteAnc,
            setAmbientLevel = controller::setAmbientLevel,
            setFocusOnVoice = controller::setFocusOnVoice,
            setSpeakToChat = controller::setSpeakToChat,
            setEqPreset = controller::setEqPreset,
            selectDevice = controller::selectDevice,
        )
    }
}
