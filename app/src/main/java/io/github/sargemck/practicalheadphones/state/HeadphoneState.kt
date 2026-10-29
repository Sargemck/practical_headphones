package io.github.sargemck.practicalheadphones.state

import io.github.sargemck.practicalheadphones.bmap.AudioSettings
import io.github.sargemck.practicalheadphones.bmap.EqBand
import io.github.sargemck.practicalheadphones.bmap.Favourites
import io.github.sargemck.practicalheadphones.bmap.ModeConfig
import io.github.sargemck.practicalheadphones.bmap.Multipoint
import io.github.sargemck.practicalheadphones.bmap.SelfVoice

/** One part of the state. The UI shows only [Loaded] values as real. */
sealed interface Section<out T> {
    data object Loading : Section<Nothing>

    data class Loaded<T>(val value: T) : Section<T>

    data class Failed(val message: String) : Section<Nothing>

    /** The headphones don't answer this address. */
    data object Unsupported : Section<Nothing>
}

fun <T> Section<T>.valueOrNull(): T? = (this as? Section.Loaded<T>)?.value

/** [modes] holds only configured modes (name not blank or `None`). */
data class ModesSection(val modes: List<ModeConfig>, val current: Int, val favourites: Favourites?) {
    val currentMode: ModeConfig? get() = modes.firstOrNull { it.index == current }
}

enum class LinkState {
    BluetoothOff,
    NotPaired,

    /** Headphones off, asleep or connected elsewhere. Quiet: waits for the next connected event. */
    Disconnected,
    Connecting,
    Connected,
    AnotherApp,
    HeadphonesOff,
    PairingMode,
    ConnectFailed,

    /** The link stopped answering. */
    Lost,
}

data class HeadphoneState(
    val link: LinkState = LinkState.Disconnected,
    val name: Section<String> = Section.Loading,
    val firmware: Section<String> = Section.Loading,
    val battery: Section<Int> = Section.Loading,
    val modes: Section<ModesSection> = Section.Loading,
    val audio: Section<AudioSettings> = Section.Loading,
    val eq: Section<List<EqBand>> = Section.Loading,
    val selfVoice: Section<SelfVoice> = Section.Loading,
    val autoPlayPause: Section<Boolean> = Section.Loading,
    val autoAnswer: Section<Boolean> = Section.Loading,
    val multipoint: Section<Multipoint> = Section.Loading,
    val boseAppInstalled: Boolean = false,
) {
    /** Every section reset to [Section.Loading], keeping the link and the Bose app flag. */
    fun cleared(): HeadphoneState = HeadphoneState(link = link, boseAppInstalled = boseAppInstalled)

    val fullyLoaded: Boolean
        get() = listOf(name, firmware, battery, modes, audio, eq, selfVoice, autoPlayPause, autoAnswer, multipoint)
            .none { it is Section.Loading }

    /** One-line dump for debug logs. Leaves out the product name and paired-device details. */
    fun summary(): String = buildString {
        append("link=").append(link)
        append(" battery=").append(short(battery))
        append(" modes=").append(short(modes) { "${it.modes.size} current=${it.current} favs=${it.favourites?.slots}" })
        append(" audio=").append(short(audio) { "cnc=${it.cnc} spatial=${it.spatial} wind=${it.wind} anc=${it.anc}" })
        append(" eq=").append(short(eq) { bands -> bands.joinToString("/") { it.current.toString() } })
        append(" selfVoice=").append(short(selfVoice) { it.level.toString() })
        append(" autoPlayPause=").append(short(autoPlayPause))
        append(" autoAnswer=").append(short(autoAnswer))
        append(" multipoint=").append(short(multipoint) { it.enabled.toString() })
        append(" name=").append(short(name) { "set" })
        append(" firmware=").append(short(firmware))
        append(" bose=").append(boseAppInstalled)
    }

    private fun <T> short(section: Section<T>, value: (T) -> String = { it.toString() }): String = when (section) {
        Section.Loading -> "loading"
        is Section.Loaded -> value(section.value)
        is Section.Failed -> "failed"
        Section.Unsupported -> "unsupported"
    }
}

/** Something to tell the user, e.g. a write that was rolled back. */
data class UserMessage(val text: String)
