package io.github.sargemck.practicalheadphones.soundcore

/**
 * One decoded Soundcore packet: `[prefix: 5B] [cmdGroup: 1B] [cmdId: 1B] [totalLen: 2B LE] [payload...] [checksum: 1B]`.
 */
data class SoundcoreFrame(
    val cmdGroup: Int,
    val cmdId: Int,
    val payload: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean =
        other is SoundcoreFrame && cmdGroup == other.cmdGroup && cmdId == other.cmdId && payload.contentEquals(other.payload)

    override fun hashCode(): Int = 31 * (31 * cmdGroup + cmdId) + payload.contentHashCode()
}

/**
 * Soundcore RFCOMM framing codec (from `soundcorebridge` & `omarchy-headphones/soundcore-bridge`, MIT).
 */
object SoundcoreFrameCodec {
    val TX_PREFIX = byteArrayOf(0x08, 0xEE.toByte(), 0x00, 0x00, 0x00)
    val RX_PREFIX = byteArrayOf(0x09, 0xFF.toByte(), 0x00, 0x00, 0x01)
    private const val MIN_PACKET_LEN = 10 // 5 prefix + 2 cmd + 2 len + 1 checksum

    fun encodeTx(cmdGroup: Int, cmdId: Int, payload: ByteArray = ByteArray(0)): ByteArray =
        encode(TX_PREFIX, cmdGroup, cmdId, payload)

    fun encodeRx(cmdGroup: Int, cmdId: Int, payload: ByteArray = ByteArray(0)): ByteArray =
        encode(RX_PREFIX, cmdGroup, cmdId, payload)

    private fun encode(prefix: ByteArray, cmdGroup: Int, cmdId: Int, payload: ByteArray): ByteArray {
        val totalLen = MIN_PACKET_LEN + payload.size
        val out = ByteArray(totalLen)
        prefix.copyInto(out, 0)
        out[5] = cmdGroup.toByte()
        out[6] = cmdId.toByte()
        out[7] = (totalLen and 0xFF).toByte()
        out[8] = ((totalLen ushr 8) and 0xFF).toByte()
        payload.copyInto(out, 9)
        var sum = 0
        for (i in 0 until totalLen - 1) sum = (sum + (out[i].toInt() and 0xFF)) and 0xFF
        out[totalLen - 1] = sum.toByte()
        return out
    }

    fun decode(raw: ByteArray, expectTx: Boolean = false): SoundcoreFrame? {
        if (raw.size < MIN_PACKET_LEN) return null
        val prefix = if (expectTx) TX_PREFIX else RX_PREFIX
        for (i in prefix.indices) if (raw[i] != prefix[i]) return null
        val totalLen = (raw[7].toInt() and 0xFF) or ((raw[8].toInt() and 0xFF) shl 8)
        if (totalLen != raw.size || totalLen < MIN_PACKET_LEN) return null
        var sum = 0
        for (i in 0 until totalLen - 1) sum = (sum + (raw[i].toInt() and 0xFF)) and 0xFF
        if ((raw[totalLen - 1].toInt() and 0xFF) != sum) return null
        return SoundcoreFrame(
            cmdGroup = raw[5].toInt() and 0xFF,
            cmdId = raw[6].toInt() and 0xFF,
            payload = raw.copyOfRange(9, totalLen - 1),
        )
    }
}

/** Stateful stream framer handling arbitrarily chunked Soundcore RFCOMM streams. */
class SoundcoreFrameParser(private val expectTx: Boolean = false) {
    private val prefix = if (expectTx) SoundcoreFrameCodec.TX_PREFIX else SoundcoreFrameCodec.RX_PREFIX
    private val buffer = ArrayList<Byte>()

    fun feed(chunk: ByteArray): List<SoundcoreFrame> {
        for (b in chunk) buffer.add(b)
        val out = mutableListOf<SoundcoreFrame>()
        while (buffer.size >= 10) {
            val idx = findPrefix()
            if (idx < 0) {
                // Keep at most prefix.size - 1 tail bytes in case prefix splits across chunks
                val keep = minOf(buffer.size, prefix.size - 1)
                if (buffer.size > keep) buffer.subList(0, buffer.size - keep).clear()
                break
            }
            if (idx > 0) buffer.subList(0, idx).clear()
            if (buffer.size < 10) break
            val totalLen = (buffer[7].toInt() and 0xFF) or ((buffer[8].toInt() and 0xFF) shl 8)
            if (totalLen < 10 || totalLen > 2048) {
                buffer.removeAt(0)
                continue
            }
            if (buffer.size < totalLen) break
            val raw = ByteArray(totalLen) { buffer[it] }
            buffer.subList(0, totalLen).clear()
            SoundcoreFrameCodec.decode(raw, expectTx)?.let(out::add)
        }
        return out
    }

    private fun findPrefix(): Int {
        outer@ for (i in 0..buffer.size - prefix.size) {
            for (j in prefix.indices) {
                if (buffer[i + j] != prefix[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
