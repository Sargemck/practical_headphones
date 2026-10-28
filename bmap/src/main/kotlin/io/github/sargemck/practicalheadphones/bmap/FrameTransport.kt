package io.github.sargemck.practicalheadphones.bmap

/**
 * The byte link under a [BmapConnection], e.g. an RFCOMM socket in `:app`. It carries raw
 * bytes; framing happens in the connection. An [java.io.IOException] from either call means
 * the link is broken.
 */
interface FrameTransport {
    /** The next chunk of bytes, or null once the other side closed the link (`read() == -1`). */
    suspend fun read(): ByteArray?

    suspend fun write(bytes: ByteArray)

    fun close()
}
