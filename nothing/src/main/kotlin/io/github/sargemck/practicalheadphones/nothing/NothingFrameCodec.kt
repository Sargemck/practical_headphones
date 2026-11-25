package io.github.sargemck.practicalheadphones.nothing

/**
 * One decoded Nothing NT Link frame:
 * `55 60 01 <wire: u16 LE> <len: u16 LE> <op: u8> <payload> <crc16: u16 LE>`.
 */
data class NothingFrame(
    val command: Int,
    val direction: Int,
    val payload: ByteArray = ByteArray(0),
    val operation: Int = 1,
) {
    override fun equals(other: Any?): Boolean =
        other is NothingFrame &&
            command == other.command &&
            direction == other.direction &&
            operation == other.operation &&
            payload.contentEquals(other.payload)

    override fun hashCode(): Int =
        31 * (31 * (31 * command + direction) + operation) + payload.contentHashCode()
}

/**
 * Nothing X / NT Link framing & CRC-16/MODBUS codec (from `omarchy-headphones/nothing-bridge`, MIT).
 */
object NothingFrameCodec {
    const val SOF = 0x55
    const val CTRL_WITH_CRC = 0x0160

    fun crc16Modbus(data: ByteArray, length: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in 0 until length) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 1) != 0) (crc ushr 1) xor 0xA001 else (crc ushr 1)
            }
        }
        return crc and 0xFFFF
    }

    fun encode(command: Int, direction: Int, payload: ByteArray = ByteArray(0), operation: Int = 1): ByteArray {
        val wire = (command and 0xFF) or ((direction and 0xFF) shl 8)
        val bodyLen = 8 + payload.size
        val out = ByteArray(bodyLen + 2)
        out[0] = SOF.toByte()
        out[1] = (CTRL_WITH_CRC and 0xFF).toByte()
        out[2] = ((CTRL_WITH_CRC ushr 8) and 0xFF).toByte()
        out[3] = (wire and 0xFF).toByte()
        out[4] = ((wire ushr 8) and 0xFF).toByte()
        out[5] = (payload.size and 0xFF).toByte()
        out[6] = ((payload.size ushr 8) and 0xFF).toByte()
        out[7] = (operation and 0xFF).toByte()
        payload.copyInto(out, 8)
        val crc = crc16Modbus(out, bodyLen)
        out[bodyLen] = (crc and 0xFF).toByte()
        out[bodyLen + 1] = ((crc ushr 8) and 0xFF).toByte()
        return out
    }

    fun decode(raw: ByteArray): NothingFrame? {
        if (raw.size < 8 || (raw[0].toInt() and 0xFF) != SOF) return null
        val ctrl = (raw[1].toInt() and 0xFF) or ((raw[2].toInt() and 0xFF) shl 8)
        val wire = (raw[3].toInt() and 0xFF) or ((raw[4].toInt() and 0xFF) shl 8)
        val length = (raw[5].toInt() and 0xFF) or ((raw[6].toInt() and 0xFF) shl 8)
        val crcSize = if ((ctrl and 0x20) != 0) 2 else 0
        val total = 8 + length + crcSize
        if (length < 0 || raw.size != total) return null
        if (crcSize == 2) {
            val expected = crc16Modbus(raw, 8 + length)
            val actual = (raw[8 + length].toInt() and 0xFF) or ((raw[9 + length].toInt() and 0xFF) shl 8)
            if (expected != actual) return null
        }
        return NothingFrame(
            command = wire and 0xFF,
            direction = (wire ushr 8) and 0xFF,
            payload = raw.copyOfRange(8, 8 + length),
            operation = raw[7].toInt() and 0xFF,
        )
    }
}

/** Stream framer handling arbitrarily chunked Nothing RFCOMM reads. */
class NothingFrameParser {
    private val buffer = ArrayList<Byte>()

    fun feed(chunk: ByteArray): List<NothingFrame> {
        for (b in chunk) buffer.add(b)
        val out = mutableListOf<NothingFrame>()
        while (buffer.size >= 8) {
            val start = buffer.indexOf(NothingFrameCodec.SOF.toByte())
            if (start < 0) {
                buffer.clear()
                break
            }
            if (start > 0) buffer.subList(0, start).clear()
            if (buffer.size < 8) break
            val ctrl = (buffer[1].toInt() and 0xFF) or ((buffer[2].toInt() and 0xFF) shl 8)
            val length = (buffer[5].toInt() and 0xFF) or ((buffer[6].toInt() and 0xFF) shl 8)
            val crcSize = if ((ctrl and 0x20) != 0) 2 else 0
            val total = 8 + length + crcSize
            if (length < 0 || total > 1024) {
                buffer.removeAt(0)
                continue
            }
            if (buffer.size < total) break
            val raw = ByteArray(total) { buffer[it] }
            val decoded = NothingFrameCodec.decode(raw)
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
