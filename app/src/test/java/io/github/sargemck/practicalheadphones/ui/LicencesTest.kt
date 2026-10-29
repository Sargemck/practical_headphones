package io.github.sargemck.practicalheadphones.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

class LicencesTest {
    // Gradle runs unit tests from the module directory.
    private fun root(name: String) = File("../$name").readText().replace("\r\n", "\n")

    @Test
    fun `About shows the same LICENSE and NOTICE as the repo`() {
        assertEquals(root("LICENSE"), Licences.LICENSE)
        assertEquals(root("NOTICE"), Licences.NOTICE)
    }
}
