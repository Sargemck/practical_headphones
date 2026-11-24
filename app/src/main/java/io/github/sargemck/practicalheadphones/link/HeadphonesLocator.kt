package io.github.sargemck.practicalheadphones.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.ParcelUuid
import io.github.sargemck.practicalheadphones.samsung.SamsungProtocol
import io.github.sargemck.practicalheadphones.sony.SonyProtocol
import io.github.sargemck.practicalheadphones.soundcore.SoundcoreProtocol
import io.github.sargemck.practicalheadphones.state.PairedHeadphoneChoice
import java.util.UUID

enum class HeadphoneBrand(val displayName: String) {
    BOSE("Bose"),
    SONY("Sony"),
    SOUNDCORE("Soundcore"),
    SAMSUNG("Samsung"),
}

/**
 * Finds paired supported headphones across Bose, Sony, Anker Soundcore, and Samsung Galaxy Buds.
 * BLE-only bonds (`DEVICE_TYPE_LE`, e.g. `LE-...`) are ignored because all four protocols run
 * over Classic RFCOMM. The selected address is remembered in SharedPreferences so renaming the
 * headphones doesn't lose them. Addresses are never logged.
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is checked before the service starts.
class HeadphonesLocator(context: Context) {
    private val prefs = context.getSharedPreferences("headphones", Context.MODE_PRIVATE)

    fun find(adapter: BluetoothAdapter): BluetoothDevice? {
        val bonded = adapter.bondedDevices.orEmpty()
        val candidates = bonded.map(::toCandidate)
        val selected = select(candidates, savedAddress = prefs.getString(KEY_ADDRESS, null)) ?: return null
        if (selected.address != prefs.getString(KEY_ADDRESS, null)) {
            prefs.edit().putString(KEY_ADDRESS, selected.address).apply()
        }
        return bonded.firstOrNull { it.address == selected.address }
    }

    /** Returns every supported Classic/Dual bonded headphone for the device switcher UI. */
    fun listAll(adapter: BluetoothAdapter): List<PairedHeadphoneChoice> {
        val saved = prefs.getString(KEY_ADDRESS, null)
        val bonded = adapter.bondedDevices.orEmpty()
        val supported = bonded.map(::toCandidate)
            .filter { !it.isLeOnly && detectBrand(it) != null }
        val activeAddress = select(supported, saved)?.address
        return supported.map { c ->
            val brand = detectBrand(c)!!
            PairedHeadphoneChoice(
                address = c.address,
                name = c.name?.takeIf { it.isNotBlank() } ?: "${brand.displayName} headphones",
                brand = brand.displayName,
                selected = c.address == activeAddress,
            )
        }
    }

    fun selectAddress(address: String) {
        prefs.edit().putString(KEY_ADDRESS, address).apply()
    }

    fun brandOf(device: BluetoothDevice): HeadphoneBrand =
        detectBrand(toCandidate(device)) ?: HeadphoneBrand.BOSE

    private fun toCandidate(device: BluetoothDevice): Candidate {
        val uuids = device.uuids.orEmpty().map { it.uuid.toString().lowercase() }.toSet()
        return Candidate(
            address = device.address,
            name = device.name,
            hasBmapUuid = BMAP_SERVICE.uuid.toString().lowercase() in uuids,
            isLeOnly = device.type == BluetoothDevice.DEVICE_TYPE_LE,
            uuids = uuids,
        )
    }

    internal data class Candidate(
        val address: String,
        val name: String?,
        val hasBmapUuid: Boolean,
        val isLeOnly: Boolean,
        val uuids: Set<String> = emptySet(),
    )

    companion object {
        val BMAP_SERVICE: ParcelUuid = ParcelUuid(UUID.fromString("00000000-deca-fade-deca-deafdecacaff"))
        private const val NAME_MATCH = "QC Ultra"
        private const val KEY_ADDRESS = "address"

        internal fun detectBrand(candidate: Candidate): HeadphoneBrand? {
            if (candidate.isLeOnly) return null
            val n = candidate.name?.trim()?.lowercase().orEmpty()
            val uuids = candidate.uuids

            if (candidate.hasBmapUuid ||
                "qc ultra" in n || "quietcomfort" in n || "qc45" in n || "qc35" in n ||
                "bose qc" in n || "ultra open" in n
            ) {
                return HeadphoneBrand.BOSE
            }
            if (SonyProtocol.UUID_V2 in uuids || SonyProtocol.UUID_V1 in uuids ||
                "wh-1000xm" in n || "wf-1000xm" in n || "wh-ch720n" in n || "linkbuds" in n
            ) {
                return HeadphoneBrand.SONY
            }
            if (uuids.any { it.startsWith(SoundcoreProtocol.UUID_PREFIX) } ||
                "soundcore" in n || "life q30" in n || "life q35" in n ||
                "space q45" in n || "space one" in n || "space 2" in n || "liberty 4" in n
            ) {
                return HeadphoneBrand.SOUNDCORE
            }
            if (SamsungProtocol.UUID_SPP_NEW in uuids || "galaxy buds" in n) {
                return HeadphoneBrand.SAMSUNG
            }
            return null
        }

        internal fun select(candidates: Iterable<Candidate>, savedAddress: String?): Candidate? {
            val classic = candidates.filterNot { it.isLeOnly }
            if (savedAddress != null) {
                classic.firstOrNull { it.address == savedAddress }?.let { return it }
            }
            // Prefer verified Bose QC Ultra first for backward compatibility.
            val qcUltra = classic.filter { it.name?.contains(NAME_MATCH, ignoreCase = true) == true }
            qcUltra.firstOrNull { it.hasBmapUuid }?.let { return it }
            qcUltra.firstOrNull()?.let { return it }
            classic.firstOrNull { it.hasBmapUuid }?.let { return it }
            return classic.firstOrNull { detectBrand(it) != null }
        }
    }
}
