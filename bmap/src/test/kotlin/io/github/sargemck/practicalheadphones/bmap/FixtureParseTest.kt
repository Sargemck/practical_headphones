package io.github.sargemck.practicalheadphones.bmap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Every captured fixture line must parse into whole frames with no skipped bytes and
 * nothing left over. This also proves redacted fixtures still have consistent lengths.
 * Line format: `> 02 02 01 00` (sent) or `< 02 02 03 04 50 ff ff 00` (received).
 */
class FixtureParseTest {
    @Test
    fun `all fixtures parse cleanly`() {
        // Walks the source folder (tests run in the module directory), so new captures are covered too.
        val files = File("src/testFixtures/resources/fixtures").walkTopDown()
            .filter { it.isFile && it.extension == "txt" }.toList()
        assertTrue(files.size >= Fixtures.NAMES.size, "fixtures not found")

        for (file in files) {
            val lines = file.readLines().filter { it.startsWith(">") || it.startsWith("<") }
            assertTrue(lines.isNotEmpty(), "${file.name} has no frames")
            for (line in lines) {
                val parser = FrameParser()
                val events = parser.feed(Hex.parse(line.drop(1)))
                assertTrue(events.none { it is FrameEvent.Skipped }, "${file.name}: skipped bytes in \"$line\"")
                assertTrue(events.isNotEmpty(), "${file.name}: no frame in \"$line\"")
                assertEquals(0, parser.buffered, "${file.name}: leftover bytes in \"$line\"")
            }
        }
    }
}
