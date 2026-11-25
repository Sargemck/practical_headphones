package io.github.sargemck.practicalheadphones.jbl

import kotlinx.coroutines.channels.Channel
import java.io.IOException

/**
 * In-memory JBL BLE GATT earbuds simulator (JBL TUNE230NC TWS / Wave Buds 2).
 */
class FakeJblDevice : JblTransport {
    private val outgoing = Channel<ByteArray?>(Channel.UNLIMITED)
    private val parser = JblFrameParser()
    private var linkUp = true

    var mode: JblMode = JblMode.ANC
    var leftBattery: Int = 80
    var rightBattery: Int = 75
    var caseBattery: Int = 90

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

    private fun handle(frame: JblFrame) {
        val op = frame.payload.firstOrNull()?.toInt()?.and(0xFF) ?: return
        when (frame.cmd) {
            JblProtocol.CMD_NOTIFY_ENABLE -> {
                send(JblProtocol.CMD_NOTIFY_ENABLE, byteArrayOf(0x02, 0x01, 0x00))
            }
            JblProtocol.CMD_BATTERY -> if (op == 0x00) {
                send(
                    JblProtocol.CMD_BATTERY,
                    byteArrayOf(0x01, 0x00, 0x00, leftBattery.toByte(), rightBattery.toByte(), 0x00, caseBattery.toByte()),
                )
            }
            JblProtocol.CMD_MODES -> when (op) {
                JblProtocol.OP_MODE_GET -> sendModeReport()
                JblProtocol.OP_MODE_SET -> {
                    val reportLike = JblFrame(JblProtocol.CMD_MODES, frame.payload.copyOf().also { it[0] = JblProtocol.OP_MODE_REPORT.toByte() })
                    JblProtocol.parseModeReport(reportLike)?.let { mode = it }
                    sendModeReport()
                }
            }
        }
    }

    private fun sendModeReport() {
        val anc: Byte = if (mode == JblMode.ANC) 1 else 0
        val amb: Byte = if (mode == JblMode.AMBIENT) 1 else 0
        val talk: Byte = if (mode == JblMode.TALKTHRU) 1 else 0
        send(JblProtocol.CMD_MODES, byteArrayOf(JblProtocol.OP_MODE_REPORT.toByte(), 0x01, anc, 0x02, amb, 0x03, talk))
    }

    private fun send(cmd: Int, payload: ByteArray) {
        outgoing.trySend(JblFrameCodec.encode(cmd, payload))
    }
}
