package io.github.sargemck.practicalheadphones.bmap

/** Hex helpers. Format is lowercase, space separated: `02 02 01 00`. */
object Hex {
    fun format(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }

    /** Parses hex with optional whitespace, `:` or `-` between bytes. */
    fun parse(text: String): ByteArray {
        val digits = text.filterNot { it.isWhitespace() || it == ':' || it == '-' }
        require(digits.length % 2 == 0) { "odd number of hex digits: \"$text\"" }
        return ByteArray(digits.length / 2) { i ->
            val hi = Character.digit(digits[2 * i], 16)
            val lo = Character.digit(digits[2 * i + 1], 16)
            require(hi >= 0 && lo >= 0) { "not hex: \"$text\"" }
            ((hi shl 4) or lo).toByte()
        }
    }
}
