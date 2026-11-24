package io.github.sargemck.practicalheadphones.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import io.github.sargemck.practicalheadphones.bmap.BmapConnection
import io.github.sargemck.practicalheadphones.bmap.BmapException
import io.github.sargemck.practicalheadphones.bmap.BoseCatalog
import io.github.sargemck.practicalheadphones.bmap.FrameLogger
import io.github.sargemck.practicalheadphones.bmap.Headphones
import io.github.sargemck.practicalheadphones.driver.BoseDriver
import io.github.sargemck.practicalheadphones.driver.DriverConnector
import io.github.sargemck.practicalheadphones.driver.HeadphoneDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/**
 * Opens a Bose BMAP RFCOMM connection: SDP with the SPP UUID first, then reflection on channels
 * 2, 8, and 9. Sends `GET [0.1]` init packet if the initial battery probe doesn't answer (QC45,
 * QC35). One attempt per path, no retry loops.
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is checked before the service starts.
class RfcommConnector(
    private val context: Context,
    private val locator: HeadphonesLocator,
    private val logger: FrameLogger,
    private val log: (String) -> Unit,
) : DriverConnector {
    private sealed interface Path {
        data object Spp : Path
        data class Channel(val number: Int) : Path
    }

    override suspend fun connect(scope: CoroutineScope): HeadphoneDriver {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        val device = locator.find(adapter) ?: throw IOException("headphones not paired")
        return connectBose(device, scope)
    }

    suspend fun connectBose(device: BluetoothDevice, scope: CoroutineScope): BoseDriver {
        val guessed = BoseCatalog.inferFromName(device.name)
        val paths = buildList {
            add(Path.Spp)
            if (guessed?.defaultChannel == 8) {
                add(Path.Channel(8))
                add(Path.Channel(2))
            } else {
                add(Path.Channel(2))
                add(Path.Channel(8))
            }
            add(Path.Channel(9))
        }
        for (path in paths) {
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
            val probe = probeBose(connection, guessed, path)
            if (probe != null) {
                log("CONNECT $path ok (${probe.id}) in ${(System.nanoTime() - started) / 1_000_000} ms")
                return BoseDriver(connection, scope, probe, log)
            }
            connection.close()
        }
        throw IOException("no BMAP answer on SPP or channels 2, 8, 9")
    }

    private suspend fun probeBose(
        connection: BmapConnection,
        guessed: io.github.sargemck.practicalheadphones.bmap.BoseProfile?,
        path: Path,
    ): io.github.sargemck.practicalheadphones.bmap.BoseProfile? {
        val hp = Headphones(connection)
        var usedInit = false
        val battery = try {
            if (guessed?.requiresInitPacket == true) {
                runCatching { hp.initSession() }
                usedInit = true
            }
            hp.batteryStatus()
        } catch (e: BmapException) {
            if (!usedInit) {
                // Older Bose models (QC45, QC35) stay silent until GET [0.1] is sent.
                try {
                    hp.initSession()
                    usedInit = true
                    hp.batteryStatus()
                } catch (e2: BmapException) {
                    log("CONNECT $path no BMAP answer after init: ${e2.message}")
                    return null
                }
            } else {
                log("CONNECT $path no BMAP answer: ${e.message}")
                return null
            }
        }
        return BoseCatalog.refineFromWire(
            initial = guessed,
            batteryPayloadLength = if (battery.components.isNotEmpty()) 16 else 1,
            usedInitPacket = usedInit,
        )
    }

    private fun open(device: BluetoothDevice, path: Path): BluetoothSocket = when (path) {
        Path.Spp -> device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
        // Non-SDK API, allowed at every target SDK today (CLAUDE.md).
        is Path.Channel -> device.javaClass
            .getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
            .invoke(device, path.number) as BluetoothSocket
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    }
}
