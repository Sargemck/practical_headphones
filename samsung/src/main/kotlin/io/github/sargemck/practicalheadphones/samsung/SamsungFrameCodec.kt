package io.github.sargemck.practicalheadphones.samsung

/**
 * One decoded Samsung Galaxy Buds SPP frame:
 * `FD <header: 2B LE> <msgId: 1B> <payload> <crc16: 2B LE> DD`.
 */
data class SamsungFrame(
    val msgId: Int,
    val payload: ByteArray = ByteArray(0),
    val isRequest: Boolean = false,
) {
    override fun equals(other: Any?): Boolean =
        other is SamsungFrame && msgId == other.msgId && isRequest == other.isRequest && payload.contentEquals(other.payload)

    override fun hashCode(): Int = 31 * (31 * msgId + isRequest.hashCode()) + payload.contentHashCode()
}

/**
 * Samsung Galaxy Buds SPP framing & CRC16-CCITT/XMODEM codec (from `omarchy-headphones/samsung-bridge`, MIT).
 */
object SamsungFrameCodec {
    const val SOF = 0xFD
    const val EOF = 0xDD
    private const val REQUEST_BIT = 0x1000
    private const val SIZE_MASK = 0x3FF

    fun crc16(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if ((crc and 0x8000) != 0) {
                    ((crc shl 1) xor 0x1021) and 0xFFFF
                } else {
                    (crc shl 1) and 0xFFFF
                }
            }
        }
        return crc and 0xFFFF
    }

    fun encode(msgId: Int, payload: ByteArray = ByteArray(0), request: Boolean = true): ByteArray {
        val size = 1 + payload.size + 2
        val header = (size and SIZE_MASK) or (if (request) REQUEST_BIT else 0)
        val crcInput = ByteArray(1 + payload.size)
        crcInput[0] = msgId.toByte()
        payload.copyInto(crcInput, 1)
        val crc = crc16(crcInput)

        val out = ByteArray(1 + 2 + size + 1)
        out[0] = SOF.toByte()
        out[1] = (header and 0xFF).toByte()
        out[2] = ((header ushr 8) and 0xFF).toByte()
        out[3] = msgId.toByte()
        payload.copyInto(out, 4)
        out[4 + payload.size] = (crc and 0xFF).toByte()
        out[5 + payload.size] = ((crc ushr 8) and 0xFF).toByte()
        out[6 + payload.size] = EOF.toByte()
        return out
    }

    fun decode(raw: ByteArray): SamsungFrame? {
        if (raw.size < 7) return null
        if ((raw.first().toInt() and 0xFF) != SOF || (raw.last().toInt() and 0xFF) != EOF) return null
        val header = (raw[1].toInt() and 0xFF) or ((raw[2].toInt() and 0xFF) shl 8)
        val size = header and SIZE_MASK
        if (size < 3 || raw.size != 1 + 2 + size + 1) return null
        val msgId = raw[3].toInt() and 0xFF
        val payload = raw.copyOfRange(4, raw.size - 3)
        val expectedCrc = crc16(byteArrayOf(msgId.toByte()) + payload)
        val actualCrc = (raw[raw.size - 3].toInt() and 0xFF) or ((raw[raw.size - 2].toInt() and 0xFF) shl 8)
        if (actualCrc != expectedCrc) return null
        return SamsungFrame(msgId = msgId, payload = payload, isRequest = (header and REQUEST_BIT) != 0)
    }
}

/** Stateful stream framer handling arbitrarily chunked Galaxy Buds RFCOMM streams. */
class SamsungFrameParser {
    private val buffer = ArrayList<Byte>()

    fun feed(chunk: ByteArray): List<SamsungFrame> {
        for (b in chunk) buffer.add(b)
        val out = mutableListOf<SamsungFrame>()
        while (buffer.size >= 7) {
            val start = buffer.indexOf(SamsungFrameCodec.SOF.toByte())
            if (start < 0) {
                buffer.clear()
                break
            }
            if (start > 0) buffer.subList(0, start).clear()
            if (buffer.size < 7) break
            val header = (buffer[1].toInt() and 0xFF) or ((buffer[2].toInt() and 0xFF) shl 8)
            val size = header and 0x3FF
            val total = 1 + 2 + size + 1
            if (size < 3 || total > 1024) {
                buffer.removeAt(0)
                continue
            }
            if (buffer.size < total) break
            val raw = ByteArray(total) { buffer[it] }
            buffer.subList(0, total).clear()
            SamsungFrameCodec.decode(raw)?.let(out::add)
        }
        return out
    }
}
