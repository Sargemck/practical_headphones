package io.github.sargemck.practicalheadphones.jbl

/**
 * Abstract byte transport for JBL's BLE GATT characteristics (`...0001` notify / `...0002` write).
 */
interface JblTransport {
    suspend fun read(): ByteArray?
    suspend fun write(bytes: ByteArray)
    fun close()
}

sealed interface JblCloseReason {
    data object RemoteClosed : JblCloseReason
    data class IoError(val message: String?) : JblCloseReason
    data object Unresponsive : JblCloseReason
    data object Local : JblCloseReason
}

sealed interface JblConnectionState {
    data object Open : JblConnectionState
    data class Closed(val reason: JblCloseReason) : JblConnectionState
}

open class JblException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Timeout(message: String) : JblException(message)
    class Disconnected(val reason: JblCloseReason) : JblException("JBL disconnected: $reason")
}
