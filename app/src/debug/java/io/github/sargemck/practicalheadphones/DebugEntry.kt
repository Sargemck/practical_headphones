package io.github.sargemck.practicalheadphones

import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import io.github.sargemck.practicalheadphones.bmap.BmapConnection
import io.github.sargemck.practicalheadphones.bmap.FakeBmapDevice
import io.github.sargemck.practicalheadphones.link.BmapLog
import io.github.sargemck.practicalheadphones.state.Connector
import io.github.sargemck.practicalheadphones.state.HeadphoneController
import io.github.sargemck.practicalheadphones.state.HeadphoneRepository
import io.github.sargemck.practicalheadphones.state.LiveUpdates
import io.github.sargemck.practicalheadphones.state.SystemLink

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
     */
    fun demoController(activity: ComponentActivity): HeadphoneController? {
        if (!activity.intent.getBooleanExtra("demo", false)) return null
        val repository = HeadphoneRepository(
            scope = activity.lifecycleScope,
            connector = Connector { scope -> BmapConnection(FakeBmapDevice(scope), scope) },
            clock = SystemClock::elapsedRealtime,
            log = { BmapLog.d("DEMO $it") },
            live = LiveUpdates(polling = false),
        )
        repository.onSystemLink(SystemLink(bluetoothOn = true, paired = true, connected = true))
        return repository
    }
}
