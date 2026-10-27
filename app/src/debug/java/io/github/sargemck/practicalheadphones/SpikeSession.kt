package io.github.sargemck.practicalheadphones

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import io.github.sargemck.practicalheadphones.SpikeLog.log
import io.github.sargemck.practicalheadphones.SpikeLog.ms
import io.github.sargemck.practicalheadphones.bmap.BmapFrame
import io.github.sargemck.practicalheadphones.bmap.BmapOp
import io.github.sargemck.practicalheadphones.bmap.FrameEvent
import io.github.sargemck.practicalheadphones.bmap.FrameParser
import io.github.sargemck.practicalheadphones.bmap.Hex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

/**
 * M0 spike session (debug builds only). One RFCOMM socket, one reader coroutine,
 * one request in flight. Every frame in and out is logged with tag BMAP.
 */
@SuppressLint("MissingPermission") // checked in connect() before any Bluetooth call
object SpikeSession {
    private val BMAP_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacaff")
    private const val BMAP_CHANNEL = 2
    private const val DEVICE_NAME_MATCH = "QC Ultra 2"
    private const val REPLY_TIMEOUT_MS = 3_000L
    private const val DRAIN_SILENCE_MS = 500L
    private const val DRAIN_MAX_FRAMES = 200
    private const val WATCH_MS = 3_000L

    private class Received(val frame: BmapFrame, val atMs: Double)

    private val replyOps = setOf(BmapOp.STATUS, BmapOp.RESULT, BmapOp.ERROR)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Mutex()
    private val inFlight = Mutex()
    private val incoming = MutableSharedFlow<Received>(extraBufferCapacity = 1024)

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var lastPath: String? = null
    @Volatile private var heartbeat: Job? = null

    init {
        SpikeLog.clock()
    }

    fun handle(context: Context, extras: Bundle) {
        val cmd = extras.getString("cmd") ?: return log("CMD missing: use --es cmd <name>")
        val app = context.applicationContext
        log("CMD $cmd")
        scope.launch {
            commands.withLock {
                try {
                    run(app, cmd, extras)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("ERR $cmd: $e")
                }
            }
        }
    }

    private suspend fun run(context: Context, cmd: String, extras: Bundle) {
        when (cmd) {
            "connect" -> connect(context, extras.getString("path") ?: "reflect")
            "close" -> close()
            "probe2" -> probeSecondSocket(context)
            "send" -> send(Hex.parse(extras.getString("hex").orEmpty()))
            "latency" -> latency(extras.getInt("n", 20))
            "burst" -> burst(extras.getInt("n", 10), extras.getInt("gap", 0).toLong())
            "heartbeat" -> heartbeat(extras.getInt("every", 60))
            "dump" -> dump()
            "writes" -> writes()
            "clock" -> SpikeLog.clock()
            else -> log("CMD unknown: $cmd")
        }
    }

    // ── Connection ──────────────────────────────────────────────────────────

    private fun findHeadphones(context: Context): BluetoothDevice? =
        context.getSystemService(BluetoothManager::class.java).adapter.bondedDevices
            .firstOrNull { it.name?.contains(DEVICE_NAME_MATCH) == true }

    private fun openSocket(device: BluetoothDevice, path: String): BluetoothSocket = when (path) {
        "sdp" -> device.createInsecureRfcommSocketToServiceRecord(BMAP_UUID)
        "reflect" -> device.javaClass
            .getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
            .invoke(device, BMAP_CHANNEL) as BluetoothSocket
        else -> throw IllegalArgumentException("path must be sdp or reflect, got $path")
    }

    /** RFCOMM channel the socket landed on, via the hidden getPort(); "unknown" if blocked. */
    private fun channelOf(s: BluetoothSocket): String =
        try {
            s.javaClass.getMethod("getPort").invoke(s).toString()
        } catch (e: Exception) {
            "unknown (${e.javaClass.simpleName})"
        }

    private fun connect(context: Context, path: String) {
        if (socket != null) return log("CONNECT skipped: already connected")
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return log("CONNECT failed: BLUETOOTH_CONNECT not granted (install with adb install -g)")
        }
        val device = findHeadphones(context) ?: return log("CONNECT failed: no bonded device named *$DEVICE_NAME_MATCH*")
        SpikeLog.clock()
        val start = SpikeLog.nowMs()
        val s = try {
            openSocket(device, path)
        } catch (e: Exception) {
            return log("CONNECT failed to create socket path=$path: $e")
        }
        try {
            s.connect()
        } catch (e: IOException) {
            runCatching { s.close() }
            return log("CONNECT failed path=$path after ${ms(SpikeLog.nowMs() - start)} ms: $e")
        }
        log("CONNECTED path=$path device=\"${device.name}\" in ${ms(SpikeLog.nowMs() - start)} ms channel=${channelOf(s)}")
        socket = s
        lastPath = path
        startReader(s)
    }

    private fun startReader(s: BluetoothSocket) {
        val parser = FrameParser()
        scope.launch {
            val input = s.inputStream
            val buf = ByteArray(1024)
            var reason = "unknown"
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) {
                        reason = "read() == -1"
                        break
                    }
                    val at = SpikeLog.nowMs()
                    log("RAW< $n bytes")
                    for (event in parser.feed(buf.copyOf(n))) {
                        when (event) {
                            is FrameEvent.Frame -> {
                                log("IN  ${Hex.format(event.frame.encode())}  ${event.frame}")
                                incoming.emit(Received(event.frame, at))
                            }
                            is FrameEvent.Skipped ->
                                log("RESYNC dropped ${event.bytes.size} bytes: ${Hex.format(event.bytes)}")
                        }
                    }
                }
            } catch (e: IOException) {
                reason = "IOException: ${e.message}"
            }
            val ours = socket !== s
            log("DISCONNECT $reason${if (ours) " (after our close)" else ""}; ${parser.buffered} bytes left in parser")
            if (socket === s) socket = null
            runCatching { s.close() }
        }
    }

    private fun close() {
        val s = socket ?: return log("CLOSE: not connected")
        socket = null
        runCatching { s.close() }
        log("CLOSE done")
    }

    /** E5: one-shot second socket while the first is open. Expected to be refused. */
    private fun probeSecondSocket(context: Context) {
        val path = lastPath ?: return log("PROBE2: connect first")
        if (socket == null) log("PROBE2 warning: first socket is not open")
        val device = findHeadphones(context) ?: return log("PROBE2: no bonded device")
        val s2 = openSocket(device, path)
        val start = SpikeLog.nowMs()
        try {
            s2.connect()
            log("PROBE2 second socket CONNECTED path=$path in ${ms(SpikeLog.nowMs() - start)} ms channel=${channelOf(s2)}")
        } catch (e: IOException) {
            log("PROBE2 refused path=$path after ${ms(SpikeLog.nowMs() - start)} ms: $e")
        } finally {
            runCatching { s2.close() }
        }
    }

    // ── Sending and matching ────────────────────────────────────────────────

    private fun sendRaw(frame: BmapFrame): Boolean {
        SpikeGuard.refusal(frame)?.let {
            log("REFUSED ${Hex.format(frame.encode())}  $frame: $it")
            return false
        }
        val s = socket ?: run {
            log("SEND failed: not connected")
            return false
        }
        val bytes = frame.encode()
        log("OUT ${Hex.format(bytes)}  $frame")
        return try {
            s.outputStream.write(bytes)
            s.outputStream.flush()
            true
        } catch (e: IOException) {
            log("SEND failed: $e")
            false
        }
    }

    /**
     * Sends [frame] and waits for the first frame with the same address and op
     * STATUS / RESULT / ERROR. PROCESSING keeps waiting. Returns the reply and its latency.
     */
    private suspend fun request(frame: BmapFrame): Pair<BmapFrame, Double>? = inFlight.withLock {
        coroutineScope {
            val reply = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(REPLY_TIMEOUT_MS) {
                    incoming.first { it.frame.fblock == frame.fblock && it.frame.func == frame.func && it.frame.op in replyOps }
                }
            }
            val sentAt = SpikeLog.nowMs()
            if (!sendRaw(frame)) {
                reply.cancel()
                return@coroutineScope null
            }
            val received = reply.await()
            if (received == null) {
                log("TIMEOUT ${frame.address} after $REPLY_TIMEOUT_MS ms")
                null
            } else {
                received.frame to (received.atMs - sentAt)
            }
        }
    }

    /** Sends [frame] and collects every frame until [DRAIN_SILENCE_MS] of silence. */
    private suspend fun drain(frame: BmapFrame): List<BmapFrame> = inFlight.withLock {
        coroutineScope {
            val channel = Channel<Received>(Channel.UNLIMITED)
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { incoming.collect { channel.send(it) } }
            val out = mutableListOf<BmapFrame>()
            if (sendRaw(frame)) {
                var next = withTimeoutOrNull(REPLY_TIMEOUT_MS) { channel.receive() }
                while (next != null && out.size < DRAIN_MAX_FRAMES) {
                    out += next.frame
                    next = withTimeoutOrNull(DRAIN_SILENCE_MS) { channel.receive() }
                }
            }
            collector.cancel()
            log("DRAIN ${frame.address}: ${out.size} frames")
            out
        }
    }

    /** Logs and counts every frame that arrives in the next [ms] milliseconds. */
    private suspend fun watch(label: String, ms: Long) {
        log("WATCH begin $label ($ms ms)")
        var count = 0
        withTimeoutOrNull(ms) { incoming.collect { count++ } }
        log("WATCH end $label: $count frames")
    }

    // ── Commands ────────────────────────────────────────────────────────────

    private suspend fun send(bytes: ByteArray) {
        val parser = FrameParser()
        val frames = parser.feed(bytes).filterIsInstance<FrameEvent.Frame>()
        if (frames.size != 1 || parser.buffered != 0) return log("SEND needs exactly one whole frame: ${Hex.format(bytes)}")
        val reply = request(frames.single().frame) ?: return
        log("REPLY ${reply.first} in ${ms(reply.second)} ms")
    }

    private suspend fun latency(n: Int) {
        val get = BmapFrame(2, 2, BmapOp.GET)
        val times = mutableListOf<Double>()
        repeat(n) { i ->
            val reply = request(get)
            if (reply == null) log("LATENCY #${i + 1} no reply") else {
                times += reply.second
                log("LATENCY #${i + 1} ${ms(reply.second)} ms")
            }
        }
        val sorted = times.sorted()
        if (sorted.isEmpty()) return log("LATENCY n=$n: no replies")
        val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        log("LATENCY n=$n replies=${sorted.size} min=${ms(sorted.first())} median=${ms(median)} max=${ms(sorted.last())} ms")
    }

    private val burstAddresses = listOf(2 to 2, 0 to 5, 1 to 7, 1 to 10, 1 to 11, 1 to 24, 1 to 27, 31 to 3, 31 to 10, 5 to 1)

    /** E4: n GETs to different addresses back to back, [gapMs] between writes (a test parameter). */
    private suspend fun burst(n: Int, gapMs: Long) = inFlight.withLock {
        coroutineScope {
            val frames = List(n) { burstAddresses[it % burstAddresses.size].let { (b, f) -> BmapFrame(b, f, BmapOp.GET) } }
            val replies = Channel<Received>(Channel.UNLIMITED)
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                incoming.collect { if (it.frame.op in replyOps) replies.send(it) }
            }
            val start = SpikeLog.nowMs()
            frames.forEachIndexed { i, frame ->
                if (i > 0 && gapMs > 0) delay(gapMs)
                sendRaw(frame)
            }
            val got = mutableListOf<Received>()
            while (got.size < n) got += withTimeoutOrNull(REPLY_TIMEOUT_MS) { replies.receive() } ?: break
            collector.cancel()
            val sent = frames.map { it.address }
            val order = got.map { it.frame.address }
            log("BURST sent   $sent")
            log("BURST got    $order")
            log(
                "BURST n=$n gap=$gapMs ms: ${got.size}/$n replies, errors=${got.count { it.frame.op == BmapOp.ERROR }}, " +
                    "inOrder=${order == sent.take(order.size)}, lastReplyAt=${got.lastOrNull()?.let { ms(it.atMs - start) } ?: "-"} ms",
            )
        }
    }

    /** E0a: GET battery every [everySec] s while connected. 0 stops it. The interval is the test, not a reply wait. */
    private fun heartbeat(everySec: Int) {
        heartbeat?.cancel()
        heartbeat = null
        if (everySec <= 0) return log("HEARTBEAT stopped")
        log("HEARTBEAT every $everySec s")
        heartbeat = scope.launch {
            var i = 0
            while (isActive) {
                delay(everySec * 1_000L)
                i++
                if (socket == null) {
                    log("HEARTBEAT #$i: not connected, stopping")
                    break
                }
                val reply = request(BmapFrame(2, 2, BmapOp.GET))
                log("HEARTBEAT #$i ${if (reply == null) "no reply" else "ok ${ms(reply.second)} ms"}")
            }
        }
    }

    private val dumpAddresses = listOf(
        0 to 5, 1 to 2, 1 to 4, 1 to 7, 1 to 9, 1 to 10, 1 to 11, 1 to 24, 1 to 27,
        2 to 2, 4 to 4, 5 to 1, 31 to 3, 31 to 4, 31 to 8, 31 to 10,
    )

    /** E6: GET every feature address, then 4.5 with a MAC from 4.4, then START 31.1 drain. */
    private suspend fun dump() {
        var paired: BmapFrame? = null
        for ((b, f) in dumpAddresses) {
            val reply = exchange(BmapFrame(b, f, BmapOp.GET))
            if (b == 4 && f == 4) paired = reply
        }
        val mac = paired?.takeIf { it.op == BmapOp.STATUS }?.payload?.let { p ->
            when {
                p.size >= 7 && (p.size - 1) % 6 == 0 -> p.copyOfRange(1, 7)
                p.size >= 6 && p.size % 6 == 0 -> p.copyOfRange(0, 6)
                else -> null
            }
        }
        if (mac == null) log("DUMP 4.5 skipped: can't pick a MAC from the 4.4 reply") else exchange(BmapFrame(4, 5, BmapOp.GET, mac))

        val start = BmapFrame(31, 1, BmapOp.START)
        log("FIXTURE 31.1-START > ${Hex.format(start.encode())}")
        val frames = drain(start)
        log("FIXTURE 31.1-START < ${Hex.format(frames.fold(ByteArray(0)) { acc, fr -> acc + fr.encode() })}")
        log("DUMP done")
    }

    /** One request with its fixture lines logged. */
    private suspend fun exchange(frame: BmapFrame): BmapFrame? {
        val name = "${frame.address}-${frame.op}"
        log("FIXTURE $name > ${Hex.format(frame.encode())}")
        val reply = request(frame)
        log("FIXTURE $name < ${reply?.let { Hex.format(it.first.encode()) } ?: "timeout"}")
        return reply?.first
    }

    /** E5: timed writes with a snapshot, a 3 s watch after each, restore, read-back check. */
    private suspend fun writes() {
        if (heartbeat?.isActive == true) return log("WRITES refused: stop the heartbeat first (--ei every 0)")
        val mode0 = readStatus(31, 3) ?: return log("WRITES abort: can't read 31.3")
        val audio0 = readStatus(31, 10)?.takeIf { it.size == 5 } ?: return log("WRITES abort: can't read 31.10")
        val origIdx = mode0[0]
        val otherIdx: Byte = if (origIdx.toInt() == 0) 1 else 0
        log("WRITES snapshot mode=$origIdx audio=${Hex.format(audio0)}")

        step("mode -> $otherIdx", BmapFrame(31, 3, BmapOp.START, byteArrayOf(otherIdx, 0)))
        val audio1 = readStatus(31, 10)?.takeIf { it.size == 5 } ?: return log("WRITES abort before ANC write: can't read 31.10; restoring mode").also { restore(origIdx, audio0) }
        val toggled = audio1.copyOf().also { it[4] = if (it[4].toInt() == 0) 1 else 0 }
        step("anc toggled", BmapFrame(31, 10, BmapOp.SETGET, toggled))
        restore(origIdx, audio0)
    }

    private suspend fun restore(origIdx: Byte, audio0: ByteArray) {
        step("restore mode $origIdx", BmapFrame(31, 3, BmapOp.START, byteArrayOf(origIdx, 0)))
        step("restore audio", BmapFrame(31, 10, BmapOp.SETGET, audio0))
        val mode2 = readStatus(31, 3)
        val audio2 = readStatus(31, 10)
        if (mode2 != null && mode2[0] == origIdx && audio2 != null && audio2.contentEquals(audio0)) {
            log("RESTORE OK mode=$origIdx audio=${Hex.format(audio0)}")
        } else {
            log("RESTORE FAILED: expected mode=$origIdx audio=${Hex.format(audio0)}, got mode=${mode2?.let { Hex.format(it) }} audio=${audio2?.let { Hex.format(it) }}. STOP AND REPORT.")
        }
    }

    private suspend fun step(label: String, frame: BmapFrame) {
        val reply = request(frame)
        log("STEP $label: ${reply?.let { "${it.first} in ${ms(it.second)} ms" } ?: "no reply"}")
        watch("after $label", WATCH_MS)
    }

    private suspend fun readStatus(fblock: Int, func: Int): ByteArray? =
        request(BmapFrame(fblock, func, BmapOp.GET))?.first?.takeIf { it.op == BmapOp.STATUS && it.payload.isNotEmpty() }?.payload
}
