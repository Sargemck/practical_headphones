package io.github.sargemck.practicalheadphones.sony

/**
 * One decoded Sony MDR frame.
 * Wire layout: `3E <escaped(dataType, seq, len:4B BE, payload, checksum)> 3C`.
 */
data class SonyFrame(
    val dataType: Int,
    val seq: Int,
    val payload: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean =
        other is SonyFrame && dataType == other.dataType && seq == other.seq && payload.contentEquals(other.payload)

    override fun hashCode(): Int = 31 * (31 * dataType + seq) + payload.contentHashCode()
}

/**
 * Sony MDR v1/v2 framing codec (from `SonyHeadphonesClient` & `omarchy-headphones/sony-bridge`, MIT).
 */
object SonyFrameCodec {
    const val HDR = 0x3E
    const val TRL = 0x3C
    const val ESC = 0x3D
    const val MASK = 0xEF

    const val DATA_MDR = 0x0C
    const val DATA_MDR_NO2 = 0x0E
    const val ACK = 0x01

    fun escape(data: ByteArray): ByteArray {
        val out = ArrayList<Byte>(data.size + 8)
        for (b in data) {
            val u = b.toInt() and 0xFF
            if (u == HDR || u == TRL || u == ESC) {
                out.add(ESC.toByte())
                out.add((u and MASK).toByte())
            } else {
                out.add(b)
            }
        }
        return out.toByteArray()
    }

    fun unescape(data: ByteArray): ByteArray {
        val out = ArrayList<Byte>(data.size)
        var i = 0
        while (i < data.size) {
            val u = data[i].toInt() and 0xFF
            if (u == ESC) {
                i++
                if (i >= data.size) break
                out.add(((data[i].toInt() and 0xFF) or 0x10).toByte())
            } else {
                out.add(data[i])
            }
            i++
        }
        return out.toByteArray()
    }

    fun encode(frame: SonyFrame): ByteArray {
        val n = frame.payload.size
        val body = ByteArray(6 + n)
        body[0] = frame.dataType.toByte()
        body[1] = frame.seq.toByte()
        body[2] = ((n ushr 24) and 0xFF).toByte()
        body[3] = ((n ushr 16) and 0xFF).toByte()
        body[4] = ((n ushr 8) and 0xFF).toByte()
        body[5] = (n and 0xFF).toByte()
        frame.payload.copyInto(body, 6)
        val checksum = (body.sumOf { it.toInt() and 0xFF } and 0xFF).toByte()
        val stuffed = escape(body + checksum)
        return byteArrayOf(HDR.toByte()) + stuffed + byteArrayOf(TRL.toByte())
    }

    /** Decodes a complete `3E .. 3C` frame, returning null if malformed or checksum fails. */
    fun decode(raw: ByteArray): SonyFrame? {
        if (raw.size < 9) return null
        if ((raw.first().toInt() and 0xFF) != HDR || (raw.last().toInt() and 0xFF) != TRL) return null
        val msg = unescape(raw.copyOfRange(1, raw.size - 1))
        if (msg.size < 7) return null
        val dataType = msg[0].toInt() and 0xFF
        val seq = msg[1].toInt() and 0xFF
        val length = ((msg[2].toInt() and 0xFF) shl 24) or
            ((msg[3].toInt() and 0xFF) shl 16) or
            ((msg[4].toInt() and 0xFF) shl 8) or
            (msg[5].toInt() and 0xFF)
        if (length < 0 || msg.size != 6 + length + 1) return null
        var sum = 0
        for (i in 0 until 6 + length) sum = (sum + (msg[i].toInt() and 0xFF)) and 0xFF
        val checksum = msg[6 + length].toInt() and 0xFF
        if (checksum != sum) return null
        return SonyFrame(dataType, seq, msg.copyOfRange(6, 6 + length))
    }
}

/** Stateful stream framer that handles arbitrarily chunked RFCOMM reads. */
class SonyFrameParser {
    private val buffer = ArrayList<Byte>()

    fun feed(chunk: ByteArray): List<SonyFrame> {
        for (b in chunk) buffer.add(b)
        val out = mutableListOf<SonyFrame>()
        while (true) {
            val start = buffer.indexOf(SonyFrameCodec.HDR.toByte())
            if (start < 0) {
                buffer.clear()
                break
            }
            if (start > 0) buffer.subList(0, start).clear()
            val end = buffer.indexOf(SonyFrameCodec.TRL.toByte())
            if (end < 0) {
                if (buffer.size > MAX_BUFFER_BYTES) {
                    buffer.subList(0, buffer.size - MAX_BUFFER_BYTES).clear()
                }
                break
            }
            // Interior HDR (0x3E) bytes are always escaped (0x3D 0x2E), so any later 0x3E before
            // TRL means the earlier 0x3E belonged to a truncated/corrupted frame.
            var realStart = 0
            for (i in 1 until end) {
                if (buffer[i] == SonyFrameCodec.HDR.toByte()) realStart = i
            }
            if (realStart > 0) {
                buffer.subList(0, realStart).clear()
                continue
            }
            val raw = ByteArray(end + 1) { buffer[it] }
            buffer.subList(0, end + 1).clear()
            SonyFrameCodec.decode(raw)?.let(out::add)
        }
        return out
    }

    private companion object {
        const val MAX_BUFFER_BYTES = 4096
    }
}
