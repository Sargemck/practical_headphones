package io.github.sargemck.practicalheadphones.bmap

sealed interface FrameEvent {
    data class Frame(val frame: BmapFrame) : FrameEvent

    /** Bytes dropped while looking for a valid frame start. */
    class Skipped(val bytes: ByteArray) : FrameEvent {
        override fun toString(): String = "Skipped(${Hex.format(bytes)})"
    }
}

/**
 * Streaming BMAP frame parser. Feed it bytes as they arrive from the socket; it keeps
 * partial frames between calls and returns every complete frame in order.
 *
 * BMAP has no sync byte. A frame start is accepted only when its op nibble is a valid
 * operator (0..7) and its fblock is in [knownFblocks]. Otherwise one byte is dropped and
 * parsing retries; consecutive dropped bytes are reported as one [FrameEvent.Skipped].
 * A corrupt length byte on an otherwise valid header can't be detected.
 */
class FrameParser(private val knownFblocks: Set<Int> = QC_ULTRA2_FBLOCKS) {
    private var buffer = ByteArray(0)

    /** Bytes held back waiting for the rest of a frame. */
    val buffered: Int get() = buffer.size

    fun feed(chunk: ByteArray): List<FrameEvent> {
        val data = buffer + chunk
        val events = mutableListOf<FrameEvent>()
        val skipped = mutableListOf<Byte>()
        var pos = 0

        fun flushSkipped() {
            if (skipped.isNotEmpty()) {
                events += FrameEvent.Skipped(skipped.toByteArray())
                skipped.clear()
            }
        }

        while (data.size - pos >= 3) {
            val fblock = data[pos].toInt() and 0xFF
            val func = data[pos + 1].toInt() and 0xFF
            val flags = data[pos + 2].toInt() and 0xFF
            val op = BmapOp.fromCode(flags and 0x0F)
            if (op == null || fblock !in knownFblocks) {
                skipped += data[pos]
                pos++
                continue
            }
            if (data.size - pos < BmapFrame.HEADER_SIZE) break
            val length = data[pos + 3].toInt() and 0xFF
            val end = pos + BmapFrame.HEADER_SIZE + length
            if (end > data.size) break
            flushSkipped()
            events += FrameEvent.Frame(
                BmapFrame(
                    fblock = fblock,
                    func = func,
                    op = op,
                    payload = data.copyOfRange(pos + BmapFrame.HEADER_SIZE, end),
                    deviceId = flags ushr 6,
                    port = (flags ushr 4) and 0x03,
                ),
            )
            pos = end
        }
        flushSkipped()
        buffer = data.copyOfRange(pos, data.size)
        return events
    }

    companion object {
        /** Function blocks the QC Ultra 2 answers on (bosectl NOTES.md function block map). */
        val QC_ULTRA2_FBLOCKS: Set<Int> = (0..9).toSet() + 18 + 31
    }
}
