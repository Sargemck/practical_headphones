package io.github.sargemck.practicalheadphones.samsung

enum class SamsungNoiseMode(val code: Int, val label: String) {
    OFF(0x00, "Off"),
    ANC(0x01, "Noise cancelling"),
    AMBIENT(0x02, "Ambient sound");

    companion object {
        fun fromCode(code: Int): SamsungNoiseMode? = entries.firstOrNull { it.code == code }
    }
}

enum class SamsungEqPreset(val code: Int, val label: String) {
    NORMAL(0x00, "Normal"),
    BASS_BOOST(0x01, "Bass boost"),
    SOFT(0x02, "Soft"),
    DYNAMIC(0x03, "Dynamic"),
    CLEAR(0x04, "Clear"),
    TREBLE_BOOST(0x05, "Treble boost");

    companion object {
        fun fromCode(code: Int): SamsungEqPreset? = entries.firstOrNull { it.code == code }
    }
}

data class SamsungStatus(
    val leftBattery: Int?,
    val rightBattery: Int?,
    val caseBattery: Int?,
    val noiseMode: SamsungNoiseMode,
    val eqPreset: SamsungEqPreset = SamsungEqPreset.NORMAL,
) {
    val aggregateBattery: Int?
        get() = listOfNotNull(leftBattery, rightBattery).minOrNull() ?: caseBattery
}

/**
 * Samsung Galaxy Buds SPP protocol constants & parsers (from `omarchy-headphones/samsung-bridge`, MIT).
 */
object SamsungProtocol {
    const val UUID_SPP_NEW = "2e73a4ad-332d-41fc-90e2-16bef06523f2"
    const val UUID_SPP = "00001101-0000-1000-8000-00805f9b34fb"

    const val MSG_STATUS_UPDATED = 0x60
    const val MSG_EXTENDED_STATUS_UPDATED = 0x61
    const val MSG_NOISE_CONTROLS_UPDATE = 0x77
    const val MSG_NOISE_CONTROLS = 0x78
    const val MSG_EQUALIZER = 0x86
    const val MSG_MANAGER_INFO = 0x88

    fun buildManagerInfo(): ByteArray = byteArrayOf(1, 1, 34)

    fun buildNoiseControlSet(mode: SamsungNoiseMode): ByteArray = byteArrayOf(mode.code.toByte())

    fun buildEqualizerSet(preset: SamsungEqPreset): ByteArray = byteArrayOf(preset.code.toByte())

    /** Parses `EXTENDED_STATUS_UPDATED` (`0x61`). */
    fun parseExtendedStatus(payload: ByteArray): SamsungStatus? {
        if (payload.size < 13) return null
        val mode = SamsungNoiseMode.fromCode(payload[12].toInt() and 0xFF) ?: return null
        val eq = payload.getOrNull(9)?.let { SamsungEqPreset.fromCode(it.toInt() and 0xFF) } ?: SamsungEqPreset.NORMAL
        return SamsungStatus(
            leftBattery = pct(payload, 2),
            rightBattery = pct(payload, 3),
            caseBattery = pct(payload, 7),
            noiseMode = mode,
            eqPreset = eq,
        )
    }

    fun parseNoiseControlUpdate(payload: ByteArray): SamsungNoiseMode? =
        payload.firstOrNull()?.let { SamsungNoiseMode.fromCode(it.toInt() and 0xFF) }

    private fun pct(payload: ByteArray, index: Int): Int? {
        if (index >= payload.size) return null
        val v = payload[index].toInt() and 0xFF
        return v.takeIf { it in 0..100 }
    }
}
