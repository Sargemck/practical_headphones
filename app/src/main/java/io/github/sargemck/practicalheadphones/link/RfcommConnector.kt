package io.github.sargemck.practicalheadphones.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import io.github.sargemck.practicalheadphones.bmap.BmapConnection
import io.github.sargemck.practicalheadphones.bmap.BmapException
import io.github.sargemck.practicalheadphones.bmap.FrameLogger
import io.github.sargemck.practicalheadphones.bmap.Headphones
import io.github.sargemck.practicalheadphones.bmap.QcUltra2
import io.github.sargemck.practicalheadphones.state.Connector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/**
 * Connect rule (CLAUDE.md, M0 E1/E8): SDP with the SPP UUID first, then reflection on
 * channel 2. A path counts only once GET 2.2 is answered. One attempt per path, no loops.
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is checked before the service starts.
class RfcommConnector(
    private val context: Context,
    private val locator: HeadphonesLocator,
    private val logger: FrameLogger,
    private val log: (String) -> Unit,
) : Connector {
    private enum class Path { SPP, REFLECT }

    override suspend fun connect(scope: CoroutineScope): BmapConnection {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        val device = locator.find(adapter) ?: throw IOException("headphones not paired")
        for (path in Path.entries) {
            val started = System.nanoTime()
            var opened: BluetoothSocket? = null
            val socket = try {
                open(device, path).also {
                    opened = it
                    withContext(Dispatchers.IO) { it.connect() }
                }
            } catch (e: Exception) {
                runCatching { opened?.close() }
                log("CONNECT $path failed: ${e.javaClass.simpleName} ${e.message}")
                continue
            }
            val connection = BmapConnection(RfcommTransport(socket), scope, logger)
            try {
                Headphones(connection).read(QcUltra2.battery)
                log("CONNECT $path ok in ${(System.nanoTime() - started) / 1_000_000} ms")
                return connection
            } catch (e: BmapException) {
                log("CONNECT $path no BMAP answer: ${e.message}")
                connection.close()
            }
        }
        throw IOException("no BMAP answer on SPP or channel 2")
    }

    private fun open(device: BluetoothDevice, path: Path): BluetoothSocket = when (path) {
        Path.SPP -> device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
        // Non-SDK API, allowed at every target SDK today (CLAUDE.md).
        Path.REFLECT -> device.javaClass
            .getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
            .invoke(device, BMAP_CHANNEL) as BluetoothSocket
    }

    private companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
        const val BMAP_CHANNEL = 2
    }
}
