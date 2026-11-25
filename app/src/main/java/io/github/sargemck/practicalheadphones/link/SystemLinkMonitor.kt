package io.github.sargemck.practicalheadphones.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothLeAudio
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import io.github.sargemck.practicalheadphones.state.SystemLink

/**
 * What Android reports about Bluetooth and the headphones. They count as connected when A2DP,
 * HEADSET (HFP), or LE Audio (Android 13+) is connected; any can be up without the others.
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is checked before the service starts.
class SystemLinkMonitor(
    private val context: Context,
    private val locator: HeadphonesLocator,
    private val onChange: (SystemLink) -> Unit,
    private val log: (String) -> Unit,
) {
    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    private val profiles = buildList {
        add(BluetoothProfile.A2DP)
        add(BluetoothProfile.HEADSET)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(BluetoothProfile.LE_AUDIO)
    }
    private val activeByProfile = profiles.associateWith { mutableSetOf<String>() }
    private val proxies = mutableMapOf<Int, BluetoothProfile>()
    private var last: SystemLink? = null
    private var lastAddress: String? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val on = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON
                    log("SYSTEM bluetooth ${if (on) "on" else "not on"}")
                    if (!on) activeByProfile.values.forEach { it.clear() }
                    if (on) queryProfiles()
                }
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> profileChanged(BluetoothProfile.A2DP, intent)
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> profileChanged(BluetoothProfile.HEADSET, intent)
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val bondedAddresses = adapter.bondedDevices.orEmpty().map { it.address }.toSet()
                    activeByProfile.values.forEach { set -> set.retainAll(bondedAddresses) }
                }
                else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    intent.action == BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED
                ) {
                    profileChanged(BluetoothProfile.LE_AUDIO, intent)
                }
            }
            publish()
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                addAction(BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED)
            }
        }
        // Sent by the Bluetooth app, not the system uid: must be exported to arrive.
        context.registerSystemReceiver(receiver, filter, exported = true)
        queryProfiles()
        publish()
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
        proxies.forEach { (profile, proxy) -> adapter.closeProfileProxy(profile, proxy) }
        proxies.clear()
        activeByProfile.values.forEach { it.clear() }
        last = null
        lastAddress = null
    }

    private fun profileChanged(profile: Int, intent: Intent) {
        @Suppress("DEPRECATION")
        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
        val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
        val set = activeByProfile[profile] ?: return
        if (state == BluetoothProfile.STATE_CONNECTED) {
            set.add(device.address)
        } else {
            set.remove(device.address)
        }
        log("SYSTEM ${name(profile)} state=$state")
    }

    /** Initial state through the profile proxies (they connect asynchronously). */
    private fun queryProfiles() {
        if (!adapter.isEnabled) return
        for (profile in profiles) {
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
        val set = activeByProfile[profile] ?: return
        set.clear()
        runCatching { proxy.connectedDevices }.getOrDefault(emptyList()).forEach { set.add(it.address) }
    }

    private fun publish() {
        val connectedSet = if (adapter.isEnabled) activeByProfile.values.flatten().toSet() else emptySet()
        val active = if (adapter.isEnabled) locator.find(adapter, connectedSet) else null
        val link = SystemLink(
            bluetoothOn = adapter.isEnabled,
            paired = active != null,
            connected = active != null && active.address in connectedSet,
        )
        val switched = active?.address != null && lastAddress != null && active.address != lastAddress && link.connected
        if (active?.address != null) lastAddress = active.address
        if (switched && last == link) {
            // Active headset auto-switched to a newly connected pair while link stayed Connected:
            // bounce through disconnected -> connected so the repository opens the new driver.
            log("SYSTEM auto-switched active headset")
            onChange(link.copy(connected = false))
            onChange(link)
            return
        }
        if (link != last) {
            last = link
            log("SYSTEM $link")
            onChange(link)
        }
    }

    private fun name(profile: Int) = when {
        profile == BluetoothProfile.A2DP -> "A2DP"
        profile == BluetoothProfile.HEADSET -> "HFP"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && profile == BluetoothProfile.LE_AUDIO -> "LE_AUDIO"
        else -> profile.toString()
    }
}
