package io.github.sargemck.practicalheadphones.sony

import java.io.IOException

interface SonyTransport {
    suspend fun read(): ByteArray?
    suspend fun write(bytes: ByteArray)
    fun close()
}

sealed interface SonyCloseReason {
    data object RemoteClosed : SonyCloseReason
    data class IoError(val message: String?) : SonyCloseReason
    data object Unresponsive : SonyCloseReason
    data object Local : SonyCloseReason
}

sealed interface SonyConnectionState {
    data object Open : SonyConnectionState
    data class Closed(val reason: SonyCloseReason) : SonyConnectionState
}

open class SonyException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class Disconnected(val reason: SonyCloseReason) : SonyException("Sony link closed: $reason")
    class Timeout(message: String) : SonyException(message)
    class Unsupported(message: String) : SonyException(message)
}
