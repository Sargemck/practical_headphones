package io.github.sargemck.practicalheadphones.driver

import io.github.sargemck.practicalheadphones.bmap.AudioSettings
import io.github.sargemck.practicalheadphones.bmap.CloseReason
import io.github.sargemck.practicalheadphones.bmap.EqBand
import io.github.sargemck.practicalheadphones.bmap.Favourites
import io.github.sargemck.practicalheadphones.bmap.ProfileSettings
import io.github.sargemck.practicalheadphones.bmap.SelfVoice
import io.github.sargemck.practicalheadphones.bmap.SelfVoiceLevel
import io.github.sargemck.practicalheadphones.state.Capabilities
import io.github.sargemck.practicalheadphones.state.DeviceBadge
import io.github.sargemck.practicalheadphones.state.DiscreteAncState
import io.github.sargemck.practicalheadphones.state.HeadphoneState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class SectionKey {
    Name,
    Battery,
    Modes,
    Audio,
    DiscreteAnc,
    Eq,
    SelfVoice,
    SpeakToChat,
    AutoPlayPause,
    AutoAnswer,
    Multipoint,
}

sealed interface DriverState {
    data object Open : DriverState
    data class Closed(val reason: CloseReason) : DriverState
}

open class DriverException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Disconnected(val reason: CloseReason, cause: Throwable? = null) : DriverException("disconnected: $reason", cause)
    class Unsupported(message: String = "unsupported") : DriverException(message)
}

/**
 * Brand-agnostic driver adapting a pure-JVM protocol module (`:bmap`, `:sony`, `:soundcore`,
 * `:samsung`) to [io.github.sargemck.practicalheadphones.state.HeadphoneRepository].
 */
interface HeadphoneDriver {
    val badge: DeviceBadge?
    val capabilities: Capabilities
    val state: StateFlow<DriverState>

    /** Unsolicited updates tagged with the section key so the repository's write guard applies. */
    val updates: Flow<Pair<SectionKey, (HeadphoneState) -> HeadphoneState>>

    suspend fun initialLoad(apply: ((HeadphoneState) -> HeadphoneState) -> Unit)

    suspend fun retryFailed(current: () -> HeadphoneState, apply: ((HeadphoneState) -> HeadphoneState) -> Unit)

    suspend fun poll(
        battery: Boolean,
        guarded: suspend (SectionKey, suspend () -> ((HeadphoneState) -> HeadphoneState)?) -> Unit,
    )

    suspend fun selectMode(index: Int): AudioSettings? = throw DriverException.Unsupported()
    suspend fun setAudio(settings: AudioSettings): AudioSettings = throw DriverException.Unsupported()
    suspend fun setDiscreteAnc(optionId: String): DiscreteAncState = throw DriverException.Unsupported()
    suspend fun setAmbientLevel(level: Int): DiscreteAncState = throw DriverException.Unsupported()
    suspend fun setFocusOnVoice(on: Boolean): DiscreteAncState = throw DriverException.Unsupported()
    suspend fun setSpeakToChat(on: Boolean): Boolean = throw DriverException.Unsupported()
    suspend fun setEqBand(bandId: Int, value: Int): List<EqBand> = throw DriverException.Unsupported()
    suspend fun setEq(
        bass: Int,
        mid: Int,
        treble: Int,
        current: List<EqBand>,
        onConfirmed: (List<EqBand>) -> Unit = {},
    ): List<EqBand> = throw DriverException.Unsupported()
    suspend fun setEqPreset(presetId: String): List<EqBand> = throw DriverException.Unsupported()
    suspend fun setSelfVoice(level: SelfVoiceLevel): SelfVoice = throw DriverException.Unsupported()
    suspend fun setAutoPlayPause(on: Boolean): Boolean = throw DriverException.Unsupported()
    suspend fun setAutoAnswer(on: Boolean): Boolean = throw DriverException.Unsupported()
    suspend fun setName(name: String): String = throw DriverException.Unsupported()
    suspend fun setFavourites(favourites: Favourites): Favourites = throw DriverException.Unsupported()
    suspend fun createMode(name: String, settings: ProfileSettings) { throw DriverException.Unsupported() }
    suspend fun updateMode(index: Int, name: String, settings: ProfileSettings, isCurrent: Boolean) { throw DriverException.Unsupported() }
    suspend fun deleteMode(index: Int, isCurrent: Boolean, updatedFavourites: Favourites?) { throw DriverException.Unsupported() }
    suspend fun reloadModes(apply: ((HeadphoneState) -> HeadphoneState) -> Unit) {}
    suspend fun powerOff() { throw DriverException.Unsupported() }
    suspend fun pairingMode() { throw DriverException.Unsupported() }

    fun close()
}

fun interface DriverConnector {
    suspend fun connect(scope: CoroutineScope): HeadphoneDriver
}
