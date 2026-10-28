package io.github.sargemck.practicalheadphones.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import io.github.sargemck.practicalheadphones.state.SystemLink

/**
 * What Android reports about Bluetooth and the headphones. They count as connected when A2DP
 * or HEADSET (HFP) is connected; either can be up without the other. Known gap: LE Audio
 * (fw 10.12.12 beta) would show neither (FINDINGS).
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is checked before the service starts.
class SystemLinkMonitor(
    private val context: Context,
    private val locator: HeadphonesLocator,
    private val onChange: (SystemLink) -> Unit,
    private val log: (String) -> Unit,
) {
    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    private val connected = mutableMapOf(BluetoothProfile.A2DP to false, BluetoothProfile.HEADSET to false)
    private val proxies = mutableMapOf<Int, BluetoothProfile>()
    private var last: SystemLink? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val on = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON
                    log("SYSTEM bluetooth ${if (on) "on" else "not on"}")
                    if (!on) connected.keys.forEach { connected[it] = false }
                    if (on) queryProfiles()
                }
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> profileChanged(BluetoothProfile.A2DP, intent)
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> profileChanged(BluetoothProfile.HEADSET, intent)
            }
            publish()
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        }
        context.registerSystemReceiver(receiver, filter)
        queryProfiles()
        publish()
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
        proxies.forEach { (profile, proxy) -> adapter.closeProfileProxy(profile, proxy) }
        proxies.clear()
    }

    private fun profileChanged(profile: Int, intent: Intent) {
        @Suppress("DEPRECATION")
        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        if (device == null || device != locator.find(adapter)) return
        val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
        connected[profile] = state == BluetoothProfile.STATE_CONNECTED
        log("SYSTEM ${name(profile)} state=$state")
    }

    /** Initial state through the profile proxies (they connect asynchronously). */
    private fun queryProfiles() {
        if (!adapter.isEnabled) return
        for (profile in connected.keys) {
            proxies[profile]?.let { update(profile, it); continue }
            adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                    proxies[p] = proxy
                    update(p, proxy)
                    publish()
                }

                override fun onServiceDisconnected(p: Int) {
                    proxies.remove(p)
                }
            }, profile)
        }
    }

    private fun update(profile: Int, proxy: BluetoothProfile) {
        val device = locator.find(adapter) ?: return
        connected[profile] = proxy.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED
    }

    private fun publish() {
        val link = SystemLink(
            bluetoothOn = adapter.isEnabled,
            paired = adapter.isEnabled && locator.find(adapter) != null,
            connected = adapter.isEnabled && connected.values.any { it },
        )
        if (link != last) {
            last = link
            log("SYSTEM $link")
            onChange(link)
        }
    }

    private fun name(profile: Int) = if (profile == BluetoothProfile.A2DP) "A2DP" else "HFP"
}
