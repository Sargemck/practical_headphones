package io.github.sargemck.practicalheadphones.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.ParcelUuid
import java.util.UUID

/**
 * Finds the paired headphones: the bonded device that advertises the BMAP service UUID
 * (M0 E8), else one named like a QC Ultra. The address is remembered, so renaming the
 * headphones doesn't lose them. Addresses are never logged.
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is checked before the service starts.
class HeadphonesLocator(context: Context) {
    private val prefs = context.getSharedPreferences("headphones", Context.MODE_PRIVATE)

    fun find(adapter: BluetoothAdapter): BluetoothDevice? {
        val bonded = adapter.bondedDevices.orEmpty()
        prefs.getString(KEY_ADDRESS, null)?.let { saved ->
            bonded.firstOrNull { it.address == saved }?.let { return it }
        }
        val found = bonded.firstOrNull { device -> device.uuids.orEmpty().any { it == BMAP_SERVICE } }
            ?: bonded.firstOrNull { it.name?.contains(NAME_MATCH, ignoreCase = true) == true }
            ?: return null
        prefs.edit().putString(KEY_ADDRESS, found.address).apply()
        return found
    }

    companion object {
        val BMAP_SERVICE: ParcelUuid = ParcelUuid(UUID.fromString("00000000-deca-fade-deca-deafdecacaff"))
        private const val NAME_MATCH = "QC Ultra"
        private const val KEY_ADDRESS = "address"
    }
}
