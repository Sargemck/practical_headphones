package io.github.sargemck.practicalheadphones

import io.github.sargemck.practicalheadphones.bmap.Address
import io.github.sargemck.practicalheadphones.bmap.BmapFrame
import io.github.sargemck.practicalheadphones.bmap.BmapOp
import io.github.sargemck.practicalheadphones.bmap.MacAddress

/**
 * Spike send allowlist (M0, extended in S1). Every outgoing frame goes through [refusal];
 * anything not listed is refused, so a typo in `send` can't rename the headphones or overwrite a mode.
 */
object SpikeGuard {
    private const val AUTH_FBLOCK = 18
    private const val AUDIO_MODES = 31
    private const val ROUTE_UP: Byte = 0x82.toByte()

    /** Unpairs devices or is unknown (bosectl NOTES block 4): never sent, with any op. */
    private val NEVER = setOf(Address(4, 3), Address(4, 7), Address(4, 10), Address(4, 11))
    private val ROUTING = Address(4, 12)
    private val CONNECT = Address(4, 1)
    private val DISCONNECT = Address(4, 2)

    /**
     * S1: MACs allowed for the session, set by the `allow` command after checking them against [4.4].
     * [routeMacs] may be routed to ([4.12]); [linkMacs] may be disconnected and reconnected ([4.2] / [4.1]).
     * Never in code or commits.
     */
    @Volatile var routeMacs: Set<MacAddress> = emptySet()
    @Volatile var linkMacs: Set<MacAddress> = emptySet()

    /** Null if the frame may be sent, otherwise the reason it's refused. */
    fun refusal(frame: BmapFrame): String? = when {
        frame.fblock == AUTH_FBLOCK -> "fblock 18 (authentication) is never touched"
        frame.address in NEVER -> "${frame.address} unpairs devices or is unknown: never sent"
        frame.op == BmapOp.GET -> null
        frame.op == BmapOp.START && frame.fblock == AUDIO_MODES && frame.func in setOf(1, 3) -> null
        frame.op == BmapOp.SETGET && frame.fblock == AUDIO_MODES && frame.func == 10 -> null
        frame.op == BmapOp.START && frame.address == ROUTING -> {
            val p = frame.payload
            when {
                p.size != 7 || p[0] != ROUTE_UP -> "4.12 payload must be [0x82] + MAC"
                MacAddress.of(p, 1) !in routeMacs -> "4.12 to a MAC that isn't allowed this session"
                else -> null
            }
        }
        frame.op == BmapOp.START && (frame.address == CONNECT || frame.address == DISCONNECT) -> {
            // The bare MAC, or one 0x00 flags byte then the MAC (mirrors 4.12's [flags, MAC]). Nothing else.
            val p = frame.payload
            val mac = when {
                p.size == 6 -> MacAddress.of(p)
                p.size == 7 && p[0] == 0.toByte() -> MacAddress.of(p, 1)
                else -> null
            }
            when {
                mac == null -> "${frame.address} payload must be MAC or [0x00] + MAC"
                mac !in linkMacs -> "${frame.address} to a MAC that isn't allowed this session"
                else -> null
            }
        }
        else -> "${frame.op} on ${frame.address} is not on the spike allowlist"
    }
}
