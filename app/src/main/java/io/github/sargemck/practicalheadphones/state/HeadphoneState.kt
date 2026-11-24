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

/** Identifies the connected model and whether its protocol support is still unverified on real hardware. */
data class DeviceBadge(val brand: String, val model: String, val experimental: Boolean)

/** One battery component for earbuds (e.g. L, R, Case). */
data class BatteryPart(val label: String, val percent: Int)

/** One selectable option in a discrete ANC / Ambient control card (Sony, Soundcore, Samsung, Bose QC35). */
data class DiscreteAncOption(val id: String, val label: String, val description: String? = null)

/** State for headphones that expose discrete noise-control modes rather than Bose's continuous CNC slider. */
data class DiscreteAncState(
    val options: List<DiscreteAncOption>,
    val selectedId: String,
    val ambientLevel: Int? = null,
    val ambientMax: Int = 20,
    val focusOnVoice: Boolean? = null,
)

/** One selectable EQ preset for headphones that provide their own preset list (Sony, Soundcore, Samsung). */
data class EqPresetChoice(val id: String, val label: String, val values: List<Int> = emptyList())

/** One paired supported headphone available to switch to. */
data class PairedHeadphoneChoice(
    val address: String,
    val name: String,
    val brand: String,
    val selected: Boolean,
)

/** Per-model capability flags controlling which Device card and Mode actions are shown. */
data class Capabilities(
    val canRename: Boolean = true,
    val canPair: Boolean = true,
    val canPowerOff: Boolean = true,
    val canEditModes: Boolean = true,
    val editableSlots: IntRange = 4..10,
    val presetSlots: IntRange = 0..3,
    val presetNames: List<String> = listOf("Quiet", "Aware", "Immersion", "Cinema"),
)

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
    val deviceBadge: DeviceBadge? = null,
    val batteryParts: List<BatteryPart> = emptyList(),
    val discreteAnc: Section<DiscreteAncState> = Section.Unsupported,
    val speakToChat: Section<Boolean> = Section.Unsupported,
    val eqPresets: List<EqPresetChoice>? = null,
    val eqBandLabels: List<String>? = null,
    val availableDevices: List<PairedHeadphoneChoice> = emptyList(),
    val capabilities: Capabilities = Capabilities(),
    val companionAppName: String? = null,
    /**
     * The values last read while connected, kept after the link drops so the UI can show them
     * dimmed. Null until something has loaded in this process. Never shown as live values.
     */
    val lastKnown: HeadphoneState? = null,
) {
    /** Every section reset to [Section.Loading], keeping the link, app flags, available devices and a snapshot in [lastKnown]. */
    fun cleared(): HeadphoneState = HeadphoneState(
        link = link,
        boseAppInstalled = boseAppInstalled,
        companionAppName = companionAppName,
        availableDevices = availableDevices,
        lastKnown = snapshot() ?: lastKnown,
    )

    private fun snapshot(): HeadphoneState? =
        if (sections.any { it is Section.Loaded }) copy(lastKnown = null, batteryParts = emptyList()) else null

    private val sections
        get() = listOf(name, firmware, battery, modes, audio, discreteAnc, eq, selfVoice, speakToChat, autoPlayPause, autoAnswer, multipoint)

    val fullyLoaded: Boolean
        get() = sections.none { it is Section.Loading }

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
