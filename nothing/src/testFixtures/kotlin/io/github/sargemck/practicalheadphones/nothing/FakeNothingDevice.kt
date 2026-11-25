package io.github.sargemck.practicalheadphones.nothing

import kotlinx.coroutines.channels.Channel
import java.io.IOException

/**
 * In-memory Nothing Ear / Headphone (1) simulator for deterministic JVM tests.
 */
class FakeNothingDevice : NothingTransport {
    private val outgoing = Channel<ByteArray?>(Channel.UNLIMITED)
    private val parser = NothingFrameParser()
    private var linkUp = true

    var leftBattery: Int = 85
    var rightBattery: Int = 80
    var caseBattery: Int = 75
    var ancMode: NothingAncMode = NothingAncMode.ANC_ADAPTIVE

    override suspend fun read(): ByteArray? = outgoing.receive()

    override suspend fun write(bytes: ByteArray) {
        if (!linkUp) throw IOException("link down")
        for (frame in parser.feed(bytes)) {
            handle(frame)
        }
    }

    override fun close() {
        linkUp = false
        outgoing.trySend(null)
    }

    private fun handle(frame: NothingFrame) {
        when (frame.command to frame.direction) {
            NothingProtocol.CMD_DEVICE_INFO to NothingProtocol.DIR_GET -> {
                send(NothingProtocol.CMD_DEVICE_INFO, NothingProtocol.DIR_ANSWER, byteArrayOf(0x01, 0x02))
            }
            NothingProtocol.CMD_BATTERY to NothingProtocol.DIR_GET -> {
                send(
                    NothingProtocol.CMD_BATTERY,
                    NothingProtocol.DIR_ANSWER,
                    byteArrayOf(0x03, 0x02, leftBattery.toByte(), 0x03, rightBattery.toByte(), 0x04, caseBattery.toByte()),
                )
            }
            NothingProtocol.CMD_ANC_GET to NothingProtocol.DIR_GET -> {
                send(NothingProtocol.CMD_ANC_GET, NothingProtocol.DIR_ANSWER, byteArrayOf(0x01, ancMode.code.toByte()))
            }
            NothingProtocol.CMD_ANC_SET to NothingProtocol.DIR_SET -> {
                NothingProtocol.parseAnc(frame.payload)?.let { ancMode = it }
                send(NothingProtocol.CMD_ANC_SET, NothingProtocol.DIR_ACK, byteArrayOf(0x00))
                send(NothingProtocol.CMD_ANC_GET, NothingProtocol.DIR_EVENT, byteArrayOf(0x01, ancMode.code.toByte()))
            }
        }
    }

    private fun send(cmd: Int, dir: Int, payload: ByteArray) {
        outgoing.trySend(NothingFrameCodec.encode(cmd, dir, payload))
    }
}
