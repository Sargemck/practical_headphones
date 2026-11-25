package io.github.sargemck.practicalheadphones.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import io.github.sargemck.practicalheadphones.jbl.JblTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/**
 * Coroutine-backed Android BLE GATT characteristic transport (`TRANSPORT_LE`).
 *
 * Opens a GATT connection, discovers [serviceUuid], enables notifications via CCCD (`0x2902`) on
 * [notifyCharUuid], and serializes writes to [writeCharUuid] behind a [Mutex]. Reusable for any
 * BLE GATT headphone protocol (JBL, Sennheiser, etc.).
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is checked before the service starts.
class BleGattTransport private constructor(
    private val gatt: BluetoothGatt,
    private val writeChar: BluetoothGattCharacteristic,
    private val incoming: Channel<ByteArray?>,
    private val writeCompleted: Channel<Int>,
    private val closed: AtomicBoolean,
) : JblTransport {
    private val writeMutex = Mutex()

    override suspend fun read(): ByteArray? = incoming.receive()

    override suspend fun write(bytes: ByteArray): Unit = writeMutex.withLock {
        if (closed.get()) throw IOException("BLE GATT closed")
        while (writeCompleted.tryReceive().isSuccess) {
            // Drain any stale completion before issuing next write
        }
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val writeType = if ((writeChar.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
            gatt.writeCharacteristic(writeChar, bytes, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                writeChar.value = bytes
                gatt.writeCharacteristic(writeChar)
            }
        }
        if (!started) throw IOException("BLE GATT writeCharacteristic failed to start")
        val status = withTimeoutOrNull(3.seconds) { writeCompleted.receive() }
            ?: throw IOException("BLE GATT write timed out")
        if (status != BluetoothGatt.GATT_SUCCESS) {
            throw IOException("BLE GATT write failed: status=$status")
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        incoming.trySend(null)
        writeCompleted.close()
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
    }

    companion object {
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        suspend fun connect(
            context: Context,
            device: BluetoothDevice,
            serviceUuid: UUID,
            notifyCharUuid: UUID,
            writeCharUuid: UUID,
            log: (String) -> Unit = {},
        ): BleGattTransport {
            val incoming = Channel<ByteArray?>(Channel.UNLIMITED)
            val writeCompleted = Channel<Int>(Channel.CONFLATED)
            val closed = AtomicBoolean(false)
            val connectedReady = CompletableDeferred<BluetoothGatt>()
            val servicesReady = CompletableDeferred<BluetoothGatt>()
            val cccdReady = CompletableDeferred<Int>()

            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                        connectedReady.complete(g)
                        g.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        if (!connectedReady.isCompleted) {
                            connectedReady.completeExceptionally(IOException("GATT disconnected during connect (status=$status)"))
                        }
                        if (!servicesReady.isCompleted) {
                            servicesReady.completeExceptionally(IOException("GATT disconnected before service discovery"))
                        }
                        if (closed.compareAndSet(false, true)) {
                            incoming.trySend(null)
                            writeCompleted.close()
                            runCatching { g.close() }
                        }
                    }
                }

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        servicesReady.complete(g)
                    } else {
                        servicesReady.completeExceptionally(IOException("GATT discoverServices failed: $status"))
                    }
                }

                override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                    cccdReady.complete(status)
                }

                override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                    writeCompleted.trySend(status)
                }

                override fun onCharacteristicChanged(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                ) {
                    if (characteristic.uuid == notifyCharUuid) {
                        incoming.trySend(value.copyOf())
                    }
                }

                @Deprecated("Used on API 31-32 before the ByteArray overload in API 33")
                override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && characteristic.uuid == notifyCharUuid) {
                        @Suppress("DEPRECATION")
                        characteristic.value?.let { incoming.trySend(it.copyOf()) }
                    }
                }
            }

            @Suppress("DEPRECATION") // 4-arg connectGatt is the lowest-SDK overload supporting TRANSPORT_LE from minSdk 31.
            val gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                ?: throw IOException("connectGatt returned null")

            try {
                withTimeoutOrNull(8.seconds) { servicesReady.await() }
                    ?: throw IOException("BLE GATT service discovery timed out")
                val service = gatt.getService(serviceUuid)
                    ?: throw IOException("BLE GATT service $serviceUuid not found")
                val notifyChar = service.getCharacteristic(notifyCharUuid)
                    ?: throw IOException("BLE GATT notify characteristic $notifyCharUuid not found")
                val writeChar = service.getCharacteristic(writeCharUuid)
                    ?: throw IOException("BLE GATT write characteristic $writeCharUuid not found")

                gatt.setCharacteristicNotification(notifyChar, true)
                val cccd = notifyChar.getDescriptor(CCCD_UUID)
                if (cccd != null) {
                    val cccdStarted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        run {
                            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(cccd)
                        }
                    }
                    if (cccdStarted) {
                        withTimeoutOrNull(3.seconds) { cccdReady.await() }
                    }
                }
                log("CONNECT BLE GATT ok ($serviceUuid)")
                return BleGattTransport(gatt, writeChar, incoming, writeCompleted, closed)
            } catch (e: Exception) {
                closed.set(true)
                runCatching { gatt.disconnect() }
                runCatching { gatt.close() }
                throw e
            }
        }
    }
}
