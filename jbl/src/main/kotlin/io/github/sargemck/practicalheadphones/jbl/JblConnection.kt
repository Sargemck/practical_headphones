package io.github.sargemck.practicalheadphones.jbl

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

class JblConnection(
    private val transport: JblTransport,
    scope: CoroutineScope,
    private val replyTimeout: Duration = 3.seconds,
) {
    private val parser = JblFrameParser()
    private val mutex = Mutex()

    private val _state = MutableStateFlow<JblConnectionState>(JblConnectionState.Open)
    val state: StateFlow<JblConnectionState> = _state.asStateFlow()

    private val _notifications = MutableSharedFlow<JblFrame>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val notifications: SharedFlow<JblFrame> = _notifications.asSharedFlow()

    @Volatile private var pending: Pending? = null

    private val reader: Job = scope.launch { readLoop() }

    fun close() = closeWith(JblCloseReason.Local)

    suspend fun request(
        rawFrame: ByteArray,
        timeout: Duration = replyTimeout,
        match: (JblFrame) -> Boolean,
    ): JblFrame = mutex.withLock {
        val p = Pending(match)
        pending = p
        try {
            checkOpen()
            writeRaw(rawFrame)
            withTimeoutOrNull(timeout) { p.replies.receive() }
                ?: throw JblException.Timeout("JBL GATT reply timed out")
        } finally {
            pending = null
        }
    }

    suspend fun send(rawFrame: ByteArray) = mutex.withLock {
        checkOpen()
        writeRaw(rawFrame)
    }

    private suspend fun writeRaw(bytes: ByteArray) {
        try {
            transport.write(bytes)
        } catch (e: IOException) {
            val reason = JblCloseReason.IoError(e.message)
            closeWith(reason)
            throw JblException.Disconnected(reason)
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
            JblCloseReason.RemoteClosed
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            JblCloseReason.IoError(e.message)
        }
        closeWith(reason)
    }

    private fun checkOpen() {
        (state.value as? JblConnectionState.Closed)?.let { throw JblException.Disconnected(it.reason) }
    }

    private fun closeWith(reason: JblCloseReason) {
        if (!_state.compareAndSet(JblConnectionState.Open, JblConnectionState.Closed(reason))) return
        pending?.replies?.close(JblException.Disconnected(reason))
        transport.close()
        if (reason !is JblCloseReason.RemoteClosed && reason !is JblCloseReason.IoError) reader.cancel()
    }

    private class Pending(val match: (JblFrame) -> Boolean) {
        val replies = Channel<JblFrame>(Channel.UNLIMITED)
    }
}

class JblBuds(private val connection: JblConnection) {
    suspend fun enableNotifications() {
        try {
            connection.request(JblProtocol.buildEnableNotifications(), timeout = 1500.milliseconds) {
                it.cmd == JblProtocol.CMD_NOTIFY_ENABLE
            }
        } catch (_: JblException.Timeout) {
            // Some JBL firmware streams notifications immediately after CCCD write without 0x9B ack.
        }
    }

    suspend fun getMode(): JblMode {
        val reply = connection.request(JblProtocol.buildModeGet()) {
            JblProtocol.parseModeReport(it) != null
        }
        return JblProtocol.parseModeReport(reply) ?: throw JblException("Malformed JBL mode report")
    }

    suspend fun setMode(mode: JblMode): JblMode {
        connection.send(JblProtocol.buildModeSet(mode))
        return mode
    }

    suspend fun getBattery(): JblBattery? = try {
        val reply = connection.request(JblProtocol.buildBatteryGet(), timeout = 1500.milliseconds) {
            JblProtocol.parseBattery(it) != null
        }
        JblProtocol.parseBattery(reply)
    } catch (_: JblException.Timeout) {
        null
    }
}
