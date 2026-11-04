package io.github.sargemck.practicalheadphones.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Auto-connect through the Companion Device Manager (PLAN M6): an association with the paired
 * headphones, then presence observation, so the system starts [io.github.sargemck.practicalheadphones.service.PresenceService]
 * when they connect. Addresses are never logged.
 */
@SuppressLint("MissingPermission") // Callers check BLUETOOTH_CONNECT; without it there's no address to associate.
class AutoConnect(private val context: Context, private val log: (String) -> Unit = {}) {
    enum class Status {
        /** No companion device support, or the headphones aren't paired. */
        Unavailable,
        Off,
        On,
    }

    private val manager: CompanionDeviceManager? =
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) {
            context.getSystemService(CompanionDeviceManager::class.java)
        } else {
            null
        }

    private fun address(): String? {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        return runCatching { HeadphonesLocator(context).find(adapter)?.address }.getOrNull()
    }

    /**
     * On when this app has an association. It only ever associates the headphones, so this doesn't
     * need the paired-device list, which is empty while Bluetooth is off (M6 device run).
     */
    fun status(): Status {
        if (manager == null) return Status.Unavailable
        if (associated()) return Status.On
        return if (address() == null) Status.Unavailable else Status.Off
    }

    @Suppress("DEPRECATION")
    private fun associated(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) manager?.myAssociations.orEmpty().isNotEmpty() else manager?.associations.orEmpty().isNotEmpty()

    /**
     * Asks Android to associate the headphones. [launch] shows the system's confirmation;
     * [done] gets true once the association exists (and presence is observed).
     */
    fun request(launch: (IntentSender) -> Unit, done: (Boolean) -> Unit) {
        val manager = manager ?: return done(false)
        val address = address() ?: return done(false)
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(address).build())
            .setSingleDevice(true)
            .build()
        val callback = object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) = launch(intentSender)

            // API 31-32: the confirmation comes this way; the association exists once it's accepted.
            @Deprecated("Replaced by onAssociationPending in API 33")
            override fun onDeviceFound(intentSender: IntentSender) = launch(intentSender)

            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                log("AUTOCONNECT associated")
                observe()
                done(true)
            }

            override fun onFailure(error: CharSequence?) {
                log("AUTOCONNECT association failed: $error")
                done(false)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.associate(request, context.mainExecutor, callback)
        } else {
            @Suppress("DEPRECATION")
            manager.associate(request, callback, Handler(Looper.getMainLooper()))
        }
    }

    /** Registers for presence events. Idempotent; called after associating and on every service start. */
    @SuppressLint("MissingPermission") // REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE is a normal permission in the manifest.
    fun observe() {
        val manager = manager ?: return
        val address = address() ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                val association = manager.myAssociations.firstOrNull { it.deviceMacAddress?.toString().equals(address, ignoreCase = true) } ?: return
                manager.startObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(association.id).build())
            } else {
                if (!associated()) return
                @Suppress("DEPRECATION")
                manager.startObservingDevicePresence(address)
            }
            log("AUTOCONNECT observing presence")
        } catch (e: RuntimeException) {
            log("AUTOCONNECT observe failed: ${e.message}")
        }
    }
}
