package io.github.sargemck.practicalheadphones.jbl

enum class JblMode(val label: String) {
    ANC("Noise cancelling"),
    AMBIENT("Ambient Aware"),
    TALKTHRU("TalkThru"),
    OFF("Off"),
}

data class JblBattery(
    val percent: Int,
    val left: Int? = null,
    val right: Int? = null,
    val casePct: Int? = null,
)

/**
 * JBL BLE GATT protocol constants, builders, and parsers (from `omarchy-headphones/jbl-bridge` & `PROTOCOL.md`, MIT).
 */
object JblProtocol {
    const val GATT_SERVICE_UUID = "65786365-6c70-6f69-6e74-2e636f6d0000"
    const val GATT_NOTIFY_CHAR_UUID = "65786365-6c70-6f69-6e74-2e636f6d0001"
    const val GATT_WRITE_CHAR_UUID = "65786365-6c70-6f69-6e74-2e636f6d0002"
    const val FAST_PAIR_RFCOMM_UUID = "df21fe2c-2515-4fdb-8886-f12c4d67927c"
    const val JBL_VENDOR_RFCOMM_UUID = "8a482a08-5507-42ac-b673-a88df48b3fc7"

    const val CMD_BATTERY = 0x25
    const val CMD_MODES = 0x91
    const val CMD_NOTIFY_ENABLE = 0x9B

    const val OP_MODE_SET = 0x10
    const val OP_MODE_GET = 0x11
    const val OP_MODE_REPORT = 0x12

    fun buildEnableNotifications(): ByteArray =
        JblFrameCodec.encode(CMD_NOTIFY_ENABLE, byteArrayOf(0x01, 0x01))

    fun buildBatteryGet(): ByteArray =
        JblFrameCodec.encode(CMD_BATTERY, byteArrayOf(0x00))

    fun buildModeGet(): ByteArray =
        JblFrameCodec.encode(CMD_MODES, byteArrayOf(OP_MODE_GET.toByte()))

    fun buildModeSet(mode: JblMode): ByteArray {
        val anc: Byte = if (mode == JblMode.ANC) 1 else 0
        val amb: Byte = if (mode == JblMode.AMBIENT) 1 else 0
        val talk: Byte = if (mode == JblMode.TALKTHRU) 1 else 0
        return JblFrameCodec.encode(
            CMD_MODES,
            byteArrayOf(OP_MODE_SET.toByte(), 0x01, anc, 0x02, amb, 0x03, talk),
        )
    }

    /** Parses `AA 91 07 12 01 <anc> 02 <ambient> 03 <talkthru>`. */
    fun parseModeReport(frame: JblFrame): JblMode? {
        val p = frame.payload
        if (frame.cmd != CMD_MODES || p.size < 7) return null
        if ((p[0].toInt() and 0xFF) != OP_MODE_REPORT) return null
        var anc = false
        var amb = false
        var talk = false
        for (i in 1..p.size - 2 step 2) {
            val slot = p[i].toInt() and 0xFF
            val on = (p[i + 1].toInt() and 0xFF) != 0
            when (slot) {
                1 -> anc = on
                2 -> amb = on
                3 -> talk = on
            }
        }
        return when {
            talk -> JblMode.TALKTHRU
            amb -> JblMode.AMBIENT
            anc -> JblMode.ANC
            else -> JblMode.OFF
        }
    }

    /**
     * Parses `AA 25 <len> 01 00 00 <L> <R> 00 <Case> ...` (frame bytes 6, 7, 9 -> payload[3, 4, 6]).
     * `0x7F` / `0xFF` means docked/no reading per Fast Pair battery spec.
     */
    fun parseBattery(frame: JblFrame): JblBattery? {
        val p = frame.payload
        if (frame.cmd != CMD_BATTERY || p.size < 5) return null
        if ((p[0].toInt() and 0xFF) != 0x01) return null
        fun pct(idx: Int): Int? {
            if (idx >= p.size) return null
            val raw = p[idx].toInt() and 0x7F
            return raw.takeIf { it in 0..100 }
        }
        val left = pct(3)
        val right = pct(4)
        val casePct = pct(6)?.takeIf { it > 0 }
        val aggregate = listOfNotNull(left, right).minOrNull() ?: casePct ?: return null
        return JblBattery(percent = aggregate, left = left, right = right, casePct = casePct)
    }

    /**
     * Parses Google Fast Pair Message Stream frames (`group u8 | code u8 | len u16 BE | payload`)
     * to extract the rotating 6-byte BLE address (`03 02 00 06 <6B>`) if present.
     */
    fun parseFastPairBleAddress(stream: ByteArray): String? {
        var pos = 0
        while (pos + 4 <= stream.size) {
            val group = stream[pos].toInt() and 0xFF
            val code = stream[pos + 1].toInt() and 0xFF
            val len = ((stream[pos + 2].toInt() and 0xFF) shl 8) or (stream[pos + 3].toInt() and 0xFF)
            if (pos + 4 + len > stream.size) break
            if (group == 0x03 && code == 0x02 && len == 6) {
                return (0 until 6).joinToString(":") { i -> "%02X".format(stream[pos + 4 + i].toInt() and 0xFF) }
            }
            pos += 4 + len
        }
        return null
    }
}
