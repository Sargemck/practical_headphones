package io.github.sargemck.practicalheadphones.sony

import kotlinx.coroutines.channels.Channel
import java.io.IOException

/**
 * In-memory Sony MDR headset/earbuds simulator for deterministic JVM tests.
 * Supports WH-1000XM5 (v2 0x17), WH-1000XM6 (v2 0x17 GET + 0x19 NTFY), WH-1000XM4 (v1 0x02),
 * and WF-1000XM5 (earbuds L/R/Case battery).
 */
class FakeSonyDevice(
    val protocolV2: Boolean = true,
    val inquiredType: Int = if (protocolV2) 0x17 else 0x02,
    val emitXm6Ntfy: Boolean = false,
    val earbudsBattery: Boolean = false,
) : SonyTransport {
    private val outgoing = Channel<ByteArray?>(Channel.UNLIMITED)
    private val parser = SonyFrameParser()
    private var linkUp = true
    private var deviceSeq = 0

    var mode: SonyAncMode = SonyAncMode.ANC
    var ambientLevel: Int = 12
    var focusOnVoice: Boolean = false
    var batteryPercent: Int = 82
    var leftBattery: Int = 85
    var rightBattery: Int = 80
    var cradleBattery: Int = 90
    var eqPreset: SonyEqPreset = SonyEqPreset.OFF
    var eqBands: List<Int> = listOf(0, 0, 0, 0, 0, 0)
    var speakToChat: Boolean = false

    val received = mutableListOf<SonyFrame>()

    override suspend fun read(): ByteArray? = outgoing.receive()

    override suspend fun write(bytes: ByteArray) {
        if (!linkUp) throw IOException("link down")
        for (frame in parser.feed(bytes)) {
            received += frame
            if (frame.dataType == SonyFrameCodec.ACK) continue
            // ACK the host's command with 1 - frame.seq
            sendRaw(SonyFrame(SonyFrameCodec.ACK, 1 - (frame.seq and 0x01)))
            handlePayload(frame.payload)
        }
    }

    override fun close() {
        linkUp = false
        outgoing.trySend(null)
    }

    private fun handlePayload(p: ByteArray) {
        if (p.isEmpty()) return
        when (p[0].toInt() and 0xFF) {
            SonyProtocol.CONNECT_GET_PROTOCOL_INFO -> {
                val reply = if (protocolV2) {
                    byteArrayOf(SonyProtocol.CONNECT_RET_PROTOCOL_INFO.toByte(), 0x00, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00)
                } else {
                    byteArrayOf(SonyProtocol.CONNECT_RET_PROTOCOL_INFO.toByte(), 0x00, 0x01, 0x00)
                }
                sendData(reply)
            }
            SonyProtocol.COMMON_GET_BATTERY_LEVEL -> {
                val type = p.getOrNull(1)?.toInt()?.and(0xFF) ?: return
                when {
                    type == SonyProtocol.BATTERY_SINGLE && !earbudsBattery -> sendData(
                        byteArrayOf(SonyProtocol.COMMON_RET_BATTERY_LEVEL.toByte(), 0x00, batteryPercent.toByte(), 0x00),
                    )
                    type == SonyProtocol.BATTERY_LEFT_RIGHT && earbudsBattery -> sendData(
                        byteArrayOf(
                            SonyProtocol.COMMON_RET_BATTERY_LEVEL.toByte(),
                            0x01,
                            leftBattery.toByte(),
                            0x00,
                            rightBattery.toByte(),
                            0x00,
                        ),
                    )
                    type == SonyProtocol.BATTERY_CRADLE && earbudsBattery -> sendData(
                        byteArrayOf(SonyProtocol.COMMON_RET_BATTERY_LEVEL.toByte(), 0x02, cradleBattery.toByte(), 0x00),
                    )
                }
            }
            SonyProtocol.NCASM_GET_PARAM -> {
                val reqType = p.getOrNull(1)?.toInt()?.and(0xFF) ?: return
                if (reqType != inquiredType) return
                sendData(buildNcAsmReply(SonyProtocol.NCASM_RET_PARAM, inquiredType))
            }
            SonyProtocol.NCASM_SET_PARAM -> {
                val parsed = parseSet(p) ?: return
                mode = parsed.first
                ambientLevel = parsed.second
                focusOnVoice = parsed.third
                val ntfyType = if (emitXm6Ntfy) SonyProtocol.XM6_NTFY_INQUIRED else inquiredType
                sendData(buildNcAsmReply(SonyProtocol.NCASM_NTFY_PARAM, ntfyType))
            }
            SonyProtocol.EQEBB_GET_PARAM -> {
                sendData(buildEqReply(SonyProtocol.EQEBB_RET_PARAM))
            }
            SonyProtocol.EQEBB_SET_PARAM -> {
                if (p.size >= 4) {
                    eqPreset = SonyEqPreset.fromCode(p[2].toInt() and 0xFF) ?: SonyEqPreset.CUSTOM
                    val count = p[3].toInt() and 0xFF
                    if (p.size >= 4 + count) {
                        eqBands = List(count) { i -> ((p[4 + i].toInt() and 0xFF) - 10).coerceIn(-10, 10) }
                    }
                    sendData(buildEqReply(SonyProtocol.EQEBB_NTFY_PARAM))
                }
            }
            SonyProtocol.SYSTEM_GET_EXT_PARAM -> {
                if (p.getOrNull(1)?.toInt()?.and(0xFF) == SonyProtocol.SMART_TALKING_MODE_TYPE) {
                    sendData(
                        byteArrayOf(
                            SonyProtocol.SYSTEM_RET_EXT_PARAM.toByte(),
                            SonyProtocol.SMART_TALKING_MODE_TYPE.toByte(),
                            if (speakToChat) 0x01 else 0x00,
                        ),
                    )
                }
            }
            SonyProtocol.SYSTEM_SET_EXT_PARAM -> {
                if (p.size >= 3 && (p[1].toInt() and 0xFF) == SonyProtocol.SMART_TALKING_MODE_TYPE) {
                    speakToChat = (p[2].toInt() and 0xFF) == 0x01
                    sendData(
                        byteArrayOf(
                            SonyProtocol.SYSTEM_NTFY_EXT_PARAM.toByte(),
                            SonyProtocol.SMART_TALKING_MODE_TYPE.toByte(),
                            if (speakToChat) 0x01 else 0x00,
                        ),
                    )
                }
            }
            SonyProtocol.POWER_SET_STATUS -> {
                close()
            }
        }
    }

    private fun parseSet(p: ByteArray): Triple<SonyAncMode, Int, Boolean>? {
        val type = p.getOrNull(1)?.toInt()?.and(0xFF) ?: return null
        return when (type) {
            0x02 -> {
                if (p.size < 8) return null
                val on = (p[2].toInt() and 0xFF) != 0x00
                val ncVal = p[4].toInt() and 0xFF
                val m = when {
                    !on -> SonyAncMode.OFF
                    ncVal == 0x00 -> SonyAncMode.AMBIENT
                    else -> SonyAncMode.ANC
                }
                Triple(m, p[7].toInt() and 0xFF, (p[6].toInt() and 0xFF) == 0x01)
            }
            0x17 -> {
                if (p.size < 7) return null
                val on = (p[3].toInt() and 0xFF) == 0x01
                val amb = (p[4].toInt() and 0xFF) == 0x01
                val m = if (!on) SonyAncMode.OFF else if (amb) SonyAncMode.AMBIENT else SonyAncMode.ANC
                Triple(m, p[6].toInt() and 0xFF, (p[5].toInt() and 0xFF) == 0x01)
            }
            else -> null
        }
    }

    private fun buildNcAsmReply(cmd: Int, type: Int): ByteArray {
        val on = if (mode != SonyAncMode.OFF) 1 else 0
        val amb = if (mode == SonyAncMode.AMBIENT) 1 else 0
        val voice = if (focusOnVoice) 1 else 0
        return when (type) {
            0x02 -> byteArrayOf(
                cmd.toByte(),
                0x02,
                on.toByte(),
                0x02,
                if (mode == SonyAncMode.ANC) 0x02 else 0x00,
                0x01,
                voice.toByte(),
                ambientLevel.toByte(),
            )
            SonyProtocol.XM6_NTFY_INQUIRED -> byteArrayOf(
                cmd.toByte(),
                SonyProtocol.XM6_NTFY_INQUIRED.toByte(),
                0x01,
                on.toByte(),
                amb.toByte(),
                voice.toByte(),
                ambientLevel.toByte(),
                0x00,
                0x00,
            )
            else -> byteArrayOf(
                cmd.toByte(),
                type.toByte(),
                0x01,
                on.toByte(),
                amb.toByte(),
                voice.toByte(),
                ambientLevel.toByte(),
            )
        }
    }

    private fun buildEqReply(cmd: Int): ByteArray {
        val out = ByteArray(4 + eqBands.size)
        out[0] = cmd.toByte()
        out[1] = SonyProtocol.EQEBB_INQUIRED_PRESET_AND_BANDS.toByte()
        out[2] = eqPreset.code.toByte()
        out[3] = eqBands.size.toByte()
        eqBands.forEachIndexed { i, v -> out[4 + i] = (v + 10).toByte() }
        return out
    }

    private fun sendData(payload: ByteArray) {
        sendRaw(SonyFrame(SonyFrameCodec.DATA_MDR, deviceSeq, payload))
        deviceSeq = 1 - deviceSeq
    }

    private fun sendRaw(frame: SonyFrame) {
        outgoing.trySend(SonyFrameCodec.encode(frame))
    }
}
