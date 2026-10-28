package io.github.sargemck.practicalheadphones.link

import android.bluetooth.BluetoothSocket
import io.github.sargemck.practicalheadphones.bmap.FrameTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A connected RFCOMM socket as a [FrameTransport]. `read() == -1` becomes null (link closed). */
class RfcommTransport(private val socket: BluetoothSocket) : FrameTransport {
    private val buffer = ByteArray(512)

    override suspend fun read(): ByteArray? = withContext(Dispatchers.IO) {
        val n = socket.inputStream.read(buffer)
        if (n < 0) null else buffer.copyOf(n)
    }

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        socket.outputStream.write(bytes)
        socket.outputStream.flush()
    }

    /** Also unblocks a pending read. */
    override fun close() {
        runCatching { socket.close() }
    }
}
