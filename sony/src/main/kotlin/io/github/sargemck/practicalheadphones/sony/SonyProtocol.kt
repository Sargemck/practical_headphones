package io.github.sargemck.practicalheadphones.sony

enum class SonyAncMode { OFF, ANC, AMBIENT }

data class SonyAncState(
    val inquiredType: Int,
    val mode: SonyAncMode,
    val ambientLevel: Int,
    val focusOnVoice: Boolean,
    val available: List<SonyAncMode>,
    val ncValue: Int? = null,
)

data class SonyBattery(
    val percent: Int,
    val left: Int? = null,
    val right: Int? = null,
    val cradle: Int? = null,
)

enum class SonyEqPreset(val code: Int, val label: String) {
    OFF(0x00, "Off"),
    BRIGHT(0x01, "Bright"),
    EXCITED(0x02, "Excited"),
    MELLOW(0x03, "Mellow"),
    RELAXED(0x04, "Relaxed"),
    VOCAL(0x05, "Vocal"),
    TREBLE_BOOST(0x06, "Treble Boost"),
    BASS_BOOST(0x07, "Bass Boost"),
    SPEECH(0x08, "Speech"),
    CUSTOM(0xA0, "Custom");

    companion object {
        fun fromCode(code: Int): SonyEqPreset? = entries.firstOrNull { it.code == code }
    }
}

/** 5-band EQ (400 Hz, 1 kHz, 2.5 kHz, 6.3 kHz, 16 kHz) + Clear Bass (each -10..10). */
data class SonyEqState(
    val preset: SonyEqPreset,
    val bands: List<Int>,
)

/**
 * Sony MDR v1/v2 protocol constants, parsers, and builders (cross-checked against
 * `SonyHeadphonesClient` & `omarchy-headphones/sony-bridge`, MIT).
 */
object SonyProtocol {
    const val UUID_V2 = "956c7b26-d49a-4ba8-b03f-b17d393cb6e2"
    const val UUID_V1 = "96cc203e-5068-46ad-b32d-e316f5e069ba"

    const val CONNECT_GET_PROTOCOL_INFO = 0x00
    const val CONNECT_RET_PROTOCOL_INFO = 0x01

    const val COMMON_GET_BATTERY_LEVEL = 0x10
    const val COMMON_RET_BATTERY_LEVEL = 0x11
    const val COMMON_NTFY_BATTERY_LEVEL = 0x13
    const val BATTERY_SINGLE = 0x00
    const val BATTERY_LEFT_RIGHT = 0x01
    const val BATTERY_CRADLE = 0x02

    const val POWER_SET_STATUS = 0x22

    const val EQEBB_GET_PARAM = 0x56
    const val EQEBB_RET_PARAM = 0x57
    const val EQEBB_SET_PARAM = 0x58
    const val EQEBB_NTFY_PARAM = 0x59
    const val EQEBB_INQUIRED_PRESET_AND_BANDS = 0x01

    const val NCASM_GET_PARAM = 0x66
    const val NCASM_RET_PARAM = 0x67
    const val NCASM_SET_PARAM = 0x68
    const val NCASM_NTFY_PARAM = 0x69

    const val SYSTEM_GET_EXT_PARAM = 0xF6
    const val SYSTEM_RET_EXT_PARAM = 0xF7
    const val SYSTEM_SET_EXT_PARAM = 0xF8
    const val SYSTEM_NTFY_EXT_PARAM = 0xF9
    const val SMART_TALKING_MODE_TYPE = 0x0C

    val CANDIDATES_V2 = listOf(0x17, 0x15, 0x22)
    val CANDIDATES_V1 = listOf(0x02)
    const val XM6_NTFY_INQUIRED = 0x19
    const val MAX_AMBIENT_LEVEL = 20
    const val DEFAULT_NC_VALUE = 0x02

    val EQ_BAND_LABELS = listOf("400 Hz", "1 kHz", "2.5 kHz", "6.3 kHz", "16 kHz", "Clear Bass")

    fun buildProtocolInfoGet(): ByteArray = byteArrayOf(CONNECT_GET_PROTOCOL_INFO.toByte(), 0x00)

    fun buildBatteryGet(type: Int): ByteArray = byteArrayOf(COMMON_GET_BATTERY_LEVEL.toByte(), type.toByte())

    fun buildNcAsmGet(inquiredType: Int): ByteArray = byteArrayOf(NCASM_GET_PARAM.toByte(), inquiredType.toByte())

    fun buildEqGet(): ByteArray = byteArrayOf(EQEBB_GET_PARAM.toByte(), EQEBB_INQUIRED_PRESET_AND_BANDS.toByte())

    fun buildSpeakToChatGet(): ByteArray = byteArrayOf(SYSTEM_GET_EXT_PARAM.toByte(), SMART_TALKING_MODE_TYPE.toByte())

    fun buildSpeakToChatSet(enabled: Boolean): ByteArray = byteArrayOf(
        SYSTEM_SET_EXT_PARAM.toByte(),
        SMART_TALKING_MODE_TYPE.toByte(),
        if (enabled) 0x01 else 0x00,
    )

    fun buildPowerOff(): ByteArray = byteArrayOf(POWER_SET_STATUS.toByte(), 0x01, 0x00)

    fun buildEqSet(preset: SonyEqPreset, bands: List<Int>): ByteArray {
        val out = ByteArray(4 + bands.size)
        out[0] = EQEBB_SET_PARAM.toByte()
        out[1] = EQEBB_INQUIRED_PRESET_AND_BANDS.toByte()
        out[2] = preset.code.toByte()
        out[3] = bands.size.toByte()
        bands.forEachIndexed { i, v -> out[4 + i] = (v.coerceIn(-10, 10) + 10).toByte() }
        return out
    }

    /**
     * Builds an NCASM_SET payload for the inquired type this headset answered with
     * (`0x17`, `0x15`, `0x22` on v2; `0x02` on v1).
     */
    fun buildNcAsmSet(
        inquiredType: Int,
        mode: SonyAncMode,
        ambientLevel: Int,
        focusOnVoice: Boolean,
        ncValue: Int = DEFAULT_NC_VALUE,
    ): ByteArray {
        val on = if (mode != SonyAncMode.OFF) 1 else 0
        val ambient = if (mode == SonyAncMode.AMBIENT) 1 else 0
        val level = ambientLevel.coerceIn(0, MAX_AMBIENT_LEVEL)
        val voice = if (focusOnVoice) 1 else 0
        return when (inquiredType) {
            0x02 -> if (mode == SonyAncMode.AMBIENT) {
                byteArrayOf(NCASM_SET_PARAM.toByte(), 0x02, 0x11, 0x01, 0x00, 0x01, voice.toByte(), maxOf(1, level).toByte())
            } else {
                byteArrayOf(
                    NCASM_SET_PARAM.toByte(),
                    0x02,
                    if (on == 1) 0x11 else 0x00,
                    0x01,
                    if (on == 1) 0x02 else 0x00,
                    0x01,
                    voice.toByte(),
                    0x00,
                )
            }
            0x15 -> byteArrayOf(
                NCASM_SET_PARAM.toByte(),
                0x15,
                0x01,
                on.toByte(),
                ambient.toByte(),
                ncValue.toByte(),
                voice.toByte(),
                level.toByte(),
            )
            0x22 -> byteArrayOf(
                NCASM_SET_PARAM.toByte(),
                0x22,
                0x01,
                on.toByte(),
                voice.toByte(),
                level.toByte(),
            )
            else -> byteArrayOf(
                NCASM_SET_PARAM.toByte(),
                0x17,
                0x01,
                on.toByte(),
                ambient.toByte(),
                voice.toByte(),
                level.toByte(),
            )
        }
    }

    /** Parses NCASM RET (`0x67`) or NTFY (`0x69`) for v1 `0x02` and v2 `0x17`, `0x19`, `0x15`, `0x22`. */
    fun parseNcAsm(payload: ByteArray, expectInquired: Int? = null): SonyAncState? {
        if (payload.size < 2) return null
        val cmd = payload.u(0)
        if (cmd != NCASM_RET_PARAM && cmd != NCASM_NTFY_PARAM) return null
        val inquired = payload.u(1)
        if (expectInquired != null && inquired != expectInquired && !(expectInquired == 0x17 && inquired == XM6_NTFY_INQUIRED)) {
            return null
        }
        return when (inquired) {
            0x02 -> {
                if (payload.size < 8) return null
                val on = payload.u(2) == 0x01
                val ncVal = payload.u(4)
                val mode = when {
                    !on -> SonyAncMode.OFF
                    ncVal == 0x00 -> SonyAncMode.AMBIENT
                    else -> SonyAncMode.ANC
                }
                SonyAncState(
                    inquiredType = 0x02,
                    mode = mode,
                    ambientLevel = payload.u(7).coerceIn(0, MAX_AMBIENT_LEVEL),
                    focusOnVoice = payload.u(6) == 0x01,
                    available = listOf(SonyAncMode.ANC, SonyAncMode.AMBIENT, SonyAncMode.OFF),
                    ncValue = payload.u(3),
                )
            }
            XM6_NTFY_INQUIRED -> {
                if (payload.size < 9) return null
                val mode = when {
                    payload.u(3) != 0x01 -> SonyAncMode.OFF
                    payload.u(4) == 0x01 -> SonyAncMode.AMBIENT
                    else -> SonyAncMode.ANC
                }
                SonyAncState(
                    inquiredType = 0x17,
                    mode = mode,
                    ambientLevel = payload.u(6).coerceIn(0, MAX_AMBIENT_LEVEL),
                    focusOnVoice = payload.u(5) == 0x01,
                    available = listOf(SonyAncMode.ANC, SonyAncMode.AMBIENT, SonyAncMode.OFF),
                )
            }
            0x17, 0x15, 0x22 -> {
                if (payload.size < 6) return null
                val on = payload.u(3) == 0x01
                val ambient = if (inquired == 0x22) true else (payload.size > 4 && payload.u(4) == 0x01)
                val mode = when {
                    !on -> SonyAncMode.OFF
                    ambient -> SonyAncMode.AMBIENT
                    else -> SonyAncMode.ANC
                }
                val available = if (inquired == 0x22) {
                    listOf(SonyAncMode.AMBIENT, SonyAncMode.OFF)
                } else {
                    listOf(SonyAncMode.ANC, SonyAncMode.AMBIENT, SonyAncMode.OFF)
                }
                SonyAncState(
                    inquiredType = inquired,
                    mode = mode,
                    ambientLevel = payload.u(payload.lastIndex).coerceIn(0, MAX_AMBIENT_LEVEL),
                    focusOnVoice = payload.u(payload.lastIndex - 1) == 0x01,
                    available = available,
                    ncValue = if (inquired == 0x15 && payload.size > 7) payload.u(5) else null,
                )
            }
            else -> null
        }
    }

    fun parseBattery(payload: ByteArray): SonyBattery? {
        if (payload.size < 3) return null
        val cmd = payload.u(0)
        if (cmd != COMMON_RET_BATTERY_LEVEL && cmd != COMMON_NTFY_BATTERY_LEVEL) return null
        return when (payload.u(1)) {
            BATTERY_SINGLE -> {
                val pct = payload.u(2)
                if (pct !in 0..100) null else SonyBattery(percent = pct)
            }
            BATTERY_LEFT_RIGHT -> {
                if (payload.size < 6) return null
                val left = payload.u(2).takeIf { it in 0..100 }
                val right = payload.u(4).takeIf { it in 0..100 }
                val pct = listOfNotNull(left, right).minOrNull() ?: return null
                SonyBattery(percent = pct, left = left, right = right)
            }
            BATTERY_CRADLE -> {
                val cradle = payload.u(2).takeIf { it in 0..100 } ?: return null
                SonyBattery(percent = cradle, cradle = cradle)
            }
            else -> null
        }
    }

    fun parseEq(payload: ByteArray): SonyEqState? {
        if (payload.size < 4) return null
        val cmd = payload.u(0)
        if (cmd != EQEBB_RET_PARAM && cmd != EQEBB_NTFY_PARAM) return null
        if (payload.u(1) != EQEBB_INQUIRED_PRESET_AND_BANDS) return null
        val preset = SonyEqPreset.fromCode(payload.u(2)) ?: SonyEqPreset.CUSTOM
        val count = payload.u(3)
        if (payload.size < 4 + count) return null
        val bands = List(count) { i -> (payload.u(4 + i) - 10).coerceIn(-10, 10) }
        return SonyEqState(preset = preset, bands = bands)
    }

    fun parseSpeakToChat(payload: ByteArray): Boolean? {
        if (payload.size < 3) return null
        val cmd = payload.u(0)
        if (cmd != SYSTEM_RET_EXT_PARAM && cmd != SYSTEM_NTFY_EXT_PARAM) return null
        if (payload.u(1) != SMART_TALKING_MODE_TYPE) return null
        return payload.u(2) == 0x01
    }

    private fun ByteArray.u(i: Int): Int = this[i].toInt() and 0xFF
}
