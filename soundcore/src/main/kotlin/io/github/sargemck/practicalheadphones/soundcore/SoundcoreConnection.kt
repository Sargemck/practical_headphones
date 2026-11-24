package io.github.sargemck.practicalheadphones.soundcore

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

class SoundcoreConnection(
    private val transport: SoundcoreTransport,
    scope: CoroutineScope,
    private val replyTimeout: Duration = 3.seconds,
) {
    private val parser = SoundcoreFrameParser(expectTx = false)
    private val mutex = Mutex()

    private val _state = MutableStateFlow<SoundcoreConnectionState>(SoundcoreConnectionState.Open)
    val state: StateFlow<SoundcoreConnectionState> = _state.asStateFlow()

    private val _notifications = MutableSharedFlow<SoundcoreFrame>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val notifications: SharedFlow<SoundcoreFrame> = _notifications.asSharedFlow()

    @Volatile private var pending: Pending? = null

    private val reader: Job = scope.launch { readLoop() }

    fun close() = closeWith(SoundcoreCloseReason.Local)

    suspend fun request(
        cmdGroup: Int,
        cmdId: Int,
        payload: ByteArray = ByteArray(0),
        timeout: Duration = replyTimeout,
        match: (SoundcoreFrame) -> Boolean = { it.cmdGroup == cmdGroup && it.cmdId == cmdId },
    ): SoundcoreFrame = mutex.withLock {
        val p = Pending(match)
        pending = p
        try {
            checkOpen()
            writeRaw(SoundcoreFrameCodec.encodeTx(cmdGroup, cmdId, payload))
            withTimeoutOrNull(timeout) { p.replies.receive() }
                ?: throw SoundcoreException.Timeout("Soundcore timeout for %02X:%02X".format(cmdGroup, cmdId))
        } finally {
            pending = null
        }
    }

    suspend fun send(cmdGroup: Int, cmdId: Int, payload: ByteArray = ByteArray(0)) = mutex.withLock {
        checkOpen()
        writeRaw(SoundcoreFrameCodec.encodeTx(cmdGroup, cmdId, payload))
    }

    private suspend fun writeRaw(bytes: ByteArray) {
        try {
            transport.write(bytes)
        } catch (e: IOException) {
            val reason = SoundcoreCloseReason.IoError(e.message)
            closeWith(reason)
            throw SoundcoreException.Disconnected(reason)
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
            SoundcoreCloseReason.RemoteClosed
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            SoundcoreCloseReason.IoError(e.message)
        }
        closeWith(reason)
    }

    private fun checkOpen() {
        (state.value as? SoundcoreConnectionState.Closed)?.let { throw SoundcoreException.Disconnected(it.reason) }
    }

    private fun closeWith(reason: SoundcoreCloseReason) {
        if (!_state.compareAndSet(SoundcoreConnectionState.Open, SoundcoreConnectionState.Closed(reason))) return
        pending?.replies?.close(SoundcoreException.Disconnected(reason))
        transport.close()
        if (reason !is SoundcoreCloseReason.RemoteClosed && reason !is SoundcoreCloseReason.IoError) reader.cancel()
    }

    private class Pending(val match: (SoundcoreFrame) -> Boolean) {
        val replies = Channel<SoundcoreFrame>(Channel.UNLIMITED)
    }
}
