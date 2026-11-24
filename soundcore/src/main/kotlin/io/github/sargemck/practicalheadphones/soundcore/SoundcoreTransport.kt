package io.github.sargemck.practicalheadphones.soundcore

import java.io.IOException

interface SoundcoreTransport {
    suspend fun read(): ByteArray?
    suspend fun write(bytes: ByteArray)
    fun close()
}

sealed interface SoundcoreCloseReason {
    data object RemoteClosed : SoundcoreCloseReason
    data class IoError(val message: String?) : SoundcoreCloseReason
    data object Unresponsive : SoundcoreCloseReason
    data object Local : SoundcoreCloseReason
}

sealed interface SoundcoreConnectionState {
    data object Open : SoundcoreConnectionState
    data class Closed(val reason: SoundcoreCloseReason) : SoundcoreConnectionState
}

open class SoundcoreException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class Disconnected(val reason: SoundcoreCloseReason) : SoundcoreException("Soundcore link closed: $reason")
    class Timeout(message: String) : SoundcoreException(message)
}
