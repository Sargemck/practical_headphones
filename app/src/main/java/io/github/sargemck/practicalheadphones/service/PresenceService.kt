package io.github.sargemck.practicalheadphones.service

import android.Manifest
import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import io.github.sargemck.practicalheadphones.link.BmapLog

/**
 * Bound by the system when the associated headphones connect (PLAN M6). Starts
 * [HeadphoneService], which then connects on the system's connected event as usual. Starting a
 * foreground service from here is allowed by REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND.
 * Disconnects need nothing: the service stops itself once the headphones are away.
 */
class PresenceService : CompanionDeviceService() {
    @RequiresApi(Build.VERSION_CODES.BAKLAVA) // Only called on API 36+.
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        BmapLog.d("PRESENCE event ${event.event}")
        if (event.event == DevicePresenceEvent.EVENT_BT_CONNECTED) startHeadphoneService()
    }

    @Deprecated("API 36 delivers onDevicePresenceEvent instead")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) return
        BmapLog.d("PRESENCE appeared")
        startHeadphoneService()
    }

    private fun startHeadphoneService() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            BmapLog.d("PRESENCE no Bluetooth permission, not starting")
            return
        }
        try {
            HeadphoneService.start(this)
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException is an IllegalStateException.
            BmapLog.d("PRESENCE couldn't start the service: ${e.message}")
        }
    }
}
