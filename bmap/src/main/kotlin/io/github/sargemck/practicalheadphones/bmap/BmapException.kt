package io.github.sargemck.practicalheadphones.bmap

/** Everything a request on [BmapConnection] or [Headphones] can fail with. */
sealed class BmapException(message: String) : Exception(message) {
    /** No reply, and the liveness check didn't answer either. The connection is closed. */
    class Timeout(val address: Address) : BmapException("[$address] no reply")

    /** No reply from this address while the link still answers, e.g. [31.4] on fw 10.12.12. */
    class Unsupported(val address: Address) : BmapException("[$address] no reply while the link answers: unsupported")

    /** The device answered with ERROR. [code] is payload byte 0, or -1 if the payload was empty. */
    class DeviceError(val address: Address, val code: Int) :
        BmapException("[$address] ERROR ${ErrorCodes.name(code)}")

    /** The link closed before the request completed, or was already closed. */
    class Disconnected(val reason: CloseReason) : BmapException("disconnected: $reason")

    /** A frame that breaks the hard rules (op SET or the Authentication block). Nothing was sent. */
    class Refused(val frame: BmapFrame) : BmapException("refused to send $frame")

    /** The reply payload doesn't have the expected shape. */
    class Malformed(val address: Address, payload: ByteArray, detail: String?) :
        BmapException("[$address] malformed payload ${Hex.format(payload)}: $detail")
}

/** BMAP ERROR payload codes (bosectl constants.py). */
object ErrorCodes {
    const val FUNC_NOT_SUPPORTED = 4
    const val INVALID_DATA = 6
    const val RUNTIME = 8

    private val names = mapOf(
        0 to "Unknown",
        1 to "Length",
        2 to "Chksum",
        3 to "FblockNotSupp",
        4 to "FuncNotSupp",
        5 to "OpNotSupp(auth)",
        6 to "InvalidData",
        7 to "DataUnavail",
        8 to "Runtime",
        9 to "Timeout",
        10 to "InvalidState",
        15 to "InvalidTransition",
        20 to "InsecureTransport",
    )

    fun name(code: Int): String = names[code] ?: "code $code"
}
