package io.github.sargemck.practicalheadphones.state

import io.github.sargemck.practicalheadphones.bmap.AudioSettings
import io.github.sargemck.practicalheadphones.bmap.SelfVoiceLevel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** What the UI sees of the headphones: state to observe and actions to send. */
interface HeadphoneController {
    val state: StateFlow<HeadphoneState>
    val messages: SharedFlow<UserMessage>

    fun reconnect()
    fun retryFailed()
    fun selectMode(index: Int)
    fun toggleFavourite(index: Int)
    fun setAudio(change: (AudioSettings) -> AudioSettings)
    fun setEq(bass: Int, mid: Int, treble: Int)
    fun setEqBand(bandId: Int, value: Int)
    fun setSelfVoice(level: SelfVoiceLevel)
    fun setAutoPlayPause(on: Boolean)
    fun setAutoAnswer(on: Boolean)
    fun setName(name: String)
    fun powerOff()
    fun pairingMode()
}
