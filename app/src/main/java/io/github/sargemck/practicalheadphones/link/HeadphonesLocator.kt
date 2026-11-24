package io.github.sargemck.practicalheadphones.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.ParcelUuid
import java.util.UUID

/**
 * Finds the paired headphones: prefers a Classic/Dual bonded device that matches "QC Ultra"
 * (with or without cached BMAP UUID), then falls back to any Classic/Dual device advertising
 * the BMAP service UUID (M0 E8) so renamed headphones still match. BLE-only bonds (`LE-...`)
 * are ignored because BMAP runs over Classic RFCOMM. The address is remembered, so renaming
 * the headphones doesn't lose them. Addresses are never logged.
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is checked before the service starts.
class HeadphonesLocator(context: Context) {
    private val prefs = context.getSharedPreferences("headphones", Context.MODE_PRIVATE)

    fun find(adapter: BluetoothAdapter): BluetoothDevice? {
        val bonded = adapter.bondedDevices.orEmpty()
        val candidates = bonded.map { device ->
            Candidate(
                address = device.address,
                name = device.name,
                hasBmapUuid = device.uuids.orEmpty().any { it == BMAP_SERVICE },
                isLeOnly = device.type == BluetoothDevice.DEVICE_TYPE_LE,
            )
        }
        val selected = select(candidates, savedAddress = prefs.getString(KEY_ADDRESS, null)) ?: return null
        if (selected.address != prefs.getString(KEY_ADDRESS, null)) {
            prefs.edit().putString(KEY_ADDRESS, selected.address).apply()
        }
        return bonded.firstOrNull { it.address == selected.address }
    }

    internal data class Candidate(
        val address: String,
        val name: String?,
        val hasBmapUuid: Boolean,
        val isLeOnly: Boolean,
    )

    companion object {
        val BMAP_SERVICE: ParcelUuid = ParcelUuid(UUID.fromString("00000000-deca-fade-deca-deafdecacaff"))
        private const val NAME_MATCH = "QC Ultra"
        private const val KEY_ADDRESS = "address"

        internal fun select(candidates: Iterable<Candidate>, savedAddress: String?): Candidate? {
            val classic = candidates.filterNot { it.isLeOnly }
            if (savedAddress != null) {
                classic.firstOrNull { it.address == savedAddress }?.let { return it }
            }
            val qcUltra = classic.filter { it.name?.contains(NAME_MATCH, ignoreCase = true) == true }
            return qcUltra.firstOrNull { it.hasBmapUuid }
                ?: qcUltra.firstOrNull()
                ?: classic.firstOrNull { it.hasBmapUuid }
        }
    }
}
