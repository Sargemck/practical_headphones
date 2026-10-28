package io.github.sargemck.practicalheadphones.bmap

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

sealed interface CloseReason {
    /** The other side closed the link: `read() == -1`. */
    data object RemoteClosed : CloseReason

    data class IoError(val message: String?) : CloseReason

    /** A request, its retry and the liveness check all went unanswered. */
    data object Unresponsive : CloseReason

    /** [BmapConnection.close] was called. */
    data object Local : CloseReason
}

sealed interface ConnectionState {
    data object Open : ConnectionState

    data class Closed(val reason: CloseReason) : ConnectionState
}

sealed interface LogEvent {
    data class Out(val frame: BmapFrame) : LogEvent

    data class In(val frame: BmapFrame) : LogEvent

    class Skipped(val bytes: ByteArray) : LogEvent {
        override fun toString(): String = "Skipped(${Hex.format(bytes)})"
    }

    data class Closed(val reason: CloseReason) : LogEvent
}

fun interface FrameLogger {
    fun log(event: LogEvent)

    companion object {
        val NONE = FrameLogger {}
    }
}

data class Timing(
    /** Wait for a reply; a PROCESSING frame restarts it. */
    val reply: Duration = 3.seconds,
    /** Cap on the total wait for one attempt, so endless PROCESSING frames still time out. */
    val maxWait: Duration = 10.seconds,
    /** A drain ends this long after its last frame if `RESULT` never comes. */
    val drainSilence: Duration = 500.milliseconds,
)

/**
 * One BMAP connection over a [FrameTransport]. A reader coroutine, started in the
 * constructor, reads the transport continuously into a [FrameParser].
 *
 * Requests run one at a time behind a [Mutex]. A request completes on the first frame with
 * the same `(fblock, func)` and op STATUS, RESULT or ERROR. PROCESSING restarts the reply
 * timer. Every other frame goes to [unsolicited], never to a caller.
 *
 * Timeouts: one retry. If the retry also times out, a GET on the [liveness] address checks
 * the link. If that answers, the address is unsupported ([BmapException.Unsupported]) and the
 * connection stays open. Otherwise the connection closes ([CloseReason.Unresponsive]).
 *
 * Known limit: a reply that arrives after its timeout could complete a later request to the
 * same address. Unlikely with a 28 ms median reply against a 3 s timeout (M0 E3).
 *
 * Frames are sent only through [Headphones]; there is no raw send. Every frame is checked
 * against the hard rules first: only GET, SETGET and START, never the Authentication block.
 */
class BmapConnection(
    private val transport: FrameTransport,
    scope: CoroutineScope,
    private val logger: FrameLogger = FrameLogger.NONE,
    private val timing: Timing = Timing(),
    private val liveness: Address = Address(2, 2),
    knownFblocks: Set<Int> = FrameParser.QC_ULTRA2_FBLOCKS,
) {
    private val parser = FrameParser(knownFblocks)
    private val mutex = Mutex()
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Open)
    private val _unsolicited = MutableSharedFlow<BmapFrame>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    @Volatile private var pending: Pending? = null

    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /** Frames that didn't belong to the request in flight. */
    val unsolicited: SharedFlow<BmapFrame> = _unsolicited.asSharedFlow()

    private val reader: Job = scope.launch { readLoop() }

    fun close() {
        closeWith(CloseReason.Local)
    }

    /** Sends [frame] and returns its reply. ERROR replies throw [BmapException.DeviceError]. */
    internal suspend fun request(frame: BmapFrame): BmapFrame = mutex.withLock {
        val reply = withRetry(frame.address) { exchange(frame) }
        if (reply.op == BmapOp.ERROR) throw deviceError(reply)
        reply
    }

    /**
     * For commands after which the device drops the link (power off, pairing mode). Never
     * retried. A disconnect before any reply counts as success. A reply also counts unless it
     * is ERROR. No reply and no disconnect within the reply timeout throws [BmapException.Timeout]
     * without closing.
     */
    internal suspend fun requestExpectingDisconnect(frame: BmapFrame): Unit = mutex.withLock {
        withPending({ it.fblock == frame.fblock && it.func == frame.func }) { replies ->
            send(frame)
            val reply = try {
                withTimeoutOrNull(timing.reply) { replies.receive() }
            } catch (e: BmapException.Disconnected) {
                return@withPending
            }
            when {
                reply == null -> throw BmapException.Timeout(frame.address)
                reply.op == BmapOp.ERROR -> throw deviceError(reply)
            }
        }
    }

    /**
     * Sends a START that answers with a stream of frames ([31.1]). Every frame from the same
     * fblock belongs to the drain until `RESULT` on the request's address. If `RESULT` never
     * comes, it ends [Timing.drainSilence] after the last frame. Returns the frames in order,
     * without the PROCESSING and RESULT frames.
     */
    internal suspend fun drain(frame: BmapFrame): List<BmapFrame> = mutex.withLock {
        withRetry(frame.address) { collectDrain(frame) }
    }

    private suspend fun <T : Any> withRetry(address: Address, attempt: suspend () -> T?): T {
        attempt()?.let { return it }
        attempt()?.let { return it }
        if (address != liveness && exchange(BmapFrame(liveness.fblock, liveness.func, BmapOp.GET)) != null) {
            throw BmapException.Unsupported(address)
        }
        closeWith(CloseReason.Unresponsive)
        throw BmapException.Timeout(address)
    }

    /** One attempt: the reply, or null on timeout. */
    private suspend fun exchange(frame: BmapFrame): BmapFrame? =
        withPending({ it.fblock == frame.fblock && it.func == frame.func }) { replies ->
            send(frame)
            withTimeoutOrNull(timing.maxWait) {
                var reply: BmapFrame? = null
                while (reply == null) {
                    val next = withTimeoutOrNull(timing.reply) { replies.receive() } ?: break
                    if (next.op != BmapOp.PROCESSING) reply = next
                }
                reply
            }
        }

    /** One drain attempt: the collected frames, or null if nothing arrived. */
    private suspend fun collectDrain(frame: BmapFrame): List<BmapFrame>? =
        withPending({ it.fblock == frame.fblock }) { replies ->
            send(frame)
            val collected = mutableListOf<BmapFrame>()
            var heardAnything = false
            var finished = false
            withTimeoutOrNull(timing.maxWait) {
                while (!finished) {
                    val wait = if (collected.isEmpty()) timing.reply else timing.drainSilence
                    val next = withTimeoutOrNull(wait) { replies.receive() } ?: break
                    heardAnything = true
                    if (next.address == frame.address) {
                        when (next.op) {
                            BmapOp.RESULT -> finished = true
                            BmapOp.ERROR -> throw deviceError(next)
                            else -> Unit
                        }
                    } else {
                        collected += next
                    }
                }
            }
            if (heardAnything) collected else null
        }

    /** Routes frames matching [match] (with a reply op) to [block] while it runs. */
    private suspend fun <T> withPending(
        match: (BmapFrame) -> Boolean,
        block: suspend (Channel<BmapFrame>) -> T,
    ): T {
        val p = Pending(match)
        pending = p
        try {
            (state.value as? ConnectionState.Closed)?.let { throw BmapException.Disconnected(it.reason) }
            return block(p.frames)
        } finally {
            pending = null
        }
    }

    private suspend fun send(frame: BmapFrame) {
        if (frame.op !in ALLOWED_OPS || frame.fblock == AUTH_FBLOCK) throw BmapException.Refused(frame)
        logger.log(LogEvent.Out(frame))
        try {
            transport.write(frame.encode())
        } catch (e: IOException) {
            val reason = CloseReason.IoError(e.message)
            closeWith(reason)
            throw BmapException.Disconnected(reason)
        }
    }

    private suspend fun readLoop() {
        val reason = try {
            while (true) {
                val chunk = transport.read() ?: break
                for (event in parser.feed(chunk)) {
                    when (event) {
                        is FrameEvent.Frame -> dispatch(event.frame)
                        is FrameEvent.Skipped -> logger.log(LogEvent.Skipped(event.bytes))
                    }
                }
            }
            CloseReason.RemoteClosed
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            CloseReason.IoError(e.message)
        }
        closeWith(reason)
    }

    private fun dispatch(frame: BmapFrame) {
        logger.log(LogEvent.In(frame))
        val p = pending
        if (p != null && frame.op in REPLY_OPS && p.match(frame)) {
            p.frames.trySend(frame)
        } else {
            _unsolicited.tryEmit(frame)
        }
    }

    private fun closeWith(reason: CloseReason) {
        if (!_state.compareAndSet(ConnectionState.Open, ConnectionState.Closed(reason))) return
        logger.log(LogEvent.Closed(reason))
        pending?.frames?.close(BmapException.Disconnected(reason))
        transport.close()
        if (reason !is CloseReason.RemoteClosed && reason !is CloseReason.IoError) reader.cancel()
    }

    private fun deviceError(reply: BmapFrame) =
        BmapException.DeviceError(reply.address, if (reply.payload.isEmpty()) -1 else reply.payload[0].toInt() and 0xFF)

    private class Pending(val match: (BmapFrame) -> Boolean) {
        val frames = Channel<BmapFrame>(Channel.UNLIMITED)
    }

    private companion object {
        const val AUTH_FBLOCK = 18
        val ALLOWED_OPS = setOf(BmapOp.GET, BmapOp.SETGET, BmapOp.START)
        val REPLY_OPS = setOf(BmapOp.STATUS, BmapOp.RESULT, BmapOp.ERROR, BmapOp.PROCESSING)
    }
}
