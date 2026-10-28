package io.github.sargemck.practicalheadphones.bmap

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/** The fake answers every captured request with exactly the captured reply. */
class FakeFixtureTest {
    @Test
    fun `fake replies match the fixtures byte for byte`() = runTest {
        for (exchange in Fixtures.all()) {
            val fake = FakeBmapDevice(backgroundScope)
            fake.write(exchange.request.encode())
            val reply = withTimeoutOrNull(5.seconds) { fake.read() }
            if (exchange.reply == null) {
                assertNull(reply, exchange.name)
            } else {
                assertEquals(Hex.format(exchange.reply!!), reply?.let(Hex::format), exchange.name)
            }
        }
    }
}
