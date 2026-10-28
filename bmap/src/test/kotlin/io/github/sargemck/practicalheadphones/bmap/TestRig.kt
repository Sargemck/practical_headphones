package io.github.sargemck.practicalheadphones.bmap

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/** A fake device, a connection to it, and everything the connection reports. */
class TestRig(
    val fake: FakeBmapDevice,
    val connection: BmapConnection,
    val headphones: Headphones,
    val unsolicited: List<BmapFrame>,
    val log: List<LogEvent>,
)

@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.rig(configure: FakeBmapDevice.() -> Unit = {}): TestRig {
    val fake = FakeBmapDevice(backgroundScope).apply(configure)
    val log = mutableListOf<LogEvent>()
    val connection = BmapConnection(fake, backgroundScope, logger = { log += it })
    val unsolicited = mutableListOf<BmapFrame>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { connection.unsolicited.toList(unsolicited) }
    return TestRig(fake, connection, Headphones(connection), unsolicited, log)
}

/** Runs [block] in the background and returns a holder for its outcome. */
fun <T> TestScope.background(block: suspend () -> T): () -> Result<T>? {
    var outcome: Result<T>? = null
    backgroundScope.launch { outcome = runCatching { block() } }
    return { outcome }
}
