package io.github.sargemck.practicalheadphones.bmap

/**
 * Typed access to a QC Ultra 2 over a [BmapConnection]. Only features defined in [QcUltra2]
 * can be sent.
 */
class Headphones(private val connection: BmapConnection) {
    suspend fun <T> read(feature: ReadFeature<T>): T {
        val reply = connection.request(BmapFrame(feature.address.fblock, feature.address.func, BmapOp.GET))
        return parse(feature.address, reply.payload, feature.parse)
    }

    suspend fun <T> write(feature: WriteFeature<T>, value: T) {
        setget(feature.address, feature.build(value))
    }

    /** Writes the live audio settings and returns what the device applied. */
    suspend fun setAudioSettings(settings: AudioSettings): AudioSettings {
        val reply = setget(QcUltra2.audioSettingsWrite, Builders.audioSettings(settings))
        return parse(QcUltra2.audioSettingsWrite, reply.payload, Parsers::audioSettings)
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
                QcUltra2.modeConfig -> parse(frame.address, frame.payload, Parsers::modeConfig).let { modes[it.index] = it }
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
        val address = QcUltra2.selectMode
        connection.request(BmapFrame(address.fblock, address.func, BmapOp.START, Builders.selectMode(index)))
        return read(QcUltra2.audioSettings)
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
        val write = Profiles.create(allModes().modes, name, settings)
        writeMode(write)
        return write.index
    }

    suspend fun updateProfile(name: String, rename: String? = null, settings: ProfileSettings? = null) {
        writeMode(Profiles.update(allModes().modes, name, rename, settings))
    }

    suspend fun deleteProfile(name: String) {
        writeMode(Profiles.delete(allModes().modes, name))
    }

    private suspend fun writeMode(write: ModeConfigWrite) {
        setget(QcUltra2.modeConfig, Builders.modeConfig(write))
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
