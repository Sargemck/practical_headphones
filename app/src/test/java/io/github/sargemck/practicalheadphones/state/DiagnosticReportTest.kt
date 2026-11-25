package io.github.sargemck.practicalheadphones.state

import io.github.sargemck.practicalheadphones.bmap.ModeConfig
import io.github.sargemck.practicalheadphones.bmap.Spatial
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiagnosticReportTest {
    @Test
    fun `diagnostic report includes model capabilities and redacts custom names and MAC addresses`() {
        val state = HeadphoneState(
            link = LinkState.Connected,
            name = Section.Loaded("Alice's Secret Headphones"),
            firmware = Section.Loaded("10.12.12-AA:BB:CC:DD:EE:FF"),
            battery = Section.Loaded(82),
            modes = Section.Loaded(
                ModesSection(
                    modes = listOf(
                        ModeConfig(4, 0, 0, editable = true, configured = true, "Secret Office Mode", 2, false, Spatial.OFF, false, true),
                    ),
                    current = 4,
                    favourites = null,
                ),
            ),
            deviceBadge = DeviceBadge("Sony", "Alice's WH-1000XM5", experimental = true),
            availableDevices = listOf(
                PairedHeadphoneChoice("11:22:33:44:55:66", "Alice's Earbuds", "Sony", selected = true),
            ),
        )

        val report = DiagnosticReport.build(state, appVersion = "1.1.0", sdkInt = 37)

        assertTrue("WH-1000XM5" in report)
        assertTrue("Sony" in report)
        assertTrue("experimental" in report)
        assertFalse("Alice" in report, "Personal Bluetooth alias leaked: $report")
        assertFalse("Secret Office" in report, "Custom mode name leaked: $report")
        assertFalse("11:22:33:44:55:66" in report, "Paired MAC leaked: $report")
        assertFalse("AA:BB:CC:DD:EE:FF" in report, "Firmware MAC leaked: $report")
    }
}
