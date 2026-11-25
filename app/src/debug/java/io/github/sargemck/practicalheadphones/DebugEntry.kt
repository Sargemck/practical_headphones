package io.github.sargemck.practicalheadphones

import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import io.github.sargemck.practicalheadphones.bmap.Address
import io.github.sargemck.practicalheadphones.bmap.BmapConnection
import io.github.sargemck.practicalheadphones.bmap.BmapOp
import io.github.sargemck.practicalheadphones.bmap.BoseCatalog
import io.github.sargemck.practicalheadphones.bmap.ErrorCodes
import io.github.sargemck.practicalheadphones.bmap.FakeBmapDevice
import io.github.sargemck.practicalheadphones.driver.BoseDriver
import io.github.sargemck.practicalheadphones.driver.DriverConnector
import io.github.sargemck.practicalheadphones.driver.JblDriver
import io.github.sargemck.practicalheadphones.driver.NothingDriver
import io.github.sargemck.practicalheadphones.driver.SamsungDriver
import io.github.sargemck.practicalheadphones.driver.SonyDriver
import io.github.sargemck.practicalheadphones.driver.SoundcoreDriver
import io.github.sargemck.practicalheadphones.jbl.FakeJblDevice
import io.github.sargemck.practicalheadphones.jbl.JblConnection
import io.github.sargemck.practicalheadphones.link.BmapLog
import io.github.sargemck.practicalheadphones.nothing.FakeNothingDevice
import io.github.sargemck.practicalheadphones.nothing.NothingConnection
import io.github.sargemck.practicalheadphones.samsung.FakeSamsungDevice
import io.github.sargemck.practicalheadphones.samsung.SamsungConnection
import io.github.sargemck.practicalheadphones.sony.FakeSonyDevice
import io.github.sargemck.practicalheadphones.sony.SonyConnection
import io.github.sargemck.practicalheadphones.soundcore.FakeSoundcoreDevice
import io.github.sargemck.practicalheadphones.soundcore.SoundcoreConnection
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
     * Demo mode (`adb shell am start ... --ez demo true`): a repository over an in-memory fake
     * device, reported connected. Supports `--es demoDevice qc_ultra2|qc_ultra2_earbuds|qc45|qc35|
     * sony_xm5|soundcore_q45|galaxy_buds2_pro`, `--es demoFail 1.11`, and `--es demoLink away|never`.
     */
    fun demoController(activity: ComponentActivity): HeadphoneController? {
        if (!activity.intent.getBooleanExtra("demo", false)) return null
        val demoDevice = activity.intent.getStringExtra("demoDevice")?.lowercase() ?: "qc_ultra2"
        val repository = HeadphoneRepository(
            scope = activity.lifecycleScope,
            driverConnector = DriverConnector { scope ->
                when (demoDevice) {
                    "sony_xm5" -> {
                        val fake = FakeSonyDevice(protocolV2 = true, inquiredType = 0x17)
                        SonyDriver(SonyConnection(fake, scope), scope, "WH-1000XM5") { BmapLog.d("DEMO $it") }
                    }
                    "soundcore_q45" -> {
                        val fake = FakeSoundcoreDevice(soundModeWidth = 6)
                        SoundcoreDriver(SoundcoreConnection(fake, scope), scope, "Soundcore Space Q45") { BmapLog.d("DEMO $it") }
                    }
                    "galaxy_buds2_pro" -> {
                        val fake = FakeSamsungDevice()
                        SamsungDriver(SamsungConnection(fake, scope), scope, "Galaxy Buds2 Pro") { BmapLog.d("DEMO $it") }
                    }
                    "nothing_ear" -> {
                        val fake = FakeNothingDevice()
                        NothingDriver(NothingConnection(fake, scope), scope, "Nothing Ear (a)") { BmapLog.d("DEMO $it") }
                    }
                    "jbl_tune230nc" -> {
                        val fake = FakeJblDevice()
                        JblDriver(JblConnection(fake, scope), scope, "JBL TUNE230NC TWS") { BmapLog.d("DEMO $it") }
                    }
                    else -> {
                        val profile = BoseCatalog.byId(demoDevice) ?: BoseCatalog.QC_ULTRA_2
                        val fail = activity.intent.getStringExtra("demoFail")?.split(".")?.map(String::toInt)
                        val fake = FakeBmapDevice(scope, profile).apply {
                            if (fail != null) forceError[Address(fail[0], fail[1]) to BmapOp.GET] = ErrorCodes.RUNTIME
                        }
                        BoseDriver(BmapConnection(fake, scope), scope, profile) { BmapLog.d("DEMO $it") }
                    }
                }
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
        // So the Quick Settings tile drives the demo device too. Cleared with the activity.
        AppGraph.controller.value = repository
        activity.lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY) AppGraph.controller.compareAndSet(repository, null)
            },
        )
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
