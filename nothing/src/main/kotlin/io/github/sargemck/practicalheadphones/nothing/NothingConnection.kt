package io.github.sargemck.practicalheadphones.nothing

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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class NothingConnection(
    private val transport: NothingTransport,
    scope: CoroutineScope,
    private val replyTimeout: Duration = 3.seconds,
) {
    private val parser = NothingFrameParser()
    private val mutex = Mutex()

    private val _state = MutableStateFlow<NothingConnectionState>(NothingConnectionState.Open)
    val state: StateFlow<NothingConnectionState> = _state.asStateFlow()

    private val _notifications = MutableSharedFlow<NothingFrame>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val notifications: SharedFlow<NothingFrame> = _notifications.asSharedFlow()

    @Volatile private var pending: Pending? = null

    private val reader: Job = scope.launch { readLoop() }

    fun close() = closeWith(NothingCloseReason.Local)

    suspend fun request(
        command: Int,
        direction: Int = NothingProtocol.DIR_GET,
        payload: ByteArray = ByteArray(0),
        timeout: Duration = replyTimeout,
        match: (NothingFrame) -> Boolean = {
            it.command == command && (it.direction == NothingProtocol.DIR_ANSWER || it.direction == NothingProtocol.DIR_ACK)
        },
    ): NothingFrame = mutex.withLock {
        val p = Pending(match)
        pending = p
        try {
            checkOpen()
            writeRaw(NothingFrameCodec.encode(command, direction, payload))
            withTimeoutOrNull(timeout) { p.replies.receive() }
                ?: throw NothingException.Timeout("Nothing timeout for cmd 0x%02X".format(command))
        } finally {
            pending = null
        }
    }

    private suspend fun writeRaw(bytes: ByteArray) {
        try {
            transport.write(bytes)
        } catch (e: IOException) {
            val reason = NothingCloseReason.IoError(e.message)
            closeWith(reason)
            throw NothingException.Disconnected(reason)
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
            NothingCloseReason.RemoteClosed
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            NothingCloseReason.IoError(e.message)
        }
        closeWith(reason)
    }

    private fun checkOpen() {
        (state.value as? NothingConnectionState.Closed)?.let { throw NothingException.Disconnected(it.reason) }
    }

    private fun closeWith(reason: NothingCloseReason) {
        if (!_state.compareAndSet(NothingConnectionState.Open, NothingConnectionState.Closed(reason))) return
        pending?.replies?.close(NothingException.Disconnected(reason))
        transport.close()
        if (reason !is NothingCloseReason.RemoteClosed && reason !is NothingCloseReason.IoError) reader.cancel()
    }

    private class Pending(val match: (NothingFrame) -> Boolean) {
        val replies = Channel<NothingFrame>(Channel.UNLIMITED)
    }
}

class NothingHeadphones(private val connection: NothingConnection) {
    /** Device-info session probe (`0x06`), best-effort per `nothing-bridge`. */
    suspend fun initSession() {
        try {
            connection.request(NothingProtocol.CMD_DEVICE_INFO, timeout = 1500.milliseconds)
        } catch (_: NothingException.Timeout) {
            // Proceeding is safe per nothing-bridge INFO_TIMEOUT note.
        }
    }

    suspend fun getBattery(): NothingBattery {
        val reply = connection.request(NothingProtocol.CMD_BATTERY)
        return NothingProtocol.parseBattery(reply.payload)
            ?: throw NothingException("Malformed Nothing battery reply")
    }

    suspend fun getAnc(): NothingAncMode? = try {
        val reply = connection.request(NothingProtocol.CMD_ANC_GET, timeout = 2000.milliseconds)
        NothingProtocol.parseAnc(reply.payload)
    } catch (_: NothingException.Timeout) {
        null
    }

    suspend fun setAnc(mode: NothingAncMode): NothingAncMode {
        connection.request(
            command = NothingProtocol.CMD_ANC_SET,
            direction = NothingProtocol.DIR_SET,
            payload = NothingProtocol.buildAncSetPayload(mode),
        )
        return mode
    }
}
