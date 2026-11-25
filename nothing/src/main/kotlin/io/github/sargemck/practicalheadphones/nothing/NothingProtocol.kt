package io.github.sargemck.practicalheadphones.nothing

enum class NothingAncMode(val code: Int, val label: String) {
    ANC_ADAPTIVE(0x04, "Adaptive ANC"),
    ANC_HIGH(0x01, "High ANC"),
    ANC_MID(0x02, "Mid ANC"),
    ANC_LOW(0x03, "Low ANC"),
    TRANSPARENCY(0x07, "Transparency"),
    OFF(0x05, "Off");

    companion object {
        fun fromWire(code: Int): NothingAncMode? = when (code) {
            0x00, 0x05 -> OFF
            else -> entries.firstOrNull { it.code == code }
        }
    }
}

data class NothingBattery(
    val percent: Int,
    val left: Int? = null,
    val right: Int? = null,
    val casePct: Int? = null,
)

/**
 * Nothing X / NT Link protocol constants & parsers (from `omarchy-headphones/nothing-bridge`, MIT).
 */
object NothingProtocol {
    const val UUID_NT_LINK = "aeac4a03-dff5-498f-843a-34487cf133eb"

    const val CMD_DEVICE_INFO = 0x06
    const val CMD_BATTERY = 0x07
    const val CMD_ANC_SET = 0x0F
    const val CMD_ANC_GET = 0x1E

    const val DIR_GET = 0xC0
    const val DIR_SET = 0xF0
    const val DIR_ANSWER = 0x40
    const val DIR_ACK = 0x70
    const val DIR_EVENT = 0xE0

    fun channelsForName(name: String?): List<Int> {
        val n = name?.trim()?.lowercase()?.removePrefix("nothing ").orEmpty()
        return when {
            "cmf headphone pro" in n -> listOf(28, 15)
            "cmf buds 2" in n || n == "buds 2" -> listOf(16, 15)
            else -> listOf(15, 28, 16)
        }
    }

    fun buildAncSetPayload(mode: NothingAncMode): ByteArray =
        byteArrayOf(0x01, mode.code.toByte(), 0x00)

    fun parseAnc(payload: ByteArray): NothingAncMode? {
        if (payload.size < 2) return null
        return NothingAncMode.fromWire(payload[1].toInt() and 0xFF)
    }

    fun parseBattery(payload: ByteArray): NothingBattery? {
        if (payload.isEmpty()) return null
        val count = payload[0].toInt() and 0xFF
        var left: Int? = null
        var right: Int? = null
        var casePct: Int? = null
        var headset: Int? = null
        for (i in 0 until count) {
            val offset = 1 + i * 2
            if (offset + 1 >= payload.size) break
            val comp = payload[offset].toInt() and 0xFF
            val level = payload[offset + 1].toInt() and 0x7F
            if (level !in 0..100) continue
            when (comp) {
                2 -> left = level
                3 -> right = level
                4 -> if (level > 0) casePct = level
                6 -> headset = level
            }
        }
        val aggregate = headset ?: listOfNotNull(left, right).minOrNull() ?: casePct ?: return null
        return NothingBattery(percent = aggregate, left = left, right = right, casePct = casePct)
    }
}
