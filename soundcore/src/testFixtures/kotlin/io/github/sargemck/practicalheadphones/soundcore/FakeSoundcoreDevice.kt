package io.github.sargemck.practicalheadphones.soundcore

import kotlinx.coroutines.channels.Channel
import java.io.IOException

/**
 * In-memory Anker Soundcore simulator supporting both 6-byte sound modes (Space Q45, Space One,
 * Space 2) and 4-byte sound modes (Life Q30).
 */
class FakeSoundcoreDevice(
    val soundModeWidth: Int = 6,
) : SoundcoreTransport {
    private val outgoing = Channel<ByteArray?>(Channel.UNLIMITED)
    private val parser = SoundcoreFrameParser(expectTx = true)
    private var linkUp = true

    var batteryLevel: Int = 7 // (7 + 1) * 10 = 80%
    var soundModeBytes: ByteArray = if (soundModeWidth == 4) {
        byteArrayOf(0x00, 0x00, 0x00, 0x00)
    } else {
        byteArrayOf(0x00, 0x5F, 0xFF.toByte(), 0x00, 0x00, 0x01)
    }
    var eqPresetId: Int = SoundcoreEqPreset.SIGNATURE.id
    var eqBands: List<Int> = List(8) { 0 }

    val received = mutableListOf<SoundcoreFrame>()

    override suspend fun read(): ByteArray? = outgoing.receive()

    override suspend fun write(bytes: ByteArray) {
        if (!linkUp) throw IOException("link down")
        for (frame in parser.feed(bytes)) {
            received += frame
            handle(frame)
        }
    }

    override fun close() {
        linkUp = false
        outgoing.trySend(null)
    }

    private fun handle(frame: SoundcoreFrame) {
        when (frame.cmdGroup to frame.cmdId) {
            SoundcoreProtocol.GROUP_STATE to SoundcoreProtocol.CMD_STATE_UPDATE -> {
                val blob = ByteArray(80)
                blob[0] = batteryLevel.toByte()
                sendRx(SoundcoreProtocol.GROUP_STATE, SoundcoreProtocol.CMD_STATE_UPDATE, blob)
            }
            SoundcoreProtocol.GROUP_STATE to SoundcoreProtocol.CMD_BATTERY -> {
                sendRx(SoundcoreProtocol.GROUP_STATE, SoundcoreProtocol.CMD_BATTERY, byteArrayOf(batteryLevel.toByte()))
            }
            SoundcoreProtocol.GROUP_SOUND_MODE to SoundcoreProtocol.CMD_SOUND_MODE_GET -> {
                sendRx(SoundcoreProtocol.GROUP_SOUND_MODE, SoundcoreProtocol.CMD_SOUND_MODE_GET, soundModeBytes)
            }
            SoundcoreProtocol.GROUP_SOUND_MODE to SoundcoreProtocol.CMD_SOUND_MODE_SET -> {
                if (frame.payload.size == soundModeWidth) {
                    soundModeBytes = frame.payload.copyOf()
                    sendRx(SoundcoreProtocol.GROUP_SOUND_MODE, SoundcoreProtocol.CMD_SOUND_MODE_GET, soundModeBytes)
                }
            }
            SoundcoreProtocol.GROUP_EQ to SoundcoreProtocol.CMD_EQ_GET -> {
                sendRx(SoundcoreProtocol.GROUP_EQ, SoundcoreProtocol.CMD_EQ_GET, SoundcoreProtocol.buildEqSet(eqPresetId, eqBands))
            }
            SoundcoreProtocol.GROUP_EQ to SoundcoreProtocol.CMD_EQ_SET -> {
                SoundcoreProtocol.parseEq(frame.payload)?.let {
                    eqPresetId = it.presetId
                    eqBands = it.bands
                    sendRx(SoundcoreProtocol.GROUP_EQ, SoundcoreProtocol.CMD_EQ_GET, SoundcoreProtocol.buildEqSet(eqPresetId, eqBands))
                }
            }
        }
    }

    private fun sendRx(group: Int, id: Int, payload: ByteArray) {
        outgoing.trySend(SoundcoreFrameCodec.encodeRx(group, id, payload))
    }
}
