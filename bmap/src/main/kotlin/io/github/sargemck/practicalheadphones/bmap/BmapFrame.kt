package io.github.sargemck.practicalheadphones.bmap

/** BMAP operator, the low nibble of the flags byte. */
enum class BmapOp(val code: Int) {
    SET(0),
    GET(1),
    SETGET(2),
    STATUS(3),
    ERROR(4),
    START(5),
    RESULT(6),
    PROCESSING(7);

    companion object {
        fun fromCode(code: Int): BmapOp? = entries.firstOrNull { it.code == code }
    }
}

/**
 * One BMAP frame: `[fblock][func][flags][len][payload...]`,
 * flags = `deviceId << 6 | port << 4 | op`.
 */
class BmapFrame(
    val fblock: Int,
    val func: Int,
    val op: BmapOp,
    payload: ByteArray = ByteArray(0),
    val deviceId: Int = 0,
    val port: Int = 0,
) {
    val payload: ByteArray = payload.copyOf()

    init {
        require(fblock in 0..255) { "fblock out of range: $fblock" }
        require(func in 0..255) { "func out of range: $func" }
        require(deviceId in 0..3) { "deviceId out of range: $deviceId" }
        require(port in 0..3) { "port out of range: $port" }
        require(this.payload.size <= MAX_PAYLOAD) { "payload too long: ${this.payload.size}" }
    }

    val flags: Int get() = (deviceId shl 6) or (port shl 4) or op.code

    val address: Address get() = Address(fblock, func)

    fun encode(): ByteArray =
        byteArrayOf(fblock.toByte(), func.toByte(), flags.toByte(), payload.size.toByte()) + payload

    override fun equals(other: Any?): Boolean =
        other is BmapFrame &&
            fblock == other.fblock &&
            func == other.func &&
            op == other.op &&
            deviceId == other.deviceId &&
            port == other.port &&
            payload.contentEquals(other.payload)

    override fun hashCode(): Int {
        var h = fblock
        h = 31 * h + func
        h = 31 * h + op.hashCode()
        h = 31 * h + deviceId
        h = 31 * h + port
        h = 31 * h + payload.contentHashCode()
        return h
    }

    override fun toString(): String =
        if (payload.isEmpty()) "[$address] $op" else "[$address] $op ${Hex.format(payload)}"

    companion object {
        const val HEADER_SIZE = 4
        const val MAX_PAYLOAD = 255
    }
}
