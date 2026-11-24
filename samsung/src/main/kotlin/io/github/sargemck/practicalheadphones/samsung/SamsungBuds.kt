package io.github.sargemck.practicalheadphones.samsung

class SamsungBuds(private val connection: SamsungConnection) {
    /** Sends `MANAGER_INFO` (`0x88`) and awaits `EXTENDED_STATUS_UPDATED` (`0x61`). */
    suspend fun fetchExtendedStatus(): SamsungStatus {
        val frame = connection.request(
            msgId = SamsungProtocol.MSG_MANAGER_INFO,
            payload = SamsungProtocol.buildManagerInfo(),
        ) { it.msgId == SamsungProtocol.MSG_EXTENDED_STATUS_UPDATED }
        return SamsungProtocol.parseExtendedStatus(frame.payload)
            ?: throw SamsungException("Malformed Galaxy Buds extended status")
    }

    suspend fun setNoiseMode(mode: SamsungNoiseMode) {
        connection.send(SamsungProtocol.MSG_NOISE_CONTROLS, SamsungProtocol.buildNoiseControlSet(mode))
    }

    suspend fun setEqualizer(preset: SamsungEqPreset) {
        connection.send(SamsungProtocol.MSG_EQUALIZER, SamsungProtocol.buildEqualizerSet(preset))
    }
}
