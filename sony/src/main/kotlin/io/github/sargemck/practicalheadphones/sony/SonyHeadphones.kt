package io.github.sargemck.practicalheadphones.sony

import kotlin.time.Duration.Companion.milliseconds

/**
 * High-level typed client for Sony MDR v1/v2 headphones and earbuds.
 */
class SonyHeadphones(private val connection: SonyConnection) {
    /**
     * Handshake: returns true if protocol v2 (>= 8 byte reply), false if v1.
     * Retries up to 3 attempts (2 s each) per `omarchy-headphones/sony-bridge`, since freshly
     * opened RFCOMM channels occasionally drop the first frame.
     */
    suspend fun handshake(): Boolean {
        var lastTimeout: SonyException.Timeout? = null
        repeat(3) {
            try {
                val reply = connection.request(SonyProtocol.buildProtocolInfoGet(), timeout = 2000.milliseconds) {
                    it.isNotEmpty() && (it[0].toInt() and 0xFF) == SonyProtocol.CONNECT_RET_PROTOCOL_INFO
                }
                return reply.size >= 8
            } catch (e: SonyException.Timeout) {
                lastTimeout = e
            }
        }
        throw lastTimeout ?: SonyException.Timeout("Sony handshake timed out")
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

    /**
     * Reads battery, probing L/R + cradle first when [preferEarbuds] is true, or single battery
     * first on headband headphones so WH-series models never stall on an unanswered L/R query.
     */
    suspend fun getBattery(preferEarbuds: Boolean = false): SonyBattery {
        suspend fun queryEarbuds(): SonyBattery? {
            val lr = try {
                val reply = connection.request(SonyProtocol.buildBatteryGet(SonyProtocol.BATTERY_LEFT_RIGHT), timeout = 1200.milliseconds) {
                    SonyProtocol.parseBattery(it)?.let { b -> b.left != null || b.right != null } == true
                }
                SonyProtocol.parseBattery(reply)
            } catch (_: SonyException.Timeout) {
                null
            } ?: return null
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

        suspend fun querySingle(): SonyBattery? = try {
            val reply = connection.request(SonyProtocol.buildBatteryGet(SonyProtocol.BATTERY_SINGLE), timeout = 1200.milliseconds) {
                SonyProtocol.parseBattery(it) != null
            }
            SonyProtocol.parseBattery(reply)
        } catch (_: SonyException.Timeout) {
            null
        }

        val battery = if (preferEarbuds) {
            queryEarbuds() ?: querySingle()
        } else {
            querySingle() ?: queryEarbuds()
        }
        return battery ?: throw SonyException.Timeout("Sony battery query timed out")
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
