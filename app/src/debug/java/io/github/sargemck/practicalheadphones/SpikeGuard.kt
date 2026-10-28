package io.github.sargemck.practicalheadphones

import io.github.sargemck.practicalheadphones.bmap.BmapFrame
import io.github.sargemck.practicalheadphones.bmap.BmapOp

/**
 * M0 send allowlist. Every outgoing frame goes through [refusal]; anything not listed
 * is refused, so a typo in `send` can't rename the headphones or overwrite a mode.
 */
object SpikeGuard {
    private const val AUTH_FBLOCK = 18
    private const val AUDIO_MODES = 31

    /** Null if the frame may be sent, otherwise the reason it's refused. */
    fun refusal(frame: BmapFrame): String? = when {
        frame.fblock == AUTH_FBLOCK -> "fblock 18 (authentication) is never touched"
        frame.op == BmapOp.GET -> null
        frame.op == BmapOp.START && frame.fblock == AUDIO_MODES && frame.func in setOf(1, 3) -> null
        frame.op == BmapOp.SETGET && frame.fblock == AUDIO_MODES && frame.func == 10 -> null
        else -> "${frame.op} on ${frame.address} is not on the M0 allowlist"
    }
}
