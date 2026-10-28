package io.github.sargemck.practicalheadphones.link

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager

/** Screen on/off, so hidden-UI polling pauses while nobody can see the notification. */
class ScreenMonitor(private val context: Context, private val onChange: (Boolean) -> Unit) {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            onChange(intent.action == Intent.ACTION_SCREEN_ON)
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        context.registerSystemReceiver(receiver, filter)
        onChange(context.getSystemService(PowerManager::class.java).isInteractive)
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
    }
}

/** System broadcasts only; not exported where the flag exists (API 33+). */
@SuppressLint("UnspecifiedRegisterReceiverFlag") // Before API 33 there is no flag; system broadcasts only.
fun Context.registerSystemReceiver(receiver: BroadcastReceiver, filter: IntentFilter) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
        registerReceiver(receiver, filter)
    }
}

object BoseAppDetector {
    private const val PACKAGE = "com.bose.bosemusic"

    /** Needs the `<queries>` entry in the manifest. */
    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
