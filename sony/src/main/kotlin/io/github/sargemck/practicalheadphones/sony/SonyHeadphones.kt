package io.github.sargemck.practicalheadphones.sony

import kotlin.time.Duration.Companion.milliseconds

/**
 * High-level typed client for Sony MDR v1/v2 headphones and earbuds.
 */
class SonyHeadphones(private val connection: SonyConnection) {
    /** Handshake: returns true if protocol v2 (>= 8 byte reply), false if v1. */
    suspend fun handshake(): Boolean {
        val reply = connection.request(SonyProtocol.buildProtocolInfoGet()) {
            it.isNotEmpty() && (it[0].toInt() and 0xFF) == SonyProtocol.CONNECT_RET_PROTOCOL_INFO
        }
        return reply.size >= 8
    }

    /**
     * Probes NC/ASM inquired types in generation order (v2: `0x17`, `0x15`, `0x22`; v1: `0x02`),
     * returning the first state that answers, or null if unsupported.
     */
    suspend fun probeNcAsm(preferV2: Boolean = true): SonyAncState? {
        val candidates = if (preferV2) {
            SonyProtocol.CANDIDATES_V2 + SonyProtocol.CANDIDATES_V1
        } else {
            SonyProtocol.CANDIDATES_V1 + SonyProtocol.CANDIDATES_V2
        }
        for (candidate in candidates) {
            val reply = try {
                connection.request(SonyProtocol.buildNcAsmGet(candidate), timeout = 1500.milliseconds) {
                    SonyProtocol.parseNcAsm(it, candidate) != null
                }
            } catch (_: SonyException.Timeout) {
                continue
            }
            SonyProtocol.parseNcAsm(reply, candidate)?.let { return it }
        }
        return null
    }

    suspend fun setNcAsm(
        inquiredType: Int,
        mode: SonyAncMode,
        ambientLevel: Int,
        focusOnVoice: Boolean,
        ncValue: Int = SonyProtocol.DEFAULT_NC_VALUE,
    ) {
        connection.sendCommand(
            SonyProtocol.buildNcAsmSet(inquiredType, mode, ambientLevel, focusOnVoice, ncValue),
        )
    }

    /** Reads single battery first; if earbuds, also reads L/R and cradle battery. */
    suspend fun getBattery(): SonyBattery {
        val lr = try {
            val reply = connection.request(SonyProtocol.buildBatteryGet(SonyProtocol.BATTERY_LEFT_RIGHT), timeout = 1200.milliseconds) {
                SonyProtocol.parseBattery(it)?.let { b -> b.left != null || b.right != null } == true
            }
            SonyProtocol.parseBattery(reply)
        } catch (_: SonyException.Timeout) {
            null
        }
        if (lr != null) {
            val cradle = try {
                val reply = connection.request(SonyProtocol.buildBatteryGet(SonyProtocol.BATTERY_CRADLE), timeout = 800.milliseconds) {
                    SonyProtocol.parseBattery(it)?.cradle != null
                }
                SonyProtocol.parseBattery(reply)?.cradle
            } catch (_: SonyException.Timeout) {
                null
            }
            return lr.copy(cradle = cradle)
        }
        val single = connection.request(SonyProtocol.buildBatteryGet(SonyProtocol.BATTERY_SINGLE)) {
            SonyProtocol.parseBattery(it) != null
        }
        return SonyProtocol.parseBattery(single) ?: throw SonyException.Unsupported("Malformed Sony battery reply")
    }

    suspend fun getEq(): SonyEqState? = try {
        val reply = connection.request(SonyProtocol.buildEqGet(), timeout = 1500.milliseconds) {
            SonyProtocol.parseEq(it) != null
        }
        SonyProtocol.parseEq(reply)
    } catch (_: SonyException.Timeout) {
        null
    }

    suspend fun setEq(preset: SonyEqPreset, bands: List<Int>) {
        connection.sendCommand(SonyProtocol.buildEqSet(preset, bands))
    }

    suspend fun getSpeakToChat(): Boolean? = try {
        val reply = connection.request(SonyProtocol.buildSpeakToChatGet(), timeout = 1200.milliseconds) {
            SonyProtocol.parseSpeakToChat(it) != null
        }
        SonyProtocol.parseSpeakToChat(reply)
    } catch (_: SonyException.Timeout) {
        null
    }

    suspend fun setSpeakToChat(enabled: Boolean) {
        connection.sendCommand(SonyProtocol.buildSpeakToChatSet(enabled))
    }

    suspend fun powerOff() {
        connection.sendExpectingDisconnect(SonyProtocol.buildPowerOff())
    }
}
