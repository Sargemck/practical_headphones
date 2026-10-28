package io.github.sargemck.practicalheadphones.bmap

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * In-memory QC Ultra 2. Its state starts as the M0 fixtures, so every captured request gets
 * the captured reply byte for byte. Writes change the state the way M0 observed. Fault
 * injection fields can be set at any time. Replies to one request go out as one chunk unless
 * [chunkSize] splits them. Delays run on [scope]'s clock (virtual time under `runTest`).
 */
class FakeBmapDevice(private val scope: CoroutineScope) : FrameTransport {
    private sealed interface Out {
        class Bytes(val bytes: ByteArray) : Out

        data object Eof : Out

        class Fail(val message: String) : Out
    }

    private val outgoing = Channel<Out>(Channel.UNLIMITED)
    private val parser = FrameParser()
    private var linkUp = true

    private val payloads = mutableMapOf<Address, ByteArray>()
    private val errors = mutableMapOf<Address, ByteArray>()
    private val modes = sortedMapOf<Int, ByteArray>()
    private val getWithPayload = mutableMapOf<Pair<Address, String>, List<BmapFrame>>()

    /** Every frame written to the device, including refused ones. */
    val received = mutableListOf<BmapFrame>()

    /** Addresses that never answer. Starts with the ones that didn't answer in M0 ([31.4]). */
    val silent = mutableSetOf<Address>()

    var silenceAll = false

    /** Per-address reply delay. */
    val delays = mutableMapOf<Address, Duration>()

    /** Ignore the next N requests to an address. */
    val ignoreNext = mutableMapOf<Address, Int>()

    /** Send PROCESSING immediately, then the reply after the address's delay. */
    val processingFirst = mutableSetOf<Address>()

    /** Answer only with PROCESSING, every [processingInterval], forever. */
    val processingForever = mutableSetOf<Address>()
    var processingInterval: Duration = 2.seconds

    /** Frames sent in the same chunk just before the next reply. */
    val beforeReply = mutableListOf<BmapFrame>()

    /** Frames added to a [31.1] drain before its RESULT. */
    val drainExtra = mutableListOf<BmapFrame>()

    var dropDrainResult = false

    /** Split each outgoing chunk into pieces of this many bytes. */
    var chunkSize: Int? = null

    init {
        for (exchange in Fixtures.all()) seed(exchange)
    }

    /** Current state of an address, for test assertions. */
    fun payload(address: Address): ByteArray? = payloads[address]?.copyOf()

    fun mode(index: Int): ByteArray? = modes[index]?.copyOf()

    fun pushUnsolicited(frame: BmapFrame) = push(frame.encode())

    fun pushRaw(bytes: ByteArray) = push(bytes)

    /** The device drops the link: the next read returns null. */
    fun disconnect() {
        linkUp = false
        outgoing.trySend(Out.Eof)
    }

    /** The next read throws [IOException]. */
    fun failRead(message: String = "fake I/O error") {
        linkUp = false
        outgoing.trySend(Out.Fail(message))
    }

    override suspend fun read(): ByteArray? =
        when (val next = outgoing.receive()) {
            is Out.Bytes -> next.bytes
            Out.Eof -> null
            is Out.Fail -> throw IOException(next.message)
        }

    override suspend fun write(bytes: ByteArray) {
        if (!linkUp) throw IOException("link down")
        for (event in parser.feed(bytes)) {
            if (event is FrameEvent.Frame) handle(event.frame)
        }
    }

    override fun close() {
        linkUp = false
        outgoing.trySend(Out.Eof)
    }

    private fun seed(exchange: Fixtures.Exchange) {
        val request = exchange.request
        val address = request.address
        when {
            exchange.reply == null -> silent += address
            request.op == BmapOp.START && address == QcUltra2.allModes ->
                for (frame in exchange.replyFrames.filter { it.op == BmapOp.STATUS }) {
                    if (frame.address == QcUltra2.modeConfig) {
                        modes[frame.payload[0].toInt()] = frame.payload
                    } else {
                        payloads[frame.address] = frame.payload
                    }
                }
            request.payload.isNotEmpty() -> getWithPayload[address to Hex.format(request.payload)] = exchange.replyFrames
            else -> {
                val reply = exchange.replyFrames.single()
                if (reply.op == BmapOp.ERROR) errors[address] = reply.payload else payloads[address] = reply.payload
            }
        }
    }

    private fun handle(frame: BmapFrame) {
        received += frame
        if (frame.op == BmapOp.SET || frame.fblock == 18) return
        val address = frame.address
        if (silenceAll || address in silent) return
        ignoreNext[address]?.let { count ->
            if (count > 0) {
                ignoreNext[address] = count - 1
                return
            }
        }
        if (address in processingForever) {
            scope.launch {
                while (linkUp) {
                    push(BmapFrame(address.fblock, address.func, BmapOp.PROCESSING).encode())
                    delay(processingInterval)
                }
            }
            return
        }
        if (frame.op == BmapOp.START && (address == QcUltra2.pairingMode || address == QcUltra2.powerOff)) {
            disconnect()
            return
        }
        val replies = beforeReply.toList() + respond(frame)
        beforeReply.clear()
        val delayFor = delays[address] ?: Duration.ZERO
        if (address in processingFirst) push(BmapFrame(address.fblock, address.func, BmapOp.PROCESSING).encode())
        val bytes = replies.fold(ByteArray(0)) { acc, f -> acc + f.encode() }
        if (delayFor == Duration.ZERO) {
            push(bytes)
        } else {
            scope.launch {
                delay(delayFor)
                push(bytes)
            }
        }
    }

    private fun respond(frame: BmapFrame): List<BmapFrame> {
        val address = frame.address
        val p = frame.payload
        return when (frame.op) {
            BmapOp.GET -> listOf(
                when {
                    p.isNotEmpty() -> return getWithPayload[address to Hex.format(p)]
                        ?: listOf(error(address, ErrorCodes.INVALID_DATA))
                    address in errors -> BmapFrame(address.fblock, address.func, BmapOp.ERROR, errors.getValue(address))
                    address in payloads -> status(address, payloads.getValue(address))
                    else -> error(address, ErrorCodes.FUNC_NOT_SUPPORTED)
                },
            )
            BmapOp.SETGET -> listOf(setget(address, p))
            BmapOp.START -> when (address) {
                QcUltra2.allModes -> drain()
                QcUltra2.selectMode -> listOf(selectMode(p))
                else -> listOf(error(address, ErrorCodes.FUNC_NOT_SUPPORTED))
            }
            else -> listOf(error(address, ErrorCodes.FUNC_NOT_SUPPORTED))
        }
    }

    private fun setget(address: Address, p: ByteArray): BmapFrame =
        when (address) {
            QcUltra2.audioSettingsWrite -> when {
                p.size != 5 -> error(address, LENGTH)
                p[1].toInt() != 0 -> error(address, ErrorCodes.RUNTIME)
                else -> status(address, p.also { payloads[address] = it })
            }
            QcUltra2.modeConfig -> writeMode(p)
            QcUltra2.Write.productName.address -> status(address, (byteArrayOf(0) + p).also { payloads[address] = it })
            QcUltra2.Write.eqBand.address -> {
                val eq = payloads.getValue(address).copyOf()
                val group = (eq.indices step 4).firstOrNull { eq[it + 3] == p[1] }
                if (group == null) {
                    error(address, ErrorCodes.INVALID_DATA)
                } else {
                    eq[group + 2] = p[0]
                    status(address, eq.also { payloads[address] = it })
                }
            }
            QcUltra2.Write.selfVoice.address -> {
                val old = payloads.getValue(address)
                status(address, (p.copyOf(2) + old.copyOfRange(2, old.size)).also { payloads[address] = it })
            }
            QcUltra2.Write.autoPlayPause.address, QcUltra2.Write.autoAnswer.address, QcUltra2.Write.favourites.address ->
                status(address, p.also { payloads[address] = it })
            else -> error(address, ErrorCodes.FUNC_NOT_SUPPORTED)
        }

    /** Presets 0..3 are locked (Runtime error 8). Echoes the 40-byte write, as bosectl's parser expects. */
    private fun writeMode(p: ByteArray): BmapFrame {
        val address = QcUltra2.modeConfig
        if (p.size != 40) return error(address, LENGTH)
        val index = p[0].toInt() and 0xFF
        if (index in QcUltra2.presetSlots) return error(address, ErrorCodes.RUNTIME)
        val old = modes[index] ?: return error(address, ErrorCodes.INVALID_DATA)
        val status = old.copyOf()
        status[1] = p[1]
        status[2] = p[2]
        status[3] = 1
        status[4] = 1
        p.copyInto(status, destinationOffset = 6, startIndex = 3, endIndex = 35)
        status[42] = p[35]
        status[43] = p[36]
        status[44] = p[37]
        status[45] = p[38]
        status[47] = p[39]
        modes[index] = status
        return status(address, p)
    }

    /**
     * START 31.3 `[idx, prompt]`. Copying the mode's settings into 31.10 is inferred from one
     * observation (M0 E5: switching to Quiet turned spatial from 02 to 00).
     */
    private fun selectMode(p: ByteArray): BmapFrame {
        val address = QcUltra2.selectMode
        val mode = p.firstOrNull()?.let { modes[it.toInt() and 0xFF] } ?: return error(address, ErrorCodes.INVALID_DATA)
        payloads[QcUltra2.currentMode.address] = byteArrayOf(mode[0])
        payloads[QcUltra2.audioSettings.address] = byteArrayOf(mode[42], mode[43], mode[44], mode[45], mode[47])
        return BmapFrame(address.fblock, address.func, BmapOp.RESULT, byteArrayOf(mode[0]))
    }

    /** Same order as the M0 capture: PROCESSING, 31.0, 31.2, 31.3, 31.6 per slot, 31.8, 31.10, 31.11, RESULT. */
    private fun drain(): List<BmapFrame> {
        val all = QcUltra2.allModes
        val out = mutableListOf(BmapFrame(all.fblock, all.func, BmapOp.PROCESSING))
        for (func in listOf(0, 2, 3)) payloads[Address(31, func)]?.let { out += status(Address(31, func), it) }
        for (mode in modes.values) out += status(QcUltra2.modeConfig, mode)
        for (func in listOf(8, 10, 11)) payloads[Address(31, func)]?.let { out += status(Address(31, func), it) }
        out += drainExtra
        if (!dropDrainResult) out += BmapFrame(all.fblock, all.func, BmapOp.RESULT)
        return out
    }

    private fun push(bytes: ByteArray) {
        val size = chunkSize
        if (size == null) {
            outgoing.trySend(Out.Bytes(bytes))
        } else {
            for (start in bytes.indices step size) {
                outgoing.trySend(Out.Bytes(bytes.copyOfRange(start, minOf(start + size, bytes.size))))
            }
        }
    }

    private fun status(address: Address, payload: ByteArray) =
        BmapFrame(address.fblock, address.func, BmapOp.STATUS, payload)

    private fun error(address: Address, code: Int) =
        BmapFrame(address.fblock, address.func, BmapOp.ERROR, byteArrayOf(code.toByte()))

    private companion object {
        const val LENGTH = 1
    }
}
