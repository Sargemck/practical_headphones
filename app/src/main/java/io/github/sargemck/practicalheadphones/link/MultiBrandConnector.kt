package io.github.sargemck.practicalheadphones.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import io.github.sargemck.practicalheadphones.bmap.FrameLogger
import io.github.sargemck.practicalheadphones.driver.DriverConnector
import io.github.sargemck.practicalheadphones.driver.HeadphoneDriver
import io.github.sargemck.practicalheadphones.driver.SamsungDriver
import io.github.sargemck.practicalheadphones.driver.SonyDriver
import io.github.sargemck.practicalheadphones.driver.SoundcoreDriver
import io.github.sargemck.practicalheadphones.samsung.SamsungConnection
import io.github.sargemck.practicalheadphones.samsung.SamsungProtocol
import io.github.sargemck.practicalheadphones.sony.SonyConnection
import io.github.sargemck.practicalheadphones.sony.SonyProtocol
import io.github.sargemck.practicalheadphones.soundcore.SoundcoreConnection
import io.github.sargemck.practicalheadphones.soundcore.SoundcoreProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

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
        return SoundcoreDriver(conn, scope, device.name ?: "Soundcore headphones", log)
    }

    private suspend fun connectSamsung(device: BluetoothDevice, scope: CoroutineScope): HeadphoneDriver {
        val socket = openFirst(
            device,
            listOf(UUID.fromString(SamsungProtocol.UUID_SPP_NEW), UUID.fromString(SamsungProtocol.UUID_SPP)),
        )
        val conn = SamsungConnection(RfcommTransport(socket), scope)
        return SamsungDriver(conn, scope, device.name ?: "Galaxy Buds", log)
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
            } catch (e: Exception) {
                runCatching { opened?.close() }
                log("CONNECT uuid=$uuid failed: ${e.javaClass.simpleName} ${e.message}")
                lastError = e
            }
        }
        throw IOException("RFCOMM connect failed: ${lastError?.message}", lastError)
    }
}
