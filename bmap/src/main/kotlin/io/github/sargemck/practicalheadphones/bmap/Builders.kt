package io.github.sargemck.practicalheadphones.bmap

/**
 * SETGET and START payload builders, ported from bosectl `devices/parsers.py`. Only START 31.3
 * and SETGET 31.10 are verified on the device (M0 E5); the rest are checked in M2's hardware test.
 * Invalid values throw [IllegalArgumentException].
 */
object Builders {
    /** Longest name the device stores, in UTF-8 bytes (32-byte field with a NUL). */
    const val MAX_NAME_BYTES = 31

    /** Product name SETGET: UTF-8, no flag byte (bosectl `set_name`). Too long is rejected, not truncated. */
    fun productName(name: String): ByteArray {
        val bytes = name.toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= MAX_NAME_BYTES) { "name must be 1..$MAX_NAME_BYTES UTF-8 bytes" }
        return bytes
    }

    fun eqBand(setting: EqBandSetting): ByteArray {
        require(setting.bandId in 0..2) { "band ${setting.bandId} out of range" }
        require(setting.value in -10..10) { "EQ value ${setting.value} out of range" }
        return byteArrayOf(setting.value.toByte(), setting.bandId.toByte())
    }

    /** `[persist, level]` with persist always 1 (bosectl `build_sidetone`). */
    fun selfVoice(level: SelfVoiceLevel): ByteArray = byteArrayOf(1, level.code.toByte())

    fun bool(value: Boolean): ByteArray = byteArrayOf(if (value) 1 else 0)

    /** `[total, maskHi, maskLo]`. The 16-bit byte order is inferred (M4 confirms). */
    fun favourites(favourites: Favourites): ByteArray {
        require(favourites.slots.all { it in 0 until 16 }) { "slot out of range: ${favourites.slots}" }
        val mask = favourites.slots.fold(0) { m, slot -> m or (1 shl slot) }
        return byteArrayOf(favourites.total.toByte(), (mask shr 8).toByte(), mask.toByte())
    }

    /** `[cnc, autoCnc, spatial, wind, anc]`. autoCnc is always 0: the firmware rejects 1. */
    fun audioSettings(settings: AudioSettings): ByteArray {
        require(settings.cnc in 0..10) { "cnc ${settings.cnc} out of range" }
        return byteArrayOf(
            settings.cnc.toByte(),
            0,
            settings.spatial.code.toByte(),
            if (settings.wind) 1 else 0,
            if (settings.anc) 1 else 0,
        )
    }

    /** 40 bytes: idx, prompt b1, prompt b2, name (32 B, NUL padded), cnc, autoCnc 0, spatial, wind, anc. */
    fun modeConfig(write: ModeConfigWrite): ByteArray {
        require(write.index in 0..255) { "index ${write.index} out of range" }
        require(write.cnc in 0..10) { "cnc ${write.cnc} out of range" }
        val name = write.name.toByteArray(Charsets.UTF_8)
        require(name.size <= MAX_NAME_BYTES) { "name longer than $MAX_NAME_BYTES UTF-8 bytes" }
        val out = ByteArray(40)
        out[0] = write.index.toByte()
        out[1] = write.promptB1.toByte()
        out[2] = write.promptB2.toByte()
        name.copyInto(out, 3)
        out[35] = write.cnc.toByte()
        out[36] = 0
        out[37] = write.spatial.code.toByte()
        out[38] = if (write.wind) 1 else 0
        out[39] = if (write.anc) 1 else 0
        return out
    }

    /** START 31.3 `[idx, 0]`: switch mode without a voice prompt. */
    fun selectMode(index: Int): ByteArray {
        require(index in 0..255) { "index $index out of range" }
        return byteArrayOf(index.toByte(), 0)
    }
}
