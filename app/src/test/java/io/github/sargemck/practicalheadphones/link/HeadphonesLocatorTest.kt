package io.github.sargemck.practicalheadphones.link

import io.github.sargemck.practicalheadphones.link.HeadphonesLocator.Candidate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HeadphonesLocatorTest {
    private val otherBose = Candidate("00:00:00:00:00:01", "Bose SoundLink", hasBmapUuid = true, isLeOnly = false)
    private val qcUltra = Candidate("00:00:00:00:00:02", "Bose QC Ultra Headphones", hasBmapUuid = true, isLeOnly = false)
    private val qcUltraNoUuid = Candidate("00:00:00:00:00:03", "Bose QC Ultra Headphones", hasBmapUuid = false, isLeOnly = false)
    private val qcUltraLe = Candidate("00:00:00:00:00:04", "LE-Bose QC Ultra Headphones", hasBmapUuid = true, isLeOnly = true)
    private val renamedBose = Candidate("00:00:00:00:00:05", "Desk Headphones", hasBmapUuid = true, isLeOnly = false)
    private val unrelated = Candidate("00:00:00:00:00:06", "Pixel Buds", hasBmapUuid = false, isLeOnly = false)

    @Test
    fun `prefers QC Ultra over another Bose device`() {
        assertEquals(qcUltra, HeadphonesLocator.select(listOf(otherBose, qcUltra), savedAddress = null))
    }

    @Test
    fun `prefers QC Ultra without cached UUID over another Bose device`() {
        assertEquals(qcUltraNoUuid, HeadphonesLocator.select(listOf(otherBose, qcUltraNoUuid), savedAddress = null))
    }

    @Test
    fun `prefers QC Ultra with BMAP UUID over one without`() {
        assertEquals(qcUltra, HeadphonesLocator.select(listOf(qcUltraNoUuid, qcUltra), savedAddress = null))
    }

    @Test
    fun `ignores BLE-only bonds`() {
        assertNull(HeadphonesLocator.select(listOf(qcUltraLe, unrelated), savedAddress = null))
        assertEquals(qcUltraNoUuid, HeadphonesLocator.select(listOf(qcUltraLe, qcUltraNoUuid), savedAddress = null))
        assertEquals(qcUltra, HeadphonesLocator.select(listOf(qcUltraLe, qcUltra), savedAddress = qcUltraLe.address))
    }

    @Test
    fun `falls back to renamed device with BMAP UUID`() {
        assertEquals(renamedBose, HeadphonesLocator.select(listOf(unrelated, renamedBose), savedAddress = null))
    }

    @Test
    fun `keeps saved address after rename and rediscovers if unpaired`() {
        val renamedSaved = renamedBose.copy(hasBmapUuid = false)
        assertEquals(renamedSaved, HeadphonesLocator.select(listOf(otherBose, renamedSaved), savedAddress = renamedSaved.address))
        assertEquals(qcUltra, HeadphonesLocator.select(listOf(otherBose, qcUltra), savedAddress = "00:00:00:00:00:99"))
    }

    @Test
    fun `returns null when nothing matches`() {
        assertNull(HeadphonesLocator.select(listOf(unrelated), savedAddress = null))
        assertNull(HeadphonesLocator.select(emptyList(), savedAddress = null))
    }
}
