package io.github.sargemck.practicalheadphones.link

import android.os.SystemClock
import android.util.Log
import io.github.sargemck.practicalheadphones.BuildConfig
import io.github.sargemck.practicalheadphones.bmap.FrameLogger
import io.github.sargemck.practicalheadphones.bmap.LogEvent
import java.util.Locale

/** Logcat tag `BMAP`, lines prefixed with elapsedRealtime in ms. Frames only in debug builds. */
object BmapLog {
    private const val TAG = "BMAP"

    fun d(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, line(message))
    }

    fun e(message: String) {
        Log.e(TAG, line(message))
    }

    val frames = FrameLogger { event ->
        when (event) {
            is LogEvent.Closed -> d("CLOSED ${event.reason}")
            is LogEvent.Skipped -> e("SKIPPED $event")
            is LogEvent.In -> d("IN  ${event.frame}")
            is LogEvent.Out -> d("OUT ${event.frame}")
        }
    }

    private fun line(message: String) =
        String.format(Locale.ROOT, "%.3f %s", SystemClock.elapsedRealtimeNanos() / 1_000_000.0, message)
}
