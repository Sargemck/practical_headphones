package io.github.sargemck.practicalheadphones

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.ZonedDateTime
import java.util.Locale

/** M0 spike log: logcat tag BMAP, each line prefixed with elapsedRealtime in ms. */
object SpikeLog {
    private const val TAG = "BMAP"
    private const val MAX_LINES = 400

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun nowMs(): Double = SystemClock.elapsedRealtimeNanos() / 1_000_000.0

    fun ms(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

    fun log(message: String) {
        val line = String.format(Locale.ROOT, "%.3f %s", nowMs(), message)
        Log.i(TAG, line)
        _lines.update { (it + line).takeLast(MAX_LINES) }
    }

    /** Wall clock and elapsedRealtime together, to line our timestamps up with Bluetooth's. */
    fun clock() = log("CLOCK wall=${ZonedDateTime.now()} elapsedRealtimeMs=${String.format(Locale.ROOT, "%.3f", nowMs())}")
}
