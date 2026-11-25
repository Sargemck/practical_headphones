package io.github.sargemck.practicalheadphones.nothing

interface NothingTransport {
    suspend fun read(): ByteArray?
    suspend fun write(bytes: ByteArray)
    fun close()
}

sealed interface NothingCloseReason {
    data object RemoteClosed : NothingCloseReason
    data class IoError(val message: String?) : NothingCloseReason
    data object Unresponsive : NothingCloseReason
    data object Local : NothingCloseReason
}

sealed interface NothingConnectionState {
    data object Open : NothingConnectionState
    data class Closed(val reason: NothingCloseReason) : NothingConnectionState
}

open class NothingException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Timeout(message: String) : NothingException(message)
    class Disconnected(val reason: NothingCloseReason) : NothingException("Nothing disconnected: $reason")
}
