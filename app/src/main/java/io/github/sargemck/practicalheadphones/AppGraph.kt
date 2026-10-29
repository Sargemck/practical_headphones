package io.github.sargemck.practicalheadphones

import io.github.sargemck.practicalheadphones.state.HeadphoneController
import kotlinx.coroutines.flow.MutableStateFlow

/** Process-wide handle to the service's controller. Same process, so no binder. */
object AppGraph {
    /** Set by the service in onCreate, cleared in onDestroy. */
    val controller = MutableStateFlow<HeadphoneController?>(null)
}
