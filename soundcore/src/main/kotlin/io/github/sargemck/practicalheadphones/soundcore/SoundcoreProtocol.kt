package io.github.sargemck.practicalheadphones.soundcore

enum class SoundcoreAncMode(val code: Int, val label: String) {
    ANC(0x00, "Noise cancelling"),
    TRANSPARENCY(0x01, "Transparency"),
    NORMAL(0x02, "Normal");

    companion object {
        fun fromCode(code: Int): SoundcoreAncMode? = entries.firstOrNull { it.code == code }
    }
}

data class SoundcoreSoundMode(
    val mode: SoundcoreAncMode,
    val rawBytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is SoundcoreSoundMode && mode == other.mode && rawBytes.contentEquals(other.rawBytes)

    override fun hashCode(): Int = 31 * mode.hashCode() + rawBytes.contentHashCode()
}

enum class SoundcoreEqPreset(val id: Int, val label: String, val bands: List<Int>) {
    SIGNATURE(0x00, "Signature", listOf(0, 0, 0, 0, 0, 0, 0, 0)),
    ACOUSTIC(0x01, "Acoustic", listOf(4, 1, 2, 2, 4, 4, 4, 2)),
    BASS_BOOSTER(0x02, "Bass Booster", listOf(4, 3, 1, 0, 0, 0, 0, 0)),
    BASS_REDUCER(0x03, "Bass Reducer", listOf(-4, -3, -1, 0, 0, 0, 0, 0)),
    CLASSICAL(0x04, "Classical", listOf(3, 3, -2, -2, 0, 2, 3, 4)),
    PODCAST(0x05, "Podcast", listOf(-3, 2, 4, 4, 3, 2, 0, -2)),
    TREBLE_BOOSTER(0x06, "Treble Booster", listOf(-2, -2, -2, -1, 1, 2, 3, 4)),
    ROCK(0x0F, "Rock", listOf(-1, 1, 3, 3, 1, -1, -2, -3)),
    CUSTOM(0xFE, "Custom", emptyList());

    companion object {
        fun fromId(id: Int): SoundcoreEqPreset? = entries.firstOrNull { it.id == id }
    }
}

data class SoundcoreEqState(
    val presetId: Int,
    val bands: List<Int>,
)

/**
 * Soundcore protocol constants, parsers, and builders (from `soundcorebridge` &
 * `omarchy-headphones/soundcore-bridge`, MIT).
 */
object SoundcoreProtocol {
    const val UUID_PREFIX = "0cf12d31-fac3-4553-bd80-d6832e7"
    const val DEFAULT_UUID = "0cf12d31-fac3-4553-bd80-d6832e700000"

    const val GROUP_STATE = 0x01
    const val CMD_STATE_UPDATE = 0x01
    const val CMD_BATTERY = 0x03

    const val GROUP_EQ = 0x02
    const val CMD_EQ_GET = 0x01
    const val CMD_EQ_SET = 0x81

    const val GROUP_SOUND_MODE = 0x06
    const val CMD_SOUND_MODE_GET = 0x01
    const val CMD_SOUND_MODE_SET = 0x81

    val EQ_BAND_LABELS = listOf("100 Hz", "200 Hz", "400 Hz", "800 Hz", "1.6 kHz", "3.2 kHz", "6.4 kHz", "12.8 kHz")

    /** Parses battery level byte (0..9 -> 10..100%, or direct percentage if 10..100). */
    fun parseBatteryPercent(payload: ByteArray): Int? {
        if (payload.isEmpty()) return null
        val raw = payload[0].toInt() and 0xFF
        return when {
            raw in 0..9 -> ((raw + 1) * 10).coerceIn(0, 100)
            raw in 10..100 -> raw
            else -> null
        }
    }

    fun parseSoundMode(payload: ByteArray): SoundcoreSoundMode? {
        if (payload.isEmpty()) return null
        val mode = SoundcoreAncMode.fromCode(payload[0].toInt() and 0xFF) ?: return null
        return SoundcoreSoundMode(mode = mode, rawBytes = payload.copyOf())
    }

    /** Builds `06 81` payload preserving the device's block width (4B on Q30, 6B on Space One/Q45/Space 2). */
    fun buildSoundModeSet(mode: SoundcoreAncMode, existingRaw: ByteArray?): ByteArray {
        val out = when {
            existingRaw != null && existingRaw.size >= 4 -> existingRaw.copyOf()
            else -> byteArrayOf(0x00, 0x1F, 0x02, 0x00, 0x00, 0x01)
        }
        out[0] = mode.code.toByte()
        // On 6-byte layouts, byte 2 is 0x02 on writes (0xFF on reads is ignored by Space 2).
        if (out.size >= 6 && (out[2].toInt() and 0xFF) == 0xFF) {
            out[2] = 0x02
        }
        return out
    }

    /** Parses `02 01` EQ reply: `[presetLo, presetHi, b1..b8, ...]` centred on 120 = 0 dB (10 units/dB). */
    fun parseEq(payload: ByteArray): SoundcoreEqState? {
        if (payload.size < 10) return null
        val presetId = payload[0].toInt() and 0xFF
        val bands = List(8) { i ->
            val raw = payload[2 + i].toInt() and 0xFF
            ((raw - 120) / 10).coerceIn(-6, 6)
        }
        return SoundcoreEqState(presetId = presetId, bands = bands)
    }

    fun buildEqSet(presetId: Int, bands: List<Int>): ByteArray {
        val out = ByteArray(10)
        out[0] = (presetId and 0xFF).toByte()
        out[1] = if (presetId == SoundcoreEqPreset.CUSTOM.id) 0xFE.toByte() else 0x00
        for (i in 0 until 8) {
            val db = (bands.getOrNull(i) ?: 0).coerceIn(-6, 6)
            out[2 + i] = (120 + db * 10).toByte()
        }
        return out
    }
}
