package io.github.sargemck.practicalheadphones.sony

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

/**
 * Coroutine-based Sony MDR connection over [SonyTransport].
 *
 * Handles 1-bit toggling sequence numbers, immediate ACK emission for every incoming `DATA_MDR` /
 * `DATA_MDR_NO2` frame, serialized request/reply matching, and unsolicited notification emission.
 */
class SonyConnection(
    private val transport: SonyTransport,
    scope: CoroutineScope,
    private val replyTimeout: Duration = 3.seconds,
    private val ackTimeout: Duration = 2.seconds,
) {
    private val parser = SonyFrameParser()
    private val writeMutex = Mutex()
    private val requestMutex = Mutex()

    private val _state = MutableStateFlow<SonyConnectionState>(SonyConnectionState.Open)
    val state: StateFlow<SonyConnectionState> = _state.asStateFlow()

    private val _notifications = MutableSharedFlow<SonyFrame>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val notifications: SharedFlow<SonyFrame> = _notifications.asSharedFlow()

    @Volatile private var seq = 0
    @Volatile private var ackChannel: Channel<Int>? = null
    @Volatile private var pending: Pending? = null

    private val reader: Job = scope.launch { readLoop() }

    fun close() = closeWith(SonyCloseReason.Local)

    /** Sends a command payload, waits for its ACK, and awaits a reply matching [match]. */
    suspend fun request(
        payload: ByteArray,
        timeout: Duration = replyTimeout,
        match: (ByteArray) -> Boolean,
    ): ByteArray = requestMutex.withLock {
        val p = Pending(match)
        pending = p
        try {
            checkOpen()
            sendAndAwaitAck(payload)
            val reply = withTimeoutOrNull(timeout) { p.replies.receive() }
                ?: throw SonyException.Timeout("Sony reply timed out for cmd 0x%02X".format(payload.firstOrNull()?.toInt()?.and(0xFF) ?: 0))
            reply
        } finally {
            pending = null
        }
    }

    /** Sends a command payload and waits only for its ACK (e.g. SET commands whose NTFY follows asynchronously). */
    suspend fun sendCommand(payload: ByteArray) = requestMutex.withLock {
        checkOpen()
        sendAndAwaitAck(payload)
    }

    /** Sends a command expected to drop the link (e.g. power off). */
    suspend fun sendExpectingDisconnect(payload: ByteArray) = requestMutex.withLock {
        checkOpen()
        try {
            sendAndAwaitAck(payload)
        } catch (_: SonyException.Disconnected) {
            // Expected when the headphones power off immediately.
        }
    }

    private suspend fun sendAndAwaitAck(payload: ByteArray) {
        val acks = Channel<Int>(Channel.UNLIMITED)
        ackChannel = acks
        try {
            val frame = SonyFrame(SonyFrameCodec.DATA_MDR, seq, payload)
            writeRaw(SonyFrameCodec.encode(frame))
            val nextSeq = withTimeoutOrNull(ackTimeout) { acks.receive() }
            if (nextSeq != null) {
                seq = nextSeq and 0x01
            } else {
                seq = 1 - seq
            }
        } finally {
            ackChannel = null
        }
    }

    private suspend fun writeRaw(bytes: ByteArray) = writeMutex.withLock {
        try {
            transport.write(bytes)
        } catch (e: IOException) {
            val reason = SonyCloseReason.IoError(e.message)
            closeWith(reason)
            throw SonyException.Disconnected(reason)
        }
    }

    private suspend fun readLoop() {
        val reason = try {
            while (true) {
                val chunk = transport.read() ?: break
                for (frame in parser.feed(chunk)) {
                    dispatch(frame)
                }
            }
            SonyCloseReason.RemoteClosed
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            SonyCloseReason.IoError(e.message)
        }
        closeWith(reason)
    }

    private suspend fun dispatch(frame: SonyFrame) {
        if (frame.dataType == SonyFrameCodec.ACK) {
            ackChannel?.trySend(frame.seq)
            return
        }
        if (frame.dataType == SonyFrameCodec.DATA_MDR || frame.dataType == SonyFrameCodec.DATA_MDR_NO2) {
            // Always ACK immediately with 1 - incoming.seq before processing.
            runCatching { writeRaw(SonyFrameCodec.encode(SonyFrame(SonyFrameCodec.ACK, 1 - (frame.seq and 0x01)))) }
        }
        if (frame.payload.isEmpty()) return
        val p = pending
        if (p != null && p.match(frame.payload)) {
            p.replies.trySend(frame.payload)
        } else {
            _notifications.tryEmit(frame)
        }
    }

    private fun checkOpen() {
        (state.value as? SonyConnectionState.Closed)?.let { throw SonyException.Disconnected(it.reason) }
    }

    private fun closeWith(reason: SonyCloseReason) {
        if (!_state.compareAndSet(SonyConnectionState.Open, SonyConnectionState.Closed(reason))) return
        val err = SonyException.Disconnected(reason)
        ackChannel?.close(err)
        pending?.replies?.close(err)
        transport.close()
        if (reason !is SonyCloseReason.RemoteClosed && reason !is SonyCloseReason.IoError) reader.cancel()
    }

    private class Pending(val match: (ByteArray) -> Boolean) {
        val replies = Channel<ByteArray>(Channel.UNLIMITED)
    }
}
