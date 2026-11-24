package io.github.sargemck.practicalheadphones.bmap

/**
 * Payload parsers, ported from bosectl `devices/parsers.py` unless noted. Strict: a payload
 * that's too short or out of range throws [IllegalArgumentException] instead of returning a
 * default, so callers never show made-up values as real. [Headphones] wraps that into
 * [BmapException.Malformed].
 */
object Parsers {
    fun firmware(p: ByteArray): String {
        require(p.isNotEmpty()) { "empty" }
        return String(p, Charsets.US_ASCII)
    }

    /** `[flag, utf8...]`. */
    fun productName(p: ByteArray): String {
        require(p.isNotEmpty()) { "empty" }
        return p.copyOfRange(1, p.size).toString(Charsets.UTF_8)
    }

    /** 4-byte groups `[min, max, current, bandId]`, signed values. */
    fun eq(p: ByteArray): List<EqBand> {
        require(p.isNotEmpty() && p.size % 4 == 0) { "expected 4-byte groups, got ${p.size} bytes" }
        return (p.indices step 4).map { i ->
            EqBand(bandId = p.u(i + 3), min = p[i].toInt(), max = p[i + 1].toInt(), current = p[i + 2].toInt())
        }
    }

    fun buttons(p: ByteArray): ButtonMapping {
        require(p.size >= 3) { "expected at least 3 bytes" }
        return ButtonMapping(buttonId = p.u(0), event = p.u(1), action = p.u(2))
    }

    fun multipoint(p: ByteArray): Multipoint {
        require(p.isNotEmpty()) { "empty" }
        return Multipoint(enabled = p.u(0) and 0x02 != 0, raw = p.u(0))
    }

    /** `[persist, level, ?]`. The third byte is kept raw (M0: `01 02 0f`). */
    fun selfVoice(p: ByteArray): SelfVoice {
        require(p.size >= 2) { "expected at least 2 bytes" }
        val level = requireNotNull(SelfVoiceLevel.fromCode(p.u(1))) { "unknown level ${p.u(1)}" }
        return SelfVoice(persist = p.u(0) != 0, level = level, extra = if (p.size >= 3) p.u(2) else null)
    }

    fun bool(p: ByteArray): Boolean {
        require(p.isNotEmpty()) { "empty" }
        return p.u(0) != 0
    }

    /** Byte 0 is the percentage. */
    fun battery(p: ByteArray): Int {
        require(p.isNotEmpty()) { "empty" }
        return p.u(0).also { require(it in 0..100) { "battery $it out of range" } }
    }

    /**
     * Parses [2.2] battery payload for both headphones (1+ bytes, byte 0 = %) and earbuds
     * (4 * N bytes: `[level, rsv, rsv, id]` where 1=Right, 2=Left, 3=Case, 4=Combined).
     */
    fun batteryStatus(p: ByteArray): BatteryStatus {
        require(p.isNotEmpty()) { "empty" }
        if (p.size >= 8 && p.size % 4 == 0) {
            var right: Int? = null
            var left: Int? = null
            var casePct: Int? = null
            var combined: Int? = null
            for (i in p.indices step 4) {
                val level = p.u(i)
                val id = p.u(i + 3)
                if (level !in 0..100) continue
                when (id) {
                    1 -> right = level
                    2 -> left = level
                    3 -> casePct = level
                    4 -> combined = level
                }
            }
            val components = buildList {
                left?.let { add(BatteryComponent("L", it)) }
                right?.let { add(BatteryComponent("R", it)) }
                casePct?.let { add(BatteryComponent("Case", it)) }
            }
            val aggregate = combined
                ?: listOfNotNull(left, right).minOrNull()
                ?: casePct
                ?: throw IllegalArgumentException("no valid battery component in ${p.size} bytes")
            return BatteryStatus(percent = aggregate, components = components)
        }
        return BatteryStatus(percent = battery(p))
    }

    /** QC35 / QC35 II ANR [1.6]: `[level, capabilities?]`. */
    fun anr(p: ByteArray): AnrMode {
        require(p.isNotEmpty()) { "empty" }
        return requireNotNull(AnrMode.fromCode(p.u(0))) { "unknown ANR level ${p.u(0)}" }
    }

    /** Inferred from the M0 fixture (bosectl has no parser): `[header]` then 6-byte MACs. */
    fun pairedDevices(p: ByteArray): PairedDevices {
        require(p.isNotEmpty() && (p.size - 1) % 6 == 0) { "expected 1 + 6n bytes, got ${p.size}" }
        return PairedDevices(header = p.u(0), devices = (1 until p.size step 6).map { MacAddress.of(p, it) })
    }

    /** Inferred from the M0 fixture: `[mac 6][flags 3][utf8 name...]`. */
    fun deviceInfo(p: ByteArray): DeviceInfo {
        require(p.size >= 9) { "expected at least 9 bytes" }
        return DeviceInfo(
            mac = MacAddress.of(p),
            flags = List(3) { p.u(6 + it) },
            name = p.copyOfRange(9, p.size).toString(Charsets.UTF_8),
        )
    }

    /** `[supportedHi, supportedLo, activeType, mac 6 if bluetooth]`. */
    fun source(p: ByteArray): Source {
        require(p.size >= 3) { "expected at least 3 bytes" }
        val type = requireNotNull(SourceType.fromCode(p.u(2))) { "unknown source type ${p.u(2)}" }
        val mac = if (type == SourceType.BLUETOOTH && p.size >= 9) MacAddress.of(p, 3) else null
        return Source(supported = (p.u(0) shl 8) or p.u(1), type = type, mac = mac)
    }

    fun currentMode(p: ByteArray): Int {
        require(p.isNotEmpty()) { "empty" }
        return p.u(0)
    }

    /** `[total, maskHi, maskLo]`, bit i = mode i. The 16-bit byte order is inferred (M4 confirms). */
    fun favourites(p: ByteArray): Favourites {
        require(p.size >= 3) { "expected 3 bytes" }
        val total = p.u(0)
        val mask = (p.u(1) shl 8) or p.u(2)
        return Favourites(total = total, slots = (0 until 16).filter { mask and (1 shl it) != 0 }.toSet())
    }

    /** `[cnc, autoCnc, spatial, wind, anc]`. */
    fun audioSettings(p: ByteArray): AudioSettings {
        require(p.size >= 5) { "expected 5 bytes" }
        require(p.u(0) in 0..10) { "cnc ${p.u(0)} out of range" }
        return AudioSettings(
            cnc = p.u(0),
            autoCnc = p.u(1) != 0,
            spatial = spatial(p.u(2)),
            wind = p.u(3) != 0,
            anc = p.u(4) != 0,
        )
    }

    /**
     * 48-byte STATUS: `[0]` idx, `[1:3]` prompt, `[3]` editable, `[4]` configured, `[5]` ?,
     * `[6:38]` name (UTF-8, NUL padded), `[38:42]` ?, `[42]` cnc, `[43]` autoCnc,
     * `[44]` spatial, `[45]` ?, `[46]` wind, `[47]` anc.
     * Wind is `[46]`, not `[45]` as bosectl has it (M4 device run, fw 10.12.12): `[46]` is set
     * for exactly the modes whose switch turns Wind Block on, and follows a write; `[45]` is 1
     * on every custom slot and 0 on presets.
     */
    fun modeConfig(p: ByteArray): ModeConfig {
        require(p.size >= 48) { "expected 48 bytes, got ${p.size}" }
        val nameBytes = p.copyOfRange(6, 38)
        val end = nameBytes.indexOf(0).let { if (it < 0) nameBytes.size else it }
        return ModeConfig(
            index = p.u(0),
            promptB1 = p.u(1),
            promptB2 = p.u(2),
            editable = p.u(3) != 0,
            configured = p.u(4) != 0,
            name = nameBytes.copyOfRange(0, end).toString(Charsets.UTF_8),
            cnc = p.u(42),
            autoCnc = p.u(43) != 0,
            spatial = spatial(p.u(44)),
            wind = p.u(46) != 0,
            anc = p.u(47) != 0,
        )
    }

    /**
     * 47-byte STATUS for QuietComfort Headphones 2023 ("prince") and QC45: identical to
     * [modeConfig] up to byte 46 (`wind`), with no byte 47 (`anc` defaults to true).
     */
    fun modeConfig47(p: ByteArray): ModeConfig {
        require(p.size >= 47) { "expected 47 bytes, got ${p.size}" }
        val nameBytes = p.copyOfRange(6, 38)
        val end = nameBytes.indexOf(0).let { if (it < 0) nameBytes.size else it }
        return ModeConfig(
            index = p.u(0),
            promptB1 = p.u(1),
            promptB2 = p.u(2),
            editable = p.u(3) != 0,
            configured = p.u(4) != 0,
            name = nameBytes.copyOfRange(0, end).toString(Charsets.UTF_8),
            cnc = p.u(42),
            autoCnc = p.u(43) != 0,
            spatial = spatial(p.u(44)),
            wind = p.u(46) != 0,
            anc = true,
        )
    }

    /** Parses either 48-byte or 47-byte [31.6] STATUS frames. */
    fun modeConfigAny(p: ByteArray): ModeConfig =
        if (p.size == 47) modeConfig47(p) else modeConfig(p)

    private fun spatial(code: Int): Spatial = requireNotNull(Spatial.fromCode(code)) { "unknown spatial $code" }

    private fun ByteArray.u(i: Int): Int = this[i].toInt() and 0xFF
}
