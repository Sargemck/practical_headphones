package io.github.sargemck.practicalheadphones.bmap

data class MacAddress(val octets: List<Int>) {
    init {
        require(octets.size == 6 && octets.all { it in 0..255 }) { "MAC needs 6 octets: $octets" }
    }

    fun toBytes(): ByteArray = ByteArray(6) { octets[it].toByte() }

    override fun toString(): String = octets.joinToString(":") { "%02X".format(it) }

    companion object {
        fun of(bytes: ByteArray, offset: Int = 0): MacAddress =
            MacAddress(List(6) { bytes[offset + it].toInt() and 0xFF })
    }
}

/** One EQ band from [1.7]. Band 0 bass, 1 mid, 2 treble. */
data class EqBand(val bandId: Int, val min: Int, val max: Int, val current: Int)

/** A single-band EQ write: [value] in -10..10. */
data class EqBandSetting(val bandId: Int, val value: Int)

/** [1.9], read only. IDs are kept as numbers; remapping is never built. */
data class ButtonMapping(val buttonId: Int, val event: Int, val action: Int)

/** [1.10]. [raw] is the whole bitfield; only bit 0x02 (enabled) is understood. */
data class Multipoint(val enabled: Boolean, val raw: Int)

enum class SelfVoiceLevel(val code: Int) {
    OFF(0),
    HIGH(1),
    MEDIUM(2),
    LOW(3);

    companion object {
        fun fromCode(code: Int): SelfVoiceLevel? = entries.firstOrNull { it.code == code }
    }
}

/** [1.11]. [extra] is the unverified third byte (fw 10.12.12 sends 0x0f), null if absent. */
data class SelfVoice(val persist: Boolean, val level: SelfVoiceLevel, val extra: Int?)

/** [4.4]. [header] is the unexplained first byte. */
data class PairedDevices(val header: Int, val devices: List<MacAddress>)

/** [4.5]. [flags] are the 3 unexplained bytes between MAC and name. */
data class DeviceInfo(val mac: MacAddress, val flags: List<Int>, val name: String)

enum class SourceType(val code: Int) {
    NONE(0),
    BLUETOOTH(1),
    AUXILIARY(2);

    companion object {
        fun fromCode(code: Int): SourceType? = entries.firstOrNull { it.code == code }
    }
}

/** [5.1]. [mac] is set only for a Bluetooth source. */
data class Source(val supported: Int, val type: SourceType, val mac: MacAddress?)

enum class Spatial(val code: Int) {
    OFF(0),
    FIXED_TO_ROOM(1),
    FIXED_TO_HEAD(2);

    companion object {
        fun fromCode(code: Int): Spatial? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Live audio settings [31.10]. [cnc] 0..10 is inverted: 0 = most noise cancelling.
 * Writes always send autoCnc 0 (firmware rejects 1).
 */
data class AudioSettings(
    val cnc: Int,
    val autoCnc: Boolean,
    val spatial: Spatial,
    val wind: Boolean,
    val anc: Boolean,
)

/** [31.8]. [slots] are the favourite mode indices. */
data class Favourites(val total: Int, val slots: Set<Int>)

/** One mode from a [31.6] 48-byte STATUS. */
data class ModeConfig(
    val index: Int,
    val promptB1: Int,
    val promptB2: Int,
    val editable: Boolean,
    val configured: Boolean,
    val name: String,
    val cnc: Int,
    val autoCnc: Boolean,
    val spatial: Spatial,
    val wind: Boolean,
    val anc: Boolean,
)

/** A [31.6] 40-byte SETGET. */
data class ModeConfigWrite(
    val index: Int,
    val promptB1: Int,
    val promptB2: Int,
    val name: String,
    val cnc: Int,
    val spatial: Spatial,
    val wind: Boolean,
    val anc: Boolean,
)

/**
 * Everything one [31.1] drain returns. Fields other than [modes] are null when the drain
 * didn't include them. If an address repeats, the last value wins.
 */
data class ModesSnapshot(
    val modes: List<ModeConfig>,
    val currentMode: Int?,
    val favourites: Favourites?,
    val audioSettings: AudioSettings?,
)
