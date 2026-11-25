package io.github.sargemck.practicalheadphones.link

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HeadphonesLocatorTest {
    @Test
    fun `ignores LE-only bond and picks classic QC Ultra`() {
        val le = HeadphonesLocator.Candidate("AA:BB:CC:DD:EE:01", "LE-Bose QC Ultra Headphones", hasBmapUuid = false, isLeOnly = true)
        val classic = HeadphonesLocator.Candidate("AA:BB:CC:DD:EE:02", "Bose QC Ultra Headphones", hasBmapUuid = true, isLeOnly = false)
        assertEquals(classic, HeadphonesLocator.select(listOf(le, classic), savedAddress = null))
    }

    @Test
    fun `prefers saved address when still classic`() {
        val renamed = HeadphonesLocator.Candidate("AA:BB:CC:DD:EE:01", "Desk pair", hasBmapUuid = true, isLeOnly = false)
        val other = HeadphonesLocator.Candidate("AA:BB:CC:DD:EE:02", "Bose QC Ultra Headphones", hasBmapUuid = true, isLeOnly = false)
        assertEquals(renamed, HeadphonesLocator.select(listOf(other, renamed), savedAddress = renamed.address))
    }

    @Test
    fun `ignores saved address if it became LE-only`() {
        val le = HeadphonesLocator.Candidate("AA:BB:CC:DD:EE:01", "LE-Bose QC Ultra Headphones", hasBmapUuid = false, isLeOnly = true)
        val classic = HeadphonesLocator.Candidate("AA:BB:CC:DD:EE:02", "Bose QC Ultra Headphones", hasBmapUuid = true, isLeOnly = false)
        assertEquals(classic, HeadphonesLocator.select(listOf(le, classic), savedAddress = le.address))
    }

    @Test
    fun `prefers QC Ultra with BMAP UUID over QC Ultra without cached UUID`() {
        val noUuid = HeadphonesLocator.Candidate("AA:BB:CC:DD:EE:01", "Bose QC Ultra", hasBmapUuid = false, isLeOnly = false)
        val withUuid = HeadphonesLocator.Candidate("AA:BB:CC:DD:EE:02", "Bose QC Ultra", hasBmapUuid = true, isLeOnly = false)
        assertEquals(withUuid, HeadphonesLocator.select(listOf(noUuid, withUuid), savedAddress = null))
    }

    @Test
    fun `falls back to QC Ultra by name when UUIDs not yet cached`() {
        val noUuid = HeadphonesLocator.Candidate("AA:BB:CC:DD:EE:01", "Bose QC Ultra Headphones", hasBmapUuid = false, isLeOnly = false)
        assertEquals(noUuid, HeadphonesLocator.select(listOf(noUuid), savedAddress = null))
    }

    @Test
    fun `falls back to any classic device with BMAP UUID when renamed`() {
        val renamed = HeadphonesLocator.Candidate("AA:BB:CC:DD:EE:01", "My Headphones", hasBmapUuid = true, isLeOnly = false)
        assertEquals(renamed, HeadphonesLocator.select(listOf(renamed), savedAddress = null))
    }

    @Test
    fun `returns null when only unrelated or LE-only devices are bonded`() {
        val le = HeadphonesLocator.Candidate("AA:BB:CC:DD:EE:01", "LE-Bose QC Ultra", hasBmapUuid = false, isLeOnly = true)
        val car = HeadphonesLocator.Candidate("AA:BB:CC:DD:EE:02", "Car Audio", hasBmapUuid = false, isLeOnly = false)
        assertNull(HeadphonesLocator.select(listOf(le, car), savedAddress = null))
    }

    @Test
    fun `detects Bose Sony Soundcore and Samsung brands by UUID or name`() {
        val sony = HeadphonesLocator.Candidate("11:22:33:44:55:01", "WH-1000XM5", hasBmapUuid = false, isLeOnly = false)
        val soundcore = HeadphonesLocator.Candidate("11:22:33:44:55:02", "Soundcore Space Q45", hasBmapUuid = false, isLeOnly = false)
        val samsung = HeadphonesLocator.Candidate("11:22:33:44:55:03", "Galaxy Buds2 Pro", hasBmapUuid = false, isLeOnly = false)
        val bose45 = HeadphonesLocator.Candidate("11:22:33:44:55:04", "Bose QC45", hasBmapUuid = false, isLeOnly = false)

        assertEquals(HeadphoneBrand.SONY, HeadphonesLocator.detectBrand(sony))
        assertEquals(HeadphoneBrand.SOUNDCORE, HeadphonesLocator.detectBrand(soundcore))
        assertEquals(HeadphoneBrand.SAMSUNG, HeadphonesLocator.detectBrand(samsung))
        assertEquals(HeadphoneBrand.BOSE, HeadphonesLocator.detectBrand(bose45))
        assertEquals(sony, HeadphonesLocator.select(listOf(sony), savedAddress = null))
    }

    @Test
    fun `auto-switches to actively connected supported headset when saved headset is offline`() {
        val deskBose = HeadphonesLocator.Candidate("AA:BB:CC:DD:EE:01", "Bose QC Ultra", hasBmapUuid = true, isLeOnly = false)
        val pocketSony = HeadphonesLocator.Candidate("11:22:33:44:55:01", "WH-1000XM5", hasBmapUuid = false, isLeOnly = false)

        // When neither is connected, keep the saved Bose
        assertEquals(deskBose, HeadphonesLocator.select(listOf(deskBose, pocketSony), savedAddress = deskBose.address, connectedAddresses = emptySet()))

        // When Bose is offline in bag and Sony is actively connected, auto-switch to Sony
        assertEquals(
            pocketSony,
            HeadphonesLocator.select(listOf(deskBose, pocketSony), savedAddress = deskBose.address, connectedAddresses = setOf(pocketSony.address)),
        )

        // When BOTH are connected simultaneously, honour the user's saved selection
        assertEquals(
            deskBose,
            HeadphonesLocator.select(listOf(deskBose, pocketSony), savedAddress = deskBose.address, connectedAddresses = setOf(deskBose.address, pocketSony.address)),
        )
    }
}
