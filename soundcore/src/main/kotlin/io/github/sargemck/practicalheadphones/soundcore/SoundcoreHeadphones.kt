package io.github.sargemck.practicalheadphones.soundcore

import kotlin.time.Duration.Companion.milliseconds

class SoundcoreHeadphones(private val connection: SoundcoreConnection) {
    /** Reads full state blob `01:01` (used as handshake & battery fallback). */
    suspend fun getState(): ByteArray =
        connection.request(SoundcoreProtocol.GROUP_STATE, SoundcoreProtocol.CMD_STATE_UPDATE).payload

    /** Reads battery via `01:03`, falling back to byte 0 of `01:01`. */
    suspend fun getBattery(): Int {
        val direct = try {
            val f = connection.request(SoundcoreProtocol.GROUP_STATE, SoundcoreProtocol.CMD_BATTERY, timeout = 1500.milliseconds)
            SoundcoreProtocol.parseBatteryPercent(f.payload)
        } catch (_: SoundcoreException.Timeout) {
            null
        }
        if (direct != null) return direct
        val state = getState()
        return SoundcoreProtocol.parseBatteryPercent(state)
            ?: throw SoundcoreException("Malformed Soundcore battery payload")
    }

    suspend fun getSoundMode(): SoundcoreSoundMode? = try {
        val f = connection.request(SoundcoreProtocol.GROUP_SOUND_MODE, SoundcoreProtocol.CMD_SOUND_MODE_GET, timeout = 1500.milliseconds)
        SoundcoreProtocol.parseSoundMode(f.payload)
    } catch (_: SoundcoreException.Timeout) {
        null
    }

    suspend fun setSoundMode(mode: SoundcoreAncMode, existingRaw: ByteArray?): SoundcoreSoundMode {
        val body = SoundcoreProtocol.buildSoundModeSet(mode, existingRaw)
        connection.send(SoundcoreProtocol.GROUP_SOUND_MODE, SoundcoreProtocol.CMD_SOUND_MODE_SET, body)
        return SoundcoreSoundMode(mode, body)
    }

    suspend fun getEq(): SoundcoreEqState? = try {
        val f = connection.request(SoundcoreProtocol.GROUP_EQ, SoundcoreProtocol.CMD_EQ_GET, timeout = 1500.milliseconds)
        SoundcoreProtocol.parseEq(f.payload)
    } catch (_: SoundcoreException.Timeout) {
        null
    }

    suspend fun setEq(presetId: Int, bands: List<Int>): SoundcoreEqState {
        val body = SoundcoreProtocol.buildEqSet(presetId, bands)
        connection.send(SoundcoreProtocol.GROUP_EQ, SoundcoreProtocol.CMD_EQ_SET, body)
        return SoundcoreEqState(presetId, bands.take(8))
    }
}
