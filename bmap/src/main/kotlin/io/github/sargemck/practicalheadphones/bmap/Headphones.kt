package io.github.sargemck.practicalheadphones.bmap

/**
 * Typed access to Bose BMAP headphones over a [BmapConnection]. Defaults to [BoseCatalog.QC_ULTRA_2]
 * so existing callers remain unchanged while other Bose models pass their [BoseProfile].
 */
class Headphones(
    private val connection: BmapConnection,
    val profile: BoseProfile = BoseCatalog.QC_ULTRA_2,
) {
    /** Sends GET [0.1] required by QC45 and QC35 before answering other GET requests. */
    suspend fun initSession() {
        val address = BoseCatalog.initPacket
        connection.request(BmapFrame(address.fblock, address.func, BmapOp.GET))
    }

    suspend fun <T> read(feature: ReadFeature<T>): T {
        val reply = connection.request(BmapFrame(feature.address.fblock, feature.address.func, BmapOp.GET))
        return parse(feature.address, reply.payload, feature.parse)
    }

    /** Reads [2.2] battery for either single-battery headphones or multi-component earbuds. */
    suspend fun batteryStatus(): BatteryStatus = read(BoseCatalog.batteryStatus)

    /** Reads QC35 / QC35 II ANR mode [1.6]. */
    suspend fun anr(): AnrMode = read(BoseCatalog.anr)

    /** Sets QC35 / QC35 II ANR mode [1.6]. */
    suspend fun setAnr(mode: AnrMode): AnrMode {
        val reply = write(BoseCatalog.writeAnr, mode)
        return (reply as WriteReply.Applied).value
    }

    /** Writes [value] and returns what the device's reply says it applied. */
    suspend fun <T, R> write(feature: WriteFeature<T, R>, value: T): WriteReply<R> {
        val reply = setget(feature.address, feature.build(value))
        val parseReply = feature.parseReply ?: return WriteReply.Raw(reply)
        return WriteReply.Applied(parse(feature.address, reply.payload, parseReply))
    }

    suspend fun deviceInfo(mac: MacAddress): DeviceInfo {
        val address = QcUltra2.deviceInfo
        val reply = connection.request(BmapFrame(address.fblock, address.func, BmapOp.GET, mac.toBytes()))
        return parse(address, reply.payload, Parsers::deviceInfo)
    }

    /** One [31.1] drain: every mode, plus current mode, favourites and audio settings if sent. */
    suspend fun allModes(): ModesSnapshot {
        val address = QcUltra2.allModes
        val frames = connection.drain(BmapFrame(address.fblock, address.func, BmapOp.START))
            .filter { it.op == BmapOp.STATUS }
        val modes = linkedMapOf<Int, ModeConfig>()
        var current: Int? = null
        var favourites: Favourites? = null
        var audio: AudioSettings? = null
        for (frame in frames) {
            when (frame.address) {
                QcUltra2.modeConfig -> parse(frame.address, frame.payload, Parsers::modeConfigAny).let { modes[it.index] = it }
                QcUltra2.currentMode.address -> current = parse(frame.address, frame.payload, Parsers::currentMode)
                QcUltra2.favourites.address -> favourites = parse(frame.address, frame.payload, Parsers::favourites)
                QcUltra2.audioSettings.address -> audio = parse(frame.address, frame.payload, Parsers::audioSettings)
            }
        }
        return ModesSnapshot(modes.values.sortedBy { it.index }, current, favourites, audio)
    }

    /**
     * Switches mode, then re-reads the live audio settings: a mode switch changes them
     * (M0 E5: spatial went from 02 to 00).
     */
    suspend fun selectMode(index: Int): AudioSettings {
        selectModeOnly(index)
        return read(QcUltra2.audioSettings)
    }

    /** Switches mode via START [31.3] without reading [31.10] (for models without live audio settings). */
    suspend fun selectModeOnly(index: Int) {
        val address = QcUltra2.selectMode
        connection.request(BmapFrame(address.fblock, address.func, BmapOp.START, Builders.selectMode(index)))
    }

    suspend fun pairingMode() {
        val address = QcUltra2.pairingMode
        connection.requestExpectingDisconnect(BmapFrame(address.fblock, address.func, BmapOp.START, byteArrayOf(1)))
    }

    suspend fun powerOff() {
        val address = QcUltra2.powerOff
        connection.requestExpectingDisconnect(BmapFrame(address.fblock, address.func, BmapOp.START, byteArrayOf(0)))
    }

    /** Creates a custom mode in the first free slot and returns the slot. */
    suspend fun createProfile(name: String, settings: ProfileSettings): Int {
        val write = Profiles.create(
            modes = allModes().modes,
            name = name,
            settings = settings,
            editableSlots = profile.editableSlots,
            presetNames = profile.presetNames,
        )
        writeMode(write)
        return write.index
    }

    suspend fun updateProfile(name: String, rename: String? = null, settings: ProfileSettings? = null) {
        writeMode(
            Profiles.update(
                modes = allModes().modes,
                name = name,
                rename = rename,
                settings = settings,
                presetSlots = profile.presetSlots,
                presetNames = profile.presetNames,
            ),
        )
    }

    suspend fun deleteProfile(name: String) {
        writeMode(Profiles.delete(allModes().modes, name, presetSlots = profile.presetSlots))
    }

    /** Like [updateProfile], for the mode in slot [index]. Checked against a fresh drain. */
    suspend fun updateMode(index: Int, rename: String? = null, settings: ProfileSettings? = null) {
        writeMode(
            Profiles.update(
                modes = allModes().modes,
                index = index,
                rename = rename,
                settings = settings,
                presetSlots = profile.presetSlots,
                presetNames = profile.presetNames,
            ),
        )
    }

    /** Like [deleteProfile], for the mode in slot [index]. Checked against a fresh drain. */
    suspend fun deleteMode(index: Int) {
        writeMode(Profiles.delete(allModes().modes, index, presetSlots = profile.presetSlots))
    }

    private suspend fun writeMode(write: ModeConfigWrite) {
        val payload = when (profile.modeLayout) {
            ModeLayout.V47_39 -> Builders.modeConfig39(write)
            else -> Builders.modeConfig(write)
        }
        setget(QcUltra2.modeConfig, payload)
    }

    private suspend fun setget(address: Address, payload: ByteArray): BmapFrame =
        connection.request(BmapFrame(address.fblock, address.func, BmapOp.SETGET, payload))

    private fun <T> parse(address: Address, payload: ByteArray, parser: (ByteArray) -> T): T =
        try {
            parser(payload)
        } catch (e: IllegalArgumentException) {
            throw BmapException.Malformed(address, payload, e.message)
        } catch (e: IndexOutOfBoundsException) {
            throw BmapException.Malformed(address, payload, e.message)
        }
}
