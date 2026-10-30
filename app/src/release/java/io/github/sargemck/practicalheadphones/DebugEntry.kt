package io.github.sargemck.practicalheadphones

import androidx.activity.ComponentActivity
import io.github.sargemck.practicalheadphones.state.HeadphoneController

/** Release builds: no window flags, no demo mode, no fake device. */
object DebugEntry {
    @Suppress("UNUSED_PARAMETER")
    fun onCreate(activity: ComponentActivity) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun demoController(activity: ComponentActivity): HeadphoneController? = null
}
