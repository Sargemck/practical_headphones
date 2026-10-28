package io.github.sargemck.practicalheadphones.bmap

/** A feature read with GET. */
class ReadFeature<T>(val address: Address, val parse: (ByteArray) -> T)

/**
 * A feature written with SETGET. [parseReply] reads the device's reply into what it applied.
 * It is set only where the reply shape is verified on the device; otherwise the reply comes
 * back raw ([WriteReply.Raw]).
 */
class WriteFeature<T, R>(
    val address: Address,
    val build: (T) -> ByteArray,
    val parseReply: ((ByteArray) -> R)? = null,
)

/** What a SETGET reply said the device applied. */
sealed interface WriteReply<out R> {
    data class Applied<R>(val value: R) : WriteReply<R>

    /** Reply shape not verified yet, so it isn't parsed. */
    class Raw(val frame: BmapFrame) : WriteReply<Nothing> {
        override fun toString(): String = "Raw($frame)"
    }
}

/**
 * Bose QC Ultra Headphones (2nd Gen), codename "wolverine", PID 0x4082, as data.
 * Addresses from bosectl `devices/qc_ultra2.py`, checked against fw 10.12.12 in M0.
 * [1.4] and [31.4] are left out: unsupported on fw 10.12.12 (M0 FINDINGS).
 */
object QcUltra2 {
    val firmware = ReadFeature(Address(0, 5), Parsers::firmware)
    val productName = ReadFeature(Address(1, 2), Parsers::productName)
    val eq = ReadFeature(Address(1, 7), Parsers::eq)
    val buttons = ReadFeature(Address(1, 9), Parsers::buttons)
    val multipoint = ReadFeature(Address(1, 10), Parsers::multipoint)
    val selfVoice = ReadFeature(Address(1, 11), Parsers::selfVoice)
    val autoPlayPause = ReadFeature(Address(1, 24), Parsers::bool)
    val autoAnswer = ReadFeature(Address(1, 27), Parsers::bool)
    val battery = ReadFeature(Address(2, 2), Parsers::battery)
    val pairedDevices = ReadFeature(Address(4, 4), Parsers::pairedDevices)
    val source = ReadFeature(Address(5, 1), Parsers::source)
    val currentMode = ReadFeature(Address(31, 3), Parsers::currentMode)
    val favourites = ReadFeature(Address(31, 8), Parsers::favourites)
    val audioSettings = ReadFeature(Address(31, 10), Parsers::audioSettings)

    /**
     * Every SETGET reply is STATUS in the same shape as the GET reply, so it's parsed with the
     * read parser (31.10 in M0 E5; the rest in the M2 hardware test, fw 10.12.12).
     */
    object Write {
        val productName = WriteFeature(Address(1, 2), Builders::productName, Parsers::productName)
        val eqBand = WriteFeature(Address(1, 7), Builders::eqBand, Parsers::eq)
        val selfVoice = WriteFeature(Address(1, 11), Builders::selfVoice, Parsers::selfVoice)
        val autoPlayPause = WriteFeature(Address(1, 24), Builders::bool, Parsers::bool)
        val autoAnswer = WriteFeature(Address(1, 27), Builders::bool, Parsers::bool)
        val favourites = WriteFeature(Address(31, 8), Builders::favourites, Parsers::favourites)

        /** Reply is STATUS with the applied values, same shape as GET (M0 E5). */
        val audioSettings = WriteFeature(Address(31, 10), Builders::audioSettings, Parsers::audioSettings)
    }

    /** Writes whose meaning isn't known yet. Only the M2 hardware test may use them. */
    @HardwareTestOnly
    object Experimental {
        /**
         * Raw [1.10] bitfield. Fw 10.12.12 reads `07` and ignores a write of `05` (M2 hardware
         * test: reply and read-back stay `07`), so multipoint is read-only in v1.
         */
        val multipointRaw = WriteFeature(Address(1, 10), { v: Int -> byteArrayOf(v.toByte()) }, Parsers::multipoint)
    }

    /** GET with a MAC payload. */
    val deviceInfo = Address(4, 5)

    /** START with `[1]`. The device drops the link. */
    val pairingMode = Address(4, 8)

    /** START with `[0]`. The device drops the link. */
    val powerOff = Address(7, 4)

    /** START, then a drain of STATUS frames ending in RESULT. */
    val allModes = Address(31, 1)

    /** START `[idx, 0]` switches mode. */
    val selectMode = Address(31, 3)

    val modeConfig = Address(31, 6)

    /** Bose presets, locked by the firmware (Runtime error 8 on write). */
    val presetSlots = 0..3

    /** Custom mode slots. */
    val editableSlots = 4..10

    val presetNames = listOf("Quiet", "Aware", "Immersion", "Cinema")
}
