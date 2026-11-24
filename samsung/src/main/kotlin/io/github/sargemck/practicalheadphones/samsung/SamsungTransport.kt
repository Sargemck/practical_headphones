package io.github.sargemck.practicalheadphones.samsung

import java.io.IOException

interface SamsungTransport {
    suspend fun read(): ByteArray?
    suspend fun write(bytes: ByteArray)
    fun close()
}

sealed interface SamsungCloseReason {
    data object RemoteClosed : SamsungCloseReason
    data class IoError(val message: String?) : SamsungCloseReason
    data object Unresponsive : SamsungCloseReason
    data object Local : SamsungCloseReason
}

sealed interface SamsungConnectionState {
    data object Open : SamsungConnectionState
    data class Closed(val reason: SamsungCloseReason) : SamsungConnectionState
}

open class SamsungException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class Disconnected(val reason: SamsungCloseReason) : SamsungException("Samsung link closed: $reason")
    class Timeout(message: String) : SamsungException(message)
}
