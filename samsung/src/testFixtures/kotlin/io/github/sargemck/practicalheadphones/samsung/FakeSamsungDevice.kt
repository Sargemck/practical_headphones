package io.github.sargemck.practicalheadphones.samsung

import kotlinx.coroutines.channels.Channel
import java.io.IOException

/** In-memory Samsung Galaxy Buds simulator (Buds2, Buds2 Pro, Buds FE, Buds Live, Buds Pro). */
class FakeSamsungDevice : SamsungTransport {
    private val outgoing = Channel<ByteArray?>(Channel.UNLIMITED)
    private val parser = SamsungFrameParser()
    private var linkUp = true

    var leftBattery: Int = 88
    var rightBattery: Int = 84
    var caseBattery: Int = 65
    var noiseMode: SamsungNoiseMode = SamsungNoiseMode.ANC
    var eqPreset: SamsungEqPreset = SamsungEqPreset.NORMAL

    val received = mutableListOf<SamsungFrame>()

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

    private fun handle(frame: SamsungFrame) {
        when (frame.msgId) {
            SamsungProtocol.MSG_MANAGER_INFO -> sendExtendedStatus()
            SamsungProtocol.MSG_NOISE_CONTROLS -> {
                val mode = frame.payload.firstOrNull()?.let { SamsungNoiseMode.fromCode(it.toInt() and 0xFF) } ?: return
                noiseMode = mode
                send(SamsungProtocol.MSG_NOISE_CONTROLS_UPDATE, byteArrayOf(mode.code.toByte()))
            }
            SamsungProtocol.MSG_EQUALIZER -> {
                val preset = frame.payload.firstOrNull()?.let { SamsungEqPreset.fromCode(it.toInt() and 0xFF) } ?: return
                eqPreset = preset
                sendExtendedStatus()
            }
        }
    }

    private fun sendExtendedStatus() {
        val payload = ByteArray(38)
        payload[2] = leftBattery.toByte()
        payload[3] = rightBattery.toByte()
        payload[7] = caseBattery.toByte()
        payload[9] = eqPreset.code.toByte()
        payload[12] = noiseMode.code.toByte()
        send(SamsungProtocol.MSG_EXTENDED_STATUS_UPDATED, payload)
    }

    private fun send(msgId: Int, payload: ByteArray) {
        outgoing.trySend(SamsungFrameCodec.encode(msgId, payload, request = false))
    }
}
