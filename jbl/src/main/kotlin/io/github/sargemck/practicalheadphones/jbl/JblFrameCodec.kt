package io.github.sargemck.practicalheadphones.jbl

/**
 * One decoded JBL BLE GATT frame: `AA <cmd: u8> <len: u8> <payload: len B>`.
 */
data class JblFrame(
    val cmd: Int,
    val payload: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean =
        other is JblFrame && cmd == other.cmd && payload.contentEquals(other.payload)

    override fun hashCode(): Int = 31 * cmd + payload.contentHashCode()
}

/**
 * JBL BLE GATT frame codec (from `omarchy-headphones/jbl-bridge` & `PROTOCOL.md`, MIT).
 */
object JblFrameCodec {
    const val MAGIC = 0xAA

    fun encode(cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArray(3 + payload.size)
        out[0] = MAGIC.toByte()
        out[1] = (cmd and 0xFF).toByte()
        out[2] = (payload.size and 0xFF).toByte()
        payload.copyInto(out, 3)
        return out
    }

    fun decode(raw: ByteArray): JblFrame? {
        if (raw.size < 3 || (raw[0].toInt() and 0xFF) != MAGIC) return null
        val cmd = raw[1].toInt() and 0xFF
        val len = raw[2].toInt() and 0xFF
        if (raw.size != 3 + len) return null
        return JblFrame(cmd = cmd, payload = raw.copyOfRange(3, 3 + len))
    }
}

/** Stream/characteristic parser handling coalesced or chunked GATT notifications. */
class JblFrameParser {
    private val buffer = ArrayList<Byte>()

    fun feed(chunk: ByteArray): List<JblFrame> {
        for (b in chunk) buffer.add(b)
        val out = mutableListOf<JblFrame>()
        while (buffer.size >= 3) {
            val start = buffer.indexOf(JblFrameCodec.MAGIC.toByte())
            if (start < 0) {
                buffer.clear()
                break
            }
            if (start > 0) buffer.subList(0, start).clear()
            if (buffer.size < 3) break
            val len = buffer[2].toInt() and 0xFF
            val total = 3 + len
            if (len > 128) {
                buffer.removeAt(0)
                continue
            }
            if (buffer.size < total) break
            val raw = ByteArray(total) { buffer[it] }
            val decoded = JblFrameCodec.decode(raw)
            if (decoded != null) {
                buffer.subList(0, total).clear()
                out.add(decoded)
            } else {
                buffer.removeAt(0)
            }
        }
        return out
    }
}
