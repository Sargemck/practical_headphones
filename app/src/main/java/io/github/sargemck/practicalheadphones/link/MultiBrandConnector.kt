package io.github.sargemck.practicalheadphones.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import io.github.sargemck.practicalheadphones.bmap.FrameLogger
import io.github.sargemck.practicalheadphones.driver.DriverConnector
import io.github.sargemck.practicalheadphones.driver.HeadphoneDriver
import io.github.sargemck.practicalheadphones.driver.JblDriver
import io.github.sargemck.practicalheadphones.driver.NothingDriver
import io.github.sargemck.practicalheadphones.driver.SamsungDriver
import io.github.sargemck.practicalheadphones.driver.SonyDriver
import io.github.sargemck.practicalheadphones.driver.SoundcoreDriver
import io.github.sargemck.practicalheadphones.jbl.JblConnection
import io.github.sargemck.practicalheadphones.jbl.JblProtocol
import io.github.sargemck.practicalheadphones.nothing.NothingConnection
import io.github.sargemck.practicalheadphones.nothing.NothingProtocol
import io.github.sargemck.practicalheadphones.samsung.SamsungConnection
import io.github.sargemck.practicalheadphones.samsung.SamsungProtocol
import io.github.sargemck.practicalheadphones.sony.SonyConnection
import io.github.sargemck.practicalheadphones.sony.SonyProtocol
import io.github.sargemck.practicalheadphones.soundcore.SoundcoreConnection
import io.github.sargemck.practicalheadphones.soundcore.SoundcoreProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Multi-brand RFCOMM connector routing to Bose (`:bmap`), Sony (`:sony`), Anker Soundcore
 * (`:soundcore`), or Samsung Galaxy Buds (`:samsung`). One attempt per UUID path, no retry loops.
 */
@SuppressLint("MissingPermission")
class MultiBrandConnector(
    private val context: Context,
    private val locator: HeadphonesLocator,
    logger: FrameLogger,
    private val log: (String) -> Unit,
) : DriverConnector {
    private val boseConnector = RfcommConnector(context, locator, logger, log)

    override suspend fun connect(scope: CoroutineScope): HeadphoneDriver {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        val device = locator.find(adapter) ?: throw IOException("headphones not paired")
        val brand = locator.brandOf(device)
        log("CONNECT brand=$brand")
        return when (brand) {
            HeadphoneBrand.BOSE -> boseConnector.connectBose(device, scope)
            HeadphoneBrand.SONY -> connectSony(device, scope)
            HeadphoneBrand.SOUNDCORE -> connectSoundcore(device, scope)
            HeadphoneBrand.SAMSUNG -> connectSamsung(device, scope)
            HeadphoneBrand.NOTHING -> connectNothing(device, scope)
            HeadphoneBrand.JBL -> connectJbl(adapter, device, scope)
        }
    }

    private suspend fun connectSony(device: BluetoothDevice, scope: CoroutineScope): HeadphoneDriver {
        val socket = openFirst(
            device,
            listOf(UUID.fromString(SonyProtocol.UUID_V2), UUID.fromString(SonyProtocol.UUID_V1)),
        )
        val conn = SonyConnection(RfcommTransport(socket), scope)
        return SonyDriver(conn, scope, device.name ?: "Sony headphones", log)
    }

    private suspend fun connectSoundcore(device: BluetoothDevice, scope: CoroutineScope): HeadphoneDriver {
        val vendorUuid = device.uuids.orEmpty()
            .map { it.uuid }
            .firstOrNull { it.toString().lowercase().startsWith(SoundcoreProtocol.UUID_PREFIX) }
        val uuids = listOfNotNull(
            vendorUuid,
            UUID.fromString(SoundcoreProtocol.DEFAULT_UUID).takeIf { it != vendorUuid },
            RfcommConnector.SPP_UUID,
        )
        val socket = openFirst(device, uuids)
        val conn = SoundcoreConnection(RfcommTransport(socket), scope)
        return SoundcoreDriver(conn, scope, device.name ?: "Soundcore headphones", vendorUuid?.toString(), log)
    }

    private suspend fun connectSamsung(device: BluetoothDevice, scope: CoroutineScope): HeadphoneDriver {
        val socket = openFirst(
            device,
            listOf(UUID.fromString(SamsungProtocol.UUID_SPP_NEW), UUID.fromString(SamsungProtocol.UUID_SPP)),
        )
        val conn = SamsungConnection(RfcommTransport(socket), scope)
        return SamsungDriver(conn, scope, device.name ?: "Galaxy Buds", log)
    }

    private suspend fun connectNothing(device: BluetoothDevice, scope: CoroutineScope): HeadphoneDriver {
        val socket = try {
            openFirst(device, listOf(UUID.fromString(NothingProtocol.UUID_NT_LINK)))
        } catch (_: IOException) {
            openFirstChannel(device, NothingProtocol.channelsForName(device.name))
        }
        val conn = NothingConnection(RfcommTransport(socket), scope)
        return NothingDriver(conn, scope, device.name ?: "Nothing Ear", log)
    }

    private suspend fun connectJbl(
        adapter: android.bluetooth.BluetoothAdapter,
        device: BluetoothDevice,
        scope: CoroutineScope,
    ): HeadphoneDriver {
        val serviceUuid = UUID.fromString(JblProtocol.GATT_SERVICE_UUID)
        val notifyUuid = UUID.fromString(JblProtocol.GATT_NOTIFY_CHAR_UUID)
        val writeUuid = UUID.fromString(JblProtocol.GATT_WRITE_CHAR_UUID)

        val gattTransport = try {
            BleGattTransport.connect(context, device, serviceUuid, notifyUuid, writeUuid, log)
        } catch (firstErr: Exception) {
            if (firstErr is CancellationException) throw firstErr
            val bleAddress = discoverFastPairBleAddress(device) ?: throw firstErr
            val bleDevice = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                adapter.getRemoteLeDevice(bleAddress, BluetoothDevice.ADDRESS_TYPE_RANDOM)
            } else {
                adapter.getRemoteDevice(bleAddress)
            }
            BleGattTransport.connect(context, bleDevice, serviceUuid, notifyUuid, writeUuid, log)
        }
        val conn = JblConnection(gattTransport, scope)
        return JblDriver(conn, scope, device.name ?: "JBL earbuds", log)
    }

    private suspend fun discoverFastPairBleAddress(device: BluetoothDevice): String? {
        var socket: BluetoothSocket? = null
        return try {
            val s = device.createInsecureRfcommSocketToServiceRecord(UUID.fromString(JblProtocol.FAST_PAIR_RFCOMM_UUID))
            socket = s
            withContext(Dispatchers.IO) { s.connect() }
            withTimeoutOrNull(2.seconds) {
                withContext(Dispatchers.IO) {
                    val buf = ByteArray(256)
                    val n = s.inputStream.read(buf)
                    if (n > 0) JblProtocol.parseFastPairBleAddress(buf.copyOf(n)) else null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
            runCatching { socket?.close() }
        }
    }

    private suspend fun openFirstChannel(device: BluetoothDevice, channels: List<Int>): BluetoothSocket {
        var lastError: Exception? = null
        for (ch in channels) {
            var opened: BluetoothSocket? = null
            try {
                val sock = device.javaClass
                    .getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(device, ch) as BluetoothSocket
                opened = sock
                withContext(Dispatchers.IO) { sock.connect() }
                return sock
            } catch (e: CancellationException) {
                runCatching { opened?.close() }
                throw e
            } catch (e: Exception) {
                runCatching { opened?.close() }
                lastError = e
            }
        }
        throw IOException("RFCOMM channel connect failed: ${lastError?.message}", lastError)
    }

    private suspend fun openFirst(device: BluetoothDevice, uuids: List<UUID>): BluetoothSocket {
        var lastError: Exception? = null
        for (uuid in uuids) {
            var opened: BluetoothSocket? = null
            try {
                return device.createInsecureRfcommSocketToServiceRecord(uuid).also {
                    opened = it
                    withContext(Dispatchers.IO) { it.connect() }
                }
            } catch (e: CancellationException) {
                runCatching { opened?.close() }
                throw e
            } catch (e: Exception) {
                runCatching { opened?.close() }
                log("CONNECT uuid=$uuid failed: ${e.javaClass.simpleName} ${e.message}")
                lastError = e
            }
        }
        throw IOException("RFCOMM connect failed: ${lastError?.message}", lastError)
    }
}
