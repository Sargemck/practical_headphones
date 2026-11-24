package io.github.sargemck.practicalheadphones.samsung

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class SamsungConnection(
    private val transport: SamsungTransport,
    scope: CoroutineScope,
    private val replyTimeout: Duration = 4.seconds,
) {
    private val parser = SamsungFrameParser()
    private val mutex = Mutex()

    private val _state = MutableStateFlow<SamsungConnectionState>(SamsungConnectionState.Open)
    val state: StateFlow<SamsungConnectionState> = _state.asStateFlow()

    private val _notifications = MutableSharedFlow<SamsungFrame>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val notifications: SharedFlow<SamsungFrame> = _notifications.asSharedFlow()

    @Volatile private var pending: Pending? = null

    private val reader: Job = scope.launch { readLoop() }

    fun close() = closeWith(SamsungCloseReason.Local)

    suspend fun request(
        msgId: Int,
        payload: ByteArray = ByteArray(0),
        timeout: Duration = replyTimeout,
        match: (SamsungFrame) -> Boolean,
    ): SamsungFrame = mutex.withLock {
        val p = Pending(match)
        pending = p
        try {
            checkOpen()
            writeRaw(SamsungFrameCodec.encode(msgId, payload, request = true))
            withTimeoutOrNull(timeout) { p.replies.receive() }
                ?: throw SamsungException.Timeout("Samsung timeout for msgId 0x%02X".format(msgId))
        } finally {
            pending = null
        }
    }

    suspend fun send(msgId: Int, payload: ByteArray = ByteArray(0)) = mutex.withLock {
        checkOpen()
        writeRaw(SamsungFrameCodec.encode(msgId, payload, request = true))
    }

    private suspend fun writeRaw(bytes: ByteArray) {
        try {
            transport.write(bytes)
        } catch (e: IOException) {
            val reason = SamsungCloseReason.IoError(e.message)
            closeWith(reason)
            throw SamsungException.Disconnected(reason)
        }
    }

    private suspend fun readLoop() {
        val reason = try {
            while (true) {
                val chunk = transport.read() ?: break
                for (frame in parser.feed(chunk)) {
                    val p = pending
                    if (p != null && p.match(frame)) {
                        p.replies.trySend(frame)
                    } else {
                        _notifications.tryEmit(frame)
                    }
                }
            }
            SamsungCloseReason.RemoteClosed
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            SamsungCloseReason.IoError(e.message)
        }
        closeWith(reason)
    }

    private fun checkOpen() {
        (state.value as? SamsungConnectionState.Closed)?.let { throw SamsungException.Disconnected(it.reason) }
    }

    private fun closeWith(reason: SamsungCloseReason) {
        if (!_state.compareAndSet(SamsungConnectionState.Open, SamsungConnectionState.Closed(reason))) return
        pending?.replies?.close(SamsungException.Disconnected(reason))
        transport.close()
        if (reason !is SamsungCloseReason.RemoteClosed && reason !is SamsungCloseReason.IoError) reader.cancel()
    }

    private class Pending(val match: (SamsungFrame) -> Boolean) {
        val replies = Channel<SamsungFrame>(Channel.UNLIMITED)
    }
}
