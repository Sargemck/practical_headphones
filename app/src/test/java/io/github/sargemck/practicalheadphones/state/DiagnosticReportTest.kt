package io.github.sargemck.practicalheadphones.state

import io.github.sargemck.practicalheadphones.bmap.ModeConfig
import io.github.sargemck.practicalheadphones.bmap.Spatial
import org.junit.jupiter.api.Assertions.assertEquals
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

    @Test
    fun `diagnostics never copy free text from a Bluetooth name or error`() {
        for (alias in listOf("JBL Tune Sam", "JBL Wave Buds 2 - Sam", "LinkBuds Sam")) {
            val state = HeadphoneState(
                deviceBadge = DeviceBadge("JBL", alias, experimental = true),
                firmware = Section.Loaded("Sam's firmware"),
                battery = Section.Failed("Sam's headphones disconnected"),
            )
            val report = DiagnosticReport.build(state, appVersion = "1.1.0", sdkInt = 37)
            assertFalse("Sam" in report)
        }
        assertEquals("unknown model", DiagnosticReport.sanitizeModel("JBL Tune Sam"))
        assertEquals("JBL Wave Buds 2", DiagnosticReport.sanitizeModel("JBL Wave Buds 2 - Sam"))
    }

    @Test
    fun `firmware keeps the leading version and drops the rest`() {
        assertEquals("10.12.12", DiagnosticReport.sanitizeFirmware("10.12.12-AA:BB:CC:DD:EE:FF"))
        assertEquals("01.59", DiagnosticReport.sanitizeFirmware("01.59"))
        assertEquals("1.2.3.4", DiagnosticReport.sanitizeFirmware("  1.2.3.4 Sam  "))
        assertEquals("10.12.12+gabcdef0", DiagnosticReport.sanitizeFirmware("10.12.12+gabcdef0-dirty"))
        assertEquals("present", DiagnosticReport.sanitizeFirmware("Sam's firmware"))
        assertEquals("present", DiagnosticReport.sanitizeFirmware("v1.2"))
        assertEquals("present", DiagnosticReport.sanitizeFirmware("12"))

        val report = DiagnosticReport.build(
            HeadphoneState(firmware = Section.Loaded("10.12.12-AA:BB:CC:DD:EE:FF")),
            appVersion = "1.1.0",
            sdkInt = 37,
        )
        assertTrue("- Firmware: loaded:10.12.12\n" in report, report)
    }

    @Test
    fun `fixed driver failure strings are kept`() {
        for (msg in listOf("read failed", "Couldn't read battery", "Couldn't read earbuds status", "Couldn't read listening mode")) {
            assertEquals("failed($msg)", DiagnosticReport.sanitizeFailure(msg))
        }
        // Exact match only.
        assertEquals("failed", DiagnosticReport.sanitizeFailure("Couldn't read battery for Sam"))
        assertEquals("failed", DiagnosticReport.sanitizeFailure("read failed "))
    }

    @Test
    fun `bose failures keep address and error type only`() {
        val cases = mapOf(
            "[1.3] ERROR OpNotSupp(auth)" to "failed([1.3] ERROR OpNotSupp(auth))",
            "[31.4] ERROR FuncNotSupp" to "failed([31.4] ERROR FuncNotSupp)",
            "[1.2] ERROR code 99" to "failed([1.2] ERROR code 99)",
            "[1.2] no reply" to "failed([1.2] no reply)",
            "[31.4] no reply while the link answers: unsupported" to "failed([31.4] no reply while the link answers: unsupported)",
            "[1.2] malformed payload 53 61 6D: short" to "failed([1.2] malformed payload)",
            "[1.2] malformed payload : null" to "failed([1.2] malformed payload)",
        )
        for ((msg, expected) in cases) assertEquals(expected, DiagnosticReport.sanitizeFailure(msg), msg)
    }

    @Test
    fun `other failures become plain failed`() {
        for (msg in listOf(
            "Sam's headphones disconnected",
            "disconnected: IoError(Sam)",
            "refused to send frame",
            "",
            "[1234.2] no reply",
            "1.2 no reply",
        )) {
            assertEquals("failed", DiagnosticReport.sanitizeFailure(msg), msg)
        }
    }

    @Test
    fun `failed section in report shows sanitized message`() {
        val state = HeadphoneState(
            battery = Section.Failed("Couldn't read battery"),
            modes = Section.Failed("[1.2] malformed payload 53 61 6D: short"),
            eq = Section.Failed("Sam's headphones disconnected"),
        )
        val report = DiagnosticReport.build(state, appVersion = "1.1.0", sdkInt = 37)
        assertTrue("battery=failed(Couldn't read battery)" in report, report)
        assertTrue("modes=failed([1.2] malformed payload)" in report, report)
        assertTrue("eq=failed," in report, report)
        assertFalse("53 61 6D" in report, report)
        assertFalse("Sam" in report, report)
    }
}
