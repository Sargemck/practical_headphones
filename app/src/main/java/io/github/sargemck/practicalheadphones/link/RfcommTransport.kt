package io.github.sargemck.practicalheadphones.link

import android.bluetooth.BluetoothSocket
import io.github.sargemck.practicalheadphones.bmap.FrameTransport
import io.github.sargemck.practicalheadphones.nothing.NothingTransport
import io.github.sargemck.practicalheadphones.samsung.SamsungTransport
import io.github.sargemck.practicalheadphones.sony.SonyTransport
import io.github.sargemck.practicalheadphones.soundcore.SoundcoreTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A connected RFCOMM socket adapting to all five pure-JVM RFCOMM protocol transport interfaces. */
class RfcommTransport(private val socket: BluetoothSocket) :
    FrameTransport,
    SonyTransport,
    SoundcoreTransport,
    SamsungTransport,
    NothingTransport {
    private val buffer = ByteArray(1024)

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
