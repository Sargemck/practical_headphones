package io.github.sargemck.practicalheadphones

import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import io.github.sargemck.practicalheadphones.bmap.Address
import io.github.sargemck.practicalheadphones.bmap.BmapConnection
import io.github.sargemck.practicalheadphones.bmap.BmapOp
import io.github.sargemck.practicalheadphones.bmap.ErrorCodes
import io.github.sargemck.practicalheadphones.bmap.FakeBmapDevice
import io.github.sargemck.practicalheadphones.link.BmapLog
import io.github.sargemck.practicalheadphones.state.Connector
import io.github.sargemck.practicalheadphones.state.HeadphoneController
import io.github.sargemck.practicalheadphones.state.HeadphoneRepository
import io.github.sargemck.practicalheadphones.state.LinkState
import io.github.sargemck.practicalheadphones.state.LiveUpdates
import io.github.sargemck.practicalheadphones.state.SystemLink
import io.github.sargemck.practicalheadphones.ui.Permission
import io.github.sargemck.practicalheadphones.ui.Permissions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Debug builds only. The release source set has the same object doing nothing. */
object DebugEntry {
    /** Keeps the screen on and shows over the lock screen, so hands-off runs work on a locked phone. */
    fun onCreate(activity: ComponentActivity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        activity.setShowWhenLocked(true)
        activity.setTurnScreenOn(true)
    }

    /**
     * Demo mode (`adb shell am start ... --ez demo true`): a repository over [FakeBmapDevice],
     * reported connected. The service isn't started and the headphones aren't touched.
     * `--es demoLink away` reports the headphones gone once everything has loaded (the last known
     * view); `--es demoLink never` never reports them connected (first run).
     */
    fun demoController(activity: ComponentActivity): HeadphoneController? {
        if (!activity.intent.getBooleanExtra("demo", false)) return null
        val repository = HeadphoneRepository(
            scope = activity.lifecycleScope,
            connector = Connector { scope ->
                // `--es demoFail 1.11` makes that address answer GET with a Runtime error (S1 E6).
                val fail = activity.intent.getStringExtra("demoFail")?.split(".")?.map(String::toInt)
                val fake = FakeBmapDevice(scope).apply {
                    if (fail != null) forceError[Address(fail[0], fail[1]) to BmapOp.GET] = ErrorCodes.RUNTIME
                }
                BmapConnection(fake, scope)
            },
            clock = SystemClock::elapsedRealtime,
            log = { BmapLog.d("DEMO $it") },
            live = LiveUpdates(polling = false),
        )
        val on = SystemLink(bluetoothOn = true, paired = true, connected = true)
        when (activity.intent.getStringExtra("demoLink")) {
            "never" -> repository.onSystemLink(on.copy(connected = false))
            "away" -> {
                repository.onSystemLink(on)
                activity.lifecycleScope.launch {
                    repository.state.first { it.link == LinkState.Connected && it.fullyLoaded }
                    repository.onSystemLink(on.copy(connected = false))
                }
            }
            else -> repository.onSystemLink(on)
        }
        return repository
    }

    /**
     * Demo mode only: `--es demoPerm bt|bt-blocked|notif|notif-blocked` shows that permission
     * state on screen for screenshots. Nothing is requested.
     */
    fun demoPermissions(activity: ComponentActivity): Permissions? {
        if (!activity.intent.getBooleanExtra("demo", false)) return null
        return when (activity.intent.getStringExtra("demoPerm")) {
            "bt" -> Permissions(bluetooth = Permission.Denied)
            "bt-blocked" -> Permissions(bluetooth = Permission.Blocked)
            "notif" -> Permissions(notifications = Permission.Denied)
            "notif-blocked" -> Permissions(notifications = Permission.Blocked)
            else -> null
        }
    }
}
